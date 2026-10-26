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
| 0 | Perplexity contract probe (`brain/scripts/probe_perplexity.py`) | Script ready; needs `PERPLEXITY_API_KEY` |
| 1 | Brain: model, approvals, supervisor, gateway, chaos suite | **Built and tested** (120 tests) |
| 2 | Android shell: WebView robot, gateway client, approvals UI | Next |
| 3 | Device tools: SMS, notification replies, contacts, calendar, email, tasks | Brain-side bridge built (`device.py`); tools next |
| 4 | App operator (Accessibility) | Planned |
| 5 | Calls: screening, Telnyx AI Assistant answering | Planned; buying a number needs explicit go-ahead |

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
  tool calls routed to the phone by action id (the phone's ledger dedups them).

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
