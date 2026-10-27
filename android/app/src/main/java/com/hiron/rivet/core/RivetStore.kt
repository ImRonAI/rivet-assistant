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
    /** The call Rivet's answering line is on, or the last one it finished until dismissed. */
    val liveCall: LiveCall? = null,
)

/** A call Rivet's Telnyx assistant answered: who, what is being said, and, once [LiveCallStatus.Ended], how it went. */
data class LiveCall(
    val callId: String,
    /** The caller's name once they gave it, otherwise their number. */
    val caller: String,
    val number: String,
    val verified: Boolean,
    /** Epoch milliseconds. */
    val startedAt: Long,
    val status: LiveCallStatus,
    /** Oldest first, the latest [RivetStore.CALL_LINE_LIMIT]. */
    val lines: List<CallLineText> = emptyList(),
    val summary: String = "",
    /** `message_taken`, `no_message`, `caller_hung_up`, `spam` or `unknown`; see [CallText.outcome]. */
    val outcome: String = "",
    val durationSeconds: Int? = null,
    val takenOver: Boolean = false,
)

enum class LiveCallStatus { Answering, Transferring, Ended }

/** One transcript line; [speaker] is `caller` or `rivet`. */
data class CallLineText(val speaker: String, val text: String)

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

/** How a message Rivet took on a call reads in the log and notifications. */
object CallText {
    fun caller(event: ServerEvent.CallMessage): String =
        listOf(event.callerName, event.callbackNumber).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Unknown caller" }

    fun describe(event: ServerEvent.CallMessage): String {
        val flags = listOfNotNull(if (event.urgent) "urgent" else null, if (event.hungUpEarly) "hung up early" else null)
        val suffix = if (flags.isEmpty()) "" else " (${flags.joinToString()})"
        return "Message from ${caller(event)}$suffix: ${event.message.ifBlank { "(no message)" }}"
    }

    fun speaker(line: CallLineText): String = if (line.speaker == "caller") "Caller" else "Rivet"

    fun line(line: CallLineText): String = "${speaker(line)}: ${line.text}"

    fun outcome(outcome: String): String = when (outcome) {
        "message_taken" -> "Message taken."
        "no_message" -> "No message."
        "caller_hung_up" -> "The caller hung up."
        "spam" -> "Looks like spam."
        else -> ""
    }

    fun duration(seconds: Int): String =
        if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"

    /** One line for the activity log and the summary notification. */
    fun summary(call: LiveCall): String {
        val who = listOf(call.caller, call.number).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        val length = call.durationSeconds?.let { ", ${duration(it)}" }.orEmpty()
        val result = if (call.takenOver) "You took it over." else outcome(call.outcome)
        return listOf("Call from ${who.ifBlank { "Unknown caller" }}$length:", call.summary.ifBlank { "No summary." }, result)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
}

object RivetStore {
    private const val LOG_LIMIT = 200
    private const val TRACE_LIMIT = 7
    const val CALL_LINE_LIMIT = 50

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
        is ServerEvent.CallAnswering -> state.log(now, "call", "Rivet is answering a call from ${event.from.ifBlank { "an unknown number" }}")
        is ServerEvent.CallMessage -> state.copy(liveCall = state.liveCall?.named(event.callerName))
            .log(now, "call", CallText.describe(event))
        is ServerEvent.CallLive -> state.copy(liveCall = live(state.liveCall, event, now))
        // The brain reports the call's status with call.live; a refusal can leave it transferring.
        is ServerEvent.CallTakeOverFailed -> state.copy(lastError = event.error)
            .log(now, "call", "Couldn't take over the call: ${event.error}")
        is ServerEvent.CallEnded -> ended(state.liveCall, event, now).let { call ->
            state.copy(liveCall = call).log(now, "call", CallText.summary(call))
        }
        else -> state
    }

    /** Why "Take over" can't be sent for [callId] now, or null when it can. */
    fun takeOverRefusal(state: RivetState, callId: String, ownNumber: String, forwardsAllCalls: Boolean): String? {
        val call = state.liveCall?.takeIf { it.callId == callId }
        return when {
            call == null || call.status == LiveCallStatus.Ended -> "That call has already ended."
            call.status == LiveCallStatus.Transferring -> "Rivet is already transferring that call to you."
            state.connection != Connection.Online -> "Rivet is offline, so it can't transfer the call."
            !isE164(ownNumber) -> "Add your own number, like +18015550100, in Settings › Call answering to take over calls."
            forwardsAllCalls ->
                "All your calls forward to Rivet, so the transfer would come straight back. Forward only unanswered calls to take over."
            else -> null
        }
    }

    /** Clears a finished call; a live one stays. */
    fun dismissCall(state: RivetState): RivetState =
        state.copy(liveCall = state.liveCall?.takeIf { it.status != LiveCallStatus.Ended })

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

    private fun live(current: LiveCall?, event: ServerEvent.CallLive, now: Long): LiveCall {
        val status = if (event.status == "transferring") LiveCallStatus.Transferring else LiveCallStatus.Answering
        if (current == null || current.callId != event.callId) {
            return LiveCall(
                callId = event.callId,
                caller = event.from.ifBlank { "Unknown caller" },
                number = event.from,
                verified = event.verified,
                startedAt = event.startedAt?.let { (it * 1000).toLong() } ?: now,
                status = status,
                lines = event.lines.takeLast(CALL_LINE_LIMIT),
            )
        }
        if (current.status == LiveCallStatus.Ended) return current
        val lines = if (event.snapshot) event.lines else current.lines + event.lines
        return current.copy(status = status, lines = lines.takeLast(CALL_LINE_LIMIT))
    }

    private fun ended(current: LiveCall?, event: ServerEvent.CallEnded, now: Long): LiveCall {
        val call = current?.takeIf { it.callId == event.callId } ?: LiveCall(
            callId = event.callId,
            caller = event.from.ifBlank { "Unknown caller" },
            number = event.from,
            verified = false,
            startedAt = event.startedAt?.let { (it * 1000).toLong() } ?: now,
            status = LiveCallStatus.Ended,
        )
        return call.copy(
            caller = event.callerName.ifBlank { call.caller },
            status = LiveCallStatus.Ended,
            summary = event.summary,
            outcome = event.outcome,
            durationSeconds = event.durationSeconds,
            takenOver = event.takenOver,
        )
    }

    private fun LiveCall.named(name: String): LiveCall =
        if (status == LiveCallStatus.Ended || name.isBlank()) this else copy(caller = name)

    private fun RivetState.trace(tag: String, text: String, tone: String): RivetState =
        copy(trace = (trace + TraceStep(tag, text.take(44), tone)).takeLast(TRACE_LIMIT))

    private fun RivetState.log(now: Long, kind: String, text: String): RivetState =
        copy(log = (log + LogEntry(now, kind, text)).takeLast(LOG_LIMIT))
}
