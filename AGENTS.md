# AGENTS.md — Emrys

Guidance for any AI agent (or person) working in this repository. Read this first, then `android/README.md`.
`README.md`, `android/README.md`, `docs/calls-live.md` and `docs/scaling.md` still describe the deleted brain; trust
this file where they disagree.

Emrys is an Android phone assistant built by Ron AI (founder Tim Hunter, https://www.hi-ron.com,
support@hi-ron.com). It handles texts, email drafts, calls, apps, reminders and tasks on the phone through the owner's
ElevenLabs agents. Its face is the Emrys robot (`android/robot/Emrys_the_Robot.html`).

## How it works

The app connects straight to the owner's two ElevenLabs agents with the official ElevenLabs Android SDK (0.13.0) and
answers their client tools on the phone. Nothing sits in between: no backend, no gateway, no wrapper around the agents.

- **Session start** (`voice/EmrysAgent.kt`): `ConversationConfig(agentId, userId, dynamicVariables, textOnly, clientTools, …)`.
  - Voice agent `agent_2901m620nzwrev2r7ekqpydcxe25`, text agent `agent_9601m623qa72ffs9cyy3ezc0p6tb` (public agents,
    so `agentId` alone starts a session; no API key on the phone).
  - `userId` is a UUID made once per install (`Settings.userId`) and also sent as the `user_id` dynamic variable.
  - Eight `perm_*` dynamic variables (`perm_use_apps`, `perm_send_messages`, `perm_read_messages`, `perm_purchases`,
    `perm_install_apps`, `perm_cloud_coding`, `perm_deploy`, `perm_create_music`), each `granted` or `prompt`, from
    Settings › Permissions (`core/Approvals.kt`, `core/Settings.kt`). How the agents use them is set in their prompts,
    which the owner manages.
- **Client tools** (`AgentTools` in `voice/EmrysAgent.kt`, run by `device/DeviceTools.kt`), 18 phone tools with no gate
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
- **Transcript:** agent replies are shown without their expressive audio tags (`[warmly]`, `[surprised]`), which the
  voice still performs. `AgentTools.withoutAudioTags` uses the pattern from ElevenLabs' own widget (convai-widget-core
  `stripAudioTags`), so markdown links stay.
- **Release builds** keep the SDK's private fields (`android/app/proguard-rules.pro`). The SDK reads the voice
  conversation token into a private field with Gson, and its own keep rules cover only public members. Without the
  rule, R8 renames the field, the token comes back blank and voice fails with "Couldn't reach the ElevenLabs agent".

## Status (2026-10-31)

| Area | State |
|---|---|
| App | **0.5.0** (versionCode 13, `com.hiron.emrys`). PR #4 (`design/facelift`) merged into `ccr-22d59ed7-dzijhh` at `fd621d2`; `design/facelift` is ahead of it with the brain off the VM (`3440e8b`) and the voice-token keep rule, the audio-tag filter and the arm64-only build (`11b5b98`). The release build ships arm64-v8a only (19.2 MB; the WebRTC library in the SDK is about 12 MB per ABI). |
| Verified | 109 unit and screen tests pass, Kotlin compiles with warnings as errors, lint has 0 errors. On the Android 15 emulator (2026-10-31) a typed question opened a session straight to the text agent (`conv_2801m68vtz0te2natfxrg5cyc6gp`): `user_id` and all eight `perm_*` arrived, the agent called `device_status`, the phone returned real data and the agent answered with the battery level. The arm64-only release APK, with the keep rule and the tag filter, installed on the emulator and opened a voice session with the voice agent ("Listening"; the agent's greeting appeared without its audio tags). |
| Not verified | Talking to the voice agent through a real microphone (the emulator runs without audio, so only the connection and the agent's greeting were checked); `request_approval` from a live agent (the handler is registered, but on 2026-10-31 the tool was not attached to either agent — attaching it is the owner's call); anything on a physical phone. |
| Attachments | Images and PDFs are not built: SDK 0.13.0 has no way to send files (`OutgoingEvent` has no multimodal message). The WebSocket protocol's `multimodal_message` with `file_input` exists only in the JS SDK and raw API. |
| Workspace VM | Google Cloud `instance-20261029-122759` (us-central1-a, c4-standard-8, Debian 13, 50 GB, static IP 136.113.136.145). `/opt/emrys` runs Caddy 2.11.7, the laptop (linuxserver webtop, https://desktop.rivet.ron-ai.dev), OpenVSCode (https://code.rivet.ron-ai.dev) and the `computer` container (Desktop Commander 0.3.1 behind FastMCP's proxy at `/computer/mcp`, reachable only with `EMRYS_COMPUTER_TOKEN`). OpenCode on the laptop runs Mistral `zai-glm-5-3` at `reasoningEffort: max`. The brain is off the VM (deployed 2026-10-31 from `deploy/vm`: `docker compose -p emrys up -d --remove-orphans` removed `emrys-brain-1`); `https://rivet.ron-ai.dev/` answers 404, `/computer/mcp` 401 without the token and Desktop Commander's `initialize` with it. The previous files are kept as `compose.yaml.before-brain-removal-*` and `conf/Caddyfile.before-brain-removal-*`, and `data/`, `src/` and `brain.env` are still on disk. Reach it with `gcloud compute ssh instance-20261029-122759 --project fair-expanse-493212-h8 --zone us-central1-a --tunnel-through-iap`. Env files (`brain.env`, `laptop.env`, `computer.env`, `code-token`) live only on the VM. |
| Telnyx | The answering number +17608769757 forwards to a Telnyx call-answering assistant set up earlier by the brain. The live-call transcript, Take over and summaries went with the brain; the app keeps the number, mode and carrier forwarding codes in Settings. |

## Rules that always apply

- **Zero harness.** Connect the app to the ElevenLabs session and handle client tools. Don't write orchestration
  layers, backends between the phone and the agents, wrappers, mock runtimes, simulated paths or LLM pipes. A new
  on-device action is a new client tool.
- **The ElevenLabs agents' configuration belongs to the owner.** Never change their model, backups, prompt, tools, MCP
  servers, secrets or overrides, and never run sessions that override their settings. Reading it is fine.
- **No Claude or Anthropic models in the product.** The agents' models are the owner's choice in the dashboard.
- **No live side effects without the owner's approval:** sends, calls, purchases, Telnyx writes, DNS changes, real
  email. Deploying committed changes to the workspace VM doesn't need a separate OK (owner, 2026-10-31).
- **Never commit secrets.** `android/emrys.local.properties` (the baked answering number) is gitignored; the VM's env
  files and `code-token` stay on the VM.
- **No fakes, stubs or simulations in the product.** Test the real feature in the real app (emulator or phone) before
  calling it done.
- **Branches:** work on `design/facelift`, merge through PRs; don't push to `main` or `ccr-22d59ed7-dzijhh`.
- **Match the surrounding code:** naming, comment density, evergreen comments with no "previously" or "now".
  Commits use conventional commit messages.
- **Validate before handing over an APK:** `./gradlew testDebugUnitTest lintDebug assembleRelease`, review the
  screenshots in `android/build/screens/`, check the APK (`apksigner verify`, `aapt2 dump badging`), and run one real
  conversation on the emulator.

## Repository map

```
android/   Kotlin/Compose app (minSdk 29, targetSdk 36), robot WebView, ElevenLabs stage layer, widgets, assistant overlay
  robot/     the robot page and its bridge (built into assets by tools/build_robot_asset.py)
  stage-ui/  ElevenLabs UI matrix, orb and shimmering-text, bundled into assets/emrys/vendor/
deploy/vm/ the workspace VM: compose.yaml, Caddyfile, computer/ (Desktop Commander over HTTP), laptop/ (OpenCode config, workspace AGENTS.md)
docs/      design/ (Noir design system, Matrix light language, sigil); calls-live.md and scaling.md describe the old brain
.github/   CI: android.yml (asset checks, tests, lint, release build)
```

Commands:
```bash
cd android && python3 tools/build_robot_asset.py --check && python3 tools/build_icon.py --check
JAVA_HOME=<JDK 21> ./gradlew testDebugUnitTest lintDebug assembleRelease
cd android/stage-ui && npm ci && npm run verify:components   # the ElevenLabs files byte-identical to upstream
```

## Everything Emrys can do today

### Android app (`android/app/src/main/java/com/hiron/emrys/`)
- **Runtime** (`core/EmrysRuntime.kt`): the state the screens render, the robot's command stream, the phone tools, the
  agent, and the approvals the agents ask for. No foreground service; the session lives while the app runs.
- **Interface (`ui/`)**: the Noir design system (`docs/design/EMRYS_DESIGN.md`): near-black paper, porcelain ink, one
  periwinkle halo accent; Instrument Serif, Geist and Geist Mono. The kit is in `Theme.kt`, `Kit.kt`, `Keys.kt`,
  `Surfaces.kt`, `Chrome.kt` and `Meters.kt`; the brand mark is the dot-matrix sigil.
  - **Power-on screen** (`Onboarding.kt`): meet Emrys ("Set up" or "Not now"), the talk key, the default assistant,
    and phone access.
  - **Home** (`HomeScreen.kt`): the masthead and status, the robot stage, a serif greeting, the transcript sheet, a
    thinking row while the agent works, and the dock: quick keys (Catch me up, My day, Texts, Calls), the composer
    (text agent) and the voice disc (voice agent, asking for the microphone first).
  - **Approval sheet** (`ApprovalSheet.kt`): see `request_approval` above.
  - **Activity** (`ActivityScreen.kt`): pending reminders, then the activity log grouped by day.
  - **Settings** (`SettingsScreen.kt`): 1 Voice (the talk key), 2 Permissions (a switch per `perm_*` action: "Always
    allowed" or "Asks you first", applied from the next conversation), 3 Call answering (number, mode, carrier codes),
    4 What Emrys can use (phone permissions), 5 About.
- **Robot** (`ui/RobotView.kt`, `android/robot/bridge.js`): the bundled page runs offline; moves follow what Emrys is
  doing; the agent's speech moves the mouth (the SDK's audio alignment, `AgentTools.words`); the ElevenLabs Matrix,
  Orb and ShimmeringText on the stage (`android/stage-ui`, installed verbatim from elevenlabs/ui@`3a28ebab`) follow
  only real signals (states, the agent's turn and audio level as the SDK reports them). The big stage is on Home and a
  short band on Activity and Settings; a page that fails says why with Retry. The emulator renders in software, so
  the page drops to `lowFx` there (no Orb, floor or gauntlets).
- **Launcher** (`res/`, `widget/EmrysWidgets.kt`): adaptive and themed sigil icon, splash and notification icon;
  shortcuts Talk to Emrys, Ask Emrys, Catch me up, My day; a search-bar widget (4×1) that opens the composer and a
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
3. **Docs:** `README.md`, `android/README.md`, `docs/calls-live.md` and `docs/scaling.md` still describe the brain.
4. **VM leftovers:** `/opt/emrys/data`, `src/`, `brain.env` and the `emrys-brain:14bcf2b` image stay until the owner
   removes them.
5. **Owner's backlog** (from the approved plan): the ElevenLabs UI chat rebuild (threads, Orb, music players,
   simplified Settings), CloudFS, triggers and webhooks, and sharing the computer container's X display.

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
- **Design:** `docs/design/EMRYS_DESIGN.md`, `docs/design/EMRYS_MATRIX.md`

## History
- **0.5.0, 2026-10-29/30:** Rivet renamed **Emrys** (Welsh "immortal", Merlin's true name; not a legal trademark
  clearance) with no compatibility layer, the Noir redesign, the new robot and the ElevenLabs stage layer, on top of
  upstream 0.4.5. A WebView with `WRAP_CONTENT` height collapses the robot page, so `RobotView` uses `MATCH_PARENT`.
- **2026-10-31:** the brain (Strands harness, gateway, search proxy, email, live calls, approval rules), the gateway
  client and service, brain search, recommended settings and the model picker were deleted; the app talks to the
  agents directly (`9ffab7a`). The computer container was added to the VM (`2810aae`).
