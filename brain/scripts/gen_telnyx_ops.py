"""Dump the Telnyx MCP catalog (name -> HTTP method/path) exactly as OubliTelnyxMCP builds it.

Mirrors `oubliai_server.server.build_operations` minus the CodeMode transform, so every tool that
`execute(call_tool(...))` can reach is listed with its route.
Usage: python -I gen_telnyx_ops.py <openapi.json> <out.json>
"""

import asyncio
import hashlib
import json
import sys
from pathlib import Path

from fastmcp import FastMCP
from fastmcp.server.providers.openapi import MCPType, OpenAPIProvider, RouteMap
from fastmcp.server.providers.openapi.components import OpenAPITool
from oubliai_server.apps import ALL_APPS
from oubliai_server.runtime.http import make_telnyx_client
from oubliai_server.runtime.tasks import register_task_tools
from oubliai_server.server import OAUTH_PROTOCOL_TAG
from oubliai_server.spec import collision_names, default_base_url, foreign_server_routes


async def main(spec_path: Path, out_path: Path) -> None:
    raw = spec_path.read_bytes()
    spec = json.loads(raw)
    excluded = foreign_server_routes(spec)

    def exclude_foreign(route, mcp_type):
        return MCPType.EXCLUDE if (route.method.upper(), route.path) in excluded else None

    server = FastMCP("dump")
    server.add_provider(
        OpenAPIProvider(
            openapi_spec=spec,
            client=make_telnyx_client(default_base_url(spec)),
            route_maps=[RouteMap(tags={OAUTH_PROTOCOL_TAG}, mcp_type=MCPType.EXCLUDE)],
            route_map_fn=exclude_foreign,
            mcp_names=collision_names(spec),
            tags={"telnyx"},
        )
    )
    for app in ALL_APPS:
        server.add_provider(app)
    register_task_tools(server)

    operations: dict[str, dict] = {}
    helpers: dict[str, dict] = {}
    for tool in await server.list_tools():
        if isinstance(tool, OpenAPITool):
            route = tool._route
            operations[tool.name] = {
                "method": route.method.upper(),
                "path": route.path,
                "summary": route.summary or "",
                "tags": sorted(route.tags or []),
            }
            continue
        ui = (tool.meta or {}).get("ui") or {}
        helpers[tool.name] = {
            "visibility": ui.get("visibility"),
            "task": bool(tool.task_config and tool.task_config.mode != "forbidden"),
        }
    out = {
        "source": "OubliTelnyxMCP docs/reference/telnyx/openapi.json",
        "sha256": hashlib.sha256(raw).hexdigest(),
        "operations": dict(sorted(operations.items())),
        "helpers": dict(sorted(helpers.items())),
    }
    out_path.write_text(json.dumps(out, indent=0, sort_keys=False) + "\n")
    methods: dict[str, int] = {}
    for op in operations.values():
        methods[op["method"]] = methods.get(op["method"], 0) + 1
    print(len(operations), "operations", methods, "|", len(helpers), "helpers")


asyncio.run(main(Path(sys.argv[1]), Path(sys.argv[2])))
