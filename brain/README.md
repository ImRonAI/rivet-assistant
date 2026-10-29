# rivet-brain

Rivet's brain: a [Strands harness](https://strandsagents.com/docs/user-guide/harness/) agent on
Perplexity's Agent API (OpenAI Responses-compatible), connected to the Telnyx MCP server on Prefect
Horizon, with human approval for risky actions and a supervisor that keeps one session alive across
hangs, context overflow, outages, and restarts.

See the repository root `README.md` for the architecture and setup.

## Approval rules

Standing "always allow" and "always decline" rules are stored per session under
`session/<session_id>/rivet/approval-rules.json` in the session storage backend (`rules.py`). The phone receives
them as a `rules` event on every `resume` and after every change, and changes them with `rules.set` and
`rules.revoke`; an answer to an approval can carry `standing: "allow" | "deny"`, which the brain stores only when
that approval's sheet offered it. See the `gateway.py` docstring for every message.
