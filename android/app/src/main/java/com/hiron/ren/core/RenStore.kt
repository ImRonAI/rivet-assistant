package com.hiron.ren.core

import java.util.UUID

/** Everything the screens render. */
data class RenState(
    val messages: List<ChatMessage> = emptyList(),
    val activity: Activity = Activity.Idle,
    /** `request_approval` calls waiting for the user, oldest first. */
    val approvals: List<ApprovalRequest> = emptyList(),
    val log: List<LogEntry> = emptyList(),
    val lastError: String? = null,
    /** A voice session is starting or on. */
    val voice: Boolean = false,
)

/** What Ren is doing right now; drives the robot's moves. */
enum class Activity { Idle, Listening, Thinking, Searching, Working, Texting, Emailing, Waiting, Done, Failed }

data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    /** The user's words, an app line, or Ren's replies in this turn joined. */
    val text: String,
    /** A small line from the app itself. */
    val system: Boolean = false,
    /** Ren's turn in the order it happened: what it said ([Reply]) and the tools it used ([ToolStep]). */
    val parts: List<ChatPart> = emptyList(),
    /** The turn's reasoning, every segment in one: the ElevenLabs text agent's summary or a Strands text model's thinking. */
    val reasoning: Reasoning? = null,
)

sealed interface ChatPart {
    val id: String
}

/** One response from the agent, by its `response_id`; [raw] still has the voice's audio tags. */
data class Reply(override val id: String, val raw: String, val text: String, val streaming: Boolean = false) : ChatPart

enum class ToolState { Running, Done, Failed }

/**
 * One tool call; [type] is the agent's tool type: `client` (the phone), `webhook`, `mcp` or `system`, or `strands` for the
 * Gemini agents' other tools.
 */
data class ToolStep(
    override val id: String,
    val name: String,
    val type: String,
    val state: ToolState,
    /** What a search tool found: from the phone's own search, or from a webhook's full result. */
    val search: SearchResults? = null,
    /** The MCP call's own id (`tlcal_…`); the agent names the same call by another id. */
    val mcpId: String? = null,
) : ChatPart

/** Reasoning text and how long it took: [spentMs] for the closed segments, [openedAt] while one streams. */
data class Reasoning(val text: String, val spentMs: Long = 0, val openedAt: Long? = null) {
    val streaming: Boolean get() = openedAt != null

    /** Whole seconds spent thinking, rounded up; null while it streams. */
    val seconds: Int? get() = if (streaming) null else ((spentMs + 999) / 1000).toInt()
}

data class LogEntry(val at: Long, val kind: String, val text: String)

object RenStore {
    private const val LOG_LIMIT = 200

    const val EXPIRED = "Nobody answered in time, so Ren didn't do it."

    /** Ids of steps the phone made before the agent's event for the call arrived. */
    const val PHONE_STEP = "phone:"

    /** The activity line for one answer. */
    fun answer(request: ApprovalRequest, answer: ApprovalAnswer): String = when {
        answer.approved && answer.remember -> "Always allowed: ${request.action.label}"
        answer.approved -> "Approved: ${request.details}"
        else -> "Declined: ${request.details}"
    }

    fun note(state: RenState, now: Long, kind: String, text: String): RenState =
        state.copy(log = (state.log + LogEntry(now, kind, text)).takeLast(LOG_LIMIT))

    /**
     * The phone's own search under the tool [name] finished: its results go on the turn's latest step for that tool
     * that has none yet, or on a new step when the agent's event for the call hasn't arrived.
     */
    fun searched(state: RenState, name: String, results: SearchResults): RenState = turn(state) { message ->
        val at = message.parts.indexOfLast { it is ToolStep && it.name == name && it.search == null }
        val parts = if (at < 0) {
            message.parts + ToolStep(PHONE_STEP + UUID.randomUUID().toString().take(12), name, "client", ToolState.Done, results)
        } else {
            message.parts.toMutableList().also { it[at] = (it[at] as ToolStep).copy(search = results) }
        }
        message.copy(parts = parts)
    }

    /**
     * Changes Ren's current turn: the last message when it is one, else a new turn at the end. The turn's [ChatMessage.text]
     * follows its replies.
     */
    fun turn(state: RenState, change: (ChatMessage) -> ChatMessage): RenState {
        val last = state.messages.lastOrNull()
        val current = last?.takeIf { !it.fromUser && !it.system }
        val changed = change(current ?: ChatMessage("agent:" + UUID.randomUUID().toString().take(12), false, "")).let { m ->
            m.copy(text = m.parts.filterIsInstance<Reply>().map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n"))
        }
        val messages = if (current != null) state.messages.dropLast(1) + changed else state.messages + changed
        return state.copy(messages = messages)
    }
}
