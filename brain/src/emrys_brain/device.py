"""Brain-side proxies for tools that run on the phone (SMS, notifications, contacts, calendar, apps).

A device tool sends ``device.call`` over the session's WebSocket and waits for the matching
``device.result``. The call's ``action_id`` is the model's tool use id, so the phone's executed-actions
ledger can refuse to run the same action twice, which makes re-sending after a reconnect safe.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable, Mapping
from typing import Any

from strands.tools import PythonAgentTool

logger = logging.getLogger(__name__)

Sender = Callable[[dict[str, Any]], Awaitable[None]]


class DeviceUnavailableError(RuntimeError):
    """No phone is connected to the session."""


class DeviceBridge:
    """Routes device tool calls to the connected phone and hands results back to the waiting tool."""

    def __init__(self, session_id: str = "") -> None:
        self.session_id = session_id
        self._send: Sender | None = None
        self._pending: dict[str, tuple[asyncio.Future[dict[str, Any]], dict[str, Any]]] = {}

    @property
    def connected(self) -> bool:
        """Whether a phone is attached."""
        return self._send is not None

    async def attach(self, send: Sender) -> None:
        """Use ``send`` for device calls and re-send calls still waiting (the phone's ledger dedups them)."""
        self._send = send
        for _, message in list(self._pending.values()):
            await send(message)

    def detach(self, send: Sender | None = None) -> None:
        """Forget the phone (only if ``send`` is still the attached one)."""
        if send is None or self._send is send:
            self._send = None

    async def call(self, tool: str, tool_input: Any, action_id: str, timeout: float) -> dict[str, Any]:
        """Run ``tool`` on the phone and return its result payload."""
        if self._send is None:
            raise DeviceUnavailableError("the phone is not connected")
        future: asyncio.Future[dict[str, Any]] = asyncio.get_running_loop().create_future()
        message = {"type": "device.call", "action_id": action_id, "tool": tool, "input": tool_input}
        self._pending[action_id] = (future, message)
        try:
            await self._send(message)
            return await asyncio.wait_for(future, timeout)
        finally:
            self._pending.pop(action_id, None)

    def resolve(self, action_id: str, result: Mapping[str, Any]) -> bool:
        """Deliver a ``device.result``; returns False when nothing waits for it (late or duplicate)."""
        entry = self._pending.get(action_id)
        if entry is None or entry[0].done():
            return False
        entry[0].set_result(dict(result))
        return True


def device_tool(
    bridge: DeviceBridge, name: str, description: str, input_schema: dict[str, Any], *, timeout: float = 60.0
) -> PythonAgentTool:
    """A tool the model calls like any other, executed by the phone.

    The phone answers ``{"ok": bool, "content": [...] | "data": ...}``; ``content`` is passed through as
    tool result content, otherwise ``data`` is sent as JSON text.
    """

    async def run(tool_use: Any, **_: Any) -> Any:
        tool_use_id = tool_use["toolUseId"]
        try:
            result = await bridge.call(name, tool_use.get("input") or {}, tool_use_id, timeout)
        except DeviceUnavailableError:
            return _error(tool_use_id, "The phone is not connected, so this did not run. Try again when it is.")
        except TimeoutError:
            return _error(
                tool_use_id,
                f"The phone did not answer within {timeout:.0f}s. The action may or may "
                "not have run; check before retrying.",
            )
        content = result.get("content")
        if not isinstance(content, list) or not content:
            content = [{"text": json.dumps(result.get("data"), ensure_ascii=False, default=str)}]
        return {
            "toolUseId": tool_use_id,
            "status": "success" if result.get("ok", True) else "error",
            "content": content,
        }

    spec = {"name": name, "description": description, "inputSchema": {"json": input_schema}}
    return PythonAgentTool(name, spec, run)  # type: ignore[arg-type]


def _error(tool_use_id: str, text: str) -> dict[str, Any]:
    return {"toolUseId": tool_use_id, "status": "error", "content": [{"text": text}]}
