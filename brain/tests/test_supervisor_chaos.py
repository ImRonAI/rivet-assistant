"""Chaos suite: every disruption ends with the same session, intact history, no duplicate side effects."""

from __future__ import annotations

import threading
from pathlib import Path
from typing import Any

import pytest
from strands import tool

from rivet_brain.approvals import ToolPolicy
from rivet_brain.supervisor import ACTIONS_KEY, MAY_HAVE_RUN
from tests.chaos import make_supervisor, roles, silent_stream, user_texts
from tests.sse import SSE, FakePerplexity, error_response


async def test_silent_model_stream_is_replaced_and_the_turn_continues_without_repeating_the_prompt(
    tmp_path: Path,
) -> None:
    events: list[dict[str, Any]] = []
    main = FakePerplexity([silent_stream, SSE("r2").text("Your balance is $12.").completed()])
    supervisor = make_supervisor(tmp_path, main, events=events)
    await supervisor.start()
    try:
        outcome = await supervisor.send("what's my balance?", turn_id="t1")
        assert outcome.status == "completed" and outcome.text == "Your balance is $12." and outcome.restarts == 1
        lane = supervisor._lanes["default"]
        agent = lane.gen.agent  # type: ignore[union-attr]
        assert agent.agent_id == "default" and lane.fence.generation >= 1
        assert roles(agent.messages) == ["user", "assistant"]
        assert user_texts(agent.messages)[0] == "what's my balance?"
        assert any("Rivet restarted after: no response from the model" in t for t in user_texts(agent.messages))
        prompts = [i for i in main.requests[1]["input"] if i.get("role") == "user"]
        assert len(prompts) == 1  # the prompt was not sent twice
        assert [e["type"] for e in events if e["type"] in ("restart", "turn.completed")] == [
            "restart",
            "turn.completed",
        ]
    finally:
        await supervisor.close()


async def test_hung_side_effect_after_approval_is_reported_as_uncertain_and_never_rerun(tmp_path: Path) -> None:
    release = threading.Event()
    calls: list[str] = []

    @tool
    def send_sms(to: str, body: str) -> str:
        """Send an SMS.

        Args:
            to: Recipient.
            body: Text.
        """
        calls.append(to)
        release.wait(10)
        return "sent"

    main = FakePerplexity(
        [
            SSE().function_call("send_sms", {"to": "+15551234", "body": "late"}).completed(),
            SSE("r2").text("I may have texted Ada already; check your messages before I resend.").completed(),
        ]
    )
    supervisor = make_supervisor(
        tmp_path, main, tools=[send_sms], policies={"send_sms": ToolPolicy("send", "Send an SMS", scope_arg="to")}
    )
    await supervisor.start()
    try:
        paused = await supervisor.send("text Ada I'm late", turn_id="t1")
        assert paused.status == "awaiting_approval"
        outcome = await supervisor.respond({paused.approvals[0]["id"]: {"approved": True}})
        assert outcome.status == "completed" and outcome.restarts == 1
        agent = supervisor._lanes["default"].gen.agent  # type: ignore[union-attr]
        result = next(c["toolResult"] for m in agent.messages for c in m["content"] if "toolResult" in c)
        assert result["status"] == "error" and result["content"][0]["text"] == MAY_HAVE_RUN
        assert agent.state.get(ACTIONS_KEY)["call_1"]["status"] == "started"
    finally:
        release.set()
        await supervisor.close()
    assert calls == ["+15551234"]  # ran once, never replayed


async def test_hung_read_only_tool_is_simply_run_again(tmp_path: Path) -> None:
    attempts: list[int] = []
    release = threading.Event()

    @tool
    def lookup_contact(name: str) -> str:
        """Look up a contact.

        Args:
            name: Name.
        """
        attempts.append(1)
        if len(attempts) == 1:
            release.wait(10)
        return "+15551234"

    main = FakePerplexity(
        [
            SSE().function_call("lookup_contact", {"name": "Ada"}).completed(),
            SSE("r2").text("Ada is +15551234.").completed(),
        ]
    )
    supervisor = make_supervisor(
        tmp_path, main, tools=[lookup_contact], policies={"lookup_contact": ToolPolicy("read", "Look up a contact")}
    )
    await supervisor.start()
    try:
        outcome = await supervisor.send("what's Ada's number?")
        assert outcome.status == "completed" and outcome.text == "Ada is +15551234." and outcome.restarts == 1
        assert len(attempts) == 2 and len(main.requests) == 2
    finally:
        release.set()
        await supervisor.close()


@pytest.mark.parametrize(
    "failure",
    [
        lambda: error_response(429, "rate_limit_exceeded", "slow down"),
        lambda: error_response(503, "service_unavailable", "busy"),
        lambda: SSE().failed("overloaded", "try again later"),
    ],
)
async def test_exhausted_retries_rebuild_and_resume(tmp_path: Path, failure: Any) -> None:
    # Two attempts per generation: gen 0 fails twice, gen 1 fails once then succeeds.
    main = FakePerplexity([failure(), failure(), failure(), SSE("ok").text("Done.").completed()])
    supervisor = make_supervisor(tmp_path, main)
    await supervisor.start()
    try:
        outcome = await supervisor.send("hi")
        assert outcome.status == "completed" and outcome.text == "Done." and outcome.restarts == 1
        agent = supervisor._lanes["default"].gen.agent  # type: ignore[union-attr]
        assert roles(agent.messages) == ["user", "assistant"] and len(main.requests) == 4
    finally:
        await supervisor.close()


async def test_breaker_gives_up_tells_the_user_and_the_next_message_recovers(tmp_path: Path) -> None:
    busy = [error_response(503, "service_unavailable", "busy") for _ in range(10)]
    main = FakePerplexity([*busy, SSE("ok").text("Back now.").completed()])
    events: list[dict[str, Any]] = []
    supervisor = make_supervisor(tmp_path, main, events=events)
    supervisor.settings = type(supervisor.settings)(**{**supervisor.settings.__dict__, "max_restarts": 2})
    await supervisor.start()
    try:
        failed = await supervisor.send("hi", turn_id="t1")
        assert failed.status == "failed" and "gave up after 2 restarts" in (failed.error or "")
        assert any(e["type"] == "turn.failed" for e in events)
        main.script = [SSE("ok").text("Back now.").completed()]
        outcome = await supervisor.send("are you there?", turn_id="t2")
        assert outcome.status == "completed" and outcome.text == "Back now."
        agent = supervisor._lanes["default"].gen.agent  # type: ignore[union-attr]
        assert roles(agent.messages) == ["user", "assistant", "user", "assistant"]
        assert "couldn't finish" in agent.messages[1]["content"][0]["text"]
    finally:
        await supervisor.close()


async def test_bad_requests_fail_fast_without_restarting(tmp_path: Path) -> None:
    main = FakePerplexity([error_response(400, "invalid_request", "bad tool schema")])
    supervisor = make_supervisor(tmp_path, main)
    await supervisor.start()
    try:
        outcome = await supervisor.send("hi")
        assert outcome.status == "failed" and outcome.restarts == 0 and "BadRequestError" in (outcome.error or "")
        assert len(main.requests) == 1
    finally:
        await supervisor.close()


async def test_max_tokens_continues_with_the_same_agent(tmp_path: Path) -> None:
    main = FakePerplexity(
        [SSE().text("Part one, ").incomplete("max_output_tokens"), SSE("r2").text("part two.").completed()]
    )
    supervisor = make_supervisor(tmp_path, main)
    await supervisor.start()
    try:
        outcome = await supervisor.send("write a long reply")
        assert outcome.status == "completed" and outcome.restarts == 0 and outcome.text == "part two."
        assert "output limit" in str(main.requests[1]["input"])
    finally:
        await supervisor.close()
