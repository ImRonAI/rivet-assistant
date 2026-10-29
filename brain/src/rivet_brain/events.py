"""The agent's callback handler: Rivet UI events for the phone, plus heartbeats for the supervisor."""

from __future__ import annotations

import logging
from collections.abc import Callable
from typing import Any

from .search import KINDS

logger = logging.getLogger(__name__)

UiSink = Callable[[dict[str, Any]], None]

# Perplexity server-side tool output; its text reaches the model inside Perplexity's own loop.
_NATIVE_SOURCE_KINDS = frozenset({"search_results", "people_search_results", "fetch_url_results"})
_NATIVE_REPORT_KINDS = frozenset({"image_search_results", "finance_results", "sandbox_results", "share_file"})
_UNTRUSTED_KINDS = (
    _NATIVE_SOURCE_KINDS | _NATIVE_REPORT_KINDS | {"reasoning.search_results", "reasoning.fetch_url_results"}
)


class RivetEventBridge:
    """Translates SDK stream events into compact UI events.

    UI events (``type``): ``text``, ``thinking``, ``searching``, ``sources``, ``report``, ``tool``,
    ``tool_done`` (one per tool-result message), ``search.results`` {tool_id, results} (after ``tool_done``, for
    each successful ``search_*`` tool result carrying a search envelope), ``backoff``, ``usage``, ``notice``. The
    phone maps them to Rivet's moves.

    Args:
        sink: Receives each UI event; must not block. Errors it raises are logged, never propagated.
        on_activity: Called with a short label on every stream event (the supervisor's heartbeat).
        on_untrusted: Called when Perplexity's native tools bring third-party content into the turn.
    """

    def __init__(
        self,
        sink: UiSink | None = None,
        *,
        on_activity: Callable[[str], None] | None = None,
        on_untrusted: Callable[[], None] | None = None,
    ) -> None:
        self.sink = sink
        self.on_activity = on_activity
        self.on_untrusted = on_untrusted
        self._announced_tools: dict[str, str] = {}

    def __call__(self, **kwargs: Any) -> None:
        """Callback-handler entry point."""
        label, event = self._translate(kwargs)
        if self.on_activity is not None:
            self.on_activity(label)
        if event is None:
            return
        self._send(event)
        if event["type"] == "tool_done":
            for search in self._search_results(kwargs["message"]):
                self._send(search)

    def _send(self, event: dict[str, Any]) -> None:
        if self.sink is None:
            return
        try:
            self.sink(event)
        except Exception:
            logger.exception("event_type=<%s> | ui sink failed", event.get("type"))

    def _translate(self, kwargs: dict[str, Any]) -> tuple[str, dict[str, Any] | None]:
        if "data" in kwargs:
            return "text", {"type": "text", "delta": kwargs["data"]}
        if "reasoningText" in kwargs:
            return "reasoning", {"type": "thinking", "delta": kwargs["reasoningText"]}
        if "current_tool_use" in kwargs:
            return "tool_input", self._tool_started(kwargs["current_tool_use"])
        if "message" in kwargs:
            # Tool results reach callback handlers only as the user-role tool-result message.
            return "message", self._tools_finished(kwargs["message"])
        if "event_loop_throttled_delay" in kwargs:
            return "backoff", {"type": "backoff", "delay": kwargs["event_loop_throttled_delay"]}
        chunk = kwargs.get("event")
        if isinstance(chunk, dict) and "rivet" in chunk:
            return self._rivet(chunk["rivet"])
        if isinstance(chunk, dict):
            return next(iter(chunk), "chunk"), None
        return "event", None

    def _tool_started(self, tool_use: Any) -> dict[str, Any] | None:
        tool_id = tool_use.get("toolUseId") if isinstance(tool_use, dict) else None
        if not tool_id or tool_id in self._announced_tools:
            return None
        self._announced_tools[tool_id] = str(tool_use.get("name") or "")
        return {"type": "tool", "id": tool_id, "name": tool_use.get("name")}

    @staticmethod
    def _tools_finished(message: Any) -> dict[str, Any] | None:
        content = message.get("content") if isinstance(message, dict) else None
        results = [block["toolResult"] for block in content or [] if isinstance(block, dict) and "toolResult" in block]
        if not results:
            return None
        return {"type": "tool_done", "tools": [{"id": r.get("toolUseId"), "status": r.get("status")} for r in results]}

    def _search_results(self, message: dict[str, Any]) -> list[dict[str, Any]]:
        events = []
        for block in message.get("content") or []:
            result = block.get("toolResult") if isinstance(block, dict) else None
            if not isinstance(result, dict) or result.get("status") != "success":
                continue
            tool_id = result.get("toolUseId")
            if not self._announced_tools.get(str(tool_id), "").startswith("search_"):
                continue
            content = result.get("content") or [{}]
            envelope = content[0].get("json") if isinstance(content[0], dict) else None
            if isinstance(envelope, dict) and envelope.get("kind") in KINDS:
                events.append({"type": "search.results", "tool_id": tool_id, "results": envelope})
        return events

    def _rivet(self, payload: dict[str, Any]) -> tuple[str, dict[str, Any] | None]:
        kind = str(payload.get("type", "activity"))
        if kind in _UNTRUSTED_KINDS and self.on_untrusted is not None:
            self.on_untrusted()
        fields = {k: v for k, v in payload.items() if k != "type"}
        if kind == "reasoning.search_queries":
            return kind, {"type": "searching", "queries": fields.get("queries") or []}
        if kind in _NATIVE_SOURCE_KINDS:
            return kind, {"type": "sources", "kind": kind, **fields}
        if kind in _NATIVE_REPORT_KINDS:
            return kind, {"type": "report", "kind": kind, **fields}
        if kind == "usage":
            return kind, {"type": "usage", **fields}
        if kind == "incomplete":
            return kind, {"type": "notice", "kind": "incomplete", **fields}
        return kind, None
