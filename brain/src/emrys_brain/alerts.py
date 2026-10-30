"""What an approval says, in plain words: one headline and typed blocks, composed from the call's own input.

The copy is fixed text (no model writes it). Blocks are typed so the phone styles only Emrys's own ``note``
markdown, never a message's text: a note never holds a value from the call's input, which goes in a field or a
quote. Values are shown as the tool will receive them; only headlines are clipped.
"""

from __future__ import annotations

import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import TYPE_CHECKING, Literal, TypedDict

if TYPE_CHECKING:
    from .approvals import Tier

HEADLINE_LIMIT = 80
REASON_LIMIT = 140
_DAYS = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
_MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
_WORD_START = re.compile(r"(?<!^)(?=[A-Z])")
_NOT_CHECKED = "Emrys can't tell what this does, so it asks first."
_ON_TELNYX = "Runs on your Telnyx account."
_NO_POLICY = "Emrys has no rule for this tool, so it asks first."
_TAP_CHECKED = "Emrys checks the button still says this before it taps."


class FieldBlock(TypedDict):
    """A labelled value ("To", "+18015550100")."""

    kind: Literal["field"]
    label: str
    value: str


class QuoteBlock(TypedDict):
    """Text that will be sent or written as is ("Message", the text)."""

    kind: Literal["quote"]
    label: str
    text: str


class NoteBlock(TypedDict):
    """Emrys's own explanation, in markdown."""

    kind: Literal["note"]
    markdown: str


Block = FieldBlock | QuoteBlock | NoteBlock


@dataclass(frozen=True)
class Alert:
    """An approval's headline and body."""

    headline: str
    blocks: tuple[Block, ...] = ()


ALERT_TOOLS: frozenset[str] = frozenset(
    {
        "sms_send",
        "phone_call",
        "notification_reply",
        "calendar_create",
        "screen_act",
        "email_send",
        "email_reply",
        "email_mark",
        "call_answering_setup",
        "telnyx_execute",
    }
)
"""Tools with their own alert copy; ``alert_for`` treats any other tool as one Emrys doesn't know."""


def clip(text: str) -> str:
    """``text`` for a headline: on one line, cut at a space and ended with an ellipsis past 80 characters."""
    line = text.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
    if len(line) <= HEADLINE_LIMIT:
        return line
    cut = line.rfind(" ", 0, HEADLINE_LIMIT + 1)
    return (line[:cut] if cut > 0 else line[:HEADLINE_LIMIT]) + "…"


def when(start: str, end: str = "") -> str:
    """An event time in fixed English ("Tue Oct 27, 9:00 AM to 10:00 AM"); the raw text when it isn't ISO-8601."""
    try:
        begins = datetime.fromisoformat(start)
    except ValueError:
        return start
    text = f"{_day(begins)}, {_clock(begins)}"
    try:
        ends = datetime.fromisoformat(end) if end else None
    except ValueError:
        ends = None
    if ends is None:
        return text
    if ends.date() == begins.date():
        return f"{text} to {_clock(ends)}"
    return f"{text} to {_day(ends)}, {_clock(ends)}"


def operation_summary(name: str, summary: str) -> str:
    """How a Telnyx operation reads: its catalog summary, else its name split into words."""
    text = summary.strip().removesuffix(".").strip()
    if text:
        return text
    words = _WORD_START.split(name.removesuffix("_2"))
    return " ".join([words[0], *(word.lower() for word in words[1:])])


def because_you_asked(prompt: object) -> str | None:
    """Why a conversation turn's approvals are asked: what the user said."""
    if isinstance(prompt, str) and prompt.strip():
        return f"You asked: “{clip(prompt.strip())}”"
    return None


def because_task(goal: str) -> str:
    """Why a task's approvals are asked."""
    return f"For a task you gave Emrys: “{clip(goal.strip())}”"


def task_reason(value: object) -> str | None:
    """A task's ``reason`` from the phone, kept plain and short."""
    if not isinstance(value, str):
        return None
    text = " ".join(value.split())
    return text[:REASON_LIMIT] or None


def alert_for(tool: str, tool_input: object, tier: Tier, operations: Sequence[Mapping[str, str]]) -> Alert:
    """The approval copy for one call.

    Args:
        tool: Tool name.
        tool_input: The call's input as the model sent it.
        tier: The call's risk tier.
        operations: For ``telnyx_execute``, each ``call_tool`` target with its ``name``, ``summary`` and ``tier``.
    """
    data: Mapping[str, object] = tool_input if isinstance(tool_input, Mapping) else {}

    def text(key: str) -> str:
        value = data.get(key)
        return "" if value is None else value if isinstance(value, str) else str(value)

    if tool == "sms_send":
        return Alert(
            f"Text {text('to')}: “{clip(text('body'))}”",
            (_field("To", text("to")), _quote("Message", text("body"))),
        )
    if tool == "phone_call":
        return Alert(
            f"Call {text('number')}",
            (_field("Number", text("number")), _note("Your phone places the call now. You do the talking.")),
        )
    if tool == "notification_reply":
        return Alert(
            f"Reply to a message: “{clip(text('text'))}”",
            (_quote("Reply", text("text")), _note("Sent from the app the message came from.")),
        )
    if tool == "calendar_create":
        blocks: list[Block] = [_field("When", when(text("start"), text("end")))]
        if text("location"):
            blocks.append(_field("Where", text("location")))
        if text("description"):
            blocks.append(_quote("Notes", text("description")))
        return Alert(f"Add “{text('title')}” to your calendar", tuple(blocks))
    if tool == "screen_act":
        return _screen_alert(text("action"), text("label"))
    if tool == "email_send":
        blocks = [_field("To", text("to"))]
        if text("cc"):
            blocks.append(_field("Copy to", text("cc")))
        blocks += [_field("Subject", text("subject")), _quote("Message", text("body"))]
        return Alert(f"Email {text('to')}: “{clip(text('subject'))}”", tuple(blocks))
    if tool == "email_reply":
        everyone = " and everyone on the email" if data.get("reply_all") is True else ""
        return Alert(
            f"Reply to {text('to')}{everyone}: “{clip(text('body'))}”",
            (_field("To", text("to")), _quote("Reply", text("body"))),
        )
    if tool == "email_mark":
        return _mark_alert(text("action"), text("folder"))
    if tool == "call_answering_setup":
        number = text("number") or "a Telnyx number"
        if data.get("reassign") is True:
            return Alert(
                f"Move {number} to Emrys's call answering",
                (
                    _field("Number", number),
                    _note("Calls to this number stop going where they go now, and Emrys answers them instead."),
                ),
            )
        return Alert(
            f"Set up call answering on {number}",
            (
                _field("Number", number),
                _note(
                    "Creates Emrys's answering assistant on your Telnyx account and sends calls to this number to it."
                ),
            ),
        )
    if tool == "telnyx_execute":
        return _telnyx_alert(tier, operations)
    return Alert(
        f"Use a tool Emrys doesn't know: {tool}", (_note("Emrys has no rule for this tool, so it asks first."),)
    )


def _screen_alert(action: str, label: str) -> Alert:
    if action != "tap":
        return Alert("Do something Emrys doesn't recognise in the app on screen", (_note(_NOT_CHECKED),))
    if not label.strip():
        return Alert("Tap something without a label in the app on screen", (_note(_NOT_CHECKED),))
    return Alert(
        f"Tap “{label}” in the app on screen", (_note("Emrys checks the button still says this before it taps."),)
    )


_MARKS = {"read": "Mark an email as read", "unread": "Mark an email as unread", "archive": "Archive an email"}


def _mark_alert(action: str, folder: str) -> Alert:
    headline = _MARKS.get(action)
    if headline is None:
        return Alert("Do something Emrys doesn't recognise with an email")
    blocks: list[Block] = [_field("Folder", "Inbox" if folder in ("", "INBOX") else folder)]
    if action == "archive":
        blocks.append(_note("Nothing is deleted."))
    return Alert(headline, tuple(blocks))


def _telnyx_alert(tier: Tier, operations: Sequence[Mapping[str, str]]) -> Alert:
    changes = [op for op in operations if op.get("tier") != "read"]
    lines = [_operation_line(op) for op in changes]
    note = _note("\n".join([*lines, _ON_TELNYX]))
    unknown = list(dict.fromkeys(op.get("name", "") for op in changes if op.get("tier") == "unknown"))
    if tier == "unknown" or unknown:
        blocks: tuple[Block, ...] = (note,)
        if unknown:
            blocks = (_field("Not recognised", ", ".join(unknown)), note)
        return Alert("Run code on your Telnyx account that Emrys can't check", blocks)
    if len(changes) == 1:
        only = changes[0]
        return Alert(
            f"{operation_summary(only.get('name', ''), only.get('summary', ''))} on your Telnyx account", (note,)
        )
    return Alert(f"{len(changes)} changes on your Telnyx account", (note,))


def _operation_line(op: Mapping[str, str]) -> str:
    if op.get("tier") == "unknown":
        return "- Something Emrys doesn't recognise"
    return f"- {operation_summary(op.get('name', ''), op.get('summary', ''))}"


def _day(moment: datetime) -> str:
    return f"{_DAYS[moment.weekday()]} {_MONTHS[moment.month - 1]} {moment.day}"


def _clock(moment: datetime) -> str:
    hour = moment.hour % 12 or 12
    return f"{hour}:{moment.minute:02d} {'AM' if moment.hour < 12 else 'PM'}"


def _field(label: str, value: str) -> FieldBlock:
    return {"kind": "field", "label": label, "value": value}


def _quote(label: str, text: str) -> QuoteBlock:
    return {"kind": "quote", "label": label, "text": text}


def _note(markdown: str) -> NoteBlock:
    return {"kind": "note", "markdown": markdown}
