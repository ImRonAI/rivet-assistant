"""The Brave search proxy: mapping, parameters, errors and privacy.

Fixtures are synthetic, built from the examples and field tables in Brave's API reference; Brave forbids keeping
real results.
"""

from __future__ import annotations

import json
import logging
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import httpx
import pytest
from starlette.testclient import TestClient

from rivet_brain.gateway import configure_logging, create_app
from rivet_brain.search import BraveSearch, SearchKeys
from tests.chaos import Aux, fast_settings, make_build
from tests.sse import FakePerplexity

TOKEN = "device-token-0123456789abcdef"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
KEYS = SearchKeys(search="search-key-1", answers="answers-key-2", spellcheck="spell-key-3", suggest="suggest-key-4")

WEB = {
    "type": "search",
    "query": {"original": "golden gate bridge history", "altered": "golden gate bridge history facts"},
    "web": {
        "type": "search",
        "results": [
            {
                "title": "Golden Gate Bridge &amp; its history",
                "url": "https://example.com/ggb",
                "description": "Opened in 1937 &mdash; a suspension bridge.",
                "age": "2 days ago",
                "profile": {"name": "Example"},
                "meta_url": {"hostname": "example.com", "favicon": "https://imgs.example/fav.png"},
                "thumbnail": {"src": "https://imgs.example/t.jpg", "original": "https://example.com/o.jpg"},
                "language": "en",
                "unknown_field": {"nested": True},
            },
            {"title": "Only a hostname", "url": "https://other.org/a", "meta_url": {"hostname": "other.org"}},
            {"url": "https://example.com/no-title"},
        ],
    },
}
NEWS = {
    "type": "news",
    "query": {"original": "nasa"},
    "results": [
        {
            "type": "news_result",
            "title": "Launch today",
            "url": "https://news.example/launch",
            "description": "Liftoff.",
            "age": "1 hour ago",
            "breaking": True,
            "meta_url": {"hostname": "news.example"},
        },
        {"title": "Older story", "url": "https://news.example/old"},
    ],
}
IMAGES = {
    "type": "images",
    "query": {"original": "golden gate bridge"},
    "results": [
        {
            "type": "image_result",
            "title": "Bridge at dusk",
            "url": "https://photos.example/page",
            "source": "photos.example",
            "thumbnail": {"src": "https://imgs.example/thumb.jpg", "width": 500, "height": 300},
            "properties": {"url": "https://photos.example/full.jpg", "width": 4000, "height": 2400},
            "confidence": "high",
        },
        {
            "title": "No properties",
            "url": "https://photos.example/other",
            "thumbnail": {"src": "https://imgs.example/t2.jpg", "width": 500, "height": 500},
        },
        {"title": "No thumbnail", "url": "https://photos.example/none"},
    ],
    "extra": {"might_be_offensive": False},
}
VIDEOS = {
    "type": "videos",
    "query": {"original": "change a bike tire"},
    "results": [
        {
            "type": "video_result",
            "title": "How to change a tire",
            "url": "https://www.youtube.com/watch?v=abc_DEF-123",
            "description": "Step by step.",
            "age": "3 years ago",
            "thumbnail": {"src": "https://imgs.example/v.jpg"},
            "video": {"duration": "05:12", "views": 120345, "creator": "Bike Shop", "publisher": "YouTube"},
        },
        {"title": "Short link", "url": "https://youtu.be/abc_DEF-123"},
        {"title": "Shorts", "url": "https://www.youtube.com/shorts/abc"},
        {"title": "Bad id", "url": "https://www.youtube.com/watch?v=abc%20def"},
        {"title": "Other host", "url": "https://vimeo.com/123"},
    ],
}
PLACES = {
    "type": "locations",
    "query": {"original": "coffee"},
    "location": {"coordinates": [37.7749, -122.4194], "name": "San Francisco", "country": "US"},
    "results": [
        {
            "type": "location_result",
            "id": "poi-1",
            "title": "Blue Cup",
            "url": "https://maps.example/blue-cup",
            "provider_url": "https://maps.example/blue-cup",
            "coordinates": [37.78, -122.41],
            "postal_address": {"type": "PostalAddress", "displayAddress": "1 Market St, San Francisco, CA"},
            "contact": {"telephone": "+1 415 555 0100"},
            "rating": {"ratingValue": 4.5, "bestRating": 5, "reviewCount": 120},
            "price_range": "$$",
            "categories": ["Cafe", 7],
            "serves_cuisine": ["Coffee", "Cafe"],
            "pictures": {"results": [{"src": "https://imgs.example/p1.jpg"}]},
            "opening_hours": {
                "current_day": [
                    {"abbr_name": "Wed", "full_name": "Wednesday", "opens": "07:00", "closes": "12:00"},
                    {"abbr_name": "Wed", "full_name": "Wednesday", "opens": "13:00", "closes": "19:00"},
                ]
            },
            "timezone": "America/Los_Angeles",
            "distance": {"value": 1.2, "units": "km"},
        },
        {"title": "No coordinates", "url": "https://maps.example/x"},
    ],
}
CONTEXT = {
    "grounding": {
        "generic": [
            {"url": "https://docs.example/py", "title": "Python history", "snippets": [f"s{i}" for i in range(7)]},
            {"url": "https://docs.example/nometa", "title": "No metadata", "snippets": ["x" * 1500]},
            {"title": "No url"},
        ],
        "map": [],
    },
    "sources": {
        "https://docs.example/py": {
            "title": "Python history",
            "hostname": "docs.example",
            "site_name": "Docs",
            "favicon": "https://imgs.example/f.png",
            "thumbnail": {"src": "https://imgs.example/c.jpg"},
            "age": ["Monday, January 1, 2024", "2024-01-01", "2 years ago", "2024-01-01T00:00:00"],
        },
        "https://docs.example/nometa": {"hostname": "docs.example", "age": []},
    },
}
ANSWER = {
    "id": "a1",
    "object": "chat.completion",
    "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "K2 &amp; more."}}],
    "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
}
SUGGEST = {
    "type": "suggest",
    "query": {"original": "albert"},
    "results": [{"query": "albert einstein"}, {"query": "albertsons"}, {"query": "albert einstein"}, {}],
}
SPELLCHECK = {"type": "spellcheck", "query": {"original": "artifical"}, "results": [{"query": "artificial"}]}
FIXTURES = {
    "/res/v1/web/search": WEB,
    "/res/v1/news/search": NEWS,
    "/res/v1/images/search": IMAGES,
    "/res/v1/videos/search": VIDEOS,
    "/res/v1/local/place_search": PLACES,
    "/res/v1/llm/context": CONTEXT,
    "/res/v1/chat/completions": ANSWER,
    "/res/v1/suggest/search": SUGGEST,
    "/res/v1/spellcheck/search": SPELLCHECK,
}


class FakeBrave:
    """Answers each Brave path with its fixture (or ``respond``) and records the requests."""

    def __init__(self, respond: Callable[[httpx.Request], httpx.Response] | None = None) -> None:
        self.respond = respond
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if self.respond is not None:
            return self.respond(request)
        return httpx.Response(200, json=FIXTURES[request.url.path])

    @property
    def last(self) -> httpx.Request:
        return self.requests[-1]


@pytest.fixture
def brave() -> FakeBrave:
    return FakeBrave()


@pytest.fixture
def client(tmp_path: Path, brave: FakeBrave) -> Iterator[TestClient]:
    with _client(tmp_path, BraveSearch(KEYS, transport=httpx.MockTransport(brave))) as test_client:
        yield test_client


def _client(tmp_path: Path, search: BraveSearch | None) -> TestClient:
    app = create_app(
        fast_settings(tmp_path), build=make_build(FakePerplexity([]), Aux()), device_token=TOKEN, search=search
    )
    return TestClient(app)


def _get(client: TestClient, path: str, **params: Any) -> httpx.Response:
    response = client.get(path, params=params, headers=AUTH)
    assert response.headers["cache-control"] == "no-store"
    return response


def test_web_maps_every_field_and_drops_items_without_a_title(client: TestClient, brave: FakeBrave) -> None:
    body = _get(client, "/v1/search/web", q="  golden gate bridge history  ").json()
    assert body["kind"] == "web" and body["query"] == "golden gate bridge history"
    assert body["altered"] == "golden gate bridge history facts" and body["answer"] is None and body["center"] is None
    assert body["items"] == [
        {
            "title": "Golden Gate Bridge & its history",
            "url": "https://example.com/ggb",
            "description": "Opened in 1937 — a suspension bridge.",
            "site": "Example",
            "favicon": "https://imgs.example/fav.png",
            "thumbnail": "https://imgs.example/t.jpg",
            "age": "2 days ago",
            "breaking": False,
        },
        {
            "title": "Only a hostname",
            "url": "https://other.org/a",
            "description": None,
            "site": "other.org",
            "favicon": None,
            "thumbnail": None,
            "age": None,
            "breaking": False,
        },
    ]
    request = brave.last
    assert request.url.host == "api.search.brave.com" and request.method == "GET"
    assert request.headers["x-subscription-token"] == "search-key-1"
    assert request.headers["accept"] == "application/json"
    assert dict(request.url.params) == {"q": "golden gate bridge history", "count": "8", "text_decorations": "false"}


def test_news_keeps_breaking(client: TestClient, brave: FakeBrave) -> None:
    body = _get(client, "/v1/search/news", q="nasa", freshness="pd", count=50).json()
    assert [(i["title"], i["breaking"], i["site"]) for i in body["items"]] == [
        ("Launch today", True, "news.example"),
        ("Older story", False, None),
    ]
    assert dict(brave.last.url.params) == {"q": "nasa", "count": "20", "freshness": "pd"}


def test_images_use_the_full_size_file_and_fall_back_to_the_thumbnail(client: TestClient, brave: FakeBrave) -> None:
    body = _get(client, "/v1/search/images", q="golden gate bridge", count=0).json()
    assert body["items"] == [
        {
            "title": "Bridge at dusk",
            "url": "https://photos.example/page",
            "image": "https://photos.example/full.jpg",
            "thumbnail": "https://imgs.example/thumb.jpg",
            "width": 4000,
            "height": 2400,
            "source": "photos.example",
        },
        {
            "title": "No properties",
            "url": "https://photos.example/other",
            "image": "https://imgs.example/t2.jpg",
            "thumbnail": "https://imgs.example/t2.jpg",
            "width": 500,
            "height": 500,
            "source": None,
        },
    ]
    assert dict(brave.last.url.params) == {"q": "golden gate bridge", "count": "1"}


def test_videos_get_a_youtube_id_only_for_watch_urls(client: TestClient, brave: FakeBrave) -> None:
    body = _get(client, "/v1/search/videos", q="change a bike tire").json()
    first = body["items"][0]
    assert first == {
        "title": "How to change a tire",
        "url": "https://www.youtube.com/watch?v=abc_DEF-123",
        "description": "Step by step.",
        "thumbnail": "https://imgs.example/v.jpg",
        "duration": "05:12",
        "views": 120345,
        "creator": "Bike Shop",
        "publisher": "YouTube",
        "age": "3 years ago",
        "youtube_id": "abc_DEF-123",
    }
    assert [i["youtube_id"] for i in body["items"][1:]] == [None, None, None, None]
    assert dict(brave.last.url.params) == {"q": "change a bike tire", "count": "8"}


def test_places_swap_nothing_and_map_every_field(client: TestClient, brave: FakeBrave) -> None:
    body = _get(client, "/v1/search/places", q="coffee", location="San Francisco, CA,  United States").json()
    assert body["center"] == {"lat": 37.7749, "lng": -122.4194, "name": "San Francisco"}
    assert body["items"] == [
        {
            "id": "poi-1",
            "title": "Blue Cup",
            "url": "https://maps.example/blue-cup",
            "lat": 37.78,
            "lng": -122.41,
            "address": "1 Market St, San Francisco, CA",
            "phone": "+1 415 555 0100",
            "rating": 4.5,
            "rating_max": 5.0,
            "reviews": 120,
            "price": "$$",
            "categories": ["Cafe", "Coffee"],
            "thumbnail": "https://imgs.example/p1.jpg",
            "hours_today": [{"opens": "07:00", "closes": "12:00"}, {"opens": "13:00", "closes": "19:00"}],
            "timezone": "America/Los_Angeles",
            "distance": {"value": 1.2, "units": "km"},
        }
    ]
    assert dict(brave.last.url.params) == {"q": "coffee", "count": "8", "location": "San Francisco CA United States"}


def test_places_by_coordinates_or_location_alone(client: TestClient, brave: FakeBrave) -> None:
    _get(client, "/v1/search/places", lat="30.27", long="-97.74")
    assert dict(brave.last.url.params) == {"count": "8", "latitude": "30.27", "longitude": "-97.74"}
    _get(client, "/v1/search/places", location="tokyo japan", lat="95", long="200")
    assert dict(brave.last.url.params) == {
        "count": "8",
        "location": "tokyo japan",
        "latitude": "90.0",
        "longitude": "180.0",
    }


def test_context_keeps_five_short_snippets_and_the_relative_age(client: TestClient, brave: FakeBrave) -> None:
    body = _get(client, "/v1/search/context", q="when was python created", max_tokens=99999).json()
    assert body["kind"] == "context" and body["query"] == "when was python created" and body["altered"] is None
    first, second = body["items"]
    assert first == {
        "title": "Python history",
        "url": "https://docs.example/py",
        "snippets": ["s0", "s1", "s2", "s3", "s4"],
        "site": "Docs",
        "favicon": "https://imgs.example/f.png",
        "thumbnail": "https://imgs.example/c.jpg",
        "age": "2 years ago",
    }
    assert second["snippets"] == ["x" * 1000] and second["site"] == "docs.example" and second["age"] is None
    assert dict(brave.last.url.params) == {
        "q": "when was python created",
        "maximum_number_of_tokens": "8192",
        "maximum_number_of_urls": "5",
        "enable_source_metadata": "true",
    }


def test_answers_post_one_blocking_question_on_the_answers_key(client: TestClient, brave: FakeBrave) -> None:
    body = _get(client, "/v1/search/answers", q="What is the second highest mountain?").json()
    assert body == {
        "kind": "answers",
        "query": "What is the second highest mountain?",
        "altered": None,
        "items": [],
        "answer": "K2 & more.",
        "center": None,
    }
    request = brave.last
    assert request.method == "POST" and request.url.path == "/res/v1/chat/completions"
    assert request.headers["x-subscription-token"] == "answers-key-2"
    assert json.loads(request.content) == {
        "messages": [{"role": "user", "content": "What is the second highest mountain?"}],
        "stream": False,
    }


def test_suggest_dedupes_and_spellcheck_corrects(client: TestClient, brave: FakeBrave) -> None:
    body = _get(client, "/v1/suggest", q="albert", count=40).json()
    assert body == {"query": "albert", "suggestions": ["albert einstein", "albertsons"]}
    assert dict(brave.last.url.params) == {"q": "albert", "count": "10"}
    assert brave.last.headers["x-subscription-token"] == "suggest-key-4"
    assert _get(client, "/v1/suggest", q="albert").json()["suggestions"]
    assert dict(brave.last.url.params)["count"] == "5"
    body = _get(client, "/v1/spellcheck", q="artifical").json()
    assert body == {"query": "artifical", "corrected": "artificial"}
    assert brave.last.headers["x-subscription-token"] == "spell-key-3"


@pytest.mark.parametrize(
    "results", [[], [{"query": "ARTIFICIAL "}], [{}]], ids=["no results", "same but for case", "no query"]
)
def test_spellcheck_has_no_correction_when_nothing_changes(tmp_path: Path, results: list[Any]) -> None:
    fake = FakeBrave(lambda request: httpx.Response(200, json={"type": "spellcheck", "results": results}))
    with _client(tmp_path, BraveSearch(KEYS, transport=httpx.MockTransport(fake))) as client:
        assert _get(client, "/v1/spellcheck", q="artificial").json()["corrected"] is None


@pytest.mark.parametrize(
    ("path", "params", "error"),
    [
        ("/v1/search/web", {}, "Type something to search for."),
        ("/v1/search/web", {"q": "   "}, "Type something to search for."),
        ("/v1/search/web", {"q": "x" * 401}, "Keep the search under 400 characters."),
        ("/v1/search/news", {"q": "nasa", "freshness": "pq"}, "Freshness must be pd, pw, pm or py"),
        ("/v1/search/places", {}, "Say where to look for places, like 'austin tx united states'."),
        ("/v1/search/places", {"q": "coffee", "lat": "30.2"}, "Give both lat and long as numbers."),
        ("/v1/search/places", {"lat": "north", "long": "1"}, "Give both lat and long as numbers."),
        ("/v1/suggest", {}, "Type something to search for."),
        ("/v1/spellcheck", {"q": ""}, "Type something to search for."),
    ],
)
def test_bad_parameters_are_400_and_never_reach_brave(
    client: TestClient, brave: FakeBrave, path: str, params: dict[str, str], error: str
) -> None:
    response = _get(client, path, **params)
    assert response.status_code == 400 and response.json()["error"].startswith(error)
    assert brave.requests == []


def test_counts_default_and_clamp(client: TestClient, brave: FakeBrave) -> None:
    for kind, default, ceiling in [("web", 8, 20), ("news", 8, 20), ("images", 12, 50), ("videos", 8, 20)]:
        _get(client, f"/v1/search/{kind}", q="x", count="lots")
        assert brave.last.url.params["count"] == str(default)
        _get(client, f"/v1/search/{kind}", q="x", count=1000)
        assert brave.last.url.params["count"] == str(ceiling)
    _get(client, "/v1/search/places", location="x", count=-3)
    assert brave.last.url.params["count"] == "1"


def test_device_token_and_unknown_kind(client: TestClient, brave: FakeBrave) -> None:
    for path in ("/v1/search/web", "/v1/suggest", "/v1/spellcheck"):
        for headers in ({}, {"Authorization": "Bearer wrong"}):
            response = client.get(path, params={"q": "x"}, headers=headers)
            assert response.status_code == 401 and response.json() == {"error": "unauthorized"}
            assert response.headers["cache-control"] == "no-store"
    response = _get(client, "/v1/search/maps", q="x")
    assert response.status_code == 404 and response.json() == {"error": "There's no search of that kind."}
    assert brave.requests == []


@pytest.mark.parametrize(
    ("keys", "path"),
    [
        (SearchKeys(answers="a", spellcheck="s", suggest="g"), "/v1/search/web"),
        (SearchKeys(answers="a", spellcheck="s", suggest="g"), "/v1/search/context"),
        (SearchKeys(search="w", spellcheck="s", suggest="g"), "/v1/search/answers"),
        (SearchKeys(search="w", answers="a", suggest="g"), "/v1/spellcheck"),
        (SearchKeys(search="w", answers="a", spellcheck="s"), "/v1/suggest"),
    ],
)
def test_a_missing_plan_key_is_503(tmp_path: Path, keys: SearchKeys, path: str) -> None:
    fake = FakeBrave()
    with _client(tmp_path, BraveSearch(keys, transport=httpx.MockTransport(fake))) as client:
        response = _get(client, path, q="x")
        assert response.status_code == 503 and response.json() == {"error": "Search isn't set up on Rivet's brain yet."}
    assert fake.requests == []


def test_no_search_client_means_every_route_is_503(tmp_path: Path) -> None:
    with _client(tmp_path, None) as client:
        for path in ("/v1/search/web", "/v1/search/answers", "/v1/suggest", "/v1/spellcheck"):
            assert _get(client, path, q="x").status_code == 503


def test_keys_come_from_the_environment_and_blank_is_unset() -> None:
    keys = SearchKeys.from_env(
        {"BRAVE_SEARCH_API_KEY": " k ", "BRAVE_ANSWERS_API_KEY": "", "BRAVE_SUGGEST_API_KEY": "s"}
    )
    assert (keys.search, keys.answers, keys.spellcheck, keys.suggest) == ("k", None, None, "s")
    assert "k" not in repr(keys)


@pytest.mark.parametrize(
    ("headers", "retry_after"), [({"X-RateLimit-Reset": "1, 224521"}, 1), ({}, None)], ids=["header", "no header"]
)
def test_brave_rate_limits_are_429_with_retry_after(
    tmp_path: Path, headers: dict[str, str], retry_after: int | None
) -> None:
    fake = FakeBrave(lambda request: httpx.Response(429, headers=headers, json={"type": "ErrorResponse"}))
    with _client(tmp_path, BraveSearch(KEYS, transport=httpx.MockTransport(fake))) as client:
        response = _get(client, "/v1/search/web", q="x")
    assert response.status_code == 429
    assert response.json() == {"error": "Search is busy. Try again in a moment.", "retry_after": retry_after}


def test_a_rejected_key_is_502_with_the_upstream_status(tmp_path: Path) -> None:
    invalid = {
        "type": "ErrorResponse",
        "error": {
            "code": "SUBSCRIPTION_TOKEN_INVALID",
            "detail": "The provided subscription token is invalid.",
            "meta": {"component": "authentication"},
            "status": 422,
        },
    }
    fake = FakeBrave(lambda request: httpx.Response(422, json=invalid))
    with _client(tmp_path, BraveSearch(KEYS, transport=httpx.MockTransport(fake))) as client:
        response = _get(client, "/v1/search/web", q="x")
        assert response.status_code == 502
        assert response.json() == {"error": "Brave Search didn't answer properly.", "upstream": 422}
        fake.respond = lambda request: httpx.Response(200, content=b"<html>")
        assert _get(client, "/v1/suggest", q="x").json()["upstream"] == 200


def test_a_timeout_is_504(tmp_path: Path) -> None:
    def slow(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow", request=request)

    with _client(tmp_path, BraveSearch(KEYS, transport=httpx.MockTransport(FakeBrave(slow)))) as client:
        response = _get(client, "/v1/search/answers", q="x")
    assert response.status_code == 504 and response.json() == {"error": "Brave Search took too long."}


def test_logs_never_hold_a_query_or_a_key(
    client: TestClient, brave: FakeBrave, caplog: pytest.LogCaptureFixture, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("RIVET_LOG_LEVEL", "DEBUG")
    caplog.set_level(logging.DEBUG)
    configure_logging()
    try:
        for path in ("/v1/search/web", "/v1/search/answers", "/v1/suggest", "/v1/spellcheck"):
            _get(client, path, q="secretquerytext")
    finally:
        logging.getLogger("httpx").setLevel(logging.NOTSET)
    # uvicorn configures its access logger itself, so check its line through the logger's filters.
    access = logging.getLogger("uvicorn.access")
    line = access.makeRecord(
        access.name,
        logging.INFO,
        __file__,
        0,
        '%s - "%s %s HTTP/%s" %d',
        ("c", "GET", "/v1/suggest?q=x", "1.1", 200),
        None,
    )
    assert access.filter(line) and line.getMessage() == 'c - "GET /v1/suggest HTTP/1.1" 200'
    text = "\n".join(record.getMessage() for record in caplog.records)
    assert "kind=<web>, status=<200>" in text and "kind=<suggest>" in text
    assert "secretquerytext" not in text
    assert not any(key in text for key in ("search-key-1", "answers-key-2", "spell-key-3", "suggest-key-4"))
