package com.hiron.emrys.core

/** Everything the screens render. */
data class EmrysState(
    val messages: List<ChatMessage> = emptyList(),
    val activity: Activity = Activity.Idle,
    /** `request_approval` calls waiting for the user, oldest first. */
    val approvals: List<ApprovalRequest> = emptyList(),
    val log: List<LogEntry> = emptyList(),
    val lastError: String? = null,
)

/** What Emrys is doing right now; drives the robot's moves. */
enum class Activity { Idle, Listening, Thinking, Searching, Working, Texting, Emailing, Waiting, Done, Failed }

data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    /** A small line from the app itself. */
    val system: Boolean = false,
    /** Search results shown as cards in place of a bubble; kept in memory only. */
    val search: SearchResults? = null,
    /** A search still running: its cards show as placeholders. */
    val searching: SearchKind? = null,
)

data class LogEntry(val at: Long, val kind: String, val text: String)

object EmrysStore {
    private const val LOG_LIMIT = 200

    const val EXPIRED = "Nobody answered in time, so Emrys didn't do it."

    /** The activity line for one answer. */
    fun answer(request: ApprovalRequest, answer: ApprovalAnswer): String = when {
        answer.approved && answer.remember -> "Always allowed: ${request.action.label}"
        answer.approved -> "Approved: ${request.details}"
        else -> "Declined: ${request.details}"
    }

    fun note(state: EmrysState, now: Long, kind: String, text: String): EmrysState =
        state.copy(log = (state.log + LogEntry(now, kind, text)).takeLast(LOG_LIMIT))

    /** A search started: placeholder cards at the end of the conversation, under [id]. */
    fun searchStarted(state: EmrysState, id: String, kind: SearchKind): EmrysState = state.copy(
        messages = state.messages + ChatMessage(id, false, "", searching = kind),
        activity = Activity.Searching,
    )

    /** The search under [id] finished: its cards replace the placeholders, or join the end when there were none. */
    fun searchShown(state: EmrysState, id: String, results: SearchResults): EmrysState {
        val shown = ChatMessage(id, false, "", search = results)
        val at = state.messages.indexOfFirst { it.id == id }
        val messages = if (at < 0) state.messages + shown else state.messages.toMutableList().also { it[at] = shown }
        return state.copy(messages = messages)
    }

    /** The search under [id] failed: its placeholders go, replaced by [error] when there is one to show. */
    fun searchFailed(state: EmrysState, id: String, error: String?): EmrysState {
        val at = state.messages.indexOfFirst { it.id == id && it.searching != null }
        if (at < 0) return state
        val messages = state.messages.toMutableList()
        if (error == null) messages.removeAt(at) else messages[at] = ChatMessage(id, false, error, system = true)
        return state.copy(messages = messages)
    }
}
