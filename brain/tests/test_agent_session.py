from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

import httpx
import pytest
from starlette.testclient import TestClient

from emrys_brain.gateway import create_app
from tests.chaos import Aux, fast_settings, make_build
from tests.sse import FakePerplexity

TOKEN = "device-token-0123456789abcdef"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
KEY = "xi-test-key-not-real"
VOICE_AGENT = "agent_2901m620nzwrev2r7ekqpydcxe25"
TEXT_AGENT = "agent_9601m623qa72ffs9cyy3ezc0p6tb"


class FakeElevenLabs:
    """Answers the two ElevenLabs credential endpoints and records what it was asked."""

    def __init__(self, respond: Callable[[httpx.Request], httpx.Response] | None = None) -> None:
        self.requests: list[httpx.Request] = []
        self.respond = respond or self.issue

    def issue(self, request: httpx.Request) -> httpx.Response:
        if request.headers.get("xi-api-key") != KEY:
            return httpx.Response(401, json={"detail": "invalid api key"})
        agent = request.url.params["agent_id"]
        if request.url.path == "/v1/convai/conversation/token":
            return httpx.Response(200, json={"token": f"lk-{agent}", "conversation_id": "conv_1"})
        if request.url.path == "/v1/convai/conversation/get-signed-url":
            return httpx.Response(
                200,
                json={
                    "signed_url": f"wss://api.elevenlabs.io/v1/convai/conversation?agent_id={agent}&conversation_signature=s"
                },
            )
        return httpx.Response(404)

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return self.respond(request)


def _client(tmp_path: Path, upstream: FakeElevenLabs) -> TestClient:
    app = create_app(
        fast_settings(tmp_path),
        build=make_build(FakePerplexity([]), Aux()),
        device_token=TOKEN,
        elevenlabs_transport=httpx.MockTransport(upstream),
    )
    return TestClient(app)


@pytest.fixture(autouse=True)
def _env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ELEVENLABS_API_KEY", KEY)
    monkeypatch.delenv("EMRYS_ELEVENLABS_VOICE_AGENT_ID", raising=False)
    monkeypatch.delenv("EMRYS_ELEVENLABS_TEXT_AGENT_ID", raising=False)


def test_voice_gets_a_conversation_token_for_the_voice_agent(tmp_path: Path) -> None:
    upstream = FakeElevenLabs()
    with _client(tmp_path, upstream) as client:
        response = client.get("/v1/agent/session", params={"mode": "voice"}, headers=AUTH)
    assert response.status_code == 200
    assert response.json() == {"mode": "voice", "agent_id": VOICE_AGENT, "conversation_token": f"lk-{VOICE_AGENT}"}
    assert response.headers["cache-control"] == "no-store"
    assert [r.url.path for r in upstream.requests] == ["/v1/convai/conversation/token"]
    assert upstream.requests[0].method == "GET" and str(upstream.requests[0].url).startswith(
        "https://api.elevenlabs.io/"
    )
    assert KEY not in response.text


def test_text_gets_a_signed_url_for_the_text_agent(tmp_path: Path) -> None:
    upstream = FakeElevenLabs()
    with _client(tmp_path, upstream) as client:
        body = client.get("/v1/agent/session", params={"mode": "text"}, headers=AUTH).json()
    assert body["mode"] == "text" and body["agent_id"] == TEXT_AGENT
    assert body["signed_url"].startswith("wss://") and f"agent_id={TEXT_AGENT}" in body["signed_url"]
    assert [r.url.path for r in upstream.requests] == ["/v1/convai/conversation/get-signed-url"]


def test_agent_ids_can_be_overridden(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("EMRYS_ELEVENLABS_VOICE_AGENT_ID", "agent_other")
    with _client(tmp_path, FakeElevenLabs()) as client:
        body = client.get("/v1/agent/session", params={"mode": "voice"}, headers=AUTH).json()
    assert body["agent_id"] == "agent_other" and body["conversation_token"] == "lk-agent_other"


def test_the_device_token_is_required(tmp_path: Path) -> None:
    upstream = FakeElevenLabs()
    with _client(tmp_path, upstream) as client:
        assert client.get("/v1/agent/session", params={"mode": "voice"}).status_code == 401
        wrong = {"Authorization": "Bearer wrong"}
        assert client.get("/v1/agent/session", params={"mode": "voice"}, headers=wrong).status_code == 401
    assert upstream.requests == []


def test_a_bad_mode_is_refused(tmp_path: Path) -> None:
    upstream = FakeElevenLabs()
    with _client(tmp_path, upstream) as client:
        for params in ({"mode": "video"}, {}):
            response = client.get("/v1/agent/session", params=params, headers=AUTH)
            assert response.status_code == 400 and response.json() == {"error": "mode must be voice or text"}
    assert upstream.requests == []


def test_no_key_means_503(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ELEVENLABS_API_KEY")
    upstream = FakeElevenLabs()
    with _client(tmp_path, upstream) as client:
        response = client.get("/v1/agent/session", params={"mode": "voice"}, headers=AUTH)
    assert response.status_code == 503 and "not set up" in response.json()["error"]
    assert upstream.requests == []


@pytest.mark.parametrize(
    "respond",
    [
        lambda request: httpx.Response(401, json={"detail": "invalid api key"}),
        lambda request: httpx.Response(500, text="boom"),
        lambda request: httpx.Response(200, text="not json"),
        lambda request: httpx.Response(200, json={"conversation_id": "conv_1"}),
        lambda request: httpx.Response(200, json={"token": ""}),
        lambda request: (_ for _ in ()).throw(httpx.ConnectError("down")),
    ],
)
def test_upstream_failures_are_502_without_the_key(
    tmp_path: Path, respond: Callable[[httpx.Request], httpx.Response], caplog: pytest.LogCaptureFixture
) -> None:
    with _client(tmp_path, FakeElevenLabs(respond)) as client:
        response = client.get("/v1/agent/session", params={"mode": "voice"}, headers=AUTH)
    assert response.status_code == 502 and response.json() == {"error": "ElevenLabs did not issue a session"}
    assert KEY not in response.text and KEY not in caplog.text
