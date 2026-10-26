"""WebSocket gateway between the phone and its session supervisor.

Protocol (JSON messages on ``/v1/sessions/{session_id}``, ``Authorization: Bearer <RIVET_DEVICE_TOKEN>``):

Phone to brain: ``turn`` {text, turn_id?}, ``approval.response`` {answers: {interrupt_id: response}, lane?},
``task`` {goal, task_id?, max_attempts?, timeout?}, ``device.result`` {action_id, result}, ``resume``, ``ping``.

Brain to phone: ``turn.accepted``, ``turn.started``, the agent's UI events (``text``, ``thinking``,
``searching``, ``sources``, ``tool``, ...), ``approval.request``, ``approval.expired``, ``restart``,
``turn.completed``, ``turn.failed``, ``device.call``, ``session.ready``, ``pong``, ``error``.

Events emitted while the phone is away are buffered and delivered in order when it reconnects.
"""

from __future__ import annotations

import asyncio
import contextlib
import hmac
import logging
import os
import re
from collections import deque
from collections.abc import AsyncIterator, Callable, Coroutine, Sequence
from contextlib import asynccontextmanager
from typing import Any

from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route, WebSocketRoute
from starlette.websockets import WebSocket, WebSocketDisconnect
from strands.storage import LocalFileStorage, Storage

from . import device_tools
from .approvals import RivetApprovals
from .config import BrainSettings
from .device import DeviceBridge
from .factory import build_agent
from .supervisor import BuildFn, NoPendingApprovalError, SessionLockedError, SessionSupervisor

logger = logging.getLogger(__name__)

DEVICE_TOKEN_ENV = "RIVET_DEVICE_TOKEN"
MIN_TOKEN_LENGTH = 24
BUFFER_LIMIT = 1000
TURN_MEMORY = 256
_SESSION_ID = re.compile(r"^[A-Za-z0-9._-]{1,128}$")

ToolsFor = Callable[[DeviceBridge], Sequence[Any]]


class _Session:
    """One session's supervisor, device bridge, and the phone socket attached to it."""

    def __init__(self, supervisor_factory: Callable[[_Session], SessionSupervisor]) -> None:
        self.bridge = DeviceBridge()
        self.socket: WebSocket | None = None
        self.buffer: deque[dict[str, Any]] = deque(maxlen=BUFFER_LIMIT)
        self.queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self.send_lock = asyncio.Lock()
        self.tasks: set[asyncio.Task[Any]] = set()
        self.turn_ids: deque[str] = deque(maxlen=TURN_MEMORY)
        self.supervisor = supervisor_factory(self)
        self.failed: str | None = None
        self.started = self.spawn(self._start())
        self.writer = self.spawn(self._write())

    def emit(self, event: dict[str, Any]) -> None:
        """Supervisor sink: queue the event for ordered delivery."""
        self.queue.put_nowait(event)

    async def attach(self, socket: WebSocket) -> None:
        """Make ``socket`` the phone; deliver what was buffered while it was away."""
        previous, self.socket = self.socket, socket
        if previous is not None and previous is not socket:
            with contextlib.suppress(Exception):
                await previous.close(code=4000, reason="replaced by a newer connection")
        async with self.send_lock:
            while self.buffer:
                await socket.send_json(self.buffer.popleft())
        await self.bridge.attach(self.send_device)

    def detach(self, socket: WebSocket) -> None:
        """Forget ``socket`` if it is still the attached phone."""
        if self.socket is socket:
            self.socket = None
            self.bridge.detach(self.send_device)

    async def send_device(self, message: dict[str, Any]) -> None:
        """Send a device call now (it has a deadline); fails if the phone is gone."""
        socket = self.socket
        if socket is None:
            raise ConnectionError("the phone disconnected")
        async with self.send_lock:
            await socket.send_json(message)

    async def handle(self, message: dict[str, Any]) -> None:
        """Dispatch one phone message."""
        kind = message.get("type")
        if kind == "ping":
            self.emit({"type": "pong"})
            return
        if kind == "device.result":
            if not self.bridge.resolve(str(message.get("action_id")), message.get("result") or {}):
                logger.info("action_id=<%s> | device result had no waiting call", message.get("action_id"))
            return
        await self.started
        if self.failed is not None:
            self.emit({"type": "error", "error": self.failed})
            return
        if kind == "turn" and isinstance(message.get("text"), str) and message["text"].strip():
            turn_id = message.get("turn_id")
            self.emit({"type": "turn.accepted", "turn_id": turn_id})
            # The phone re-sends turns it never saw accepted; the supervisor also refuses its latest turn id.
            if turn_id is not None:
                if turn_id in self.turn_ids:
                    return
                self.turn_ids.append(turn_id)
            self.spawn(self._guard(self.supervisor.send(message["text"], turn_id=turn_id), turn_id))
        elif kind == "approval.response" and isinstance(message.get("answers"), dict):
            lane = str(message.get("lane") or "default")
            self.spawn(self._guard(self.supervisor.respond(message["answers"], lane_id=lane), None))
        elif kind == "task" and isinstance(message.get("goal"), str):
            self.spawn(
                self._guard(
                    self.supervisor.run_task(
                        message["goal"],
                        task_id=message.get("task_id"),
                        max_attempts=int(message.get("max_attempts", 5)),
                        timeout=float(message.get("timeout", 1800)),
                    ),
                    message.get("task_id"),
                )
            )
        elif kind == "resume":
            for lane in self.supervisor.lanes:
                approvals = self.supervisor.pending_approvals(lane)
                if approvals:
                    self.emit({"type": "approval.request", "lane": lane, "approvals": approvals, "resent": True})
            self.emit({"type": "session.ready"})
        else:
            self.emit({"type": "error", "error": f"unsupported message: {kind!r}"})

    async def close(self) -> None:
        for task in list(self.tasks):
            if task is not self.writer:
                task.cancel()
        with contextlib.suppress(Exception):
            await self.supervisor.close()
        self.writer.cancel()

    async def _start(self) -> None:
        try:
            await self.supervisor.start()
        except SessionLockedError as error:
            self.failed = str(error)
            self.emit({"type": "error", "error": self.failed})

    def spawn(self, coroutine: Coroutine[Any, Any, Any]) -> asyncio.Task[Any]:
        task = asyncio.ensure_future(coroutine)
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return task

    async def _guard(self, coroutine: Coroutine[Any, Any, Any], turn_id: Any) -> None:
        try:
            await coroutine
        except NoPendingApprovalError as error:
            self.emit({"type": "error", "turn_id": turn_id, "error": str(error)})
        except Exception as error:
            logger.exception("turn_id=<%s> | turn crashed", turn_id)
            self.emit({"type": "turn.failed", "turn_id": turn_id, "error": f"{type(error).__name__}: {error}"})

    async def _write(self) -> None:
        while True:
            event = await self.queue.get()
            async with self.send_lock:
                socket = self.socket
                if socket is None:
                    self.buffer.append(event)
                    continue
                try:
                    await socket.send_json(event)
                except Exception:
                    self.buffer.append(event)
                    self.detach(socket)


class SessionHub:
    """Creates one supervisor per session on first connect and keeps it for the process lifetime."""

    def __init__(
        self,
        settings: BrainSettings,
        *,
        backend: Storage,
        build: BuildFn,
        tools_for: ToolsFor | None,
        approvals: RivetApprovals,
    ) -> None:
        self.settings = settings
        self.backend = backend
        self.build = build
        self.tools_for = tools_for
        self.approvals = approvals
        self.sessions: dict[str, _Session] = {}

    def get(self, session_id: str) -> _Session:
        session = self.sessions.get(session_id)
        if session is None:

            def factory(owner: _Session) -> SessionSupervisor:
                tools = self.tools_for(owner.bridge) if self.tools_for else ()
                return SessionSupervisor(
                    self.settings,
                    session_id,
                    backend=self.backend,
                    approvals=self.approvals,
                    tools=tools,
                    sink=owner.emit,
                    build=self.build,
                )

            session = self.sessions[session_id] = _Session(factory)
        return session

    async def close(self) -> None:
        for session in self.sessions.values():
            await session.close()
        self.sessions.clear()


def create_app(
    settings: BrainSettings,
    *,
    backend: Storage | None = None,
    build: BuildFn = build_agent,
    tools_for: ToolsFor | None = None,
    approvals: RivetApprovals | None = None,
    device_token: str | None = None,
) -> Starlette:
    """The gateway ASGI app.

    Args:
        settings: Brain settings.
        backend: Session storage; defaults to ``LocalFileStorage(settings.session_dir)``.
        build: Generation factory (tests inject scripted models here).
        tools_for: Local tools for a session, given its device bridge.
        approvals: Approval policy shared by every session (device tool policies go here).
        device_token: The phone's bearer token; defaults to ``$RIVET_DEVICE_TOKEN``. Required.
    """
    token = device_token or os.environ.get(DEVICE_TOKEN_ENV, "")
    if len(token) < MIN_TOKEN_LENGTH:
        raise RuntimeError(f"{DEVICE_TOKEN_ENV} must be set to a random secret of at least {MIN_TOKEN_LENGTH} chars")
    hub = SessionHub(
        settings,
        backend=backend or LocalFileStorage(settings.session_dir),
        build=build,
        tools_for=tools_for,
        approvals=approvals or RivetApprovals(),
    )
    expected = f"Bearer {token}".encode()

    async def healthz(request: Request) -> JSONResponse:
        return JSONResponse({"status": "ok"})

    async def session_socket(socket: WebSocket) -> None:
        offered = socket.headers.get("authorization", "").encode()
        if not hmac.compare_digest(offered, expected):
            await socket.close(code=4401, reason="unauthorized")
            return
        session_id = socket.path_params["session_id"]
        if not _SESSION_ID.match(session_id):
            await socket.close(code=4400, reason="invalid session id")
            return
        await socket.accept()
        session = hub.get(session_id)
        await session.attach(socket)
        try:
            while True:
                message = await socket.receive_json()
                if isinstance(message, dict):
                    await session.handle(message)
        except WebSocketDisconnect:
            pass
        finally:
            session.detach(socket)

    @asynccontextmanager
    async def lifespan(app: Starlette) -> AsyncIterator[None]:
        try:
            yield
        finally:
            await hub.close()

    app = Starlette(
        routes=[Route("/healthz", healthz), WebSocketRoute("/v1/sessions/{session_id}", session_socket)],
        lifespan=lifespan,
    )
    app.state.hub = hub
    return app


def main() -> None:
    """``rivet-brain``: serve the gateway (``RIVET_HOST``/``PORT``)."""
    import uvicorn

    logging.basicConfig(
        level=os.environ.get("RIVET_LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )
    app = create_app(
        BrainSettings.from_env(),
        tools_for=device_tools.tools_for,
        approvals=RivetApprovals(device_tools.POLICIES),
    )
    uvicorn.run(
        app,
        host=os.environ.get("RIVET_HOST", "0.0.0.0"),
        port=int(os.environ.get("PORT", "8080")),
        ws_ping_interval=20,
        ws_ping_timeout=20,
    )
