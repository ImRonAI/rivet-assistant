"""A local stand-in for the Telnyx MCP server on Prefect Horizon.

It serves the same six tool names over streamable HTTP and rejects requests without the expected bearer,
like Horizon does. Executed code is recorded; code containing ``HANG`` blocks until released, for
watchdog tests.
"""

from __future__ import annotations

import asyncio
import socket
import threading
import time
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any

import uvicorn
from mcp.server.mcpserver import MCPServer

INSTRUCTIONS = "Telnyx operations live behind search/get_schema/execute. Use literal tool names."


@dataclass
class FakeTelnyx:
    url: str
    token: str
    executed: list[str] = field(default_factory=list)
    unauthorized: int = 0
    release: threading.Event = field(default_factory=threading.Event)


def _server(state: FakeTelnyx) -> MCPServer:
    server = MCPServer("Oubliai Telnyx", instructions=INSTRUCTIONS)

    @server.tool()
    def search(query: str, tags: list[str] | None = None) -> str:
        """Search the Telnyx catalog."""
        return "ListPhoneNumbers: List phone numbers\nSendMessage: Send a message"

    @server.tool()
    def get_schema(tools: list[str]) -> str:
        """Get tool schemas."""
        return "{}"

    @server.tool()
    async def execute(code: str) -> str:
        """Execute tool calls using Python code."""
        state.executed.append(code)
        while "HANG" in code and not state.release.is_set():
            await asyncio.sleep(0.05)
        return '[{"phone_number": "+18015550100"}]'

    @server.tool()
    def tags() -> str:
        """List tags."""
        return "Messages\nPhone Number Configurations"

    @server.tool()
    def generate_prefab_ui(code: str) -> str:
        """Generate a UI."""
        return "{}"

    @server.tool()
    def search_prefab_components(query: str) -> str:
        """Search UI components."""
        return "[]"

    return server


def _require_bearer(app: Any, state: FakeTelnyx) -> Any:
    expected = f"Bearer {state.token}".encode()

    async def guarded(scope: dict[str, Any], receive: Any, send: Any) -> None:
        if scope["type"] == "http" and dict(scope["headers"]).get(b"authorization") != expected:
            state.unauthorized += 1
            await send({"type": "http.response.start", "status": 401, "headers": [(b"content-type", b"text/plain")]})
            await send({"type": "http.response.body", "body": b"unauthorized"})
            return
        await app(scope, receive, send)

    return guarded


class FakeTelnyxServer:
    """The fake server on a fixed local port; ``stop()`` and ``start()`` simulate a dropped connection."""

    def __init__(self, token: str = "horizon-test-key") -> None:
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            self.port = probe.getsockname()[1]
        self.state = FakeTelnyx(url=f"http://127.0.0.1:{self.port}/mcp", token=token)
        self._server: uvicorn.Server | None = None
        self._thread: threading.Thread | None = None

    def start(self) -> FakeTelnyx:
        """Serve on the port (a fresh MCP app, so no session survives a restart)."""
        app = _require_bearer(_server(self.state).streamable_http_app(), self.state)
        self._server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=self.port, log_level="warning"))
        self._thread = threading.Thread(target=self._server.run, daemon=True)
        self._thread.start()
        deadline = time.monotonic() + 10
        while not self._server.started:
            if time.monotonic() > deadline:
                raise RuntimeError("fake Telnyx MCP server did not start")
            time.sleep(0.02)
        return self.state

    def stop(self) -> None:
        """Shut the server down and close its connections."""
        if self._server is None or self._thread is None:
            return
        self._server.should_exit = True
        self._server.force_exit = True
        self._thread.join(timeout=10)
        self._server = self._thread = None


@contextmanager
def serve_fake_telnyx(token: str = "horizon-test-key") -> Iterator[FakeTelnyx]:
    """Run the fake server on a free local port for the duration of the block."""
    server = FakeTelnyxServer(token)
    state = server.start()
    try:
        yield state
    finally:
        state.release.set()
        server.stop()
