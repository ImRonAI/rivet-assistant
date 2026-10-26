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
    kwargs.setdefault("approvals", RivetApprovals({"send_sms": ToolPolicy("send", "Send an SMS", scope_arg="to")}))
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
        assert sheet["request"]["tier"] == "send" and sheet["request"]["remember"] is True and sent == []
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
