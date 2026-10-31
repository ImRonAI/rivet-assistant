"""Serves Desktop Commander (stdio only) to the ElevenLabs agents over streamable HTTP with FastMCP's proxy.

The proxy holds one connected Desktop Commander, so processes it starts outlive a single request.
"""

import asyncio
import os

from fastmcp import Client
from fastmcp.server import create_proxy

DESKTOP_COMMANDER = {
    "mcpServers": {
        "desktop-commander": {
            "command": "npx",
            "args": ["-y", "@wonderwhy-er/desktop-commander@0.3.1"],
            # MCP stdio passes only a few variables through: npx needs the image's cache, OpenCode the Mistral key.
            "env": {"npm_config_cache": os.environ["npm_config_cache"], "MISTRAL_API_KEY": os.environ["MISTRAL_API_KEY"]},
        }
    }
}


async def main() -> None:
    async with Client(DESKTOP_COMMANDER) as connected:
        proxy = create_proxy(connected, name="desktop-commander")
        await proxy.run_http_async(show_banner=False, host="0.0.0.0", port=8080, path="/computer/mcp")


asyncio.run(main())
