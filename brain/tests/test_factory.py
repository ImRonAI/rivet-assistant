from __future__ import annotations

from collections.abc import Iterator
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest
from strands import tool
from strands.storage import LocalFileStorage
from strands_harness import HARNESS_CONTRACT

from emrys_brain.approvals import TURN_KEY, EmrysApprovals, ToolPolicy
from emrys_brain.config import BrainSettings, available_providers, default_provider
from emrys_brain.factory import EMRYS_INSTRUCTIONS, Generation, build_agent, build_models
from emrys_brain.models.groq import GroqModel
from emrys_brain.models.perplexity import PerplexityAgentModel, build_perplexity_model
from emrys_brain.storage import FenceHolder
from tests.sse import SSE, FakeGroq, FakePerplexity
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
        approvals=EmrysApprovals(),
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
        assert prompt.startswith(HARNESS_CONTRACT[:40]) and EMRYS_INSTRUCTIONS in prompt and INSTRUCTIONS in prompt
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
        assert result.stop_reason == "interrupt" and result.interrupts[0].name == "emrys-approval"
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
    fake = FakePerplexity([SSE().text("Hi, I'm Emrys.").completed(), SSE("r2").text("Still me.").completed()])
    first = _build(tmp_path, telnyx.url, fake, fence)
    first.agent.system_prompt = "stale"
    first.agent("hello")  # saved on every message, stale prompt included
    first.agent.cleanup()

    fence.advance()
    second = _build(tmp_path, telnyx.url, fake, fence)
    try:
        assert second.number == 1 and [m["role"] for m in second.agent.messages] == ["user", "assistant"]
        assert second.agent.system_prompt != "stale" and EMRYS_INSTRUCTIONS in (second.agent.system_prompt or "")
        second.agent("still there?")
        assert len(second.agent.messages) == 4
    finally:
        second.agent.cleanup()


def test_build_models_follows_the_provider(tmp_path: Path) -> None:
    perplexity = BrainSettings(perplexity_api_key="p", groq_api_key="g")
    main, aux = build_models(perplexity)
    assert isinstance(main, PerplexityAgentModel) and isinstance(aux, PerplexityAgentModel)
    groq = replace(perplexity, model_provider="groq")
    main, aux = build_models(groq)
    assert isinstance(main, GroqModel) and isinstance(aux, GroqModel) and main is not aux
    assert main.config["model_id"] == "qwen/qwen3.8-27b" and main.context_window_limit == 131_072
    assert main.client_args["base_url"] == "https://api.groq.com/openai/v1" and not main.stateful
    with pytest.raises(RuntimeError, match="GROQ_API_KEY"):
        build_models(replace(groq, groq_api_key=None))


def test_a_scripted_groq_turn_calls_a_tool_and_answers(tmp_path: Path) -> None:
    calls: list[str] = []

    @tool
    def device_status() -> str:
        """Read the phone's battery and network."""
        calls.append("status")
        return "battery 80%"

    fake = FakeGroq(
        [
            SSE().groq_function_call("device_status", {}).completed(cost=None),
            SSE("r2").text("Your battery is at 80%.").completed(cost=None),
        ]
    )
    aux = FakeGroq([], fallback=lambda: SSE("aux").text("[]").completed(cost=None))
    models = (
        GroqModel(api_key="k", model_id="qwen/qwen3.8-27b", transport_factory=fake.transport_factory()),
        GroqModel(api_key="k", model_id="qwen/qwen3.8-27b", transport_factory=aux.transport_factory()),
    )
    generation = build_agent(
        replace(_settings(tmp_path, "http://unused"), model_provider="groq"),
        session_id="s1",
        fence=FenceHolder(),
        backend=LocalFileStorage(str(tmp_path / "sessions")),
        approvals=EmrysApprovals({"device_status": ToolPolicy("read", "Read the phone's status")}),
        tools=[device_status],
        models=models,
        require_telnyx=False,
    )
    try:
        assert "Your battery is at 80%." in str(generation.agent("how's my battery?"))
    finally:
        generation.agent.cleanup()
    assert calls == ["status"]
    first, second = fake.requests
    assert first["model"] == "qwen/qwen3.8-27b" and first["store"] is False and "previous_response_id" not in second
    assert "You can search the web" not in first["instructions"]
    output = next(i for i in second["input"] if i.get("type") == "function_call_output")
    assert output["call_id"] == "call_1" and "battery 80%" in str(output)


def test_provider_settings_come_from_the_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in ("EMRYS_MODEL_PROVIDER", "GROQ_API_KEY", "GROQ_MODEL", "GROQ_CONTEXT_WINDOW", "PERPLEXITY_API_KEY"):
        monkeypatch.delenv(name, raising=False)
    settings = BrainSettings.from_env()
    assert settings.model_provider == "perplexity" and settings.groq_model_id == "qwen/qwen3.8-27b"
    assert available_providers(settings) == () and default_provider(settings) == "perplexity"
    monkeypatch.setenv("EMRYS_MODEL_PROVIDER", "groq")
    monkeypatch.setenv("PERPLEXITY_API_KEY", "p")
    settings = BrainSettings.from_env()
    # Groq was asked for but has no key, so the effective default is the provider that has one.
    assert available_providers(settings) == ("perplexity",) and default_provider(settings) == "perplexity"
    monkeypatch.setenv("GROQ_API_KEY", "g")
    monkeypatch.setenv("GROQ_CONTEXT_WINDOW", "65536")
    settings = BrainSettings.from_env()
    assert default_provider(settings) == "groq" and settings.groq_context_window == 65536
    monkeypatch.setenv("EMRYS_MODEL_PROVIDER", "llama")
    with pytest.raises(ValueError, match="perplexity or groq"):
        BrainSettings.from_env()
