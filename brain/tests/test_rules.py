from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from strands.storage import InMemoryStorage, LocalFileStorage

from rivet_brain.approvals import RivetApprovals
from rivet_brain.device_tools import POLICIES as PHONE_POLICIES
from rivet_brain.email_tools import POLICIES as EMAIL_POLICIES
from rivet_brain.rules import (
    LIMIT_REACHED,
    NOT_ALLOWABLE,
    RULES_LIMIT,
    RuleOffer,
    RuleRefusedError,
    StandingRule,
    StandingRules,
    Target,
    admit,
    apply_answers,
    normalize,
)

APPROVALS = RivetApprovals({**PHONE_POLICIES, **EMAIL_POLICIES})


def test_shared_normalizer_vectors() -> None:
    assert normalize("phone", "+1 (801) 555-0100") == "+18015550100"
    assert normalize("phone", "+1.801.555.0100\t") == "+18015550100"
    assert normalize("phone", "") == ""
    assert normalize("text", "  Send  ") == "Send"
    assert normalize("emails", " B@x.com, a@X.com ,") == "a@x.com,b@x.com"


@pytest.mark.parametrize(
    ("normalizer", "value", "expected"),
    [
        ("flag", True, "true"),
        ("flag", False, "false"),
        ("flag", None, "false"),
        ("flag", "true", None),
        ("phone", None, ""),
        ("phone", 18015550100, None),
        ("text", {"a": 1}, None),
        ("emails", ["a@x.com"], None),
    ],
)
def test_normalizer_types(normalizer: Any, value: object, expected: str | None) -> None:
    assert normalize(normalizer, value) == expected


def test_target_keys_escape_every_reserved_character() -> None:
    target = Target.of("email_send", {"to": "a+b@x.com,c@x.com", "cc": "x&y=z/“q”"})
    assert target.key == "email_send?cc=x%26y%3Dz%2F%E2%80%9Cq%E2%80%9D&to=a%2Bb%40x.com%2Cc%40x.com"
    assert Target.of("calendar_create", {}).key == "calendar_create?"


def _target(tool: str, tool_input: dict[str, Any]) -> Target | None:
    return APPROVALS.assess(tool, tool_input).target


@pytest.mark.parametrize(
    ("tool", "tool_input", "scope"),
    [
        ("sms_send", {"to": "+1 801-555-0100", "body": "hi"}, {"to": "+18015550100"}),
        ("sms_send", {"to": "", "body": "hi"}, None),
        ("sms_send", {"to": 18015550100, "body": "hi"}, None),
        ("phone_call", {"number": "(801) 555 0100"}, {"number": "8015550100"}),
        ("calendar_create", {"title": "Dentist", "start": "2026-10-27T09:00"}, {}),
        ("email_send", {"to": "B@x.com,a@x.com", "subject": "s", "body": "b"}, {"to": "a@x.com,b@x.com", "cc": ""}),
        ("email_reply", {"uid": 1, "to": "a@x.com", "body": "b"}, {"to": "a@x.com", "reply_all": "false"}),
        (
            "email_reply",
            {"uid": 1, "to": "a@x.com", "body": "b", "reply_all": True},
            {"to": "a@x.com", "reply_all": "true"},
        ),
        ("email_mark", {"uid": 1, "action": "archive"}, {"action": "archive"}),
        ("email_mark", {"uid": 1, "action": "delete"}, None),
        ("screen_act", {"action": "tap", "node": 1, "label": " Send "}, {"label": "Send"}),
        ("screen_act", {"action": "type", "node": 1, "label": "Send", "text": "x"}, None),
        ("screen_act", {"action": "tap", "node": 1}, None),
        ("notification_reply", {"key": "k", "text": "ok"}, None),
        ("telnyx_execute", {"code": "await call_tool('SendMessage', {})"}, {"operation": "SendMessage"}),
        ("telnyx_execute", {"code": "await call_tool('SendMessage', {})\nawait call_tool('DialCall', {})"}, None),
        ("telnyx_execute", {"code": "n = 'SendMessage'\nawait call_tool(n, {})"}, None),
        ("call_answering_setup", {"number": "+18015550100", "owner_name": "Tim"}, None),
    ],
)
def test_eligibility_table(tool: str, tool_input: dict[str, Any], scope: dict[str, str] | None) -> None:
    target = _target(tool, tool_input)
    assert (target.scope_dict() if target is not None else None) == scope


@pytest.mark.parametrize(
    ("tool", "tool_input", "label", "allow"),
    [
        ("sms_send", {"to": "+18015550100", "body": "x"}, "Texts to +18015550100", True),
        ("phone_call", {"number": "+18015550100"}, "Calls to +18015550100", True),
        ("calendar_create", {"title": "t", "start": "s"}, "Adding events to your calendar", True),
        ("email_send", {"to": "a@x.com", "subject": "s", "body": "b"}, "Emails to a@x.com", True),
        ("email_reply", {"uid": 1, "to": "a@x.com", "body": "b"}, "Replies to a@x.com", True),
        ("email_mark", {"uid": 1, "action": "read"}, "Marking emails as read", True),
        ("email_mark", {"uid": 1, "action": "unread"}, "Marking emails as unread", True),
        ("screen_act", {"action": "tap", "node": 1, "label": "Send"}, "Tapping “Send” in apps", False),
        (
            "telnyx_execute",
            {"code": "await call_tool('UpdateMessagingProfile', {})"},
            "Update a messaging profile on your Telnyx account",
            False,
        ),
    ],
)
def test_offer_labels(tool: str, tool_input: dict[str, Any], label: str, allow: bool) -> None:
    offer = APPROVALS.offer(APPROVALS.assess(tool, tool_input), tainted=False)
    assert offer is not None and offer.label == label and offer.allow is allow and offer.deny is True


def _offer(allow: bool = True) -> RuleOffer:
    return RuleOffer(Target.of("sms_send", {"to": "+18015550100"}), "Texts to +18015550100", allow, True, None)


def test_admit_refuses_an_allow_the_offer_did_not_grant() -> None:
    with pytest.raises(RuleRefusedError, match=NOT_ALLOWABLE):
        admit(_offer(allow=False), "allow", now=1.0)
    rule = admit(_offer(allow=False), "deny", now=1.0)
    assert rule.decision == "deny" and rule.created_at == 1.0


def test_offers_round_trip_their_wire_form() -> None:
    offer = RuleOffer(Target.of("screen_act", {"label": "Pay"}), "Tapping “Pay” in apps", False, True, "purchase")
    assert RuleOffer.from_wire(offer.to_wire()) == offer
    assert RuleOffer.from_wire({**offer.to_wire(), "no_allow": "sometimes"}) is None
    assert RuleOffer.from_wire({**offer.to_wire(), "scope": {"label": 3}}) is None
    assert RuleOffer.from_wire("offer") is None


@pytest.mark.parametrize("kind", ["memory", "file"])
async def test_rules_round_trip_through_storage(kind: str, tmp_path: Path) -> None:
    backend = InMemoryStorage() if kind == "memory" else LocalFileStorage(str(tmp_path))
    rules = StandingRules(backend, "phone-1")
    await rules.load()
    assert rules.revision == 0 and rules.rules() == ()
    dana = admit(_offer(), "allow", now=1.0)
    await rules.put(dana)
    eve = StandingRule(Target.of("sms_send", {"to": "+15550000000"}), "deny", "Texts to +15550000000", 2.0)
    await rules.put(eve)
    await rules.put(StandingRule(dana.target, "deny", dana.label, 3.0))
    assert rules.revision == 3
    assert [rule.decision for rule in rules.rules()] == ["deny", "deny"]  # the replaced rule moved to the end
    assert await rules.revoke(eve.target.key) is True and rules.revision == 4
    assert await rules.revoke("missing") is False and rules.revision == 4

    again = StandingRules(backend, "phone-1")
    await again.load()
    assert again.revision == 4 and again.rules() == rules.rules()
    assert again.event() == {
        "type": "rules",
        "revision": 4,
        "rules": [
            {
                "key": "sms_send?to=%2B18015550100",
                "tool": "sms_send",
                "scope": {"to": "+18015550100"},
                "decision": "deny",
                "label": "Texts to +18015550100",
                "created_at": 3.0,
            }
        ],
    }
    other = StandingRules(backend, "phone-2")
    await other.load()
    assert other.rules() == ()


async def test_a_corrupt_document_is_set_aside_and_bad_entries_dropped() -> None:
    backend = InMemoryStorage()
    rules = StandingRules(backend, "phone-1")
    await backend.write(rules.key, b"{not json")
    await rules.load()
    assert rules.rules() == () and rules.revision == 0
    copies = [key for key in await backend.list("session/phone-1/rivet/") if ".corrupt-" in key]
    assert len(copies) == 1 and await backend.read(copies[0]) == b"{not json"

    good = admit(_offer(), "allow", now=1.0).to_wire()
    document = {"v": 1, "revision": 7, "rules": [good, {"tool": "x"}, {**good, "decision": "maybe"}]}
    await backend.write(rules.key, json.dumps(document).encode())
    await rules.load()
    assert rules.revision == 7 and len(rules.rules()) == 1
    await backend.write(rules.key, json.dumps({**document, "v": 2}).encode())
    await rules.load()
    assert rules.rules() == ()


async def test_at_most_200_rules() -> None:
    rules = StandingRules.in_memory()
    for i in range(RULES_LIMIT):
        await rules.put(StandingRule(Target.of("sms_send", {"to": f"+1{i:010d}"}), "deny", "x", 0.0))
    with pytest.raises(RuleRefusedError, match="at most 200"):
        await rules.put(StandingRule(Target.of("sms_send", {"to": "+19999999999"}), "deny", "x", 0.0))
    assert LIMIT_REACHED.startswith("Rivet keeps at most 200 rules.")
    # Replacing an existing rule is still allowed.
    await rules.put(StandingRule(Target.of("sms_send", {"to": f"+1{0:010d}"}), "allow", "x", 0.0))


async def test_apply_answers_writes_only_what_was_offered() -> None:
    rules = StandingRules.in_memory()
    allow_offer = _offer().to_wire()
    deny_only = RuleOffer(Target.of("screen_act", {"label": "Pay"}), "Tapping “Pay” in apps", False, True, "purchase")
    pending: list[dict[str, Any]] = [
        {"id": "a", "request": {"offer": allow_offer}},
        {"id": "b", "request": {"offer": deny_only.to_wire()}},
        {"id": "c", "request": {}},
    ]
    refused: list[str] = []
    answers: dict[str, object] = {
        "a": {"approved": True, "standing": "deny"},  # contradicts itself: nothing written
        "b": {"approved": True, "standing": "allow"},  # deny-only offer: refused
        "c": {"approved": False, "standing": "deny"},  # nothing offered
        "z": {"approved": False, "standing": "deny"},  # no such approval
    }
    assert await apply_answers(rules, pending, answers, now=5.0, on_refused=refused.append) is False
    assert rules.rules() == () and refused == [NOT_ALLOWABLE]

    answers = {"a": {"approved": True, "standing": "allow"}, "b": {"approved": False, "standing": "deny"}}
    assert await apply_answers(rules, pending, answers, now=5.0) is True
    assert {(r.target.tool, r.decision) for r in rules.rules()} == {("sms_send", "allow"), ("screen_act", "deny")}
