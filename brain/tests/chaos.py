"""Shared fixtures for the supervisor's chaos tests: fast deadlines, scripted models, hanging responses."""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Callable, Sequence
from dataclasses import replace
from pathlib import Path
from typing import Any

import httpx
from strands.storage import LocalFileStorage

from rivet_brain.approvals import RivetApprovals, ToolPolicy
from rivet_brain.config import BrainSettings, WatchdogSettings
from rivet_brain.factory import Generation, build_agent
from rivet_brain.models.perplexity import build_perplexity_model
from rivet_brain.supervisor import SessionSupervisor
from tests.sse import SSE, FakePerplexity

FAST = WatchdogSettings(
    first_chunk=0.6,
    idle=1.0,
    model_total=10.0,
    tool_default=1.0,
    telnyx_execute=1.0,
    soft_grace=0.2,
    hard_grace=0.2,
    build=20.0,
    cleanup=5.0,
    drain=1.0,
    poll=0.05,
)


def fast_settings(tmp_path: Path, **overrides: Any) -> BrainSettings:
    settings = BrainSettings(
        perplexity_api_key="test",
        session_dir=str(tmp_path / "sessions"),
        skills_dir=str(tmp_path / "skills"),
        retry_attempts=2,
        retry_initial_delay=0,
        restart_backoff=0.0,
        max_restarts=4,
        approval_timeout=60.0,
        watchdog=FAST,
    )
    return replace(settings, **overrides)


def silent_stream(request: httpx.Request) -> httpx.Response:
    """Perplexity accepted the request and then went silent: no events, no close."""

    async def never() -> AsyncIterator[bytes]:
        await asyncio.Event().wait()
        yield b""

    return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=never())


class Aux:
    """The aux model's endpoint: memory extraction, summaries, and GoalLoop verdicts in order."""

    def __init__(self, verdicts: Sequence[bool] = (), summary: str = "Earlier: the user greeted Rivet.") -> None:
        self.verdicts = list(verdicts)
        self.summary = summary
        self.judged = 0
        self.summaries = 0
        self.fake = FakePerplexity([self._respond] * 1000)

    def _respond(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content or b"{}")
        if any(t.get("name") == "JudgeOutcome" for t in body.get("tools") or []):
            if any(i.get("type") == "function_call_output" for i in body.get("input") or []):
                return httpx.Response(
                    200,
                    headers={"content-type": "text/event-stream"},
                    content=SSE("judged").text("ok").completed().body(),
                )
            self.judged += 1
            passed = self.verdicts.pop(0) if self.verdicts else True
            verdict = {"passed": passed, "feedback": "done" if passed else "not yet"}
            return httpx.Response(
                200,
                headers={"content-type": "text/event-stream"},
                content=SSE("judge").function_call("JudgeOutcome", verdict).completed().body(),
            )
        if "response_format" in body:
            self.judged += 1
            passed = self.verdicts.pop(0) if self.verdicts else True
            text = json.dumps({"passed": passed, "feedback": "done" if passed else "not yet"})
            payload = {
                "id": "resp_judge",
                "object": "response",
                "status": "completed",
                "model": "aux",
                "created_at": 1,
                "output": [
                    {
                        "type": "message",
                        "id": "m",
                        "role": "assistant",
                        "status": "completed",
                        "content": [{"type": "output_text", "text": text}],
                    }
                ],
            }
            return httpx.Response(200, json=payload)
        instructions = str(body.get("instructions") or "")
        if instructions.startswith("Summarize this conversation"):
            self.summaries += 1
            return httpx.Response(
                200,
                headers={"content-type": "text/event-stream"},
                content=SSE("sum").text(self.summary).completed().body(),
            )
        return httpx.Response(
            200, headers={"content-type": "text/event-stream"}, content=SSE("aux").text("[]").completed().body()
        )


def make_build(main: FakePerplexity, aux: Aux, **extra: Any) -> Callable[..., Generation]:
    main_model = build_perplexity_model(api_key="k", model_id="main", transport_factory=main.transport_factory())
    aux_model = build_perplexity_model(
        api_key="k", model_id="aux", native_tools=(), max_steps=1, transport_factory=aux.fake.transport_factory()
    )
    extra.setdefault("require_telnyx", False)

    def build(settings: BrainSettings, **kwargs: Any) -> Generation:
        return build_agent(settings, models=(main_model, aux_model), **extra, **kwargs)

    return build


def make_supervisor(
    tmp_path: Path,
    main: FakePerplexity,
    *,
    aux: Aux | None = None,
    tools: Sequence[Any] = (),
    policies: dict[str, ToolPolicy] | None = None,
    events: list[dict[str, Any]] | None = None,
    lock_dir: str | None = None,
    settings: BrainSettings | None = None,
    **build_extra: Any,
) -> SessionSupervisor:
    settings = settings or fast_settings(tmp_path)
    return SessionSupervisor(
        settings,
        "phone-1",
        backend=LocalFileStorage(settings.session_dir),
        approvals=RivetApprovals(policies),
        tools=tools,
        sink=events.append if events is not None else None,
        build=make_build(main, aux or Aux(), **build_extra),
        lock_dir=lock_dir,
    )


def roles(messages: Sequence[dict[str, Any]]) -> list[str]:
    return [m["role"] for m in messages]


def user_texts(messages: Sequence[dict[str, Any]]) -> list[str]:
    return [b["text"] for m in messages if m["role"] == "user" for b in m["content"] if "text" in b]
