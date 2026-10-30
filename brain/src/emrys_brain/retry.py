"""Model retry policy: the SDK's exponential backoff, widened to Perplexity's transient failures."""

from __future__ import annotations

from collections.abc import Callable

from strands import ModelRetryStrategy
from strands.types.exceptions import ModelThrottledException

from .models.perplexity import PerplexityTransientError


class TransientRetry(ModelRetryStrategy):
    """Retries throttling, 5xx, dropped connections and ``response.failed`` codes Perplexity marks transient.

    The SDK sleeps between attempts without streaming anything, so ``on_backoff`` tells the supervisor's
    watchdog that a silence of up to ``max_delay`` seconds is a backoff, not a hang.

    Args:
        max_attempts: Total model attempts before the error escapes to the supervisor.
        initial_delay: First backoff in seconds; doubles per attempt.
        max_delay: Backoff cap in seconds.
        on_backoff: Called with the exception each time a failed call is judged retryable.
    """

    def __init__(
        self,
        *,
        max_attempts: int = 6,
        initial_delay: int = 4,
        max_delay: int = 60,
        on_backoff: Callable[[Exception], None] | None = None,
    ) -> None:
        super().__init__(max_attempts=max_attempts, initial_delay=initial_delay, max_delay=max_delay)
        self.max_delay = max_delay
        self._on_backoff = on_backoff

    def is_retryable(self, exception: Exception) -> bool:
        """Throttling and typed transient Perplexity errors; bad requests and overflow are not retried here."""
        retryable = isinstance(exception, ModelThrottledException | PerplexityTransientError)
        if retryable and self._on_backoff is not None:
            self._on_backoff(exception)
        return retryable
