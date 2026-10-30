"""Phase 0 contract probe: record raw Perplexity Agent API streams as test fixtures.

Each case sends one request to ``$PERPLEXITY_BASE_URL/responses`` and saves the raw server-sent events to
``tests/fixtures/perplexity/<case>.sse`` (plus the request body as ``.request.json``). Nothing here touches
Telnyx. The API key is read from ``PERPLEXITY_API_KEY`` and never written to disk.

Usage: python -I scripts/probe_perplexity.py [case ...]
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any

import httpx

OUT = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "perplexity"
MODEL = os.environ.get("EMRYS_MODEL", "openai/gpt-5.6-sol")
LOOKUP = {
    "type": "function",
    "name": "lookup_contact",
    "description": "Look up a contact's phone number.",
    "parameters": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]},
}


def _body(prompt: Any, **extra: Any) -> dict[str, Any]:
    return {"model": MODEL, "input": prompt, "stream": True, "store": False, "max_steps": 8, **extra}


CASES: dict[str, dict[str, Any]] = {
    "plain_text": _body("Say hello in five words."),
    "function_call": _body("Find Ada's phone number with the tool.", tools=[LOOKUP]),
    "web_search": _body("What happened in Salt Lake City today?", tools=[{"type": "web_search"}]),
    "fetch_url": _body("Summarize https://example.com in one line.", tools=[{"type": "fetch_url"}]),
    "image_search": _body("Find a picture of a red fox.", tools=[{"type": "image_search"}]),
    "finance_search": _body("What is Apple's stock price?", tools=[{"type": "finance_search"}]),
    "people_search": _body("Who is the CEO of Telnyx?", tools=[{"type": "people_search"}]),
    "incomplete": _body("Write a 2000-word essay about rivers.", max_output_tokens=16),
    "bad_request": _body("hi", tools=[{"type": "function", "name": "bad name with spaces", "parameters": {}}]),
    "consecutive_user_items": _body(
        [{"role": "user", "content": "My name is Ada."}, {"role": "user", "content": "What is my name?"}]
    ),
}


def record(name: str, body: dict[str, Any], client: httpx.Client) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.request.json").write_text(json.dumps(body, indent=2) + "\n")
    with client.stream("POST", "/responses", json=body) as response:
        raw = response.read()
    (OUT / f"{name}.sse").write_bytes(raw)
    print(f"{name}: HTTP {response.status_code}, {len(raw)} bytes")


def main(argv: list[str]) -> None:
    key = os.environ.get("PERPLEXITY_API_KEY")
    if not key:
        raise SystemExit("PERPLEXITY_API_KEY is not set")
    base = os.environ.get("PERPLEXITY_BASE_URL", "https://api.perplexity.ai/v1")
    names = argv or list(CASES)
    with httpx.Client(base_url=base, headers={"Authorization": f"Bearer {key}"}, timeout=180) as client:
        for name in names:
            record(name, CASES[name], client)


if __name__ == "__main__":
    main(sys.argv[1:])
