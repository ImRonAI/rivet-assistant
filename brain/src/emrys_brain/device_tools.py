"""The tools Emrys runs on the user's phone, and how each one is approved.

The phone implements them (``android/app/src/main/java/com/hiron/emrys/device/DeviceTools.kt``); here they are
declared to the model as proxies over the session's device bridge. Policies follow the plan's rule: reads run
without asking (third-party content taints the turn), anything that reaches another person or spends money
asks, and the app operator asks at commit points, judged from the label the phone verifies before it taps.
"""

from __future__ import annotations

import re
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from strands.tools import PythonAgentTool

from .alerts import alert_for
from .approvals import Tier, ToolPolicy
from .device import DeviceBridge, device_tool
from .rules import Standing


@dataclass(frozen=True)
class DeviceToolSpec:
    """One phone tool: what the model sees, how it is approved, and how long the phone gets to answer."""

    name: str
    description: str
    properties: Mapping[str, Any]
    required: tuple[str, ...]
    policy: ToolPolicy
    timeout: float = 60.0

    @property
    def input_schema(self) -> dict[str, Any]:
        """JSON schema for the tool input."""
        return {"type": "object", "properties": dict(self.properties), "required": list(self.required)}


def _str(description: str) -> dict[str, Any]:
    return {"type": "string", "description": description}


def _int(description: str) -> dict[str, Any]:
    return {"type": "integer", "description": description}


_TIME = "ISO-8601 local time on the phone, e.g. 2026-10-27T09:00 (or with an offset)."

_PURCHASE_WORDS = (
    "pay",
    "buy",
    "purchase",
    "checkout",
    "check out",
    "place order",
    "order now",
    "subscribe",
    "transfer",
    "send money",
    "donate",
    "book now",
    "rent",
    "upgrade",
)
_DESTRUCTIVE_WORDS = (
    "delete",
    "remove",
    "erase",
    "uninstall",
    "unsubscribe",
    "block",
    "report",
    "discard",
    "reset",
    "clear all",
    "clear data",
    "clear history",
    "sign out",
    "log out",
    "deactivate",
    "close account",
    "cancel subscription",
    "cancel order",
    "cancel booking",
    "cancel reservation",
    "cancel membership",
    "cancel plan",
    "cancel ride",
    "cancel trip",
    "cancel appointment",
)
_SEND_WORDS = ("send", "post", "reply", "share", "tweet", "publish", "comment", "submit", "invite", "call", "forward")
_COMMIT_WORDS = (
    "confirm",
    "ok",
    "yes",
    "accept",
    "allow",
    "agree",
    "done",
    "finish",
    "save",
    "apply",
    "sign",
    "approve",
    "grant",
    "continue to payment",
)
_NAVIGATION = frozenset({"back", "home", "recents", "notifications", "scroll_down", "scroll_up"})


def _mentions(label: str, words: Sequence[str]) -> bool:
    return any(re.search(rf"(?<![a-z]){re.escape(word)}(?![a-z])", label) for word in words)


def screen_act_tier(tool_input: Any) -> tuple[Tier, str]:
    """Approval tier for one app-operator action, from its verified label.

    Navigation and typing run freely (typing commits nothing by itself); a tap asks when its label reads like a
    commit point. A tap with no label cannot be judged, so it gets the strictest tier.
    """
    data = tool_input if isinstance(tool_input, Mapping) else {}
    action = str(data.get("action") or "")
    label = str(data.get("label") or "").strip()
    if action in _NAVIGATION:
        return "read", f"Navigate: {action.replace('_', ' ')}"
    if action == "type":
        return "read", f'Type into "{label}"' if label else "Type into a field"
    if action != "tap":
        return "unknown", f"Unrecognised app action {action!r}"
    if not label:
        return "unknown", "Tap an unlabelled element"
    lowered = label.lower()
    summary = f'Tap "{label}" in the open app'
    if _mentions(lowered, _PURCHASE_WORDS):
        return "purchase", summary
    if _mentions(lowered, _DESTRUCTIVE_WORDS):
        return "destructive", summary
    if _mentions(lowered, _SEND_WORDS):
        return "send", summary
    if _mentions(lowered, _COMMIT_WORDS):
        return "write", summary
    return "read", summary


def _sends(tool: str) -> Callable[[object], tuple[Tier, str]]:
    def assess(tool_input: object) -> tuple[Tier, str]:
        return "send", alert_for(tool, tool_input, "send", ()).headline

    return assess


SMS_STANDING = Standing((("to", "phone"),), frozenset({"to"}), True, lambda scope: f"Texts to {scope['to']}")
CALL_STANDING = Standing(
    (("number", "phone"),), frozenset({"number"}), True, lambda scope: f"Calls to {scope['number']}"
)
CALENDAR_STANDING = Standing((), frozenset(), True, lambda scope: "Adding events to your calendar")
TAP_STANDING = Standing(
    (("label", "text"),),
    frozenset({"label"}),
    False,
    lambda scope: f"Tapping “{scope['label']}” in apps",
    when=(("action", frozenset({"tap"})),),
)


SPECS: tuple[DeviceToolSpec, ...] = (
    DeviceToolSpec(
        "sms_send",
        "Send an SMS from the user's phone number. `to` must be a phone number (look names up with "
        "contacts_search first). Waits for the carrier's sent confirmation.",
        {"to": _str("Recipient phone number, e.g. +18015550100."), "body": _str("Message text.")},
        ("to", "body"),
        ToolPolicy("send", "Send a text", standing=SMS_STANDING, assess=_sends("sms_send")),
        timeout=90.0,
    ),
    DeviceToolSpec(
        "sms_list",
        "Recent SMS threads on the phone, newest first. Filter by a contact name or number.",
        {"contact": _str("Name or number to filter by (optional)."), "limit": _int("Max messages (default 20).")},
        (),
        ToolPolicy("read", "Read text messages", untrusted_output=True),
    ),
    DeviceToolSpec(
        "contacts_search",
        "Find contacts by name, number or email; returns their phone numbers and emails.",
        {"query": _str("Name, number or email."), "limit": _int("Max contacts (default 10).")},
        ("query",),
        ToolPolicy("read", "Search contacts"),
    ),
    DeviceToolSpec(
        "calls_recent",
        "Recent calls (incoming, outgoing, missed) from the phone's call log.",
        {"limit": _int("Max calls (default 20).")},
        (),
        ToolPolicy("read", "Read recent calls"),
    ),
    DeviceToolSpec(
        "phone_call",
        "Place a phone call from the user's phone right now (the user talks; you don't).",
        {"number": _str("Phone number to call.")},
        ("number",),
        ToolPolicy("send", "Place a call", standing=CALL_STANDING, assess=_sends("phone_call")),
        timeout=30.0,
    ),
    DeviceToolSpec(
        "calendar_list",
        "Events on the phone's calendars from now for the next `days` days.",
        {"days": _int("Days ahead (default 7, max 90)."), "query": _str("Only events whose title or place matches.")},
        (),
        ToolPolicy("read", "Read the calendar", untrusted_output=True),
    ),
    DeviceToolSpec(
        "calendar_create",
        "Add an event to the user's primary calendar.",
        {
            "title": _str("Event title."),
            "start": _str(_TIME),
            "end": _str(_TIME + " Defaults to one hour after start."),
            "location": _str("Where (optional)."),
            "description": _str("Notes (optional)."),
        },
        ("title", "start"),
        ToolPolicy("write", "Add a calendar event", standing=CALENDAR_STANDING),
    ),
    DeviceToolSpec(
        "notifications_list",
        "The phone's current notifications (messages from any app, email previews, missed calls). Each has a `key`; "
        "those with `can_reply` can be answered with notification_reply.",
        {"app": _str("Only notifications from this app, e.g. WhatsApp (optional).")},
        (),
        ToolPolicy("read", "Read notifications", untrusted_output=True),
    ),
    DeviceToolSpec(
        "notification_reply",
        "Reply to a message notification through the app's own reply action (SMS, WhatsApp, Signal, Messenger, ...).",
        {"key": _str("Notification key from notifications_list."), "text": _str("Reply text.")},
        ("key", "text"),
        ToolPolicy("send", "Reply to a message"),
    ),
    DeviceToolSpec(
        "reminder_create",
        "Set a reminder that notifies the user on the phone at a time.",
        {"text": _str("What to remind them of."), "at": _str(_TIME)},
        ("text", "at"),
        ToolPolicy("read", "Set a reminder"),
    ),
    DeviceToolSpec("reminders_list", "Pending reminders on the phone.", {}, (), ToolPolicy("read", "List reminders")),
    DeviceToolSpec(
        "reminder_cancel",
        "Cancel a pending reminder.",
        {"id": _str("Reminder id from reminders_list.")},
        ("id",),
        ToolPolicy("read", "Cancel a reminder"),
    ),
    DeviceToolSpec(
        "email_draft",
        "Open an email draft in the user's mail app (or post a notification to open it). The user reviews and "
        "presses Send themselves; this never sends.",
        {"to": _str("Recipient email(s), comma separated."), "subject": _str("Subject."), "body": _str("Body text.")},
        ("to",),
        ToolPolicy("read", "Prepare an email draft"),
    ),
    DeviceToolSpec(
        "apps_list",
        "Installed apps the user can open.",
        {"query": _str("Filter by name (optional).")},
        (),
        ToolPolicy("read", "List apps"),
    ),
    DeviceToolSpec(
        "app_open",
        "Open an installed app by name or package.",
        {"app": _str("App name (e.g. Uber) or package id.")},
        ("app",),
        ToolPolicy("read", "Open an app"),
    ),
    DeviceToolSpec(
        "device_status",
        "The phone's time zone, local time, battery, network, and which Emrys permissions are on.",
        {},
        (),
        ToolPolicy("read", "Check the phone's status"),
    ),
    DeviceToolSpec(
        "screen_read",
        "Read the app on screen as numbered elements (label, kind, whether tappable or editable). Always read before "
        "screen_act, and again after each action.",
        {},
        (),
        ToolPolicy("read", "Read the screen", untrusted_output=True),
        timeout=20.0,
    ),
    DeviceToolSpec(
        "screen_act",
        "Act in the app on screen. tap/type/scroll_* need `node` (its number from the latest screen_read) and `label` "
        "(its label exactly as read; the phone refuses if it changed). back/home/recents/notifications need neither. "
        "Never type passwords; ask the user.",
        {
            "action": {
                "type": "string",
                "enum": ["tap", "type", "scroll_down", "scroll_up", "back", "home", "recents", "notifications"],
            },
            "node": _int("Element number from screen_read."),
            "label": _str("The element's label as read."),
            "text": _str("Text to type (for type)."),
        },
        ("action",),
        ToolPolicy("read", "Act in an app", standing=TAP_STANDING, assess=screen_act_tier),
        timeout=30.0,
    ),
)

POLICIES: dict[str, ToolPolicy] = {spec.name: spec.policy for spec in SPECS}

_EMAIL_ON_PHONE = (
    "you can read email previews in `notifications_list`; to write one, use `email_draft` (the user sends it)."
)
_EMAIL_IN_MAILBOX = "the user's mailbox is connected, so use the Email tools below rather than notification previews."


def instructions(*, mailbox: bool = False) -> str:
    """The phone section of Emrys's instructions; ``mailbox`` when the brain's own email tools are registered."""
    email = _EMAIL_IN_MAILBOX if mailbox else _EMAIL_ON_PHONE
    return f"""\
# Phone
 - Texts: find the number with `contacts_search`, then `sms_send`. To answer a message in any app, use \
`notifications_list` and `notification_reply` with its key.
 - Times you send are in the phone's zone; check `device_status` when the zone matters.
 - Email: {email}
 - Apps: `app_open`, then `screen_read` and `screen_act` one step at a time, reading the screen after each \
action. Stop and ask the user when you reach a login, a payment, or anything you are unsure of.
 - A tool error saying a permission is missing means the user must grant it in Emrys's Settings; tell them which."""


INSTRUCTIONS = instructions()


def tools_for(bridge: DeviceBridge) -> list[PythonAgentTool]:
    """The phone tools for one session, proxied over its device bridge."""
    return [device_tool(bridge, s.name, s.description, s.input_schema, timeout=s.timeout) for s in SPECS]
