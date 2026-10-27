# Live calls: transcript, take-over and summary

What the phone gets while Rivet's Telnyx assistant is on a call, and the schema evidence for each choice.

- Schema: OubliTelnyxMCP `docs/reference/telnyx/openapi.json`, SHA-256 `34a17d54…7fdf8`. That is the same file
  `brain/src/rivet_brain/data/telnyx_operations.json` was generated from, so the tool names below are the MCP names
  `call_tool` takes.
- Argument names come from FastMCP 4.1.0's OpenAPI parser (`_combine_schemas_and_map_params`) run over that schema.
  4.1.0 is the version OubliTelnyxMCP pins.
- Code: `brain/src/rivet_brain/live_calls.py` (watcher, take-over, `TelnyxRunner`) and `calls.py` (assistant config
  and webhook bodies).

## How the assistant is wired, and why that decides everything

The number routes to the assistant's own TeXML application (`telephony_settings.default_texml_app_id`: "This will be
created automatically on assistant creation."). The assistant config (`CreateAssistantRequest`,
`UpdateAssistantRequest`) has only these URL settings: `dynamic_variables_webhook_url`, webhook tools, and
`messaging_settings.delivery_status_webhook_url`. None of them is a destination for Call Control webhooks.
The candidates that rely on Call Control webhooks (`call.*` events) therefore have no documented way to reach the
brain.

## Correlating webhooks with a call (chosen)

- `dynamic_variables_webhook_url`: "Telnyx sends a POST request to this URL at the start of the conversation to
  resolve dynamic variables. **Gotcha:** the webhook response must wrap variables under a top-level
  `dynamic_variables` object". The brain answers `{"dynamic_variables": {"rivet_call_id": "c-…"}}`.
- `WebhookToolParams.preset_body_fields`: "Body fields supplied by the assistant configuration rather than by the
  model. They are never advertised in the tool definition, so the LLM can neither see nor set them … Values support
  mustache templating, so they can hold dynamic variables (`{{customer_id}}`)".
  `SendMessageTool.message_template` confirms that "custom webhook-provided variables will be resolved at runtime".
  `take_message` and `call_summary` therefore carry `{"call_id": "{{rivet_call_id}}"}`.
- `dynamic_variables` gives `rivet_call_id` the default `""`. `dynamic_variables_webhook_timeout_ms`: "If the webhook
  does not respond within this timeout, the call proceeds with default values."
- **Blocker (unknown, not guessed):** the schema does not describe the *request* body of the dynamic-variables
  webhook; it links to an external guide. The gateway reads only `telnyx_end_user_target` and
  `telnyx_end_user_target_verified`, as it already did. That gives it **no** `call_control_id` or conversation id.

## (a) Live transcript

| Candidate | Evidence | Verdict |
|---|---|---|
| Push: `telephony_settings.send_message_history_updates` | "Whether the assistant sends a `call.ai_gather.message_history_updated` webhook with the full message history every time the conversation history changes." Payload (`CallAIGatherMessageHistoryUpdated`): `call_control_id`, `from`, `to`, `message_history[] {role, content}`. | A Call Control webhook, listed as an expected webhook of `callGatherUsingAI`. No documented route to the brain for a TeXML-answered assistant. Making it reachable means routing the number to a Call Control application, with the brain answering (`AnswerCall`) and starting the assistant (`CallStartAIAssistant`) on every call. That is two live writes per call and puts the brain on the answering path. **Not used.** |
| Call Control transcription | `StartCallTranscription`: "Start real-time transcription." Expected webhook `call.transcription` (`transcription_data.transcript`, `is_final`). | Needs `call_control_id` and the same Call Control routing. **Not used.** |
| A webhook tool the model calls every turn | `WebhookToolParams.async`: "the assistant will move forward without waiting". | Depends on the model calling it each turn, paraphrases the words, and slows the call. **Not used.** |
| **Read the AI Conversations API while the call is live** | `get_conversations_public_conversations_get` (GET /ai/conversations): "Supports PostgREST-style query parameters". Filters `metadata->telnyx_end_user_target`, `metadata->telnyx_conversation_channel` ("e.g., `…=eq.phone_call`"), `created_at` ("e.g., `created_at=gte.2025-01-01`"), `order` ("e.g., `order=created_at.desc`"), `limit`. `Get_conversation_messages` (GET /ai/conversations/{conversation_id}/messages; operationId `get_conversations_public__conversation_id__messages_get`): args `conversation_id`, `page[size]` (max 100), `page[number]`. Returns `ConversationMessage {role: user\|assistant\|tool, text, sent_at, created_at}` and `meta.total_pages`. | **Chosen.** It is polling: the only transcript source the schema allows for this wiring. |

How the watcher works:

- Every `RIVET_CALL_POLL` seconds (default 2), it runs one `execute` with fixed code.
- The code holds only GETs. The brain's own approval catalog classifies it as `read`, and a test checks this.
- The first ticks find the conversation: this caller, a phone call, created after the call started (minus 60 s), newest
  first.
- Every tick reads up to 3 pages of messages.
- The brain sorts the messages by `sent_at` (falling back to `created_at`) and keeps `user` and `assistant` text. It
  sends the phone only the lines it has not sent yet.
- Polling stops when the call ends, or after 30 minutes.

## (b) Take over

| Candidate | Evidence | Verdict |
|---|---|---|
| The assistant's `transfer` tool | `InferenceEmbeddingTransferToolParams`: `targets` ("The assistant will be able to choose one of the targets"; or "`{{ targets }}`" from the dynamic-variables webhook), `from`, `diversion`, `warm_transfer_*`. | The model decides when to transfer. A tap on the phone has no documented way into the live conversation: `CallAddMessagesToAIAssistant` needs a `call_control_id` too. It would also let a caller talk the assistant into transferring. **Not used; the assistant has no transfer tool.** |
| **Call Control `TransferCall`** | POST /calls/{call_control_id}/actions/transfer: "Transfer a call to a new destination. If the transfer is unsuccessful, a `call.hangup` webhook for the other call (Leg B) will be sent … The original call will remain active". | **Chosen.** |

The `TransferCall` arguments (FastMCP 4.1.0 names):

- Required: `call_control_id` and `to`.
- `from`: the answering line. "The number should be in +E164 format. This attribute will default to the `to` number of
  the original call if omitted."
- `command_id`: "Telnyx will ignore any command with the same `command_id` for the same `call_control_id`." It is one
  id per take-over attempt.
- `timeout_secs`: 25. "Minimum value is 5 seconds. Maximum value is 600 seconds."

Where the call id comes from:

- The conversation's `metadata.call_control_id`. The list filter documents it: "`metadata->call_control_id`: Filter by
  call control ID (e.g., `metadata->call_control_id=eq.v3:123`)".
- The take-over code reads it fresh: `get_conversation_by_id_public_conversations_get` (`data.metadata`), or the list
  query when the watcher has not found the conversation yet.
- A TeXML call has such an id: `TexmlCallCompletedWebhookSchema.CallSid` is "The unique identifier of the call
  (call_control_id)."

Where the user's own number comes from:

- Telnyx's forwarding info exists, but only as `sip_headers` ("User-to-User and Diversion headers from sip invite",
  values like `<sip:111@192.168.1.1>`) in Call Control payloads. Those do not reach the brain (see above).
- It is also not a system dynamic variable. `CallAssistantRequest.dynamic_variables` lists "telnyx_call_to,
  telnyx_call_from, telnyx_conversation_channel, telnyx_agent_target, telnyx_end_user_target,
  telnyx_call_caller_id_name … and custom header variables".
- So the phone sends it: Settings › own number, in `call.take_over {call_id, to}`. The brain checks it is E.164, and
  the code refuses the answering line itself (`metadata.telnyx_agent_target`).

## (c) Summary and hangup

| Candidate | Evidence | Verdict |
|---|---|---|
| **Post-conversation webhook tool `call_summary`** | `PostConversationSettings`: "When enabled, the assistant receives one additional LLM turn after the conversation ends, allowing it to execute final tool calls such as sending a summary or updating a record via webhook or function tools. … call-control tools (e.g. hangup, transfer) are also unavailable. Beta feature." | **Chosen.** It was already enabled (`post_conversation_settings.enabled: true`). Its arrival is the brain's end-of-call signal. |
| Insights | `InsightSettings.insight_group_id`, then `call.conversation_insights.generated` (`payload.results[] {insight_id, result}`). Groups and templates have a `webhook` field with no description. | Needs three more resources (template, group, assignment), and nothing says how the group webhook is delivered. **Not used.** |
| Hangup webhooks | `call.conversation.ended` (`payload.call_control_id`, `conversation_id`, `duration_sec`, `reason`) and `call.hangup` (`hangup_cause`). | Call Control webhooks (expected for `CallStartAIAssistant` and `HangupCall`), with the same routing problem. **Not used.** |
| TeXML `status_callback` | `TexmlCallCompletedWebhookSchema`: `CallStatus`, `CallDuration`, `HangupCause`, `CallControlId`. | Works for TeXML calls, but means rewriting the assistant's auto-created TeXML app. `UpdateTexmlApplication` requires `friendly_name` and `voice_url`. **Not used.** This is the upgrade path if the summary turn proves unreliable. |

The brain ends a call that has had no summary for 30 minutes, with outcome `unknown`.

## Blockers and what is unverified

- The dynamic-variables request body is undocumented (see above). The live call keys on the caller number and the start
  time, not on a Telnyx id.
- Nothing in the schema says that `TransferCall` works on a call an assistant answered through TeXML. It is unverified
  live.
- Also unverified: whether the assistant stops talking while the transfer rings, and whether the post-conversation turn
  runs after a transfer. If it does not, the call ends after 30 minutes with outcome `unknown`.
- Carrier forwarding can send the transfer back to Rivet. The transfer rings the user's own number through their
  carrier:
  - With "all calls" forwarding, it would come straight back to Rivet. The phone refuses a take-over when it last
    turned that mode on.
  - With "unanswered calls" forwarding, a transfer the user does not pick up can return after the carrier's no-answer
    timer.
- Cost: one Horizon `execute` (two or three Telnyx GETs) every 2 seconds per live call.
- Nothing here has run against Telnyx. Every test uses the fake Telnyx in `brain/tests`.
