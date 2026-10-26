"""Replays streams recorded from the live Perplexity Agent API (``scripts/probe_perplexity.py``).

These guard the model subclass against the API's real event and item shapes, not just the ones the
hand-built ``SSE`` streams in ``tests/sse.py`` assume.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx
import openai
import pytest
from strands import Agent, tool
from strands.types.exceptions import MaxTokensReachedException

from rivet_brain.events import RivetEventBridge
from rivet_brain.models.perplexity import PerplexityAgentModel, build_perplexity_model
from tests.sse import FakePerplexity

RECORDED = Path(__file__).parent / "fixtures" / "perplexity"


def recorded(name: str, status: int = 200) -> httpx.Response:
    body = (RECORDED / f"{name}.sse").read_bytes()
    if status != 200:
        return httpx.Response(status, headers={"content-type": "application/json"}, content=body)
    return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=body)


def recorded_text(name: str) -> str:
    text = []
    for block in (RECORDED / f"{name}.sse").read_text().split("\n\n"):
        data = "".join(line[5:].strip() for line in block.splitlines() if line.startswith("data:"))
        if data and data != "[DONE]":
            event = json.loads(data)
            if event.get("type") == "response.output_text.delta":
                text.append(event["delta"])
    return "".join(text)


def model(fake: FakePerplexity) -> PerplexityAgentModel:
    return build_perplexity_model(
        api_key="k", model_id="openai/gpt-5.6-sol", transport_factory=fake.transport_factory()
    )


def _text(agent: Agent) -> str:
    parts: list[str] = []
    for block in agent.messages[-1]["content"]:
        if "text" in block:
            parts.append(block["text"])
        elif "citationsContent" in block:
            parts.extend(c["text"] for c in block["citationsContent"]["content"])
    return "".join(parts)


def test_plain_text_and_usage_cost() -> None:
    usage: list[dict[str, Any]] = []
    bridge = RivetEventBridge(lambda e: usage.append(e) if e["type"] == "usage" else None)
    agent = Agent(model=model(FakePerplexity([recorded("plain_text")])), callback_handler=bridge)
    result = agent("Say hello in five words.")
    assert result.stop_reason == "end_turn" and _text(agent) == recorded_text("plain_text")
    assert usage and usage[0]["cost"]


def test_function_call_arrives_once_with_real_arguments() -> None:
    calls: list[str] = []

    @tool
    def lookup_contact(name: str) -> str:
        """Look up a contact's phone number.

        Args:
            name: Contact name.
        """
        calls.append(name)
        return "+18015550100"

    fake = FakePerplexity([recorded("function_call"), recorded("plain_text")])
    Agent(model=model(fake), tools=[lookup_contact], callback_handler=None)("Find Ada's phone number.")
    assert calls == ["Ada"]
    replay = fake.requests[1]["input"]
    assert [i["type"] for i in replay if i.get("type") in ("function_call", "function_call_output")] == [
        "function_call",
        "function_call_output",
    ]


@pytest.mark.parametrize("name", ["web_search", "people_search", "fetch_url"])
def test_web_sources_become_citations_and_taint_the_turn(name: str) -> None:
    untrusted: list[bool] = []
    bridge = RivetEventBridge(None, on_untrusted=lambda: untrusted.append(True))
    agent = Agent(model=model(FakePerplexity([recorded(name)])), callback_handler=bridge)
    agent("question")
    assert _text(agent) == recorded_text(name) and untrusted
    if name != "fetch_url":
        block = next(b["citationsContent"] for b in agent.messages[-1]["content"] if "citationsContent" in b)
        assert block["citations"] and all(c["location"]["web"]["url"].startswith("http") for c in block["citations"])


@pytest.mark.parametrize("name", ["image_search", "finance_search", "consecutive_user_items"])
def test_other_native_tools_and_unknown_items_pass_through(name: str) -> None:
    events: list[dict[str, Any]] = []
    agent = Agent(model=model(FakePerplexity([recorded(name)])), callback_handler=RivetEventBridge(events.append))
    result = agent("question")
    assert result.stop_reason == "end_turn" and _text(agent) == recorded_text(name)
    if name != "consecutive_user_items":
        assert any(e["type"] == "report" for e in events)


def test_output_limit_cutoff_raises_max_tokens() -> None:
    with pytest.raises(MaxTokensReachedException):
        Agent(model=model(FakePerplexity([recorded("incomplete")])), callback_handler=None)("Write an essay.")


def test_real_validation_error_is_a_bad_request_and_not_retried() -> None:
    fake = FakePerplexity([recorded("bad_request", status=400)])
    with pytest.raises(openai.BadRequestError, match="description is required"):
        Agent(model=model(fake), callback_handler=None)("hi")
    assert len(fake.requests) == 1
