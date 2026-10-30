# AGENTS.md — Emrys

Guidance for any AI agent (or person) working in this repository. Read this first, then `README.md`,
`android/README.md`, `brain/README.md` and `docs/calls-live.md`.

Emrys is an Android phone assistant built by Ron AI (founder Tim Hunter, https://www.hi-ron.com,
support@hi-ron.com). It handles texts, email, calls, apps, reminders and tasks. Anything that sends, buys,
changes or deletes waits for the user's approval. Its face is the Emrys robot (`android/robot/Emrys_the_Robot.html`).

## Status (2026-10-28)

| Area | State |
|---|---|
| App | **0.4.0** (versionCode 7) on branch `ccr-22d59ed7-dzijhh` (draft PR #3): the robot kept in view on every screen (a mini stage on other tabs), tasks 3 and 4: plain alerts with "Don't ask again", standing approval rules, one notification per call, grouped notifications and recommended settings, on top of the v0.3 interface and the ElevenLabs agents. Release APK built and verified. Not yet tested on a physical phone; there is no emulator in the build container. The brain on Fly does not run this code yet: the app and the brain ship together (rules messages need the new brain). |
| Emrys rename and Noir facelift | Local branch `design/facelift`, uncommitted (2026-10-29): Rivet is renamed Emrys everywhere in code — app ID `com.hiron.emrys` (it installs as a new app; existing installs keep running as Rivet until removed), Kotlin packages and classes, the robot bridge (`window.emrys`, `EmrysHost`), `assets/emrys/`, the brain package `emrys_brain`, `EMRYS_*` settings, storage paths and the call assistant's name, tag and call-id variable — and the whole interface is redesigned (`docs/design/EMRYS_DESIGN.md`, `docs/design/EMRYS_MATRIX.md`). The brain still reads the pre-rename names (`brain/src/emrys_brain/legacy.py`: `RIVET_*` secrets, `session/<id>/rivet/` files, `memory/rivet/` memories carried over once; `calls.py`: the old assistant is found and renamed, and `rivet_call_id` is still answered), and `deploy_fly.sh` keeps an existing `RIVET_DEVICE_TOKEN` so the phone stays paired. Live resources keep their old names until the owner renames them: the Fly app `rivet-assistant` and its URL, the volume `rivet_data`, the repository `ImRonAI/rivet-assistant`, the ElevenLabs agents' prompts and the live Telnyx assistant (renamed on the next call answering setup). |
| Brain on Fly | `rivet-assistant` (https://rivet-assistant.fly.dev, region `sjc`, the owner's personal Fly organization) runs the brain with the ElevenLabs session endpoint (deployed 2026-10-28; `/healthz` ok, both agent credentials verified live). `EMRYS_DEVICE_TOKEN` and `ELEVENLABS_API_KEY` are set; **`PERPLEXITY_API_KEY` and `HORIZON_API_KEY` are not yet**, so chat and Telnyx don't work there until the owner sets them. Call answering still points at the old app `rivet-brain-hiron`, which keeps running until the owner shuts it down. Redeploy with `brain/scripts/deploy_fly.sh` and `FLY_LOCAL_BUILD=1` (Fly's remote builder isn't reachable from cloud sessions). If Docker Hub rate-limits the base image, pull `mirror.gcr.io/library/python:3.12-slim` and tag it `python:3.12-slim` first. |
| Search and models (brain) | The Brave search proxy (`/v1/search/*`, `/v1/suggest`, `/v1/spellcheck`), the brain's `search_*` tools with `search.results` events, turn attachments ("Add to Emrys"), and Groq `qwen/qwen3.8-27b` as a per-session model choice (`model.set`). Tested with fakes; each search route was probed once live against Brave through a local brain (all 200, 2026-10-29), and the streamed answers with citations once more (200 in 2.4 s, 11 citations, 2026-10-29). Deployed on `rivet-assistant` with the four `BRAVE_*` keys and `GROQ_API_KEY` set (2026-10-29); every search, suggest and spellcheck route answered 200 live there, and 401 without the device token. Groq has run only against fakes, never live, and switching models mid-session is covered by fakes only. |
| Tests | Brain: 403 pass (`-m "not live"`), ruff, format and mypy clean. Android: 278 pass and 1 is skipped (the live test, run with `EMRYS_LIVE=1`); lint has 0 errors. Both run without the gitignored `android/emrys.local.properties`, as CI does. |
| Telnyx | The answering number +17608769757 is routed to the Emrys call-answering assistant. Live-call features need the owner to re-run call answering setup once (the brain is already redeployed); it asks for approval. |
| ElevenLabs | Voice and text agents are wired and the brain's session endpoint returns real credentials for both agents (checked live against `rivet-assistant`). No full conversation has run on a phone yet. On by default for voice and text; Settings › "Use ElevenLabs agents" off sends everything to Emrys's own brain. The owner attached the 7 search client tools to both agents (q, count 1–20 default 10, freshness; 20 s timeout). The 18 phone tools are not attached to either agent yet, the old Cato demo tools still are, and the voice agent's LLM reads `claude-sonnet-4-5` (read 2026-10-29); those are the owner's to change in ElevenLabs. Both agents also have `enable_auth` off and carry webhook tools that use the owner's ElevenLabs key (read 2026-10-29; `docs/scaling.md` §2). The agents' `place_search` sends no location and the phone adds none, so Brave searches without a location unless `q` names the place ("coffee in austin tx"); whether Brave reads a place out of `q` is unverified. The conversation carried into a new session (voice to text and back) is tested with fakes only. |
| Search (app) | The app side of the Brave search UI is built and tested under Robolectric with MockWebServer: client tools, result cards, results page, map, image viewer, inline YouTube, suggestions, Spellcheck and "Add to Emrys". It needs the brain's search proxy (`/v1/search/*`, `/v1/suggest`, `/v1/spellcheck`, `search.results`, `model` events), which is deployed. Not checked on a phone: the WebGL map, YouTube playback and its Referer, sharing into real apps, and the widget sheet's keyboard. |
| Email | Built and tested against fake TLS servers. Off until the owner sets `EMRYS_EMAIL_ADDRESS` and `EMRYS_EMAIL_PASSWORD` (an app password) as Fly secrets. |
| Workspace VM | Google Cloud `instance-20261029-122759` (zone `us-central1-a`, c4-standard-8: 8 vCPU, 30 GB, Debian 13, 10 GB boot disk), provided by the owner on 2026-10-29 for task 2. **Not yet hardened or used.** It runs as the Compute Engine default service account with "Allow full access to all Cloud APIs", so any code on it can take that account's token; its `http-server` and `https-server` tags allow HTTP and HTTPS (the firewall rules themselves weren't listed), its IP is ephemeral and its disk is deleted with it. Cloud sessions have no access to it: the hardening commands in `docs/scaling.md` §5.1 are the owner's to run. |

## Rules that always apply

- **No Claude or Anthropic models in the product.** The brain uses Perplexity's Agent API; the call assistant uses a
  non-Anthropic Telnyx model.
- **No live side effects without explicit approval from the owner.** That means no sends, calls, purchases, Telnyx
  writes, DNS changes, production deploys, or real email. Tests use fakes: `brain/tests/telnyx_server.py`,
  `brain/tests/mail_server.py`, and MockWebServer on Android.
- **Never commit secrets.** `android/emrys.local.properties`, which holds the baked brain URL, session, device token and
  answering number, is gitignored. Fly secrets are set with `fly secrets set`, never in `fly.toml`.
- **Approvals fail closed and are decided deterministically, never by an LLM classifier.** See
  `brain/src/emrys_brain/approvals.py`.
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
docs/     calls-live.md: Telnyx schema evidence for live calls; scaling.md: the plan for many users and the workspace VM
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
EMRYS_LIVE=1 ./gradlew testDebugUnitTest --tests 'com.hiron.emrys.core.LiveBrainTest'   # needs emrys.local.properties
```

## Everything Emrys can do today

### Brain (`brain/src/emrys_brain/`)
- **Agent** (`factory.py`): `create_harness()` on `PerplexityAgentModel` (`models/perplexity.py`), or on `GroqModel` (`models/groq.py`, `qwen/qwen3.8-27b`, a Groq Preview model, stateless) when the session's model is Groq.
  - Native search runs server-side on Perplexity: web, URL fetch, image, finance, people. Groq has none, so its instructions leave that out.
  - `EMRYS_MODEL_PROVIDER` sets the default; a provider is offered only when its key is set. The phone picks per session with `model.set`, stored in `session/<id>/emrys/model.json`; the agent is rebuilt on the new provider and the conversation carries over. Refused while a turn or task runs or an approval waits; work that arrives during the switch, such as a call follow-up, waits for the new agent.
  - Memory, todos, an auto context manager and snapshot sessions on fenced storage.
  - No shell, file or subagent tools.
  - Instructions follow the tools actually registered.
- **Supervisor** (`supervisor.py`):
  - Watchdogs for hangs, context overflow (compaction), outages and crashes. It rebuilds the agent on the same session id and resumes without repeating side effects (write-ahead journal).
  - Background GoalLoop tasks run on their own lanes.
- **Approvals** (`approvals.py`):
  - Tiers: read, send, write, destructive, purchase, unknown.
  - Telnyx `execute` code is parsed, and each `call_tool` target is looked up in the generated operation catalog.
  - Each approval says in plain words what Emrys wants to do, to whom, and why (`alerts.py`: a headline, typed field/quote/note blocks and a "because" line), and the model gets a plain result when a call is declined, times out or is superseded.
  - Standing rules (`rules.py`): "Don't ask again" with Approve or Deny makes an always-allow or always-decline per action and recipient, stored per session in the session's storage (`session/<id>/emrys/approval-rules.json`) and checked deterministically before anything is asked. Purchases, deletes, unrecognised actions, app taps, Telnyx code and email replies to everyone on the email can only be always declined, and a stored always-allow is followed only where the brain would offer one for that call; an always-decline tells the model why.
  - A turn that reads untrusted content (messages, mail, web, screens) is "tainted": always-allows are suspended for it and the sheet offers only Deny. Always-declines still apply.
  - The gateway adds `rules`, `rules.list`, `rules.set`, `rules.revoke`, `rules.refused` and `rules.applied`, and a task's `reason`.
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
  - The carrier forwards calls to a Telnyx number whose AI assistant answers as Emrys and takes a message.
  - The phone gets a live transcript, by polling Telnyx's conversations API.
  - The user can take the call over (Call Control `TransferCall` to the user's own number).
  - An end-of-call summary arrives, and a background follow-up task proposes next steps.
- **Search** (`search.py`, `search_tools.py`), when the Brave keys are set (one per plan):
  - A proxy for the phone behind the device token: `GET /v1/search/{web|news|images|videos|places|context|answers}`, `GET /v1/suggest` and `GET /v1/spellcheck`. It maps Brave's responses to one envelope of typed items (YouTube watch URLs get a `youtube_id`), answers with plain-sentence errors (400, 401, 404, 429 with `retry_after`, 502, 503 when a plan's key is unset, 504), sends `Cache-Control: no-store` and keeps nothing. Logs hold the kind, status and latency only; httpx request lines are off and uvicorn access lines lose their query strings.
  - Seven `search_*` tools for Emrys's own agent (read tier; web content taints the turn). Each result also reaches the phone as a `search.results` event, so it renders as cards.
  - Answers stream from Brave with `enable_citations` (the only way Brave sends citations): the envelope carries the answer with each citation as `[n]` and `citations` [{number, url, snippet}]. For context, `count` is the number of pages (1 to 20, default 5). A place search may give `q` alone; Brave then searches without a location.
  - Every Brave call is bounded as a whole, reading included: 10 s for searches, 12 s for answers, 4 s for suggest and spellcheck, inside the phone's 15 s and the agents' 20 s. Brave recommends 30 s for LLM Context and Answers, so a heavy `context` or `answers` call may end in a 504 where Brave would have answered; how often is unmeasured (one live answers call took 2.4 s).
  - A `turn` can carry up to 5 `attachments` (search results the user added, `https://` links only); they reach the model marked as web content, and the turn is tainted. A turn left with no text and no usable attachment is accepted and then fails with a plain sentence, so the phone stops waiting.
  - Unverified: the brain path end to end with a live model; whether Brave's terms allow the results that enter the session snapshots (see the spec's blocker 1).
- **Gateway** (`gateway.py`): one WebSocket per session, a device token, and events buffered while the phone is away. Call webhooks are protected by an HMAC token.
  - `GET /v1/agent/session?mode=voice|text`, behind the device token, returns an ElevenLabs conversation token (voice) or signed URL (text) for the owner's agents (`ELEVENLABS_AGENTS`). The ElevenLabs API key stays on the brain.
  - `model` {model, available} on every `resume` and after a change, `model.refused` {error}, and `search.results` {lane, tool_id, results}.

### Android app (`android/app/src/main/java/com/hiron/emrys/`)
- **Interface (`ui/`)**: the Noir design system (`docs/design/EMRYS_DESIGN.md`) — near-black paper, porcelain ink, one pale-periwinkle halo accent, glacier/sage/honey/coral status; Instrument Serif for editorial moments, Geist for interface text, Geist Mono for figures; porcelain primary pills, glass cards, hairlines, status as a dot plus a sentence. The shared kit is in `Theme.kt`, `Kit.kt`, `Keys.kt`, `Surfaces.kt`, `Chrome.kt` and `Meters.kt`; the brand mark is the dot-matrix sigil (`res/drawable/emrys_sigil.xml`, from `docs/design/emrys-sigil.json`).
  - **Power-on screen** on first run (`Onboarding.kt`): four steps, starting with "Use recommended settings", "Set up step by step" and "Not now"; the second step has the "ElevenLabs agents" and "Talk key" switches.
  - **Recommended settings** (`ui/Recommended.kt`, `core/Recommended.kt`): a sheet from the power-on screen and from Settings listing only what would change (connect automatically, a quiet overlay, offer replies, answer only missed calls, the missing permissions in one prompt, the default assistant screen, and "Stop asking" for prompts this user declined 3 times in 30 days and never approved), each with a switch and its value now and after; Apply changes only what is chosen, and Settings can undo it.
  - **Home** (`HomeScreen.kt`):
    - a masthead (the sigil and a serif "Emrys") with one quiet status line, and the robot stage, which glows by state;
    - a serif greeting, and a transcript sheet you drag over the robot;
    - a "thinking" row while Emrys works;
    - the dock: quick keys (Catch me up, My day, Texts, Calls), a Task latch, the composer and the porcelain voice disc;
    - replies render markdown and citations (`Rich.kt`);
    - as you type, Brave Suggest offers up to 5 searches above the field (250 ms pause, 2+ characters) and Spellcheck a cyan "Did you mean …" (800 ms, 4+ characters), off with Settings › "Suggest searches as you type";
    - when the ElevenLabs agent can't start, the error says why in a plain sentence and, while the brain is connected, offers "Use Emrys's own brain".
  - **Search results** (`Results.kt`, `ResultsScreen.kt`, `ImageViewer.kt`, `VideoPlayer.kt`, `PlaceMap.kt`, `RemoteImage.kt`; `core/Search.kt`, `core/SearchClient.kt`): every search shows as numbered cards in the conversation, placeholders first:
    - web, news (with BREAKING) and page text in a swipeable carousel ending in "See all"; images in a row of tiles that open a full-screen viewer with pinch-zoom and Share (the image itself through a `FileProvider`, or its link when it can't be downloaded); videos with their length, playing inline in YouTube's embedded player (one at a time, `Referer: https://com.hiron.emrys`) or opening outside for other hosts; places on a MapLibre GL map (OpenFreeMap dark, vendored MapLibre 5.24.0 in `assets/map/`) with numbered pins over a pager of place cards (Call, Directions, Open); Brave's answer as formatted text with cyan `[n]` markers and its numbered sources under it (a tap opens one);
    - every card has Open, Share and "Add to Emrys" (only for `https://` links, the only ones the brain accepts): the ElevenLabs agent gets the result as a contextual update (its link, title, description, and the watch URL for a video), and Emrys's own brain gets it as a removable chip sent with the next turn; chips still waiting when the ElevenLabs agents are turned on go to the agent;
    - the results page has a search field with suggestions, tabs for Web, News, Images, Videos and Places (the Places tab's map takes gestures and a tapped pin raises its card), "Try again" when a search fails, and "Ask Emrys about …";
    - results stay in memory only. Built and tested under Robolectric with synthetic results; the map, the player and sharing are not yet checked on a phone.
  - **Live call plate** (`LiveCallCard.kt`): live transcript lines, Take over, and the summary when the call ends.
  - **Approval sheet** (`ApprovalSheet.kt`): the plain headline with contact names in place of numbers, why Emrys is asking, a risk meter, the message as a quote, Emrys's notes formatted, technical details folded away, and "Don't ask again" (Always allow / Always deny). Biometrics for high-risk actions and for every always-allow.
  - **Activity** (`ActivityScreen.kt`): today's counters, then the activity trace grouped by day.
  - **Settings** (`SettingsScreen.kt`): numbered plates for:
    - link and connection details;
    - behaviour toggles, with "Use recommended settings" and its undo, reading replies aloud in the overlay, the ElevenLabs agents and search suggestions (with Brave's 90-day log retention said plainly);
    - Emrys's own brain: Perplexity or Groq · Qwen 3.8 27B, as the brain reports it (`model` event); options the brain hasn't set up are disabled, and the choice is offline-disabled;
    - approval rules: each always-allow and always-decline, removable while connected;
    - call answering: number, mode, carrier codes, own number;
    - permissions, with "Allow all" (the microphone among them);
    - the unit plate.
- **Robot** (`ui/RobotView.kt`, `android/robot/bridge.js`, built into assets by `tools/build_robot_asset.py`):
  - the bundled page runs offline;
  - moves, the trace HUD and the email and text cards follow what Emrys is doing;
  - lip sync follows text-to-speech, and the stage glow follows Emrys's mood;
  - the figure (`android/robot/Emrys_the_Robot.html`): a porcelain hero with a smoked visor, a midnight cape and a dot-matrix emblem; armor slots for the Matrix (chest, visor voice band, gauntlets) and a light floor, each with a native fallback (`docs/design/EMRYS_MATRIX.md` §3);
  - the ElevenLabs UI layer (`android/stage-ui`, bundled into `assets/emrys/vendor/`): the `matrix`, `orb` and `shimmering-text` components installed verbatim with the official registry and checked by `npm run verify:components`. The Matrix is Emrys's light language (a glyph per state, viseme mouth shapes, the boot show, beat-locked dance floors); the Orb is a soft mood backlight; the caption names the work in progress. Real signals only: states, TTS word timing and the ElevenLabs agent's audio level. Verified with Playwright on the real page; not yet on a phone;
  - the robot stays in view: the big stage on Home and a short band on Activity, Settings and the voice setup, sharing one page, so its moves, mood and mouth carry across screens. Built and tested in Robolectric only; not yet checked on a phone.
- **Launcher** (`res/`, `widget/EmrysWidgets.kt`):
  - an adaptive icon with a themed monochrome layer (`tools/build_icon.py`);
  - long-press shortcuts: Talk, Ask, Catch me up, My day;
  - two widgets: the search bar (4×1) and the panel (4×2).
  - the bar's "Search or ask Emrys" opens the search sheet (`ACTION_SEARCH`): the results page with the field focused, Brave suggestions and "Did you mean", and Web results on Enter. Widgets can't take typing, so the field is in the app. The engine is Brave through the brain; the other engines of task 5 are not built.
- **Default assistant** (`voice/`): a `VoiceInteractionService` overlay over any app, with live speech-to-text through `SpeechRecognizer`, a keyboard fallback and "Open Emrys". It is locked out on a secured lock screen.
- **Voice**: speech input through `RecognizerIntent` / `SpeechRecognizer`, and replies spoken with Android TextToSpeech (`voice/Speaker.kt`).
- **ElevenLabs agents** (`voice/EmrysAgent.kt`), Settings › "Use ElevenLabs agents" (on by default; off talks to Emrys's own brain):
  - the talk key starts or stops a voice session, asking for the microphone first when it isn't allowed; the composer sends text to the text agent;
  - the agents call the 18 phone tools as client tools; sends, calls, message replies, calendar writes and commit-point taps follow the brain's standing rules (a read-only copy on the phone) or wait for the approval sheet, and a deny or a 20-second timeout refuses; reading texts, the calendar, notifications or the screen suspends always-allows until the user speaks again;
  - agent speech moves the robot's mouth, and messages land in the transcript;
  - 7 search client tools (`voice/SearchTools.kt`: `web_search`, `news_search`, `image_search`, `video_search`, `place_search`, `answers`, `llm_context`) call the brain's search proxy, show the cards, mark the turn untrusted, and tell the agent what is on screen in at most 8,000 characters: snippets for web and news, the image file, thumbnail, size and page for images, URL, length and creator for videos, address, coordinates and rating for places, page text for context, and the answer with its citations. `count` defaults to 10 and is held to 1 to 20 (for `llm_context` it is the number of pages); a `freshness` other than `pd`, `pw`, `pm` or `py` is ignored; `place_search` works with `q` alone, as the agents send no location;
  - a search tool answers within 17 s (the phone's call to the brain allows 15 s), before the agents cut a tool at 20 s; the phone tools keep their own 27 s budget;
  - a new session (starting, or switching between voice and text) first gets a contextual update with the conversation so far: the last 10 things the user and the agent said, at most 2,000 characters, labelled as the earlier conversation and the mode it was in. A session with nothing else to send is in use at once and gets it when the SDK reports the connection, or not at all if that takes over 15 s; release builds strip debug logging, which in the SDK echoes every contextual update;
  - built and unit-tested only, not verified against a live agent or on a phone.
- **Service**: a foreground WebSocket client with resume and an offline queue (`service/EmrysService.kt`, `core/GatewayClient.kt`).
  - The action ledger never repeats a device action.
  - Notifications cover approvals (titled with what Emrys wants, with Approve/Deny that need the phone unlocked), calls, reminders and things ready to open. Each call is one notification that moves from answering to live to its summary and carries the caller's message; approval notifications clear when the app comes to the front; each kind is grouped, with a summary only while two or more are showing. Verified under Robolectric only.
- **Device access**: SMS, contacts, calendar, call log, notification listener (reply in any app), call screening, and an accessibility "app operator" that shows a Stop overlay and asks at commit points.

## Remaining work (owner's list, in priority order)

Each task lists where to start and the documents to read first. Facts about external APIs must come from the linked
documents, not from memory. Record anything that can't be verified as a blocker instead of guessing.

### 1. Robot always in view, voiced by an ElevenLabs agent (realtime) or the language model alone
- **Goal:** the robot stays visible in the app (not only on Home) and speaks through an ElevenLabs voice agent using
  ElevenLabs' realtime conversational model. A setting switches between:
  - **ElevenLabs realtime voice:** speech in and speech out through the ElevenLabs agent. The agent's "LLM" is Emrys's brain.
  - **Language model only:** today's path, text in and out, with optional Android TTS.
- **Status:** a minimal first cut is built but unverified live (see "ElevenLabs agents" above). It follows the owner's
  agent design: two platform agents (voice `agent_2901m620nzwrev2r7ekqpydcxe25`, text
  `agent_9601m623qa72ffs9cyy3ezc0p6tb`) with the phone tools as client tools. Approvals for those tools run on the
  phone, not in the brain. Which model the agents run on is the owner's call in the ElevenLabs platform settings
  (never an Anthropic model). The persistent robot stage is built (one shared page, a mini-stage off Home) but not yet checked on a phone.
- **Design notes:**
  - Keep the brain as the single decision-maker and keep approvals intact. The ElevenLabs agent should call the brain as a custom LLM or through server or client tools, never act on its own.
  - Get private-agent conversation tokens from the brain; the ElevenLabs API key never goes on the phone.
  - Drive the robot's mouth from the agent's audio events (`emrys.viseme`, `emrys.mouth` in `bridge.js`).
  - The product must not use Anthropic models in the ElevenLabs agent configuration either.
- **Start in:**
  - `android/.../ui/HomeScreen.kt`, `ui/RobotView.kt` (a persistent mini-stage on other tabs);
  - `voice/Speaker.kt`, `voice/EmrysSession.kt`;
  - `brain/src/emrys_brain/gateway.py` (token endpoint).
- **Docs:**
  - ElevenAgents (formerly Agents Platform) overview: https://elevenlabs.io/docs/eleven-agents/overview
  - Android / Kotlin SDK: https://elevenlabs.io/docs/eleven-agents/libraries/kotlin (also https://elevenlabs.io/docs/agents-platform/libraries/kotlin)
  - WebSocket API: https://elevenlabs.io/docs/agents-platform/libraries/web-sockets
  - Custom LLM (point the agent at Emrys's brain): https://elevenlabs.io/docs/agents-platform/customization/llm/custom-llm
  - Full docs index for agents: https://elevenlabs.io/docs/llms.txt
  - The ElevenLabs MCP connector is available in Claude sessions for configuring agents. Treat agent creation as a live write that needs approval.

### 2. VPS workspace: openvscode and a virtual desktop
- **Goal:** Emrys can create and work on projects and documents on a VPS, not on the phone. That means an
  OpenVSCode Server and a browser-reachable Linux desktop, opened from the app (WebView or Custom Tab), with brain tools
  to start a workspace, create files and documents, and share results back to the phone.
- **Status:** a Google Cloud VM is provided for this (status table), not yet hardened or used. Its hardening, design,
  role and limits are in `docs/scaling.md` §5. Nothing is built.
- **Design notes:**
  - Put both behind authentication: Emrys's device token, or an OAuth proxy.
  - Use TLS only.
  - Each user gets their own workspace.
  - Workspace actions that change files are `write` tier; anything that deletes or spends money is high tier.
  - Fly.io machines with a volume are one option, alongside the current brain; any VPS works.
- **Start in:**
  - new `brain/src/emrys_brain/workspace_tools.py` (tools plus `POLICIES`, following `email_tools.py`);
  - a new app tab or plate for "Workspace".
- **Docs:**
  - OpenVSCode Server: https://github.com/gitpod-io/openvscode-server (Docker: `gitpod/openvscode-server`)
  - Webtop, a Linux desktop in the browser: https://docs.linuxserver.io/images/docker-webtop/
  - KasmVNC: https://www.kasmweb.com/kasmvnc/docs/latest/index.html
  - noVNC: https://github.com/novnc/noVNC
  - Fly.io machines and volumes: https://fly.io/docs/machines/ and https://fly.io/docs/volumes/
  - Android Custom Tabs: https://developer.chrome.com/docs/android/custom-tabs

### 3. Less noise: plain-language, formatted alerts with "Don't ask again" (built in 0.4.0)
- **Status:** built and tested in the brain and the app (see "Everything Emrys can do today"); not yet checked on a
  real phone or against the deployed brain. Open points: the ElevenLabs agents may have server-side tools the phone
  can't see, so the phone path can only taint on its own phone tools (only texts, calls and calendar events can be
  always-allowed there); the model learns of an always-decline only when it tries the action; notification grouping
  and alert-once behaviour on real launchers is unverified.
- **Problem:** at the highest access level, Emrys raises many approvals and notifications written in jargon. Examples
  are tool ids such as `sms_send` and `telnyx_execute`, tier names, and raw operation names.
- **Goal:**
  - Every alert says in plain words what Emrys wants to do, to whom, and why. For example: "Text Dana: 'Friday at 9 works!'"
    instead of "send · sms_send".
  - Render alert bodies as formatted text, using `ui/Rich.kt` in the app and plain text in notifications.
  - Add a **"Don't ask again"** choice that applies whether the user approves *or denies*:
    - **Approve + don't ask again:** a standing allow rule for that action and recipient.
    - **Deny + don't ask again:** a standing deny rule. Emrys stops proposing it and is told why.
  - Rules are listed and revocable in Settings.
  - Purchases and destructive actions can never be made standing allows.
  - Cut duplicate notifications, for example an approval notification plus the sheet, or a live-call notification
    plus a summary for the same call. Group the rest.
- **Start in:**
  - `brain/src/emrys_brain/approvals.py` (remembered approvals today are 30 minutes and allow-only; add durable allow and deny rules keyed like `scope_arg`);
  - `device_tools.py`, `calls.py` and `email_tools.py` (`ToolPolicy.summary` / `assess` text);
  - `android/.../ui/ApprovalSheet.kt`, `ui/ApprovalPreview.kt`, `service/Notifications.kt`, `core/Protocol.kt` (answer payload).
- **Docs:**
  - Android notification design: https://developer.android.com/develop/ui/views/notifications
  - Notification grouping: https://developer.android.com/develop/ui/views/notifications/group
  - Material writing guidance: https://m3.material.io/foundations/content-design/style-guide/ux-writing-best-practices

### 4. Recommended settings (built in 0.4.0)
- **Status:** built and tested (Robolectric). The default assistant cannot be requested by an app (the role is not
  requestable), so the recommendation opens Android's default apps screen. Changing the call answering mode does not
  change carrier forwarding. A fresh install has no "Stop asking" suggestions until the user has declined something
  three times.
- **Goal:** a one-tap "Use recommended settings" choice, shown on the power-on screen and in Settings. It sets safe,
  low-noise defaults:
  - connect automatically;
  - read replies aloud off when the overlay is used;
  - offer replies on;
  - call answering for unanswered calls only, which is the only mode that supports Take over;
  - the default assistant role requested;
  - the permissions Emrys needs requested together;
  - standing deny rules for the noisiest low-value prompts from task 3.
  
  Show exactly what each recommendation changes before applying it, and let the user undo it.
- **Start in:** `android/.../core/Settings.kt`, `ui/SettingsScreen.kt`, `ui/Onboarding.kt`.

### 5. A real search-bar widget on the home screen, backed by Ron's search engine
- **Goal:** the 4×1 widget sits on the phone's home screen and searches the web.
  - The default engine is the search engine from Ron's browser rebuild ("RonBrowserRebuild"). Users can switch to Google,
    Bing, DuckDuckGo, Brave or a custom `https://…?q=%s` URL in Settings.
  - The mic and "Ask Emrys" keep their current behaviour: they open the assistant.
  - Offer to pin the widget from the app with `AppWidgetManager.requestPinAppWidget`, from the power-on screen and from
    Settings, so users don't have to find it in the widget picker.
- **Note:** no repository named exactly `RonBrowserRebuild` is reachable from this environment. The candidates are:
  - https://github.com/ImRonAI/RonBrowser
  - https://github.com/ImRonAI/TheBrowser (most recently updated)
  - https://github.com/catoailabs/ronbrowser
  
  Confirm which one holds the search engine and its query URL format before wiring it. Until then, keep the engine URL a
  setting with no hard-coded guess.
- **Start in:**
  - `android/.../widget/EmrysWidgets.kt` (the `ask` click goes to a search intent);
  - `res/layout/widget_search.xml`, `core/Settings.kt` (`searchEngine`);
  - `ui/SettingsScreen.kt`.
- **Docs:**
  - App widgets: https://developer.android.com/develop/ui/views/appwidgets/overview
  - Pinning widgets: https://developer.android.com/develop/ui/views/appwidgets/configuration#pin
  - OpenSearch description format: https://github.com/dewitt/opensearch

### 6. Storage on Telnyx KV, through the Telnyx MCP (decided)
- **Decision (owner, 2026-10-28):** the brain's sessions, memory and action journal move from the Fly volume (`LocalFileStorage`)
  to a Telnyx KV namespace, reached through the Telnyx MCP server that is already wired. No new secrets.
- **Under review:** `docs/scaling.md` §6 (decision 2) recommends keeping user data out of Telnyx KV: through Horizon
  every call's arguments and results are logged by default and kept indefinitely, KV has no compare-and-swap, and one shared
  namespace separates users only in code. The owner decides.
- **Build:**
  - `brain/src/emrys_brain/storage/telnyx_kv.py`, implementing the Strands `Storage` protocol (`write`, `read`, `delete`, `list`, `search`) over `PutKvKey`, `GetKvKey`, `ListKvKeys` (with `prefix` and `cursor`) and `DeleteKvKey`.
  - Run them through `live_calls.TelnyxRunner`, as fixed code with no model-written code.
  - Keep `FencedStorage` wrapped around it.
- **Constraints, from the Telnyx schema in OubliTelnyxMCP `docs/reference/telnyx/openapi.json`:**
  - A value holds at most 1 MiB. Split larger snapshots into `key/part-N` entries plus a manifest.
  - Key names may use only `a-z A-Z 0-9 - _ / = .`, up to 256 characters, with no leading `_`. Encode Strands keys to fit.
  - A namespace is created asynchronously (`CreateKvNamespace`, then wait for `provision_ok`). That is a one-time Telnyx write and needs the owner's approval.
  - The namespace id goes in `EMRYS_TELNYX_KV_NAMESPACE`.
- **Tradeoffs:**
  - Each save becomes a Horizon round trip, so turns get slower; measure it.
  - Tenancy is one namespace with a prefix per session. Per-user namespaces come later.
- **References:**
  - `strands-py/src/strands/storage/storage.py`, the protocol (https://github.com/ImRonAI/strands-harness-sdk-python)
  - OubliTelnyxMCP `docs/reference/telnyx/domains/storage.md` and its KV end-to-end tests
  - Emrys's `brain/src/emrys_brain/storage/fenced.py` and `factory.py`

### 7. Scheduled routines (decided: a scheduler inside the brain)
- **Why not Strands:** Strands has no scheduler. The strands-agents-tools `cron` tool is deprecated (0.8.10, `strands_tools/cron.py`);
  its note recommends a hosted scheduler such as EventBridge, or the shell tool. Emrys has no shell tool, by design.
- **Build:** `brain/src/emrys_brain/routines.py`.
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
When a feature lands, update "Everything Emrys can do today" and the status table in the same change. When something is
built but unverified on a real phone or live service, say so.

### 9. Many users, safely (plan: `docs/scaling.md`)
- **Goal:** Emrys serves 10, then 100, 1,000, 100,000 and 1,000,000+ people with no loss of speed. No design rules out
  every breach; this one keeps a breach to one user or one cell, detects it and contains it.
- **Status:** planned, nothing built. Emrys is single-user today: one shared device token baked into the APK, session
  ids the phone picks, memory shared across sessions, and the owner's mailbox, Telnyx account and ElevenLabs agents
  behind every session. Text Emrys reads can also make it send data out unasked, through Perplexity's server-side
  `fetch_url`, which Emrys's approvals never see (`docs/scaling.md` §2).
- **Hold:** the repository is public, so `docs/scaling.md` and the rows of this file that name open exposures stay on
  the local `scaling-plan` branch until the owner confirms step 0 is done. Don't push them before then.
- **Next:** the owner's step 0 (a read-only check for misuse first, Horizon payload logging off, no APK hand-outs and
  the device token rotated on every Fly app, `rivet-brain-hiron` included, the ElevenLabs agents locked down,
  `fetch_url` dropped, the VM hardened, six decisions), then steps 1-10 to 10 users, starting with no secrets in the
  APK and per-user sign-in (`docs/scaling.md` §7).
- **Decisions waiting** (`docs/scaling.md` §6): the sign-in provider; where user data lives (this reopens task 6);
  Emrys-provided or bring-your-own numbers and agents; Gmail OAuth or app passwords; the ElevenLabs plan; one cloud or
  Fly.
- **Start in:** `brain/src/emrys_brain/gateway.py` (identity, sessions, limits), `factory.py` and `storage/` (per-user
  prefixes), `android/app/build.gradle.kts` and `core/Settings.kt` (nothing baked into release builds).

## Before launch (checklist)
1. Task 5 (the real search widget) above; task 1 (ElevenLabs voice) if voice is part of the launch. Tasks 3 and 4 are built; deploy the matching brain and check them on a phone.
2. Task 6 (Telnyx KV storage) and task 7 (routines).
3. **On-device pass** on a real phone. See "Verification gaps" below and the checklist in `android/README.md`.
4. **Live services:**
   - re-run call answering setup (it asks for approval) and place one real forwarded call;
   - set `EMRYS_EMAIL_*` as Fly secrets and test one real mailbox.
5. **Release signing:** the APK is signed with the debug key (`android/app/build.gradle.kts`). Create a release keystore and sign with it before any public distribution. See https://developer.android.com/studio/publish/app-signing.
6. **Secrets hygiene:** rotate the Fly token and the API keys that were pasted into chat sessions.
7. **CI:** confirm `.github/workflows/brain.yml` and `android.yml` pass on `main`.
8. **Store compliance (Play Store, if used):**
   - SMS and call-log permissions need a declared default-handler or permitted use case;
   - Accessibility use needs a disclosure;
   - see https://support.google.com/googleplay/android-developer/answer/10208820 and https://support.google.com/googleplay/android-developer/answer/10964491.
   - Sideloading avoids this.
9. **Owner credentials in the APK:** release builds bake in the brain URL, device token, session id and answering number
   (`android/app/build.gradle.kts`, `core/Settings.kt`), so whoever holds an APK is the owner's session. Hand none out,
   rotate `EMRYS_DEVICE_TOKEN` on every Fly app (APKs 0.1.2-0.2.0 carry `rivet-brain-hiron`'s), and ship per-user
   sign-in first (`docs/scaling.md` §2, §7).
10. **The workspace VM's service account:** the VM runs as the Compute Engine default service account with full Cloud
    API access, so any code on it can take that account's token. Swap in an account with no roles and finish
    `docs/scaling.md` §5.1 before anything runs there.
11. **ElevenLabs agents locked down:** both agents have `enable_auth` off and webhook tools that use the owner's
    ElevenLabs key. Turn auth on, detach the tools Emrys doesn't use, turn client overrides off and set retention
    (`docs/scaling.md` §7 step 0).
12. **No silent fetches:** the main model's Perplexity `fetch_url` runs outside Emrys's approvals, so an injected email
    or page can make Emrys read texts or mail and send them to any URL (not tried live). Drop it from the native tools
    (`brain/src/emrys_brain/models/perplexity.py`), with a test (`docs/scaling.md` §2 danger 4, §7 step 0).

## Verification gaps to close on a real phone
- **Widgets:** both widgets on a real launcher.
- **Alerts and rules:** contact names in approvals (needs Contacts); "Always allow" with biometrics; an always-decline
  stopping a later request; removing a rule in Settings; the ElevenLabs path following a rule.
- **Notifications:** grouping and summaries on a real launcher; one notification per call from answering to summary;
  an urgent message alerting once; approval notifications clearing when Emrys opens.
- **Recommended settings:** the permission prompt and the default apps screen returning to Emrys; undo.
- **Assistant overlay:**
  - it appears over other apps;
  - the first-use microphone grant works;
  - on Android 10 and 11, other apps' voice input still works through Emrys's recognizer.
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
- **Many users, safely, and the workspace VM:** `docs/scaling.md`
- **Android app build and on-device checklist:** `android/README.md`
- **Brain configuration:** `brain/.env.example`. Deploy with `brain/scripts/deploy_fly.sh`; set up call answering with `brain/scripts/setup_call_answering.py`.

## Addendum, 2026-10-29: the Emrys rename and the Noir facelift (branch `design/facelift`)

### What this work was
The owner asked for three things: a new name for Rivet (a human name, two syllables, confirmed available); a drastic,
elegant, editorial facelift of the whole app using the ElevenLabs UI components, with ElevenLabs' `Matrix` as the second
most prevalent visual after the robot; and a more sophisticated, superhero-like robot. Every action and state had to
keep working. The work ran in a separate clone on branch `design/facelift`, branched from `ccr-22d59ed7-dzijhh` at
`4be282e`, so the session working on that branch (the "Brave" thread) was never disturbed. One agent took each
visual: kit and app shell, Home, onboarding and setup, approvals, calls and the overlay, search, settings and activity,
the robot, the ElevenLabs stage layer, and the launcher. A lead integrated their work, did the rename and verified it.

### The name
**Emrys** (EM-riss): Welsh for "immortal", and Merlin's true name. Checked on 2026-10-29:
- no AI assistant called Emrys on Google Play or the web;
- `heyemrys.com`, `meetemrys.com` and `askemrys.com` are free (registrar lookup), and `emrys.ai` is listed for sale;
- the older EMRYS software trademarks are dead (per third-party aggregators, not TSDR).
This is not a legal trademark clearance.

### Done
- **Design system:** `docs/design/EMRYS_DESIGN.md` ("Noir": near-black, porcelain, one halo accent, Instrument
  Serif, Geist and Geist Mono) and `docs/design/EMRYS_MATRIX.md` (the Matrix's light language, armor slots and light
  shows). The tokens are in `ui/Theme.kt`, the OFL fonts in `res/font` and `assets/emrys/fonts` (`FONTS-OFL.txt`), and
  the sigil in `docs/design/emrys-sigil.json` and `res/drawable/emrys_sigil.xml`.
- **Compose app:** the shared kit (`Kit`, `Keys`, `Surfaces`, `Chrome`, `Meters`, `StageFrame`, `MiniStage`) and the
  tab shell, plus every screen: Home, onboarding, recommended settings, voice setup, the approval sheet, the live call
  card, the assistant overlay, search results and their page, the image viewer, video, the map page, Settings and
  Activity. Copy is in sentence case, and every test tag, role and content description is kept.
- **Robot** (`android/robot/Emrys_the_Robot.html`): a porcelain hero with a smoked visor, a crest, a midnight cape
  that sways without clipping, and a dark-glass emblem. All 42 moves and 14 states are kept. Noir stage cards, a
  spotlight floor, Matrix armor slots with native fallbacks, `emrys.slots()` and `emrys.scene()`. It costs the same
  per frame as before.
- **ElevenLabs UI** (`android/stage-ui`, bundled into `assets/emrys/vendor/`):
  - Components: `matrix`, `orb` and `shimmering-text`, installed with the official shadcn registry items from
    elevenlabs/ui@`3a28ebab` (the `ui.elevenlabs.io` registry answers bots with a challenge, so they were fetched from
    GitHub). They are byte-identical to upstream, and `npm run verify:components` checks that in CI.
  - The Matrix sits in the chest emblem, a voice band on the visor, the gauntlets, a light floor and the mini band:
    a glyph per state, viseme mouth shapes, the boot show and beat-locked dance floors.
  - The Orb is a soft mood backlight; ShimmeringText is the work caption.
  - Only real signals drive them. The Orb's noise texture is served offline by `RobotView.kt`.
- **Rename, everywhere in code:**
  - app ID `com.hiron.emrys` and the Kotlin packages and classes;
  - the bridge (`window.emrys`, `EmrysHost`), `assets/emrys/` and resources;
  - the brain package `emrys_brain`, `EMRYS_*` settings, storage paths, the persona prompt, and the call assistant's
    name, tag and call-ID variable;
  - CI and docs.
- **Compatibility with live data:**
  - `brain/src/emrys_brain/legacy.py` reads `RIVET_*` secrets as `EMRYS_*`, reads `session/<id>/rivet/` files, and
    copies `memory/rivet/` memories over once;
  - `calls.py` finds and renames the old Telnyx assistant and still answers `rivet_call_id`;
  - `deploy_fly.sh` keeps an existing `RIVET_DEVICE_TOKEN`, so the phone stays paired;
  - Gradle still reads an old `rivet.local.properties`.
- **Pre-existing bug fixed:** the robot never drew on a real device. Its WebView had `WRAP_CONTENT` height, which gives
  the page a 0 px layout viewport. It was reproduced with the untouched 0.4.0 Rivet build on an Android 15 emulator,
  `RobotView.kt` now gives the WebView `MATCH_PARENT`, and `AppFlowTest` guards it.
- **Verification on this branch:**
  - Android unit and screen tests and lint, the brain's pytest, ruff and mypy (counts in the commit message);
  - stage Playwright: 9 of 9;
  - `build_robot_asset.py --check` and `build_icon.py --check`;
  - a signed release APK (`com.hiron.emrys`, label "Emrys");
  - the release APK walked through on an emulator: onboarding, Home, Activity and Settings, with the robot and the
    Matrix drawing.

### Left to do
1. **Merge upstream.** `origin/ccr-22d59ed7-dzijhh` moved on while this was built (0.4.2 to 0.4.5: one page per robot
   slot, the simulations removed, warnings as errors, a type-checked bridge). Merge it into this branch, keeping every
   one of its behaviour changes, then rerun everything.
2. **Launcher art.** The launcher agent's icon, widgets, shortcuts and notification copy were still running in
   `/Users/tims-stuff/rivet-facelift-wt/launcher`. Integrate them through `rename_android.py` (the identifiers there are
   still the old ones). Until then the icon and widgets are the old drawings in Noir colours. The old fonts (Big
   Shoulders, IBM Plex) stay bundled until the widgets stop using them.
3. **Device checks still open:**
   - the approval sheet against a live brain, the voice setup, the search sheet, the overlay over another app, widgets
     and notifications;
   - the full stage on a real phone: the emulator dropped to `lowFx`, which turns off the Orb, floor and gauntlets by
     design;
   - the onboarding stage hint covers the robot's feet;
   - the CI Playwright step is unproven on Linux.
4. **Owner's live renames:**
   - the Fly app `rivet-assistant`, its URL and the volume `rivet_data`;
   - the repository `ImRonAI/rivet-assistant`;
   - the ElevenLabs agents' prompts, which still say Rivet;
   - the live Telnyx assistant, which is renamed on the next call answering setup;
   - a brain redeploy, so it says "Emrys";
   - optionally, renaming the Fly secrets to `EMRYS_*`.
5. **Installing:** `com.hiron.emrys` installs as a new app next to Rivet. Enter the brain settings once, then remove
   Rivet.
6. **Choices to confirm:** the light floor is 17×17, not the 25×25 in the spec, because of performance. "Don't ask
   again" stays a switch.
