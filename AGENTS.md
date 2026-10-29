# AGENTS.md — Rivet

Guidance for any AI agent (or person) working in this repository. Read this first, then `README.md`,
`android/README.md`, `brain/README.md` and `docs/calls-live.md`.

Rivet is an Android phone assistant built by Ron AI (founder Tim Hunter, https://www.hi-ron.com,
support@hi-ron.com). It handles texts, email, calls, apps, reminders and tasks. Anything that sends, buys,
changes or deletes waits for the user's approval. Its face is the Rivet robot (`android/robot/Rivet_the_Robot.html`).

## Status (2026-10-28)

| Area | State |
|---|---|
| App | **0.3.0** (versionCode 6) on branch `ccr-22d59ed7-dzijhh` (draft PR #3): the interface redesigned from the owner's 11 Stitch screens (4 onboarding steps, 7 voice setup screens) plus the ElevenLabs agents. Release APK built and verified. Not yet tested on a physical phone; there is no emulator in the build container. |
| Brain on Fly | `rivet-assistant` (https://rivet-assistant.fly.dev, region `sjc`, the owner's personal Fly organization) runs the brain with the ElevenLabs session endpoint (deployed 2026-10-28; `/healthz` ok, both agent credentials verified live). `RIVET_DEVICE_TOKEN` and `ELEVENLABS_API_KEY` are set; **`PERPLEXITY_API_KEY` and `HORIZON_API_KEY` are not yet**, so chat and Telnyx don't work there until the owner sets them. Call answering still points at the old app `rivet-brain-hiron`, which keeps running until the owner shuts it down. Redeploy with `brain/scripts/deploy_fly.sh` and `FLY_LOCAL_BUILD=1` (Fly's remote builder isn't reachable from cloud sessions). If Docker Hub rate-limits the base image, pull `mirror.gcr.io/library/python:3.12-slim` and tag it `python:3.12-slim` first. |
| Tests | Brain: 239 pass (`-m "not live"`), ruff, format and mypy clean. Android: 157 pass and 1 is skipped (the live test, run with `RIVET_LIVE=1`); lint has 0 errors. Both run without the gitignored `android/rivet.local.properties`, as CI does. |
| Telnyx | The answering number +17608769757 is routed to the Rivet call-answering assistant. Live-call features need the owner to re-run call answering setup once (the brain is already redeployed); it asks for approval. |
| ElevenLabs | Voice and text agents are wired and the brain's session endpoint returns real credentials for both agents (checked live against `rivet-assistant`). No full conversation has run on a phone yet. Off until the user turns on Settings › "Use ElevenLabs agents". |
| Email | Built and tested against fake TLS servers. Off until the owner sets `RIVET_EMAIL_ADDRESS` and `RIVET_EMAIL_PASSWORD` (an app password) as Fly secrets. |

## Rules that always apply

- **No Claude or Anthropic models in the product.** The brain uses Perplexity's Agent API; the call assistant uses a
  non-Anthropic Telnyx model.
- **No live side effects without explicit approval from the owner.** That means no sends, calls, purchases, Telnyx
  writes, DNS changes, production deploys, or real email. Tests use fakes: `brain/tests/telnyx_server.py`,
  `brain/tests/mail_server.py`, and MockWebServer on Android.
- **Never commit secrets.** `android/rivet.local.properties`, which holds the baked brain URL, session, device token and
  answering number, is gitignored. Fly secrets are set with `fly secrets set`, never in `fly.toml`.
- **Approvals fail closed and are decided deterministically, never by an LLM classifier.** See
  `brain/src/rivet_brain/approvals.py`.
- **Match the surrounding code:** naming, comment density, and evergreen comments with no "previously" or "now".
  Commits use conventional commit messages.
- **Validate before handing over an APK:**
  - `./gradlew testDebugUnitTest lintDebug assembleRelease`;
  - review the screenshots in `android/build/screens/`;
  - verify the APK (`apksigner verify`, `aapt2 dump badging`);
  - run the live test against Fly.

## Repository map

```
brain/    Python 3.12 agent service (Strands harness + Perplexity Agent API + Telnyx MCP on Prefect Horizon)
android/  Kotlin/Compose app (minSdk 29, targetSdk 36), robot WebView, widgets, assistant overlay
docs/     calls-live.md: Telnyx schema evidence for live calls
.github/  CI: brain.yml (ruff, format, mypy, pytest, docker build), android.yml (asset check, tests, lint, release)
```

Commands:
```bash
# brain
cd brain && uv sync --frozen --extra dev
uv run pytest -q -m "not live" && uv run ruff check src tests scripts && uv run ruff format --check src tests scripts && uv run mypy src
# android
cd android && python3 tools/build_robot_asset.py --check && python3 tools/build_icon.py --check
./gradlew testDebugUnitTest lintDebug assembleRelease
RIVET_LIVE=1 ./gradlew testDebugUnitTest --tests 'com.hiron.rivet.core.LiveBrainTest'   # needs rivet.local.properties
```

## Everything Rivet can do today

### Brain (`brain/src/rivet_brain/`)
- **Agent** (`factory.py`): `create_harness()` on `PerplexityAgentModel` (`models/perplexity.py`).
  - Native search runs server-side: web, URL fetch, image, finance, people.
  - Memory, todos, an auto context manager and snapshot sessions on fenced storage.
  - No shell, file or subagent tools.
  - Instructions follow the tools actually registered.
- **Supervisor** (`supervisor.py`):
  - Watchdogs for hangs, context overflow (compaction), outages and crashes. It rebuilds the agent on the same session id and resumes without repeating side effects (write-ahead journal).
  - Background GoalLoop tasks run on their own lanes.
- **Approvals** (`approvals.py`):
  - Tiers: read, send, write, destructive, purchase, unknown.
  - Telnyx `execute` code is parsed, and each `call_tool` target is looked up in the generated operation catalog.
  - A turn that reads untrusted content (messages, mail, web, screens) is "tainted"; remembered approvals are suspended for it.
  - Remembered approvals are scoped per recipient.
- **Phone tools** (`device_tools.py`), 18 of them, run on the phone over the WebSocket:
  - SMS send and list, contacts, recent calls, placing a call;
  - calendar list and create;
  - reading notifications and replying to them in any app;
  - reminders, an email draft, listing and opening apps, device status, the answering number;
  - the screen operator: read the screen, then tap, type or scroll.
- **Email** (`email_tools.py`), when configured: `email_search`, `email_read`, `email_send`, `email_reply` and `email_mark` (read, unread, archive) over IMAP/SMTP.
  - TLS only; it never deletes.
  - Reading mail taints the turn.
- **Calls** (`calls.py`, `live_calls.py`): call answering on the user's own number.
  - The carrier forwards calls to a Telnyx number whose AI assistant answers as Rivet and takes a message.
  - The phone gets a live transcript, by polling Telnyx's conversations API.
  - The user can take the call over (Call Control `TransferCall` to the user's own number).
  - An end-of-call summary arrives, and a background follow-up task proposes next steps.
- **Gateway** (`gateway.py`): one WebSocket per session, a device token, and events buffered while the phone is away. Call webhooks are protected by an HMAC token.
  - `GET /v1/agent/session?mode=voice|text`, behind the device token, returns an ElevenLabs conversation token (voice) or signed URL (text) for the owner's agents (`ELEVENLABS_AGENTS`). The ElevenLabs API key stays on the brain.

### Android app (`android/app/src/main/java/com/hiron/rivet/`)
- **Interface (`ui/`)**: the Rivet design system — dark graphite, safety orange, and cyan/green/amber/crimson status; Big Shoulders, IBM Plex Sans and Plex Mono. The shared kit is in `Theme.kt`, `Kit.kt` and `Chrome.kt`.
  - **Power-on screen** on first run (`Onboarding.kt`): a checklist with one "Turn everything on" key.
  - **Home** (`HomeScreen.kt`):
    - a telemetry header (MODE, LINK, QUEUE) and the robot stage, which glows by state;
    - a display-type greeting, and a transcript sheet you drag over the robot;
    - a "thinking" row while Rivet works;
    - the dock: quick keys (Catch me up, My day, Texts, Calls), a Task latch, the composer and an orange talk key;
    - replies render markdown and citations (`Rich.kt`).
  - **Live call plate** (`LiveCallCard.kt`): live transcript lines, Take over, and the summary when the call ends.
  - **Approval sheet** (`ApprovalSheet.kt`): readable fields, a risk meter, and biometrics for high-risk actions.
  - **Activity** (`ActivityScreen.kt`): today's counters, then the activity trace grouped by day.
  - **Settings** (`SettingsScreen.kt`): numbered plates for:
    - link and connection details;
    - behaviour toggles;
    - call answering: number, mode, carrier codes, own number;
    - permissions, with "Allow all";
    - the unit plate.
- **Robot** (`ui/RobotView.kt`, `android/robot/bridge.js`, built into assets by `tools/build_robot_asset.py`):
  - the bundled page runs offline;
  - moves, the trace HUD and the email and text cards follow what Rivet is doing;
  - lip sync follows text-to-speech, and the stage glow follows Rivet's mood;
  - the robot stays in view: the big stage on Home and a short band on Activity, Settings and the voice setup, sharing one page, so its moves, mood and mouth carry across screens. Built and tested in Robolectric only; not yet checked on a phone.
- **Launcher** (`res/`, `widget/RivetWidgets.kt`):
  - an adaptive icon with a themed monochrome layer (`tools/build_icon.py`);
  - long-press shortcuts: Talk, Ask, Catch me up, My day;
  - two widgets: the search bar (4×1) and the panel (4×2).
  - **The search bar currently opens Rivet. It is not yet a web search** (see task 5).
- **Default assistant** (`voice/`): a `VoiceInteractionService` overlay over any app, with live speech-to-text through `SpeechRecognizer`, a keyboard fallback and "Open Rivet". It is locked out on a secured lock screen.
- **Voice**: speech input through `RecognizerIntent` / `SpeechRecognizer`, and replies spoken with Android TextToSpeech (`voice/Speaker.kt`).
- **ElevenLabs agents** (`voice/RivetAgent.kt`), behind Settings › "Use ElevenLabs agents" (off by default):
  - the talk key starts or stops a voice session; the composer sends text to the text agent;
  - the agents call the 18 phone tools as client tools; sends, calls, message replies, calendar writes and commit-point taps wait for the approval sheet, and a deny or a 20-second timeout refuses;
  - agent speech moves the robot's mouth, and messages land in the transcript;
  - built and unit-tested only, not verified against a live agent or on a phone.
- **Service**: a foreground WebSocket client with resume and an offline queue (`service/RivetService.kt`, `core/GatewayClient.kt`).
  - The action ledger never repeats a device action.
  - Notifications cover approvals (with Approve/Deny that need the phone unlocked), live calls (Take over), call summaries and reminders.
- **Device access**: SMS, contacts, calendar, call log, notification listener (reply in any app), call screening, and an accessibility "app operator" that shows a Stop overlay and asks at commit points.

## Remaining work (owner's list, in priority order)

Each task lists where to start and the documents to read first. Facts about external APIs must come from the linked
documents, not from memory. Record anything that can't be verified as a blocker instead of guessing.

### 1. Robot always in view, voiced by an ElevenLabs agent (realtime) or the language model alone
- **Goal:** the robot stays visible in the app (not only on Home) and speaks through an ElevenLabs voice agent using
  ElevenLabs' realtime conversational model. A setting switches between:
  - **ElevenLabs realtime voice:** speech in and speech out through the ElevenLabs agent. The agent's "LLM" is Rivet's brain.
  - **Language model only:** today's path, text in and out, with optional Android TTS.
- **Status:** a minimal first cut is built but unverified live (see "ElevenLabs agents" above). It follows the owner's
  agent design: two platform agents (voice `agent_2901m620nzwrev2r7ekqpydcxe25`, text
  `agent_9601m623qa72ffs9cyy3ezc0p6tb`) with the phone tools as client tools. Approvals for those tools run on the
  phone, not in the brain. Which model the agents run on is the owner's call in the ElevenLabs platform settings
  (never an Anthropic model). The persistent robot stage is built (one shared page, a mini-stage off Home) but not yet checked on a phone.
- **Design notes:**
  - Keep the brain as the single decision-maker and keep approvals intact. The ElevenLabs agent should call the brain as a custom LLM or through server or client tools, never act on its own.
  - Get private-agent conversation tokens from the brain; the ElevenLabs API key never goes on the phone.
  - Drive the robot's mouth from the agent's audio events (`rivet.viseme`, `rivet.mouth` in `bridge.js`).
  - The product must not use Anthropic models in the ElevenLabs agent configuration either.
- **Start in:**
  - `android/.../ui/HomeScreen.kt`, `ui/RobotView.kt` (a persistent mini-stage on other tabs);
  - `voice/Speaker.kt`, `voice/RivetSession.kt`;
  - `brain/src/rivet_brain/gateway.py` (token endpoint).
- **Docs:**
  - ElevenAgents (formerly Agents Platform) overview: https://elevenlabs.io/docs/eleven-agents/overview
  - Android / Kotlin SDK: https://elevenlabs.io/docs/eleven-agents/libraries/kotlin (also https://elevenlabs.io/docs/agents-platform/libraries/kotlin)
  - WebSocket API: https://elevenlabs.io/docs/agents-platform/libraries/web-sockets
  - Custom LLM (point the agent at Rivet's brain): https://elevenlabs.io/docs/agents-platform/customization/llm/custom-llm
  - Full docs index for agents: https://elevenlabs.io/docs/llms.txt
  - The ElevenLabs MCP connector is available in Claude sessions for configuring agents. Treat agent creation as a live write that needs approval.

### 2. VPS workspace: openvscode and a virtual desktop
- **Goal:** Rivet can create and work on projects and documents on a VPS, not on the phone. That means an
  OpenVSCode Server and a browser-reachable Linux desktop, opened from the app (WebView or Custom Tab), with brain tools
  to start a workspace, create files and documents, and share results back to the phone.
- **Design notes:**
  - Put both behind authentication: Rivet's device token, or an OAuth proxy.
  - Use TLS only.
  - Each user gets their own workspace.
  - Workspace actions that change files are `write` tier; anything that deletes or spends money is high tier.
  - Fly.io machines with a volume are one option, alongside the current brain; any VPS works.
- **Start in:**
  - new `brain/src/rivet_brain/workspace_tools.py` (tools plus `POLICIES`, following `email_tools.py`);
  - a new app tab or plate for "Workspace".
- **Docs:**
  - OpenVSCode Server: https://github.com/gitpod-io/openvscode-server (Docker: `gitpod/openvscode-server`)
  - Webtop, a Linux desktop in the browser: https://docs.linuxserver.io/images/docker-webtop/
  - KasmVNC: https://www.kasmweb.com/kasmvnc/docs/latest/index.html
  - noVNC: https://github.com/novnc/noVNC
  - Fly.io machines and volumes: https://fly.io/docs/machines/ and https://fly.io/docs/volumes/
  - Android Custom Tabs: https://developer.chrome.com/docs/android/custom-tabs

### 3. Less noise: plain-language, formatted alerts with "Don't ask again"
- **Problem:** at the highest access level, Rivet raises many approvals and notifications written in jargon. Examples
  are tool ids such as `sms_send` and `telnyx_execute`, tier names, and raw operation names.
- **Goal:**
  - Every alert says in plain words what Rivet wants to do, to whom, and why. For example: "Text Dana: 'Friday at 9 works!'"
    instead of "send · sms_send".
  - Render alert bodies as formatted text, using `ui/Rich.kt` in the app and plain text in notifications.
  - Add a **"Don't ask again"** choice that applies whether the user approves *or denies*:
    - **Approve + don't ask again:** a standing allow rule for that action and recipient.
    - **Deny + don't ask again:** a standing deny rule. Rivet stops proposing it and is told why.
  - Rules are listed and revocable in Settings.
  - Purchases and destructive actions can never be made standing allows.
  - Cut duplicate notifications, for example an approval notification plus the sheet, or a live-call notification
    plus a summary for the same call. Group the rest.
- **Start in:**
  - `brain/src/rivet_brain/approvals.py` (remembered approvals today are 30 minutes and allow-only; add durable allow and deny rules keyed like `scope_arg`);
  - `device_tools.py`, `calls.py` and `email_tools.py` (`ToolPolicy.summary` / `assess` text);
  - `android/.../ui/ApprovalSheet.kt`, `ui/ApprovalPreview.kt`, `service/Notifications.kt`, `core/Protocol.kt` (answer payload).
- **Docs:**
  - Android notification design: https://developer.android.com/develop/ui/views/notifications
  - Notification grouping: https://developer.android.com/develop/ui/views/notifications/group
  - Material writing guidance: https://m3.material.io/foundations/content-design/style-guide/ux-writing-best-practices

### 4. Recommended settings
- **Goal:** a one-tap "Use recommended settings" choice, shown on the power-on screen and in Settings. It sets safe,
  low-noise defaults:
  - connect automatically;
  - read replies aloud off when the overlay is used;
  - offer replies on;
  - call answering for unanswered calls only, which is the only mode that supports Take over;
  - the default assistant role requested;
  - the permissions Rivet needs requested together;
  - standing deny rules for the noisiest low-value prompts from task 3.
  
  Show exactly what each recommendation changes before applying it, and let the user undo it.
- **Start in:** `android/.../core/Settings.kt`, `ui/SettingsScreen.kt`, `ui/Onboarding.kt`.

### 5. A real search-bar widget on the home screen, backed by Ron's search engine
- **Goal:** the 4×1 widget sits on the phone's home screen and searches the web.
  - The default engine is the search engine from Ron's browser rebuild ("RonBrowserRebuild"). Users can switch to Google,
    Bing, DuckDuckGo, Brave or a custom `https://…?q=%s` URL in Settings.
  - The mic and "Ask Rivet" keep their current behaviour: they open the assistant.
  - Offer to pin the widget from the app with `AppWidgetManager.requestPinAppWidget`, from the power-on screen and from
    Settings, so users don't have to find it in the widget picker.
- **Note:** no repository named exactly `RonBrowserRebuild` is reachable from this environment. The candidates are:
  - https://github.com/ImRonAI/RonBrowser
  - https://github.com/ImRonAI/TheBrowser (most recently updated)
  - https://github.com/catoailabs/ronbrowser
  
  Confirm which one holds the search engine and its query URL format before wiring it. Until then, keep the engine URL a
  setting with no hard-coded guess.
- **Start in:**
  - `android/.../widget/RivetWidgets.kt` (the `ask` click goes to a search intent);
  - `res/layout/widget_search.xml`, `core/Settings.kt` (`searchEngine`);
  - `ui/SettingsScreen.kt`.
- **Docs:**
  - App widgets: https://developer.android.com/develop/ui/views/appwidgets/overview
  - Pinning widgets: https://developer.android.com/develop/ui/views/appwidgets/configuration#pin
  - OpenSearch description format: https://github.com/dewitt/opensearch

### 6. Storage on Telnyx KV, through the Telnyx MCP (decided)
- **Decision (owner, 2026-10-28):** the brain's sessions, memory and action journal move from the Fly volume (`LocalFileStorage`)
  to a Telnyx KV namespace, reached through the Telnyx MCP server that is already wired. No new secrets.
- **Build:**
  - `brain/src/rivet_brain/storage/telnyx_kv.py`, implementing the Strands `Storage` protocol (`write`, `read`, `delete`, `list`, `search`) over `PutKvKey`, `GetKvKey`, `ListKvKeys` (with `prefix` and `cursor`) and `DeleteKvKey`.
  - Run them through `live_calls.TelnyxRunner`, as fixed code with no model-written code.
  - Keep `FencedStorage` wrapped around it.
- **Constraints, from the Telnyx schema in OubliTelnyxMCP `docs/reference/telnyx/openapi.json`:**
  - A value holds at most 1 MiB. Split larger snapshots into `key/part-N` entries plus a manifest.
  - Key names may use only `a-z A-Z 0-9 - _ / = .`, up to 256 characters, with no leading `_`. Encode Strands keys to fit.
  - A namespace is created asynchronously (`CreateKvNamespace`, then wait for `provision_ok`). That is a one-time Telnyx write and needs the owner's approval.
  - The namespace id goes in `RIVET_TELNYX_KV_NAMESPACE`.
- **Tradeoffs:**
  - Each save becomes a Horizon round trip, so turns get slower; measure it.
  - Tenancy is one namespace with a prefix per session. Per-user namespaces come later.
- **References:**
  - `strands-py/src/strands/storage/storage.py`, the protocol (https://github.com/ImRonAI/strands-harness-sdk-python)
  - OubliTelnyxMCP `docs/reference/telnyx/domains/storage.md` and its KV end-to-end tests
  - Rivet's `brain/src/rivet_brain/storage/fenced.py` and `factory.py`

### 7. Scheduled routines (decided: a scheduler inside the brain)
- **Why not Strands:** Strands has no scheduler. The strands-agents-tools `cron` tool is deprecated (0.8.10, `strands_tools/cron.py`);
  its note recommends a hosted scheduler such as EventBridge, or the shell tool. Rivet has no shell tool, by design.
- **Build:** `brain/src/rivet_brain/routines.py`.
  - Store routines in the KV storage from task 6.
  - Repeat rules use RFC 5545 RRULE with a time zone (`dateutil.rrule`), plus triggers on incoming notifications.
  - An asyncio loop in the gateway starts due routines as GoalLoop tasks with `_Session.run_task`, so approvals, taint and notifications all apply.
  - Tools: `routine_create`, `routine_list` and `routine_cancel`. Create is write tier. A routine can never buy or delete; its runs still ask before sending unless a standing rule from task 3 allows it.
  - App: a "Routines" plate in Activity or Settings.
- **One-off timed actions** use Telnyx's own scheduling through the MCP:
  - `ScheduleMessage` (SMS);
  - `create_scheduled_event` (an AI-assistant call or text);
  - the email `/schedule` operations.
- **References:**
  - https://dateutil.readthedocs.io/en/stable/rrule.html
  - https://datatracker.ietf.org/doc/html/rfc5545#section-3.3.10
  - the Telnyx schema operations named above

### 8. Keep this file honest
When a feature lands, update "Everything Rivet can do today" and the status table in the same change. When something is
built but unverified on a real phone or live service, say so.

## Before launch (checklist)
1. Tasks 3 (plain alerts and "Don't ask again"), 4 (recommended settings) and 5 (the real search widget) above; task 1 (ElevenLabs voice) if voice is part of the launch.
2. Task 6 (Telnyx KV storage) and task 7 (routines).
3. **On-device pass** on a real phone. See "Verification gaps" below and the checklist in `android/README.md`.
4. **Live services:**
   - re-run call answering setup (it asks for approval) and place one real forwarded call;
   - set `RIVET_EMAIL_*` as Fly secrets and test one real mailbox.
5. **Release signing:** the APK is signed with the debug key (`android/app/build.gradle.kts`). Create a release keystore and sign with it before any public distribution. See https://developer.android.com/studio/publish/app-signing.
6. **Secrets hygiene:** rotate the Fly token and the API keys that were pasted into chat sessions.
7. **CI:** confirm `.github/workflows/brain.yml` and `android.yml` pass on `main`.
8. **Store compliance (Play Store, if used):**
   - SMS and call-log permissions need a declared default-handler or permitted use case;
   - Accessibility use needs a disclosure;
   - see https://support.google.com/googleplay/android-developer/answer/10208820 and https://support.google.com/googleplay/android-developer/answer/10964491.
   - Sideloading avoids this.

## Verification gaps to close on a real phone
- **Widgets:** both widgets on a real launcher.
- **Assistant overlay:**
  - it appears over other apps;
  - the first-use microphone grant works;
  - on Android 10 and 11, other apps' voice input still works through Rivet's recognizer.
- **Live calls:** after redeploy and setup, a real forwarded call, Take over (`TransferCall` on an assistant-answered TeXML call is undocumented), and the summary turn.
- **Email:** a real Gmail or iCloud mailbox, including the possible duplicate copy in Gmail's Sent folder.
- **Screen operator:** Accessibility and notification access on Android 13+ with restricted settings.

## Key documents
- **Strands harness and SDK:** https://strandsagents.com/docs/user-guide/harness/ (repository: https://github.com/ImRonAI/strands-harness-sdk-python)
- **Perplexity Agent API:** https://docs.perplexity.ai/ (the OpenAI Responses-compatible endpoint is `https://api.perplexity.ai/v1`)
- **Telnyx MCP server:**
  - Source: https://github.com/ImRonAI/OubliTelnyxMCP
  - Canonical schema: `docs/reference/telnyx/openapi.json`, in that repository
  - Live endpoint: https://oubli-catolabs.fastmcp.app/mcp, on Prefect Horizon
- **Live-call design and its blockers:** `docs/calls-live.md`
- **Android app build and on-device checklist:** `android/README.md`
- **Brain configuration:** `brain/.env.example`. Deploy with `brain/scripts/deploy_fly.sh`; set up call answering with `brain/scripts/setup_call_answering.py`.
