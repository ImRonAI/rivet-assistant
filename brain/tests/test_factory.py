from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
from strands.storage import LocalFileStorage
from strands_harness import HARNESS_CONTRACT

from rivet_brain.approvals import TURN_KEY, RivetApprovals
from rivet_brain.config import BrainSettings
from rivet_brain.factory import RIVET_INSTRUCTIONS, Generation, build_agent
from rivet_brain.models.perplexity import build_perplexity_model
from rivet_brain.storage import FenceHolder
from tests.sse import SSE, FakePerplexity
from tests.telnyx_server import INSTRUCTIONS, FakeTelnyx, serve_fake_telnyx

LIST = "return await call_tool('ListPhoneNumbers', {})"
SEND = "await call_tool('SendMessage', {'to': '+15551234', 'text': 'hi'})"


@pytest.fixture
def telnyx(monkeypatch: pytest.MonkeyPatch) -> Iterator[FakeTelnyx]:
    with serve_fake_telnyx() as server:
        monkeypatch.setenv("HORIZON_API_KEY", server.token)
        yield server


def _settings(tmp_path: Path, url: str) -> BrainSettings:
    return BrainSettings(
        perplexity_api_key="test",
        telnyx_mcp_url=url,
        session_dir=str(tmp_path / "sessions"),
        skills_dir=str(tmp_path / "skills"),
        retry_attempts=2,
        retry_initial_delay=0,
    )


def _build(tmp_path: Path, url: str, fake: FakePerplexity, fence: FenceHolder, **kwargs: Any) -> Generation:
    # Memory extraction runs on the aux model; it gets its own endpoint so it never consumes the main script.
    aux_fake = FakePerplexity([], fallback=lambda: SSE("aux").text("[]").completed())
    main = build_perplexity_model(api_key="k", model_id="main", transport_factory=fake.transport_factory())
    aux = build_perplexity_model(
        api_key="k", model_id="aux", native_tools=(), max_steps=1, transport_factory=aux_fake.transport_factory()
    )
    settings = _settings(tmp_path, url)
    generation = build_agent(
        settings,
        session_id="phone-1",
        fence=fence,
        backend=LocalFileStorage(settings.session_dir),
        approvals=RivetApprovals(),
        models=(main, aux),
        **kwargs,
    )
    generation.agent.state.set(TURN_KEY, "turn-1")
    return generation


def test_build_connects_telnyx_and_configures_the_harness(tmp_path: Path, telnyx: FakeTelnyx) -> None:
    generation = _build(tmp_path, telnyx.url, FakePerplexity([]), FenceHolder())
    agent = generation.agent
    try:
        names = set(agent.tool_names)
        assert {
            "telnyx_search",
            "telnyx_get_schema",
            "telnyx_execute",
            "telnyx_tags",
            "telnyx_generate_prefab_ui",
            "telnyx_search_prefab_components",
            "todo_write",
            "search_memory",
        } <= names
        assert not names & {
            "shell",
            "read",
            "write",
            "edit",
            "web_fetch",
            "web_search",
            "subagent",
            "programmatic_tool_caller",
            "add_memory",
        }
        assert generation.tool_timeouts["telnyx_execute"] == 150 and generation.tool_timeouts["telnyx_search"] == 120
        prompt = agent.system_prompt or ""
        assert prompt.startswith(HARNESS_CONTRACT[:40]) and RIVET_INSTRUCTIONS in prompt and INSTRUCTIONS in prompt
        assert agent.agent_id == "default" and generation.number == 0
    finally:
        agent.cleanup()


def test_read_only_telnyx_code_runs_over_mcp_and_writes_wait_for_approval(tmp_path: Path, telnyx: FakeTelnyx) -> None:
    fake = FakePerplexity(
        [
            SSE().function_call("telnyx_execute", {"code": LIST}).completed(),
            SSE("r2").function_call("telnyx_execute", {"code": SEND}, call_id="call_2").completed(),
        ]
    )
    agent = _build(tmp_path, telnyx.url, fake, FenceHolder()).agent
    try:
        result = agent("list my numbers, then text +15551234 hi")
        assert telnyx.executed == [LIST]
        assert result.stop_reason == "interrupt" and result.interrupts[0].name == "rivet-approval"
        output = next(c["toolResult"] for m in agent.messages for c in m["content"] if "toolResult" in c)
        assert "+18015550100" in str(output["content"])
    finally:
        agent.cleanup()


def test_a_wrong_horizon_key_fails_the_build(
    tmp_path: Path, telnyx: FakeTelnyx, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("HORIZON_API_KEY", "wrong")
    with pytest.raises(Exception, match="(?i)mcp|401|unauthorized"):
        _build(tmp_path, telnyx.url, FakePerplexity([]), FenceHolder())
    assert telnyx.unauthorized >= 1


def test_a_missing_horizon_key_fails_the_build(
    tmp_path: Path, telnyx: FakeTelnyx, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("HORIZON_API_KEY")
    with pytest.raises(Exception):  # noqa: B017 - the SDK wraps the auth error in its own exception types
        _build(tmp_path, telnyx.url, FakePerplexity([]), FenceHolder())
    assert telnyx.unauthorized == 0


def test_next_generation_restores_the_session_with_a_fresh_prompt(tmp_path: Path, telnyx: FakeTelnyx) -> None:
    fence = FenceHolder()
    fake = FakePerplexity([SSE().text("Hi, I'm Rivet.").completed(), SSE("r2").text("Still me.").completed()])
    first = _build(tmp_path, telnyx.url, fake, fence)
    first.agent.system_prompt = "stale"
    first.agent("hello")  # saved on every message, stale prompt included
    first.agent.cleanup()

    fence.advance()
    second = _build(tmp_path, telnyx.url, fake, fence)
    try:
        assert second.number == 1 and [m["role"] for m in second.agent.messages] == ["user", "assistant"]
        assert second.agent.system_prompt != "stale" and RIVET_INSTRUCTIONS in (second.agent.system_prompt or "")
        second.agent("still there?")
        assert len(second.agent.messages) == 4
    finally:
        second.agent.cleanup()
