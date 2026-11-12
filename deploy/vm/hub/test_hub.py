"""The hub end to end, in one process and over real sockets: sides attach by WebSocket, a client calls them over HTTP."""

from __future__ import annotations

import socket
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import anyio
import pytest
import uvicorn
from fastmcp import Client, FastMCP
from mcp.client.websocket import websocket_client

import app as hub_app


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port: int = probe.getsockname()[1]
        return port


@asynccontextmanager
async def running_hub() -> AsyncIterator[int]:
    """The hub's real ASGI app on a local port."""
    port = free_port()
    server = uvicorn.Server(uvicorn.Config(hub_app.app, host="127.0.0.1", port=port, log_level="warning"))
    async with anyio.create_task_group() as tasks:
        tasks.start_soon(server.serve)
        while not server.started:
            await anyio.sleep(0.02)
        try:
            yield port
        finally:
            server.should_exit = True


@asynccontextmanager
async def attached(port: int, peer: str, side: FastMCP[None]) -> AsyncIterator[None]:
    """[side] dials the hub and serves its MCP server over that socket, the way the phone and ren desktop do."""
    async with anyio.create_task_group() as tasks:

        async def serve() -> None:
            async with websocket_client(f"ws://127.0.0.1:{port}/attach/{peer}") as (read, write):
                await side._mcp_server.run(read, write, side._mcp_server.create_initialization_options())

        tasks.start_soon(serve)
        with anyio.fail_after(5):
            while peer not in hub_app.attached:
                await anyio.sleep(0.02)
        try:
            yield
        finally:
            tasks.cancel_scope.cancel()


def phone() -> FastMCP[None]:
    side: FastMCP[None] = FastMCP("ren-phone")

    @side.tool
    def device_status() -> dict[str, int]:
        """Battery level."""
        return {"battery": 81}

    return side


def desktop() -> FastMCP[None]:
    side: FastMCP[None] = FastMCP("ren-desktop")

    @side.tool
    def browser_open(url: str) -> str:
        """Open a tab."""
        return f"opened {url}"

    return side


async def names(port: int) -> set[str]:
    async with Client(f"http://127.0.0.1:{port}/mcp") as client:
        return {tool.name for tool in await client.list_tools()}


@pytest.mark.anyio
async def test_attached_sides_are_listed_and_called_through_the_hub(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(hub_app, "PING_SECONDS", 0.1)
    async with running_hub() as port:
        assert await names(port) == {"hub_peers"}

        async with attached(port, "phone", phone()), attached(port, "desktop", desktop()):
            # The phone's tools keep their names; the desktop's are under desktop_.
            assert await names(port) == {"hub_peers", "device_status", "desktop_browser_open"}
            async with Client(f"http://127.0.0.1:{port}/mcp") as client:
                assert (await client.call_tool("hub_peers")).data == {"phone": True, "desktop": True}
                assert (await client.call_tool("device_status")).data == {"battery": 81}
                opened = await client.call_tool("desktop_browser_open", {"url": "https://example.com"})
                assert opened.data == "opened https://example.com"

        # A side that goes away takes its tools with it.
        with anyio.fail_after(5):
            while hub_app.attached:
                await anyio.sleep(0.05)
        assert await names(port) == {"hub_peers"}


@pytest.mark.anyio
async def test_a_side_that_attaches_again_replaces_its_earlier_session(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(hub_app, "PING_SECONDS", 0.1)
    async with running_hub() as port:
        async with attached(port, "phone", phone()):
            first = hub_app.attached["phone"]
            second_side: FastMCP[None] = FastMCP("ren-phone")

            @second_side.tool
            def sms_list() -> list[str]:
                """Texts."""
                return []

            async with anyio.create_task_group() as tasks:

                async def serve() -> None:
                    async with websocket_client(f"ws://127.0.0.1:{port}/attach/phone") as (read, write):
                        await second_side._mcp_server.run(read, write, second_side._mcp_server.create_initialization_options())

                tasks.start_soon(serve)
                with anyio.fail_after(5):
                    while hub_app.attached.get("phone") is first:
                        await anyio.sleep(0.02)
                assert await names(port) == {"hub_peers", "sms_list"}
                tasks.cancel_scope.cancel()


@pytest.mark.anyio
async def test_an_unknown_side_is_refused() -> None:
    async with running_hub() as port:
        with pytest.raises(Exception):
            async with websocket_client(f"ws://127.0.0.1:{port}/attach/laptop") as (read, _write):
                async for _message in read:
                    pass
        assert hub_app.attached == {}


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"
