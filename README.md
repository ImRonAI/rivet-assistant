# ren mobile

ren mobile (app name Ren) is the phone side of ren, the browser by Oubli AI. It is a lightweight Android browser with
ren's search and an AI agent panel, and it handles texts, email drafts, calls, apps, reminders and tasks on the phone
through the owner's ElevenLabs agents. Its face is Cato, recoloured to ren's volt. It was Emrys until 2026-11-12.

The app connects straight to two ElevenLabs agents (text and voice) with the official ElevenLabs Android SDK and answers
their client tools on the phone. Gemini, NVIDIA NIM and Mistral are optional Strands agents on the owner's workspace VM
(https://rivet.ron-ai.dev). The phone and ren desktop (`ImRonAI/TheBrowser`) each serve an MCP server through Ren's hub
on that VM and call each other through it.

`AGENTS.md` is the source of truth: how it works, its status, the rules, and the remaining work. Read it first.

## Repository

```
android/   Kotlin/Compose app (minSdk 29, targetSdk 36): the browser, the agent panel, widgets, assistant overlay
  web/       the app's bundled pages (Vite): Cato and ren's robot, ren desktop's search page, the agent panel's chat
deploy/vm/ the workspace VM: compose.yaml, Caddyfile, computer/ (Desktop Commander over HTTP, background-task hooks),
           gemini/ (the Strands agents), hub/ (Ren's hub), search/ (ren's search), laptop/ (OpenCode config),
           toolbox/ (pinned developer tools)
docs/      design/ (the ren. logotype)
```

## Build

See `android/README.md`. In short, with JDK 21, the Android SDK and Node 22:

```bash
cd android
python3 tools/build_icon.py --check
./gradlew testDebugUnitTest lintDebug assembleRelease
```
