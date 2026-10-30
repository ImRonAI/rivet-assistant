"""Live checks against Perplexity and the Telnyx MCP server on Horizon; skipped without keys.

Telnyx is exercised read-only: approvals stay on, and any write the model attempts is denied.
Run with ``pytest -m live``.
"""

from __future__ import annotations

import os
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest
from strands import Agent, tool
from strands.storage import LocalFileStorage

from emrys_brain.approvals import TURN_KEY, EmrysApprovals
from emrys_brain.config import BrainSettings
from emrys_brain.factory import build_agent, build_models
from emrys_brain.storage import FenceHolder

pytestmark = pytest.mark.live

needs_perplexity = pytest.mark.skipif(not os.environ.get("PERPLEXITY_API_KEY"), reason="PERPLEXITY_API_KEY not set")
needs_horizon = pytest.mark.skipif(not os.environ.get("HORIZON_API_KEY"), reason="HORIZON_API_KEY not set")


def _settings(tmp_path: Path) -> BrainSettings:
    return replace(BrainSettings.from_env(), session_dir=str(tmp_path / "sessions"))


@needs_perplexity
def test_live_answer_cites_web_sources(tmp_path: Path) -> None:
    main, _ = build_models(_settings(tmp_path))
    agent = Agent(model=main, callback_handler=None)
    result = agent("What is the current weather in Salt Lake City? One sentence.")
    content = agent.messages[-1]["content"]
    assert str(result).strip()
    assert any("citationsContent" in block for block in content), "expected web citations"


@needs_perplexity
def test_live_function_call_round_trip(tmp_path: Path) -> None:
    seen: list[dict[str, Any]] = []

    @tool
    def lookup_contact(name: str) -> str:
        """Look up a contact's phone number.

        Args:
            name: Contact name.
        """
        seen.append({"name": name})
        return "+18015550100"

    main, _ = build_models(_settings(tmp_path))
    agent = Agent(model=main, tools=[lookup_contact], callback_handler=None)
    result = agent("Use lookup_contact to find Ada's number, then tell me the number.")
    assert seen and seen[0]["name"].lower().startswith("ada")
    assert "8015550100" in "".join(ch for ch in str(result) if ch.isdigit())


@needs_perplexity
@needs_horizon
def test_live_read_only_telnyx_turn(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    generation = build_agent(
        settings,
        session_id="live-check",
        fence=FenceHolder(),
        backend=LocalFileStorage(settings.session_dir),
        approvals=EmrysApprovals(),
    )
    agent = generation.agent
    agent.state.set(TURN_KEY, "live-1")
    try:
        result = agent(
            "Using only read-only Telnyx calls, how many phone numbers are on my Telnyx account? "
            "Do not change, buy, send or delete anything."
        )
        while result.stop_reason == "interrupt":
            # A write slipped in: deny it, and make sure nothing ran.
            result = agent(
                [
                    {"interruptResponse": {"interruptId": i.id, "response": {"approved": False}}}
                    for i in result.interrupts or []
                ]
            )
        assert result.stop_reason == "end_turn" and str(result).strip()
        executed = [c["toolUse"]["name"] for m in agent.messages for c in m["content"] if "toolUse" in c]
        assert any(name.startswith("telnyx_") for name in executed)
    finally:
        agent.cleanup()
