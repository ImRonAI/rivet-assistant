# Rivet

Rivet is an Android phone assistant: email, texts, calls, apps, reminders and tasks, with a human
approval before anything that sends, buys, changes or deletes. The face is the Rivet robot (SVG); the
brain is a [Strands harness](https://strandsagents.com/docs/user-guide/harness/) agent on Perplexity's
Agent API, connected to the Telnyx MCP server on Prefect Horizon.

```
Android app (Kotlin/Compose)                   Rivet brain (this repo: brain/)
┌──────────────────────────────┐   WSS   ┌────────────────────────────────────────────────┐
│ Rivet robot (WebView)        │◄───────►│ gateway: /v1/sessions/{id}, device token        │
│ approval sheet, notifications│ events  │  └ SessionSupervisor (one per session)          │
│ device tool executors + ledger│approvals│     └ lanes: conversation, GoalLoop tasks      │
└──────────────────────────────┘ device  │        └ generation N: create_harness(...)     │
                                         │           model: Perplexity Agent API          │
                                         │           tools: Telnyx MCP, device proxies    │
                                         │           interventions: RivetApprovals        │
                                         └───────────┬──────────────────────┬─────────────┘
                                         Perplexity Agent API        Telnyx MCP (Horizon)
                                         (web/people/image/finance   1,371 Telnyx operations
                                          search run server-side)    via search/get_schema/execute
```

## Status

| Phase | What | State |
|---|---|---|
| 0 | Perplexity contract probe (`brain/scripts/probe_perplexity.py`) | **Done**: real streams recorded in `brain/tests/fixtures/perplexity`, replayed by `test_recorded_streams.py` |
| 1 | Brain: model, approvals, supervisor, gateway, chaos suite | **Built, tested, verified live** against Perplexity and the Telnyx MCP server (read-only) |
| 2 | Android app: robot WebView, gateway client, chat, voice, approvals, activity, settings | **Built**: APK, JVM tests and lint green; needs an on-device pass (no emulator here) |
| 3 | Device tools: SMS, replies in any messaging app, contacts, calendar, calls, reminders, email drafts | **Built** on both sides; phone tools verified live through the brain with a scripted phone |
| 4 | App operator (Accessibility): read screen, tap/type/scroll, Stop overlay, approval at commit points | **Built**; needs an on-device pass |
| 5 | Calls: screening, and Rivet answering calls to the user's own number | **Built**: setup verified live up to the first Telnyx write; turning it on needs a Telnyx number Rivet may use (moving one that's in use, or buying one, needs your OK) |

## How the brain works

- **Model** (`models/perplexity.py`): `PerplexityAgentModel` subclasses Strands' `OpenAIResponsesModel`
  for Perplexity's OpenAI Responses-compatible Agent API. Native tools (`web_search`, `fetch_url`,
  `image_search`, `finance_search`, `people_search`) are kept in `params["tools"]` and sent on every
  request; `max_steps`, the `models` fallback chain and `max_output_tokens` go in `extra_body`. It builds
  function calls from output items (Perplexity sends no argument deltas), carries search results as
  citations, replays `thought_signature`, and raises typed, retryable errors. An aux model (no native
  tools) handles memory extraction, summaries and the GoalLoop judge.
- **Agent** (`factory.py`): `create_harness()` with no shell/file/code/subagent tools, todos, the auto
  context manager, file memory, snapshot sessions on fenced storage, the Telnyx `MCPClient` (Horizon
  bearer read per request, `telnyx_` prefix, failure is fatal) and `RivetApprovals`.
- **Approvals** (`approvals.py`): deterministic and fail-closed. Telnyx `execute` code is parsed and every
  `call_tool` target is looked up in a catalog generated from OubliTelnyxMCP's own tool construction
  (`scripts/gen_telnyx_ops.py`): GET runs, anything else asks; purchases and deletes are high tier and
  never remembered. Remembered approvals cover outbound sends only, expire, and are suspended for a turn
  once it has read untrusted content (messages, mail, web pages, screens).
- **Supervisor** (`supervisor.py`): watchdog phase deadlines (first chunk, stream idle, model total,
  per-tool limits, retry backoff, Telnyx connection loss); stop sequence of cancel, hard cancel, abandon;
  generation fencing so an abandoned agent can never write the session; rebuild on the same session id;
  resume without re-sending the prompt, using a write-ahead side-effect journal so completed actions are
  never repeated and uncertain ones are reported; overflow compaction on the aux model; restart circuit
  breaker; crash recovery; pending approvals that survive restarts and time out to a denial; GoalLoop
  tasks that are never retried over an approval pause.
- **Gateway** (`gateway.py`): one WebSocket per session, events buffered while the phone is away, device
  tool calls routed to the phone by action id (the phone's ledger dedups them), re-sent turns ignored.
- **Phone tools** (`device_tools.py`): the 18 tools the Android app runs, with their approval policies:
  reads run (messages, notifications, calendar and screens taint the turn), texts, replies and calls ask
  (rememberable per recipient), calendar writes ask, and the app operator asks when a tap's label reads
  like a commit point (send, pay, delete, confirm). The phone refuses a tap whose label changed.

## Answering your calls (`brain/src/rivet_brain/calls.py`)

Callers keep dialing your own number. Android doesn't let an ordinary app speak on a cellular call, so when
you turn call answering on, Rivet dials your carrier's forwarding code (calls you don't pick up, or all calls)
to a Telnyx number where a Telnyx AI Assistant answers as Rivet and takes a message.

1. Ask Rivet to "set up call answering" with a Telnyx number on your account. `call_answering_setup` creates
   (or updates) the *Rivet call answering* assistant (a non-Anthropic Telnyx model; `RIVET_CALL_MODEL`) and
   routes the number to it, after your approval. A number already used by another connection is only moved
   if you say so (biometric approval). The number is saved on the phone.
2. Turn it on in Rivet › Settings › Call answering. That dials `**004*` / `**21*` (most carriers) or `*71` /
   `*72` (Verizon); turning off dials `##002#` / `*73`.
3. When the assistant picks up, the phone shows "Rivet is answering a call from …"; the message the caller
   leaves arrives as a notification and in `call_messages`, so you can ask Rivet to text them back.

The assistant reaches the brain at `RIVET_PUBLIC_URL/v1/calls/{session}/{token}/start|message`, where the
token is an HMAC of the session id under the device token.

## The Android app (`android/`)

Kotlin + Compose, minSdk 29, targetSdk 36. See [`android/README.md`](android/README.md) for building,
installing and the on-device checklist.

- **Robot**: the original `Rivet_the_Robot.html`, bundled with offline fonts and a small bridge
  (`android/robot/bridge.js`) so the app drives its moves, trace HUD, email and text cards, and lip sync
  from text-to-speech. Served from app assets only (no network, no file access).
- **Connection**: one OkHttp WebSocket per session in a foreground service, with `resume` on every
  reconnect, exponential backoff, an offline queue and re-sent unacknowledged turns.
- **Approvals**: a review sheet that answers a lane's approvals together, notification Approve/Deny that
  need the phone unlocked, and biometrics or the device credential for high-risk tiers.
- **Device tools**: `ActionLedger` records every action by the brain's tool use id, so a re-sent call returns
  its stored result and one interrupted by the app dying is reported as uncertain, never repeated.

## Run it

```bash
cd brain
uv sync --frozen --extra dev
export PERPLEXITY_API_KEY=... HORIZON_API_KEY=... RIVET_DEVICE_TOKEN=$(python -c 'import secrets; print(secrets.token_urlsafe(32))')
uv run rivet-brain            # serves :8080, /healthz and /v1/sessions/{session_id}
```

Configuration is in `brain/.env.example`. Secrets belong in your secret store (Fly secrets, cloud
environment secrets), never in the repo. Deploy with `fly deploy` from `brain/` (`fly.toml`, one machine
with a `/data` volume: sessions are single-writer).

## Test it

```bash
cd brain
uv run pytest -q -m "not live"   # unit, chaos (hangs, overflow, outages, kill -9, MCP drop), gateway
uv run pytest -q -m live         # real Perplexity + read-only Telnyx, when the keys are set
uv run ruff check src tests scripts && uv run mypy src
```

Tests never call Telnyx: a local MCP server stands in for Horizon. Live tests use Telnyx read-only, with
approvals on and any write denied.
