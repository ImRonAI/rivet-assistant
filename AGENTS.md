# AGENTS.md — ren mobile

Guidance for any AI agent (or person) working in this repository. Read this first, then `android/README.md`.

ren mobile (app name **Ren**, `com.hiron.ren`; Emrys until 2026-11-12, Rivet before that) is the phone side of ren, the
browser built by Oubli AI (founder Tim Hunter, https://oubliai.com, tim@oubliai.com). It is a lightweight Android
browser with ren's search and an AI agent panel, and it still does everything Emrys did: texts, email drafts, calls,
apps, reminders and tasks on the phone through the owner's ElevenLabs agents. ren desktop is the Electron browser in
`ImRonAI/TheBrowser` (the owner's checkout is `RonBrowserRebuild`). Its face is Cato, the robot of catolabs.com,
recoloured to ren's volt (`android/web/src/robot/`).

The ElevenLabs agents still call themselves Emrys: their prompts, the webhook tool `call_as_emrys` and the VM's wire
names (`emrys-computer`, `EMRYS_*`, `/opt/emrys`) are unchanged, and the sections on them below say Emrys.

## ren mobile, 2026-11-12

What the overhaul added, on the branch `claude/project-thread-tnj6xo` (PR #14) and, for ren desktop,
`claude/ren-mcp-hub` in `ImRonAI/TheBrowser` (PR #3):

- **Design: Ink & Volt**, as ren desktop's Electron code implements it (`tailwind.config.ts` and `src/index.css` there
  are the truth; `docs/mobile-design.md` there describes an older palette and is not followed). Volt `#5543FF` in Light
  and `#8D98FE` in Dark, ink `#0B0C17`, colourless dark surfaces, flat surfaces with no glows, 10 dp controls, 12 dp
  cards. Bricolage Grotesque (display), Inter and JetBrains Mono, as variable fonts in `res/font/`. The logo is the
  "ren." logotype (`docs/design/ren-logotype.json`, copied from ren desktop's `logotype.ts`); `tools/build_icon.py`
  generates the launcher icon, the R notification icon and the wordmark from it. Light and Dark follow the phone;
  Glass is not built.
- **Robot: Cato**, unmodified in `android/web/src/cato/` (github.com/ImRonAI/cato-labs at `2a7a9b6`, hashes in
  `SHA256SUMS`). `scripts/gen-ren-robot.mjs` generates ren's robot from it into `src/robot/`: the same geometry, rig,
  moves and renderer, with Cato's blue changed to volt and an sRGB step added to the body shader (Cato's phone tier
  skips it, so its white shell draws grey without the bloom pass). The app drives it through `window.ren.*`
  (`src/robot/bridge.ts`, `ui/RobotView.kt`).
- **Browser** (`browser/Browser.kt`, `browser/Addresses.kt`, `ui/BrowserScreen.kt`, `ui/BrowserHome.kt`): the app
  opens on it. Tabs are real WebViews, made on a tab's first navigation; an address bar that loads addresses and
  searches phrases (ported from ren desktop's `navigable-url.ts` and `UrlBar.tsx`); back, forward, reload, stop; a tabs
  sheet; downloads, uploads and fullscreen video; new windows become tabs; page permissions are refused. Home is
  `ren://home` with the logotype, a search box and the five search levels. **Ask Ren** slides the agent panel
  (`HomeScreen.kt` under a header) over the browser.
- **Search**: `ren://search?q=…&level=…` is ren desktop's own results page. `android/web/src/ren/` holds
  `src/pages/SearchResultsPage.tsx` and everything it imports from ren desktop at `b112adf`, unmodified (hashes in
  `SHA256SUMS`, `npm run check:ren`), with ren desktop's Tailwind config and `index.css`. `src/search/` mounts it:
  the query and level come from the address, Dark follows the phone, a result opens in a new tab (`window.open`, which
  the browser turns into a tab), and `host.ts` adds the workspace token to the page's requests. The page gets the
  token from the `RenHost` web message listener, which answers only the app's bundled origin
  (`https://appassets.androidplatform.net`). The backend is ren desktop's own search and Discover routers on the VM
  (`deploy/vm/search`, image built from `ImRonAI/TheBrowser` at `b112adf`), at `https://rivet.ron-ai.dev/search/*` and
  `/discover/*` behind the same token. "Add search to Ren context" and "Search deeper" need ren desktop's context
  store and Electron, so the first fails on the phone and the second is not shown.
- **Ren's hub** (`deploy/vm/hub/app.py`, FastMCP 3.4.0 and mcp 1.26.0, the versions ren desktop pins; the `hub`
  container at `https://rivet.ron-ai.dev/hub/` behind `EMRYS_GEMINI_TOKEN`): the one place the phone, ren desktop and
  the agents meet. A phone and a desktop are behind home routers, so each dials out
  (`wss://…/hub/attach/phone`, `/attach/desktop`, WebSocket subprotocol `mcp`) and serves its own MCP server on that
  socket; the hub is an MCP client there (`ProxyProvider` over the live session, `cache_ttl=0`) and an MCP server at
  `/hub/mcp` (Streamable HTTP). The phone's tools keep their names, ren desktop's are under `desktop_`, and
  `hub_peers` says which side is attached. `/hub/agents/mcp` is the same hub for the ElevenLabs agents, which already
  run the phone's tools as client tools: only the phone's `browser_*` tools, `desktop_*` and `hub_peers`.
  FastMCP 4.1.0 pulls mcp 2.3.0, which has no WebSocket transport, which is why the hub is on 3.4.0.
- **The phone on the hub** (`mcp/HubLink.kt`, `mcp/HubService.kt`, `mcp/PhoneMcpServer.kt`, `mcp/BrowserTools.kt`;
  Settings › Remote tools): a foreground service keeps the phone attached with the Kotlin MCP SDK 0.16.0
  (`Server.createSession(WebSocketClientTransport)`), proves the link with a ping every 20 s and retries from 2 s to
  60 s. It serves the 18 phone tools, `request_approval`, the seven searches and 13 browser tools (`browser_tabs`,
  `browser_open`, `browser_navigate`, `browser_back`, `browser_forward`, `browser_reload`, `browser_select`,
  `browser_close`, `browser_read`, `browser_click`, `browser_type`, `browser_scroll`, `browser_screenshot`) through the
  same handlers as the client tools. `HubLink.tools()` and `call()` make the phone an MCP client of the hub too. The
  old listener on the phone's Tailscale address is gone (the owner's Mac has no Tailscale, so nothing could reach it).
  `browser_click` runs the page's `click()` without a user gesture, so Chromium may not add a history entry for it.
- **ren desktop on the hub** (`agent/api/core/ren_mcp.py` in `ImRonAI/TheBrowser`): FastMCP server `ren-desktop` at
  `/mcp/` with the browser under `browser_` (a proxy over the `electron-playwright-mcp` server its own agents drive)
  and Ren's agent as `ask_ren`, `ren_threads`, `ren_thread` and `ren_cancel`. With `REN_HUB_URL` and `REN_HUB_TOKEN`
  set the backend attaches to the hub, and the panel agent's tool catalog gains the server `phone` (the hub's tools,
  prefixed `phone_`).
- **The Strands agents on the hub** (`deploy/vm/gemini/app.py`): the server `ren-hub` (`http://hub:8300/mcp`,
  unprefixed) replaces the phone's Tailscale address, so Gemini, NIM and Mistral reach the phone and ren desktop
  whichever is attached.
- **ElevenLabs is out of credits (2026-11-12):** a typed question on the emulator opened a session with the text
  agent (tools registered, both MCP servers connected), and ElevenLabs closed it 8 s later with WebSocket code 1002,
  "This request exceeds your quota limit." The SDK reports any close but 1000 as `DisconnectionDetails.Error`, and the
  panel now shows it ("ElevenLabs ended the conversation: …") where it used to go back to Ready in silence. No
  ElevenLabs reply has been seen in ren mobile yet.
- **Live, 2026-11-12:** with the hub deployed, the emulator's app and ren desktop's backend (the branch above, run
  from a clone on the owner's Mac) both attached. A client on `/hub/mcp` listed 76 tools (40 from the phone, 36 from
  the desktop); `device_status` returned the emulator's real battery and network; the desktop's own Strands
  `MCPClient` (`hub_client_factory`) opened https://example.com in the phone's browser and read it back; and
  `/hub/agents/mcp` listed 50 tools. The search page rendered in the app. Not run: ren desktop's browser tools
  through the hub (Electron wasn't running), anything on a physical phone.

## How it works

The app connects straight to the owner's two ElevenLabs agents with the official ElevenLabs Android SDK (0.13.0) and
answers their client tools on the phone. Nothing sits in between: no backend, no gateway, no wrapper around the agents.

- **Session start** (`voice/RenAgent.kt`): `ConversationConfig(agentId, userId, dynamicVariables, textOnly, clientTools, …)`.
  - Voice agent `agent_2901m620nzwrev2r7ekqpydcxe25`, text agent `agent_9601m623qa72ffs9cyy3ezc0p6tb` (public agents,
    so `agentId` alone starts a session; no API key on the phone).
  - `userId` is a UUID made once per install (`Settings.userId`) and also sent as the `user_id` dynamic variable.
  - Eight `perm_*` dynamic variables (`perm_use_apps`, `perm_send_messages`, `perm_read_messages`, `perm_purchases`,
    `perm_install_apps`, `perm_cloud_coding`, `perm_deploy`, `perm_create_music`), each `granted` or `prompt`, from
    Settings › Permissions (`core/Approvals.kt`, `core/Settings.kt`). How the agents use them is set in their prompts,
    which the owner manages.
- **Client tools** (`AgentTools` in `voice/RenAgent.kt`, run by `device/DeviceTools.kt`), 18 phone tools with no gate
  on the phone: `sms_send`, `sms_list`, `contacts_search`, `calls_recent`, `phone_call`, `calendar_list`,
  `calendar_create`, `notifications_list`, `notification_reply`, `reminder_create`, `reminders_list`,
  `reminder_cancel`, `email_draft`, `apps_list`, `app_open`, `device_status`, `screen_read`, `screen_act`.
- **`request_approval`** (client tool, `action_type` + `details`): shows the native approval sheet (`ui/ApprovalSheet.kt`)
  with what Emrys wants to do, an "Always allow" switch, Deny, and Approve behind biometrics or the screen lock. It
  answers `{"approved": …, "remember_decision": …}`; "Always allow" stores that `perm_<action>` as `granted` for later
  sessions. While Emrys is in the background the request is also a notification. The SDK ends every client tool call
  at 30 seconds, which withdraws an unanswered request (logged as expired).
- **Agents' own tools** (configured by the owner in the ElevenLabs dashboard, read 2026-10-31): both agents have the
  MCP servers `oubliai-telnyx` (https://oubli-catolabs.fastmcp.app/mcp) and `emrys-computer`
  (https://rivet.ron-ai.dev/computer/mcp, bearer-gated). The workspace also holds webhook tools that use the owner's
  ElevenLabs key.
- **Background tasks** (`deploy/vm/computer/hooks.py`, the `hooks` container at https://hooks.oubliai.com; deployed
  2026-11-03, live run the same day below):
  1. Either agent calls the async webhook tool `start_background_task`, and `POST /api/task` answers `started` at once.
     It takes `task` and `conversation_id` (from `system__conversation_id`), and `title`, `done_when` and the eight
     `perm_*` when the tool sends them; a missing `perm_*` asks first. The tool's description says Emrys phones the
     owner with the result itself, so the brief leaves calling back out. A conversation that ends with work it didn't
     hand off starts it through its post-call `background_task` data collection item instead.
  2. hooks.py records the job in `/config/workspace/jobs/<conversation>/<n>/job.json` and holds a text conversation with
     the Task agent "Rivet (Task)" (`agent_4901m6gdwby2fetbqddcpfkevvpp`): the text agent without the client tools or
     `start_background_task`, text only, 7200 s long. It speaks the documented WebSocket protocol rather than the SDK's
     `AsyncConversation`, whose `start_session` and `end_session` have no type annotations. The brief (`Title`, `Task`,
     `Done when`) is the first message, the `perm_*` are dynamic variables, the conversation id is the job's `session`,
     and the agent's replies go to `session.log`.
  3. The Task agent works through emrys-computer, oubliai-telnyx and the search webhooks, checks `done_when` itself and
     calls ElevenLabs' built-in `end_call` (attached 2026-11-04). Its post-call webhook, matched to the job by `session`, carries the report as data collection:
     `outcome` (ready, not_ready, needs_owner), `result`, `where`, `checked`, `blocker`, and `opening` (owner-approved
     2026-11-04), the line the callback starts with: why Emrys is calling back and how the task went. A session that ends without an
     outcome reports not_ready with ElevenLabs' termination reason; one that never starts reports why.
  4. Once the conversation the task came from has ended (its post-call webhook, or 600 s at most), the voice agent calls
     the owner from +17608769757 with the job's eight `perm_*` (its tools require them) and `original_request`, `source_summary`, `job_outcome`,
     `job_result`, `job_where`, `job_checked` and `job_blocker` (each `none` by default), the report's `opening` as its
     first message (empty for a session that never started, so it waits for the owner's hello), and its procedure
     "Report background task results"
     (`agtprc_2801m6ge2jbzebp8126nvrbc7520`) started: it checks the work with its own tools, then says whether it's
     ready. A refused call is placed once more after 60 s; every attempt's `success` and `message` are in `job.json`.
     A callback (`job_outcome` not `none`) never starts a task from its own post-call data, so a misread report can't
     loop.

  Live run, 2026-11-03: a request typed in Emrys on the emulator reached the text agent
  (`conv_4501m6grm404fpdsebhe55vknaba`), which called `start_background_task` with a title, `done_when` and all eight
  `perm_*`. The Task agent (`conv_3701m6grnh7mea2vxc8c6nhrqere`) wrote `/config/workspace/toolbox-versions.md` through
  emrys-computer in 22 s, every version matching the commands, and checked it. With no `end_call` it called
  `create_dub` with `name: "end_call"` (HTTP 400, nothing made), and the session stayed open until ElevenLabs ended it
  5414 s in ("Conversation timed out after the last agent message"). The post-call report (`ready`, with result, where
  and checked) reached hooks.py, which placed the callback at once because the text chat had ended. The callback
  (`conv_8601m6gxtz4jfberz3cr1wzkk4tm`) was answered, then ended after 7 s with error 1008 "Missing required dynamic
  variables in tools" for the eight `perm_*`, which the callback didn't send. hooks.py passes them on the callback (`58b5f83`,
  deployed the same day with `docker compose -p emrys build computer` and `up -d --no-deps hooks`, so only hooks was
  recreated; the previous file is `computer/hooks.py.before-callback-perms-20261103-211725`). After that deploy both
  endpoints answer 401 without their secrets and a task without `task` gets 422.

  Second live run, 2026-11-04 00:27 UTC, with the fix: a task typed in Emrys (text chat
  `conv_4901m6hz7jgsep0a8shh65chmjcc`) reached hooks in 20 s with all eight `perm_*`, and the Task agent
  (`conv_8101m6hz849rew8stvgpmjv42888`) wrote `/config/workspace/callback.md` (`date -u`, `uname -r`, both matching a
  fresh run) within seconds and reported ready, saying it had no way to end the session. Restarting the hooks container
  closed the session; its post-call report (`ready`) arrived within a second, and the callback waited for the text chat
  to end (about 9 minutes). Both callback attempts (`conv_2301m6hzt0xwe0qs274p47c6pvdn`,
  `conv_9501m6hzw9ynfmq8dedychk4eph3`) were refused with "[quota_exceeded] You've run out of credits", so the fix
  hasn't been heard on a call yet.

  Third live run, 2026-11-04 04:37 UTC, with `opening` (`2d36d67`): the same request plus "call me back when it is
  done" (text chat `conv_1501m6jdhvhke59trn18azx9xdmk`). The text agent wrote calling the owner back into the brief,
  since neither its prompt nor `start_background_task` says Emrys calls back with the report. The Task agent
  (`conv_7901m6jdk6tmfwjr59qwdcp5sbqd`) wrote and checked `callback.md` in 6 s, then, as its prompt allows a call the
  brief asks for, placed its own TeXML call from +17608769757 through oubliai-telnyx (`CreateTexmlCall`, queued 04:40)
  that read out the result, and ended its session with `end_call` at 287 s. The post-call report (`ready`) copied that
  call's script into `opening`, result and "Goodbye" included. hooks.py placed the callback when the text chat ended
  (`conv_3201m6je4b8kfeg8tx18xv5w76wx`, 04:47): it opened with that line, reached voicemail, and the voice agent ended
  it with `voicemail_detection` after 6 s without leaving a message.

  Fourth live run, 04:55 UTC, after the owner's OK to do whatever makes the calling agent know what it's doing:
  `start_background_task`'s description says Emrys phones the owner itself, and `opening` says to write the line
  fresh, why Emrys is calling and how it went, with no introduction or goodbye. The same request (text chat
  `conv_2001m6jejz48fjs97zykg7ykxfbq`) gave a brief ending "Do not call or text the owner, because reporting back
  happens automatically". The Task agent (`conv_6901m6jem6ewewwvy5xa0jv5g1a3`) wrote and checked `callback.md` and
  ended with `end_call` in 10 s, with no call of its own. The one callback (`conv_7201m6jet80sft0rjxqf9dzn2crw`,
  04:59, answered) opened "I'm calling back about writing the UTC date and kernel version to callback.md. That's all
  done and checked."; the owner hung up 10 s in, so nothing after the opening was tested.
- **Calls Emrys places** (owner-approved 2026-11-04): asked to call the owner back or to call someone, either agent
  hands the conversation to the voice agent on the phone with the webhook tool `call_as_emrys`
  (`tool_8901m6j86w2mfqa8ew5hkxvrgqs9`, on both agents). It posts to ElevenLabs' own
  `/v1/convai/sip-trunk/outbound-call` with the workspace secret `xi_api_key`: the voice agent calls from
  +17608769757 with the eight `perm_*`, `call_thread` (the conversation so far, `system__conversation_history`) and,
  written by the calling agent, `to_number` (the owner's number is in the tool's description, not in git),
  `call_to`, `call_reason` and the call's `first_message`, the line Emrys says when they pick up. The call starts the
  voice agent's procedure "Continue a conversation by phone" (`agtprc_0501m6j84vnafrcs0naakt8506da`), which reads
  those variables (placeholders `none`). Both prompts say Emrys can phone out, that a `sip_trunk` call is one Emrys
  placed when an active procedure says so, and when to use `call_as_emrys` rather than `phone_call`.

  The first message carries the reason because the voice agent runs Eleven Realtime v1 Max, whose quick replies
  didn't act on the started procedure. Live, 2026-11-04 03:11 UTC, with an empty first message
  (`conv_9301m6j8kcdde2ztjmercs82v6m9`): the thread, reason and `perm_*` all arrived, yet the first two replies were
  "I was wondering when you'd call" and "I don't actually have a reason for calling"; only the third, reasoned reply
  used the reason. On that call the agent also called `device_status`, which can't run on a phone call, and then said
  the phone was "plugged in and charging", which no tool returned. With the text agent's first message
  (`conv_7601m6j92ff3e849z3d8d95qe203`, 03:19 UTC) the call opened "Hey, calling you back about your Wi-Fi. Your
  phone's connected to Wi-Fi right now, and the battery's at 100 percent", the owner said thanks, and the agent ended
  the call with `end_call`. Neither text chat started a background task from its post-call data.
- **Transcript:** agent replies are shown without their expressive audio tags (`[warmly]`, `[surprised]`), which the
  voice still performs. `AgentTools.withoutAudioTags` uses the pattern from ElevenLabs' own widget (convai-widget-core
  `stripAudioTags`), so markdown links stay.
- **Strands providers: Gemini, NVIDIA NIM and Mistral** (Settings › Agents; ElevenLabs is the default): Emrys's agents
  in Strands on the workspace VM (`deploy/vm/gemini/app.py`, the `gemini` container at `wss://rivet.ron-ai.dev/gemini/`,
  behind Caddy's bearer `EMRYS_GEMINI_TOKEN`). The app's `voice/Assistant.kt` is the seam: `RenRuntime.agent` is
  `RenAgent` or `StrandsAgent`, and the screens use only its six members. Settings › Agents picks the provider, the text
  model from the provider's live catalogue (`GET /gemini/models?provider=`, searchable; none chosen is the VM's default)
  and the model's reasoning level (`GET /gemini/efforts`, with Auto first); the session's WebSocket carries `provider`,
  `model` and `effort`.
  - NIM and Mistral are ren's (`ImRonAI/TheBrowser` at `0c973a6`; `b112adf`'s reordering of tool results is not copied,
    because Strands 1.64.0's formatter already puts them first), copied into `providers.py`, `mistral_model.py`,
    `media_capabilities.py` and `context_windows.py`: NIM is Strands' `OpenAIModel` on `integrate.api.nvidia.com/v1`
    (default `z-ai/glm-5.3-flash`), Mistral is ren's `RonMistralModel` (default `mistral-large-latest`), each passed to
    `create_harness(model=<Model>)`, with ren's per-model reasoning tables. `providers.py`'s header lists what the copies
    change for mypy and for Strands 1.64.0. Keys: `NVIDIA_NIM_API_KEY` and `MISTRAL_API_KEY` in `gemini.env`.
  - Text (`/gemini/text`): the Strands harness, `create_harness(model="google/<text model>", effort=…)` for Gemini, with
    every built-in on (shell, read, write, edit, web_fetch, Google Search, programmatic_tool_caller, subagent, todos,
    memory, sessions) plus python_repl, http_request, notebook, sleep, stop, handoff_to_user, swarm, graph and
    mcp_router. Gemini's default text model is gemini-4-argon.
  - Voice (`/gemini/voice`): `BidiAgent` with `GoogleGeminiLiveModel` on gemini-3.8-live, voice Algieba, the same
    vended tools except swarm and graph (subagent and web_fetch run on the text model) and the bidi docs'
    `end_conversation`. Live, 2026-11-06 on the Gemini API: `enable_affective_dialog` is refused (1007 "Request
    contains an invalid argument"), and Strands 1.64.0 sends swarm's and graph's schemas as `additional_properties`,
    which Live refuses (1007 "Unknown name"); without both, a spoken "What is 2 + 2?" got "2 + 2 is 4." in
    24 kHz audio with both transcripts. The
    phone streams 16 kHz PCM and plays 24 kHz PCM (`voice/GeminiAudio.kt`), cleared on `bidi_barge_in`.
  - Both reach Ren's hub (`http://hub:8300/mcp`: the phone and ren desktop), emrys-computer and oubliai-telnyx
    (`${TELNYX_MCP_AUTHORIZATION}`), unprefixed so the names match the prompt, and use the ElevenLabs text and voice
    agents' own prompts, read when a conversation opens and filled like ElevenLabs' dynamic variables. The chat is
    built from Strands' events (`voice/GeminiEvents.kt`). `start_background_task`, `call_as_emrys`, Google Calendar
    and the ElevenLabs webhook tools have no Gemini counterpart yet.
- **Phone MCP server** (`mcp/`, Settings › Remote tools): served on Ren's hub; see "The phone on the hub" above.
- **Release builds** keep the SDK's private fields (`android/app/proguard-rules.pro`). The SDK reads the voice
  conversation token into a private field with Gson, and its own keep rules cover only public members. Without the
  rule, R8 renames the field, the token comes back blank and voice fails with "Couldn't reach the ElevenLabs agent".
  R8's own `-dontwarn` rules cover Ktor's `java.lang.management` check, under the phone's MCP server.

## Status (2026-10-31)

| Area | State |
|---|---|
| App | **0.5.0** (versionCode 13, `com.hiron.emrys`). Everything is on `main` (PRs #5 to #8). The release build ships arm64-v8a only (19.2 MB; the WebRTC library in the SDK is about 12 MB per ABI). |
| Verified | 109 unit and screen tests pass, Kotlin compiles with warnings as errors, lint has 0 errors. On the Android 15 emulator (2026-10-31) a typed question opened a session straight to the text agent (`conv_2801m68vtz0te2natfxrg5cyc6gp`): `user_id` and all eight `perm_*` arrived, the agent called `device_status`, the phone returned real data and the agent answered with the battery level. The arm64-only release APK, with the keep rule and the tag filter, installed on the emulator and opened a voice session with the voice agent ("Listening"; the agent's greeting appeared without its audio tags). |
| Not verified | Talking to the voice agent through a real microphone (the emulator runs without audio, so only the connection and the agent's greeting were checked); `request_approval` from a live agent (the handler is registered, but on 2026-10-31 the tool was not attached to either agent — attaching it is the owner's call); anything on a physical phone. |
| Attachments | Images and PDFs are not built: SDK 0.13.0 has no way to send files (`OutgoingEvent` has no multimodal message). The WebSocket protocol's `multimodal_message` with `file_input` exists only in the JS SDK and raw API. |
| Workspace VM | Google Cloud `instance-20261029-122759` (us-central1-a, c4-standard-8, Debian 13, 50 GB, static IP 136.113.136.145). `/opt/emrys` runs Caddy 2.11.7, the laptop (linuxserver webtop, https://desktop.rivet.ron-ai.dev), OpenVSCode (https://code.rivet.ron-ai.dev) and the `computer` container (Desktop Commander 0.3.1 behind FastMCP's proxy at `/computer/mcp`, reachable only with `EMRYS_COMPUTER_TOKEN`). The laptop (`deploy/vm/laptop/Dockerfile`, image `emrys-laptop:ls219`) and the computer image (`node:26.13.0-bookworm`) both run `deploy/vm/toolbox/install.sh`, so the owner's terminal, Desktop Commander and the hooks jobs share one set of pinned, checksum-checked developer tools: Node 26.13.0 LTS with npm/npx, pnpm, yarn, the Vercel CLI, TypeScript and tsx, Python 3 with pip, venv and pipx, uv/uvx, Bun, Deno, Go, gh from GitHub's apt repository, build tools and common CLIs. Rust (rustup 1.29.2, stable, minimal profile plus clippy and rustfmt) is in the shared home (`~/.rustup`, `~/.cargo`, on PATH via `~/.bashrc`, `~/.profile` and the computer image's `PATH`). OpenCode on the laptop runs Mistral `zai-glm-5-3` at `reasoningEffort: max`. It is OpenCode 2.2.5 (2026-11-01, from the v2 installer with the npm tarball's integrity checked; 1.19.5 is kept as `/config/.opencode/opencode-1.19.5.bak`), with the shared background service disabled (`opencode service set disabled true`, in `~/.config/opencode/service.json`) so every container's `opencode` runs a private server with its own environment. It reads the V1 `opencode.json` as is, except `whitelist`, which V2 ignores. Its MCP servers are context7, deepwiki, github (GitHub's remote server with the `ItzOubliAI` fine-grained token read from `~/.config/opencode/github-token`, mode 600, only on the VM), playwright (Chromium in `~/.cache/ms-playwright`), vercel and linear; the last two need a one-time OAuth sign-in from the laptop desktop (`opencode`, then `/mcps`). The owner signed in on 2026-11-01 (Vercel user `iamronai`, Linear team Cato-labs `CAT`), and the hooks jobs use those sign-ins from the shared home; the owner approved the Vercel team `team_dQkiAxSaM1x2NGxpqfymb7kT` (`tim-hi-roncoms-projects`) for that sign-in through `authorize_team_access` the same day, and `get_team`/`list_projects` for it succeed from the hooks container. The computer image puts `/config/.opencode/bin` and `/config/.cargo/bin` on `PATH`. A fresh private server lists no MCP servers for its first seconds, so `opencode mcp list` prints "No MCP servers configured" and `opencode mcp auth <name>` cannot find the server; against a server that has been up 15 s the list is complete, and a real `opencode run` attaches them. The brain is off the VM (deployed 2026-10-31 from `deploy/vm`: `docker compose -p emrys up -d --remove-orphans` removed `emrys-brain-1`); `https://rivet.ron-ai.dev/` answers 404, `/computer/mcp` 401 without the token and Desktop Commander's `initialize` with it. The previous files are kept as `compose.yaml.before-brain-removal-*` and `conf/Caddyfile.before-brain-removal-*`, and `data/`, `src/` and `brain.env` are still on disk. Reach it with `gcloud compute ssh instance-20261029-122759 --project fair-expanse-493212-h8 --zone us-central1-a --tunnel-through-iap`. Env files (`brain.env`, `laptop.env`, `computer.env`, `elevenlabs.env`, `hooks.env`, `code-token`) live only on the VM. The `hooks` container (`deploy/vm/computer/hooks.py`, https://hooks.oubliai.com, DNS on Vercel) serves the agents' async webhook tool `POST /api/task` (bearer from the ElevenLabs workspace secret `emrys_hooks_authorization`) and the post-call webhook `POST /api/post-call` (HMAC, ElevenLabs webhook "Emrys post-call" `cb517c50a8ab47818657c68e60d80ada`). Deployed 2026-11-01 with OpenCode doing each job (`opencode run --standalone --auto`) and a callback carrying `job_dir`; the Task agent version (see Background tasks) replaced it on 2026-11-03 (`docker compose -p emrys build computer`, then `up -d computer hooks`; the previous files are kept as `*.before-task-agent-20261103-104022`). After that deploy, `/api/task` and `/api/post-call` answer 401 without their secrets, a task without `task` gets 422, and `/computer/mcp` still answers `initialize` with its token. Both agents have the `start_background_task` tool and the post-call webhook; only the text agent has the `background_task` data collection item. Live run the same day: the text chat `conv_0701m6b181kpe78trarsr9jdbsx7` died on an LLM timeout, the post-call webhook started its task, and OpenCode built and deployed https://transilvanya-roses.vercel.app through the Vercel MCP (it exited 1 after its built-in web search was cancelled; a clean run exits 0). The callback reached the owner (`conv_1701m6b8m26me11ryhp498m9v4jq`) once the Telnyx connection's Caller ID Override was cleared. The 2026-11-01 version did not check `outbound_call`'s `success`; the current one records every attempt in `job.json`. |
| Telnyx outbound | ElevenLabs calls out through `sip.telnyx.com` as credential user `nursetimmy1988` with caller ID +17608769757. The connection's Caller ID Override must stay empty: when it held the owner's cell, Telnyx swapped that number in and refused every call with `403 Unverified origination number D51`. The phone number has "Store SIP messages" on, so `GET /v1/convai/conversations/<id>/sip-messages` shows the raw INVITE and Telnyx's reply. |
| Telnyx | The answering number +17608769757 forwards to a Telnyx call-answering assistant set up earlier by the brain. The live-call transcript, Take over and summaries went with the brain; the app keeps the number, mode and carrier forwarding codes in Settings. |

## Rules that always apply

- **Zero harness.** Connect the app to the ElevenLabs session and handle client tools. Don't write orchestration
  layers, backends between the phone and the agents, wrappers, mock runtimes, simulated paths or LLM pipes. A new
  on-device action is a new client tool.
- **The ElevenLabs agents' configuration belongs to the owner.** Never change their model, backups, prompt, tools, MCP
  servers, secrets or overrides, and never run sessions that override their settings. Reading it is fine.
- **Models are the owner's choice, in the dashboard.** The text agent and the Task agent run `claude-opus-5-5` (owner,
  2026-11-03: the task needs the most capable model). The voice agent is in Realtime mode on Eleven Realtime v1 Max
  (`realtime_model: eleven_realtime_v1_max`), which manages its LLM, so its stored `gpt-6-astra` isn't used (read
  2026-11-04). Don't add Claude or Anthropic models anywhere else in the product.
- **No live side effects without the owner's approval:** sends, calls, purchases, Telnyx writes, DNS changes, real
  email. Deploying committed changes to the workspace VM doesn't need a separate OK (owner, 2026-10-31).
- **Never commit secrets.** `android/ren.local.properties` (the baked answering number and the VM token) is gitignored; the VM's env
  files and `code-token` stay on the VM.
- **No fakes, stubs or simulations in the product.** Test the real feature in the real app (emulator or phone) before
  calling it done.
- **Branches:** branch from `main` and merge through PRs; don't push to `main`.
- **Match the surrounding code:** naming, comment density, evergreen comments with no "previously" or "now".
  Commits use conventional commit messages.
- **Validate before handing over an APK:** `./gradlew testDebugUnitTest lintDebug assembleRelease`, review the
  screenshots in `android/build/screens/`, check the APK (`apksigner verify`, `aapt2 dump badging`), and run one real
  conversation on the emulator.

## Repository map

```
android/   Kotlin/Compose app (minSdk 29, targetSdk 36): the browser, the agent panel, widgets, assistant overlay
  web/       the app's bundled pages, built by Vite into assets/ren/web by Gradle (webInstall, webBuild; needs Node):
             src/cato (Cato, unmodified), src/robot (ren's robot), src/ren (ren desktop's search page, unmodified),
             src/search (the phone's mount of it)
  stage-ui/  the chat page's build (ElevenLabs UI on Ink & Volt tokens), bundled into assets/ren/chat (in git)
deploy/vm/ the workspace VM: compose.yaml, Caddyfile, computer/ (Desktop Commander over HTTP), gemini/ (the Strands
           agents), hub/ (Ren's hub), search/ (ren desktop's search routers), laptop/ (OpenCode config, workspace AGENTS.md)
docs/      design/ (the ren. logotype; REN_DESIGN.md and REN_MATRIX.md still describe the Noir design they replaced)
.github/   CI: android.yml (tests, lint, release build)
```

Commands:
```bash
cd android && python3 tools/build_icon.py --check
JAVA_HOME=<JDK 21> ./gradlew testDebugUnitTest lintDebug assembleRelease   # builds android/web first (npm ci, npm run build)
cd android/web && npm run build && npm test   # the pages alone: hash checks, tsc, Vite, then Playwright on the robot
cd android/stage-ui && npm ci && npm run verify:components   # the ElevenLabs files byte-identical to upstream
uvx --python 3.12 --with fastmcp==4.1.0 --with elevenlabs==2.75.0 mypy --config-file deploy/vm/computer/mypy.ini   # the VM's Python
uvx --python 3.12 --with "strands-agents[bidi,bidi-google,gemini,mistral,openai,web-fetch]==1.64.0" --with strands-harness==0.3.4 --with mistralai==2.10.1 --with openai==2.50.0 --with fastapi==0.162.0 --with uvicorn==0.54.0 --with elevenlabs==2.75.0 mypy --config-file deploy/vm/gemini/mypy.ini
uvx --python 3.12 --with fastmcp==3.4.0 --with mcp==1.26.0 --with uvicorn==0.54.0 --with websockets==15.0.1 mypy --config-file deploy/vm/hub/mypy.ini
cd deploy/vm/hub && uvx --python 3.12 --with fastmcp==3.4.0 --with mcp==1.26.0 --with uvicorn==0.54.0 --with websockets==15.0.1 --with pytest --with anyio pytest test_hub.py
```

## Everything Emrys can do today

### Android app (`android/app/src/main/java/com/hiron/ren/`)
- **Runtime** (`core/RenRuntime.kt`): the state the screens render, the robot's command stream, the phone tools, the
  agent, and the approvals the agents ask for. No foreground service; the session lives while the app runs.
- **Interface (`ui/`)**: Ink & Volt (see "ren mobile" above). The kit is in `Theme.kt`, `Kit.kt`, `Keys.kt`,
  `Surfaces.kt`, `Chrome.kt` and `Meters.kt`; the brand mark is the "ren." logotype. The app opens on the browser
  (`BrowserScreen.kt`); the screens below are the agent panel and the pages its menu opens.
  - **Power-on screen** (`Onboarding.kt`): meet Emrys ("Set up" or "Not now"), the talk key, the default assistant,
    and phone access.
  - **Agent panel** (`HomeScreen.kt`, opened by Ask Ren): the status, the robot stage, a greeting, the transcript sheet, a
    thinking row while the agent works, and the dock: quick keys (Catch me up, My day, Texts, Calls), the composer
    (text agent) and the voice disc (voice agent, asking for the microphone first). During a voice session
    (`RenState.voice`) Emrys stays on its stage and the chat folds under it as a Transcript header that opens the
    same chat view as typing; checked on the emulator 2026-11-04.
  - **Approval sheet** (`ApprovalSheet.kt`): see `request_approval` above.
  - **Activity** (`ActivityScreen.kt`): pending reminders, then the activity log grouped by day.
  - **Settings** (`SettingsScreen.kt`): 1 Agents (provider, the Gemini text model, the talk key), 2 Permissions (a
    switch per `perm_*` action: "Always allowed" or "Asks you first", applied from the next conversation), 3 Call
    answering (number, mode, carrier codes), 4 What Emrys can use (phone permissions), 5 Remote tools (the phone's MCP
    server), 6 About.
- **Robot** (`ui/RobotView.kt`, `android/web/src/robot/`): the bundled page runs offline; Cato's moves follow what
  Ren is doing (`core/RobotDirector.kt`); the agent's speech moves the mouth (the SDK's audio alignment,
  `AgentTools.words`). The stage is in the agent panel; a page that fails says why with Retry. Run the emulator with
  `-gpu host`: the robot is WebGL.
- **Launcher** (`res/`, `widget/RenWidgets.kt`): adaptive and themed logotype icon, splash and notification icon;
  shortcuts Talk to Ren, Ask Ren, Catch me up, My day; a search-bar widget (4×1) that opens the composer and a
  panel widget (4×2) with the last reply and how many approvals wait.
- **Default assistant** (`voice/`): a `VoiceInteractionService` overlay over any app with speech-to-text and a keyboard
  fallback, sending to the text agent; locked out on a secured lock screen.
- **Notifications** (`service/Notifications.kt`): approvals ("Emrys needs your OK: …", cleared when the app comes to
  the front) and reminders.
- **Device access**: SMS, contacts, calendar, call log, notification listener (reply in any app), call screening (logs
  incoming calls), and the accessibility "app operator" that reads the screen and taps, types or scrolls.

## Remaining work

1. **Live checks on a phone:** a voice conversation, `request_approval` end to end (once the owner attaches the tool),
   each phone tool, the overlay, widgets and notifications.
2. **Image and PDF input:** blocked on the Android SDK (see Status). Don't build an upload path around it.
3. **VM leftovers:** `/opt/emrys/data`, `src/`, `brain.env` and the `emrys-brain:14bcf2b` image stay until the owner
   removes them.
4. **Background tasks:** a callback the owner talks through past its opening line; a session that never started
   still calls with an empty first message. Not checked since the 2026-11-03 config read: the voice agent's
   `background_task` item and `job_dir` on both agents.
5. **A stated result no tool returned:** on a phone call the voice agent called a phone tool that can't run there and
   then reported a result anyway (see Calls Emrys places). The prompt is the owner's.
6. **Owner's backlog** (from the approved plan): the ElevenLabs UI chat rebuild (threads, Orb, music players,
   simplified Settings), CloudFS, triggers and webhooks, and sharing the computer container's X display.
7. **The Strands agents** (plan https://claude.ai/artifact/Ri9R22DZiVUhd2x2oW6CeL): they run on the Gemini API (AI
   Studio, owner, 2026-11-06), NIM and Mistral. The `gemini` and `search` images are built on the VM
   (`emrys-gemini:1.64.0`, `ren-search:b112adf`) and start once their env files are in `/opt/emrys`: `gemini.env`
   (`GOOGLE_API_KEY`, `NVIDIA_NIM_API_KEY`, `MISTRAL_API_KEY`; without `TELNYX_MCP_AUTHORIZATION` oubliai-telnyx
   doesn't load there) and `search.env` (`BRAVE_SEARCH_API_KEY`, `BRAVE_ANSWERS_API_KEY`, `NVIDIA_NIM_API_KEY`).
   `EMRYS_GEMINI_TOKEN` is in `computer.env`, and the app's `geminiToken` (in `android/ren.local.properties`) is the
   same token. Not run live yet: a search from the app, and a chat with each Strands provider. Next:
   `start_background_task` and `call_as_emrys` for them, and the hub's `/hub/agents/mcp` on the ElevenLabs text and
   voice agents (the owner asked for both agents to be connected; it needs the token as a workspace secret).
8. **ren mobile:** the agent panel's chat on ren desktop's components (it is the ElevenLabs UI page from
   `android/stage-ui`, on Ink & Volt tokens); Kotlin comments that still use Noir's words (serif, porcelain, halo);
   the Glass theme; the robot in ren desktop; an
   "Open on desktop" action that uses `HubLink.call`; trusted touch events for `browser_click`; the design documents
   in `docs/design/` rewritten for Ink & Volt; Gemini Live as a Telnyx assistant (Call Control media streaming; ask
   before changing who answers +17608769757).

## Before launch (checklist)
1. **Release signing:** the APK is signed with the debug key (`android/app/build.gradle.kts`). Create a release
   keystore before any public distribution.
2. **ElevenLabs agents locked down (owner):** as of 2026-10-29 both agents had `enable_auth` off and webhook tools
   that use the owner's ElevenLabs key; public agents can be started by anyone with the agent id.
3. **The workspace VM's service account:** swap the Compute Engine default account for one with no roles.
4. **Secrets hygiene:** rotate the keys that were pasted into chat sessions.
5. **Store compliance (Play Store, if used):** SMS and call-log permissions need a permitted use case; Accessibility
   needs a disclosure. Sideloading avoids this.

## Key documents
- **ElevenLabs Agents:** https://elevenlabs.io/docs/eleven-agents/overview; Kotlin SDK
  https://elevenlabs.io/docs/eleven-agents/libraries/kotlin (source: github.com/elevenlabs/elevenlabs-android, tag
  v0.13.0); WebSocket API https://elevenlabs.io/docs/agents-platform/libraries/web-sockets; index
  https://elevenlabs.io/docs/llms.txt
- **ElevenLabs UI:** https://github.com/elevenlabs/ui
- **Telnyx MCP server:** https://github.com/ImRonAI/OubliTelnyxMCP, live at https://oubli-catolabs.fastmcp.app/mcp
- **Desktop Commander:** https://github.com/wonderwhy-er/DesktopCommanderMCP
- **Design:** ren desktop's `tailwind.config.ts`, `src/index.css` and `docs/brand.md` (`ImRonAI/TheBrowser`);
  `docs/design/ren-logotype.json`
- **FastMCP:** https://gofastmcp.com (proxy providers, mounting, transforms); MCP Kotlin SDK
  github.com/modelcontextprotocol/kotlin-sdk (0.16.0)

## History
- **0.5.0, 2026-10-29/30:** Rivet renamed **Emrys** (Welsh "immortal", Merlin's true name; not a legal trademark
  clearance) with no compatibility layer, the Noir redesign, the new robot and the ElevenLabs stage layer, on top of
  upstream 0.4.5. A WebView with `WRAP_CONTENT` height collapses the robot page, so `RobotView` uses `MATCH_PARENT`.
- **2026-10-31:** the brain (Strands harness, gateway, search proxy, email, live calls, approval rules), the gateway
  client and service, brain search, recommended settings and the model picker were deleted; the app talks to the
  agents directly (`9ffab7a`). The computer container was added to the VM (`2810aae`).
- **2026-11-12:** Emrys became **ren mobile** (`com.hiron.ren`, with no compatibility layer): ren desktop's Ink & Volt
  design, Cato as the robot, a browser with ren desktop's search page, and Ren's hub joining the phone, ren desktop
  and the agents over MCP. The Matrix and Orb stage went with the old robot. Built web pages, `node_modules` and
  Playwright results left the repository; Gradle builds the pages.
