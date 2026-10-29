"""Keeps one session's agents alive across hangs, context overflow, outages and restarts.

A ``SessionSupervisor`` owns one session id. Each agent in it (the conversation, and one per GoalLoop task)
runs in a ``Lane`` holding the lane's fence and its current generation. When a generation hangs, overflows
past what the context manager can reduce, loses its Telnyx connection, or crashes, the lane stops it,
fences off its storage, builds the next generation with the same session id (restoring the conversation,
state and pending approvals), repairs any tool call that never reported, and continues the turn without
re-sending the user's prompt.

Side effects are journalled write-ahead in ``agent.state``: a non-read tool is recorded as started (and the
snapshot saved) before it runs, and as completed with its result after. Recovery therefore knows, per tool
call, whether it completed, may have run, or never started. A fenced generation cannot even record a start,
so an abandoned agent cannot begin a new side effect.
"""

from __future__ import annotations

import asyncio
import contextlib
import copy
import fcntl
import json
import logging
import re
import threading
import time
import uuid
from collections import deque
from collections.abc import Callable, Coroutine, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import IO, Any, Literal, cast

import openai
from strands import Agent
from strands.agent.agent_result import AgentResult
from strands.hooks import (
    AfterInvocationEvent,
    AfterModelCallEvent,
    AfterToolCallEvent,
    BeforeModelCallEvent,
    BeforeToolCallEvent,
    HookOrder,
)
from strands.storage import Storage
from strands.types.agent import Limits
from strands.types.exceptions import (
    ContextWindowOverflowException,
    MaxTokensReachedException,
    MCPClientInitializationError,
    SessionException,
    SnapshotException,
)
from strands.vended_plugins.goal import GoalLoop

from .alerts import because_task, because_you_asked
from .approvals import TELNYX_PREFIX, TURN_KEY, RivetApprovals
from .config import BrainSettings, WatchdogSettings
from .events import UiSink
from .factory import Generation, Wiring, apply_system_prompt, build_agent

logger = logging.getLogger(__name__)

STATUS_KEY = "rivet.status"
ACTIONS_KEY = "rivet.actions"
GOAL_KEY = "rivet.goal"
DEFAULT_LANE = "default"
JOURNAL_LIMIT = 100
COMPACT_KEEP = 12
SYNTHETIC_CANCEL = [{"text": "Cancelled by user"}]
DEACTIVATED_INTERRUPTS: dict[str, Any] = {"interrupts": {}, "context": {}, "activated": False}
CONTINUE_PROMPT = (
    "<system-reminder>Your previous reply hit the output limit. Continue exactly where it stopped.</system-reminder>"
)
NOT_STARTED = "This action did not run: Rivet restarted before starting it. Retry it if it is still wanted."
MAY_HAVE_RUN = (
    "Rivet restarted while this action was running. It may or may not have taken effect; check the current "
    "state before retrying."
)
_SESSION_ID = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
_NO_GOAL_RESUME = frozenset(
    {
        "interrupt",
        "cancelled",
        "limit_turns",
        "limit_total_tokens",
        "limit_output_tokens",
        "guardrail_intervened",
        "content_filtered",
    }
)
# Bridge labels that are not model output: loop bookkeeping, backoff notices, finished messages, and the
# messageStart the model emits as soon as response headers arrive (before Perplexity sends any event).
_NON_STREAM_LABELS = frozenset({"event", "backoff", "message", "messageStart"})
_MCP_BROKEN = re.compile(
    r"session is not running|MCP server was closed|ClosedResourceError|BrokenResourceError|"
    r"Connection (?:closed|refused|reset)|ConnectError|RemoteProtocolError|ReadError|401 Unauthorized",
    re.IGNORECASE,
)
# Configuration or request errors: restarting cannot fix them.
_FATAL_ERRORS = (openai.AuthenticationError, openai.PermissionDeniedError, openai.BadRequestError, openai.NotFoundError)

TurnStatus = Literal["completed", "awaiting_approval", "failed"]


class SessionLockedError(RuntimeError):
    """Another process already supervises this session."""


class NoPendingApprovalError(RuntimeError):
    """``respond`` was called while nothing awaits approval."""


class BuildTimeoutError(RuntimeError):
    """Building a generation took longer than the build deadline."""


class _Hang(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class _GiveUp(Exception):
    """The restart circuit breaker tripped."""


@dataclass
class TurnOutcome:
    """How a turn ended, as reported to the phone."""

    turn_id: str
    status: TurnStatus
    text: str = ""
    stop_reason: str | None = None
    approvals: list[dict[str, Any]] = field(default_factory=list)
    restarts: int = 0
    error: str | None = None
    lane: str = DEFAULT_LANE
    goal: str | None = None

    def to_event(self) -> dict[str, Any]:
        """The ``turn.*`` event for this outcome."""
        kind = {"completed": "turn.completed", "awaiting_approval": "approval.request", "failed": "turn.failed"}
        event: dict[str, Any] = {
            "type": kind[self.status],
            "turn_id": self.turn_id,
            "lane": self.lane,
            "restarts": self.restarts,
        }
        if self.text:
            event["text"] = self.text
        if self.stop_reason:
            event["stop_reason"] = self.stop_reason
        if self.approvals:
            event["approvals"] = self.approvals
        if self.error:
            event["error"] = self.error
        if self.goal:
            event["goal"] = self.goal
        return event


class Watchdog:
    """Phase deadlines for one lane's running invocation. Thread-safe (MCP progress arrives off-loop)."""

    def __init__(self, limits: WatchdogSettings, clock: Callable[[], float] = time.monotonic) -> None:
        self.limits = limits
        self._clock = clock
        self._lock = threading.Lock()
        self.reset()

    def reset(self) -> None:
        """Start watching a new invocation."""
        with self._lock:
            now = self._clock()
            self._phase = "working"
            self._last = now
            self._model_started = now
            self._backoff_until = now
            self._tools: dict[str, list[Any]] = {}
            self._tripped: str | None = None

    def activity(self, label: str = "") -> None:
        """Any stream event; the first model output moves the call from waiting to streaming."""
        with self._lock:
            self._last = self._clock()
            if self._phase == "model_first" and label not in _NON_STREAM_LABELS:
                self._phase = "model_stream"

    def model_started(self) -> None:
        """A model call began."""
        with self._lock:
            now = self._clock()
            self._phase, self._model_started, self._last = "model_first", now, now

    def model_finished(self) -> None:
        """A model call returned or raised."""
        with self._lock:
            self._phase, self._last = "working", self._clock()

    def backoff(self, seconds: float) -> None:
        """The retry strategy is sleeping before the next attempt."""
        with self._lock:
            now = self._clock()
            self._phase, self._backoff_until, self._last = "backoff", now + seconds, now

    def tool_started(self, tool_id: str, name: str, limit: float) -> None:
        """A tool began running."""
        with self._lock:
            now = self._clock()
            self._tools[tool_id] = [name, now, limit, now]
            self._last = now

    def tool_progress(self) -> None:
        """A running tool reported progress (MCP progress notification)."""
        with self._lock:
            now = self._clock()
            for entry in self._tools.values():
                entry[3] = now
            self._last = now

    def tool_finished(self, tool_id: str) -> None:
        """A tool returned."""
        with self._lock:
            self._tools.pop(tool_id, None)
            self._last = self._clock()

    def trip(self, reason: str) -> None:
        """Force a restart at the next check (a broken Telnyx connection, for example)."""
        with self._lock:
            self._tripped = self._tripped or reason

    @property
    def tripped(self) -> str | None:
        """The reason passed to ``trip``, if any."""
        with self._lock:
            return self._tripped

    def verdict(self) -> str | None:
        """Why the invocation counts as hung, or None while it is healthy."""
        limits = self.limits
        with self._lock:
            now = self._clock()
            if self._tripped:
                return self._tripped
            for name, started, limit, progressed in self._tools.values():
                if now > max(started + limit, progressed + limits.idle) + limits.soft_grace:
                    return f"tool {name} ran past its {limit:.0f}s limit"
            if self._tools:
                return None
            if self._phase == "backoff":
                overran = now > self._backoff_until + limits.soft_grace
                return "retry backoff did not resume the model call" if overran else None
            if self._phase == "model_first" and now - self._model_started > limits.first_chunk:
                return f"no response from the model within {limits.first_chunk:.0f}s"
            if self._phase == "model_stream" and now - self._model_started > limits.model_total:
                return f"model call ran past {limits.model_total:.0f}s"
            if now - self._last > limits.idle:
                return f"no progress for {limits.idle:.0f}s"
            return None


@dataclass
class GoalBudget:
    """A task's GoalLoop budget, mirrored in ``agent.state`` so a rebuilt task resumes with what is left."""

    goal: str
    max_attempts: int
    deadline: float
    attempts_used: int = 0

    @classmethod
    def load(cls, state: Any) -> GoalBudget | None:
        """The budget stored in an agent's state, if any."""
        raw = state.get(GOAL_KEY)
        return cls(**raw) if isinstance(raw, dict) else None

    def save(self, state: Any) -> None:
        """Store the budget in an agent's state."""
        state.set(
            GOAL_KEY,
            {
                "goal": self.goal,
                "max_attempts": self.max_attempts,
                "deadline": self.deadline,
                "attempts_used": self.attempts_used,
            },
        )


@dataclass
class _Plan:
    kind: Literal["continue", "awaiting", "done"]
    input: Any = None
    pending: list[dict[str, Any]] = field(default_factory=list)
    text: str = ""


class Lane:
    """One agent id within the session: its fence, current generation, watchdog and restart history."""

    def __init__(
        self, agent_id: str, limits: WatchdogSettings, tools: Sequence[Any], goal: GoalBudget | None = None
    ) -> None:
        from .storage import FenceHolder

        self.agent_id = agent_id
        self.tools = list(tools)
        self.goal = goal
        self.fence = FenceHolder()
        self.watchdog = Watchdog(limits)
        self.lock = asyncio.Lock()
        self.gen: Generation | None = None
        self.generation_used = False
        self.restarts: deque[float] = deque()
        self.goal_loop: GoalLoop | None = None
        self.expiry: asyncio.TimerHandle | None = None
        self.zombies: list[Any] = []


BuildFn = Callable[..., Generation]


class SessionSupervisor:
    """Runs turns for one session and keeps its agents alive.

    Args:
        settings: Brain settings (deadlines, restart budget, approval timeout).
        session_id: Stable session id (``[A-Za-z0-9._-]``).
        backend: The real storage backend for snapshots, memory and the context stash.
        approvals: The approval policy, shared by every generation.
        tools: Local tools for the conversation lane (device tools, task tools).
        sink: Receives UI and turn events for the phone.
        build: Generation factory; defaults to ``build_agent``.
        lock_dir: Directory for the per-session OS lock; defaults to ``settings.session_dir/locks``.
    """

    def __init__(
        self,
        settings: BrainSettings,
        session_id: str,
        *,
        backend: Storage,
        approvals: RivetApprovals | None = None,
        tools: Sequence[Any] = (),
        sink: UiSink | None = None,
        build: BuildFn = build_agent,
        lock_dir: str | None = None,
    ) -> None:
        if not _SESSION_ID.match(session_id):
            raise ValueError(f"invalid session id '{session_id}'")
        self.settings = settings
        self.session_id = session_id
        self.backend = backend
        self.approvals = approvals or RivetApprovals()
        self.sink = sink
        self._tools = list(tools)
        self._build_fn = build
        self._lock_dir = Path(lock_dir or Path(settings.session_dir) / "locks")
        self._lock_file: IO[str] | None = None
        self._lanes: dict[str, Lane] = {}
        self._background: set[asyncio.Task[Any]] = set()

    # -- lifecycle ------------------------------------------------------------------------------------------

    async def start(self) -> TurnOutcome | None:
        """Take the session lock, build the conversation agent, and finish any turn a crash interrupted."""
        self._acquire_lock()
        lane = self._lane(DEFAULT_LANE, self._tools)
        async with lane.lock:
            try:
                await self._open(lane)
            except _GiveUp as error:
                logger.error("session_id=<%s> | could not start: %s", self.session_id, error)
                self._emit({"type": "turn.failed", "lane": DEFAULT_LANE, "error": str(error)})
                return None
            return await self._recover(lane)

    async def close(self) -> None:
        """Flush memory, release every agent, and drop the session lock."""
        for task in list(self._background):
            task.cancel()
        for lane in self._lanes.values():
            if lane.expiry is not None:
                lane.expiry.cancel()
            gen = lane.gen
            if gen is None:
                continue
            with contextlib.suppress(Exception):
                await asyncio.wait_for(gen.agent.shutdown_async(), timeout=self.settings.watchdog.cleanup)
            await self._dispose(lane, gen)
            lane.gen = None
        if self._lock_file is not None:
            fcntl.flock(self._lock_file, fcntl.LOCK_UN)
            self._lock_file.close()
            self._lock_file = None

    # -- public turn API ------------------------------------------------------------------------------------

    async def send(self, prompt: Any, *, turn_id: str | None = None) -> TurnOutcome:
        """Run one user turn on the conversation lane.

        A message that arrives while approvals are pending denies them first (the old turn finishes), then
        runs as a new turn.
        """
        lane = self._lane(DEFAULT_LANE, self._tools)
        caller_id = turn_id is not None
        turn_id = turn_id or uuid.uuid4().hex
        async with lane.lock:
            lane.restarts.clear()
            try:
                gen = await self._ensure_built(lane)
            except _GiveUp as error:
                return self._failed(lane, turn_id, str(error), 0)
            status = self._status(gen.agent)
            if caller_id and status.get("turn_id") == turn_id:
                # A re-sent copy of the turn this lane already ran (or recovered after a crash).
                logger.info("turn_id=<%s> | duplicate turn ignored", turn_id)
                return TurnOutcome(turn_id, "completed", stop_reason="duplicate", lane=lane.agent_id)
            if status.get("status") == "awaiting_approval":
                await self._answer(lane, {}, default={"approved": False, "reason": "superseded by a new message"})
            elif status.get("status") == "running":
                await self._seal(lane)
            gen = await self._ensure_built(lane)
            gen.agent.state.set(TURN_KEY, turn_id)
            self._set_status(gen.agent, _running(turn_id, because_you_asked(prompt)))
            self._emit({"type": "turn.started", "turn_id": turn_id, "lane": lane.agent_id})
            return await self._drive(lane, turn_id, prompt)

    async def respond(self, answers: Mapping[str, Any], *, lane_id: str = DEFAULT_LANE) -> TurnOutcome:
        """Answer pending approvals by interrupt id; unanswered ones are denied. Continues the turn."""
        lane = self._lanes.get(lane_id)
        if lane is None:
            raise NoPendingApprovalError(f"no lane '{lane_id}'")
        async with lane.lock:
            return await self._answer(lane, answers)

    @property
    def lanes(self) -> list[str]:
        """Agent ids with a lane in this session (``default`` and ``task-<id>``)."""
        return list(self._lanes)

    def pending_approvals(self, lane_id: str = DEFAULT_LANE) -> list[dict[str, Any]]:
        """Approval sheets waiting for an answer (re-sent to the phone when it reconnects)."""
        lane = self._lanes.get(lane_id)
        if lane is None or lane.gen is None:
            return []
        status = self._status(lane.gen.agent)
        if status.get("status") != "awaiting_approval":
            return []
        return [_sheet(p, status.get("because")) for p in status.get("pending", [])]

    async def run_task(
        self,
        goal: str,
        *,
        task_id: str | None = None,
        max_attempts: int = 5,
        timeout: float = 1800.0,
        tools: Sequence[Any] | None = None,
        reason: str | None = None,
    ) -> TurnOutcome:
        """Run a goal-driven task on its own lane, judged by GoalLoop on the aux model.

        ``reason`` is why the task exists, in plain words; approvals it raises show it (else the goal).
        """
        task_id = task_id or uuid.uuid4().hex[:12]
        budget = GoalBudget(goal, max_attempts, time.time() + timeout)
        lane = self._lane(f"task-{task_id}", self._tools if tools is None else tools, goal=budget)
        async with lane.lock:
            try:
                gen = await self._ensure_built(lane)
            except _GiveUp as error:
                return self._failed(lane, task_id, str(error), 0)
            gen.agent.state.set(TURN_KEY, task_id)
            self._set_status(gen.agent, _running(task_id, reason or because_task(goal)))
            self._emit({"type": "turn.started", "turn_id": task_id, "lane": lane.agent_id, "goal": goal})
            return await self._drive(lane, task_id, f"Task: {goal}")

    # -- turn driver ----------------------------------------------------------------------------------------

    async def _drive(self, lane: Lane, turn_id: str, prompt: Any) -> TurnOutcome:
        restarts = compactions = continuations = 0
        next_input = prompt
        while True:
            try:
                gen = await self._ensure_built(lane)
            except _GiveUp as error:
                return self._failed(lane, turn_id, str(error), restarts)
            try:
                result = await self._run_watched(lane, gen, next_input, turn_id)
            except _Hang as hang:
                reason = hang.reason
            except ContextWindowOverflowException:
                compactions += 1
                if compactions > 2 or not await self._compact(lane, compactions):
                    return await self._fail(
                        lane, turn_id, "the conversation is too large even after compacting", restarts
                    )
                next_input = None
                continue
            except MaxTokensReachedException:
                continuations += 1
                if continuations > 2:
                    return await self._fail(lane, turn_id, "the reply kept hitting the output limit", restarts)
                last = gen.agent.messages[-1] if gen.agent.messages else None
                next_input = CONTINUE_PROMPT if last and last["role"] == "assistant" else None
                continue
            except _FATAL_ERRORS as error:
                return await self._fail(lane, turn_id, _describe(error), restarts)
            except Exception as error:
                # Retries exhausted (throttling, 5xx), a dropped MCP connection, or a crash in the loop.
                logger.warning("lane=<%s>, error=<%s> | invocation failed", lane.agent_id, _describe(error))
                reason = _describe(error)
            else:
                return await self._finish(lane, turn_id, result, restarts)

            restarts += 1
            try:
                await self._restart(lane, reason)
            except _GiveUp as error:
                return self._failed(lane, turn_id, str(error), restarts)
            plan = await self._plan_resume(
                lane,
                notice=f"Rivet restarted after: {reason}. Continue the task; "
                "verify the state of anything that may have changed before retrying.",
            )
            if plan.kind == "done":
                return await self._finish_from_history(lane, turn_id, plan.text, restarts)
            if plan.kind == "awaiting":
                return await self._await_approval(lane, turn_id, plan.pending, restarts)
            next_input = plan.input

    async def _run_watched(self, lane: Lane, gen: Generation, prompt: Any, turn_id: str) -> AgentResult:
        lane.watchdog.reset()
        limits = cast(Limits, dict(self.settings.turn_limits))
        task = asyncio.create_task(_consume(gen.agent, prompt, turn_id, limits))
        while True:
            done, _ = await asyncio.wait({task}, timeout=self.settings.watchdog.poll)
            if done:
                tripped = lane.watchdog.tripped
                if tripped is not None:
                    # The invocation stopped itself (cooperative cancel) after a fatal tool failure.
                    with contextlib.suppress(BaseException):
                        task.result()
                    await self._retire(lane, gen)
                    raise _Hang(tripped)
                return task.result()
            reason = lane.watchdog.verdict()
            if reason is not None:
                logger.warning(
                    "lane=<%s>, generation=<%d>, reason=<%s> | agent hung", lane.agent_id, gen.number, reason
                )
                await self._stop(lane, gen, task)
                raise _Hang(reason)

    async def _finish(self, lane: Lane, turn_id: str, result: AgentResult, restarts: int) -> TurnOutcome:
        gen = _current(lane)
        if result.stop_reason == "interrupt":
            pending = [{"id": i.id, "name": i.name, "reason": i.reason} for i in result.interrupts or []]
            return await self._await_approval(lane, turn_id, pending, restarts)
        return await self._finish_from_history(
            lane, turn_id, str(result).strip(), restarts, stop_reason=result.stop_reason, gen=gen
        )

    async def _finish_from_history(
        self,
        lane: Lane,
        turn_id: str,
        text: str,
        restarts: int,
        *,
        stop_reason: str | None = "end_turn",
        gen: Generation | None = None,
    ) -> TurnOutcome:
        gen = gen or _current(lane)
        self._set_status(gen.agent, {"turn_id": turn_id, "status": "done"})
        await self._save(gen)
        goal = None
        if lane.goal_loop is not None:
            last = lane.goal_loop.last_result(gen.agent)
            goal = last.stop_reason if last is not None else None
        outcome = TurnOutcome(turn_id, "completed", text, stop_reason, restarts=restarts, lane=lane.agent_id, goal=goal)
        self._emit(outcome.to_event())
        return outcome

    async def _await_approval(
        self, lane: Lane, turn_id: str, pending: list[dict[str, Any]], restarts: int
    ) -> TurnOutcome:
        gen = _current(lane)
        expires_at = time.time() + self.settings.approval_timeout
        because = self._status(gen.agent).get("because")
        status: dict[str, Any] = {
            "turn_id": turn_id,
            "status": "awaiting_approval",
            "pending": pending,
            "expires_at": expires_at,
        }
        if isinstance(because, str):
            status["because"] = because
        self._set_status(gen.agent, status)
        await self._save(gen)
        self._schedule_expiry(lane, turn_id, expires_at)
        outcome = TurnOutcome(
            turn_id,
            "awaiting_approval",
            stop_reason="interrupt",
            approvals=[_sheet(p, because) for p in pending],
            restarts=restarts,
            lane=lane.agent_id,
        )
        self._emit({**outcome.to_event(), "expires_at": expires_at})
        return outcome

    async def _answer(self, lane: Lane, answers: Mapping[str, Any], *, default: Any = None) -> TurnOutcome:
        gen = await self._ensure_built(lane)
        status = self._status(gen.agent)
        if status.get("status") != "awaiting_approval":
            raise NoPendingApprovalError("nothing is waiting for approval")
        fallback = default if default is not None else {"approved": False, "reason": "no answer"}
        resolved = {p["id"]: answers.get(p["id"], fallback) for p in status.get("pending", [])}
        if lane.expiry is not None:
            lane.expiry.cancel()
            lane.expiry = None
        # Answers are persisted before anything runs, so a crash mid-resume replays the same answers.
        self._set_status(gen.agent, {**status, "status": "running", "answers": resolved})
        await self._save(gen)
        return await self._drive(lane, status["turn_id"], _responses(resolved))

    async def _fail(self, lane: Lane, turn_id: str, error: str, restarts: int) -> TurnOutcome:
        if lane.gen is not None:
            with contextlib.suppress(Exception):
                await self._seal(lane, reply=f"I couldn't finish that: {error}")
        return self._failed(lane, turn_id, error, restarts)

    def _failed(self, lane: Lane, turn_id: str, error: str, restarts: int) -> TurnOutcome:
        outcome = TurnOutcome(turn_id, "failed", error=error, restarts=restarts, lane=lane.agent_id)
        self._emit(outcome.to_event())
        return outcome

    # -- approvals timing -----------------------------------------------------------------------------------

    def _schedule_expiry(self, lane: Lane, turn_id: str, expires_at: float) -> None:
        if lane.expiry is not None:
            lane.expiry.cancel()
        delay = max(0.0, expires_at - time.time())
        loop = asyncio.get_running_loop()
        lane.expiry = loop.call_later(delay, lambda: self._spawn(self._expire(lane, turn_id)))

    async def _expire(self, lane: Lane, turn_id: str) -> None:
        async with lane.lock:
            gen = lane.gen
            if gen is None:
                return
            status = self._status(gen.agent)
            if status.get("status") != "awaiting_approval" or status.get("turn_id") != turn_id:
                return
            self._emit({"type": "approval.expired", "turn_id": turn_id, "lane": lane.agent_id})
            await self._answer(lane, {}, default={"approved": False, "reason": "timed out"})

    # -- stopping and rebuilding ----------------------------------------------------------------------------

    async def _stop(self, lane: Lane, gen: Generation, task: asyncio.Task[AgentResult]) -> None:
        """Cancel cooperatively, then hard, then abandon; fence and release the generation either way."""
        limits = self.settings.watchdog
        gen.agent.cancel()
        done, _ = await asyncio.wait({task}, timeout=limits.soft_grace)
        if not done:
            task.cancel()
            done, _ = await asyncio.wait({task}, timeout=limits.hard_grace)
        if done:
            with contextlib.suppress(BaseException):
                task.result()
        else:
            logger.error(
                "lane=<%s>, generation=<%d> | agent ignored cancellation; abandoning it", lane.agent_id, gen.number
            )
            lane.zombies.append(task)
        await self._retire(lane, gen)

    async def _retire(self, lane: Lane, gen: Generation) -> None:
        """Fence the generation's storage, wait out its in-flight writes, and release its resources."""
        if lane.fence.generation == gen.number:
            lane.fence.advance()
            lane.generation_used = False
        await lane.fence.drain(timeout=self.settings.watchdog.drain)
        if lane.gen is gen:
            lane.gen = None
        await self._dispose(lane, gen)

    async def _dispose(self, lane: Lane, gen: Generation) -> None:
        def cleanup() -> None:
            try:
                gen.agent.cleanup()
            except Exception:
                logger.exception("lane=<%s>, generation=<%d> | cleanup failed", lane.agent_id, gen.number)

        future = asyncio.ensure_future(asyncio.to_thread(cleanup))
        done, _ = await asyncio.wait({future}, timeout=self.settings.watchdog.cleanup)
        if not done:
            # Keep the agent referenced: letting it be collected on the loop would block it in MCP teardown.
            lane.zombies.append((gen, future))
        lane.zombies = [z for z in lane.zombies if not _finished(z)]

    async def _restart(self, lane: Lane, reason: str) -> None:
        if lane.gen is not None:
            await self._retire(lane, lane.gen)
        delay = self._breaker(lane, reason)
        self._emit({"type": "restart", "lane": lane.agent_id, "reason": reason})
        if delay:
            await asyncio.sleep(delay)
        await self._ensure_built(lane)

    def _breaker(self, lane: Lane, reason: str) -> float:
        now = time.monotonic()
        lane.restarts.append(now)
        while lane.restarts and now - lane.restarts[0] > self.settings.restart_window:
            lane.restarts.popleft()
        count = len(lane.restarts)
        if count > self.settings.max_restarts:
            raise _GiveUp(
                f"gave up after {count - 1} restarts in {self.settings.restart_window:.0f}s; last error: {reason}"
            )
        return 0.0 if count == 1 else min(30.0, self.settings.restart_backoff * 2 ** (count - 2))

    async def _ensure_built(self, lane: Lane) -> Generation:
        while lane.gen is None:
            try:
                await self._build(lane)
            except Exception as error:
                delay = self._breaker(lane, f"could not start the agent: {_describe(error)}")
                if delay:
                    await asyncio.sleep(delay)
        return lane.gen

    async def _build(self, lane: Lane) -> Generation:
        if lane.generation_used:
            lane.fence.advance()
        lane.generation_used = True
        lane.watchdog.reset()
        box: list[Generation] = []
        future = asyncio.ensure_future(
            asyncio.to_thread(
                self._build_fn,
                self.settings,
                session_id=self.session_id,
                fence=lane.fence,
                backend=self.backend,
                approvals=self.approvals,
                wiring=self._wiring(lane, box),
                tools=lane.tools,
                agent_id=lane.agent_id,
            )
        )
        done, _ = await asyncio.wait({future}, timeout=self.settings.watchdog.build)
        if not done:
            lane.fence.advance()
            lane.generation_used = False
            future.add_done_callback(lambda f: self._spawn(self._dispose_late(lane, f)))
            raise BuildTimeoutError(f"building the agent took longer than {self.settings.watchdog.build:.0f}s")
        gen = future.result()
        box.append(gen)
        self._attach_goal(lane, gen)
        lane.gen = gen
        # An immutable checkpoint of exactly what was restored, before recovery edits anything.
        if gen.agent.messages:
            await gen.session_manager.save_snapshot(gen.agent, is_latest=False)
        return gen

    async def _dispose_late(self, lane: Lane, future: asyncio.Future[Generation]) -> None:
        if not future.cancelled() and future.exception() is None:
            await self._dispose(lane, future.result())

    def _attach_goal(self, lane: Lane, gen: Generation) -> None:
        budget = GoalBudget.load(gen.agent.state) or lane.goal
        lane.goal_loop = None
        if budget is None:
            return
        budget.save(gen.agent.state)
        remaining = budget.max_attempts - budget.attempts_used
        seconds = budget.deadline - time.time()
        if remaining >= 1 and seconds > 0:
            lane.goal_loop = GoalLoop(budget.goal, max_attempts=remaining, timeout=seconds)
            lane.goal_loop.init_agent(gen.agent)

    async def _open(self, lane: Lane) -> Generation:
        """First build of a lane; a corrupt latest snapshot falls back to the newest good checkpoint."""
        try:
            return await self._build(lane)
        except Exception as error:
            logger.warning("lane=<%s>, error=<%s> | first build failed", lane.agent_id, _describe(error))
            if not await self._quarantine_latest(lane):
                self._breaker(lane, _describe(error))
                return await self._ensure_built(lane)
        gen = await self._ensure_built(lane)
        for snapshot_id in reversed(await gen.session_manager.list_snapshot_ids(gen.agent)):
            try:
                if await gen.session_manager.restore_snapshot(gen.agent, snapshot_id=snapshot_id):
                    apply_system_prompt(gen.agent, gen.telnyx)
                    await self._save(gen)
                    logger.warning("lane=<%s>, snapshot_id=<%s> | restored from checkpoint", lane.agent_id, snapshot_id)
                    break
            except (SnapshotException, SessionException, ValueError, KeyError):
                logger.exception("lane=<%s>, snapshot_id=<%s> | checkpoint unusable", lane.agent_id, snapshot_id)
        return gen

    async def _quarantine_latest(self, lane: Lane) -> bool:
        key = latest_snapshot_key(self.session_id, lane.agent_id)
        raw = await self.backend.read(key)
        if raw is None:
            return False
        try:
            json.loads(raw)
        except ValueError:
            await self.backend.write(f"{key}.corrupt-{int(time.time())}", raw)
            await self.backend.delete(key)
            logger.error("lane=<%s>, key=<%s> | latest snapshot was corrupt; quarantined it", lane.agent_id, key)
            return True
        return False

    async def _recover(self, lane: Lane) -> TurnOutcome | None:
        gen = _current(lane)
        status = self._status(gen.agent)
        turn_id = str(status.get("turn_id") or uuid.uuid4().hex)
        if status.get("status") == "awaiting_approval":
            pending = status.get("pending", [])
            self._schedule_expiry(lane, turn_id, float(status.get("expires_at") or time.time()))
            outcome = TurnOutcome(
                turn_id,
                "awaiting_approval",
                stop_reason="interrupt",
                approvals=[_sheet(p, status.get("because")) for p in pending],
                lane=lane.agent_id,
            )
            self._emit(outcome.to_event())
            return outcome
        if status.get("status") != "running":
            return None
        logger.warning("lane=<%s>, turn_id=<%s> | resuming a turn interrupted by a restart", lane.agent_id, turn_id)
        plan = await self._plan_resume(
            lane,
            notice="Rivet restarted while working on this. Continue; verify the "
            "state of anything that may have changed before retrying.",
        )
        if plan.kind == "done":
            return await self._finish_from_history(lane, turn_id, plan.text, 1)
        if plan.kind == "awaiting":
            return await self._await_approval(lane, turn_id, plan.pending, 1)
        return await self._drive(lane, turn_id, plan.input)

    # -- recovery edits -------------------------------------------------------------------------------------

    async def _plan_resume(self, lane: Lane, *, notice: str | None) -> _Plan:
        """Decide how the restored agent continues, repairing history so nothing runs twice."""
        gen = _current(lane)
        agent = gen.agent
        snapshot = agent.take_snapshot(include=["messages", "interrupt_state"])
        messages: list[dict[str, Any]] = copy.deepcopy(snapshot.data["messages"])
        interrupts: dict[str, Any] = copy.deepcopy(snapshot.data.get("interrupt_state") or DEACTIVATED_INTERRUPTS)
        status = self._status(agent)
        changed = _drop_synthetic_cancel(messages)
        if not messages:
            return _Plan("done")
        last = messages[-1]
        uses = (
            [block["toolUse"] for block in last["content"] if "toolUse" in block] if last["role"] == "assistant" else []
        )
        if last["role"] == "assistant" and not uses:
            if changed:
                await self._load(gen, snapshot, messages, interrupts)
            return _Plan("done", text=_message_text(last))

        journal: dict[str, Any] = agent.state.get(ACTIONS_KEY) or {}
        if interrupts.get("activated"):
            answers = status.get("answers")
            if not answers:
                return _Plan("awaiting", pending=status.get("pending") or _pending_from(interrupts))
            if not any(use["toolUseId"] in journal for use in uses):
                if changed:
                    await self._load(gen, snapshot, messages, interrupts)
                return _Plan("continue", input=_responses(answers))
        if uses:
            reads_only = all(self.approvals.assess(u["name"], u.get("input")).tier == "read" for u in uses)
            if reads_only and not interrupts.get("activated") and not any(u["toolUseId"] in journal for u in uses):
                # The SDK re-runs the trailing tool calls itself; read-only calls are safe to repeat.
                if changed:
                    await self._load(gen, snapshot, messages, interrupts)
                return _Plan("continue")
            results = [_recovered_result(use, journal.get(use["toolUseId"])) for use in uses]
            messages.append({"role": "user", "content": [{"toolResult": result} for result in results]})
            interrupts = copy.deepcopy(DEACTIVATED_INTERRUPTS)
        elif interrupts.get("activated"):
            interrupts = copy.deepcopy(DEACTIVATED_INTERRUPTS)
        if notice and messages[-1]["role"] == "user":
            messages[-1]["content"].append({"text": f"<system-reminder>{notice}</system-reminder>"})
        await self._load(gen, snapshot, messages, interrupts)
        return _Plan("continue")

    async def _seal(self, lane: Lane, *, reply: str = "I couldn't finish the previous request.") -> None:
        """Close an unfinished turn so the history is valid for the next one."""
        gen = _current(lane)
        snapshot = gen.agent.take_snapshot(include=["messages", "interrupt_state"])
        messages: list[dict[str, Any]] = copy.deepcopy(snapshot.data["messages"])
        _drop_synthetic_cancel(messages)
        journal: dict[str, Any] = gen.agent.state.get(ACTIONS_KEY) or {}
        if messages and messages[-1]["role"] == "assistant":
            uses = [block["toolUse"] for block in messages[-1]["content"] if "toolUse" in block]
            if uses:
                results = [_recovered_result(use, journal.get(use["toolUseId"])) for use in uses]
                messages.append({"role": "user", "content": [{"toolResult": result} for result in results]})
        if messages and messages[-1]["role"] == "user":
            messages.append({"role": "assistant", "content": [{"text": reply}]})
        self._set_status(gen.agent, {**self._status(gen.agent), "status": "done"})
        await self._load(gen, snapshot, messages, copy.deepcopy(DEACTIVATED_INTERRUPTS))

    async def _load(
        self, gen: Generation, snapshot: Any, messages: list[dict[str, Any]], interrupts: dict[str, Any]
    ) -> None:
        snapshot.data = {"messages": messages, "interrupt_state": interrupts}
        gen.agent.load_snapshot(snapshot)
        await self._save(gen)

    async def _compact(self, lane: Lane, attempt: int) -> bool:
        """Last-resort overflow recovery: summarize the head on the aux model, keep a clean tail.

        The first attempt keeps a recent tail (or only the latest user turn when that leaves nothing to
        summarize); later attempts keep only the latest turn and shrink large tool results harder.

        Returns:
            False when nothing could be removed, so retrying would send the same request again.
        """
        gen = _current(lane)
        agent = gen.agent
        messages: list[dict[str, Any]] = copy.deepcopy(cast(Any, agent.messages))
        before = json.dumps(messages, sort_keys=True, default=str)
        cut = 0
        for keep in (COMPACT_KEEP, 1) if attempt == 1 else (1,):
            cut = _tail_start(messages, keep)
            if cut:
                break
        head = messages[:cut]
        tail = [_shrink(message, 8000 if attempt == 1 else 2000) for message in messages[cut:]]
        if not tail or json.dumps(tail, sort_keys=True, default=str) == before:
            return False
        await gen.session_manager.save_snapshot(agent, is_latest=False)  # the full history stays recoverable
        if head:
            summary = await self._summarize(agent, head)
            tail[0]["content"].insert(
                0,
                {
                    "text": "<system-reminder>Summary of the earlier conversation (the full "
                    f"history is kept in a checkpoint):\n{summary}</system-reminder>"
                },
            )
        agent.messages = cast(Any, tail)
        await self._save(gen)
        self._emit({"type": "notice", "kind": "compacted", "lane": lane.agent_id, "kept": len(tail)})
        return True

    async def _summarize(self, agent: Agent, head: list[dict[str, Any]]) -> str:
        summarizer = Agent(
            model=agent.aux_model,
            callback_handler=None,
            system_prompt=(
                "Summarize this conversation between a user and their phone assistant for the assistant to continue "
                "from. Keep names, numbers, commitments, decisions, pending tasks and anything already sent or "
                "changed. Plain text, at most 300 words."
            ),
        )
        try:
            result = await asyncio.wait_for(
                summarizer.invoke_async(_render(head)), timeout=self.settings.watchdog.model_total
            )
            return str(result).strip()
        except Exception as error:
            logger.warning("error=<%s> | summarization failed", _describe(error))
            return f"({len(head)} earlier messages omitted; summarization failed.)"

    # -- hooks ----------------------------------------------------------------------------------------------

    def _wiring(self, lane: Lane, box: list[Generation]) -> Wiring:
        watchdog = lane.watchdog
        approvals = self.approvals

        def tool_limit(name: str) -> float:
            timeouts = box[0].tool_timeouts if box else {}
            return timeouts.get(name, self.settings.watchdog.tool_default)

        def before_model(event: BeforeModelCallEvent) -> None:
            watchdog.model_started()

        def after_model(event: AfterModelCallEvent) -> None:
            watchdog.model_finished()

        async def before_tool(event: BeforeToolCallEvent) -> None:
            use = event.tool_use
            watchdog.tool_started(use["toolUseId"], use["name"], tool_limit(use["name"]))
            if event.cancel_tool or event.selected_tool is None:
                return
            if approvals.assess(use["name"], use.get("input")).tier == "read":
                return
            _journal(event.agent, use["toolUseId"], {"tool": use["name"], "status": "started"})
            await self._save(box[0])

        async def after_tool(event: AfterToolCallEvent) -> None:
            use = event.tool_use
            watchdog.tool_finished(use["toolUseId"])
            if use["name"].startswith(TELNYX_PREFIX) and _mcp_broken(event):
                watchdog.trip("the Telnyx MCP connection failed")
                # Stop before the next model call; the rebuilt generation gets a fresh connection.
                event.agent.cancel()
            journal = event.agent.state.get(ACTIONS_KEY) or {}
            if use["toolUseId"] not in journal:
                return
            result = event.result if isinstance(event.result, dict) else {}
            status = "completed" if event.exception is None and result.get("status") == "success" else "failed"
            _journal(
                event.agent,
                use["toolUseId"],
                {"tool": use["name"], "status": status, "result": _portable_result(use["toolUseId"], result)},
            )
            await self._save(box[0])

        def goal_guard(event: AfterInvocationEvent) -> None:
            budget = GoalBudget.load(event.agent.state)
            if budget is None:
                return
            stop = event.result.stop_reason if event.result is not None else None
            if stop in _NO_GOAL_RESUME:
                # GoalLoop judges every invocation; never let it retry over a pause, cancel or limit.
                event.resume = None
                return
            budget.attempts_used += 1
            budget.save(event.agent.state)

        async def progress(progress: float, total: float | None, message: str | None) -> None:
            watchdog.tool_progress()

        hooks: list[tuple[Callable[..., Any], float]] = [
            (before_model, HookOrder.SDK_FIRST),
            (after_model, HookOrder.SDK_FIRST),
            # After approvals (INTERVENTION_INPUT): only calls that will really run are journalled.
            (before_tool, HookOrder.SDK_LAST),
            (after_tool, HookOrder.SDK_FIRST),
            # After GoalLoop (DEFAULT); a no-op on lanes without a goal budget.
            (goal_guard, HookOrder.SDK_LAST),
        ]
        return Wiring(
            sink=lambda event: self._emit({**event, "lane": lane.agent_id}),
            on_activity=watchdog.activity,
            on_backoff=lambda error: watchdog.backoff(float(self.settings.retry_max_delay)),
            on_mcp_progress=progress,
            hooks=hooks,
        )

    # -- helpers --------------------------------------------------------------------------------------------

    def _lane(self, agent_id: str, tools: Sequence[Any], goal: GoalBudget | None = None) -> Lane:
        lane = self._lanes.get(agent_id)
        if lane is None:
            lane = self._lanes[agent_id] = Lane(agent_id, self.settings.watchdog, tools, goal)
        return lane

    @staticmethod
    def _status(agent: Agent) -> dict[str, Any]:
        status = agent.state.get(STATUS_KEY)
        return status if isinstance(status, dict) else {}

    @staticmethod
    def _set_status(agent: Agent, status: dict[str, Any]) -> None:
        agent.state.set(STATUS_KEY, status)

    @staticmethod
    async def _save(gen: Generation) -> None:
        await gen.session_manager.save_snapshot(gen.agent, is_latest=True)

    def _emit(self, event: dict[str, Any]) -> None:
        if self.sink is None:
            return
        try:
            self.sink({**event, "session_id": self.session_id})
        except Exception:
            logger.exception("event_type=<%s> | sink failed", event.get("type"))

    def _spawn(self, coroutine: Coroutine[Any, Any, Any]) -> None:
        task = asyncio.ensure_future(coroutine)
        self._background.add(task)
        task.add_done_callback(self._background.discard)

    def _acquire_lock(self) -> None:
        if self._lock_file is not None:
            return
        self._lock_dir.mkdir(parents=True, exist_ok=True)
        handle = (self._lock_dir / f"{self.session_id}.lock").open("a+")
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            handle.close()
            raise SessionLockedError(f"session '{self.session_id}' is supervised by another process") from error
        self._lock_file = handle


def latest_snapshot_key(session_id: str, agent_id: str) -> str:
    """Backend key of an agent's ``snapshot_latest`` (the snapshot session manager's ``session/`` layout)."""
    return f"session/{session_id}/scopes/agent/{agent_id}/snapshots/snapshot_latest.json"


async def _consume(agent: Agent, prompt: Any, turn_id: str, limits: Limits) -> AgentResult:
    result: AgentResult | None = None
    async for event in agent.stream_async(prompt, limits=limits, idempotency_token=turn_id):
        if isinstance(event, dict) and "result" in event:
            result = event["result"]
    if result is None:
        raise RuntimeError("the agent stream ended without a result")
    return result


def _current(lane: Lane) -> Generation:
    if lane.gen is None:
        raise RuntimeError(f"lane '{lane.agent_id}' has no agent")
    return lane.gen


def _finished(zombie: Any) -> bool:
    item = zombie[1] if isinstance(zombie, tuple) else zombie
    return bool(item.done())


def _describe(error: BaseException) -> str:
    return f"{type(error).__name__}: {str(error)[:300]}"


def _responses(answers: Mapping[str, Any]) -> list[dict[str, Any]]:
    return [
        {"interruptResponse": {"interruptId": interrupt_id, "response": response}}
        for interrupt_id, response in answers.items()
    ]


def _running(turn_id: str, because: str | None) -> dict[str, Any]:
    status: dict[str, Any] = {"turn_id": turn_id, "status": "running"}
    if because:
        status["because"] = because
    return status


def _sheet(pending: Mapping[str, Any], because: object = None) -> dict[str, Any]:
    reason = pending.get("reason")
    try:
        request = json.loads(reason) if isinstance(reason, str) else reason
    except ValueError:
        request = {"headline": reason}
    sheet = {"id": pending.get("id"), "name": pending.get("name"), "request": request}
    if isinstance(because, str) and because:
        sheet["because"] = because
    return sheet


def _pending_from(interrupts: Mapping[str, Any]) -> list[dict[str, Any]]:
    return [
        {"id": interrupt_id, "name": data.get("name"), "reason": data.get("reason")}
        for interrupt_id, data in (interrupts.get("interrupts") or {}).items()
        if data.get("response") is None
    ]


def _journal(agent: Agent, tool_use_id: str, entry: dict[str, Any]) -> None:
    journal = dict(agent.state.get(ACTIONS_KEY) or {})
    journal.pop(tool_use_id, None)
    journal[tool_use_id] = entry
    while len(journal) > JOURNAL_LIMIT:
        journal.pop(next(iter(journal)))
    agent.state.set(ACTIONS_KEY, journal)


def _portable_result(tool_use_id: str, result: Mapping[str, Any]) -> dict[str, Any]:
    content = [block for block in result.get("content") or [] if "text" in block or "json" in block]
    return {"toolUseId": tool_use_id, "status": result.get("status", "error"), "content": content or [{"text": ""}]}


def _recovered_result(use: Mapping[str, Any], entry: Mapping[str, Any] | None) -> dict[str, Any]:
    tool_use_id = str(use["toolUseId"])
    if entry is not None and entry.get("status") in ("completed", "failed") and isinstance(entry.get("result"), dict):
        return {**entry["result"], "toolUseId": tool_use_id}
    text = MAY_HAVE_RUN if entry is not None else NOT_STARTED
    return {"toolUseId": tool_use_id, "status": "error", "content": [{"text": text}]}


def _drop_synthetic_cancel(messages: list[dict[str, Any]]) -> bool:
    dropped = False
    while messages and messages[-1]["role"] == "assistant" and messages[-1]["content"] == SYNTHETIC_CANCEL:
        messages.pop()
        dropped = True
    return dropped


def _message_text(message: Mapping[str, Any]) -> str:
    parts: list[str] = []
    for block in message.get("content") or []:
        if "text" in block:
            parts.append(block["text"])
        elif "citationsContent" in block:
            parts.extend(c.get("text", "") for c in block["citationsContent"].get("content", []))
    return "".join(parts).strip()


def _mcp_broken(event: AfterToolCallEvent) -> bool:
    if isinstance(event.exception, MCPClientInitializationError):
        return True
    result = event.result if isinstance(event.result, dict) else {}
    if result.get("status") != "error":
        return False
    text = " ".join(str(block.get("text", "")) for block in result.get("content") or [])
    return bool(_MCP_BROKEN.search(text))


def _tail_start(messages: Sequence[Mapping[str, Any]], keep: int) -> int:
    """Index where the kept tail begins: a user message with no tool results, so tool pairs stay intact."""
    clean = [
        i
        for i, m in enumerate(messages)
        if m["role"] == "user" and not any("toolResult" in block for block in m["content"])
    ]
    if not clean:
        return 0
    recent = [i for i in clean if i >= len(messages) - keep]
    return recent[0] if recent else clean[-1]


def _shrink(message: dict[str, Any], limit: int = 8000) -> dict[str, Any]:
    for block in message["content"]:
        if "text" in block and len(block["text"]) > limit * 2:
            block["text"] = block["text"][: limit * 2] + "\n[truncated]"
        result = block.get("toolResult")
        if result:
            for part in result.get("content") or []:
                if "text" in part and len(part["text"]) > limit:
                    part["text"] = part["text"][:limit] + "\n[truncated to fit the context window]"
    return message


def _render(messages: Sequence[Mapping[str, Any]], limit: int = 100_000) -> str:
    lines: list[str] = []
    for message in messages:
        for block in message["content"]:
            if "text" in block:
                lines.append(f"{message['role']}: {block['text'][:2000]}")
            elif "toolUse" in block:
                lines.append(f"assistant called {block['toolUse']['name']}")
            elif "toolResult" in block:
                text = " ".join(str(p.get("text", "")) for p in block["toolResult"].get("content") or [])
                lines.append(f"tool result ({block['toolResult'].get('status')}): {text[:500]}")
            elif "citationsContent" in block:
                lines.append(f"assistant: {_message_text({'content': [block]})[:2000]}")
    return "\n".join(lines)[-limit:]
