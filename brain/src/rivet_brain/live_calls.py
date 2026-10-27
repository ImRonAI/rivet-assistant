"""Calls Rivet is answering right now: the live transcript, the user's take-over, and the summary at the end.

The schema evidence for every Telnyx operation and field used here is in ``docs/calls-live.md``.

- The ``start`` webhook opens a ``LiveCall`` and returns its id as the ``rivet_call_id`` dynamic variable; the
  assistant's webhook tools send it back as a preset body field its model can neither see nor set.
- While the call is live, a watcher runs fixed read-only code every few seconds: it finds the call's AI
  conversation and returns its messages, which reach the phone as new transcript lines.
- ``call.take_over`` from the phone transfers the call to the user's own number with ``TransferCall``. The tap is
  the approval: it only arrives over the authenticated device socket, and it runs fixed code, never model-written.
- ``call_summary``, the assistant's post-conversation webhook tool, ends the call.

Telnyx code runs through a ``TelnyxRunner``: the Telnyx MCP server's ``execute`` tool, as ``call_answering_setup``
calls it, but on a connection of its own, so a take-over never waits on an agent turn or dies with a generation
restart.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import re
import secrets
import time
from collections import OrderedDict
from collections.abc import Callable, Coroutine, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, Literal

from strands.tools.mcp import MCPClient

from .calls import _parse
from .supervisor import _MCP_BROKEN

logger = logging.getLogger(__name__)

POLL_SECONDS = 2.0
LIVE_LIMIT = 30 * 60.0
LOOKBACK = 60.0
LINE_LIMIT = 200
LINE_TEXT_LIMIT = 500
CALL_MEMORY = 32
TRANSFER_RING_SECONDS = 25
WATCH_TIMEOUT = timedelta(seconds=20)
TAKE_OVER_TIMEOUT = timedelta(seconds=30)
E164 = re.compile(r"^\+[1-9]\d{6,14}$")
_SPEAKERS = {"user": "caller", "assistant": "rivet"}

Status = Literal["answering", "transferring", "ended"]
Emit = Callable[[dict[str, Any]], None]
Spawn = Callable[[Coroutine[Any, Any, Any]], asyncio.Task[Any]]

_TAKE_OVER_ERRORS = {
    "no_conversation": "Telnyx has no record of this call yet. Try again in a moment.",
    "no_call_control_id": "Telnyx did not say which call to transfer.",
    "answering_line": "That is Rivet's answering line. Set your own phone number in Settings › Call answering.",
}


class TelnyxRunError(RuntimeError):
    """Telnyx code did not run, or Telnyx reported an error."""


class TelnyxRunner:
    """Runs fixed Telnyx ``execute`` code outside any agent turn, on a Telnyx MCP connection of its own.

    The connection opens on first use and reopens after it breaks.

    Args:
        connect: Builds a fresh, unstarted client (``factory.build_telnyx_client``).
    """

    def __init__(self, connect: Callable[[], MCPClient]) -> None:
        self._connect = connect
        self._client: MCPClient | None = None
        self._lock = asyncio.Lock()

    async def execute(self, code: str, *, label: str, timeout: timedelta) -> Any:
        """Run ``code`` with the ``execute`` tool and return its result.

        Raises:
            TelnyxRunError: The connection failed, or Telnyx or the code reported an error.
        """
        client = await self._ready()
        try:
            raw = await client.call_tool_async(label, "execute", {"code": code}, read_timeout_seconds=timeout)
        except Exception as error:
            await self._drop(client)
            raise TelnyxRunError(f"the Telnyx connection failed: {error}") from error
        outcome = _parse(raw)
        if raw.get("status") == "error":
            if _MCP_BROKEN.search(str(outcome)):
                await self._drop(client)
            raise TelnyxRunError(str(outcome)[:500])
        return outcome

    async def close(self) -> None:
        """Close the connection."""
        async with self._lock:
            client, self._client = self._client, None
        if client is not None:
            await _stop(client)

    async def _ready(self) -> MCPClient:
        async with self._lock:
            if self._client is None:
                client = self._connect()
                try:
                    await asyncio.to_thread(client.start)
                except Exception as error:
                    raise TelnyxRunError(f"could not connect to Telnyx: {error}") from error
                self._client = client
            return self._client

    async def _drop(self, client: MCPClient) -> None:
        async with self._lock:
            if self._client is not client:
                return
            self._client = None
        await _stop(client)


async def _stop(client: MCPClient) -> None:
    with contextlib.suppress(Exception):
        await asyncio.to_thread(client.stop, None, None, None)


def _iso(timestamp: float) -> str:
    return datetime.fromtimestamp(timestamp, UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _find_code(caller: str, started_at: float) -> str:
    """Code that sets ``conversation`` to the newest phone conversation with ``caller`` since the call began."""
    query: dict[str, Any] = {
        "metadata->telnyx_conversation_channel": "eq.phone_call",
        "created_at": "gte." + _iso(started_at - LOOKBACK),
        "order": "created_at.desc",
        "limit": 1,
    }
    if caller:
        query["metadata->telnyx_end_user_target"] = "eq." + caller
    return f"""\
found = await call_tool('get_conversations_public_conversations_get', {query!r})
rows = found.get('data') or []
conversation = rows[0] if rows else None
"""


def watch_code(caller: str, started_at: float, conversation_id: str | None) -> str:
    """Read-only Telnyx ``execute`` code returning the call's conversation id and its messages."""
    known = f"conversation = {{'id': {conversation_id!r}}}\n"
    find = _find_code(caller, started_at) if conversation_id is None else known
    return f"""\
{find}if conversation is None:
    return {{'conversation_id': None, 'messages': []}}
messages = []
for page in [1, 2, 3]:
    query = {{'conversation_id': conversation['id'], 'page[size]': 100, 'page[number]': page}}
    batch = await call_tool('Get_conversation_messages', query)
    messages = messages + (batch.get('data') or [])
    if page >= ((batch.get('meta') or {{}}).get('total_pages') or 1):
        break
rows = [{{'role': m.get('role'), 'text': m.get('text'), 'at': m.get('sent_at') or m.get('created_at')}}
        for m in messages]
return {{'conversation_id': conversation['id'], 'messages': rows}}
"""


def take_over_code(caller: str, started_at: float, conversation_id: str | None, to: str, command_id: str) -> str:
    """Telnyx ``execute`` code that transfers the call to ``to``; nothing is written unless the call is found."""
    if conversation_id is None:
        find = _find_code(caller, started_at)
    else:
        find = (
            "found = await call_tool('get_conversation_by_id_public_conversations_get', "
            f"{{'conversation_id': {conversation_id!r}}})\nconversation = found.get('data')\n"
        )
    return f"""\
TO = {to!r}
{find}if not conversation:
    return {{'ok': False, 'reason': 'no_conversation'}}
meta = conversation.get('metadata') or {{}}
call = meta.get('call_control_id')
if not call:
    return {{'ok': False, 'reason': 'no_call_control_id'}}
line = meta.get('telnyx_agent_target') or ''
if line == TO:
    return {{'ok': False, 'reason': 'answering_line'}}
transfer = {{'call_control_id': call, 'to': TO, 'command_id': {command_id!r}, 'timeout_secs': {TRANSFER_RING_SECONDS}}}
if line.startswith('+'):
    transfer['from'] = line
await call_tool('TransferCall', transfer)
return {{'ok': True, 'call_control_id': call}}
"""


def transcript(messages: Any) -> list[dict[str, str]]:
    """Conversation messages as transcript lines, oldest first: only what the caller and the assistant said."""
    rows = [row for row in messages if isinstance(row, Mapping)] if isinstance(messages, list) else []
    rows.sort(key=lambda row: str(row.get("at") or ""))
    lines = []
    for row in rows:
        speaker = _SPEAKERS.get(str(row.get("role")))
        text = str(row.get("text") or "").strip()
        if speaker and text:
            lines.append({"speaker": speaker, "text": text[:LINE_TEXT_LIMIT]})
    return lines


@dataclass
class LiveCall:
    """One call Rivet's assistant is on."""

    call_id: str
    caller: str
    verified: bool
    started_at: float
    status: Status = "answering"
    lines: list[dict[str, str]] = field(default_factory=list)
    conversation_id: str | None = None
    seen: int = 0
    attempts: int = 0
    taken_over: bool = False
    watcher: asyncio.Task[Any] | None = None

    def live_event(self, lines: Sequence[Mapping[str, str]], *, snapshot: bool = False) -> dict[str, Any]:
        """``call.live``: the call's state and its new transcript lines (all of them when ``snapshot``)."""
        return {
            "type": "call.live",
            "call_id": self.call_id,
            "from": self.caller,
            "verified": self.verified,
            "started_at": self.started_at,
            "status": self.status,
            "lines": [dict(line) for line in lines],
            "snapshot": snapshot,
        }


class LiveCalls:
    """The calls Rivet is answering for one session, and what the phone hears about them.

    Args:
        emit: Sends an event to the phone (buffered while it is away).
        spawn: Runs a background task owned by the session.
        telnyx: Runs the watcher and take-over code; without it there is no transcript and no take-over.
        poll: Seconds between transcript reads.
        limit: Seconds a call stays live without a summary.
        clock: Wall-clock source.
    """

    def __init__(
        self,
        emit: Emit,
        spawn: Spawn,
        *,
        telnyx: TelnyxRunner | None = None,
        poll: float = POLL_SECONDS,
        limit: float = LIVE_LIMIT,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._emit = emit
        self._spawn = spawn
        self._telnyx = telnyx
        self._poll = poll
        self._limit = limit
        self._clock = clock
        self.calls: OrderedDict[str, LiveCall] = OrderedDict()

    def open(self, caller: str, verified: bool) -> LiveCall:
        """Start tracking a call the assistant just picked up."""
        call = LiveCall(f"c-{secrets.token_hex(6)}", caller, verified, self._clock())
        self.calls[call.call_id] = call
        while len(self.calls) > CALL_MEMORY:
            self.calls.popitem(last=False)
        self._emit({"type": "call.answering", "call_id": call.call_id, "from": caller, "verified": verified})
        self._emit(call.live_event([], snapshot=True))
        call.watcher = self._spawn(self._watch(call))
        return call

    def end(self, summary: Mapping[str, Any]) -> dict[str, Any] | None:
        """Settle the call a ``call_summary`` names; returns the ``call.ended`` event, or None for a repeat."""
        call = self.calls.get(str(summary.get("call_id") or ""))
        if call is None:
            # The call began before this process did: report what the summary says.
            now = self._clock()
            event = _ended(str(summary.get("call_id") or ""), str(summary.get("caller") or ""), None, now, summary)
            self._emit(event)
            return event
        if call.status == "ended":
            return None
        return self._settle(call, summary)

    def take_over(self, call_id: str, to: Any) -> None:
        """Transfer a live call to the user's own number, as they asked from the phone."""
        call = self.calls.get(call_id)
        refusal = self._refusal(call, to)
        if call is None or self._telnyx is None or refusal is not None:
            self._emit({"type": "call.take_over.failed", "call_id": call_id, "error": refusal})
            return
        call.status = "transferring"
        call.attempts += 1
        self._emit(call.live_event([]))
        self._spawn(self._transfer(self._telnyx, call, str(to)))

    def snapshots(self) -> list[dict[str, Any]]:
        """``call.live`` with every line for each call still live (sent when the phone resumes)."""
        return [call.live_event(call.lines, snapshot=True) for call in self.calls.values() if call.status != "ended"]

    def _refusal(self, call: LiveCall | None, to: Any) -> str | None:
        if call is None:
            return "Rivet is not on that call."
        if call.status == "ended":
            return "That call has already ended."
        if call.status == "transferring":
            return "That call is already being transferred to you."
        if not isinstance(to, str) or not E164.match(to):
            return "Your own number must be in E.164 form, like +18015550100. Set it in Settings › Call answering."
        if self._telnyx is None:
            return "Taking over calls needs the brain's Telnyx connection."
        return None

    async def _transfer(self, telnyx: TelnyxRunner, call: LiveCall, to: str) -> None:
        command = f"rivet-take-over-{call.call_id}-{call.attempts}"
        code = take_over_code(call.caller, call.started_at, call.conversation_id, to, command)
        try:
            outcome = await telnyx.execute(code, label=f"{call.call_id}:take-over", timeout=TAKE_OVER_TIMEOUT)
        except TelnyxRunError as error:
            outcome = {"ok": False, "error": str(error)}
        if isinstance(outcome, Mapping) and outcome.get("ok") is True:
            call.taken_over = True
            logger.info("call_id=<%s> | call transferred to the user", call.call_id)
            return
        logger.warning("call_id=<%s>, outcome=<%s> | take-over failed", call.call_id, outcome)
        if call.status == "transferring":
            call.status = "answering"
            self._emit(call.live_event([]))
        self._emit({"type": "call.take_over.failed", "call_id": call.call_id, "error": _take_over_error(outcome)})

    async def _watch(self, call: LiveCall) -> None:
        warned = False
        while call.status != "ended":
            remaining = call.started_at + self._limit - self._clock()
            if remaining <= 0:
                logger.info("call_id=<%s> | no summary arrived; ending the call", call.call_id)
                self._settle(call, {})
                return
            if self._telnyx is None:
                await asyncio.sleep(remaining)
                continue
            code = watch_code(call.caller, call.started_at, call.conversation_id)
            try:
                outcome = await self._telnyx.execute(code, label=f"{call.call_id}:watch", timeout=WATCH_TIMEOUT)
                self._absorb(call, outcome)
            except Exception as error:  # the watcher also ends the call, so it must outlive any bad read
                log = logger.debug if warned else logger.warning
                log("call_id=<%s>, error=<%s> | could not read the call transcript", call.call_id, error)
                warned = True
            await asyncio.sleep(self._poll)

    def _absorb(self, call: LiveCall, outcome: Any) -> None:
        if not isinstance(outcome, Mapping) or call.status == "ended":
            return
        conversation = outcome.get("conversation_id")
        if call.conversation_id is None and isinstance(conversation, str) and 0 < len(conversation) <= 128:
            call.conversation_id = conversation
        lines = transcript(outcome.get("messages"))
        fresh = lines[call.seen :]
        call.seen = max(call.seen, len(lines))
        if fresh:
            call.lines = (call.lines + fresh)[-LINE_LIMIT:]
            self._emit(call.live_event(fresh))

    def _settle(self, call: LiveCall, summary: Mapping[str, Any]) -> dict[str, Any]:
        call.status = "ended"
        if call.watcher is not None and call.watcher is not asyncio.current_task():
            call.watcher.cancel()
        caller = str(summary.get("caller") or call.caller)
        event = _ended(call.call_id, caller, call.started_at, self._clock(), summary, taken_over=call.taken_over)
        self._emit(event)
        return event


def _ended(
    call_id: str,
    caller: str,
    started_at: float | None,
    ended_at: float,
    summary: Mapping[str, Any],
    *,
    taken_over: bool = False,
) -> dict[str, Any]:
    return {
        "type": "call.ended",
        "call_id": call_id,
        "from": caller,
        "caller_name": str(summary.get("caller_name") or ""),
        "started_at": started_at,
        "ended_at": ended_at,
        "duration": None if started_at is None else max(0, round(ended_at - started_at)),
        "summary": str(summary.get("summary") or ""),
        "outcome": str(summary.get("outcome") or "unknown"),
        "taken_over": taken_over,
    }


def _take_over_error(outcome: Any) -> str:
    if isinstance(outcome, Mapping):
        reason = _TAKE_OVER_ERRORS.get(str(outcome.get("reason")))
        if reason is not None:
            return reason
        if outcome.get("error"):
            return f"Telnyx did not transfer the call: {outcome['error']}"
    return f"Telnyx did not transfer the call: {outcome}"
