"""Emrys's own access to the user's mailbox, over IMAP and SMTP.

Configured from ``EMRYS_EMAIL_*`` (``EmailAccount.from_env``). Without an address and password no tool is
registered, and Emrys keeps to email previews in notifications and ``email_draft`` on the phone.

Every connection is TLS: IMAPS on 993, and SMTP over TLS on 465 or STARTTLS on 587. Any other port is refused,
and a 587 server that offers no STARTTLS gets nothing, so neither the password nor mail crosses the network in
plaintext. Searching and reading use ``EXAMINE`` and ``BODY.PEEK``, so they never change a message's flags;
marking read is its own approved action, and nothing here deletes mail. Each call opens its own connection in a
worker thread under a deadline, so a slow mail server never blocks the event loop.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import imaplib
import json
import logging
import os
import re
import smtplib
import ssl
import time
from collections.abc import Awaitable, Callable, Iterator, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date, timedelta
from email import message_from_bytes
from email.errors import HeaderParseError
from email.header import decode_header
from email.message import EmailMessage, Message
from email.policy import SMTP as SMTP_POLICY
from email.utils import formataddr, formatdate, getaddresses, make_msgid, parsedate_to_datetime
from html.parser import HTMLParser
from typing import Any

from strands.tools import PythonAgentTool

from .alerts import alert_for
from .approvals import Tier, ToolPolicy
from .rules import Standing

logger = logging.getLogger(__name__)

IMAPS_PORT = 993
SMTP_STARTTLS = {465: False, 587: True}
"""The SMTP ports Emrys uses, and whether each starts plain and upgrades with STARTTLS (587) or is TLS from the
first byte (465)."""

PROVIDERS: dict[str, tuple[str, str, int]] = {
    "gmail.com": ("imap.gmail.com", "smtp.gmail.com", 465),
    "googlemail.com": ("imap.gmail.com", "smtp.gmail.com", 465),
    "icloud.com": ("imap.mail.me.com", "smtp.mail.me.com", 587),
    "me.com": ("imap.mail.me.com", "smtp.mail.me.com", 587),
    "mac.com": ("imap.mail.me.com", "smtp.mail.me.com", 587),
    "fastmail.com": ("imap.fastmail.com", "smtp.fastmail.com", 465),
}
"""IMAP host, SMTP host and SMTP port for addresses at these domains, when the hosts are not set."""

IO_TIMEOUT = 15.0
READ_TIMEOUT = 30.0
SEND_TIMEOUT = 45.0
"""Socket timeout, then deadlines for one IMAP exchange and one SMTP hand-off. A reply makes all three in turn and
still finishes inside the supervisor's default tool limit."""

SEARCH_LIMIT = 50
SNIPPET_CHARS = 160
SNIPPET_BYTES = 8192
BODY_CHARS = 8000
READ_BYTES = 10 * 1024 * 1024
MARK_ACTIONS = ("read", "unread", "archive")

LOGIN_REFUSED = (
    "The mail server refused the login. Check EMRYS_EMAIL_ADDRESS, and that EMRYS_EMAIL_PASSWORD is an app "
    "password (Gmail and iCloud require one)."
)
UNCERTAIN = (
    "The mail server did not finish in time, so the email may or may not have gone out. Check Sent before "
    "sending it again."
)

_ADDRESS = re.compile(r"[^@\s<>()\[\],;:\"]+@[^@\s<>()\[\],;:\"]+\.[^@\s<>()\[\],;:\"]+")
_CONTROL = re.compile(r"[\r\n\0]")
_INVISIBLE = re.compile("[\u00ad\u034f\u200b-\u200f\u2060\ufeff]")
_REPLY_PREFIX = re.compile(r"^\s*re\s*:", re.IGNORECASE)
_MESSAGE_ID = re.compile(r"<[^<>\s]+>")
_QUERY_TERM = re.compile(r'(?:(\w+):)?(?:"([^"]*)"|(\S+))')
_SEARCH_KEYS = {"from": "FROM", "to": "TO", "cc": "CC", "subject": "SUBJECT"}
_MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

SEARCH_ITEMS = (
    "(UID FLAGS BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT DATE CONTENT-TYPE CONTENT-TRANSFER-ENCODING)] "
    f"BODY.PEEK[TEXT]<0.{SNIPPET_BYTES}>)"
)
READ_ITEMS = f"(UID FLAGS RFC822.SIZE BODY.PEEK[]<0.{READ_BYTES}>)"
REPLY_ITEMS = "(UID BODY.PEEK[HEADER.FIELDS (FROM REPLY-TO TO CC SUBJECT MESSAGE-ID REFERENCES IN-REPLY-TO)])"


class EmailError(RuntimeError):
    """A request Emrys refuses or the mail server could not satisfy; the message is written for the model."""


@dataclass(frozen=True)
class EmailAccount:
    """The user's mailbox: their address, an app password, and the TLS servers Emrys reaches it on."""

    address: str
    password: str = field(repr=False)
    imap_host: str
    smtp_host: str
    imap_port: int = IMAPS_PORT
    smtp_port: int = 465
    smtp_starttls: bool = False

    @property
    def domain(self) -> str:
        """The address's domain, used for Message-IDs."""
        return self.address.rpartition("@")[2]

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> EmailAccount | None:
        """The account from ``EMRYS_EMAIL_*``, or ``None`` when the address or password is unset.

        Raises:
            ValueError: The address is malformed, a port would not use TLS, or the servers are unset for a
                domain outside ``PROVIDERS``.
        """
        env = os.environ if environ is None else environ
        address = env.get("EMRYS_EMAIL_ADDRESS", "").strip()
        password = env.get("EMRYS_EMAIL_PASSWORD", "")
        if not address or not password:
            return None
        if not _ADDRESS.fullmatch(address):
            raise ValueError("EMRYS_EMAIL_ADDRESS is not an email address")
        domain = address.rpartition("@")[2].lower()
        imap_default, smtp_default, port_default = PROVIDERS.get(domain, ("", "", 465))
        imap_host = env.get("EMRYS_EMAIL_IMAP_HOST", "").strip() or imap_default
        smtp_host = env.get("EMRYS_EMAIL_SMTP_HOST", "").strip() or smtp_default
        if not imap_host or not smtp_host:
            raise ValueError(f"set EMRYS_EMAIL_IMAP_HOST and EMRYS_EMAIL_SMTP_HOST; there are no defaults for {domain}")
        imap_port = _port(env, "EMRYS_EMAIL_IMAP_PORT", IMAPS_PORT)
        smtp_port = _port(env, "EMRYS_EMAIL_SMTP_PORT", port_default)
        if imap_port != IMAPS_PORT:
            raise ValueError(f"EMRYS_EMAIL_IMAP_PORT must be {IMAPS_PORT} (IMAP over TLS); Emrys never uses plaintext")
        if smtp_port not in SMTP_STARTTLS:
            raise ValueError("EMRYS_EMAIL_SMTP_PORT must be 465 (TLS) or 587 (STARTTLS); Emrys never uses plaintext")
        return cls(address, password, imap_host, smtp_host, imap_port, smtp_port, SMTP_STARTTLS[smtp_port])


def _port(env: Mapping[str, str], name: str, default: int) -> int:
    raw = env.get(name, "").strip()
    if not raw:
        return default
    if not raw.isdigit():
        raise ValueError(f"{name} must be a port number")
    return int(raw)


class Mailbox:
    """Blocking IMAP and SMTP calls on one account; each opens and closes its own connection.

    Args:
        account: The mailbox to reach.
        tls: Certificate checks for both servers; the system's trusted CAs by default.
        timeout: Socket timeout for each network operation, in seconds.
    """

    def __init__(
        self, account: EmailAccount, *, tls: ssl.SSLContext | None = None, timeout: float = IO_TIMEOUT
    ) -> None:
        self.account = account
        self.tls = tls or ssl.create_default_context()
        self.timeout = timeout
        plain = f"\0{account.address}\0{account.password}".encode()
        # The password as a server could echo it: raw, or as SMTP AUTH LOGIN and AUTH PLAIN encode it.
        forms = {
            account.password,
            base64.b64encode(account.password.encode()).decode(),
            base64.b64encode(plain).decode(),
        }
        self._secrets = sorted(forms, key=len, reverse=True)

    def scrub(self, text: str) -> str:
        """``text`` with every form of the password masked."""
        for secret in self._secrets:
            text = text.replace(secret, "***")
        return text

    @contextlib.contextmanager
    def _imap(self, *, utf8: bool = False) -> Iterator[imaplib.IMAP4_SSL]:
        account = self.account
        conn = imaplib.IMAP4_SSL(account.imap_host, account.imap_port, ssl_context=self.tls, timeout=self.timeout)
        try:
            try:
                conn.login(account.address, account.password)
            except imaplib.IMAP4.abort:  # a dropped connection, not a refused login
                raise
            except imaplib.IMAP4.error:
                raise EmailError(LOGIN_REFUSED) from None
            conn.capabilities = _capabilities(conn)
            if utf8:
                if "ENABLE" not in conn.capabilities or "UTF8=ACCEPT" not in conn.capabilities:
                    raise EmailError("This mail server only accepts plain ASCII in searches and folder names.")
                conn.enable("UTF8=ACCEPT")
            yield conn
        finally:
            with contextlib.suppress(Exception):
                conn.logout()

    @contextlib.contextmanager
    def _smtp(self) -> Iterator[smtplib.SMTP]:
        account = self.account
        smtp: smtplib.SMTP
        if account.smtp_starttls:
            smtp = smtplib.SMTP(account.smtp_host, account.smtp_port, timeout=self.timeout)
        else:
            smtp = smtplib.SMTP_SSL(account.smtp_host, account.smtp_port, timeout=self.timeout, context=self.tls)
        try:
            if account.smtp_starttls:
                try:
                    smtp.starttls(context=self.tls)
                except smtplib.SMTPNotSupportedError:
                    raise EmailError("The SMTP server offered no STARTTLS, so nothing was sent.") from None
            try:
                smtp.login(account.address, account.password)
            except smtplib.SMTPAuthenticationError:
                raise EmailError(LOGIN_REFUSED) from None
            yield smtp
        finally:
            with contextlib.suppress(Exception):
                smtp.quit()
            smtp.close()

    def search(self, query: str, folder: str, unread_only: bool, since_days: int | None, limit: int) -> dict[str, Any]:
        """Messages matching ``query`` in ``folder``, newest first, with a snippet each."""
        since = date.today() - timedelta(days=since_days) if since_days else None
        criteria = search_criteria(query, unread_only=unread_only, since=since)
        with self._imap(utf8=not (query + folder).isascii()) as conn:
            _open(conn, folder, writable=False)
            typ, data = conn.uid("SEARCH", *criteria)
            if typ != "OK":
                raise EmailError("The mail server could not run that search.")
            uids = sorted({int(uid) for line in data if line for uid in line.split() if uid.isdigit()})
            newest = uids[-limit:][::-1]
            fetched = _fetch(conn, ",".join(map(str, newest)), SEARCH_ITEMS) if newest else {}
        messages = [_summary(uid, fetched[uid]) for uid in newest if uid in fetched]
        return {"folder": folder, "matched": len(uids), "messages": messages}

    def read(self, uid: int, folder: str) -> dict[str, Any]:
        """One message's headers, text body and attachment list."""
        with self._imap(utf8=not folder.isascii()) as conn:
            _open(conn, folder, writable=False)
            items = _fetch(conn, str(uid), READ_ITEMS).get(uid)
        if items is None:
            raise EmailError(f"There is no email with uid {uid} in {folder}; search again for its uid.")
        raw = _section(items, "BODY[]")
        message = message_from_bytes(raw)
        text, attachments = _content(message)
        body, truncated = _truncate(text, BODY_CHARS)
        result: dict[str, Any] = {
            "uid": uid,
            "folder": folder,
            "from": _decoded(message.get("From")),
            "reply_to": _decoded(message.get("Reply-To")),
            "to": _decoded(message.get("To")),
            "cc": _decoded(message.get("Cc")),
            "subject": _decoded(message.get("Subject")),
            "date": _date(message),
            "unread": not _seen(items),
            "body": body,
            "truncated": truncated,
            "attachments": attachments,
        }
        size = items.get("RFC822.SIZE")
        if isinstance(size, bytes) and size.isdigit() and int(size) > len(raw):
            result["note"] = "Only the start of this very large email was loaded; later attachments are not listed."
        return result

    def original(self, uid: int, folder: str) -> Message:
        """The headers a reply to ``uid`` is built from."""
        with self._imap(utf8=not folder.isascii()) as conn:
            _open(conn, folder, writable=False)
            items = _fetch(conn, str(uid), REPLY_ITEMS).get(uid)
        if items is None:
            raise EmailError(f"There is no email with uid {uid} in {folder}; search again for its uid.")
        return message_from_bytes(_section(items, "BODY[HEADER"))

    def new_message(
        self,
        to: Sequence[tuple[str, str]],
        cc: Sequence[tuple[str, str]],
        subject: str,
        body: str,
        *,
        in_reply_to: str = "",
        references: str = "",
    ) -> EmailMessage:
        """A plain-text message from the account's address."""
        message = EmailMessage()
        message["From"] = self.account.address
        message["To"] = ", ".join(map(formataddr, to))
        if cc:
            message["Cc"] = ", ".join(map(formataddr, cc))
        message["Subject"] = " ".join(subject.split())
        message["Date"] = formatdate(localtime=True)
        message["Message-ID"] = make_msgid(domain=self.account.domain)
        if in_reply_to:
            message["In-Reply-To"] = in_reply_to
            message["References"] = references
        message.set_content(body)
        return message

    def reply_message(
        self, original: Message, to: Any, body: str, *, reply_all: bool
    ) -> tuple[EmailMessage, list[str]]:
        """A reply threaded under ``original``, and its recipients.

        ``to`` must name only addresses replies to ``original`` go to (its Reply-To, else its From), so a mixed-up
        uid cannot send the reply to someone else. ``reply_all`` copies everyone else it was sent to.
        """
        targets = _pairs(original.get_all("Reply-To") or []) or _pairs(original.get_all("From") or [])
        expected = {address.lower() for _, address in targets}
        chosen = _recipients("to", to, required=True)
        if not expected or any(address.lower() not in expected for _, address in chosen):
            where = ", ".join(sorted(expected)) or "no address Emrys can read"
            raise EmailError(f"Replies to this email go to {where}. Call email_reply again with that as `to`.")
        cc: list[tuple[str, str]] = []
        if reply_all:
            skip = {self.account.address.lower(), *(address.lower() for _, address in chosen)}
            for name, address in _pairs([*(original.get_all("To") or []), *(original.get_all("Cc") or [])]):
                if address.lower() not in skip:
                    skip.add(address.lower())
                    cc.append((name, address))
        subject = _decoded(original.get("Subject"))
        if not _REPLY_PREFIX.match(subject):
            subject = f"Re: {subject}".rstrip()
        parent = next(iter(_MESSAGE_ID.findall(str(original.get("Message-ID") or ""))), "")
        chain = _MESSAGE_ID.findall(str(original.get("References") or "")) or _MESSAGE_ID.findall(
            str(original.get("In-Reply-To") or "")
        )
        references = " ".join([*chain, parent] if parent and parent not in chain else chain)
        message = self.new_message(chosen, cc, subject, body, in_reply_to=parent, references=references)
        return message, [address for _, address in [*chosen, *cc]]

    def deliver(self, message: EmailMessage, to: Sequence[str]) -> list[str]:
        """Hand ``message`` to the SMTP server; returns any recipients it refused (it raises if it refused all)."""
        with self._smtp() as smtp:
            refused = smtp.send_message(message, from_addr=self.account.address, to_addrs=list(to))
        return sorted(refused)

    def save_sent(self, message: EmailMessage) -> str | None:
        """File ``message`` in the folder the server marks ``\\Sent``; returns that folder, or None without one."""
        with self._imap() as conn:
            folder = _special_folder(conn, "\\sent")
            if folder is None:
                return None
            result: Any = conn.append(_quote(folder), "(\\Seen)", time.time(), message.as_bytes(policy=SMTP_POLICY))
        return folder if result[0] == "OK" else None

    def mark(self, uid: int, action: str, folder: str) -> dict[str, Any]:
        """Mark ``uid`` read or unread, or move it to the archive folder."""
        with self._imap(utf8=not folder.isascii()) as conn:
            _open(conn, folder, writable=True)
            if uid not in _fetch(conn, str(uid), "(UID FLAGS)"):
                raise EmailError(f"There is no email with uid {uid} in {folder}; search again for its uid.")
            if action != "archive":
                change = "+FLAGS.SILENT" if action == "read" else "-FLAGS.SILENT"
                _ok(conn.uid("STORE", str(uid), change, "(\\Seen)"), "change that email's flags")
                return {"uid": uid, "folder": folder, "marked": action}
            archive = _special_folder(conn, "\\archive", "\\all")
            if archive is None:
                raise EmailError("This mailbox has no archive folder, so the email stayed where it is.")
            if archive == folder:
                raise EmailError("That email is already in the archive.")
            _move(conn, uid, archive)
        return {"uid": uid, "folder": folder, "archived_to": archive}


def search_criteria(query: str, *, unread_only: bool = False, since: date | None = None) -> list[str]:
    """IMAP SEARCH keys: ``from:``, ``to:``, ``cc:`` and ``subject:`` terms as such, other words as TEXT."""
    criteria: list[str] = []
    for match in _QUERY_TERM.finditer(query):
        key, phrase, word = match.groups()
        value = phrase if phrase is not None else word
        name = _SEARCH_KEYS.get((key or "").lower())
        if key and name is None:
            value = f"{key}:{value}"
        value = " ".join(_CONTROL.sub(" ", value).split())
        if value:
            criteria += [name or "TEXT", _quote(value)]
    if unread_only:
        criteria.append("UNSEEN")
    if since is not None:
        criteria += ["SINCE", f"{since.day:02d}-{_MONTHS[since.month - 1]}-{since.year}"]
    return criteria or ["ALL"]


def _quote(text: str) -> str:
    # imaplib quotes nothing itself on older Pythons and leaves an already-quoted argument alone on newer ones.
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _capabilities(conn: imaplib.IMAP4) -> tuple[str, ...]:
    """Capabilities as of now (servers announce more after login than in their greeting)."""
    typ, data = conn.capability()
    line = data[-1] if typ == "OK" and data else None
    return tuple(line.decode("ascii", "replace").upper().split()) if isinstance(line, bytes) else conn.capabilities


def _open(conn: imaplib.IMAP4, folder: str, *, writable: bool) -> None:
    typ, _ = conn.select(_quote(folder), readonly=not writable)
    if typ != "OK":
        raise EmailError(f"There is no folder named {folder!r}.")


def _ok(response: tuple[str, Any], what: str) -> None:
    if response[0] != "OK":
        raise EmailError(f"The mail server would not {what}.")


def _fetch(conn: imaplib.IMAP4, uids: str, items: str) -> dict[int, dict[str, Any]]:
    typ, data = conn.uid("FETCH", uids, items)
    if typ != "OK":
        raise EmailError("The mail server could not fetch that email.")
    return _fetched(data)


def _move(conn: imaplib.IMAP4, uid: int, target: str) -> None:
    """Move one message. Without MOVE it is copied first and only that message is expunged (UIDPLUS); a plain
    EXPUNGE would also purge anything else flagged deleted, so without UIDPLUS nothing is moved."""
    if "MOVE" in conn.capabilities:
        _ok(conn.uid("MOVE", str(uid), _quote(target)), f"move that email to {target}")
        return
    if "UIDPLUS" not in conn.capabilities:
        raise EmailError("This mail server cannot move one email without risking others, so nothing was moved.")
    _ok(conn.uid("COPY", str(uid), _quote(target)), f"copy that email to {target}")
    _ok(conn.uid("STORE", str(uid), "+FLAGS.SILENT", "(\\Deleted)"), "finish moving that email")
    _ok(conn.uid("EXPUNGE", str(uid)), "finish moving that email")


def _special_folder(conn: imaplib.IMAP4, *uses: str) -> str | None:
    """The first folder carrying one of these special-use attributes (RFC 6154), in order of preference."""
    typ, data = conn.list()
    if typ != "OK":
        return None
    folders = []
    for response in _responses(data):
        values = _tree(_tokens(response))
        if len(values) >= 3 and isinstance(values[0], list) and isinstance(values[2], bytes):
            attributes = {a.decode("ascii", "replace").lower() for a in values[0] if isinstance(a, bytes)}
            folders.append((attributes, values[2].decode("utf-8", "replace")))
    for use in uses:
        for attributes, name in folders:
            if use in attributes and "\\noselect" not in attributes:
                return name
    return None


# -- IMAP responses ------------------------------------------------------------------------------------------

_OPEN = object()
_CLOSE = object()
_TOKEN = re.compile(rb'(\()|(\))|"((?:[^"\\]|\\.)*)"|((?:[^\s()"\[\]]|\[[^\]]*\])+)')
_LITERAL_MARK = re.compile(rb"\{\d+\}$")
_PARTIAL = re.compile(r"<\d+>$")


def _responses(data: Sequence[Any]) -> Iterator[list[Any]]:
    """imaplib's untagged data, one entry per response: a literal and the rest of its line stay together."""
    response: list[Any] = []
    continues = False
    for item in data or ():
        if item is None:
            continue
        if response and not continues:
            yield response
            response = []
        response.append(item)
        continues = isinstance(item, tuple)
    if response:
        yield response


def _tokens(response: Sequence[Any]) -> list[Any]:
    tokens: list[Any] = []
    for item in response:
        text, literal = (item[0], item[1]) if isinstance(item, tuple) else (item, None)
        if literal is not None:
            text = _LITERAL_MARK.sub(b"", text.rstrip())
        position = 0
        while position < len(text):
            if text[position : position + 1].isspace():
                position += 1
                continue
            match = _TOKEN.match(text, position)
            if match is None:
                position += 1
                continue
            position = match.end()
            opening, closing, quoted, atom = match.groups()
            if opening:
                tokens.append(_OPEN)
            elif closing:
                tokens.append(_CLOSE)
            elif quoted is not None:
                tokens.append(re.sub(rb"\\(.)", rb"\1", quoted))
            else:
                tokens.append(None if atom.upper() == b"NIL" else atom)
        if literal is not None:
            tokens.append(literal)
    return tokens


def _tree(tokens: Sequence[Any]) -> list[Any]:
    root: list[Any] = []
    stack = [root]
    for token in tokens:
        if token is _OPEN:
            child: list[Any] = []
            stack[-1].append(child)
            stack.append(child)
        elif token is _CLOSE:
            if len(stack) > 1:
                stack.pop()
        else:
            stack[-1].append(token)
    return root


def _fetched(data: Sequence[Any]) -> dict[int, dict[str, Any]]:
    """``{uid: {item: value}}`` from imaplib's ``UID FETCH`` data (items named as the server sent them)."""
    messages: dict[int, dict[str, Any]] = {}
    for response in _responses(data):
        values = _tree(_tokens(response))
        attributes = values[1] if len(values) >= 2 and isinstance(values[1], list) else []
        items = {
            key.decode("ascii", "replace").upper(): value
            for key, value in zip(attributes[::2], attributes[1::2], strict=False)
            if isinstance(key, bytes)
        }
        uid = items.get("UID")
        if isinstance(uid, bytes) and uid.isdigit():
            messages[int(uid)] = items
    return messages


def _section(items: Mapping[str, Any], prefix: str) -> bytes:
    """A fetched body section (``BODY[]``, ``BODY[TEXT]``, ``BODY[HEADER``...), partial or whole."""
    for key, value in items.items():
        if _PARTIAL.sub("", key).startswith(prefix) and isinstance(value, bytes):
            return value
    return b""


def _seen(items: Mapping[str, Any]) -> bool:
    flags = items.get("FLAGS")
    return isinstance(flags, list) and any(isinstance(f, bytes) and f.lower() == b"\\seen" for f in flags)


# -- Message content -----------------------------------------------------------------------------------------


def _decoded(value: Any) -> str:
    """A header as readable text: RFC 2047 words decoded, raw 8-bit read as UTF-8, whitespace collapsed."""
    if value is None:
        return ""
    parts = []
    try:
        chunks = decode_header(value)
    except (HeaderParseError, ValueError, LookupError):
        chunks = [(str(value), None)]
    for chunk, charset in chunks:
        if isinstance(chunk, str):
            parts.append(chunk)
            continue
        try:
            parts.append(chunk.decode(charset if charset and charset != "unknown-8bit" else "utf-8", "replace"))
        except LookupError:
            parts.append(chunk.decode("utf-8", "replace"))
    return " ".join("".join(parts).split())


def _pairs(values: Sequence[Any]) -> list[tuple[str, str]]:
    """``(name, address)`` for each address in these header values."""
    texts = [value if isinstance(value, str) else _decoded(value) for value in values]
    return [(_decoded(name), address) for name, address in getaddresses(texts) if _ADDRESS.fullmatch(address)]


def _recipients(field_name: str, value: Any, *, required: bool) -> list[tuple[str, str]]:
    """``(name, address)`` pairs from a comma-separated input field; raises EmailError on anything else."""
    text = str(value or "").strip()
    if not text:
        if required:
            raise EmailError(f"`{field_name}` needs at least one email address.")
        return []
    pairs = [] if _CONTROL.search(text) else getaddresses([text])
    if not pairs or any(not _ADDRESS.fullmatch(address) for _, address in pairs):
        raise EmailError(f"`{field_name}` must be email addresses, comma separated; got {text!r}.")
    return pairs


def _date(message: Message) -> str:
    raw = message.get("Date")
    if not raw:
        return ""
    try:
        return parsedate_to_datetime(str(raw)).isoformat()
    except (TypeError, ValueError, IndexError):
        return _decoded(raw)


def _summary(uid: int, items: Mapping[str, Any]) -> dict[str, Any]:
    header = _section(items, "BODY[HEADER").rstrip(b"\r\n")
    message = message_from_bytes(header + b"\r\n\r\n" + _section(items, "BODY[TEXT]"))
    text, _ = _content(message)
    snippet, _ = _truncate(" ".join(text.split()), SNIPPET_CHARS, marker="…")
    return {
        "uid": uid,
        "from": _decoded(message.get("From")),
        "to": _truncate(_decoded(message.get("To")), 200, marker="…")[0],
        "subject": _decoded(message.get("Subject")),
        "date": _date(message),
        "snippet": snippet,
        "unread": not _seen(items),
    }


def _content(message: Message) -> tuple[str, list[dict[str, Any]]]:
    """The message's text (its HTML as text when it has no plain part) and its attachments by name and size."""
    plain: str | None = None
    html: str | None = None
    attachments = []
    for part in message.walk():
        if part.is_multipart():
            continue
        filename = part.get_filename()
        if filename or part.get_content_disposition() == "attachment":
            payload = part.get_payload(decode=True)
            size = len(payload) if isinstance(payload, bytes) else 0
            attachments.append(
                {"name": _decoded(filename) or "(unnamed)", "type": part.get_content_type(), "size": size}
            )
        elif part.get_content_type() == "text/plain" and plain is None:
            plain = _part_text(part)
        elif part.get_content_type() == "text/html" and html is None:
            html = _part_text(part)
    text = plain if plain and plain.strip() else html_to_text(html) if html else plain or ""
    return _tidy(text), attachments


def _part_text(part: Message) -> str:
    payload = part.get_payload(decode=True)
    if not isinstance(payload, bytes):
        return ""
    try:
        return payload.decode(part.get_content_charset() or "utf-8", "replace")
    except LookupError:
        return payload.decode("utf-8", "replace")


def _tidy(text: str) -> str:
    lines = (" ".join(line.split()) for line in _INVISIBLE.sub("", text).splitlines())
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


def _truncate(text: str, limit: int, *, marker: str = "") -> tuple[str, bool]:
    if len(text) <= limit:
        return text, False
    return text[:limit].rstrip() + (marker or f"\n[... {len(text) - limit} more characters not shown]"), True


class _HtmlText(HTMLParser):
    _HIDDEN = frozenset({"script", "style", "title", "noscript", "template"})
    _BLOCKS = frozenset(
        {"p", "div", "table", "ul", "ol", "blockquote", "section", "article", "header", "footer", "hr"}
        | {f"h{level}" for level in range(1, 7)}
    )
    """Set apart by a line break on each side; ``br``, ``tr`` and ``li`` only start a new line."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.hidden = 0
        self.links: list[tuple[str, int]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in self._HIDDEN:
            self.hidden += 1
        elif tag == "li":
            self.parts.append("\n- ")
        elif tag in ("br", "tr") or tag in self._BLOCKS:
            self.parts.append("\n")
        elif tag in ("td", "th"):
            self.parts.append(" ")
        elif tag == "a":
            self.links.append((dict(attrs).get("href") or "", len(self.parts)))
        elif tag == "img" and not self.hidden:
            self.parts.append(dict(attrs).get("alt") or "")

    def handle_endtag(self, tag: str) -> None:
        if tag in self._HIDDEN:
            self.hidden = max(0, self.hidden - 1)
        elif tag in self._BLOCKS:
            self.parts.append("\n")
        elif tag == "a" and self.links:
            href, start = self.links.pop()
            label = "".join(self.parts[start:]).strip()
            target = href.removeprefix("mailto:")
            if href.startswith(("http://", "https://", "mailto:")) and target not in label:
                self.parts.append(f" ({target})")

    def handle_data(self, data: str) -> None:
        if not self.hidden:
            self.parts.append(data)


def html_to_text(html: str) -> str:
    """Readable text from an HTML email: no scripts or styles, a line per block, links kept beside their text."""
    parser = _HtmlText()
    parser.feed(html)
    parser.close()
    return _tidy("".join(parser.parts))


# -- Tools ---------------------------------------------------------------------------------------------------


def _str(description: str) -> dict[str, Any]:
    return {"type": "string", "description": description}


def _int(description: str) -> dict[str, Any]:
    return {"type": "integer", "description": description}


def _bool(description: str) -> dict[str, Any]:
    return {"type": "boolean", "description": description}


_FOLDER = _str("Mailbox folder (default INBOX).")
_UID = _int("The email's uid from email_search.")


def _send_tier(tool_input: object) -> tuple[Tier, str]:
    return "send", alert_for("email_send", tool_input, "send", ()).headline


def _reply_tier(tool_input: object) -> tuple[Tier, str]:
    return "send", alert_for("email_reply", tool_input, "send", ()).headline


def _mark_tier(tool_input: object) -> tuple[Tier, str]:
    data = tool_input if isinstance(tool_input, Mapping) else {}
    tier: Tier = "write" if data.get("action") in MARK_ACTIONS else "unknown"
    return tier, alert_for("email_mark", tool_input, tier, ()).headline


def _send_label(scope: Mapping[str, str]) -> str:
    copy = f" with a copy to {scope['cc']}" if scope.get("cc") else ""
    return f"Emails to {scope['to']}{copy}"


def _reply_label(scope: Mapping[str, str]) -> str:
    if scope.get("reply_all") == "true":
        return f"Replies to {scope['to']} that go to everyone on the email"
    return f"Replies to {scope['to']}"


_MARK_LABELS = {"read": "Marking emails as read", "unread": "Marking emails as unread", "archive": "Archiving emails"}

SEND_STANDING = Standing((("to", "emails"), ("cc", "emails")), frozenset({"to"}), True, _send_label)
# Reply-all also goes to everyone the original was sent to, a list the email's sender set, so it is deny-only.
REPLY_STANDING = Standing(
    (("to", "emails"), ("reply_all", "flag")),
    frozenset({"to"}),
    True,
    _reply_label,
    allow_only=(("reply_all", "false"),),
)
MARK_STANDING = Standing(
    (("action", "text"),),
    frozenset({"action"}),
    True,
    lambda scope: _MARK_LABELS[scope["action"]],
    when=(("action", frozenset(MARK_ACTIONS)),),
)


@dataclass(frozen=True)
class EmailToolSpec:
    """One email tool: what the model sees and how it is approved."""

    name: str
    description: str
    properties: Mapping[str, Any]
    required: tuple[str, ...]
    policy: ToolPolicy

    @property
    def input_schema(self) -> dict[str, Any]:
        """JSON schema for the tool input."""
        return {"type": "object", "properties": dict(self.properties), "required": list(self.required)}


SPECS: tuple[EmailToolSpec, ...] = (
    EmailToolSpec(
        "email_search",
        "Search the user's mailbox, newest first. `query` takes words found anywhere in a message, plus from:, to:, "
        'cc: and subject: terms (quote phrases: subject:"lunch friday"). Returns each message\'s uid, sender, '
        "recipients, subject, date, a snippet and whether it is unread. Never marks anything read.",
        {
            "query": _str("What to find, e.g. from:ada invoice (empty for everything)."),
            "folder": _FOLDER,
            "unread_only": _bool("Only unread messages."),
            "since_days": _int("Only messages from the last this many days."),
            "limit": _int(f"Max messages (default 20, max {SEARCH_LIMIT})."),
        },
        (),
        ToolPolicy("read", "Search email", untrusted_output=True),
    ),
    EmailToolSpec(
        "email_read",
        "Read one email: its headers, its text (HTML converted, long emails cut short) and its attachments by name "
        "and size. Does not mark it read.",
        {"uid": _UID, "folder": _FOLDER},
        ("uid",),
        ToolPolicy("read", "Read an email", untrusted_output=True),
    ),
    EmailToolSpec(
        "email_send",
        "Send a new plain-text email from the user's address. A copy is saved in Sent.",
        {
            "to": _str("Recipient address(es), comma separated."),
            "subject": _str("Subject."),
            "body": _str("Body text."),
            "cc": _str("Cc address(es), comma separated (optional)."),
        },
        ("to", "subject", "body"),
        ToolPolicy("send", "Send an email", standing=SEND_STANDING, assess=_send_tier),
    ),
    EmailToolSpec(
        "email_reply",
        "Reply to an email, threaded under it with a Re: subject. `to` is who replies to it go to: its reply_to, "
        "else its sender, as email_read shows them. reply_all also copies everyone else it was sent to. A copy is "
        "saved in Sent.",
        {
            "uid": _UID,
            "to": _str("The address the reply goes to (the email's reply_to, else its sender)."),
            "body": _str("Reply text."),
            "reply_all": _bool("Also copy everyone else the email was sent to."),
            "folder": _FOLDER,
        },
        ("uid", "to", "body"),
        ToolPolicy("send", "Reply to an email", untrusted_output=True, standing=REPLY_STANDING, assess=_reply_tier),
    ),
    EmailToolSpec(
        "email_mark",
        "Mark an email read or unread, or archive it (move it to the archive folder; All Mail in Gmail). Nothing "
        "deletes email.",
        {"uid": _UID, "action": {"type": "string", "enum": list(MARK_ACTIONS)}, "folder": _FOLDER},
        ("uid", "action"),
        ToolPolicy("write", "Mark or archive an email", standing=MARK_STANDING, assess=_mark_tier),
    ),
)

POLICIES: dict[str, ToolPolicy] = {spec.name: spec.policy for spec in SPECS}
TOOL_NAMES = frozenset(POLICIES)

INSTRUCTIONS = """\
# Email
 - The user's mailbox is connected. Find mail with `email_search` (narrow it with from:, to:, cc: and subject:) \
and open a message with `email_read`; neither marks anything read.
 - Write new mail with `email_send`. Answer a message with `email_reply`, giving its uid and the address replies \
go to; reply to all only when the user asks. Both keep a copy in Sent.
 - `email_mark` marks a message read or unread, or archives it. Nothing deletes email: when the user asks to \
delete one, offer to archive it.
 - Use `email_draft` instead when the user wants to finish an email on the phone themselves."""


def _result(tool_use_id: str, ok: bool, data: Any) -> dict[str, Any]:
    text = data if isinstance(data, str) else json.dumps(data, ensure_ascii=False)
    return {"toolUseId": tool_use_id, "status": "success" if ok else "error", "content": [{"text": text}]}


async def _blocking(seconds: float, function: Callable[..., Any], *args: Any) -> Any:
    return await asyncio.wait_for(asyncio.to_thread(function, *args), seconds)


def _uid(data: Mapping[str, Any]) -> int:
    value = data.get("uid")
    if isinstance(value, bool) or not str(value).isdigit():
        raise EmailError("`uid` must be an email's uid from email_search.")
    return int(str(value))


def _folder(data: Mapping[str, Any]) -> str:
    folder = str(data.get("folder") or "INBOX").strip()
    if _CONTROL.search(folder):
        raise EmailError("`folder` must be a folder name.")
    return folder


def _count(value: Any, default: int | None, ceiling: int) -> int | None:
    if value is None or isinstance(value, bool):
        return default
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return max(1, min(number, ceiling)) if number > 0 else default


def _text(data: Mapping[str, Any], name: str) -> str:
    value = data.get(name)
    if not isinstance(value, str) or not value.strip():
        raise EmailError(f"`{name}` is required.")
    return value


async def _send(mailbox: Mailbox, message: EmailMessage, to: Sequence[str]) -> dict[str, Any]:
    """Deliver, then file a copy in Sent; a missing copy never turns a delivered email into a failure."""
    try:
        refused = await _blocking(SEND_TIMEOUT, mailbox.deliver, message, to)
    except TimeoutError:
        raise EmailError(UNCERTAIN) from None
    try:
        saved = await _blocking(READ_TIMEOUT, mailbox.save_sent, message)
    except Exception as error:  # noqa: BLE001 - the email went out; only the copy in Sent is missing
        logger.warning(
            "error=<%s> | sent email was not filed in Sent", mailbox.scrub(f"{type(error).__name__}: {error}")
        )
        saved = None
    outcome: dict[str, Any] = {
        "sent": True,
        "to": str(message["To"]),
        "cc": str(message["Cc"] or ""),
        "subject": str(message["Subject"]),
        "saved_to_sent": saved,
    }
    if refused:
        outcome["refused"] = refused
    return outcome


def tools_for(mailbox: Mailbox | None) -> list[PythonAgentTool]:
    """The email tools for a configured mailbox; none without one."""
    if mailbox is None:
        return []

    async def search(data: Mapping[str, Any]) -> Any:
        query = str(data.get("query") or "")[:500]
        since_days = _count(data.get("since_days"), None, 3650)
        limit = _count(data.get("limit"), 20, SEARCH_LIMIT) or 20
        unread_only = data.get("unread_only") is True
        return await _blocking(READ_TIMEOUT, mailbox.search, query, _folder(data), unread_only, since_days, limit)

    async def read(data: Mapping[str, Any]) -> Any:
        return await _blocking(READ_TIMEOUT, mailbox.read, _uid(data), _folder(data))

    async def send(data: Mapping[str, Any]) -> Any:
        to = _recipients("to", data.get("to"), required=True)
        cc = _recipients("cc", data.get("cc"), required=False)
        message = mailbox.new_message(to, cc, _text(data, "subject"), _text(data, "body"))
        return await _send(mailbox, message, [address for _, address in [*to, *cc]])

    async def reply(data: Mapping[str, Any]) -> Any:
        body = _text(data, "body")
        original = await _blocking(READ_TIMEOUT, mailbox.original, _uid(data), _folder(data))
        message, to = mailbox.reply_message(original, data.get("to"), body, reply_all=data.get("reply_all") is True)
        return await _send(mailbox, message, to)

    async def mark(data: Mapping[str, Any]) -> Any:
        action = str(data.get("action") or "")
        if action not in MARK_ACTIONS:
            raise EmailError("`action` must be read, unread or archive.")
        return await _blocking(READ_TIMEOUT, mailbox.mark, _uid(data), action, _folder(data))

    handlers = {
        "email_search": search,
        "email_read": read,
        "email_send": send,
        "email_reply": reply,
        "email_mark": mark,
    }
    return [_tool(mailbox, spec, handlers[spec.name]) for spec in SPECS]


def _tool(
    mailbox: Mailbox, spec: EmailToolSpec, handler: Callable[[Mapping[str, Any]], Awaitable[Any]]
) -> PythonAgentTool:
    async def run(tool_use: Any, **_: Any) -> Any:
        tool_use_id = tool_use["toolUseId"]
        data = tool_use.get("input")
        try:
            return _result(tool_use_id, True, await handler(data if isinstance(data, Mapping) else {}))
        except EmailError as error:
            return _result(tool_use_id, False, mailbox.scrub(str(error)))
        except TimeoutError:
            return _result(tool_use_id, False, "The mail server did not answer in time. Try again later.")
        except (OSError, imaplib.IMAP4.error, smtplib.SMTPException, ValueError, UnicodeError) as error:
            text = mailbox.scrub(f"{type(error).__name__}: {error}")
            logger.warning("tool=<%s>, error=<%s> | email call failed", spec.name, text)
            return _result(tool_use_id, False, f"The mail server call failed: {text}")

    tool_spec = {"name": spec.name, "description": spec.description, "inputSchema": {"json": spec.input_schema}}
    return PythonAgentTool(spec.name, tool_spec, run)  # type: ignore[arg-type]
