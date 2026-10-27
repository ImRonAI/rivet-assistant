"""Have the deployed brain set up call answering on a number, as a given phone session.

Connects to the brain like the phone does and asks Rivet to set up call answering, approving only
``call_answering_setup`` for exactly ``--number`` (with the reassign the owner asked for). Every other
approval is denied, and device calls are answered as "phone not connected".

    uv run python scripts/setup_call_answering.py --url https://rivet-brain-hiron.fly.dev \\
        --session tim --number +17608769757 --owner Tim --reassign

Needs RIVET_DEVICE_TOKEN in the environment.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import ssl
from typing import Any

import websockets


async def run(url: str, session: str, number: str, owner: str, reassign: bool) -> int:
    socket_url = url.rstrip("/").replace("https://", "wss://").replace("http://", "ws://") + f"/v1/sessions/{session}"
    headers = {"Authorization": f"Bearer {os.environ['RIVET_DEVICE_TOKEN']}"}
    move = " It is in use by another Telnyx connection; I agree to moving it to Rivet." if reassign else ""
    text = f"Set up call answering on my Telnyx number {number}. My name is {owner}.{move}"
    # SSL_CERT_FILE lets this run behind a TLS-intercepting proxy; otherwise the system store is used.
    tls = ssl.create_default_context(cafile=os.environ.get("SSL_CERT_FILE")) if socket_url.startswith("wss") else None
    async with websockets.connect(socket_url, additional_headers=headers, max_size=None, ssl=tls) as ws:
        await ws.send(json.dumps({"type": "turn", "turn_id": f"setup-{number}", "text": text}))
        while True:
            event: dict[str, Any] = json.loads(await asyncio.wait_for(ws.recv(), 300))
            kind = event["type"]
            if kind == "approval.request":
                answers = {}
                for approval in event["approvals"]:
                    request = approval["request"]
                    preview = json.loads(request.get("preview") or "{}")
                    ok = (
                        request["tool"] == "call_answering_setup"
                        and preview.get("number") == number
                        and bool(preview.get("reassign")) == reassign
                    )
                    print(
                        f"approval {request['tool']} [{request['tier']}] {request['summary']}: {'yes' if ok else 'no'}"
                    )
                    answers[approval["id"]] = {"approved": ok}
                await ws.send(json.dumps({"type": "approval.response", "lane": event["lane"], "answers": answers}))
            elif kind == "device.call":
                result = {"ok": False, "data": "The phone is not connected yet; it saves the number when it connects."}
                await ws.send(json.dumps({"type": "device.result", "action_id": event["action_id"], "result": result}))
            elif kind == "tool":
                print(f"tool {event['name']}")
            elif kind in ("turn.failed", "error"):
                print(f"{kind}: {event.get('error')}")
                return 1
            elif kind == "turn.completed" and event.get("lane") == "default":
                print(f"\n{event['text']}")
                return 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--url", required=True)
    parser.add_argument("--session", required=True)
    parser.add_argument("--number", required=True)
    parser.add_argument("--owner", required=True)
    parser.add_argument("--reassign", action="store_true")
    args = parser.parse_args()
    raise SystemExit(asyncio.run(run(args.url, args.session, args.number, args.owner, args.reassign)))


if __name__ == "__main__":
    main()
