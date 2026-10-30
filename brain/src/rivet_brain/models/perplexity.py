"""Perplexity Agent API, spoken over the OpenAI Responses interface.

Perplexity's Agent API accepts ``POST /v1/responses`` as an alias of ``/v1/agent``, so the Strands
``OpenAIResponsesModel`` reaches it once ``base_url`` points at ``https://api.perplexity.ai/v1``.
Server-side native tools (``web_search``, ``fetch_url``, ...) ride in ``params["tools"]``, which the
parent merges with the agent's function tools on every request; Perplexity-only request fields
(``max_steps``, ``models``) ride in ``params["extra_body"]``.

The parent was written against OpenAI's event stream. Perplexity's differs, so this subclass owns the
stream loop and request formatting:

- Function-call arguments arrive on ``response.output_item.added/done`` items; Perplexity sends no
  ``response.function_call_arguments.*`` events, so the parent would emit every call with empty input.
- Native-tool results (``search_results``, ``fetch_url_results``, ``people_search_results``) become
  Strands citations, so sources persist in the session as ``citationsContent``.
- ``thought_signature`` on a function call maps onto the SDK's ``ToolUse.reasoningSignature`` and is
  sent back when the call is replayed.
- ``response.reasoning.*`` progress events and every other non-content event are re-emitted as
  ``{"rivet": {...}}`` chunks. They feed Rivet's "think" HUD through the callback handler and, because
  the SDK checks for cancellation on every chunk, keep the stream observable and cancellable while
  Perplexity runs server-side tools that produce no tokens.
- ``response.incomplete`` reasons map onto Strands stop reasons instead of collapsing to ``end_turn``.
- Errors are translated to ``ModelThrottledException``, ``ContextWindowOverflowException`` or
  :class:`PerplexityTransientError`, so retry strategies can tell what is retryable.
- ``function_call_output`` items are sent before any text injected into the same user message
  (todos, memory), keeping the call/output pairing intact.
"""

from __future__ import annotations

import logging
import re
from collections.abc import AsyncGenerator, Callable, Iterable, Mapping
from dataclasses import dataclass, field
from typing import Literal, TypedDict, TypeVar, Unpack, cast, override

import httpx
import openai
from pydantic import BaseModel
from strands.agent.agent_metadata import AgentMetadata
from strands.models.openai_responses import OpenAIResponsesModel
from strands.types.citations import Citation, WebLocationDict
from strands.types.content import ContentBlockStartToolUse, Messages
from strands.types.event_loop import Usage
from strands.types.exceptions import ContextWindowOverflowException, ModelThrottledException
from strands.types.streaming import StreamEvent
from strands.types.tools import ToolChoice, ToolSpec, ToolUse

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)

PERPLEXITY_BASE_URL = "https://api.perplexity.ai/v1"

DEFAULT_NATIVE_TOOLS: tuple[Mapping[str, object], ...] = (
    {"type": "web_search", "search_type": "fast"},
    {"type": "fetch_url"},
    {"type": "image_search"},
    {"type": "finance_search"},
    {"type": "people_search"},
)

_RATE_LIMIT_CODES = frozenset({"rate_limit_exceeded", "rate_limited", "too_many_requests", "rate_limit"})
_RATE_LIMIT_PATTERNS = ("rate limit", "rate_limit", "too many requests")
_OVERFLOW_CODES = frozenset({"context_length_exceeded", "context_window_exceeded"})
_OVERFLOW_PATTERNS = (
    "maximum context length",
    "context_length_exceeded",
    "context window",
    "context length",
    "too many tokens",
    "prompt is too long",
    "input is too long",
)
_OVERFLOW_REGEX = re.compile(r"exceed(?:s|ed)?(?: the)?(?: customer)? model(?:'s)? maximum")
_TRANSIENT_CODES = frozenset(
    {
        "server_error",
        "internal_error",
        "internal_server_error",
        "service_unavailable",
        "overloaded",
        "overloaded_error",
        "upstream_error",
        "bad_gateway",
        "gateway_timeout",
        "timeout",
        "external_connector_error",
    }
)
_TRANSIENT_PATTERNS = ("overloaded", "temporarily unavailable", "try again", "upstream", "timed out", "timeout")

# Output items whose results are web sources the answer may cite.
_SOURCE_ITEMS: dict[str, Literal["results", "contents"]] = {
    "search_results": "results",
    "people_search_results": "results",
    "fetch_url_results": "contents",
}
# Output items reported to the HUD only; their content reaches the model inside Perplexity's loop.
_REPORT_ITEMS = frozenset(
    {
        "image_search_results",
        "finance_results",
        "sandbox_results",
        "share_file",
        "mcp_list_tools",
        "mcp_call",
        "mcp_approval_request",
        "tool_search_output",
    }
)
_MAX_CITATIONS = 12


# Perplexity's stream and response objects, as far as the handlers read them; each value is checked where it is used.
class _Annotation(TypedDict, total=False):
    type: object
    url: object
    title: object


class _Item(TypedDict, total=False):
    type: object
    id: object
    call_id: object
    name: object
    content: list[object]
    queries: object
    results: list[object]
    contents: list[object]


class _TokenDetails(TypedDict, total=False):
    cached_tokens: int
    cache_read_input_tokens: int
    cache_creation_input_tokens: int
    cache_write_tokens: int


class _Usage(TypedDict, total=False):
    input_tokens: int
    output_tokens: int
    total_tokens: int
    input_tokens_details: _TokenDetails
    cost: object
    tool_calls_details: object


class _Error(TypedDict, total=False):
    message: object
    code: object
    type: object


class _IncompleteDetails(TypedDict, total=False):
    reason: object


class _Response(TypedDict, total=False):
    id: object
    usage: _Usage
    output: list[object]
    incomplete_details: _IncompleteDetails
    error: _Error


class _Event(TypedDict, total=False):
    type: object
    response: _Response
    item_id: object
    delta: str
    arguments: str
    annotation: _Annotation
    item: _Item
    error: _Error


class ClientArgs(TypedDict, total=False):
    """``openai.AsyncOpenAI`` arguments (:func:`build_perplexity_model` sets ``api_key``, ``base_url``, ``timeout``)."""

    api_key: str | None
    base_url: str
    timeout: float
    max_retries: int
    http_client: httpx.AsyncClient


class PerplexityTransientError(RuntimeError):
    """A retryable Perplexity failure: 5xx, connection loss, timeout, overload, or an upstream fault."""

    def __init__(self, message: str, *, code: str | None = None, status_code: int | None = None) -> None:
        """Record the provider's error code and HTTP status alongside the message.

        Args:
            message: The provider's error message.
            code: The provider's error code, when it sent one.
            status_code: The HTTP status, when the failure came with a response.
        """
        super().__init__(message)
        self.code = code
        self.status_code = status_code


def translate_error(error: BaseException) -> BaseException:
    """Map an OpenAI-SDK or transport error from Perplexity onto the SDK's typed exceptions.

    Returns the original error when it is not throttling, overflow, or transient, so callers re-raise it
    unchanged.
    """
    message = str(error)
    lowered = message.lower()
    status = getattr(error, "status_code", None)
    raw_code = getattr(error, "code", None)
    code = raw_code.lower() if isinstance(raw_code, str) else ""

    if status == 429 or code in _RATE_LIMIT_CODES or any(p in lowered for p in _RATE_LIMIT_PATTERNS):
        return ModelThrottledException(message)
    if code in _OVERFLOW_CODES or any(p in lowered for p in _OVERFLOW_PATTERNS) or _OVERFLOW_REGEX.search(lowered):
        return ContextWindowOverflowException(message)
    if _is_transient(error, status, code, lowered):
        return PerplexityTransientError(message, code=code or None, status_code=status)
    return error


def _is_transient(error: BaseException, status: object, code: str, lowered: str) -> bool:
    if isinstance(error, (openai.APIConnectionError, httpx.TransportError)):
        return True
    if isinstance(status, int) and (status >= 500 or status in (408, 409, 424)):
        return True
    return code in _TRANSIENT_CODES or any(p in lowered for p in _TRANSIENT_PATTERNS)


def rivet_chunk(kind: str, **fields: object) -> StreamEvent:
    """A custom stream chunk the SDK passes through to the callback handler as ``event``."""
    return cast(StreamEvent, {"rivet": {"type": kind, **fields}})


def _as_dict(value: openai.BaseModel) -> dict[str, object]:
    """Normalize an OpenAI SDK object to a dict. Perplexity-only events parse into the wrong SDK classes,
    but ``to_dict`` keeps every field the server sent."""
    return value.to_dict(warnings=False)


@dataclass
class _FunctionCall:
    call_id: str
    name: str
    arguments: str = ""
    signature: str | None = None


@dataclass
class _StreamState:
    """Accumulates one response's stream into Strands chunks."""

    model_state: dict[str, object] | None
    data_type: str | None = None
    calls: dict[str, _FunctionCall] = field(default_factory=dict)
    pending_citations: list[Citation] = field(default_factory=list)
    cited_urls: set[str] = field(default_factory=set)
    text_item_ids: set[str] = field(default_factory=set)
    stop_reason: str | None = None
    usage: _Usage | None = None
    done: bool = False

    def switch(self, data_type: str) -> list[StreamEvent]:
        """Close the open content block (flushing citations into a text block) and open ``data_type``."""
        if data_type == self.data_type:
            return []
        chunks = self.close()
        chunks.append({"contentBlockStart": {"start": {}}})
        self.data_type = data_type
        return chunks

    def close(self) -> list[StreamEvent]:
        if self.data_type is None:
            return []
        chunks: list[StreamEvent] = []
        if self.data_type == "text":
            chunks.extend(self.flush_citations())
        chunks.append({"contentBlockStop": {}})
        self.data_type = None
        return chunks

    def flush_citations(self) -> list[StreamEvent]:
        chunks: list[StreamEvent] = [
            cast(StreamEvent, {"contentBlockDelta": {"delta": {"citation": citation}}})
            for citation in self.pending_citations
        ]
        self.pending_citations = []
        return chunks

    def add_source(self, url: object, title: object) -> None:
        if not isinstance(url, str) or not url or url in self.cited_urls or len(self.cited_urls) >= _MAX_CITATIONS:
            return
        self.cited_urls.add(url)
        location: WebLocationDict = {"web": {"url": url}}
        self.pending_citations.append({"title": str(title or url), "location": location})

    def record_call(self, item: Mapping[str, object], *, final: bool) -> None:
        key = str(item.get("id") or item.get("call_id") or "")
        call = self.calls.get(key)
        if call is None:
            call = _FunctionCall(call_id=str(item.get("call_id") or key), name=str(item.get("name") or ""))
            self.calls[key] = call
        arguments = item.get("arguments")
        if isinstance(arguments, str) and (final or arguments):
            call.arguments = arguments or call.arguments
        if item.get("thought_signature"):
            call.signature = str(item["thought_signature"])


class PerplexityAgentModel(OpenAIResponsesModel):
    """``OpenAIResponsesModel`` adapted to Perplexity's Agent API stream and error shapes.

    Configure it with :func:`build_perplexity_model`. It must run with ``stateful=False``: the harness's
    context manager and the session snapshots own the conversation, and the SDK refuses a context manager
    on a stateful model.
    """

    def __init__(
        self,
        client_args: ClientArgs | None = None,
        *,
        transport_factory: Callable[[], httpx.AsyncBaseTransport] | None = None,
        **model_config: Unpack[OpenAIResponsesModel.OpenAIResponsesConfig],
    ) -> None:
        """Initialize the provider.

        Args:
            client_args: Arguments for ``openai.AsyncOpenAI`` (``api_key``, ``base_url``, ``timeout``).
                ``max_retries`` defaults to 0 so retries belong to the agent's retry strategy alone.
            transport_factory: Builds a fresh ``httpx`` transport for each request (tests inject a mock
                here). A new ``AsyncOpenAI`` client is created per request and closed afterwards, so a
                shared ``http_client`` would be closed after the first call.
            **model_config: ``OpenAIResponsesModel`` configuration (``model_id``, ``params``, ...).

        Raises:
            ValueError: If ``stateful=True`` is requested.
        """
        if model_config.get("stateful"):
            raise ValueError("PerplexityAgentModel runs stateless; sessions and the context manager own history.")
        super().__init__(client_args=dict(client_args or {}), **model_config)
        self._client_args: ClientArgs = {"max_retries": 0, **(client_args or {})}
        self._transport_factory = transport_factory

    @override
    def _resolve_client_args(self) -> dict[str, object]:
        return dict(self._openai_args())

    def _openai_args(self) -> ClientArgs:
        args = self._client_args.copy()
        if self._transport_factory is not None:
            args["http_client"] = httpx.AsyncClient(transport=self._transport_factory())
        return args

    @override
    async def stream(
        self,
        messages: Messages,
        tool_specs: list[ToolSpec] | None = None,
        system_prompt: str | None = None,
        *,
        tool_choice: ToolChoice | None = None,
        model_state: dict[str, object] | None = None,
        agent_metadata: AgentMetadata | None = None,
        **kwargs: object,
    ) -> AsyncGenerator[StreamEvent, None]:
        """Stream one Agent API response as Strands chunks.

        Raises:
            ModelThrottledException: When Perplexity rate-limits the request.
            ContextWindowOverflowException: When the input exceeds the model's context window.
            PerplexityTransientError: On 5xx, connection loss, timeouts, overload, or upstream faults.
        """
        request = self._format_request(messages, tool_specs, system_prompt, tool_choice, model_state, agent_metadata)
        state = _StreamState(model_state=model_state)
        async with openai.AsyncOpenAI(**self._openai_args()) as client:
            try:
                response = await client.responses.create(**request)
                yield {"messageStart": {"role": "assistant"}}
                async for event in response:
                    for chunk in self._handle_event(cast(_Event, _as_dict(event)), state):
                        yield chunk
                    if state.done:
                        break
            except (openai.APIError, httpx.HTTPError) as error:
                translated = translate_error(error)
                if translated is error:
                    raise
                raise translated from error

        for chunk in self._finish(state):
            yield chunk

    def _handle_event(self, event: _Event, state: _StreamState) -> list[StreamEvent]:
        kind = str(event.get("type", ""))
        handler = _EVENT_HANDLERS.get(kind)
        if handler is not None:
            return handler(self, event, state)
        if kind.startswith("response.reasoning."):
            return self._on_reasoning_progress(event, state)
        return [rivet_chunk("activity", event=kind)]

    # -- event handlers --

    def _on_created(self, event: _Event, state: _StreamState) -> list[StreamEvent]:
        response_id = (event.get("response") or {}).get("id")
        if state.model_state is not None and response_id:
            state.model_state["response_id"] = response_id
        return [rivet_chunk("response_created", response_id=response_id)]

    def _on_text_delta(self, event: _Event, state: _StreamState) -> list[StreamEvent]:
        delta = event.get("delta")
        if not isinstance(delta, str):
            return []
        if event.get("item_id"):
            state.text_item_ids.add(str(event["item_id"]))
        return [*state.switch("text"), {"contentBlockDelta": {"delta": {"text": delta}}}]

    def _on_reasoning_delta(self, event: _Event, state: _StreamState) -> list[StreamEvent]:
        delta = event.get("delta")
        if not isinstance(delta, str):
            return []
        return [*state.switch("reasoning"), {"contentBlockDelta": {"delta": {"reasoningContent": {"text": delta}}}}]

    def _on_annotation(self, event: _Event, state: _StreamState) -> list[StreamEvent]:
        annotation = event.get("annotation") or {}
        if annotation.get("type") == "url_citation":
            state.add_source(annotation.get("url"), annotation.get("title"))
        return []

    def _on_reasoning_progress(self, event: Mapping[str, object], state: _StreamState) -> list[StreamEvent]:
        kind = str(event.get("type", "")).removeprefix("response.reasoning.")
        fields: dict[str, object] = {}
        for key in ("queries", "thought", "results", "contents"):
            if key in event:
                fields[key] = _summarize(event[key]) if key in ("results", "contents") else event[key]
        return [rivet_chunk(f"reasoning.{kind}", **fields)]

    def _on_item(self, event: _Event, state: _StreamState) -> list[StreamEvent]:
        item = event.get("item") or {}
        item_type = str(item.get("type", ""))
        final = event.get("type") == "response.output_item.done"
        if item_type == "function_call":
            state.record_call(item, final=final)
            return [rivet_chunk("tool_call", name=item.get("name"), call_id=item.get("call_id"), final=final)]
        if not final:
            return [rivet_chunk("item_started", item_type=item_type)]
        return self._on_final_item(item, item_type, state)

    def _on_final_item(self, item: _Item, item_type: str, state: _StreamState) -> list[StreamEvent]:
        if item_type in _SOURCE_ITEMS:
            entries = item.get(_SOURCE_ITEMS[item_type]) or []
            for entry in entries:
                if isinstance(entry, dict):
                    state.add_source(entry.get("url"), entry.get("title"))
            return [rivet_chunk(item_type, queries=item.get("queries"), results=_summarize(entries))]
        if item_type in _REPORT_ITEMS:
            return [rivet_chunk(item_type, item=_summarize_item(item))]
        if item_type == "message":
            return self._message_fallback(item, state)
        return [rivet_chunk("item", item_type=item_type)]

    def _message_fallback(self, item: _Item, state: _StreamState) -> list[StreamEvent]:
        """Emit a message's text when it arrived only as a completed item, without text deltas."""
        if str(item.get("id", "")) in state.text_item_ids:
            return []
        text = "".join(
            str(part.get("text", ""))
            for part in item.get("content") or []
            if isinstance(part, dict) and part.get("type") == "output_text"
        )
        if not text:
            return []
        state.text_item_ids.add(str(item.get("id", "")))
        return [*state.switch("text"), {"contentBlockDelta": {"delta": {"text": text}}}]

    def _on_arguments_delta(self, event: _Event, state: _StreamState) -> list[StreamEvent]:
        call = state.calls.get(str(event.get("item_id", "")))
        if call is not None and isinstance(event.get("delta"), str):
            call.arguments += event["delta"]
        return []

    def _on_arguments_done(self, event: _Event, state: _StreamState) -> list[StreamEvent]:
        call = state.calls.get(str(event.get("item_id", "")))
        if call is not None and isinstance(event.get("arguments"), str):
            call.arguments = event["arguments"]
        return []

    def _on_completed(self, event: _Event, state: _StreamState) -> list[StreamEvent]:
        response = event.get("response") or {}
        state.usage = response.get("usage")
        # Recover function calls that only appear in the final snapshot.
        for item in response.get("output") or []:
            if isinstance(item, dict) and item.get("type") == "function_call":
                state.record_call(item, final=True)
        state.done = True
        return []

    def _on_incomplete(self, event: _Event, state: _StreamState) -> list[StreamEvent]:
        response = event.get("response") or {}
        state.usage = response.get("usage")
        reason = (response.get("incomplete_details") or {}).get("reason")
        if reason == "max_output_tokens":
            state.stop_reason = "max_tokens"
        elif reason == "content_filter":
            state.stop_reason = "content_filtered"
        else:
            logger.warning("reason=<%s> | perplexity response incomplete; ending the turn", reason)
        state.done = True
        return [rivet_chunk("incomplete", reason=reason)]

    def _on_failed(self, event: _Event, state: _StreamState) -> list[StreamEvent]:
        error = event.get("error") or (event.get("response") or {}).get("error") or {}
        message = str(error.get("message") or "Perplexity response failed")
        raise _stream_failure(message, error.get("code") or error.get("type"))

    # -- finalization --

    def _finish(self, state: _StreamState) -> Iterable[StreamEvent]:
        yield from state.close()
        for call in state.calls.values():
            if not call.name:
                continue
            start: ContentBlockStartToolUse = {"name": call.name, "toolUseId": call.call_id}
            if call.signature:
                start["reasoningSignature"] = call.signature
            yield cast(StreamEvent, {"contentBlockStart": {"start": {"toolUse": start}}})
            yield {"contentBlockDelta": {"delta": {"toolUse": {"input": call.arguments or ""}}}}
            yield {"contentBlockStop": {}}
        yield cast(StreamEvent, {"messageStop": {"stopReason": _stop_reason(state)}})
        if state.usage:
            yield _usage_chunk(state.usage)
            if state.usage.get("cost"):
                yield rivet_chunk("usage", cost=state.usage["cost"], tools=state.usage.get("tool_calls_details"))

    # -- request formatting --

    @override
    @classmethod
    def _format_request_messages(cls, messages: Messages) -> list[dict[str, object]]:
        formatted: list[dict[str, object]] = []
        for message in messages:
            items = super()._format_request_messages([message])
            outputs = [item for item in items if item.get("type") == "function_call_output"]
            if outputs and message["role"] == "user":
                items = [*outputs, *(item for item in items if item.get("type") != "function_call_output")]
            formatted.extend(items)
        return formatted

    @override
    @classmethod
    def _format_request_message_tool_call(cls, tool_use: ToolUse) -> dict[str, object]:
        item = super()._format_request_message_tool_call(tool_use)
        signature = tool_use.get("reasoningSignature")
        if signature:
            item["thought_signature"] = signature
        return item

    @override
    async def structured_output(
        self, output_model: type[T], prompt: Messages, system_prompt: str | None = None, **kwargs: object
    ) -> AsyncGenerator[dict[str, T], None]:
        """Get structured output through Perplexity's ``response_format`` JSON schema.

        Raises:
            ValueError: If the response is not valid JSON for ``output_model``.
        """
        request = self._format_request(prompt, system_prompt=system_prompt)
        request.pop("stream", None)
        request.pop("tools", None)
        extra_body = dict(request.pop("extra_body", None) or {})
        extra_body["response_format"] = {
            "type": "json_schema",
            "json_schema": {"name": _schema_name(output_model), "schema": output_model.model_json_schema()},
        }
        async with openai.AsyncOpenAI(**self._openai_args()) as client:
            try:
                response = await client.responses.create(**request, extra_body=extra_body)
            except (openai.APIError, httpx.HTTPError) as error:
                translated = translate_error(error)
                if translated is error:
                    raise
                raise translated from error
        text = _output_text(cast(_Response, _as_dict(response)))
        try:
            yield {"output": output_model.model_validate_json(text)}
        except ValueError as error:
            raise ValueError(f"Perplexity returned output that does not match {output_model.__name__}") from error


_Handler = Callable[[PerplexityAgentModel, _Event, _StreamState], list[StreamEvent]]
_EVENT_HANDLERS: dict[str, _Handler] = {
    "response.created": PerplexityAgentModel._on_created,
    "response.output_text.delta": PerplexityAgentModel._on_text_delta,
    "response.reasoning_text.delta": PerplexityAgentModel._on_reasoning_delta,
    "response.reasoning_summary_text.delta": PerplexityAgentModel._on_reasoning_delta,
    "response.output_text.annotation.added": PerplexityAgentModel._on_annotation,
    "response.output_item.added": PerplexityAgentModel._on_item,
    "response.output_item.done": PerplexityAgentModel._on_item,
    "response.function_call_arguments.delta": PerplexityAgentModel._on_arguments_delta,
    "response.function_call_arguments.done": PerplexityAgentModel._on_arguments_done,
    "response.completed": PerplexityAgentModel._on_completed,
    "response.incomplete": PerplexityAgentModel._on_incomplete,
    "response.failed": PerplexityAgentModel._on_failed,
    "error": PerplexityAgentModel._on_failed,
}


def _stream_failure(message: str, code: object) -> BaseException:
    carrier = PerplexityTransientError(message, code=str(code).lower() if code else None)
    translated = translate_error(carrier)
    if isinstance(translated, PerplexityTransientError):
        return translated
    if translated is carrier:
        return RuntimeError(f"Perplexity response failed: {message} (code={code})")
    return translated


def _stop_reason(state: _StreamState) -> str:
    if state.stop_reason == "max_tokens":
        return "max_tokens"
    if any(call.name for call in state.calls.values()):
        return "tool_use"
    if state.stop_reason == "content_filtered":
        return "content_filtered"
    return "end_turn"


def _usage_chunk(usage: _Usage) -> StreamEvent:
    data: Usage = {
        "inputTokens": int(usage.get("input_tokens") or 0),
        "outputTokens": int(usage.get("output_tokens") or 0),
        "totalTokens": int(usage.get("total_tokens") or 0),
    }
    details = usage.get("input_tokens_details") or {}
    cached = details.get("cached_tokens") or details.get("cache_read_input_tokens")
    if isinstance(cached, int) and cached:
        data["cacheReadInputTokens"] = cached
    written = details.get("cache_creation_input_tokens") or details.get("cache_write_tokens")
    if isinstance(written, int) and written:
        data["cacheWriteInputTokens"] = written
    return cast(StreamEvent, {"metadata": {"usage": data, "metrics": {"latencyMs": 0}}})


def _summarize(entries: object, limit: int = 8) -> list[dict[str, object]]:
    if not isinstance(entries, list):
        return []
    keep = ("id", "url", "title", "image_url", "origin_url", "date", "source")
    return [{k: v for k, v in entry.items() if k in keep} for entry in entries[:limit] if isinstance(entry, dict)]


def _summarize_item(item: _Item) -> dict[str, object]:
    drop = {"results", "contents", "output", "tools", "input_schema"}
    summary = {k: v for k, v in item.items() if k not in drop and not isinstance(v, (dict, list))}
    if isinstance(item.get("results"), list):
        summary["result_count"] = len(item["results"])
    return summary


def _output_text(response: _Response) -> str:
    return "".join(
        str(part.get("text", ""))
        for item in response.get("output") or []
        if isinstance(item, dict) and item.get("type") == "message"
        for part in item.get("content") or []
        if isinstance(part, dict) and part.get("type") == "output_text"
    )


def _schema_name(model: type[BaseModel]) -> str:
    name = re.sub(r"[^A-Za-z0-9]", "", model.__name__) or "output"
    return name[:64]


def build_perplexity_model(
    *,
    api_key: str | None,
    model_id: str,
    base_url: str = PERPLEXITY_BASE_URL,
    fallback_models: tuple[str, ...] = (),
    native_tools: tuple[Mapping[str, object], ...] = DEFAULT_NATIVE_TOOLS,
    max_steps: int = 8,
    context_window_limit: int | None = None,
    timeout: float = 180.0,
    transport_factory: Callable[[], httpx.AsyncBaseTransport] | None = None,
) -> PerplexityAgentModel:
    """Build a :class:`PerplexityAgentModel` with Rivet's defaults.

    Args:
        api_key: The Perplexity API key.
        model_id: The primary model, in ``provider/name`` form.
        base_url: The Agent API base URL.
        fallback_models: Further models tried in order when the primary fails (sent as ``models``).
        native_tools: Perplexity server-side tools to enable. Pass ``()`` for an auxiliary model so side
            calls (summaries, judging, memory extraction) never trigger searches.
        max_steps: Perplexity's per-request loop bound. Direct-model requests default to 1 server-side,
            which leaves no room for a tool call and a follow-up, so it is always sent.
        context_window_limit: The real context window, for the context manager's utilization math.
        timeout: Per-request timeout in seconds for the OpenAI client.
        transport_factory: Test seam: a fresh ``httpx`` transport per request.

    Returns:
        The configured model.
    """
    chain = (model_id, *fallback_models)
    params: dict[str, object] = {}
    if native_tools:
        params["tools"] = [dict(tool) for tool in native_tools]
    extra_body: dict[str, object] = {"max_steps": max_steps}
    if fallback_models:
        extra_body["models"] = list(chain)
    params["extra_body"] = extra_body
    config: OpenAIResponsesModel.OpenAIResponsesConfig = {"model_id": model_id, "params": params, "stateful": False}
    if context_window_limit:
        config["context_window_limit"] = context_window_limit
    return PerplexityAgentModel(
        client_args={"api_key": api_key, "base_url": base_url, "timeout": timeout},
        transport_factory=transport_factory,
        **config,
    )
