from __future__ import annotations

from typing import Any

import httpx
import pytest
from pydantic import BaseModel
from strands import Agent, tool
from strands.types.exceptions import ContextWindowOverflowException, MaxTokensReachedException, ModelThrottledException

from emrys_brain.models.perplexity import (
    PerplexityAgentModel,
    PerplexityTransientError,
    build_perplexity_model,
    translate_error,
)
from tests.sse import SSE, FakePerplexity, error_response

RESULTS = [
    {"id": 1, "url": "https://weather.example/slc", "title": "SLC weather", "snippet": "Sunny, 64F", "source": "web"},
    {"id": 2, "url": "https://news.example/a", "title": "Forecast", "snippet": "Clear skies", "source": "web"},
]


def make_model(fake: FakePerplexity, **kwargs: Any) -> PerplexityAgentModel:
    return build_perplexity_model(
        api_key="test-key", model_id="openai/gpt-5.6-sol", transport_factory=fake.transport_factory(), **kwargs
    )


async def collect(model: PerplexityAgentModel, messages: list[dict[str, Any]], **kwargs: Any) -> list[dict[str, Any]]:
    return [chunk async for chunk in model.stream(messages, **kwargs)]  # type: ignore[arg-type]


USER = [{"role": "user", "content": [{"text": "Text Ada that I'm running late"}]}]
SEND_SPEC = {
    "name": "send_sms",
    "description": "Send an SMS.",
    "inputSchema": {"json": {"type": "object", "properties": {"to": {"type": "string"}, "body": {"type": "string"}}}},
}


async def test_function_call_arguments_come_from_output_items() -> None:
    fake = FakePerplexity(
        [SSE().function_call("send_sms", {"to": "+15551234", "body": "late"}, signature="c2ln").completed()]
    )
    chunks = await collect(make_model(fake), USER, tool_specs=[SEND_SPEC])

    starts = [
        c["contentBlockStart"]["start"]["toolUse"]
        for c in chunks
        if "contentBlockStart" in c and "toolUse" in c["contentBlockStart"]["start"]
    ]
    deltas = [
        c["contentBlockDelta"]["delta"]["toolUse"]["input"]
        for c in chunks
        if "contentBlockDelta" in c and "toolUse" in c["contentBlockDelta"]["delta"]
    ]
    assert starts == [{"name": "send_sms", "toolUseId": "call_1", "reasoningSignature": "c2ln"}]
    assert deltas == ['{"to": "+15551234", "body": "late"}']
    assert {"messageStop": {"stopReason": "tool_use"}} in chunks


async def test_request_keeps_native_tools_and_perplexity_fields() -> None:
    fake = FakePerplexity([SSE().text("ok").completed()])
    await collect(
        make_model(fake, fallback_models=("anthropic/claude-sonnet-5-5",)),
        USER,
        tool_specs=[SEND_SPEC],
        system_prompt="You are Emrys.",
    )
    body = fake.requests[0]
    assert [t["type"] for t in body["tools"]] == [
        "web_search",
        "fetch_url",
        "image_search",
        "finance_search",
        "people_search",
        "function",
    ]
    assert body["tools"][0] == {"type": "web_search", "search_type": "fast"}
    assert body["max_steps"] == 8
    assert body["models"] == ["openai/gpt-5.6-sol", "anthropic/claude-sonnet-5-5"]
    assert body["max_output_tokens"] == 16384
    assert body["store"] is False and body["stream"] is True and body["instructions"] == "You are Emrys."
    assert "previous_response_id" not in body


async def test_native_tools_are_sent_again_on_every_request() -> None:
    fake = FakePerplexity([SSE().text("a").completed(), SSE("resp_2").text("b").completed()])
    model = make_model(fake)
    await collect(model, USER, tool_specs=[SEND_SPEC])
    await collect(model, USER, tool_specs=[SEND_SPEC])
    assert [len(r["tools"]) for r in fake.requests] == [6, 6]
    assert len(model.get_config()["params"]["tools"]) == 5  # config never mutated


async def test_search_results_become_citations_in_history() -> None:
    fake = FakePerplexity([SSE().search(["slc weather"], RESULTS).text("It's sunny ", "today.[web:1]").completed()])
    agent = Agent(model=make_model(fake), callback_handler=None)
    agent("What's the weather in Salt Lake?")
    content = agent.messages[-1]["content"]
    block = next(c["citationsContent"] for c in content if "citationsContent" in c)
    assert block["content"] == [{"text": "It's sunny today.[web:1]"}]
    assert [c["location"]["web"]["url"] for c in block["citations"]] == [r["url"] for r in RESULTS]


async def test_reasoning_progress_reaches_the_callback_handler() -> None:
    seen: list[dict[str, Any]] = []

    def handler(**kwargs: Any) -> None:
        event = kwargs.get("event")
        if isinstance(event, dict) and "emrys" in event:
            seen.append(event["emrys"])

    fake = FakePerplexity([SSE().search(["slc weather"], RESULTS).text("Sunny.").completed(cost=0.02)])
    Agent(model=make_model(fake), callback_handler=handler)("weather?")
    kinds = [e["type"] for e in seen]
    assert "reasoning.search_queries" in kinds and "search_results" in kinds and "usage" in kinds
    queries = next(e for e in seen if e["type"] == "reasoning.search_queries")
    assert queries["queries"] == ["slc weather"]
    assert next(e for e in seen if e["type"] == "usage")["cost"]["total_cost"] == 0.02


async def test_full_tool_loop_runs_the_tool_with_real_arguments() -> None:
    sent: list[dict[str, str]] = []

    @tool
    def send_sms(to: str, body: str) -> str:
        """Send an SMS.

        Args:
            to: Recipient number.
            body: Message text.
        """
        sent.append({"to": to, "body": body})
        return "sent"

    fake = FakePerplexity(
        [
            SSE().function_call("send_sms", {"to": "+15551234", "body": "Running late"}, signature="c2ln").completed(),
            SSE("resp_2").text("Done, I texted Ada.").completed(),
        ]
    )
    agent = Agent(model=make_model(fake), tools=[send_sms], callback_handler=None)
    result = agent("Text Ada that I'm running late")

    assert sent == [{"to": "+15551234", "body": "Running late"}]
    assert str(result).strip() == "Done, I texted Ada."
    replay = fake.requests[1]["input"]
    call = next(i for i in replay if i.get("type") == "function_call")
    output_index = next(n for n, i in enumerate(replay) if i.get("type") == "function_call_output")
    assert call["thought_signature"] == "c2ln"
    assert replay[output_index]["call_id"] == call["call_id"] == "call_1"


def test_tool_outputs_precede_text_injected_into_the_same_user_message() -> None:
    messages = [
        {"role": "user", "content": [{"text": "go"}]},
        {"role": "assistant", "content": [{"toolUse": {"toolUseId": "c1", "name": "t", "input": {}}}]},
        {
            "role": "user",
            "content": [
                {"toolResult": {"toolUseId": "c1", "status": "success", "content": [{"text": "r"}]}},
                {"text": "<todos>1. send</todos>"},
            ],
        },
    ]
    items = PerplexityAgentModel._format_request_messages(messages)  # type: ignore[arg-type]
    types = [i.get("type", i.get("role")) for i in items]
    assert types == ["user", "function_call", "function_call_output", "user"]


async def test_incomplete_for_max_output_tokens_is_max_tokens() -> None:
    fake = FakePerplexity([SSE().text("partial").incomplete("max_output_tokens")])
    chunks = await collect(make_model(fake), USER)
    assert {"messageStop": {"stopReason": "max_tokens"}} in chunks
    fake = FakePerplexity([SSE().text("partial").incomplete("max_output_tokens")])
    with pytest.raises(MaxTokensReachedException):
        Agent(model=make_model(fake), callback_handler=None)("go")


async def test_incomplete_for_other_reasons_ends_the_turn_with_a_notice() -> None:
    fake = FakePerplexity([SSE().text("partial").incomplete("max_steps")])
    chunks = await collect(make_model(fake), USER)
    assert {"messageStop": {"stopReason": "end_turn"}} in chunks
    assert {"emrys": {"type": "incomplete", "reason": "max_steps"}} in chunks


async def test_message_item_without_text_deltas_still_yields_text() -> None:
    message = {
        "type": "message",
        "id": "msg_9",
        "role": "assistant",
        "status": "completed",
        "content": [{"type": "output_text", "text": "Hello from the snapshot"}],
    }
    fake = FakePerplexity([SSE().item(message).completed()])
    agent = Agent(model=make_model(fake), callback_handler=None)
    assert str(agent("hi")).strip() == "Hello from the snapshot"


@pytest.mark.parametrize(
    ("response", "expected"),
    [
        (error_response(429, "rate_limit_exceeded", "slow down"), ModelThrottledException),
        (error_response(503, "service_unavailable", "busy"), PerplexityTransientError),
        (error_response(500, "server_error", "boom"), PerplexityTransientError),
        (
            error_response(400, "invalid_request", "maximum context length is 400000 tokens"),
            ContextWindowOverflowException,
        ),
    ],
)
async def test_http_errors_are_typed(response: httpx.Response, expected: type[Exception]) -> None:
    with pytest.raises(expected):
        await collect(make_model(FakePerplexity([response])), USER)


async def test_bad_request_is_not_retryable_and_propagates_raw() -> None:
    import openai

    with pytest.raises(openai.BadRequestError):
        await collect(make_model(FakePerplexity([error_response(400, "invalid_request", "bad tool")])), USER)


@pytest.mark.parametrize(
    ("code", "expected"),
    [
        ("rate_limit_exceeded", ModelThrottledException),
        ("overloaded", PerplexityTransientError),
        ("external_connector_error", PerplexityTransientError),
    ],
)
async def test_stream_failures_are_typed(code: str, expected: type[Exception]) -> None:
    fake = FakePerplexity([SSE().text("par").failed(code, "upstream said no")])
    with pytest.raises(expected):
        await collect(make_model(fake), USER)


async def test_connection_errors_are_transient() -> None:
    def refuse(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    with pytest.raises(PerplexityTransientError):
        await collect(make_model(FakePerplexity([refuse])), USER)


def test_translate_error_leaves_unknown_errors_alone() -> None:
    error = ValueError("nope")
    assert translate_error(error) is error


async def test_structured_output_uses_response_format() -> None:
    class Verdict(BaseModel):
        passed: bool
        feedback: str

    payload = {
        "id": "resp_s",
        "object": "response",
        "status": "completed",
        "model": "m",
        "created_at": 1,
        "output": [
            {
                "type": "message",
                "id": "m1",
                "role": "assistant",
                "status": "completed",
                "content": [{"type": "output_text", "text": '{"passed": true, "feedback": "ok"}'}],
            }
        ],
    }
    fake = FakePerplexity([httpx.Response(200, json=payload)])
    model = make_model(fake)
    outputs = [e async for e in model.structured_output(Verdict, USER)]  # type: ignore[arg-type]
    assert outputs[-1]["output"] == Verdict(passed=True, feedback="ok")
    body = fake.requests[0]
    assert body["response_format"]["type"] == "json_schema"
    assert body["response_format"]["json_schema"]["name"] == "Verdict"
    assert "tools" not in body and "stream" not in body


def test_stateful_is_refused() -> None:
    with pytest.raises(ValueError, match="stateless"):
        PerplexityAgentModel(client_args={"api_key": "k"}, model_id="m", stateful=True)


def test_aux_model_has_no_native_tools() -> None:
    aux = build_perplexity_model(api_key="k", model_id="openai/gpt-5.6-luna", native_tools=(), max_steps=1)
    params = aux.get_config()["params"]
    assert "tools" not in params and params["extra_body"] == {"max_steps": 1}
