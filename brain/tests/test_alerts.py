from __future__ import annotations

from typing import Any

import pytest

from rivet_brain.alerts import (
    Alert,
    alert_for,
    because_task,
    because_you_asked,
    clip,
    operation_summary,
    task_reason,
    when,
)
from rivet_brain.approvals import RivetApprovals

NOTE_CHECK = "Rivet can't tell what this does, so it asks first."


def test_clip() -> None:
    assert clip("Friday at 9 works!") == "Friday at 9 works!"
    assert clip("line one\nline two\r\nthree") == "line one line two three"
    words = "word " * 30
    assert clip(words) == ("word " * 16).rstrip() + "…"
    assert clip("x" * 100) == "x" * 80 + "…"
    exact = "a" * 80 + " tail"
    assert clip(exact) == "a" * 80 + "…"
    assert clip("a" * 80) == "a" * 80


def test_when() -> None:
    assert when("2026-10-27T09:00") == "Tue Oct 27, 9:00 AM"
    assert when("2026-10-27T09:00", "2026-10-27T10:00") == "Tue Oct 27, 9:00 AM to 10:00 AM"
    assert when("2026-10-27T23:30", "2026-10-28T00:15") == "Tue Oct 27, 11:30 PM to Wed Oct 28, 12:15 AM"
    assert when("2026-10-27T12:05-07:00", "soon") == "Tue Oct 27, 12:05 PM"
    assert when("tomorrow morning") == "tomorrow morning"


def test_operation_summary_fallback() -> None:
    assert operation_summary("CreateNumberOrder", "") == "Create number order"
    assert operation_summary("ListPhoneNumbers_2", "") == "List phone numbers"
    assert operation_summary("CreateRoom", "Create a room.") == "Create a room"


def test_because() -> None:
    assert because_you_asked("text Dana") == "You asked: “text Dana”"
    assert because_you_asked([{"text": "x"}]) is None
    assert because_task("Reply to Ada") == "For a task you gave Rivet: “Reply to Ada”"
    assert task_reason("  A new\nWhatsApp message arrived.  ") == "A new WhatsApp message arrived."
    assert task_reason("x" * 500) == "x" * 140
    assert task_reason(3) is None and task_reason("  ") is None


def _alert(tool: str, tool_input: Any, tier: Any = "send", operations: Any = ()) -> Alert:
    return alert_for(tool, tool_input, tier, operations)


@pytest.mark.parametrize(
    ("tool", "tool_input", "headline", "blocks"),
    [
        (
            "sms_send",
            {"to": "+18015550100", "body": "Friday at 9 works!"},
            "Text +18015550100: “Friday at 9 works!”",
            [
                {"kind": "field", "label": "To", "value": "+18015550100"},
                {"kind": "quote", "label": "Message", "text": "Friday at 9 works!"},
            ],
        ),
        (
            "phone_call",
            {"number": "+18015550100"},
            "Call +18015550100",
            [
                {"kind": "field", "label": "Number", "value": "+18015550100"},
                {"kind": "note", "markdown": "Your phone places the call now. You do the talking."},
            ],
        ),
        (
            "notification_reply",
            {"key": "k", "text": "On my way"},
            "Reply to a message: “On my way”",
            [
                {"kind": "quote", "label": "Reply", "text": "On my way"},
                {"kind": "note", "markdown": "Sent from the app the message came from."},
            ],
        ),
        (
            "calendar_create",
            {"title": "Dentist", "start": "2026-10-27T09:00", "end": "2026-10-27T10:00"},
            "Add “Dentist” to your calendar",
            [{"kind": "field", "label": "When", "value": "Tue Oct 27, 9:00 AM to 10:00 AM"}],
        ),
        (
            "calendar_create",
            {"title": "Dentist", "start": "x", "location": "Main St", "description": "Bring *card*"},
            "Add “Dentist” to your calendar",
            [
                {"kind": "field", "label": "When", "value": "x"},
                {"kind": "field", "label": "Where", "value": "Main St"},
                {"kind": "quote", "label": "Notes", "text": "Bring *card*"},
            ],
        ),
        (
            "screen_act",
            {"action": "tap", "label": "Send"},
            "Tap “Send” in the app on screen",
            [{"kind": "note", "markdown": "Rivet checks the button still says this before it taps."}],
        ),
        (
            "screen_act",
            {"action": "tap"},
            "Tap something without a label in the app on screen",
            [{"kind": "note", "markdown": NOTE_CHECK}],
        ),
        (
            "screen_act",
            {"action": "explode"},
            "Do something Rivet doesn't recognise in the app on screen",
            [{"kind": "note", "markdown": NOTE_CHECK}],
        ),
        (
            "email_send",
            {"to": "a@x.com", "cc": "b@x.com", "subject": "Lunch", "body": "Friday?"},
            "Email a@x.com: “Lunch”",
            [
                {"kind": "field", "label": "To", "value": "a@x.com"},
                {"kind": "field", "label": "Copy to", "value": "b@x.com"},
                {"kind": "field", "label": "Subject", "value": "Lunch"},
                {"kind": "quote", "label": "Message", "text": "Friday?"},
            ],
        ),
        (
            "email_reply",
            {"uid": 1, "to": "a@x.com", "body": "Yes", "reply_all": True},
            "Reply to a@x.com and everyone on the email: “Yes”",
            [{"kind": "field", "label": "To", "value": "a@x.com"}, {"kind": "quote", "label": "Reply", "text": "Yes"}],
        ),
        (
            "email_reply",
            {"uid": 1, "to": "a@x.com", "body": "Yes"},
            "Reply to a@x.com: “Yes”",
            [{"kind": "field", "label": "To", "value": "a@x.com"}, {"kind": "quote", "label": "Reply", "text": "Yes"}],
        ),
        (
            "email_mark",
            {"uid": 1, "action": "read"},
            "Mark an email as read",
            [{"kind": "field", "label": "Folder", "value": "Inbox"}],
        ),
        (
            "email_mark",
            {"uid": 1, "action": "unread", "folder": "Work"},
            "Mark an email as unread",
            [{"kind": "field", "label": "Folder", "value": "Work"}],
        ),
        (
            "email_mark",
            {"uid": 1, "action": "archive", "folder": "INBOX"},
            "Archive an email",
            [
                {"kind": "field", "label": "Folder", "value": "Inbox"},
                {"kind": "note", "markdown": "Nothing is deleted."},
            ],
        ),
        ("email_mark", {"uid": 1, "action": "delete"}, "Do something Rivet doesn't recognise with an email", []),
        (
            "call_answering_setup",
            {"number": "+18015550100"},
            "Set up call answering on +18015550100",
            [
                {
                    "kind": "note",
                    "markdown": "Creates Rivet's answering assistant on your Telnyx account and sends calls to "
                    "+18015550100 to it.",
                }
            ],
        ),
        (
            "call_answering_setup",
            {"number": "+18015550100", "reassign": True},
            "Move +18015550100 to Rivet's call answering",
            [
                {
                    "kind": "note",
                    "markdown": "Calls to +18015550100 stop going where they go now, and Rivet answers them instead.",
                }
            ],
        ),
        (
            "mystery",
            {},
            "Use a tool Rivet doesn't know: mystery",
            [{"kind": "note", "markdown": "Rivet has no rule for this tool, so it asks first."}],
        ),
    ],
)
def test_alert_rows(tool: str, tool_input: dict[str, Any], headline: str, blocks: list[dict[str, str]]) -> None:
    alert = _alert(tool, tool_input)
    assert alert.headline == headline and list(alert.blocks) == blocks


def test_headlines_clip_but_blocks_do_not() -> None:
    body = "word " * 40
    alert = _alert("sms_send", {"to": "+1", "body": body})
    assert alert.headline == f"Text +1: “{clip(body)}”" and alert.headline.endswith("…”")
    assert alert.blocks[1] == {"kind": "quote", "label": "Message", "text": body}


def test_non_string_inputs_are_shown_as_text() -> None:
    assert _alert("phone_call", {"number": 911}).headline == "Call 911"
    assert _alert("sms_send", "not a mapping").headline == "Text : “”"


def _telnyx(code: str) -> Alert:
    return RivetApprovals().assess("telnyx_execute", {"code": code}).alert


def test_telnyx_alerts() -> None:
    one = _telnyx("await call_tool('ListPhoneNumbers', {})\nawait call_tool('SendMessage', {})")
    assert one.headline == "Send a message on your Telnyx account"
    assert one.blocks == ({"kind": "note", "markdown": "- Send a message\nRuns on your Telnyx account."},)
    two = _telnyx("await call_tool('SendMessage', {})\nawait call_tool('DeleteMessagingProfile', {})")
    assert two.headline == "2 changes on your Telnyx account"
    assert two.blocks[0]["kind"] == "note"
    assert two.blocks[0]["markdown"] == ("- Send a message\n- Delete a messaging profile\nRuns on your Telnyx account.")
    odd = _telnyx("await call_tool('SendMessage', {})\nawait call_tool('NoSuchTool', {})")
    assert odd.headline == "Run code on your Telnyx account that Rivet can't check"
    assert odd.blocks[0]["markdown"] == (
        "- Send a message\n- Something Rivet doesn't recognise (`NoSuchTool`)\nRuns on your Telnyx account."
    )
    computed = _telnyx("n = 'SendMessage'\nawait call_tool(n, {})")
    assert computed.headline == "Run code on your Telnyx account that Rivet can't check"
    missing = RivetApprovals().assess("telnyx_execute", {}).alert
    assert missing.headline == "Run code on your Telnyx account that Rivet can't check"
