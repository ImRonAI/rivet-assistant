# emrys-brain

Emrys's brain: a [Strands harness](https://strandsagents.com/docs/user-guide/harness/) agent on
Perplexity's Agent API (OpenAI Responses-compatible), or on Groq when the user picks it, connected to the Telnyx MCP
server on Prefect
Horizon, with human approval for risky actions and a supervisor that keeps one session alive across
hangs, context overflow, outages, and restarts.

See the repository root `README.md` for the architecture and setup.

## Approval rules

Standing "always allow" and "always decline" rules are stored per session under
`session/<session_id>/emrys/approval-rules.json` in the session storage backend (`rules.py`). The phone receives
them as a `rules` event on every `resume` and after every change, and changes them with `rules.set` and
`rules.revoke` (by key, or by tool and scope for a rule the phone has not seen listed yet); an answer to an approval can carry `standing: "allow" | "deny"`, which the brain stores only when
that approval's sheet offered it. See the `gateway.py` docstring for every message.

## Model choice

`EMRYS_MODEL_PROVIDER` (`perplexity` or `groq`) is the default for Emrys's own agent; a provider counts only when its
key is set. The phone sends `model.set` and gets a `model` event {model, available} on every `resume` and after every
change; the choice is stored under `session/<session_id>/emrys/model.json`. A switch rebuilds the session's agent on the
new provider, and the conversation carries over through the session snapshots. It is refused while a turn or task runs
or an approval waits, and work that arrives during the switch waits for the new agent. Groq runs `qwen/qwen3.8-27b`
statelessly (Groq has no `previous_response_id` or `store`) and has no native web search, so its instructions leave that
line out.

## Search

`search.py` proxies Brave Search for the phone behind the device token: `GET /v1/search/{kind}` for web, news, images,
videos, places, LLM context and answers, plus `GET /v1/suggest` and `GET /v1/spellcheck`. Every response is
`Cache-Control: no-store`, nothing is stored (Brave prohibits keeping results), and logs carry the kind, status and
latency only. `search_tools.py` gives Emrys's own agent the same searches as `search_*` tools; each result reaches the
phone as a `search.results` event so it renders as cards. A `turn` can carry `attachments` (results the user added with
"Add to Emrys"); they reach the model marked as web content and taint the turn. Only `https://` links are kept; a turn
left with no text and no usable attachment is accepted and then fails with a plain sentence.
