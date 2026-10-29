from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
from strands import tool

from rivet_brain.approvals import RivetApprovals, ToolPolicy
from rivet_brain.device import DeviceBridge, device_tool
from rivet_brain.device_tools import POLICIES as PHONE_POLICIES
from rivet_brain.device_tools import SMS_STANDING
from rivet_brain.gateway import create_app
from tests.chaos import Aux, fast_settings, make_build
from tests.sse import SSE, FakePerplexity

TOKEN = "device-token-0123456789abcdef"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
sent: list[str] = []


@tool
def send_sms(to: str, body: str) -> str:
    """Send an SMS.

    Args:
        to: Recipient.
        body: Text.
    """
    sent.append(to)
    return "sent"


@pytest.fixture(autouse=True)
def _reset() -> Iterator[None]:
    sent.clear()
    yield


def _client(tmp_path: Path, main: FakePerplexity, **kwargs: Any) -> TestClient:
    kwargs.setdefault(
        "approvals", RivetApprovals({"send_sms": ToolPolicy("send", "Send an SMS", standing=SMS_STANDING)})
    )
    kwargs.setdefault("tools_for", lambda bridge: [send_sms])
    app = create_app(fast_settings(tmp_path), build=make_build(main, Aux()), device_token=TOKEN, **kwargs)
    return TestClient(app)


def _until(socket: Any, kind: str, limit: int = 300) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    seen: list[dict[str, Any]] = []
    for _ in range(limit):
        event = socket.receive_json()
        seen.append(event)
        if event["type"] == kind:
            return event, seen
    raise AssertionError(f"no {kind} in {[e['type'] for e in seen]}")


def test_health_and_a_mandatory_device_token(tmp_path: Path) -> None:
    with _client(tmp_path, FakePerplexity([])) as client:
        assert client.get("/healthz").json() == {"status": "ok"}
        with (
            pytest.raises(WebSocketDisconnect),
            client.websocket_connect("/v1/sessions/phone-1", headers={"Authorization": "Bearer wrong"}) as socket,
        ):
            socket.receive_json()
    with pytest.raises(RuntimeError, match="RIVET_DEVICE_TOKEN"):
        create_app(fast_settings(tmp_path), device_token="short")


def test_a_turn_streams_ui_events_and_completes(tmp_path: Path) -> None:
    main = FakePerplexity([SSE().text("Hi, ", "I'm Rivet.").completed()])
    with _client(tmp_path, main) as client, client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket:
        socket.send_json({"type": "turn", "turn_id": "t1", "text": "hello"})
        done, seen = _until(socket, "turn.completed")
        kinds = [e["type"] for e in seen]
        assert kinds[:2] == ["turn.accepted", "turn.started"] and "text" in kinds
        assert done["text"] == "Hi, I'm Rivet." and done["turn_id"] == "t1" and done["session_id"] == "phone-1"
        assert "".join(e["delta"] for e in seen if e["type"] == "text") == "Hi, I'm Rivet."


def test_approval_round_trip_over_the_socket(tmp_path: Path) -> None:
    main = FakePerplexity(
        [
            SSE().function_call("send_sms", {"to": "+15551234", "body": "late"}).completed(),
            SSE("r2").text("Texted Ada.").completed(),
        ]
    )
    with _client(tmp_path, main) as client, client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket:
        socket.send_json({"type": "turn", "text": "text Ada I'm late"})
        request, _ = _until(socket, "approval.request")
        sheet = request["approvals"][0]
        assert sheet["request"]["tier"] == "send" and sheet["request"]["v"] == 2 and sent == []
        assert "remember" not in sheet["request"]
        socket.send_json({"type": "approval.response", "answers": {sheet["id"]: {"approved": True}}})
        done, _ = _until(socket, "turn.completed")
        assert done["text"] == "Texted Ada." and sent == ["+15551234"]


def test_events_are_buffered_while_the_phone_is_away_and_resume_resends_approvals(tmp_path: Path) -> None:
    main = FakePerplexity([SSE().function_call("send_sms", {"to": "+15551234", "body": "late"}).completed()])
    with _client(tmp_path, main) as client:
        with client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket:
            socket.send_json({"type": "turn", "turn_id": "t1", "text": "text Ada"})
            _until(socket, "turn.accepted")
        # The phone dropped before the approval request went out.
        with client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket:
            buffered, _ = _until(socket, "approval.request")
            socket.send_json({"type": "resume"})
            resent, _ = _until(socket, "approval.request")
            assert resent["resent"] is True and resent["approvals"][0]["id"] == buffered["approvals"][0]["id"]
            _until(socket, "session.ready")


def test_device_tools_round_trip_through_the_phone(tmp_path: Path) -> None:
    def tools_for(bridge: DeviceBridge) -> list[Any]:
        schema = {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}
        return [device_tool(bridge, "read_contact", "Look up a contact on the phone.", schema, timeout=5)]

    main = FakePerplexity(
        [
            SSE().function_call("read_contact", {"name": "Ada"}).completed(),
            SSE("r2").text("Ada is +15551234.").completed(),
        ]
    )
    approvals = RivetApprovals({"read_contact": ToolPolicy("read", "Read a contact", untrusted_output=True)})
    with (
        _client(tmp_path, main, tools_for=tools_for, approvals=approvals) as client,
        client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket,
    ):
        socket.send_json({"type": "turn", "text": "what's Ada's number?"})
        call, _ = _until(socket, "device.call")
        assert call["tool"] == "read_contact" and call["input"] == {"name": "Ada"} and call["action_id"] == "call_1"
        socket.send_json(
            {
                "type": "device.result",
                "action_id": "call_1",
                "result": {"ok": True, "data": {"name": "Ada", "phone": "+15551234"}},
            }
        )
        done, _ = _until(socket, "turn.completed")
        assert done["text"] == "Ada is +15551234."
        output = next(i for i in main.requests[1]["input"] if i.get("type") == "function_call_output")
        assert output["call_id"] == "call_1" and "+15551234" in json.dumps(
            output
        )  # the phone's answer reached the model


def _policies() -> RivetApprovals:
    return RivetApprovals({**PHONE_POLICIES, "send_sms": ToolPolicy("send", "Send an SMS", standing=SMS_STANDING)})


def test_resume_sends_the_rules_before_session_ready(tmp_path: Path) -> None:
    with (
        _client(tmp_path, FakePerplexity([])) as client,
        client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket,
    ):
        socket.send_json({"type": "resume"})
        ready, seen = _until(socket, "session.ready")
        rules = [e for e in seen if e["type"] == "rules"]
        assert rules == [{"type": "rules", "revision": 0, "rules": []}]
        assert seen.index(rules[0]) < seen.index(ready)


def test_always_allow_is_stored_before_the_turn_resumes_and_covers_the_next_text(tmp_path: Path) -> None:
    text = {"to": "+1 801 555 0100", "body": "late"}
    main = FakePerplexity(
        [
            SSE().function_call("send_sms", text).completed(),
            SSE("r2").text("Texted Ada.").completed(),
            SSE("r3").function_call("send_sms", {**text, "to": "+18015550100"}, call_id="call_2").completed(),
            SSE("r4").text("Texted Ada again.").completed(),
        ]
    )
    with (
        _client(tmp_path, main, approvals=_policies()) as client,
        client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket,
    ):
        socket.send_json({"type": "turn", "turn_id": "t1", "text": "text Ada I'm late"})
        request, _ = _until(socket, "approval.request")
        sheet = request["approvals"][0]
        assert sheet["because"] == "You asked: “text Ada I'm late”"
        assert sheet["request"]["offer"]["label"] == "Texts to +18015550100"
        answer = {"approved": True, "standing": "allow"}
        socket.send_json({"type": "approval.response", "answers": {sheet["id"]: answer}})
        rules, seen = _until(socket, "rules")
        assert not any(e["type"] == "turn.completed" for e in seen)
        assert rules["revision"] == 1 and rules["rules"][0]["decision"] == "allow"
        assert rules["rules"][0]["key"] == "send_sms?to=%2B18015550100"
        _until(socket, "turn.completed")

        socket.send_json({"type": "turn", "turn_id": "t2", "text": "tell her again"})
        done, seen = _until(socket, "turn.completed")
        assert done["text"] == "Texted Ada again." and sent == ["+1 801 555 0100", "+18015550100"]
        assert not any(e["type"] == "approval.request" for e in seen)
        applied = next(e for e in seen if e["type"] == "rules.applied")
        assert applied["decision"] == "allow" and applied["label"] == "Texts to +18015550100"

    # The rule is durable: a new process sees it.
    with (
        _client(tmp_path, FakePerplexity([])) as client,
        client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket,
    ):
        socket.send_json({"type": "rules.list"})
        rules, _ = _until(socket, "rules")
        assert rules["revision"] == 1 and len(rules["rules"]) == 1


def test_always_deny_declines_and_the_model_is_told_why(tmp_path: Path) -> None:
    main = FakePerplexity(
        [
            SSE().function_call("send_sms", {"to": "+15550000000", "body": "x"}).completed(),
            SSE("r2").text("OK.").completed(),
        ]
    )
    with (
        _client(tmp_path, main, approvals=_policies()) as client,
        client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket,
    ):
        socket.send_json({"type": "turn", "turn_id": "t1", "text": "text Eve"})
        request, _ = _until(socket, "approval.request")
        answer = {"approved": False, "standing": "deny"}
        socket.send_json({"type": "approval.response", "answers": {request["approvals"][0]["id"]: answer}})
        rules, _ = _until(socket, "rules")
        assert rules["rules"][0]["decision"] == "deny"
        _until(socket, "turn.completed")
    output = next(i for i in main.requests[1]["input"] if i.get("type") == "function_call_output")
    assert "The user set Rivet to always decline this (Texts to +15550000000)" in json.dumps(output, ensure_ascii=False)
    assert sent == []


def test_rules_set_and_revoke_from_the_phone(tmp_path: Path) -> None:
    with (
        _client(tmp_path, FakePerplexity([]), approvals=_policies()) as client,
        client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket,
    ):
        socket.send_json({"type": "rules.set", "decision": "allow", "tool": "screen_act", "scope": {"label": "Send"}})
        refused, _ = _until(socket, "rules.refused")
        assert refused == {
            "type": "rules.refused",
            "request": "set",
            "error": "This kind of action can't be always allowed.",
        }
        socket.send_json({"type": "rules.set", "decision": "deny", "tool": "mystery", "scope": {}})
        refused, _ = _until(socket, "rules.refused")
        assert refused["error"] == "Rivet can't make a rule for this."
        socket.send_json({"type": "rules.set", "decision": "deny", "tool": "sms_send", "scope": {"to": 5}})
        _until(socket, "rules.refused")
        socket.send_json({"type": "rules.set", "decision": "never", "tool": "sms_send", "scope": {"to": "+1"}})
        _until(socket, "rules.refused")

        socket.send_json({"type": "rules.set", "decision": "deny", "tool": "screen_act", "scope": {"label": "Send"}})
        rules, _ = _until(socket, "rules")
        assert rules["revision"] == 1 and rules["rules"][0]["label"] == "Tapping “Send” in apps"
        socket.send_json(
            {"type": "rules.set", "decision": "allow", "tool": "sms_send", "scope": {"to": "+1 801 555 0100"}}
        )
        rules, _ = _until(socket, "rules")
        assert rules["revision"] == 2 and rules["rules"][1]["scope"] == {"to": "+18015550100"}

        socket.send_json({"type": "rules.revoke", "key": rules["rules"][0]["key"]})
        rules, _ = _until(socket, "rules")
        assert rules["revision"] == 3 and [r["tool"] for r in rules["rules"]] == ["sms_send"]
        socket.send_json({"type": "rules.revoke", "key": "nothing"})
        rules, _ = _until(socket, "rules")
        assert rules["revision"] == 3

        with client.websocket_connect("/v1/sessions/phone-2", headers=AUTH) as other:
            other.send_json({"type": "rules.list"})
            rules, _ = _until(other, "rules")
            assert rules["rules"] == []


def test_a_task_reason_becomes_the_because(tmp_path: Path) -> None:
    main = FakePerplexity([SSE().function_call("send_sms", {"to": "+15551234", "body": "On my way"}).completed()])
    with _client(tmp_path, main) as client, client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket:
        reason = "A new WhatsApp message arrived."
        socket.send_json({"type": "task", "goal": "Reply if needed", "task_id": "n1", "reason": reason})
        request, _ = _until(socket, "approval.request")
        assert request["approvals"][0]["because"] == reason
