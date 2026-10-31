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
}
