package com.hiron.ren.voice

import com.hiron.ren.core.Activity
import com.hiron.ren.core.BraveCodec
import com.hiron.ren.core.ChatMessage
import com.hiron.ren.core.ChatPart
import com.hiron.ren.core.RenState
import com.hiron.ren.core.RenStore
import com.hiron.ren.core.Reasoning
import com.hiron.ren.core.Reply
import com.hiron.ren.core.SearchKind
import com.hiron.ren.core.SearchResults
import com.hiron.ren.core.ToolState
import com.hiron.ren.core.ToolStep
import org.json.JSONObject
import java.util.UUID

/**
 * Builds the chat from the agent's raw server messages (`ConversationConfig.onMessage`), in the order they arrive:
 * the user's spoken words, Ren's replies as they stream, its reasoning summary, and every tool call with its state
 * and, for the searches, what was found. The event shapes are the ElevenLabs client events, as both agents send them.
 */
object ChatEvents {
    /** The search tools, by their tool name; webhooks send Brave's response as the full result. */
    private val SEARCHES = SearchTools.NAMES

    /** [state] after the server message [event]; messages the chat doesn't show leave it unchanged. */
    fun apply(state: RenState, event: JSONObject, now: Long, voice: Boolean): RenState = when (event.optString("type")) {
        "user_transcript" -> heard(state, event.optJSONObject("user_transcription_event"), voice)
        "agent_chat_response_part" -> part(state, event.optJSONObject("text_response_part") ?: return state)
        "agent_response" -> response(state, event.optJSONObject("agent_response_event") ?: return state)
        "agent_response_correction" -> correction(state, event.optJSONObject("agent_response_correction_event") ?: return state)
        "agent_reasoning_response_part" -> reasoning(state, event.optJSONObject("reasoning_response_part") ?: return state, now)
        "client_tool_call" -> event.optJSONObject("client_tool_call")?.let { requested(state, it, "client") } ?: state
        "agent_tool_request" -> event.optJSONObject("agent_tool_request")?.let { requested(state, it, it.optString("tool_type")) } ?: state
        "mcp_tool_call" -> mcp(state, event.optJSONObject("mcp_tool_call") ?: return state)
        "agent_tool_response" -> answered(state, event.optJSONObject("agent_tool_response") ?: return state)
        "agent_tool_response_full_payload" -> payload(state, event.optJSONObject("agent_tool_response_full_payload") ?: return state)
        else -> state
    }

    /** What the user said in a voice session; typed messages are already in the chat. */
    private fun heard(state: RenState, event: JSONObject?, voice: Boolean): RenState {
        val text = event?.optString("user_transcript").orEmpty()
        if (!voice || text.isBlank()) return state
        val message = ChatMessage("agent:" + UUID.randomUUID().toString().take(12), true, text)
        return state.copy(messages = state.messages + message, activity = Activity.Thinking, lastError = null)
    }

    private fun part(state: RenState, part: JSONObject): RenState {
        val id = part.optString("response_id").ifEmpty { return state }
        val text = part.optString("text")
        return when (part.optString("type")) {
            "delta" -> if (text.isEmpty()) state else RenStore.turn(state) { m ->
                val at = m.parts.indexOfFirst { it is Reply && it.id == id }
                if (at < 0) {
                    m.copy(parts = m.parts + reply(id, text, streaming = true))
                } else {
                    val old = m.parts[at] as Reply
                    m.copy(parts = m.parts.replaced(at, reply(id, old.raw + text, streaming = true)))
                }
            }
            "stop" -> changeReply(state, id) { reply(id, it.raw, streaming = false) }
            else -> state
        }
    }

    /** The response's final text; it replaces what streamed, and is the only text when nothing streamed. */
    private fun response(state: RenState, event: JSONObject): RenState {
        val raw = event.optString("agent_response")
        val id = event.optString("response_id").ifEmpty { "response:" + UUID.randomUUID().toString().take(12) }
        val done = state.copy(activity = Activity.Done)
        if (AgentTools.withoutAudioTags(raw).isBlank()) return changeReply(done, id) { reply(id, it.raw, streaming = false) }
        return RenStore.turn(done) { m ->
            val at = m.parts.indexOfFirst { it is Reply && it.id == id }
            if (at < 0) m.copy(parts = m.parts + reply(id, raw, streaming = false)) else m.copy(parts = m.parts.replaced(at, reply(id, raw, streaming = false)))
        }
    }

    /** The voice was interrupted: the reply that said [original] becomes what was actually spoken. */
    private fun correction(state: RenState, event: JSONObject): RenState {
        val original = event.optString("original_agent_response")
        val corrected = event.optString("corrected_agent_response")
        val turn = state.messages.lastOrNull()?.takeIf { !it.fromUser && !it.system } ?: return state
        val at = turn.parts.indexOfLast { it is Reply && it.raw == original }.takeIf { it >= 0 } ?: turn.parts.indexOfLast { it is Reply }
        if (at < 0) return state
        val old = turn.parts[at] as Reply
        return RenStore.turn(state) { it.copy(parts = it.parts.replaced(at, reply(old.id, corrected, streaming = false))) }
    }

    /** Each reasoning segment adds to the turn's one reasoning block, a paragraph apart. */
    private fun reasoning(state: RenState, part: JSONObject, now: Long): RenState {
        val text = part.optString("text")
        return when (part.optString("type")) {
            "start" -> RenStore.turn(state.copy(activity = Activity.Thinking)) { m ->
                val r = m.reasoning ?: Reasoning("")
                val gap = if (r.text.isBlank() || r.text.endsWith("\n\n")) "" else "\n\n"
                m.copy(reasoning = r.copy(text = r.text + gap, openedAt = r.openedAt ?: now))
            }
            "delta" -> if (text.isEmpty()) state else RenStore.turn(state) { m ->
                val r = m.reasoning ?: Reasoning("", openedAt = now)
                m.copy(reasoning = r.copy(text = r.text + text, openedAt = r.openedAt ?: now))
            }
            "stop" -> RenStore.turn(state) { m ->
                val r = m.reasoning ?: return@turn m
                val opened = r.openedAt ?: return@turn m
                m.copy(reasoning = r.copy(spentMs = r.spentMs + (now - opened).coerceAtLeast(0), openedAt = null))
            }
            else -> state
        }
    }

    /** A tool call starts: one step per call id, so the agent's and the phone's events for one call meet. */
    private fun requested(state: RenState, event: JSONObject, type: String): RenState {
        val id = event.optString("tool_call_id").ifEmpty { return state }
        val name = event.optString("tool_name").ifEmpty { return state }
        val activity = if (name in SEARCHES) Activity.Searching else Activity.Working
        return RenStore.turn(state.copy(activity = activity)) { m ->
            val at = m.parts.indexOfFirst { it is ToolStep && it.id == id }
            // A step the phone's own search already made for this tool takes the call's id.
            val early = m.parts.indexOfLast { it is ToolStep && it.name == name && it.id.startsWith(RenStore.PHONE_STEP) }
            when {
                at >= 0 -> m
                early >= 0 -> m.copy(parts = m.parts.replaced(early, (m.parts[early] as ToolStep).copy(id = id)))
                else -> m.copy(parts = m.parts + ToolStep(id, name, type.ifEmpty { "client" }, ToolState.Running))
            }
        }
    }

    /**
     * An MCP call's progress. It carries the call's own id and the tool's short name (`search`), while the agent's
     * request names the server too (`oubliai-telnyx_search`), so it joins the latest such request without one.
     */
    private fun mcp(state: RenState, event: JSONObject): RenState {
        val mcpId = event.optString("tool_call_id").ifEmpty { return state }
        val name = event.optString("tool_name").ifEmpty { return state }
        val toolState = when (event.optString("state")) {
            "success" -> ToolState.Done
            "failure" -> ToolState.Failed
            else -> ToolState.Running
        }
        return RenStore.turn(state) { m ->
            val known = m.parts.indexOfFirst { it is ToolStep && it.mcpId == mcpId }
            val at = if (known >= 0) known else m.parts.indexOfLast { it is ToolStep && it.type == "mcp" && it.mcpId == null && it.name.endsWith("_$name") }
            if (at < 0) {
                m.copy(parts = m.parts + ToolStep(mcpId, name, "mcp", toolState, mcpId = mcpId))
            } else {
                val step = m.parts[at] as ToolStep
                m.copy(parts = m.parts.replaced(at, step.copy(mcpId = mcpId, state = if (step.state == ToolState.Running) toolState else step.state)))
            }
        }
    }

    private fun answered(state: RenState, event: JSONObject): RenState {
        val failed = event.optBoolean("is_error") || event.optBoolean("is_blocked") || event.optString("status", "success") != "success"
        return changeStep(state, event) { it.copy(state = if (failed) ToolState.Failed else ToolState.Done) }
    }

    /** A webhook search's full result is Brave's own response, which becomes the step's results. */
    private fun payload(state: RenState, event: JSONObject): RenState {
        val kind = SEARCHES[event.optString("tool_name")]
        val failed = event.optBoolean("is_error") || event.optBoolean("is_blocked")
        val results = if (kind == null || failed) null else results(kind, event.optString("full_tool_result"))
        return changeStep(state, event) { step ->
            step.copy(state = if (failed) ToolState.Failed else ToolState.Done, search = step.search ?: results)
        }
    }

    /** Brave's JSON as results; null when it isn't one (cut short, or an error text). */
    internal fun results(kind: SearchKind, full: String): SearchResults? {
        if (kind == SearchKind.Answers) return null
        val data = runCatching { JSONObject(full) }.getOrNull() ?: return null
        return BraveCodec.results(kind, "", data).takeIf { it.items.isNotEmpty() || it.query.isNotEmpty() }
    }

    private fun changeStep(state: RenState, event: JSONObject, change: (ToolStep) -> ToolStep): RenState {
        val id = event.optString("tool_call_id").ifEmpty { return state }
        val turn = state.messages.lastOrNull()?.takeIf { !it.fromUser && !it.system }
        val known = turn?.parts?.any { it is ToolStep && it.id == id } == true
        val started = if (known) state else requested(state, event, event.optString("tool_type"))
        return RenStore.turn(started) { m ->
            val at = m.parts.indexOfFirst { it is ToolStep && it.id == id }
            if (at < 0) m else m.copy(parts = m.parts.replaced(at, change(m.parts[at] as ToolStep)))
        }
    }

    private fun changeReply(state: RenState, id: String, change: (Reply) -> Reply): RenState {
        val turn = state.messages.lastOrNull()?.takeIf { !it.fromUser && !it.system } ?: return state
        val at = turn.parts.indexOfFirst { it is Reply && it.id == id }
        if (at < 0) return state
        return RenStore.turn(state) { it.copy(parts = it.parts.replaced(at, change(it.parts[at] as Reply))) }
    }

    /** A reply as the chat shows it: no audio tags, and while it streams no half-arrived tag at its end. */
    internal fun reply(id: String, raw: String, streaming: Boolean): Reply {
        val shown = AgentTools.withoutAudioTags(raw).let { if (streaming) OPEN_TAG.replace(it, "").trimEnd() else it }
        return Reply(id, raw, shown, streaming)
    }

    private val OPEN_TAG = Regex("""\[[\w\s]*$""")

    private fun List<ChatPart>.replaced(at: Int, part: ChatPart): List<ChatPart> = toMutableList().also { it[at] = part }
}
