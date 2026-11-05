# Emrys

Emrys is an Android phone assistant by Oubli AI. It handles texts, email drafts, calls, apps, reminders and tasks on
the phone through the owner's ElevenLabs agents. Its face is the Emrys robot.

The app connects straight to two ElevenLabs agents (text and voice) with the official ElevenLabs Android SDK and answers
their client tools on the phone. There is no backend between the phone and the agents. The agents also reach the
owner's workspace VM (https://rivet.ron-ai.dev) and the Telnyx MCP server through MCP servers the owner configures in
ElevenLabs.

`AGENTS.md` is the source of truth: how it works, its status, the rules, and the remaining work. Read it first.

## Repository

```
android/   Kotlin/Compose app (minSdk 29, targetSdk 36): robot WebView, ElevenLabs stage layer, widgets, assistant overlay
  robot/     the robot page and its bridge (built into assets by tools/build_robot_asset.py)
  stage-ui/  ElevenLabs UI matrix, orb, shimmering text and the chat, bundled into app assets
deploy/vm/ the workspace VM: compose.yaml, Caddyfile, computer/ (Desktop Commander over HTTP, background-task hooks),
           laptop/ (OpenCode config), toolbox/ (pinned developer tools)
docs/      design/ (Noir design system, Matrix light language, sigil)
```

## Build

See `android/README.md`. In short, with JDK 21 and the Android SDK:

```bash
cd android
python3 tools/build_robot_asset.py --check && python3 tools/build_icon.py --check
./gradlew testDebugUnitTest lintDebug assembleRelease
```
