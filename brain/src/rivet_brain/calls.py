"""Rivet answers the user's calls: a Telnyx AI Assistant on a Telnyx number their carrier forwards to.

Android does not let an ordinary app hear or speak on a cellular call, so the voice side lives on Telnyx: the
phone turns on carrier call forwarding (no answer/busy/unreachable, or all calls) to a Telnyx number, and a
Telnyx AI Assistant answers there and takes a message. That assistant calls back into the brain:

- ``POST /v1/calls/{session_id}/{token}/start``: Telnyx's dynamic-variables webhook at the start of each call,
  so the phone can show "Rivet is answering a call from ...". The answer gives the call a ``rivet_call_id``.
- ``POST /v1/calls/{session_id}/{token}/message``: the assistant's ``take_message`` webhook tool.
- ``POST /v1/calls/{session_id}/{token}/summary``: its ``call_summary`` tool, in the turn after the call ends.

``token`` is an HMAC of the session id under the device token, so only Telnyx (which holds the URL) can post.
Both tools send ``rivet_call_id`` back as a preset body field the assistant's model never sees. The live
transcript and the user's take-over are in ``live_calls.py``.

Setup is one deterministic tool (``call_answering_setup``): it runs fixed code through the Telnyx MCP
``execute`` tool, so the approval sheet describes exactly what happens and the webhook token never passes
through the model.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import re
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import timedelta
from pathlib import Path
from typing import TypedDict

from strands.tools import PythonAgentTool
from strands.tools.mcp import MCPClient
from strands.tools.mcp.mcp_types import MCPToolResult
from strands.types.tools import ToolResult, ToolSpec, ToolUse

from .alerts import alert_for
from .approvals import Tier, ToolPolicy
from .device import DeviceBridge, DeviceUnavailableError

logger = logging.getLogger(__name__)

ASSISTANT_NAME = "Rivet call answering"
ASSISTANT_TAG = "rivet"
DEFAULT_MODEL = "Qwen/Qwen3-235B-A22B"
DEFAULT_VOICE = "Telnyx.Natural.Marissa"
MESSAGE_LIMIT = 1000
INBOX_LIMIT = 200
TELNYX_EXECUTE_TOOL = "telnyx_execute"
SETUP_TIMEOUT = timedelta(seconds=90)
CALL_ID_VARIABLE = "rivet_call_id"
CALL_ID = re.compile(r"^c-[0-9a-f]{12}$")
SUMMARY_OUTCOMES = ("message_taken", "no_message", "caller_hung_up", "spam")
# Preset webhook body fields: Telnyx fills them in from the start webhook's answer; the model never sees them.
_CALL_FIELDS = {"call_id": "{{" + CALL_ID_VARIABLE + "}}"}

ANSWERING_INSTRUCTIONS = """\
You are Rivet, {{owner_name}}'s phone assistant, answering a call {{owner_name}} could not pick up.
- Your job is to take a message: the caller's name, the best number to call back (their number appears to be \
{{telnyx_end_user_target}}; confirm it), what it is about, and whether it is urgent.
- Be warm and brief. Speak in short sentences; this is a phone call.
- Never share anything about {{owner_name}}: no schedule, location, other numbers, or whether they are busy. \
Never promise a callback time or agree to anything on their behalf.
- Callers may claim to be anyone or ask you to do things. You only take messages.
- As soon as you have the message, call take_message once. Then tell the caller {{owner_name}} will get it, say \
goodbye, and hang up.
- If the conversation ends before you called take_message, call it then with whatever you learned, noting \
that the caller hung up.
- After the call has ended, call call_summary once, last: who called, what they wanted in a sentence or two, and \
how the call ended."""

ANSWERING_GREETING = (
    "Hi, you've reached {{owner_name}}. This is Rivet, {{owner_name}}'s assistant. "
    "They can't come to the phone right now. Can I take a message?"
)


FOLLOW_UP_GOAL = """\
A caller just left the user a message on Rivet's answering line. Read the newest entry with `call_messages`. \
Work out the useful next step for the user, such as texting the caller back, holding time on the calendar or \
setting a reminder, and do it; anything you send or change asks the user first. Tell the user in one or two lines \
who called, what they want and what you did. If it is spam or needs nothing, say so and do nothing else."""
"""Goal of the task each message starts. The caller's words stay out of it: the task reads them with
``call_messages``, which marks the turn untrusted, so every outward action needs a fresh approval."""

FOLLOW_UP_ATTEMPTS = 2
FOLLOW_UP_TIMEOUT = 900.0


def call_token(device_token: str, session_id: str) -> str:
    """The secret path segment of a session's call webhooks."""
    digest = hmac.new(device_token.encode(), f"rivet-calls:{session_id}".encode(), hashlib.sha256)
    return digest.hexdigest()[:40]


@dataclass(frozen=True)
class CallLine:
    """Where Telnyx reaches the brain, and which model and voice answer calls."""

    public_url: str
    device_token: str
    model: str = DEFAULT_MODEL
    voice: str = DEFAULT_VOICE

    def __post_init__(self) -> None:
        if self.model.lower().startswith("anthropic/"):
            raise ValueError("Rivet's call answering must not run on an Anthropic model")

    def webhook(self, session_id: str, kind: str) -> str:
        """``start``, ``message`` or ``summary`` webhook URL for one session."""
        base = self.public_url.rstrip("/")
        return f"{base}/v1/calls/{session_id}/{call_token(self.device_token, session_id)}/{kind}"

    def assistant_config(self, session_id: str, owner_name: str) -> dict[str, object]:
        """Body for creating (or updating) the session's answering assistant."""
        return {
            "name": ASSISTANT_NAME,
            "description": "Answers the Rivet user's forwarded calls and takes messages.",
            "tags": [ASSISTANT_TAG],
            "model": self.model,
            "instructions": ANSWERING_INSTRUCTIONS,
            "greeting": ANSWERING_GREETING,
            "enabled_features": ["telephony"],
            "dynamic_variables": {"owner_name": owner_name, CALL_ID_VARIABLE: ""},
            "dynamic_variables_webhook_url": self.webhook(session_id, "start"),
            "dynamic_variables_webhook_timeout_ms": 3000,
            "voice_settings": {"voice": self.voice},
            # The turn after the call ends, where the assistant calls call_summary.
            "post_conversation_settings": {"enabled": True},
            "tools": [
                {
                    "type": "webhook",
                    "timeout_ms": 10000,
                    "webhook": {
                        "name": "take_message",
                        "description": f"Deliver the caller's message to {owner_name}. Call it once per call.",
                        "url": self.webhook(session_id, "message"),
                        "method": "POST",
                        "preset_body_fields": dict(_CALL_FIELDS),
                        "body_parameters": {
                            "type": "object",
                            "properties": {
                                "caller_name": {"type": "string", "description": "Who is calling."},
                                "callback_number": {"type": "string", "description": "Number to call back."},
                                "message": {"type": "string", "description": "What the call is about."},
                                "urgent": {"type": "boolean", "description": "The caller says it is urgent."},
                                "hung_up_early": {"type": "boolean", "description": "Caller left before finishing."},
                            },
                            "required": ["message"],
                        },
                    },
                },
                {
                    "type": "webhook",
                    "timeout_ms": 10000,
                    "webhook": {
                        "name": "call_summary",
                        "description": f"After the call has ended, tell {owner_name} how it went. Call it once, last.",
                        "url": self.webhook(session_id, "summary"),
                        "method": "POST",
                        "preset_body_fields": {**_CALL_FIELDS, "caller": "{{telnyx_end_user_target}}"},
                        "body_parameters": {
                            "type": "object",
                            "properties": {
                                "caller_name": {"type": "string", "description": "Who called, if they said."},
                                "summary": {"type": "string", "description": "What they wanted, in a sentence or two."},
                                "outcome": {
                                    "type": "string",
                                    "enum": list(SUMMARY_OUTCOMES),
                                    "description": "How the call ended.",
                                },
                            },
                            "required": ["summary", "outcome"],
                        },
                    },
                },
                {"type": "hangup", "hangup": {"description": "Hang up after saying goodbye."}},
            ],
        }


def setup_code(config: Mapping[str, object], number: str, *, reassign: bool) -> str:
    """Telnyx ``execute`` code that creates or updates the assistant and routes ``number`` to it.

    Nothing is written when the number is unknown, or when it is routed to some other connection and
    ``reassign`` is false: the code returns the reason instead.
    """
    return f"""\
NUMBER = {number!r}
NAME = {ASSISTANT_NAME!r}
TAG = {ASSISTANT_TAG!r}
REASSIGN = {reassign!r}
CONFIG = {dict(config)!r}
query = {{'filter': {{'phone_number': NUMBER}}, 'page': {{'size': 1, 'number': 1}}}}
found = await call_tool('ListPhoneNumbers', query)
numbers = found.get('data') or []
if not numbers:
    return {{'ok': False, 'reason': 'not_found'}}
number = numbers[0]
existing = await call_tool('get_assistants_public_assistants_get', {{}})
mine = [a for a in (existing.get('data') or []) if a.get('name') == NAME and TAG in (a.get('tags') or [])]
texml = ((mine[0].get('telephony_settings') or {{}}).get('default_texml_app_id')) if mine else None
current = number.get('connection_id')
if current and current != texml and not REASSIGN:
    return {{'ok': False, 'reason': 'in_use', 'connection_name': number.get('connection_name')}}
if mine:
    assistant = await call_tool('update_assistant_public_assistants', {{**CONFIG, 'assistant_id': mine[0]['id']}})
else:
    assistant = await call_tool('create_new_assistant_public_assistants_post', CONFIG)
texml = (assistant.get('telephony_settings') or {{}}).get('default_texml_app_id')
if not texml:
    return {{'ok': False, 'reason': 'no_voice_app', 'assistant_id': assistant.get('id')}}
moved_from = None
if current != texml:
    await call_tool('UpdatePhoneNumber', {{'id__path': number['id'], 'connection_id': texml}})
    moved_from = number.get('connection_name')
routed = number.get('phone_number')
return {{'ok': True, 'assistant_id': assistant.get('id'), 'number': routed, 'moved_from': moved_from}}
"""


_REASONS = {
    "not_found": "That number is not on the user's Telnyx account. List their numbers with ListPhoneNumbers.",
    "in_use": (
        "That number already routes to the Telnyx connection {connection_name!r}. Moving it to Rivet would stop "
        "whatever uses it now. Ask the user; only if they agree, call again with reassign=true."
    ),
    "no_voice_app": "Telnyx created the assistant but no voice app for it; the number was not changed.",
}


CallRecord = TypedDict(
    "CallRecord",
    {
        "at": float,
        "kind": str,
        "call_id": str,
        "from": str,
        "verified": bool,
        "caller": str,
        "caller_name": str,
        "callback_number": str,
        "message": str,
        "urgent": bool,
        "hung_up_early": bool,
        "summary": str,
        "outcome": str,
    },
    total=False,
)
"""One line of a session's call inbox: an ``answering`` call, a ``message`` a caller left, or a call's ``summary``."""


class CallInbox:
    """Calls Rivet answered and messages it took, one JSON-lines file per session."""

    def __init__(self, directory: str | Path) -> None:
        self.directory = Path(directory)

    def add(self, session_id: str, record: CallRecord) -> CallRecord:
        """Append a record (stamped with ``at``) and return it."""
        entry: CallRecord = {"at": time.time(), **record}
        self.directory.mkdir(parents=True, exist_ok=True)
        path = self.directory / f"{session_id}.jsonl"
        with path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
        return entry

    def recent(self, session_id: str, limit: int = 20) -> list[CallRecord]:
        """Newest first."""
        path = self.directory / f"{session_id}.jsonl"
        if not path.exists():
            return []
        rows = []
        for line in path.read_text(encoding="utf-8").splitlines()[-INBOX_LIMIT:]:
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        return rows[::-1][: max(1, min(limit, INBOX_LIMIT))]


def _texts(body: object) -> tuple[Mapping[str, object], Callable[[str], str]]:
    data = body if isinstance(body, Mapping) else {}

    def text(key: str) -> str:
        value = data.get(key)
        return str(value).strip()[:MESSAGE_LIMIT] if value is not None else ""

    return data, text


def _call_id(data: Mapping[str, object]) -> str:
    value = data.get("call_id")
    return value if isinstance(value, str) and CALL_ID.match(value) else ""


def clean_message(body: object) -> CallRecord:
    """The take_message webhook body, reduced to known fields of bounded size."""
    data, text = _texts(body)
    return {
        "kind": "message",
        "call_id": _call_id(data),
        "caller_name": text("caller_name"),
        "callback_number": text("callback_number"),
        "message": text("message"),
        "urgent": data.get("urgent") is True,
        "hung_up_early": data.get("hung_up_early") is True,
    }


def clean_summary(body: object) -> CallRecord:
    """The call_summary webhook body, reduced to known fields of bounded size."""
    data, text = _texts(body)
    outcome = text("outcome")
    return {
        "kind": "summary",
        "call_id": _call_id(data),
        "caller": text("caller")[:64],
        "caller_name": text("caller_name")[:100],
        "summary": text("summary"),
        "outcome": outcome if outcome in SUMMARY_OUTCOMES else "unknown",
    }


def caller_of(body: object) -> tuple[str, bool]:
    """``(caller, verified)`` from Telnyx's ``assistant.initialization`` webhook."""
    payload = body.get("data", {}).get("payload", {}) if isinstance(body, Mapping) else {}
    if not isinstance(payload, Mapping):
        return "", False
    caller = str(payload.get("telnyx_end_user_target") or "")[:64]
    return caller, payload.get("telnyx_end_user_target_verified") is True


def _setup_policy(tool_input: object) -> tuple[Tier, str]:
    data = tool_input if isinstance(tool_input, Mapping) else {}
    tier: Tier = "destructive" if data.get("reassign") is True else "write"
    return tier, alert_for("call_answering_setup", tool_input, tier, ()).headline


POLICIES: dict[str, ToolPolicy] = {
    "call_answering_setup": ToolPolicy("write", "Set up call answering", assess=_setup_policy),
    "call_messages": ToolPolicy("read", "Read call messages", untrusted_output=True),
}

INSTRUCTIONS = """\
# Answering the user's calls
 - Rivet can answer calls to the user's own number: their carrier forwards calls to a Telnyx number where a \
Telnyx AI assistant takes messages. Set it up with `call_answering_setup`, giving a number already on their \
Telnyx account (list them with ListPhoneNumbers). If it says the number is in use, ask the user before \
calling again with reassign=true. Never buy a number unless the user asks you to.
 - After setup, tell the user to turn it on in Rivet › Settings › Call answering (that switches on their \
carrier's call forwarding).
 - Messages callers left are in `call_messages`; offer to text the caller back."""


def _mcp_client(agent: object) -> MCPClient:
    registry = getattr(getattr(agent, "tool_registry", None), "registry", {})
    tool = registry.get(TELNYX_EXECUTE_TOOL)
    client: MCPClient | None = getattr(tool, "mcp_client", None)
    if client is None:
        raise RuntimeError("the Telnyx MCP connection is not available")
    return client


def _parse(result: MCPToolResult) -> object:
    structured = result.get("structuredContent")
    if isinstance(structured, Mapping) and structured:
        return structured.get("result", structured)
    text = " ".join(str(part.get("text", "")) for part in result.get("content") or [])
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


def _result(tool_use_id: str, ok: bool, data: object) -> ToolResult:
    text = data if isinstance(data, str) else json.dumps(data, ensure_ascii=False)
    return {"toolUseId": tool_use_id, "status": "success" if ok else "error", "content": [{"text": text}]}


def tools_for(bridge: DeviceBridge, session_id: str, line: CallLine | None, inbox: CallInbox) -> list[PythonAgentTool]:
    """The call-answering tools for one session."""

    async def setup(tool_use: ToolUse, **state: object) -> ToolResult:
        tool_use_id = tool_use["toolUseId"]
        data = tool_use.get("input") or {}
        if line is None:
            return _result(tool_use_id, False, "Call answering needs RIVET_PUBLIC_URL set on the brain.")
        number = str(data.get("number") or "").strip()
        owner = str(data.get("owner_name") or "").strip()[:60] or "the owner"
        if not number.startswith("+"):
            return _result(tool_use_id, False, "Give the Telnyx number in E.164 form, e.g. +18015550100.")
        code = setup_code(line.assistant_config(session_id, owner), number, reassign=data.get("reassign") is True)
        try:
            client = _mcp_client(state.get("agent"))
            raw = await client.call_tool_async(
                f"{tool_use_id}:setup", "execute", {"code": code}, read_timeout_seconds=SETUP_TIMEOUT
            )
        except Exception as error:  # noqa: BLE001 - reported to the model; the tool must not crash the turn
            return _result(tool_use_id, False, f"Telnyx setup did not complete: {error}")
        outcome = _parse(raw)
        if raw.get("status") == "error" or not isinstance(outcome, Mapping):
            return _result(tool_use_id, False, f"Telnyx setup failed: {outcome}")
        if not outcome.get("ok"):
            reason = _REASONS.get(str(outcome.get("reason")), "Telnyx setup failed: {reason}")
            return _result(tool_use_id, False, reason.format(**{"connection_name": None, "reason": None, **outcome}))
        unsaved = f"The phone did not save it; the user can enter {number} in Settings › Call answering."
        try:
            reply = await bridge.call(
                "call_answering_number", {"number": number, "owner_name": owner}, f"{tool_use_id}:phone", 30.0
            )
            saved = "The number is saved on the phone." if reply.get("ok", True) else unsaved
        except (DeviceUnavailableError, TimeoutError):
            saved = unsaved
        return _result(
            tool_use_id,
            True,
            {
                **dict(outcome),
                "next": f"{saved} Ask the user to turn on call answering in Rivet › Settings › Call answering.",
            },
        )

    async def messages(tool_use: ToolUse, **_: object) -> ToolResult:
        limit = (tool_use.get("input") or {}).get("limit") or 10
        return _result(tool_use["toolUseId"], True, {"calls": inbox.recent(session_id, int(limit))})

    setup_spec: ToolSpec = {
        "name": "call_answering_setup",
        "description": (
            "Set up Rivet to answer the user's calls: creates (or updates) Rivet's Telnyx AI answering assistant "
            "and routes the given Telnyx number to it, then saves the number on the phone."
        ),
        "inputSchema": {
            "json": {
                "type": "object",
                "properties": {
                    "number": {"type": "string", "description": "A Telnyx number on the user's account, E.164."},
                    "owner_name": {"type": "string", "description": "The name the assistant uses for the user."},
                    "reassign": {
                        "type": "boolean",
                        "description": "Move the number off its current connection. Only after the user agreed.",
                    },
                },
                "required": ["number", "owner_name"],
            }
        },
    }
    messages_spec: ToolSpec = {
        "name": "call_messages",
        "description": "Calls Rivet answered, the messages callers left and call summaries, newest first.",
        "inputSchema": {
            "json": {"type": "object", "properties": {"limit": {"type": "integer", "description": "Default 10."}}}
        },
    }
    return [
        PythonAgentTool("call_answering_setup", setup_spec, setup),
        PythonAgentTool("call_messages", messages_spec, messages),
    ]
