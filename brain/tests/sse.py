"""Builders for Perplexity Agent API server-sent-event streams, shaped after the published schema.

Event and item shapes follow ``docs.perplexity.ai/api-reference/agent-post`` (``ResponseStreamEvent`` and
``OutputItem``). Notably there are no ``response.function_call_arguments.*`` events: a function call
arrives whole on ``response.output_item.added/done``. Groq streams a function call as an empty item, then
``function_call_arguments.delta`` and ``.done`` keyed by the item id (``groq_function_call``; recorded live from
Groq with a function tool).
"""

from __future__ import annotations

import itertools
import json
from collections.abc import Callable, Iterable
from typing import Any

import httpx

MODEL = "openai/gpt-5.6-sol"


class SSE:
    """Accumulates events for one streamed response."""

    def __init__(self, response_id: str = "resp_1") -> None:
        self.response_id = response_id
        self._seq = itertools.count()
        self.events: list[dict[str, Any]] = []
        self._emit("response.created", response=self._response("in_progress"))
        self._emit("response.in_progress", response=self._response("in_progress"))

    def _response(self, status: str, **extra: Any) -> dict[str, Any]:
        return {
            "id": self.response_id,
            "object": "response",
            "status": status,
            "model": MODEL,
            "created_at": 1,
            "output": [],
            **extra,
        }

    def _emit(self, kind: str, **fields: Any) -> SSE:
        self.events.append({"type": kind, "sequence_number": next(self._seq), **fields})
        return self

    def search(self, queries: list[str], results: list[dict[str, Any]]) -> SSE:
        self._emit("response.reasoning.started")
        self._emit("response.reasoning.search_queries", queries=queries, thought="looking it up")
        self._emit("response.reasoning.search_results", results=results)
        item = {"type": "search_results", "queries": queries, "results": results}
        self._emit("response.output_item.added", output_index=0, item=item)
        self._emit("response.output_item.done", output_index=0, item=item)
        return self._emit("response.reasoning.stopped")

    def function_call(
        self, name: str, arguments: dict[str, Any], call_id: str = "call_1", signature: str | None = None
    ) -> SSE:
        item: dict[str, Any] = {
            "type": "function_call",
            "id": f"fc_{call_id}",
            "call_id": call_id,
            "name": name,
            "arguments": json.dumps(arguments),
            "status": "completed",
        }
        if signature:
            item["thought_signature"] = signature
        self._emit("response.output_item.added", output_index=1, item=item)
        return self._emit("response.output_item.done", output_index=1, item=item)

    def groq_function_call(self, name: str, arguments: dict[str, Any], call_id: str = "call_1") -> SSE:
        """A function call as Groq streams it: an empty item, then argument deltas keyed by the item id."""
        encoded = json.dumps(arguments)
        item: dict[str, Any] = {
            "type": "function_call",
            "id": call_id,
            "call_id": call_id,
            "name": name,
            "arguments": "",
            "status": "in_progress",
        }
        self._emit("response.output_item.added", output_index=1, item=item)
        self._emit("response.function_call_arguments.delta", item_id=call_id, output_index=1, delta=encoded)
        self._emit("response.output_item.done", output_index=1, item={**item, "arguments": encoded})
        return self._emit("response.function_call_arguments.done", item_id=call_id, output_index=1, arguments=encoded)

    def text(self, *parts: str, item_id: str = "msg_1") -> SSE:
        for part in parts:
            self._emit("response.output_text.delta", item_id=item_id, output_index=2, content_index=0, delta=part)
        return self

    def item(self, item: dict[str, Any]) -> SSE:
        return self._emit("response.output_item.done", output_index=3, item=item)

    def completed(self, input_tokens: int = 100, output_tokens: int = 20, cost: float | None = 0.01) -> SSE:
        usage: dict[str, Any] = {
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "total_tokens": input_tokens + output_tokens,
            "input_tokens_details": {"cached_tokens": 0},
        }
        if cost is not None:
            usage["cost"] = {"currency": "USD", "total_cost": cost}
        return self._emit("response.completed", response=self._response("completed", usage=usage))

    def incomplete(self, reason: str) -> SSE:
        usage = {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}
        return self._emit(
            "response.incomplete",
            response=self._response("incomplete", incomplete_details={"reason": reason}, usage=usage),
        )

    def failed(self, code: str, message: str) -> SSE:
        return self._emit("response.failed", error={"code": code, "message": message, "type": code})

    def body(self) -> bytes:
        return "".join(f"event: {e['type']}\ndata: {json.dumps(e)}\n\n" for e in self.events).encode()


Responder = Callable[[httpx.Request], httpx.Response]


class FakePerplexity:
    """A scripted Agent API: each request gets the next response from the script and is recorded."""

    def __init__(
        self, script: Iterable[SSE | httpx.Response | Responder], fallback: Callable[[], SSE] | None = None
    ) -> None:
        self.script = list(script)
        self.fallback = fallback
        self.requests: list[dict[str, Any]] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(json.loads(request.content or b"{}"))
        if not self.script and self.fallback is None:
            raise AssertionError("FakePerplexity received more requests than scripted")
        step = self.script.pop(0) if self.script else self.fallback()  # type: ignore[misc]
        if isinstance(step, SSE):
            return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=step.body())
        if isinstance(step, httpx.Response):
            return step
        return step(request)

    def transport_factory(self) -> Callable[[], httpx.AsyncBaseTransport]:
        return lambda: httpx.MockTransport(self)


class FakeGroq(FakePerplexity):
    """A scripted Responses API on Groq's side; it scripts and records exactly as ``FakePerplexity`` does."""


def error_response(status: int, code: str, message: str) -> httpx.Response:
    return httpx.Response(status, json={"error": {"code": code, "message": message, "type": code}})
