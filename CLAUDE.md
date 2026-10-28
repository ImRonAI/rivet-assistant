# CLAUDE.md

@AGENTS.md

`AGENTS.md` is the source of truth for Rivet's status, what it can do, the rules, and the remaining work. Read it before changing
anything. In short:

- **Version:** app 0.2.0 is on `main`, and the Fly brain runs the matching 0.2.0 code (deployed 2026-10-28).
- **Remaining work, in order:**
  1. The robot always in view, with an ElevenLabs realtime voice agent or the language model alone.
  2. A VPS workspace: OpenVSCode Server and a virtual desktop.
  3. Plain-language, formatted alerts with "Don't ask again" for both approve and deny.
  4. Recommended settings.
  5. The home-screen search widget, backed by Ron's browser search engine and user-selectable engines.
  6. Storage on Telnyx KV through the MCP (decided).
  7. Scheduled routines: a scheduler inside the brain plus Telnyx's scheduling operations (decided).
  8. Keep the docs honest.
- **Before launch:** see the checklist in `AGENTS.md`.
- **Never:**
  - use Claude or Anthropic models in the product;
  - commit secrets (`android/rivet.local.properties` stays gitignored);
  - send, call, buy, write to Telnyx, deploy, or touch real email without the owner's explicit approval.
- **Before handing over an APK:** run the tests, lint and release build, review `android/build/screens/`, verify the APK, and run the live test.
