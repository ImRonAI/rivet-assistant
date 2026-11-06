# CLAUDE.md

@AGENTS.md

`AGENTS.md` is the source of truth for Emrys's status, what it can do, the rules, and the remaining work. Read it before changing
anything. In short:

- **Version:** app 0.5.0 (versionCode 13) on `main`. The app talks to the ElevenLabs agents directly; the owner's workspace VM
  is at https://rivet.ron-ai.dev (Docker and Caddy; files in `deploy/vm/`). The Fly app `rivet-assistant` is stopped.
- **The owner's rules for this project:**
  - The ElevenLabs text agent is the default for typing and the ElevenLabs voice agent is the default for talking. Both use the
    phone through their client tools. Qwen (Groq) and Perplexity are only model options for Emrys's own brain, never a fallback
    or a workaround for the agents.
  - Gemini is an optional provider (Settings › Agents), never a fallback. Its side is Strands only, written as the Strands
    harness and bidi docs write it: `create_harness()` for text, `BidiAgent` on gemini-3.8-live for voice, every vended tool,
    strict Strands types (`deploy/vm/gemini/`). The zero-harness rule is about ElevenLabs.
  - Use what ElevenLabs offers natively. Don't invent layers, prompts, timeouts or numbers around the agents, and don't change
    the agents' prompts or tools in ElevenLabs unless the owner asks.
  - No simulations, demo content or canned text in the app.
  - Types as strict as they can be, in Kotlin, Python and TypeScript. No casts, ignores or `Any` to get around a check.
  - Simple over complex. No code that isn't needed.
  - Don't bring up GitHub Actions or CI runs.
- **Not done yet:** mypy `disallow_any_expr` and Kotlin's strict explicit API mode (the owner wants both on); connecting Emrys
  to the VM laptop; the search widget's engine (task 5); Telnyx KV storage (task 6); routines (task 7); the on-phone pass.
- **Before launch:** see the checklist in `AGENTS.md`.
- **Never:**
  - add Claude or Anthropic models to the product beyond the agents' models, which are the owner's choice (the text and
    Task agents run `claude-opus-5-5`, 2026-11-03);
  - commit secrets (`android/emrys.local.properties` and the VM's env files stay out of git);
  - send, call, buy, write to Telnyx, deploy, or touch real email without the owner's explicit approval.
- **Before handing over an APK:** run the tests, lint and release build, review `android/build/screens/`, verify the APK, and run
  the live test.

## Addendum, 2026-10-30: Emrys 0.5.0
Emrys 0.5.0 merges upstream 0.4.5 (every behaviour change kept), renames Rivet to **Emrys** everywhere in code (app ID
`com.hiron.emrys`, `EMRYS_*` settings, the bridge `window.emrys`) with no compatibility layer, and redesigns the app:
the Noir design system (`docs/design/EMRYS_DESIGN.md`), a new robot, and ElevenLabs UI's Matrix, Orb and
ShimmeringText on the stage (`docs/design/EMRYS_MATRIX.md`). It is all on `main`; branch from there.
