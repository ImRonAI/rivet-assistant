"""Recovery across process restarts, approvals, corrupt state, overflow, Telnyx drops, and GoalLoop tasks."""

from __future__ import annotations

import asyncio
import json
import os
import signal
import subprocess
import sys
import time
from collections.abc import Iterator
from dataclasses import replace
from pathlib import Path
from typing import Any

import httpx
import pytest
from strands import tool
from strands.storage import LocalFileStorage

from emrys_brain.approvals import ToolPolicy
from emrys_brain.device_tools import SMS_STANDING
from emrys_brain.factory import Generation
from emrys_brain.supervisor import BuildTimeoutError, SessionLockedError, latest_snapshot_key
from tests.chaos import FAST, Aux, fast_settings, make_build, make_supervisor, roles, user_texts
from tests.sse import SSE, FakePerplexity, error_response
from tests.telnyx_server import FakeTelnyxServer

BRAIN = Path(__file__).resolve().parents[1]
SEND_POLICY = {"send_sms": ToolPolicy("send", "Send an SMS", standing=SMS_STANDING)}
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
def _reset_sent() -> Iterator[None]:
    sent.clear()
    yield


SEND_CALL = SSE().function_call("send_sms", {"to": "+15551234", "body": "late"})


async def test_kill_9_mid_turn_then_a_new_process_finishes_the_turn(tmp_path: Path) -> None:
    marker = tmp_path / "marker"
    child = subprocess.Popen(
        [sys.executable, "-m", "tests.crash_child", str(tmp_path), str(marker)],
        cwd=BRAIN,
        env={**os.environ, "PYTHONPATH": str(BRAIN)},
    )
    try:
        deadline = time.monotonic() + 60
        while not marker.exists():
            assert child.poll() is None, "child exited early"
            assert time.monotonic() < deadline, "child never reached the second model call"
            await asyncio.sleep(0.05)
        os.kill(child.pid, signal.SIGKILL)
        child.wait(timeout=10)
    finally:
        if child.poll() is None:
            child.kill()

    lookups: list[str] = []

    @tool
    def lookup_contact(name: str) -> str:
        """Look up a contact.

        Args:
            name: Name.
        """
        lookups.append(name)
        return "+15551234"

    main = FakePerplexity([SSE("after").text("Ada is +15551234.").completed()])
    supervisor = make_supervisor(
        tmp_path, main, tools=[lookup_contact], policies={"lookup_contact": ToolPolicy("read", "Look up a contact")}
    )
    recovered = await supervisor.start()  # the dead child's OS lock is gone
    try:
        assert recovered is not None and recovered.status == "completed" and recovered.turn_id == "t1"
        assert recovered.text == "Ada is +15551234."
        agent = supervisor._lanes["default"].gen.agent  # type: ignore[union-attr]
        assert roles(agent.messages) == ["user", "assistant", "user", "assistant"]
        assert lookups == []  # the child's completed lookup was not repeated
        assert any("Emrys restarted while working on this" in t for t in user_texts(agent.messages))
    finally:
        await supervisor.close()


async def test_pending_approval_survives_a_process_restart(tmp_path: Path) -> None:
    first = make_supervisor(
        tmp_path,
        FakePerplexity([SEND_CALL.completed()]),
        tools=[send_sms],
        policies=SEND_POLICY,
        lock_dir=str(tmp_path / "locks-a"),
    )
    await first.start()
    paused = await first.send("text Ada I'm late", turn_id="t1")
    approval_id = paused.approvals[0]["id"]
    assert paused.approvals[0]["request"]["tier"] == "send"
    # The first process dies here without closing.

    main = FakePerplexity([SSE("r2").text("Texted Ada.").completed()])
    second = make_supervisor(tmp_path, main, tools=[send_sms], policies=SEND_POLICY, lock_dir=str(tmp_path / "locks-b"))
    restored = await second.start()
    try:
        assert restored is not None and restored.status == "awaiting_approval"
        assert [a["id"] for a in restored.approvals] == [approval_id]
        assert second.pending_approvals()[0]["id"] == approval_id
        because = "You asked: “text Ada I'm late”"
        assert paused.approvals[0]["because"] == because
        assert restored.approvals[0]["because"] == because and second.pending_approvals()[0]["because"] == because
        outcome = await second.respond({approval_id: {"approved": True}})
        assert outcome.status == "completed" and outcome.text == "Texted Ada." and sent == ["+15551234"]
    finally:
        await second.close()


async def test_unanswered_approval_times_out_to_a_denial(tmp_path: Path) -> None:
    events: list[dict[str, Any]] = []
    main = FakePerplexity([SEND_CALL.completed(), SSE("r2").text("OK, I didn't send it.").completed()])
    supervisor = make_supervisor(
        tmp_path,
        main,
        tools=[send_sms],
        policies=SEND_POLICY,
        events=events,
        settings=fast_settings(tmp_path, approval_timeout=0.3),
    )
    await supervisor.start()
    try:
        assert (await supervisor.send("text Ada")).status == "awaiting_approval"
        for _ in range(100):
            if any(e["type"] == "turn.completed" for e in events):
                break
            await asyncio.sleep(0.05)
        assert [e["type"] for e in events if e["type"].startswith(("approval", "turn.completed"))] == [
            "approval.request",
            "approval.expired",
            "turn.completed",
        ]
        assert sent == [] and supervisor.pending_approvals() == []
    finally:
        await supervisor.close()


async def test_a_new_message_denies_pending_approvals_then_runs(tmp_path: Path) -> None:
    main = FakePerplexity(
        [
            SEND_CALL.completed(),
            SSE("r2").text("OK, not sending.").completed(),
            SSE("r3").text("It's sunny.").completed(),
        ]
    )
    supervisor = make_supervisor(tmp_path, main, tools=[send_sms], policies=SEND_POLICY)
    await supervisor.start()
    try:
        assert (await supervisor.send("text Ada", turn_id="t1")).status == "awaiting_approval"
        outcome = await supervisor.send("never mind, what's the weather?", turn_id="t2")
        assert outcome.status == "completed" and outcome.text == "It's sunny." and sent == []
        agent = supervisor._lanes["default"].gen.agent  # type: ignore[union-attr]
        assert roles(agent.messages) == ["user", "assistant", "user", "assistant", "user", "assistant"]
    finally:
        await supervisor.close()


async def test_corrupt_latest_snapshot_falls_back_to_the_newest_checkpoint(tmp_path: Path) -> None:
    first = make_supervisor(tmp_path, FakePerplexity([SSE().text("Hi, I'm Emrys.").completed()]))
    await first.start()
    await first.send("hello")
    await first.close()
    backend = LocalFileStorage(str(tmp_path / "sessions"))
    key = latest_snapshot_key("phone-1", "default")
    assert await backend.read(key) is not None
    await backend.write(key, b"{not json")

    second = make_supervisor(tmp_path, FakePerplexity([SSE("r2").text("Still me.").completed()]))
    await second.start()
    try:
        agent = second._lanes["default"].gen.agent  # type: ignore[union-attr]
        assert roles(agent.messages) == ["user", "assistant"]
        assert any(k.startswith(f"{key}.corrupt-") for k in await backend.list("session/phone-1/"))
        assert (await second.send("still there?")).text == "Still me."
    finally:
        await second.close()


async def test_one_process_per_session(tmp_path: Path) -> None:
    first = make_supervisor(tmp_path, FakePerplexity([]))
    await first.start()
    try:
        with pytest.raises(SessionLockedError):
            await make_supervisor(tmp_path, FakePerplexity([])).start()
    finally:
        await first.close()


async def test_a_build_past_its_deadline_is_fenced_and_replaced(tmp_path: Path) -> None:
    built: list[Generation] = []
    inner = make_build(FakePerplexity([SSE().text("Ready.").completed()]), Aux())

    def slow_once(settings: Any, **kwargs: Any) -> Generation:
        if not built:
            time.sleep(1.0)
        gen = inner(settings, **kwargs)
        built.append(gen)
        return gen

    settings = fast_settings(tmp_path, watchdog=replace(FAST, build=0.3))
    supervisor = make_supervisor(tmp_path, FakePerplexity([]), settings=settings)
    supervisor._build_fn = slow_once
    await supervisor.start()
    try:
        assert (await supervisor.send("hi")).text == "Ready."
        await asyncio.sleep(1.0)
        late, current = built[0], supervisor._lanes["default"].gen
        assert current is not late and not late.storage.current and current.storage.current  # type: ignore[union-attr]
    finally:
        await supervisor.close()
    assert issubclass(BuildTimeoutError, RuntimeError)


async def test_overflow_past_the_context_manager_is_compacted_on_the_aux_model(tmp_path: Path) -> None:
    # The oversized content sits in the first message, which the SDK's context manager keeps pinned.
    def rule(request: httpx.Request) -> httpx.Response:
        text = json.dumps(json.loads(request.content)["input"])
        if "HUGE-ATTACHMENT" in text and "what was in it?" in text:
            return error_response(400, "context_length_exceeded", "maximum context length is 400000 tokens")
        return httpx.Response(
            200, headers={"content-type": "text/event-stream"}, content=SSE("ok").text("ok").completed().body()
        )

    aux = Aux(summary="The user shared a large attachment about the dinner plan.")
    events: list[dict[str, Any]] = []
    supervisor = make_supervisor(tmp_path, FakePerplexity([rule] * 100), aux=aux, events=events)
    await supervisor.start()
    try:
        await supervisor.send("here is my HUGE-ATTACHMENT about dinner")
        for n in range(6):
            await supervisor.send(f"hello {n}")
        outcome = await supervisor.send("what was in it?")
        assert outcome.status == "completed", outcome.error
        assert aux.summaries == 1
        gen = supervisor._lanes["default"].gen
        agent = gen.agent  # type: ignore[union-attr]
        first = agent.messages[0]["content"][0]["text"]
        assert "large attachment about the dinner plan" in first and "HUGE-ATTACHMENT" not in json.dumps(agent.messages)
        assert any(e.get("kind") == "compacted" for e in events)
        backend = LocalFileStorage(str(tmp_path / "sessions"))
        checkpoints = await gen.session_manager.list_snapshot_ids(agent)  # type: ignore[union-attr]
        keys = await backend.list("session/phone-1/scopes/agent/default/snapshots/")
        saved = [((await backend.read(key)) or b"").decode() for key in keys if "snapshot_latest" not in key]
        assert checkpoints and any("HUGE-ATTACHMENT" in body for body in saved)  # full history in a checkpoint
    finally:
        await supervisor.close()


async def test_goal_task_is_not_retried_over_an_approval_pause(tmp_path: Path) -> None:
    aux = Aux(verdicts=[False, True])
    main = FakePerplexity([SEND_CALL.completed(), SSE("r2").text("Reminder sent to Ada.").completed()])
    supervisor = make_supervisor(tmp_path, main, aux=aux, tools=[send_sms], policies=SEND_POLICY)
    await supervisor.start()
    try:
        paused = await supervisor.run_task("Remind Ada about dinner by text", task_id="dinner")
        assert paused.status == "awaiting_approval" and paused.lane == "task-dinner"
        assert len(main.requests) == 1  # GoalLoop's retry was suppressed while paused
        outcome = await supervisor.respond({paused.approvals[0]["id"]: True}, lane_id="task-dinner")
        assert outcome.status == "completed" and outcome.goal == "satisfied" and sent == ["+15551234"]
        assert aux.judged == 2 and len(main.requests) == 2
    finally:
        await supervisor.close()


async def test_dropped_telnyx_connection_rebuilds_with_a_fresh_client(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    server = FakeTelnyxServer()
    state = server.start()
    monkeypatch.setenv("HORIZON_API_KEY", state.token)
    listing = "return await call_tool('ListPhoneNumbers', {})"
    main = FakePerplexity(
        [
            SSE().function_call("telnyx_execute", {"code": listing}).completed(),
            SSE("r2").text("You have one number.").completed(),
            SSE("r3").function_call("telnyx_execute", {"code": listing}, call_id="call_2").completed(),
            SSE("r4").function_call("telnyx_execute", {"code": listing}, call_id="call_3").completed(),
            SSE("r5").text("Still one number.").completed(),
        ]
    )

    def on_event(event: dict[str, Any]) -> None:
        if event["type"] == "restart":
            server.start()

    settings = fast_settings(
        tmp_path, telnyx_mcp_url=state.url, watchdog=replace(FAST, telnyx_execute=3.0, tool_default=3.0, idle=4.0)
    )
    supervisor = make_supervisor(tmp_path, main, settings=settings, require_telnyx=True)
    supervisor.sink = on_event
    await supervisor.start()
    try:
        assert (await supervisor.send("how many numbers do I have?")).text == "You have one number."
        server.stop()
        outcome = await supervisor.send("check again")
        assert outcome.status == "completed" and outcome.text == "Still one number." and outcome.restarts == 1
        assert state.executed == [listing, listing]
    finally:
        await supervisor.close()
        server.stop()


async def test_a_wrong_horizon_key_reports_failure_then_recovers_once_fixed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    server = FakeTelnyxServer()
    state = server.start()
    events: list[dict[str, Any]] = []
    monkeypatch.setenv("HORIZON_API_KEY", "wrong")
    settings = fast_settings(tmp_path, telnyx_mcp_url=state.url, max_restarts=1)
    supervisor = make_supervisor(
        tmp_path,
        FakePerplexity([SSE().text("Connected.").completed()]),
        settings=settings,
        events=events,
        require_telnyx=True,
    )
    try:
        assert await supervisor.start() is None
        assert any(e["type"] == "turn.failed" and "could not start" in e["error"] for e in events)
        monkeypatch.setenv("HORIZON_API_KEY", state.token)
        assert (await supervisor.send("hi")).text == "Connected."
    finally:
        await supervisor.close()
        server.stop()
