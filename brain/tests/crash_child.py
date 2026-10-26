"""Child process for the kill -9 test: runs a turn that hangs in its second model call.

Usage: python -m tests.crash_child <tmp_dir> <marker_file>
The marker is written when the second model request arrives, after the tool result was saved.
"""

from __future__ import annotations

import asyncio
import sys
from dataclasses import replace
from pathlib import Path

import httpx
from strands import tool

from rivet_brain.approvals import ToolPolicy
from tests.chaos import FAST, fast_settings, make_supervisor, silent_stream
from tests.sse import SSE, FakePerplexity


@tool
def lookup_contact(name: str) -> str:
    """Look up a contact.

    Args:
        name: Name.
    """
    return "+15551234"


async def main(tmp: Path, marker: Path) -> None:
    def second(request: httpx.Request) -> httpx.Response:
        marker.write_text("in-flight")
        return silent_stream(request)

    fake = FakePerplexity([SSE().function_call("lookup_contact", {"name": "Ada"}).completed(), second])
    settings = fast_settings(tmp, watchdog=replace(FAST, first_chunk=300, idle=300))
    supervisor = make_supervisor(
        tmp,
        fake,
        tools=[lookup_contact],
        settings=settings,
        policies={"lookup_contact": ToolPolicy("read", "Look up a contact")},
    )
    await supervisor.start()
    await supervisor.send("what's Ada's number?", turn_id="t1")


if __name__ == "__main__":
    asyncio.run(main(Path(sys.argv[1]), Path(sys.argv[2])))
