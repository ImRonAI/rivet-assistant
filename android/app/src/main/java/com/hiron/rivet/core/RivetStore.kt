package com.hiron.rivet.core

/** Everything the screens render, rebuilt from gateway events by [RivetStore.reduce]. */
data class RivetState(
    val connection: Connection = Connection.Offline,
    val messages: List<ChatMessage> = emptyList(),
    val activeTurn: String? = null,
    val activity: Activity = Activity.Idle,
    val trace: List<TraceStep> = emptyList(),
    val approvals: List<ApprovalRequest> = emptyList(),
    val log: List<LogEntry> = emptyList(),
    val lastError: String? = null,
)

enum class Connection { Offline, Connecting, Online }

/** What Rivet is doing right now; drives the robot's moves. */
enum class Activity { Idle, Listening, Thinking, Searching, Working, Texting, Emailing, Calling, Waiting, Done, Failed }

data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    val failed: Boolean = false,
)

data class TraceStep(val tag: String, val text: String, val tone: String)

data class LogEntry(val at: Long, val kind: String, val text: String)

object RivetStore {
    private const val LOG_LIMIT = 200
    private const val TRACE_LIMIT = 7

    fun userSent(state: RivetState, turnId: String, text: String): RivetState = state.copy(
        messages = state.messages + ChatMessage(turnId + ":user", true, text),
        activeTurn = turnId,
        activity = Activity.Thinking,
        trace = listOf(TraceStep("goal", text.take(44), "acc")),
        lastError = null,
    )

    fun reduce(state: RivetState, event: ServerEvent, now: Long): RivetState = when (event) {
        is ServerEvent.TurnStarted -> state.copy(
            activeTurn = if (event.lane == "default") event.turnId else state.activeTurn,
            activity = Activity.Thinking,
        ).log(now, "turn", event.goal?.let { "Task started: $it" } ?: "Working on it")
        is ServerEvent.TextDelta -> if (event.lane != "default") state else state.appendAssistant(event.delta)
        is ServerEvent.Thinking -> state.copy(activity = Activity.Thinking)
        is ServerEvent.Searching -> state.copy(activity = Activity.Searching)
            .trace("search", event.queries.joinToString(" · "), "cy")
            .log(now, "search", event.queries.joinToString(", "))
        is ServerEvent.Sources -> state.trace("observe", "${event.titles.size} sources", "mut")
        is ServerEvent.ToolStarted -> state.copy(activity = activityFor(event.name))
            .trace("tool", event.name, "cy")
            .log(now, "tool", event.name)
        is ServerEvent.ToolsDone -> {
            val failed = event.results.count { it.second != "success" }
            state.trace("check", if (failed == 0) "tools finished" else "$failed tool(s) failed", if (failed == 0) "ok" else "amb")
        }
        is ServerEvent.Backoff -> state.log(now, "backoff", "Model busy; retrying in ${event.delaySeconds.toInt()}s")
        is ServerEvent.Restart -> state.trace("restart", event.reason, "amb").log(now, "restart", event.reason)
        is ServerEvent.Notice -> state.log(now, "notice", event.detail)
        is ServerEvent.ApprovalsRequested -> state.copy(
            approvals = state.approvals.filterNot { a -> event.approvals.any { it.id == a.id } } + event.approvals,
            activity = Activity.Waiting,
        ).log(now, "approval", event.approvals.joinToString("; ") { it.summary })
        is ServerEvent.ApprovalExpired -> state.copy(
            approvals = state.approvals.filterNot { it.turnId == event.turnId && it.lane == event.lane },
        ).log(now, "approval", "Approval timed out; the action was not taken")
        is ServerEvent.TurnCompleted -> state.finishTurn(event, now)
        is ServerEvent.TurnFailed -> state.copy(
            activity = Activity.Failed,
            activeTurn = if (event.lane == "default") null else state.activeTurn,
            lastError = event.error,
            messages = state.closeStreaming(failedText = "I couldn't finish that: ${event.error}"),
        ).log(now, "error", event.error)
        is ServerEvent.Error -> state.copy(lastError = event.message).log(now, "error", event.message)
        is ServerEvent.DeviceCall -> state.log(now, "device", event.tool)
        else -> state
    }

    fun answered(state: RivetState, ids: Set<String>): RivetState =
        state.copy(approvals = state.approvals.filterNot { it.id in ids }, activity = Activity.Thinking)

    fun connection(state: RivetState, connection: Connection): RivetState = state.copy(
        connection = connection,
        lastError = if (connection == Connection.Online) null else state.lastError,
    )

    fun note(state: RivetState, now: Long, kind: String, text: String): RivetState = state.log(now, kind, text)

    fun activityFor(tool: String): Activity = when {
        tool.startsWith("sms_") || tool == "notification_reply" -> Activity.Texting
        tool.startsWith("email_") -> Activity.Emailing
        tool == "phone_call" -> Activity.Calling
        tool.startsWith("telnyx_") -> Activity.Working
        else -> Activity.Working
    }

    private fun RivetState.appendAssistant(delta: String): RivetState {
        val last = messages.lastOrNull()
        val updated = if (last != null && !last.fromUser && last.streaming) {
            messages.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            messages + ChatMessage((activeTurn ?: "turn") + ":rivet:" + messages.size, false, delta, streaming = true)
        }
        return copy(messages = updated)
    }

    private fun RivetState.finishTurn(event: ServerEvent.TurnCompleted, now: Long): RivetState {
        if (event.lane != "default") {
            return log(now, "task", "Task finished${event.goal?.let { " ($it)" } ?: ""}: ${event.text.take(120)}")
        }
        val last = messages.lastOrNull()
        val finished = if (last != null && !last.fromUser && last.streaming) {
            messages.dropLast(1) + last.copy(text = event.text.ifBlank { last.text }, streaming = false)
        } else if (event.text.isNotBlank()) {
            messages + ChatMessage(event.turnId + ":rivet", false, event.text)
        } else {
            messages
        }
        val done = copy(messages = finished, activity = Activity.Done, activeTurn = null)
            .trace("decide", event.text.lineSequence().firstOrNull().orEmpty().take(44), "acc")
        return if (event.restarts > 0) done.log(now, "restart", "Finished after ${event.restarts} restart(s)") else done
    }

    private fun RivetState.closeStreaming(failedText: String): List<ChatMessage> {
        val last = messages.lastOrNull()
        return if (last != null && !last.fromUser && last.streaming) {
            messages.dropLast(1) + last.copy(streaming = false)
        } else {
            messages + ChatMessage("failed:" + messages.size, false, failedText, failed = true)
        }
    }

    private fun RivetState.trace(tag: String, text: String, tone: String): RivetState =
        copy(trace = (trace + TraceStep(tag, text.take(44), tone)).takeLast(TRACE_LIMIT))

    private fun RivetState.log(now: Long, kind: String, text: String): RivetState =
        copy(log = (log + LogEntry(now, kind, text)).takeLast(LOG_LIMIT))
}
