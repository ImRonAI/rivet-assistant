"""WebSocket gateway between the phone and its session supervisor.

Protocol (JSON messages on ``/v1/sessions/{session_id}``, ``Authorization: Bearer <RIVET_DEVICE_TOKEN>``):

Phone to brain: ``turn`` {text, turn_id?}, ``approval.response`` {answers: {interrupt_id: {approved, standing?:
allow|deny}}, lane?} (``standing`` makes the answer a standing rule when the sheet offered it; the rule is stored
before the turn resumes), ``task`` {goal, task_id?, max_attempts?, timeout?, reason?} (``reason``, at most 140
characters, is why the task exists; its approvals show it), ``rules.list``, ``rules.set`` {decision: allow|deny,
tool, scope} (a rule the phone makes for its own approvals; the brain recomputes the offer and refuses what it
would not offer), ``rules.revoke`` {key}, ``device.result`` {action_id, result}, ``call.take_over`` {call_id, to}
(transfer a live call to ``to``, the user's own E.164 number; the tap is the approval), ``resume``, ``ping``.

Brain to phone: ``turn.accepted``, ``turn.started``, the agent's UI events (``text``, ``thinking``,
``searching``, ``sources``, ``tool``, ...), ``approval.request`` (each approval is {id, name, request, because?},
``request`` being the v2 prompt of ``approvals.Assessment.prompt``), ``approval.expired``, ``rules`` {revision,
rules: [{key, tool, scope, decision, label, created_at}]} (on every ``resume``, before ``session.ready``, and after
every change), ``rules.refused`` {request: set|revoke, error} (plain text), ``rules.applied`` {decision, key, label,
tool} (a standing rule decided a call without asking), ``restart``,
``turn.completed``, ``turn.failed``, ``device.call``, ``call.answering`` {call_id, from, verified}, ``call.live``
{call_id, from, verified, started_at, status: answering|transferring, lines: [{speaker: caller|rivet, text}],
snapshot} (``lines`` are new since the last one, or all of them when ``snapshot``; a snapshot of each live call
is re-sent on ``resume``), ``call.take_over.failed`` {call_id, error}, ``call.message`` {call_id, caller_name,
callback_number, message, urgent, hung_up_early, at}, ``call.ended`` {call_id, from, caller_name, started_at,
ended_at, duration, summary, outcome, taken_over}, ``session.ready``, ``pong``, ``error``. Times are Unix seconds;
``started_at`` and ``duration`` are null for a call that began before the brain did.

``GET /v1/agent/session?mode=voice|text`` (same bearer) returns a credential for one ElevenLabs agent session:
``{mode, agent_id, conversation_token}`` for voice (WebRTC), ``{mode, agent_id, signed_url}`` for text (WebSocket).

Telnyx's answering assistant posts to ``/v1/calls/{session_id}/{token}/start|message|summary`` (see ``calls.py``
and ``live_calls.py``).

Events emitted while the phone is away are buffered and delivered in order when it reconnects.
"""

from __future__ import annotations

import asyncio
import contextlib
import hmac
import json
import logging
import os
import re
import time
from collections import deque
from collections.abc import AsyncIterator, Callable, Coroutine, Sequence
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

import httpx
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route, WebSocketRoute
from starlette.websockets import WebSocket, WebSocketDisconnect
from strands.storage import LocalFileStorage, Storage

from . import calls, device_tools, email_tools
from .alerts import task_reason
from .approvals import RivetApprovals
from .calls import (
    CALL_ID_VARIABLE,
    FOLLOW_UP_ATTEMPTS,
    FOLLOW_UP_GOAL,
    FOLLOW_UP_TIMEOUT,
    CallInbox,
    CallLine,
    call_token,
    caller_of,
    clean_message,
    clean_summary,
)
from .config import BrainSettings
from .device import DeviceBridge
from .factory import Wiring, build_agent, build_telnyx_client
from .live_calls import POLL_SECONDS, LiveCalls, TelnyxRunner
from .rules import RuleRefusedError, StandingRules, admit, apply_answers
from .supervisor import BuildFn, NoPendingApprovalError, SessionLockedError, SessionSupervisor

logger = logging.getLogger(__name__)

DEVICE_TOKEN_ENV = "RIVET_DEVICE_TOKEN"
MIN_TOKEN_LENGTH = 24
BUFFER_LIMIT = 1000
TURN_MEMORY = 256
CALL_BODY_LIMIT = 64 * 1024
ELEVENLABS_API = "https://api.elevenlabs.io"
ELEVENLABS_KEY_ENV = "ELEVENLABS_API_KEY"
# mode: (agent id env var, default agent id, credential endpoint, response field, field returned to the phone).
# The Android SDK starts private voice agents with a conversation token and text-only ones with a signed URL.
ELEVENLABS_AGENTS = {
    "voice": (
        "RIVET_ELEVENLABS_VOICE_AGENT_ID",
        "agent_2901m620nzwrev2r7ekqpydcxe25",
        "token",
        "token",
        "conversation_token",
    ),
    "text": (
        "RIVET_ELEVENLABS_TEXT_AGENT_ID",
        "agent_9601m623qa72ffs9cyy3ezc0p6tb",
        "get-signed-url",
        "signed_url",
        "signed_url",
    ),
}
_SESSION_ID = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
FOLLOW_UP_REASON = "A caller left you a message."
CANT_MAKE_RULE = "Rivet can't make a rule for this."

ToolsFor = Callable[[DeviceBridge], Sequence[Any]]


class _Session:
    """One session's supervisor, device bridge, live calls, standing rules, and the phone socket attached to it."""

    def __init__(
        self,
        session_id: str,
        supervisor_factory: Callable[[_Session], SessionSupervisor],
        *,
        rules: StandingRules,
        telnyx: TelnyxRunner | None = None,
        call_poll: float = POLL_SECONDS,
    ) -> None:
        self.rules = rules
        self.bridge = DeviceBridge(session_id)
        self.socket: WebSocket | None = None
        self.buffer: deque[dict[str, Any]] = deque(maxlen=BUFFER_LIMIT)
        self.queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self.send_lock = asyncio.Lock()
        self.tasks: set[asyncio.Task[Any]] = set()
        self.turn_ids: deque[str] = deque(maxlen=TURN_MEMORY)
        self.calls = LiveCalls(self.emit, self.spawn, telnyx=telnyx, poll=call_poll)
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
        if kind == "call.take_over":
            # A caller is waiting: this never queues behind the supervisor.
            self.calls.take_over(str(message.get("call_id") or ""), message.get("to"))
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
            answers: dict[str, object] = message["answers"]
            pending = self.supervisor.pending_approvals(lane)
            if await apply_answers(self.rules, pending, answers, now=time.time(), on_refused=self._refused("set")):
                self.emit(dict(self.rules.event()))
            self.spawn(self._guard(self.supervisor.respond(answers, lane_id=lane), None))
        elif kind == "task" and isinstance(message.get("goal"), str):
            self.spawn(
                self._guard(
                    self.supervisor.run_task(
                        message["goal"],
                        task_id=message.get("task_id"),
                        max_attempts=int(message.get("max_attempts", 5)),
                        timeout=float(message.get("timeout", 1800)),
                        reason=task_reason(message.get("reason")),
                    ),
                    message.get("task_id"),
                )
            )
        elif kind == "rules.list":
            self.emit(dict(self.rules.event()))
        elif kind == "rules.set":
            await self._set_rule(message.get("decision"), message.get("tool"), message.get("scope"))
        elif kind == "rules.revoke" and isinstance(message.get("key"), str):
            await self.rules.revoke(message["key"])
            self.emit(dict(self.rules.event()))
        elif kind == "resume":
            self.emit(dict(self.rules.event()))
            for lane in self.supervisor.lanes:
                approvals = self.supervisor.pending_approvals(lane)
                if approvals:
                    self.emit({"type": "approval.request", "lane": lane, "approvals": approvals, "resent": True})
            for event in self.calls.snapshots():
                self.emit(event)
            self.emit({"type": "session.ready"})
        else:
            self.emit({"type": "error", "error": f"unsupported message: {kind!r}"})

    async def _set_rule(self, decision: object, tool: object, scope: object) -> None:
        """A rule the phone makes for an approval it asked itself; the brain decides what may be a rule."""
        fields = _str_fields(scope)
        offer = None
        if isinstance(tool, str) and fields is not None:
            offer = self.supervisor.approvals.offer_for(tool, fields)
        if offer is None or (decision != "allow" and decision != "deny"):
            self._refused("set")(CANT_MAKE_RULE)
            return
        try:
            await self.rules.put(admit(offer, decision, now=time.time()))
        except RuleRefusedError as error:
            self._refused("set")(str(error))
            return
        self.emit(dict(self.rules.event()))

    def _refused(self, request: str) -> Callable[[str], None]:
        return lambda error: self.emit({"type": "rules.refused", "request": request, "error": error})

    def follow_up_call(self, received_at: float) -> None:
        """Start a background task that works out what to do about a message Rivet took on a call."""
        task_id = f"call-{int(received_at * 1000)}"
        self.spawn(self._follow_up(task_id))

    async def _follow_up(self, task_id: str) -> None:
        await self.started
        if self.failed is not None:
            return
        task = self.supervisor.run_task(
            FOLLOW_UP_GOAL,
            task_id=task_id,
            max_attempts=FOLLOW_UP_ATTEMPTS,
            timeout=FOLLOW_UP_TIMEOUT,
            reason=FOLLOW_UP_REASON,
        )
        await self._guard(task, task_id)

    async def close(self) -> None:
        for task in list(self.tasks):
            if task is not self.writer:
                task.cancel()
        with contextlib.suppress(Exception):
            await self.supervisor.close()
        self.writer.cancel()

    async def _start(self) -> None:
        try:
            await self.rules.load()
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


def _str_fields(value: object) -> dict[str, str] | None:
    if not isinstance(value, dict):
        return None
    fields = {k: v for k, v in value.items() if isinstance(k, str) and isinstance(v, str)}
    return fields if len(fields) == len(value) else None


class SessionHub:
    """Creates one supervisor per session on first connect and keeps it for the process lifetime.

    ``approvals`` is the template policy; each session gets it bound to that session's standing rules.
    """

    def __init__(
        self,
        settings: BrainSettings,
        *,
        backend: Storage,
        build: BuildFn,
        tools_for: ToolsFor | None,
        approvals: RivetApprovals,
        telnyx: TelnyxRunner | None = None,
        call_poll: float = POLL_SECONDS,
    ) -> None:
        self.settings = settings
        self.backend = backend
        self.build = build
        self.tools_for = tools_for
        self.approvals = approvals
        self.telnyx = telnyx
        self.call_poll = call_poll
        self.sessions: dict[str, _Session] = {}

    def get(self, session_id: str) -> _Session:
        session = self.sessions.get(session_id)
        if session is None:
            rules = StandingRules(self.backend, session_id)

            def factory(owner: _Session) -> SessionSupervisor:
                tools = self.tools_for(owner.bridge) if self.tools_for else ()
                return SessionSupervisor(
                    self.settings,
                    session_id,
                    backend=self.backend,
                    approvals=self.approvals.bound(rules, on_rule=lambda applied: owner.emit(dict(applied))),
                    tools=tools,
                    sink=owner.emit,
                    build=self.build,
                )

            session = _Session(session_id, factory, rules=rules, telnyx=self.telnyx, call_poll=self.call_poll)
            self.sessions[session_id] = session
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
    call_inbox: CallInbox | None = None,
    follow_up_calls: bool = True,
    call_telnyx: TelnyxRunner | None = None,
    call_poll: float = POLL_SECONDS,
    elevenlabs_transport: httpx.AsyncBaseTransport | None = None,
) -> Starlette:
    """The gateway ASGI app.

    Args:
        settings: Brain settings.
        backend: Session storage; defaults to ``LocalFileStorage(settings.session_dir)``.
        build: Generation factory (tests inject scripted models here).
        tools_for: Local tools for a session, given its device bridge.
        approvals: Approval policy template (device tool policies go here); each session binds it to its own rules.
        device_token: The phone's bearer token; defaults to ``$RIVET_DEVICE_TOKEN``. Required.
        call_inbox: Where answered calls and messages are kept; defaults to ``calls/`` beside the sessions.
        follow_up_calls: Start a task for each message a caller leaves, to propose the next step.
        call_telnyx: Telnyx connection for live calls (transcript and take-over); closed with the app.
        call_poll: Seconds between live transcript reads.
        elevenlabs_transport: HTTP transport for ElevenLabs credential requests (tests inject a fake here).
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
        telnyx=call_telnyx,
        call_poll=call_poll,
    )
    expected = f"Bearer {token}".encode()
    inbox = call_inbox or CallInbox(Path(settings.session_dir).parent / "calls")

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

    async def agent_session(request: Request) -> JSONResponse:
        """A credential for one ElevenLabs agent session; the ElevenLabs API key never leaves the brain."""
        if not hmac.compare_digest(request.headers.get("authorization", "").encode(), expected):
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        mode = request.query_params.get("mode", "")
        if mode not in ELEVENLABS_AGENTS:
            return JSONResponse({"error": "mode must be voice or text"}, status_code=400)
        key = os.environ.get(ELEVENLABS_KEY_ENV, "").strip()
        if not key:
            return JSONResponse({"error": "ElevenLabs agents are not set up on the brain"}, status_code=503)
        env_var, default_agent, endpoint, field, returned = ELEVENLABS_AGENTS[mode]
        agent_id = os.environ.get(env_var, "").strip() or default_agent
        try:
            async with httpx.AsyncClient(base_url=ELEVENLABS_API, transport=elevenlabs_transport, timeout=15) as http:
                response = await http.get(
                    f"/v1/convai/conversation/{endpoint}", params={"agent_id": agent_id}, headers={"xi-api-key": key}
                )
            response.raise_for_status()
            credential = response.json()[field]
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as error:
            logger.warning("mode=<%s>, error=<%s> | elevenlabs credential request failed", mode, type(error).__name__)
            return JSONResponse({"error": "ElevenLabs did not issue a session"}, status_code=502)
        if not isinstance(credential, str) or not credential:
            return JSONResponse({"error": "ElevenLabs did not issue a session"}, status_code=502)
        return JSONResponse(
            {"mode": mode, "agent_id": agent_id, returned: credential}, headers={"Cache-Control": "no-store"}
        )

    async def call_webhook(request: Request) -> JSONResponse:
        """Telnyx's answering assistant: ``start`` when it picks up a call, ``message`` when it took one,
        ``summary`` after the call ended."""
        session_id = request.path_params["session_id"]
        kind = request.path_params["kind"]
        offered = str(request.path_params["token"]).encode()
        if (
            kind not in ("start", "message", "summary")
            or not _SESSION_ID.match(session_id)
            or not hmac.compare_digest(offered, call_token(token, session_id).encode())
        ):
            return JSONResponse({"error": "not found"}, status_code=404)
        raw = await request.body()
        if len(raw) > CALL_BODY_LIMIT:
            return JSONResponse({"error": "too large"}, status_code=413)
        try:
            body = json.loads(raw or b"{}")
        except ValueError:
            body = {}
        session = hub.get(session_id)
        if kind == "start":
            caller, verified = caller_of(body)
            call = session.calls.open(caller, verified)
            inbox.add(session_id, {"kind": "answering", "call_id": call.call_id, "from": caller, "verified": verified})
            return JSONResponse({"dynamic_variables": {CALL_ID_VARIABLE: call.call_id}})
        if kind == "summary":
            session.calls.end(inbox.add(session_id, clean_summary(body)))
            return JSONResponse({"result": "Summary delivered."})
        record = inbox.add(session_id, clean_message(body))
        session.emit({"type": "call.message", **{k: v for k, v in record.items() if k != "kind"}})
        if follow_up_calls:
            session.follow_up_call(record["at"])
        return JSONResponse({"result": "Message saved and delivered."})

    @asynccontextmanager
    async def lifespan(app: Starlette) -> AsyncIterator[None]:
        try:
            yield
        finally:
            await hub.close()
            if call_telnyx is not None:
                await call_telnyx.close()

    app = Starlette(
        routes=[
            Route("/healthz", healthz),
            WebSocketRoute("/v1/sessions/{session_id}", session_socket),
            Route("/v1/agent/session", agent_session),
            Route("/v1/calls/{session_id}/{token}/{kind}", call_webhook, methods=["POST"]),
        ],
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
    settings = BrainSettings.from_env()
    public_url = os.environ.get("RIVET_PUBLIC_URL", "").strip()
    line = (
        CallLine(
            public_url,
            os.environ.get(DEVICE_TOKEN_ENV, ""),
            model=os.environ.get("RIVET_CALL_MODEL", calls.DEFAULT_MODEL),
            voice=os.environ.get("RIVET_CALL_VOICE", calls.DEFAULT_VOICE),
        )
        if public_url
        else None
    )
    inbox = CallInbox(Path(settings.session_dir).parent / "calls")
    account = email_tools.EmailAccount.from_env()
    mailbox = email_tools.Mailbox(account) if account is not None else None
    if account is not None:
        logger.info("imap=<%s>, smtp=<%s> | email tools enabled", account.imap_host, account.smtp_host)
    # Live calls run fixed code on a Telnyx connection of their own (see live_calls.TelnyxRunner).
    telnyx = TelnyxRunner(lambda: build_telnyx_client(settings, Wiring())) if line else None

    def tools_for(bridge: DeviceBridge) -> list[Any]:
        return [
            *device_tools.tools_for(bridge),
            *calls.tools_for(bridge, bridge.session_id, line, inbox),
            *email_tools.tools_for(mailbox),
        ]

    app = create_app(
        settings,
        tools_for=tools_for,
        approvals=RivetApprovals({**device_tools.POLICIES, **calls.POLICIES, **email_tools.POLICIES}),
        call_inbox=inbox,
        follow_up_calls=os.environ.get("RIVET_CALL_FOLLOW_UP", "1") != "0",
        call_telnyx=telnyx,
        call_poll=float(os.environ.get("RIVET_CALL_POLL") or POLL_SECONDS),
    )
    uvicorn.run(
        app,
        host=os.environ.get("RIVET_HOST", "0.0.0.0"),
        port=int(os.environ.get("PORT", "8080")),
        ws_ping_interval=20,
        ws_ping_timeout=20,
    )
