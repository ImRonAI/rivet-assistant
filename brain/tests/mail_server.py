"""In-process IMAP and SMTP servers for the email tests, speaking TLS with a throwaway self-signed certificate.

``FakeImap`` is a minimal IMAP4rev1 responder: CAPABILITY, LOGIN, ENABLE, LIST (with special-use attributes),
SELECT/EXAMINE, UID SEARCH/FETCH/STORE/MOVE/COPY/EXPUNGE, EXPUNGE, APPEND and LOGOUT, over just enough of the
grammar for Python's ``imaplib``. ``FakeSmtp`` speaks ESMTP with implicit TLS (465) or STARTTLS (587) and AUTH
PLAIN. Both record what they were sent, so tests can check what a client did, not only what it reported.
"""

from __future__ import annotations

import base64
import re
import socket
import socketserver
import ssl
import subprocess
import threading
from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from email import message_from_bytes, policy
from pathlib import Path
from typing import Any

ADDRESS = "rivet.user@example.com"
PASSWORD = "app-password-4f9c2e7a1b"
CAPABILITIES = "IMAP4rev1 SPECIAL-USE MOVE UIDPLUS ENABLE UTF8=ACCEPT"
FOLDERS: dict[str, tuple[str, ...]] = {"INBOX": (), "Sent": ("\\Sent",), "Archive": ("\\Archive",)}

_OPENSSL_CONFIG = """\
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = 127.0.0.1
[ext]
subjectAltName = IP:127.0.0.1, DNS:localhost
basicConstraints = critical, CA:TRUE
keyUsage = critical, digitalSignature, keyEncipherment, keyCertSign
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
"""


def throwaway_tls(directory: Path) -> tuple[ssl.SSLContext, ssl.SSLContext]:
    """A fresh self-signed certificate for 127.0.0.1: ``(server context, client context trusting only it)``."""
    (directory / "openssl.cnf").write_text(_OPENSSL_CONFIG)
    command = ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2"]
    command += ["-keyout", "key.pem", "-out", "cert.pem", "-config", "openssl.cnf"]
    subprocess.run(command, cwd=directory, check=True, capture_output=True)
    server = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    server.load_cert_chain(directory / "cert.pem", directory / "key.pem")
    return server, ssl.create_default_context(cafile=str(directory / "cert.pem"))


class _Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True

    def handle_error(self, request: Any, client_address: Any) -> None:
        """Clients that fail the TLS handshake or hang up are part of the tests."""


class _Background:
    """Runs a socketserver on 127.0.0.1 in a thread for the length of a ``with`` block."""

    server: _Server

    def __init__(self, session: Any) -> None:
        owner = self

        class Handler(socketserver.BaseRequestHandler):
            def handle(self) -> None:
                # Responses go out a line at a time; without this each round trip waits on a delayed ACK.
                self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                running = session(owner, self.request)
                try:
                    running.run()
                finally:
                    # A TLS session wraps the request in a new socket that socketserver does not know to close.
                    for stream in (getattr(running, "reader", None), running.sock):
                        if stream is not None:
                            stream.close()

        self.server = _Server(("127.0.0.1", 0), Handler)
        self.port: int = self.server.server_address[1]
        self.lock = threading.Lock()

    def __enter__(self) -> Any:
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
        return self

    def __exit__(self, *exc: object) -> None:
        self.server.shutdown()
        self.server.server_close()


@dataclass
class Stored:
    uid: int
    raw: bytes
    flags: set[str]
    when: datetime


@dataclass
class Folder:
    uses: tuple[str, ...] = ()
    mail: list[Stored] = field(default_factory=list)
    next_uid: int = 1

    def add(self, raw: bytes, flags: Iterable[str] = (), when: datetime | None = None) -> int:
        """Store a message; returns its uid."""
        uid = self.next_uid
        self.next_uid += 1
        self.mail.append(Stored(uid, raw, set(flags), when or datetime.now(UTC)))
        return uid

    def find(self, uids: str) -> list[Stored]:
        top = max((m.uid for m in self.mail), default=0)
        wanted: set[int] = set()
        for part in uids.split(","):
            low, _, high = part.partition(":")
            start = top if low == "*" else int(low)
            end = start if not high else top if high == "*" else int(high)
            wanted.update(range(min(start, end), max(start, end) + 1))
        return [m for m in self.mail if m.uid in wanted]


class FakeImap(_Background):
    """Folders of messages behind IMAP over TLS (plaintext when ``tls`` is None)."""

    def __init__(
        self,
        tls: ssl.SSLContext | None,
        *,
        capabilities: str = CAPABILITIES,
        folders: dict[str, tuple[str, ...]] | None = None,
        password: str = PASSWORD,
        echo_password: bool = False,
    ) -> None:
        super().__init__(_ImapSession)
        self.tls = tls
        self.capabilities = capabilities
        self.folders = {name: Folder(uses) for name, uses in (folders or FOLDERS).items()}
        self.password = password
        self.echo_password = echo_password
        self.commands: list[str] = []

    def __enter__(self) -> FakeImap:
        return super().__enter__()  # type: ignore[no-any-return]


_ARG = re.compile(rb'\s*(?:(\()|(\))|"((?:[^"\\]|\\.)*)"|\{(\d+)\}$|((?:[^\s()"\[\]{]|\[[^\]]*\])+))')
_ITEM = re.compile(r"^(BODY(?:\.PEEK)?)\[([^\]]*)\](?:<(\d+)\.(\d+)>)?$", re.IGNORECASE)
_MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


class _ImapSession:
    def __init__(self, server: FakeImap, sock: socket.socket) -> None:
        self.server = server
        self.sock = sock
        self.folder: Folder | None = None
        self.writable = False
        self.done = False

    def send(self, line: str | bytes) -> None:
        self.sock.sendall((line.encode() if isinstance(line, str) else line) + b"\r\n")

    def run(self) -> None:
        if self.server.tls is not None:
            self.sock = self.server.tls.wrap_socket(self.sock, server_side=True)
        self.reader = self.sock.makefile("rb")
        self.send(f"* OK [CAPABILITY {self.server.capabilities}] fake IMAP ready")
        while not self.done:
            line = self.reader.readline()
            if not line:
                return
            tag, _, rest = line.rstrip(b"\r\n").partition(b" ")
            command, _, text = rest.partition(b" ")
            name = command.decode("ascii", "replace").upper()
            args = self.arguments(text)
            if name == "UID" and args:
                name, args = f"UID {str(args[0]).upper()}", args[1:]
            with self.server.lock:
                self.server.commands.append("LOGIN" if name == "LOGIN" else rest.decode("utf-8", "replace"))
                handler = getattr(self, "do_" + name.lower().replace(" ", "_"), None)
                status = handler(args) if handler else "BAD unknown command"
            self.send(tag + b" " + status.encode())

    def arguments(self, text: bytes) -> list[Any]:
        stack: list[list[Any]] = [[]]
        while True:
            literal = None
            position = 0
            while (match := _ARG.match(text, position)) is not None and match.end() > position:
                position = match.end()
                opening, closing, quoted, size, atom = match.groups()
                if opening:
                    stack[-1].append([])
                    stack.append(stack[-1][-1])
                elif closing and len(stack) > 1:
                    stack.pop()
                elif quoted is not None:
                    stack[-1].append(re.sub(rb"\\(.)", rb"\1", quoted).decode())
                elif size is not None:
                    literal = int(size)
                elif atom is not None:
                    stack[-1].append(atom.decode())
            if literal is None:
                return stack[0]
            self.send("+ go ahead")
            stack[-1].append(self.reader.read(literal))
            text = self.reader.readline().rstrip(b"\r\n")

    # -- commands ---------------------------------------------------------------------------------------------

    def do_capability(self, args: list[Any]) -> str:
        self.send(f"* CAPABILITY {self.server.capabilities}")
        return "OK CAPABILITY completed"

    def do_noop(self, args: list[Any]) -> str:
        return "OK NOOP completed"

    def do_login(self, args: list[Any]) -> str:
        if args[:2] == [ADDRESS, self.server.password]:
            return "OK LOGIN completed"
        echo = f" (got {args[1]})" if self.server.echo_password and len(args) > 1 else ""
        return f"NO [AUTHENTICATIONFAILED] Invalid credentials{echo}"

    def do_logout(self, args: list[Any]) -> str:
        self.send("* BYE logging out")
        self.done = True
        return "OK LOGOUT completed"

    def do_enable(self, args: list[Any]) -> str:
        if "UTF8=ACCEPT" in args and "UTF8=ACCEPT" in self.server.capabilities.split():
            self.send("* ENABLED UTF8=ACCEPT")
        return "OK ENABLE completed"

    def do_list(self, args: list[Any]) -> str:
        for name, folder in self.server.folders.items():
            attributes = " ".join(["\\HasNoChildren", *folder.uses])
            self.send(f'* LIST ({attributes}) "/" "{name}"')
        return "OK LIST completed"

    def _open(self, args: list[Any], writable: bool) -> str:
        name = str(args[0]) if args else ""
        folder = self.server.folders.get("INBOX" if name.upper() == "INBOX" else name)
        if folder is None:
            self.folder = None
            return "NO [NONEXISTENT] no such folder"
        self.folder, self.writable = folder, writable
        self.send("* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)")
        self.send(f"* {len(folder.mail)} EXISTS")
        self.send("* 0 RECENT")
        self.send("* OK [UIDVALIDITY 1] UIDs valid")
        self.send(f"* OK [UIDNEXT {folder.next_uid}] predicted next UID")
        return "OK [READ-WRITE] SELECT completed" if writable else "OK [READ-ONLY] EXAMINE completed"

    def do_select(self, args: list[Any]) -> str:
        return self._open(args, writable=True)

    def do_examine(self, args: list[Any]) -> str:
        return self._open(args, writable=False)

    def do_close(self, args: list[Any]) -> str:
        self.folder = None
        return "OK CLOSE completed"

    def do_expunge(self, args: list[Any]) -> str:
        return self._expunge(self.folder.mail if self.folder else [])

    def do_uid_expunge(self, args: list[Any]) -> str:
        if "UIDPLUS" not in self.server.capabilities.split() or self.folder is None:
            return "BAD UID EXPUNGE needs UIDPLUS"
        return self._expunge(self.folder.find(str(args[0])))

    def _expunge(self, candidates: list[Stored]) -> str:
        if self.folder is None or not self.writable:
            return "NO no writable folder selected"
        for message in [m for m in candidates if "\\Deleted" in m.flags]:
            self.send(f"* {self.folder.mail.index(message) + 1} EXPUNGE")
            self.folder.mail.remove(message)
        return "OK EXPUNGE completed"

    def do_append(self, args: list[Any]) -> str:
        folder = self.server.folders.get(str(args[0]))
        if folder is None or not isinstance(args[-1], bytes):
            return "NO [TRYCREATE] no such folder"
        flags = next((a for a in args[1:-1] if isinstance(a, list)), [])
        uid = folder.add(args[-1], flags)
        return f"OK [APPENDUID 1 {uid}] APPEND completed"

    def do_uid_search(self, args: list[Any]) -> str:
        if self.folder is None:
            return "BAD no folder selected"
        keys = [str(a) for a in args]
        found = [str(m.uid) for m in self.folder.mail if _matches(m, keys)]
        self.send(" ".join(["* SEARCH", *found]))
        return "OK SEARCH completed"

    def do_uid_fetch(self, args: list[Any]) -> str:
        if self.folder is None:
            return "BAD no folder selected"
        items = args[1] if isinstance(args[1], list) else [args[1]]
        for message in self.folder.find(str(args[0])):
            self.send(self._fetch(message, [str(i) for i in items]))
        return "OK FETCH completed"

    def _fetch(self, message: Stored, items: list[str]) -> bytes:
        assert self.folder is not None
        parts = [b"UID %d" % message.uid]
        for item in items:
            upper = item.upper()
            body = _ITEM.match(item)
            if upper == "FLAGS":
                parts.append(("FLAGS (" + " ".join(sorted(message.flags)) + ")").encode())
            elif upper == "RFC822.SIZE":
                parts.append(b"RFC822.SIZE %d" % len(message.raw))
            elif body is not None:
                kind, section, start, length = body.groups()
                data = _section(message.raw, section)
                key = f"BODY[{section}]"
                if start is not None:
                    data, key = data[int(start) : int(start) + int(length)], f"{key}<{start}>"
                if kind.upper() == "BODY" and self.writable:
                    message.flags.add("\\Seen")
                parts.append(key.encode() + b" {%d}\r\n" % len(data) + data)
        return b"* %d FETCH (" % (self.folder.mail.index(message) + 1) + b" ".join(parts) + b")"

    def do_uid_store(self, args: list[Any]) -> str:
        if self.folder is None or not self.writable:
            return "NO folder is read-only"
        change, flags = str(args[1]).upper(), {str(f) for f in args[2]}
        for message in self.folder.find(str(args[0])):
            if change.startswith("+"):
                message.flags |= flags
            elif change.startswith("-"):
                message.flags -= flags
            else:
                message.flags = set(flags)
        return "OK STORE completed"

    def do_uid_copy(self, args: list[Any]) -> str:
        return self._copy(args, move=False)

    def do_uid_move(self, args: list[Any]) -> str:
        if "MOVE" not in self.server.capabilities.split():
            return "BAD MOVE is not supported"
        return self._copy(args, move=True)

    def _copy(self, args: list[Any], *, move: bool) -> str:
        target = self.server.folders.get(str(args[1]))
        if self.folder is None or target is None:
            return "NO [TRYCREATE] no such folder"
        for message in self.folder.find(str(args[0])):
            target.add(message.raw, message.flags - {"\\Deleted"}, message.when)
            if move:
                self.send(f"* {self.folder.mail.index(message) + 1} EXPUNGE")
                self.folder.mail.remove(message)
        return "OK COPY completed"


def _section(raw: bytes, section: str) -> bytes:
    split = raw.find(b"\r\n\r\n")
    header, text = (raw[: split + 4], raw[split + 4 :]) if split >= 0 else (raw, b"")
    name = section.upper()
    if name == "":
        return raw
    if name == "HEADER":
        return header
    if name == "TEXT":
        return text
    if name.startswith("HEADER.FIELDS"):
        wanted = set(name[name.index("(") + 1 : name.rindex(")")].split())
        kept = [field for field in re.split(rb"\r\n(?![ \t])", header.rstrip(b"\r\n")) if _field(field) in wanted]
        return b"".join(field + b"\r\n" for field in kept) + b"\r\n"
    raise ValueError(f"unsupported section {section}")


def _field(line: bytes) -> str:
    return line.split(b":", 1)[0].decode("ascii", "replace").strip().upper()


def _matches(message: Stored, keys: list[str]) -> bool:
    parsed = message_from_bytes(message.raw, policy=policy.default)
    position = 0
    while position < len(keys):
        key = keys[position].upper()
        if key == "ALL":
            position += 1
            continue
        if key in ("SEEN", "UNSEEN"):
            if ("\\Seen" in message.flags) != (key == "SEEN"):
                return False
            position += 1
            continue
        value = keys[position + 1]
        position += 2
        if key == "SINCE":
            day, month, year = value.split("-")
            if message.when.date() < date(int(year), _MONTHS.index(month) + 1, int(day)):
                return False
        elif key in ("FROM", "TO", "CC", "SUBJECT"):
            if value.lower() not in str(parsed.get(key) or "").lower():
                return False
        elif key == "TEXT":
            if value.lower() not in _searchable(parsed).lower():
                return False
        else:
            raise ValueError(f"unsupported search key {key}")
    return True


def _searchable(message: Any) -> str:
    texts = [f"{name}: {value}" for name, value in message.items()]
    for part in message.walk():
        if part.get_content_maintype() == "text":
            texts.append(part.get_content())
    return "\n".join(texts)


class FakeSmtp(_Background):
    """An ESMTP server that accepts mail for anyone once the client is on TLS and logged in."""

    def __init__(
        self,
        tls: ssl.SSLContext,
        *,
        starttls: bool = False,
        offer_starttls: bool = True,
        refuse: str | None = None,
        password: str = PASSWORD,
    ) -> None:
        super().__init__(_SmtpSession)
        self.tls = tls
        self.starttls = starttls
        self.offer_starttls = offer_starttls
        self.refuse = refuse
        self.password = password
        self.received: list[tuple[str, list[str], bytes]] = []
        self.plaintext: list[str] = []
        """Commands that arrived before TLS (only EHLO, STARTTLS and QUIT ever should)."""

    def __enter__(self) -> FakeSmtp:
        return super().__enter__()  # type: ignore[no-any-return]


class _SmtpSession:
    def __init__(self, server: FakeSmtp, sock: socket.socket) -> None:
        self.server = server
        self.sock = sock
        self.secure = False

    def send(self, line: str) -> None:
        self.sock.sendall(line.encode() + b"\r\n")

    def secure_now(self) -> None:
        self.sock = self.server.tls.wrap_socket(self.sock, server_side=True)
        self.reader = self.sock.makefile("rb")
        self.secure = True

    def run(self) -> None:
        if self.server.starttls:
            self.reader = self.sock.makefile("rb")
        else:
            self.secure_now()
        self.send("220 fake.example.com ESMTP")
        authed, sender, recipients = False, "", []
        while line := self.reader.readline():
            text = line.rstrip(b"\r\n").decode("utf-8", "replace")
            verb = text.split(" ", 1)[0].upper()
            if not self.secure:
                with self.server.lock:
                    self.server.plaintext.append(verb)
            if verb in ("EHLO", "HELO"):
                extensions = ["fake.example.com"]
                if not self.secure and self.server.offer_starttls:
                    extensions.append("STARTTLS")
                if self.secure:
                    extensions.append("AUTH PLAIN LOGIN")
                extensions.append("SIZE 10000000")
                for extension in extensions[:-1]:
                    self.send(f"250-{extension}")
                self.send(f"250 {extensions[-1]}")
            elif verb == "STARTTLS" and not self.secure and self.server.offer_starttls:
                self.send("220 2.0.0 ready to start TLS")
                self.secure_now()
            elif verb == "AUTH":
                _, _, blob = text[5:].partition(" ")
                if not self.secure:
                    self.send("530 5.7.0 must issue STARTTLS first")
                elif base64.b64decode(blob).split(b"\0")[1:] == [ADDRESS.encode(), self.server.password.encode()]:
                    authed = True
                    self.send("235 2.7.0 accepted")
                else:
                    self.send("535 5.7.8 bad credentials")
            elif verb == "MAIL":
                sender = _angle(text)
                self.send("250 OK" if authed else "530 5.7.0 authentication required")
            elif verb == "RCPT":
                if self.server.refuse:
                    self.send(f"550 5.1.1 {self.server.refuse}")
                else:
                    recipients.append(_angle(text))
                    self.send("250 OK")
            elif verb == "DATA":
                self.send("354 go ahead")
                lines = []
                while (data := self.reader.readline()) not in (b".\r\n", b""):
                    lines.append(data[1:] if data.startswith(b"..") else data)
                with self.server.lock:
                    self.server.received.append((sender, recipients, b"".join(lines)))
                recipients = []
                self.send("250 OK queued")
            elif verb == "QUIT":
                self.send("221 bye")
                return
            elif verb in ("RSET", "NOOP"):
                self.send("250 OK")
            else:
                self.send("502 5.5.2 not implemented")


def _angle(text: str) -> str:
    match = re.search(r"<([^>]*)>", text)
    return match.group(1) if match else ""


class SilentServer(_Background):
    """Accepts connections and never says a word."""

    def __init__(self) -> None:
        self.release = threading.Event()
        super().__init__(_Silent)

    def __exit__(self, *exc: object) -> None:
        self.release.set()
        super().__exit__(*exc)

    def __enter__(self) -> SilentServer:
        return super().__enter__()  # type: ignore[no-any-return]


class _Silent:
    def __init__(self, server: SilentServer, sock: socket.socket) -> None:
        self.server = server
        self.sock = sock

    def run(self) -> None:
        self.server.release.wait(10)
