from __future__ import annotations

from rivet_brain.config import WatchdogSettings
from rivet_brain.supervisor import Watchdog

LIMITS = WatchdogSettings(first_chunk=10, idle=20, model_total=100, tool_default=30, soft_grace=1)


class Clock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


def _watchdog() -> tuple[Watchdog, Clock]:
    clock = Clock()
    return Watchdog(LIMITS, clock), clock


def test_first_chunk_deadline_ignores_bookkeeping_and_message_start() -> None:
    watchdog, clock = _watchdog()
    watchdog.model_started()
    clock.now = 9
    watchdog.activity("messageStart")
    watchdog.activity("event")
    assert watchdog.verdict() is None
    clock.now = 10.5
    assert watchdog.verdict() == "no response from the model within 10s"


def test_streaming_has_an_idle_and_a_total_deadline() -> None:
    watchdog, clock = _watchdog()
    watchdog.model_started()
    clock.now = 5
    watchdog.activity("response_created")
    clock.now = 24
    assert watchdog.verdict() is None
    clock.now = 25.5
    assert watchdog.verdict() == "no progress for 20s"
    for t in range(26, 101, 10):
        clock.now = t
        watchdog.activity("text")
    clock.now = 100.5
    watchdog.activity("text")
    assert watchdog.verdict() == "model call ran past 100s"


def test_tools_use_their_own_limit_and_progress_extends_it() -> None:
    watchdog, clock = _watchdog()
    watchdog.tool_started("t1", "telnyx_execute", 30)
    clock.now = 30.5
    assert watchdog.verdict() is None  # within the grace period
    clock.now = 25
    watchdog.tool_progress()
    clock.now = 45.5
    assert watchdog.verdict() is None
    clock.now = 46.5
    assert watchdog.verdict() == "tool telnyx_execute ran past its 30s limit"
    watchdog.tool_finished("t1")
    assert watchdog.verdict() is None  # finishing a tool counts as progress


def test_backoff_window_and_trip() -> None:
    watchdog, clock = _watchdog()
    watchdog.backoff(60)
    clock.now = 60.9
    assert watchdog.verdict() is None
    clock.now = 61.5
    assert watchdog.verdict() == "retry backoff did not resume the model call"
    watchdog.reset()
    watchdog.trip("the Telnyx MCP connection failed")
    watchdog.trip("second reason is ignored")
    assert watchdog.verdict() == watchdog.tripped == "the Telnyx MCP connection failed"
