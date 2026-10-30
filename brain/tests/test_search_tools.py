"""Emrys's own search tools, the ``search.results`` event, and the search instructions."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

import httpx
from starlette.testclient import TestClient

from emrys_brain import search_tools
from emrys_brain.approvals import EmrysApprovals
from emrys_brain.events import EmrysEventBridge
from emrys_brain.factory import instructions_for
from emrys_brain.gateway import create_app
from emrys_brain.search import BraveSearch, SearchKeys
from tests.chaos import Aux, fast_settings, make_build
from tests.sse import SSE, FakePerplexity
from tests.test_search import KEYS, FakeBrave

TOKEN = "device-token-0123456789abcdef"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
NATIVE = "You can search the web, people, images and finance data."


def _search(keys: SearchKeys = KEYS, fake: FakeBrave | None = None) -> BraveSearch:
    return BraveSearch(keys, transport=httpx.MockTransport(fake or FakeBrave()))


def _run(tool: Any, tool_input: dict[str, Any]) -> dict[str, Any]:
    async def call() -> dict[str, Any]:
        result: dict[str, Any] = await tool._tool_func({"toolUseId": "t1", "name": tool.tool_name, "input": tool_input})
        return result

    return asyncio.run(call())


def test_tools_register_only_with_their_plan_key() -> None:
    assert search_tools.tools_for(None) == []
    everything = {t.tool_name for t in search_tools.tools_for(_search())}
    assert (
        everything
        == search_tools.TOOL_NAMES
        == {
            "search_web",
            "search_news",
            "search_images",
            "search_videos",
            "search_places",
            "search_answer",
            "search_context",
        }
    )
    assert {t.tool_name for t in search_tools.tools_for(_search(SearchKeys(answers="a")))} == {"search_answer"}
    search_only = {t.tool_name for t in search_tools.tools_for(_search(SearchKeys(search="s")))}
    assert search_only == search_tools.TOOL_NAMES - {"search_answer"}
    assert search_tools.tools_for(_search(SearchKeys(spellcheck="s", suggest="g"))) == []


def test_every_search_is_a_read_that_taints_the_turn() -> None:
    for name, policy in search_tools.POLICIES.items():
        assert policy.tier == "read" and policy.untrusted_output and policy.summary, name
    approvals = EmrysApprovals(search_tools.POLICIES)
    assessment = approvals.assess("search_web", {"q": "x"})
    assert assessment.tier == "read"


def test_a_result_is_the_envelope_as_a_json_block_and_an_error_is_plain_text() -> None:
    fake = FakeBrave()
    tools = {t.tool_name: t for t in search_tools.tools_for(_search(fake=fake))}
    result = _run(tools["search_news"], {"q": "nasa", "count": 3, "freshness": "pw"})
    assert result["toolUseId"] == "t1" and result["status"] == "success"
    envelope = result["content"][0]["json"]
    assert envelope["kind"] == "news" and envelope["items"][0]["breaking"] is True
    assert dict(fake.last.url.params) == {"q": "nasa", "count": "3", "freshness": "pw"}

    _run(tools["search_places"], {"q": "coffee", "location": "austin, tx, united states"})
    assert fake.last.url.params["location"] == "austin tx united states"

    assert _run(tools["search_web"], {}) == {
        "toolUseId": "t1",
        "status": "error",
        "content": [{"text": "Type something to search for."}],
    }
    fake.respond = lambda request: httpx.Response(429, json={})
    failed = _run(tools["search_answer"], {"q": "why"})
    assert failed["status"] == "error" and failed["content"] == [{"text": "Search is busy. Try again in a moment."}]


def _tool_result(tool_id: str, status: str, content: list[dict[str, Any]]) -> dict[str, Any]:
    return {"role": "user", "content": [{"toolResult": {"toolUseId": tool_id, "status": status, "content": content}}]}


def test_the_bridge_sends_search_results_after_tool_done() -> None:
    events: list[dict[str, Any]] = []
    bridge = EmrysEventBridge(events.append)
    envelope = {"kind": "web", "query": "q", "altered": None, "items": [], "answer": None, "center": None}
    bridge(current_tool_use={"toolUseId": "s1", "name": "search_web"})
    bridge(current_tool_use={"toolUseId": "s2", "name": "search_news"})
    bridge(current_tool_use={"toolUseId": "e1", "name": "email_search"})
    bridge(message=_tool_result("s1", "success", [{"json": envelope}]))
    assert [e["type"] for e in events] == ["tool", "tool", "tool", "tool_done", "search.results"]
    assert events[-1] == {"type": "search.results", "tool_id": "s1", "results": envelope}

    events.clear()
    bridge(message=_tool_result("s2", "error", [{"text": "Search is busy."}]))
    bridge(message=_tool_result("e1", "success", [{"json": envelope}]))
    bridge(message=_tool_result("s2", "success", [{"json": {"kind": "maps"}}]))
    bridge(message=_tool_result("s2", "success", [{"text": "offloaded preview"}]))
    bridge(message=_tool_result("unknown", "success", [{"json": envelope}]))
    assert [e["type"] for e in events] == ["tool_done"] * 5


def test_instructions_name_search_only_with_the_tools_and_native_search_only_on_perplexity() -> None:
    plain = instructions_for({"sms_send"})
    assert search_tools.INSTRUCTIONS not in plain and NATIVE in plain
    with_search = instructions_for({"sms_send", "search_web"})
    assert search_tools.INSTRUCTIONS in with_search and NATIVE in with_search
    on_groq = instructions_for({"sms_send", "search_web"}, native_search=False)
    assert search_tools.INSTRUCTIONS in on_groq and NATIVE not in on_groq


def test_a_brain_search_reaches_the_phone_as_cards(tmp_path: Path) -> None:
    main = FakePerplexity(
        [
            SSE().function_call("search_videos", {"q": "bike tire"}).completed(),
            SSE("r2").text("Card 1 is a good one.").completed(),
        ]
    )
    search = _search()
    app = create_app(
        fast_settings(tmp_path),
        build=make_build(main, Aux()),
        device_token=TOKEN,
        tools_for=lambda bridge: search_tools.tools_for(search),
        approvals=EmrysApprovals(search_tools.POLICIES),
        search=search,
    )
    with TestClient(app) as client, client.websocket_connect("/v1/sessions/phone-1", headers=AUTH) as socket:
        socket.send_json({"type": "turn", "turn_id": "t1", "text": "find me a bike tire video"})
        seen: list[dict[str, Any]] = []
        while not seen or seen[-1]["type"] != "turn.completed":
            seen.append(socket.receive_json())
    kinds = [e["type"] for e in seen]
    assert "approval.request" not in kinds
    cards = next(e for e in seen if e["type"] == "search.results")
    assert kinds.index("search.results") == kinds.index("tool_done") + 1
    assert cards["lane"] == "default" and cards["tool_id"] == "call_1"
    assert cards["results"]["kind"] == "videos" and cards["results"]["items"][0]["youtube_id"] == "abc_DEF-123"
    output = next(i for i in main.requests[1]["input"] if i.get("type") == "function_call_output")
    assert "abc_DEF-123" in str(output)
    assert "You can search the web" in main.requests[0]["instructions"]
