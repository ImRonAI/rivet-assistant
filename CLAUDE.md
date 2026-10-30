# CLAUDE.md

@AGENTS.md

`AGENTS.md` is the source of truth for Rivet's status, what it can do, the rules, and the remaining work. Read it before changing
anything. In short:

- **Version:** app 0.4.5 (versionCode 12) on branch `ccr-22d59ed7-dzijhh`. The brain runs on the owner's Google Cloud VM at
  https://rivet.ron-ai.dev (Docker and Caddy; files in `deploy/vm/`). The Fly app `rivet-assistant` is stopped.
- **The owner's rules for this project:**
  - The ElevenLabs text agent is the default for typing and the ElevenLabs voice agent is the default for talking. Both use the
    phone through their client tools. Qwen (Groq) and Perplexity are only model options for Rivet's own brain, never a fallback
    or a workaround for the agents.
  - Use what ElevenLabs offers natively. Don't invent layers, prompts, timeouts or numbers around the agents, and don't change
    the agents' prompts or tools in ElevenLabs unless the owner asks.
  - No simulations, demo content or canned text in the app.
  - Types as strict as they can be, in Kotlin, Python and TypeScript. No casts, ignores or `Any` to get around a check.
  - Simple over complex. No code that isn't needed.
  - Don't bring up GitHub Actions or CI runs.
- **Not done yet:** mypy `disallow_any_expr` and Kotlin's strict explicit API mode (the owner wants both on); connecting Rivet
  to the VM laptop; the search widget's engine (task 5); Telnyx KV storage (task 6); routines (task 7); the on-phone pass.
- **Before launch:** see the checklist in `AGENTS.md`.
- **Never:**
  - use Claude or Anthropic models in the product, including the ElevenLabs agents' LLM settings;
  - commit secrets (`android/rivet.local.properties` and the VM's env files stay out of git);
  - send, call, buy, write to Telnyx, deploy, or touch real email without the owner's explicit approval.
- **Before handing over an APK:** run the tests, lint and release build, review `android/build/screens/`, verify the APK, and run
  the live test.
