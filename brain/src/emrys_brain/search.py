"""Brave Search for the phone and the agent: web, news, images, videos, places, LLM context, answers, suggest and
spellcheck.

The brain holds the four Brave keys (one per plan: Search, Answers, Spellcheck, Autosuggest) and keeps nothing it
gets back: Brave prohibits retaining API results. Logs record the kind, status and latency only, never a query, a
result or a key.

Upstream facts come from Brave's API reference (https://api-dashboard.search.brave.com/llms.txt): the base URL and
``X-Subscription-Token`` header, the parameters and their ranges, and the response fields. Every response field is
read as optional; an item without the fields a card needs (title and URL; a thumbnail for images; coordinates for
places) is dropped, never the whole response. Unknown fields are ignored, as Brave's versioning guide allows new
ones at any time.
"""

from __future__ import annotations

import asyncio
import html
import json
import logging
import math
import os
import re
import time
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import asdict, dataclass, field
from typing import ClassVar, Literal, TypeVar
from urllib.parse import parse_qs, urlsplit

import httpx

logger = logging.getLogger(__name__)

SearchKind = Literal["web", "news", "images", "videos", "places", "context", "answers"]
KINDS: dict[str, SearchKind] = {
    "web": "web",
    "news": "news",
    "images": "images",
    "videos": "videos",
    "places": "places",
    "context": "context",
    "answers": "answers",
}
Freshness = Literal["pd", "pw", "pm", "py"]
_FRESHNESS: dict[str, Freshness] = {"pd": "pd", "pw": "pw", "pm": "pm", "py": "py"}

BRAVE_API = "https://api.search.brave.com/res"
SEARCH_TIMEOUT = 10.0
ANSWERS_TIMEOUT = 12.0
SUGGEST_TIMEOUT = 4.0
"""Seconds for one whole Brave call, reading included. The ElevenLabs agents cut a search tool at 20 s and the phone
waits 15 s for the brain, so the brain answers inside both."""

QUERY_LIMIT = 400
COUNTS: dict[SearchKind, tuple[int, int]] = {
    "web": (8, 20),
    "news": (8, 20),
    "images": (12, 50),
    "videos": (8, 20),
    "places": (8, 20),
    "context": (5, 20),
}
"""Default and maximum ``count`` per kind, within Brave's own ranges. For context it is the number of pages."""
SUGGEST_COUNTS = (5, 10)
CONTEXT_TOKENS = (2048, 1024, 8192)
"""Default, minimum and maximum ``maximum_number_of_tokens`` for LLM context."""
SNIPPETS = 5
SNIPPET_CHARS = 1000
CITATION_CHARS = 300

NO_QUERY = "Type something to search for."
NO_PLACE = "Say where to look for places, like 'austin tx united states'."
NOT_SET_UP = "Search isn't set up on Emrys's brain yet."

_YOUTUBE_WATCH = re.compile(r"^https://www\.youtube\.com/watch\?")
_YOUTUBE_ID = re.compile(r"^[A-Za-z0-9_-]+$")
_CITATION = re.compile(r"<citation>(.*?)</citation>", re.DOTALL)
_USAGE = re.compile(r"<usage>.*?</usage>", re.DOTALL)

_T = TypeVar("_T")


class SearchError(Exception):
    """A search Emrys could not run; ``message`` is a plain sentence for the user and the model."""

    status: ClassVar[int] = 502

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message

    def body(self) -> dict[str, object]:
        """The JSON error body the phone gets."""
        return {"error": self.message}


class SearchInvalid(SearchError):
    """The request's parameters are missing or wrong."""

    status = 400


class SearchUnavailable(SearchError):
    """The Brave key for this plan is not set."""

    status = 503

    def __init__(self) -> None:
        super().__init__(NOT_SET_UP)


class SearchBusy(SearchError):
    """Brave rate-limited the call; ``retry_after`` is seconds until the per-second window resets, when known."""

    status = 429

    def __init__(self, retry_after: int | None) -> None:
        super().__init__("Search is busy. Try again in a moment.")
        self.retry_after = retry_after

    def body(self) -> dict[str, object]:
        return {"error": self.message, "retry_after": self.retry_after}


class SearchRejected(SearchError):
    """Brave answered with something other than a usable 200; ``upstream`` is its status (None: no answer)."""

    def __init__(self, upstream: int | None) -> None:
        super().__init__("Brave Search didn't answer properly.")
        self.upstream = upstream

    def body(self) -> dict[str, object]:
        return {"error": self.message, "upstream": self.upstream}


class SearchTimeout(SearchError):
    """Brave did not answer in time."""

    status = 504

    def __init__(self) -> None:
        super().__init__("Brave Search took too long.")


@dataclass(frozen=True)
class SearchKeys:
    """One Brave key per plan; ``None`` when unset."""

    search: str | None = field(default=None, repr=False)
    answers: str | None = field(default=None, repr=False)
    spellcheck: str | None = field(default=None, repr=False)
    suggest: str | None = field(default=None, repr=False)

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> SearchKeys:
        """The keys from ``BRAVE_*_API_KEY``; empty values count as unset."""
        env = os.environ if environ is None else environ

        def key(name: str) -> str | None:
            return env.get(name, "").strip() or None

        return cls(
            search=key("BRAVE_SEARCH_API_KEY"),
            answers=key("BRAVE_ANSWERS_API_KEY"),
            spellcheck=key("BRAVE_SPELLCHECK_API_KEY"),
            suggest=key("BRAVE_SUGGEST_API_KEY"),
        )

    def for_kind(self, kind: SearchKind) -> str | None:
        """The key a search of ``kind`` runs on: Answers has its own plan, the rest are the Search plan."""
        return self.answers if kind == "answers" else self.search


@dataclass(frozen=True)
class SearchQuery:
    """A validated search request. Numbers out of range are clamped, never rejected."""

    q: str
    count: int | None = None
    freshness: Freshness | None = None
    location: str | None = None
    lat: float | None = None
    lng: float | None = None
    max_tokens: int = CONTEXT_TOKENS[0]

    @classmethod
    def parse(cls, kind: SearchKind, raw: Mapping[str, object]) -> SearchQuery:
        """Read ``q``, ``count``, ``freshness``, ``location``, ``lat``/``long`` and ``max_tokens`` for ``kind``.

        A place search may give ``q`` alone; Brave then searches without a location bias.

        Raises:
            SearchInvalid: ``q`` is missing or too long (places may go without one when given a location), the
                freshness is not ``pd``/``pw``/``pm``/``py``, or only one coordinate is given.
        """
        q = _param(raw.get("q")) or ""
        if len(q) > QUERY_LIMIT:
            raise SearchInvalid(f"Keep the search under {QUERY_LIMIT} characters.")
        freshness = _freshness(raw.get("freshness")) if kind in ("web", "news", "videos", "context") else None
        count = None
        if kind in COUNTS:
            default, ceiling = COUNTS[kind]
            count = _clamp(_int(raw.get("count")), default, 1, ceiling)
        max_tokens = _clamp(_int(raw.get("max_tokens")), *CONTEXT_TOKENS)
        if kind != "places":
            if not q:
                raise SearchInvalid(NO_QUERY)
            return cls(q, count, freshness, max_tokens=max_tokens)
        location = " ".join((_param(raw.get("location")) or "").replace(",", " ").split()) or None
        lat, lng = _coordinates(raw.get("lat"), raw.get("long"))
        if not q and location is None and lat is None:
            raise SearchInvalid(NO_PLACE)
        return cls(q, count, None, location, lat, lng, max_tokens)

    def upstream(self, kind: SearchKind) -> tuple[str, dict[str, str]]:
        """Brave's path and query parameters for this search (not answers, which is a POST)."""
        params: dict[str, str] = {"q": self.q} if self.q else {}
        if self.count is not None and kind != "context":
            params["count"] = str(self.count)
        if self.freshness is not None:
            params["freshness"] = self.freshness
        if kind == "web":
            # With Brave's default, descriptions carry highlight markup.
            params["text_decorations"] = "false"
        if kind == "places":
            if self.location is not None:
                params["location"] = self.location
            if self.lat is not None and self.lng is not None:
                params["latitude"], params["longitude"] = str(self.lat), str(self.lng)
        if kind == "context":
            params["maximum_number_of_tokens"] = str(self.max_tokens)
            params["maximum_number_of_urls"] = str(self.count or COUNTS["context"][0])
            params["enable_source_metadata"] = "true"
        return _PATHS[kind], params


_PATHS: dict[SearchKind, str] = {
    "web": "/v1/web/search",
    "news": "/v1/news/search",
    "images": "/v1/images/search",
    "videos": "/v1/videos/search",
    "places": "/v1/local/place_search",
    "context": "/v1/llm/context",
    "answers": "/v1/chat/completions",
}


@dataclass(frozen=True)
class WebItem:
    """A web page or news story (``breaking`` is Brave's flag on news)."""

    title: str
    url: str
    description: str | None
    site: str | None
    favicon: str | None
    thumbnail: str | None
    age: str | None
    breaking: bool = False


@dataclass(frozen=True)
class ImageItem:
    """An image: ``url`` is the page it is on, ``image`` the full-size file."""

    title: str
    url: str
    image: str
    thumbnail: str
    width: int | None
    height: int | None
    source: str | None


@dataclass(frozen=True)
class VideoItem:
    """A video; ``youtube_id`` is set only for ``https://www.youtube.com/watch?v=<id>`` URLs."""

    title: str
    url: str
    description: str | None
    thumbnail: str | None
    duration: str | None
    views: int | None
    creator: str | None
    publisher: str | None
    age: str | None
    youtube_id: str | None


@dataclass(frozen=True)
class Hours:
    """One opening period today, as Brave's 24-hour ``HH:MM`` strings."""

    opens: str
    closes: str


@dataclass(frozen=True)
class Distance:
    """How far a place is, in Brave's units."""

    value: float
    units: str


@dataclass(frozen=True)
class PlaceItem:
    """A place; Brave gives coordinates as ``[lat, long]``."""

    id: str | None
    title: str
    url: str
    lat: float
    lng: float
    address: str | None
    phone: str | None
    rating: float | None
    rating_max: float | None
    reviews: int | None
    price: str | None
    categories: list[str]
    thumbnail: str | None
    hours_today: list[Hours]
    timezone: str | None
    distance: Distance | None


@dataclass(frozen=True)
class ContextItem:
    """A page's extracted text for grounding."""

    title: str
    url: str
    snippets: list[str]
    site: str | None
    favicon: str | None
    thumbnail: str | None
    age: str | None


@dataclass(frozen=True)
class Center:
    """Where Brave resolved a place search."""

    lat: float
    lng: float
    name: str | None


@dataclass(frozen=True)
class Citation:
    """A source Brave's answer cites; the answer marks it as ``[number]``."""

    number: int
    url: str
    snippet: str | None


SearchItem = WebItem | ImageItem | VideoItem | PlaceItem | ContextItem


@dataclass(frozen=True)
class SearchResults:
    """The envelope the phone renders: every field present, ``None`` when absent."""

    kind: SearchKind
    query: str
    altered: str | None
    items: list[SearchItem]
    answer: str | None = None
    center: Center | None = None
    citations: list[Citation] = field(default_factory=list)

    def to_json(self) -> dict[str, object]:
        """The envelope as JSON-ready data."""
        return asdict(self)


class BraveSearch:
    """Calls Brave with the right plan's key. Closed with the gateway app.

    Args:
        keys: The Brave keys.
        transport: HTTP transport (tests inject a fake here).
    """

    def __init__(self, keys: SearchKeys, *, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self.keys = keys
        self._http = httpx.AsyncClient(base_url=BRAVE_API, transport=transport)

    async def search(self, kind: SearchKind, query: SearchQuery) -> SearchResults:
        """Run one search.

        Raises:
            SearchError: The key is unset, or Brave is busy, slow or answered wrongly.
        """
        key = self.keys.for_kind(kind)
        if kind == "answers":
            # Brave sends citations only on a stream.
            body = {"messages": [{"role": "user", "content": query.q}], "stream": True, "enable_citations": True}
            parts = await self._call(kind, key, "POST", _PATHS[kind], ANSWERS_TIMEOUT, _stream, json=body)
            answer, citations = _answer(parts)
            return SearchResults(kind, query.q, None, [], answer=answer, citations=citations)
        path, params = query.upstream(kind)
        data = await self._call(kind, key, "GET", path, SEARCH_TIMEOUT, _json, params=params)
        return _results(kind, query.q, data)

    async def suggest(self, q: str, count: int) -> list[str]:
        """Query completions for ``q``, deduplicated in Brave's order."""
        params = {"q": q, "count": str(count)}
        data = await self._call(
            "suggest", self.keys.suggest, "GET", "/v1/suggest/search", SUGGEST_TIMEOUT, _json, params=params
        )
        seen: dict[str, None] = {}
        for item in _list(data.get("results")):
            text = _text(_obj(item).get("query"))
            if text is not None:
                seen.setdefault(text, None)
        return list(seen)

    async def spellcheck(self, q: str) -> str | None:
        """Brave's correction of ``q``, or ``None`` when there is none or it only changes case or spacing."""
        params = {"q": q}
        data = await self._call(
            "spellcheck", self.keys.spellcheck, "GET", "/v1/spellcheck/search", SUGGEST_TIMEOUT, _json, params=params
        )
        results = _list(data.get("results"))
        corrected = _text(_obj(results[0]).get("query")) if results else None
        if corrected is None or corrected.casefold() == q.strip().casefold():
            return None
        return corrected

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _call(
        self,
        kind: str,
        key: str | None,
        method: str,
        path: str,
        timeout: float,
        read: Callable[[httpx.Response], Awaitable[_T]],
        *,
        params: Mapping[str, str] | None = None,
        json: object = None,
    ) -> _T:
        """One Brave call within ``timeout`` seconds in all; ``read`` reads a 200 response's body."""
        if key is None:
            raise SearchUnavailable()
        headers = {"X-Subscription-Token": key, "Accept": "application/json"}
        request = self._http.build_request(method, path, params=params, json=json, headers=headers, timeout=timeout)
        started = time.monotonic()
        status = "ok"
        try:
            async with asyncio.timeout(timeout):
                response = await self._http.send(request, stream=True)
                try:
                    status = str(response.status_code)
                    if response.status_code == 429:
                        raise SearchBusy(_retry_after(response.headers.get("x-ratelimit-reset")))
                    if response.status_code != 200:
                        raise SearchRejected(response.status_code)
                    return await read(response)
                finally:
                    await response.aclose()
        except (TimeoutError, httpx.TimeoutException):
            status = "timeout"
            raise SearchTimeout() from None
        except httpx.HTTPError as error:
            status = type(error).__name__
            raise SearchRejected(None) from None
        finally:
            self._log(kind, status, started)

    @staticmethod
    def _log(kind: str, status: str, started: float) -> None:
        latency = int((time.monotonic() - started) * 1000)
        logger.info("kind=<%s>, status=<%s>, latency_ms=<%d> | brave call", kind, status, latency)


def query_text(value: object) -> str:
    """``q`` for suggest and spellcheck.

    Raises:
        SearchInvalid: It is missing or too long.
    """
    q = _param(value) or ""
    if not q:
        raise SearchInvalid(NO_QUERY)
    if len(q) > QUERY_LIMIT:
        raise SearchInvalid(f"Keep the search under {QUERY_LIMIT} characters.")
    return q


def suggest_count(value: object) -> int:
    """``count`` for suggest, clamped."""
    default, ceiling = SUGGEST_COUNTS
    return _clamp(_int(value), default, 1, ceiling)


# -- request parameters --------------------------------------------------------------------------------------


def _param(value: object) -> str | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, str):
        return value.strip() or None
    if isinstance(value, int | float):
        return str(value)
    return None


def _int(value: object) -> int | None:
    text = _param(value)
    if text is None:
        return None
    try:
        number = float(text)
    except ValueError:
        return None
    return int(number) if math.isfinite(number) else None


def _clamp(value: int | None, default: int, low: int, high: int) -> int:
    return default if value is None else max(low, min(value, high))


def _freshness(value: object) -> Freshness | None:
    text = _param(value)
    if text is None:
        return None
    if text not in _FRESHNESS:
        raise SearchInvalid("Freshness must be pd, pw, pm or py (past day, week, month or year).")
    return _FRESHNESS[text]


def _coordinates(lat_value: object, lng_value: object) -> tuple[float | None, float | None]:
    lat_text, lng_text = _param(lat_value), _param(lng_value)
    if lat_text is None and lng_text is None:
        return None, None
    try:
        lat, lng = float(lat_text or ""), float(lng_text or "")
    except ValueError:
        raise SearchInvalid("Give both lat and long as numbers.") from None
    if not (math.isfinite(lat) and math.isfinite(lng)):
        raise SearchInvalid("Give both lat and long as numbers.")
    return max(-90.0, min(lat, 90.0)), max(-180.0, min(lng, 180.0))


def _retry_after(header: str | None) -> int | None:
    """The first value of ``X-RateLimit-Reset`` (``"1, 224521"``: per-second window first)."""
    first = (header or "").split(",")[0].strip()
    return int(first) if first.isdigit() else None


# -- response parsing ----------------------------------------------------------------------------------------


def _obj(value: object) -> Mapping[str, object]:
    return value if isinstance(value, dict) else {}


def _list(value: object) -> list[object]:
    return value if isinstance(value, list) else []


def _text(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    return html.unescape(value).strip() or None


def _number(value: object) -> float | None:
    if isinstance(value, bool) or not isinstance(value, int | float):
        return None
    return float(value) if math.isfinite(value) else None


def _count(value: object) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _src(value: object) -> str | None:
    return _text(_obj(value).get("src"))


def _youtube_id(url: str) -> str | None:
    if not _YOUTUBE_WATCH.match(url):
        return None
    ids = parse_qs(urlsplit(url).query).get("v") or []
    return ids[0] if len(ids) == 1 and _YOUTUBE_ID.match(ids[0]) else None


def _web(raw: Mapping[str, object]) -> WebItem | None:
    title, url = _text(raw.get("title")), _text(raw.get("url"))
    if title is None or url is None:
        return None
    meta = _obj(raw.get("meta_url"))
    return WebItem(
        title,
        url,
        description=_text(raw.get("description")),
        site=_text(_obj(raw.get("profile")).get("name")) or _text(meta.get("hostname")),
        favicon=_text(meta.get("favicon")),
        thumbnail=_src(raw.get("thumbnail")),
        age=_text(raw.get("age")),
        breaking=raw.get("breaking") is True,
    )


def _image(raw: Mapping[str, object]) -> ImageItem | None:
    title, url, thumbnail = _text(raw.get("title")), _text(raw.get("url")), _src(raw.get("thumbnail"))
    if title is None or url is None or thumbnail is None:
        return None
    properties, small = _obj(raw.get("properties")), _obj(raw.get("thumbnail"))
    full = _text(properties.get("url"))
    width, height = _size(properties) if full else _size(small)
    return ImageItem(
        title,
        url,
        image=full or thumbnail,
        thumbnail=thumbnail,
        width=width,
        height=height,
        source=_text(raw.get("source")),
    )


def _size(raw: Mapping[str, object]) -> tuple[int | None, int | None]:
    """Both dimensions of one image, or neither."""
    width, height = _count(raw.get("width")), _count(raw.get("height"))
    return (width, height) if width and height else (None, None)


def _video(raw: Mapping[str, object]) -> VideoItem | None:
    title, url = _text(raw.get("title")), _text(raw.get("url"))
    if title is None or url is None:
        return None
    video = _obj(raw.get("video"))
    return VideoItem(
        title,
        url,
        description=_text(raw.get("description")),
        thumbnail=_src(raw.get("thumbnail")),
        duration=_text(video.get("duration")),
        views=_count(video.get("views")),
        creator=_text(video.get("creator")),
        publisher=_text(video.get("publisher")),
        age=_text(raw.get("age")),
        youtube_id=_youtube_id(url),
    )


def _lat_lng(value: object) -> tuple[float, float] | None:
    pair = _list(value)
    if len(pair) != 2:
        return None
    lat, lng = _number(pair[0]), _number(pair[1])
    return (lat, lng) if lat is not None and lng is not None else None


def _place(raw: Mapping[str, object]) -> PlaceItem | None:
    title, url, where = _text(raw.get("title")), _text(raw.get("url")), _lat_lng(raw.get("coordinates"))
    if title is None or url is None or where is None:
        return None
    rating, distance = _obj(raw.get("rating")), _obj(raw.get("distance"))
    categories: dict[str, None] = {}
    for entry in [*_list(raw.get("categories")), *_list(raw.get("serves_cuisine"))]:
        text = _text(entry)
        if text is not None:
            categories.setdefault(text, None)
    hours = []
    for period in _list(_obj(raw.get("opening_hours")).get("current_day")):
        opens, closes = _text(_obj(period).get("opens")), _text(_obj(period).get("closes"))
        if opens is not None and closes is not None:
            hours.append(Hours(opens, closes))
    pictures = _list(_obj(raw.get("pictures")).get("results"))
    value, units = _number(distance.get("value")), _text(distance.get("units"))
    return PlaceItem(
        id=_text(raw.get("id")),
        title=title,
        url=url,
        lat=where[0],
        lng=where[1],
        address=_text(_obj(raw.get("postal_address")).get("displayAddress")),
        phone=_text(_obj(raw.get("contact")).get("telephone")),
        rating=_number(rating.get("ratingValue")),
        rating_max=_number(rating.get("bestRating")),
        reviews=_count(rating.get("reviewCount")),
        price=_text(raw.get("price_range")),
        categories=list(categories),
        thumbnail=_src(raw.get("thumbnail")) or (_src(pictures[0]) if pictures else None),
        hours_today=hours,
        timezone=_text(raw.get("timezone")),
        distance=Distance(value, units) if value is not None and units is not None else None,
    )


def _context(raw: Mapping[str, object], sources: Mapping[str, object]) -> ContextItem | None:
    title, url = _text(raw.get("title")), _text(raw.get("url"))
    if title is None or url is None:
        return None
    snippets = [text[:SNIPPET_CHARS] for text in map(_text, _list(raw.get("snippets"))) if text is not None]
    source = _obj(sources.get(url))
    age = _list(source.get("age"))
    return ContextItem(
        title,
        url,
        snippets=snippets[:SNIPPETS],
        site=_text(source.get("site_name")) or _text(source.get("hostname")),
        favicon=_text(source.get("favicon")),
        thumbnail=_src(source.get("thumbnail")),
        # Brave's age is [full date, YYYY-MM-DD, relative, ISO-8601].
        age=_text(age[2]) if len(age) == 4 else None,
    )


_PARSERS: dict[SearchKind, Callable[[Mapping[str, object]], SearchItem | None]] = {
    "web": _web,
    "news": _web,
    "images": _image,
    "videos": _video,
    "places": _place,
}


def _results(kind: SearchKind, q: str, data: Mapping[str, object]) -> SearchResults:
    query = _obj(data.get("query"))
    original, altered = _text(query.get("original")) or q, _text(query.get("altered"))
    items: list[SearchItem] = []
    if kind == "context":
        sources = _obj(data.get("sources"))
        for raw in _list(_obj(data.get("grounding")).get("generic")):
            context = _context(_obj(raw), sources)
            if context is not None:
                items.append(context)
        return SearchResults(kind, original, altered, items)
    parse = _PARSERS[kind]
    listing = _obj(data.get("web")).get("results") if kind == "web" else data.get("results")
    for raw in _list(listing):
        item = parse(_obj(raw))
        if item is not None:
            items.append(item)
    center = None
    if kind == "places":
        location = _obj(data.get("location"))
        where = _lat_lng(location.get("coordinates"))
        center = Center(where[0], where[1], _text(location.get("name"))) if where is not None else None
    return SearchResults(kind, original, altered, items, center=center)


async def _json(response: httpx.Response) -> Mapping[str, object]:
    await response.aread()
    try:
        data = response.json()
    except ValueError:
        raise SearchRejected(response.status_code) from None
    if not isinstance(data, dict):
        raise SearchRejected(response.status_code)
    return data


async def _stream(response: httpx.Response) -> list[str]:
    """The ``delta.content`` of each ``data:`` chunk of a server-sent event stream, up to ``data: [DONE]``."""
    parts: list[str] = []
    async for line in response.aiter_lines():
        if not line.startswith("data:"):
            continue
        payload = line.removeprefix("data:").strip()
        if payload == "[DONE]":
            break
        try:
            chunk = json.loads(payload)
        except ValueError:
            raise SearchRejected(response.status_code) from None
        choices = _list(_obj(chunk).get("choices"))
        content = _obj(_obj(choices[0]).get("delta")).get("content") if choices else None
        if isinstance(content, str):
            parts.append(content)
    return parts


def _answer(parts: list[str]) -> tuple[str | None, list[Citation]]:
    """The streamed answer with each ``<citation>`` tag swapped for its ``[number]`` and ``<usage>`` dropped."""
    citations: dict[int, Citation] = {}

    def cite(match: re.Match[str]) -> str:
        try:
            raw = _obj(json.loads(match.group(1)))
        except ValueError:
            return ""
        number, url = _count(raw.get("number")), _text(raw.get("url"))
        if number is None or url is None:
            return ""
        snippet = _text(raw.get("snippet"))
        citations.setdefault(number, Citation(number, url, snippet[:CITATION_CHARS] if snippet else None))
        return f"[{number}]"

    text = _CITATION.sub(cite, _USAGE.sub("", "".join(parts)))
    return _text(text), sorted(citations.values(), key=lambda citation: citation.number)
