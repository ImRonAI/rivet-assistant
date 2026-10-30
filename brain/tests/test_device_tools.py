from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import pytest
from starlette.testclient import TestClient

from emrys_brain import device_tools
from emrys_brain.approvals import EmrysApprovals
from emrys_brain.device import DeviceBridge
from emrys_brain.device_tools import POLICIES, SPECS, screen_act_tier, tools_for
from emrys_brain.gateway import create_app
from emrys_brain.rules import Target
from tests.chaos import Aux, fast_settings, make_build, make_supervisor
from tests.sse import SSE, FakePerplexity

TOKEN = "device-token-0123456789abcdef"
AUTH = {"Authorization": f"Bearer {TOKEN}"}

# The phone's tool registry, read from the app's source so both sides cannot drift apart.
PHONE_REGISTRY = Path(__file__).resolve().parents[2] / "android/app/src/main/java/com/hiron/emrys/device/DeviceTools.kt"
PHONE_TOOLS = set(re.findall(r'^\s*"([a-z_]+)" to Tool', PHONE_REGISTRY.read_text(), re.MULTILINE))
# Run on the phone only by the brain's own code (calls.py saves the answering number), never by the model.
INTERNAL_PHONE_TOOLS = {"call_answering_number"}


def test_every_phone_tool_is_declared_with_a_policy_and_a_valid_schema() -> None:
    assert len(PHONE_TOOLS) > 10
    assert {spec.name for spec in SPECS} == PHONE_TOOLS - INTERNAL_PHONE_TOOLS
    for spec in SPECS:
        schema = spec.input_schema
        assert schema["type"] == "object"
        assert set(spec.required) <= set(schema["properties"]), spec.name
        assert spec.description and spec.policy.summary
    names = [tool.tool_name for tool in tools_for(DeviceBridge())]
    assert sorted(names) == sorted(PHONE_TOOLS - INTERNAL_PHONE_TOOLS)


@pytest.mark.parametrize(
    ("tool", "tool_input", "tier", "untrusted"),
    [
        ("sms_send", {"to": "+18015550100", "body": "hi"}, "send", False),
        ("phone_call", {"number": "+18015550100"}, "send", False),
        ("notification_reply", {"key": "k", "text": "ok"}, "send", False),
        ("calendar_create", {"title": "Dentist", "start": "2026-10-27T09:00"}, "write", False),
        ("sms_list", {}, "read", True),
        ("notifications_list", {}, "read", True),
        ("screen_read", {}, "read", True),
        ("calendar_list", {}, "read", True),
        ("contacts_search", {"query": "Ada"}, "read", False),
        ("email_draft", {"to": "a@b.co"}, "read", False),
        ("reminder_create", {"text": "x", "at": "2026-10-27T09:00"}, "read", False),
    ],
)
def test_policy_tiers(tool: str, tool_input: dict[str, Any], tier: str, untrusted: bool) -> None:
    assessment = EmrysApprovals(POLICIES).assess(tool, tool_input)
    assert assessment.tier == tier and assessment.untrusted_output is untrusted


def test_send_headlines_name_the_recipient_and_rules_are_per_recipient() -> None:
    approvals = EmrysApprovals(POLICIES)
    sms = approvals.assess("sms_send", {"to": "+1 (801) 555-0100", "body": "hi"})
    assert sms.summary == "Text +1 (801) 555-0100: “hi”"
    assert sms.target == Target("sms_send", (("to", "+18015550100"),))
    assert approvals.assess("phone_call", {"number": "911"}).summary == "Call 911"
    assert approvals.assess("notification_reply", {"key": "k", "text": "ok"}).target is None


@pytest.mark.parametrize(
    ("tool_input", "tier"),
    [
        ({"action": "scroll_down", "node": 3, "label": "Inbox"}, "read"),
        ({"action": "back"}, "read"),
        ({"action": "type", "node": 2, "label": "Search", "text": "pizza"}, "read"),
        ({"action": "tap", "node": 1, "label": "Inbox"}, "read"),
        ({"action": "tap", "node": 1, "label": "Cancel"}, "read"),
        ({"action": "tap", "node": 1, "label": "Send"}, "send"),
        ({"action": "tap", "node": 1, "label": "Reply all"}, "send"),
        ({"action": "tap", "node": 1, "label": "OK"}, "write"),
        ({"action": "tap", "node": 1, "label": "Delete conversation"}, "destructive"),
        ({"action": "tap", "node": 1, "label": "Cancel subscription"}, "destructive"),
        ({"action": "tap", "node": 1, "label": "Place order · $23.10"}, "purchase"),
        ({"action": "tap", "node": 1, "label": "Pay"}, "purchase"),
        ({"action": "tap", "node": 1, "label": "Paypal account"}, "read"),
        ({"action": "tap", "node": 1}, "unknown"),
        ({"action": "explode"}, "unknown"),
    ],
)
def test_the_app_operator_asks_at_commit_points(tool_input: dict[str, Any], tier: str) -> None:
    assert screen_act_tier(tool_input)[0] == tier
    assert EmrysApprovals(POLICIES).assess("screen_act", tool_input).tier == tier


def test_phone_instructions_reach_the_system_prompt() -> None:
    from emrys_brain.factory import EMRYS_INSTRUCTIONS

    assert device_tools.INSTRUCTIONS in EMRYS_INSTRUCTIONS


def _until(socket: Any, kind: str, limit: int = 300) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    seen: list[dict[str, Any]] = []
    for _ in range(limit):
        event = socket.receive_json()
        seen.append(event)
        if event["type"] == kind:
            return event, seen
    raise AssertionError(f"no {kind} in {[e['type'] for e in seen]}")


def test_an_approved_text_runs_on_the_phone_exactly_once(tmp_path: Path) -> None:
    main = FakePerplexity(
        [
            SSE().function_call("sms_send", {"to": "+18015550100", "body": "Running late"}).completed(),
            SSE("r2").text("Texted Ada.").completed(),
        ]
    )
    app = create_app(
        fast_settings(tmp_path),
        build=make_build(main, Aux()),
        device_token=TOKEN,
        tools_for=tools_for,
        approvals=EmrysApprovals(POLICIES),
    )
    with TestClient(app) as client, client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket:
        socket.send_json({"type": "turn", "turn_id": "t1", "text": "tell Ada I'm late"})
        request, seen = _until(socket, "approval.request")
        assert not any(e["type"] == "device.call" for e in seen)
        sheet = request["approvals"][0]["request"]
        assert sheet["tool"] == "sms_send" and sheet["headline"] == "Text +18015550100: “Running late”"
        assert sheet["offer"]["label"] == "Texts to +18015550100" and sheet["offer"]["allow"] is True
        assert request["approvals"][0]["because"] == "You asked: “tell Ada I'm late”"
        socket.send_json({"type": "approval.response", "answers": {request["approvals"][0]["id"]: {"approved": True}}})
        call, _ = _until(socket, "device.call")
        assert call["tool"] == "sms_send" and call["input"]["body"] == "Running late"
        socket.send_json(
            {"type": "device.result", "action_id": call["action_id"], "result": {"ok": True, "data": {"sent": True}}}
        )
        done, seen = _until(socket, "turn.completed")
        assert done["text"] == "Texted Ada."
        assert not any(e["type"] == "device.call" for e in seen)


def test_a_resent_turn_is_accepted_but_not_run_twice(tmp_path: Path) -> None:
    main = FakePerplexity([SSE().text("Hi.").completed(), SSE("r2").text("Again?").completed()])
    app = create_app(fast_settings(tmp_path), build=make_build(main, Aux()), device_token=TOKEN)
    with TestClient(app) as client, client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket:
        socket.send_json({"type": "turn", "turn_id": "t1", "text": "hello"})
        _until(socket, "turn.completed")
        socket.send_json({"type": "turn", "turn_id": "t1", "text": "hello"})
        accepted, _ = _until(socket, "turn.accepted")
        socket.send_json({"type": "ping"})
        _, seen = _until(socket, "pong")
        assert accepted["turn_id"] == "t1" and not any(e["type"] == "turn.started" for e in seen)
    assert len(main.requests) == 1


async def test_the_supervisor_refuses_its_latest_turn_id_after_a_restart(tmp_path: Path) -> None:
    first = make_supervisor(tmp_path, FakePerplexity([SSE().text("Hi.").completed()]))
    await first.start()
    await first.send("hello", turn_id="t1")
    await first.close()
    main = FakePerplexity([SSE().text("should not run").completed()])
    second = make_supervisor(tmp_path, main)
    await second.start()
    try:
        outcome = await second.send("hello", turn_id="t1")
        assert outcome.stop_reason == "duplicate" and main.requests == []
    finally:
        await second.close()
