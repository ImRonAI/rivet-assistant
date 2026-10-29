"""Groq's OpenAI-compatible Responses API, through Strands' ``OpenAIResponsesModel``.

Groq does not support ``previous_response_id``, ``store``, ``truncation`` or ``include``
(https://console.groq.com/docs/responses-api), so the model runs stateless: the session snapshots and the
context manager own the conversation.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any, override

import httpx
from strands.models.openai_responses import OpenAIResponsesModel

GROQ_BASE_URL = "https://api.groq.com/openai/v1"


class GroqModel(OpenAIResponsesModel):
    """``OpenAIResponsesModel`` on Groq.

    Args:
        api_key: The Groq API key.
        model_id: The Groq model id.
        base_url: Groq's OpenAI-compatible base URL.
        context_window_limit: The model's context window, for the context manager's utilization math.
        timeout: Per-request timeout in seconds.
        transport_factory: Test seam: a fresh ``httpx`` transport per request. The parent opens and closes an
            ``AsyncOpenAI`` client per request, so a shared ``http_client`` would be closed after the first call.
    """

    def __init__(
        self,
        *,
        api_key: str,
        model_id: str,
        base_url: str = GROQ_BASE_URL,
        context_window_limit: int | None = None,
        timeout: float = 180.0,
        transport_factory: Callable[[], httpx.AsyncBaseTransport] | None = None,
    ) -> None:
        # max_retries=0: retries belong to the agent's retry strategy, which tells the watchdog about backoffs.
        client_args = {"api_key": api_key, "base_url": base_url, "timeout": timeout, "max_retries": 0}
        super().__init__(
            client_args=client_args, model_id=model_id, stateful=False, context_window_limit=context_window_limit
        )
        self._transport_factory = transport_factory

    @override
    def _resolve_client_args(self) -> dict[str, Any]:
        args = dict(super()._resolve_client_args())
        if self._transport_factory is not None:
            args["http_client"] = httpx.AsyncClient(transport=self._transport_factory())
        return args
