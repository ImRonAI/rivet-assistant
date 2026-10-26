# rivet-brain

Rivet's brain: a [Strands harness](https://strandsagents.com/docs/user-guide/harness/) agent on
Perplexity's Agent API (OpenAI Responses-compatible), connected to the Telnyx MCP server on Prefect
Horizon, with human approval for risky actions and a supervisor that keeps one session alive across
hangs, context overflow, outages, and restarts.

See the repository root `README.md` for the architecture and setup.
