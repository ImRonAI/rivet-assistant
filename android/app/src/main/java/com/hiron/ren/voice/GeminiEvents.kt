package com.hiron.ren.voice

import com.hiron.ren.core.Activity
import com.hiron.ren.core.ChatMessage
import com.hiron.ren.core.ChatPart
import com.hiron.ren.core.RenState
import com.hiron.ren.core.RenStore
import com.hiron.ren.core.Reply
import com.hiron.ren.core.ToolState
import com.hiron.ren.core.ToolStep
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Builds the chat from the Strands events the Gemini service sends (`deploy/vm/gemini/app.py`), in the order they
 * arrive. Text: the reply as it streams (`data`), each finished message (`message`, a Strands `Message`: the reply's
 * text and tool uses, then the tool results), and how the turn ended (`stop_reason`, with the `handoff_to_user`
 * messages as `interrupts`). Voice: Strands' bidi events, whose transcripts are what the user and Ren said, plus
 * the same tool result `message`. It makes the same chat changes as [ChatEvents].
 */
object GeminiEvents {
    /** The tools that run on the phone, which the chat shows as the phone's own. */
    /** A turn the model's provider failed (`{"error": …}`, its own words after this). */
    const val TURN_FAILED = "The agent couldn't answer: "

    private val PHONE = AgentTools.NAMES.toSet() + AgentTools.REQUEST_APPROVAL + SearchTools.NAMES.keys

    /** [state] after [event]; events the chat doesn't show leave it unchanged. */
    fun apply(state: RenState, event: JSONObject): RenState = when {
        event.has("type") -> bidi(state, event)
        event.has("data") -> streamed(state, event.optString("data"))
        event.has("message") -> message(state, event.optJSONObject("message") ?: return state)
        event.has("stop_reason") -> ended(state, event.optJSONArray("interrupts"))
        event.has("error") -> state.copy(lastError = TURN_FAILED + event.optString("error"), activity = Activity.Failed)
        else -> state
    }

    private fun bidi(state: RenState, event: JSONObject): RenState {
        val user = event.optString("role") == "user"
        return when (event.optString("type")) {
            "bidi_transcript_delta" -> if (user) state else streamed(state, event.optString("delta"))
            "bidi_transcript_block" -> if (user) heard(state, event.optString("transcript")) else spoken(state, event.optString("transcript"))
            "bidi_tool_use_blocks" -> event.optJSONArray("tool_uses")?.let { uses -> (0 until uses.length()).mapNotNull(uses::optJSONObject).fold(state, ::used) } ?: state
            else -> state
        }
    }

    /** What the user said in a voice session. */
    private fun heard(state: RenState, text: String): RenState {
        if (text.isBlank()) return state
        val message = ChatMessage("agent:" + UUID.randomUUID().toString().take(12), true, text)
        return state.copy(messages = state.messages + message, activity = Activity.Thinking, lastError = null)
    }

    /** Ren's whole spoken reply replaces what streamed. */
    private fun spoken(state: RenState, text: String): RenState = RenStore.turn(state.copy(activity = Activity.Done)) { m ->
        val last = m.parts.lastOrNull()
        when {
            last is Reply && last.streaming -> m.copy(parts = m.parts.dropLast(1) + ChatEvents.reply(last.id, text.ifBlank { last.raw }, streaming = false))
            text.isNotBlank() -> m.copy(parts = m.parts + ChatEvents.reply("reply:" + UUID.randomUUID().toString().take(12), text, streaming = false))
            else -> m
        }
    }

    /** Text adds to the reply that is streaming, or starts one. */
    private fun streamed(state: RenState, text: String): RenState = if (text.isEmpty()) state else RenStore.turn(state) { m ->
        val last = m.parts.lastOrNull()
        if (last is Reply && last.streaming) {
            m.copy(parts = m.parts.dropLast(1) + ChatEvents.reply(last.id, last.raw + text, streaming = true))
        } else {
            m.copy(parts = m.parts + ChatEvents.reply("reply:" + UUID.randomUUID().toString().take(12), text, streaming = true))
        }
    }

    /** The model's message closes the streamed reply and starts its tool calls; the tool results end them. */
    private fun message(state: RenState, message: JSONObject): RenState {
        val content = message.optJSONArray("content") ?: return state
        val blocks = (0 until content.length()).mapNotNull(content::optJSONObject)
        if (message.optString("role") == "user") return blocks.mapNotNull { it.optJSONObject("toolResult") }.fold(state, ::result)
        val text = blocks.filter { it.has("text") }.joinToString("") { it.optString("text") }
        val closed = RenStore.turn(state) { m ->
            val last = m.parts.lastOrNull()
            when {
                last is Reply && last.streaming -> m.copy(parts = m.parts.dropLast(1) + ChatEvents.reply(last.id, text.ifEmpty { last.raw }, streaming = false))
                text.isNotEmpty() -> m.copy(parts = m.parts + ChatEvents.reply("reply:" + UUID.randomUUID().toString().take(12), text, streaming = false))
                else -> m
            }
        }
        return blocks.mapNotNull { it.optJSONObject("toolUse") }.fold(closed, ::used)
    }

    private fun used(state: RenState, use: JSONObject): RenState {
        val id = use.optString("toolUseId").ifEmpty { return state }
        val name = use.optString("name").ifEmpty { return state }
        val activity = if (name in SearchTools.NAMES) Activity.Searching else Activity.Working
        return RenStore.turn(state.copy(activity = activity)) { m ->
            if (m.parts.any { it is ToolStep && it.id == id }) m
            else m.copy(parts = m.parts + ToolStep(id, name, if (name in PHONE) "client" else "strands", ToolState.Running))
        }
    }

    private fun result(state: RenState, result: JSONObject): RenState {
        val id = result.optString("toolUseId").ifEmpty { return state }
        val failed = result.optString("status") == "error"
        return RenStore.turn(state) { m ->
            val at = m.parts.indexOfFirst { it is ToolStep && it.id == id }
            if (at < 0) m else m.copy(parts = m.parts.replaced(at, (m.parts[at] as ToolStep).copy(state = if (failed) ToolState.Failed else ToolState.Done)))
        }
    }

    /** The turn is over; what `handoff_to_user` says to the user is Ren's reply. */
    private fun ended(state: RenState, interrupts: JSONArray?): RenState {
        val asks = (0 until (interrupts?.length() ?: 0)).mapNotNull { interrupts?.optString(it)?.takeIf(String::isNotBlank) }
        val done = state.copy(activity = Activity.Done)
        if (asks.isEmpty()) return done
        return RenStore.turn(done) { m ->
            m.copy(parts = m.parts + asks.map { ChatEvents.reply("handoff:" + UUID.randomUUID().toString().take(12), it, streaming = false) })
        }
    }

    private fun List<ChatPart>.replaced(at: Int, part: ChatPart): List<ChatPart> = toMutableList().also { it[at] = part }
}
