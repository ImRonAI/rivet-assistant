"""Ren's hub: one MCP server that joins the phone and the desktop, for each other and for the agents.

The phone and ren desktop are behind home routers, so neither can be called from outside. Each dials out to the hub
instead and serves its own MCP server over that WebSocket (``/attach/phone``, ``/attach/desktop``, the MCP ``mcp``
subprotocol). The hub is an MCP client on those sockets and an MCP server at ``/mcp`` (Streamable HTTP), built with
FastMCP: each attached side is a ``ProxyProvider`` over its live session, so the hub lists and forwards whatever that
side serves at the moment and nothing is copied or cached.

The phone's tools keep their own names (``sms_send``, ``browser_navigate``), the names the agents' prompts use; the
desktop's are under ``desktop_``. ``hub_peers`` says which sides are attached. Caddy puts the hub at
``https://rivet.ron-ai.dev/hub/`` behind the bearer token; the agents on the VM reach it at ``http://hub:8300/mcp``.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Literal, Unpack, get_args

import anyio
import mcp.types
import uvicorn
from anyio.streams.memory import MemoryObjectReceiveStream, MemoryObjectSendStream
from fastmcp import Client, FastMCP
from fastmcp.client.transports import ClientTransport, FastMCPTransport
from fastmcp.client.transports.base import SessionKwargs
from fastmcp.server.providers.proxy import ProxyProvider
from mcp import ClientSession
from mcp.server.websocket import websocket_server
from mcp.shared.message import SessionMessage
from starlette.applications import Starlette
from starlette.routing import Mount, WebSocketRoute
from starlette.websockets import WebSocket

Peer = Literal["phone", "desktop"]
PEERS: tuple[Peer, ...] = get_args(Peer)

#: How often the hub pings an attached side; a ping that fails means it has gone.
PING_SECONDS = 20.0


class Attached(ClientTransport):
    """An MCP client session over a WebSocket the other side dialed: the hub is the client, the dialer the server."""

    def __init__(
        self,
        read: MemoryObjectReceiveStream[SessionMessage | Exception],
        write: MemoryObjectSendStream[SessionMessage],
    ) -> None:
        self.read = read
        self.write = write

    @asynccontextmanager
    async def connect_session(self, **session_kwargs: Unpack[SessionKwargs]) -> AsyncIterator[ClientSession]:
        async with ClientSession(self.read, self.write, **session_kwargs) as session:
            yield session

    def __repr__(self) -> str:
        return "<Attached>"


hub: FastMCP[None] = FastMCP(
    "ren-hub",
    instructions=(
        "Ren's phone and desktop. Tools without a prefix run on the phone (its phone tools and its browser's "
        "`browser_*` tools); tools under `desktop_` run in ren desktop. Call hub_peers to see which are attached."
    ),
)

#: The live session of each attached side.
attached: dict[Peer, Client[Attached]] = {}

#: What a side that isn't attached serves: nothing.
ABSENT: dict[Peer, FastMCP[None]] = {peer: FastMCP(f"ren-{peer} (not attached)") for peer in PEERS}


def session(peer: Peer) -> Client[Attached] | Client[FastMCPTransport]:
    """The client the proxy uses for [peer]: its live session, or an empty server while it is away."""
    live = attached.get(peer)
    return live if live is not None else Client(ABSENT[peer])


hub.add_provider(ProxyProvider(lambda: session("phone"), cache_ttl=0))
hub.add_provider(ProxyProvider(lambda: session("desktop"), cache_ttl=0), namespace="desktop")


@hub.tool(annotations={"readOnlyHint": True})
def hub_peers() -> dict[Peer, bool]:
    """Which of Ren's sides are attached to the hub right now: the phone and ren desktop."""
    return {peer: peer in attached for peer in PEERS}


async def attach(websocket: WebSocket) -> None:
    """One side attaching: its MCP server is on the other end of this socket until it closes."""
    name = websocket.path_params["peer"]
    peer = next((known for known in PEERS if known == name), None)
    if peer is None:
        await websocket.close(code=mcp.types.INVALID_PARAMS)
        return
    async with websocket_server(websocket.scope, websocket.receive, websocket.send) as (read, write):
        client = Client(Attached(read, write))
        async with client:
            # A side that attaches again (the app restarted) replaces its earlier session.
            attached[peer] = client
            try:
                while await client.ping():
                    await anyio.sleep(PING_SECONDS)
            finally:
                if attached.get(peer) is client:
                    del attached[peer]


mcp_app = hub.http_app(path="/mcp")
app = Starlette(routes=[WebSocketRoute("/attach/{peer}", attach), Mount("/", app=mcp_app)], lifespan=mcp_app.lifespan)

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8300)
