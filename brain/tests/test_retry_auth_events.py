from __future__ import annotations

from typing import Any

import httpx
import openai
import pytest
from strands import Agent, tool

from rivet_brain.auth import HorizonBearer, MissingCredentialError
from rivet_brain.events import RivetEventBridge
from rivet_brain.models.perplexity import PerplexityTransientError, build_perplexity_model
from rivet_brain.retry import TransientRetry
from tests.sse import SSE, FakePerplexity, error_response


def _agent(fake: FakePerplexity, **kwargs: Any) -> Agent:
    model = build_perplexity_model(api_key="k", model_id="m", transport_factory=fake.transport_factory())
    kwargs.setdefault("callback_handler", None)
    return Agent(model=model, **kwargs)


@pytest.mark.parametrize(
    "failure",
    [
        error_response(503, "service_unavailable", "busy"),
        error_response(429, "rate_limit_exceeded", "slow down"),
        SSE().failed("external_connector_error", "connector hiccup"),
    ],
)
def test_transient_failures_are_retried_and_reported_as_backoff(failure: Any) -> None:
    backoffs: list[Exception] = []
    fake = FakePerplexity([failure, SSE("r2").text("Back online.").completed()])
    agent = _agent(fake, retry_strategy=TransientRetry(initial_delay=0, on_backoff=backoffs.append))
    assert str(agent("hi")).strip() == "Back online."
    assert len(fake.requests) == 2 and len(backoffs) == 1


def test_bad_requests_are_not_retried() -> None:
    backoffs: list[Exception] = []
    fake = FakePerplexity([error_response(400, "invalid_request", "bad tool schema")])
    agent = _agent(fake, retry_strategy=TransientRetry(initial_delay=0, on_backoff=backoffs.append))
    with pytest.raises(openai.BadRequestError):
        agent("hi")
    assert len(fake.requests) == 1 and backoffs == []


def test_exhausted_retries_escape_to_the_supervisor() -> None:
    fake = FakePerplexity([error_response(503, "service_unavailable", "busy") for _ in range(3)])
    agent = _agent(fake, retry_strategy=TransientRetry(max_attempts=3, initial_delay=0))
    with pytest.raises(PerplexityTransientError):
        agent("hi")
    assert len(fake.requests) == 3


def test_horizon_bearer_reads_the_key_on_every_request(monkeypatch: pytest.MonkeyPatch) -> None:
    seen: list[str | None] = []
    client = httpx.Client(
        auth=HorizonBearer(),
        transport=httpx.MockTransport(
            lambda request: (seen.append(request.headers.get("authorization")), httpx.Response(200))[1]
        ),
    )
    monkeypatch.setenv("HORIZON_API_KEY", "key-one")
    client.get("https://mcp.example/mcp")
    monkeypatch.setenv("HORIZON_API_KEY", "key-two")
    client.get("https://mcp.example/mcp")
    assert seen == ["Bearer key-one", "Bearer key-two"]

    monkeypatch.delenv("HORIZON_API_KEY")
    with pytest.raises(MissingCredentialError, match="HORIZON_API_KEY"):
        client.get("https://mcp.example/mcp")
    assert "key" not in repr(HorizonBearer()).replace("HORIZON_API_KEY", "")


@tool
def lookup_contact(name: str) -> str:
    """Look up a contact.

    Args:
        name: Contact name.
    """
    return "+15551234"


RESULTS = [{"id": 1, "url": "https://example.com/a", "title": "A", "snippet": "a", "source": "web"}]


def test_bridge_emits_ui_events_heartbeats_and_taints_on_web_content() -> None:
    events: list[dict[str, Any]] = []
    labels: list[str] = []
    untrusted: list[bool] = []
    bridge = RivetEventBridge(events.append, on_activity=labels.append, on_untrusted=lambda: untrusted.append(True))
    fake = FakePerplexity(
        [
            SSE().search(["ada lovelace"], RESULTS).function_call("lookup_contact", {"name": "Ada"}).completed(),
            SSE("r2").text("Ada is ", "+15551234.").completed(cost=0.03),
        ]
    )
    _agent(fake, tools=[lookup_contact], callback_handler=bridge)("find Ada's number")

    kinds = [e["type"] for e in events]
    assert kinds.index("searching") < kinds.index("sources") < kinds.index("tool") < kinds.index("tool_done")
    assert next(e for e in events if e["type"] == "searching")["queries"] == ["ada lovelace"]
    assert next(e for e in events if e["type"] == "tool") == {"type": "tool", "id": "call_1", "name": "lookup_contact"}
    assert kinds.count("tool") == 1
    assert next(e for e in events if e["type"] == "tool_done")["tools"] == [{"id": "call_1", "status": "success"}]
    assert "".join(e["delta"] for e in events if e["type"] == "text") == "Ada is +15551234."
    assert any(e["type"] == "usage" and e["cost"]["total_cost"] == 0.03 for e in events)
    assert untrusted and len(labels) > len(events)


def test_a_failing_sink_never_breaks_the_turn() -> None:
    def broken(event: dict[str, Any]) -> None:
        raise RuntimeError("socket closed")

    fake = FakePerplexity([SSE().text("still fine").completed()])
    assert str(_agent(fake, callback_handler=RivetEventBridge(broken))("hi")).strip() == "still fine"
