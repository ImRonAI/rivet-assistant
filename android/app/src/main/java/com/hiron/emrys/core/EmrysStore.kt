package com.hiron.emrys.core

/** Everything the screens render, rebuilt from gateway events by [EmrysStore.reduce]. */
data class EmrysState(
    val connection: Connection = Connection.Offline,
    val messages: List<ChatMessage> = emptyList(),
    val activeTurn: String? = null,
    val activity: Activity = Activity.Idle,
    val approvals: List<ApprovalRequest> = emptyList(),
    val log: List<LogEntry> = emptyList(),
    val lastError: String? = null,
    /** The call Emrys's answering line is on, or the last one it finished until dismissed. */
    val liveCall: LiveCall? = null,
    /** The brain's standing approval rules, as the phone last heard them. */
    val rules: RuleSet = RuleSet.EMPTY,
    /** Keys of rules the user asked to remove; they stay listed until the brain's next `rules` event. */
    val removingRules: Set<String> = emptySet(),
    /** Why the brain refused the last rule change, until its next `rules` event. */
    val rulesError: String? = null,
    /** Contact names for the numbers in approvals and rules (E.164 to name), looked up on this phone. */
    val names: Map<String, String> = emptyMap(),
    /** Results the user added for Emrys's own brain; they go with the next turn and clear once it is accepted. */
    val chips: List<ContextItem> = emptyList(),
    /** The model Emrys's own brain runs, as the brain last said; null until it says. */
    val brainModel: BrainModel? = null,
    val availableModels: Set<BrainModel> = emptySet(),
    /** Why the brain refused the last model change, until its next `model` event. */
    val modelError: String? = null,
    /** The ElevenLabs agent couldn't start; Home then offers Emrys's own brain instead. */
    val agentDown: Boolean = false,
)

/** A call Emrys's Telnyx assistant answered: who, what is being said, and, once [LiveCallStatus.Ended], how it went. */
data class LiveCall(
    val callId: String,
    /** The caller's name once they gave it, otherwise their number. */
    val caller: String,
    val number: String,
    val verified: Boolean,
    /** Epoch milliseconds. */
    val startedAt: Long,
    val status: LiveCallStatus,
    /** Oldest first, the latest [EmrysStore.CALL_LINE_LIMIT]. */
    val lines: List<CallLineText> = emptyList(),
    val summary: String = "",
    /** `message_taken`, `no_message`, `caller_hung_up`, `spam` or `unknown`; see [CallText.outcome]. */
    val outcome: String = "",
    val durationSeconds: Int? = null,
    val takenOver: Boolean = false,
    /** The message the caller left on this call, if any. */
    val message: String = "",
    val urgent: Boolean = false,
)

enum class LiveCallStatus { Answering, Transferring, Ended }

/** One transcript line; [speaker] is `caller` or `emrys`. */
data class CallLineText(val speaker: String, val text: String)

enum class Connection { Offline, Connecting, Online }

/** What Emrys is doing right now; drives the robot's moves. */
enum class Activity { Idle, Listening, Thinking, Searching, Working, Texting, Emailing, Waiting, Done, Failed }

data class ChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    val failed: Boolean = false,
    /** Search results shown as cards in place of a bubble; kept in memory only. */
    val search: SearchResults? = null,
    /** A search still running: its cards show as placeholders. */
    val searching: SearchKind? = null,
    /** Results the user sent along with this message. */
    val attachments: List<ContextItem> = emptyList(),
    /** A small line from the app itself, such as what was added to the conversation. */
    val system: Boolean = false,
) {
    /** A bubble with words in it: a reply or the user's own message. */
    val spoken: Boolean get() = search == null && searching == null && !system
}

data class LogEntry(val at: Long, val kind: String, val text: String)

/** How a message Emrys took on a call reads in the log and notifications. */
object CallText {
    fun caller(event: ServerEvent.CallMessage): String =
        listOf(event.callerName, event.callbackNumber).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Unknown caller" }

    fun describe(event: ServerEvent.CallMessage): String {
        val flags = listOfNotNull(if (event.urgent) "urgent" else null, if (event.hungUpEarly) "hung up early" else null)
        val suffix = if (flags.isEmpty()) "" else " (${flags.joinToString()})"
        return "Message from ${caller(event)}$suffix: ${event.message.ifBlank { "(no message)" }}"
    }

    fun speaker(line: CallLineText): String = if (line.speaker == "caller") "Caller" else "Emrys"

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

object EmrysStore {
    private const val LOG_LIMIT = 200
    const val CALL_LINE_LIMIT = 50

    fun userSent(state: EmrysState, turnId: String, text: String, attachments: List<ContextItem> = emptyList()): EmrysState = state.copy(
        messages = state.messages + ChatMessage(turnId + ":user", true, text, attachments = attachments),
        activeTurn = turnId,
        activity = Activity.Thinking,
        lastError = null,
    )

    fun reduce(state: EmrysState, event: ServerEvent, now: Long): EmrysState = when (event) {
        is ServerEvent.TurnStarted -> state.copy(
            activeTurn = if (event.lane == "default") event.turnId else state.activeTurn,
            activity = if (event.lane == "default") Activity.Thinking else state.activity,
        ).log(now, if (event.lane == "default") "turn" else "task", event.goal?.let { "Task started: $it" } ?: "Working on it")
        is ServerEvent.TextDelta -> if (event.lane != "default") state else state.appendAssistant(event.delta)
        is ServerEvent.Thinking -> if (event.lane != "default") state else state.copy(activity = Activity.Thinking)
        is ServerEvent.Searching -> (if (event.lane != "default") state else state.copy(activity = Activity.Searching))
            .log(now, "search", event.queries.joinToString(", "))
        is ServerEvent.ToolStarted -> if (event.lane != "default") {
            state
        } else {
            state.copy(activity = activityFor(event.name))
                .let { s -> SearchKind.ofBrainTool(event.name)?.let { searchStarted(s, brainSearch(event.id), it) } ?: s }
        }
        is ServerEvent.ToolsDone -> {
            val failed = event.results.filter { it.second != "success" }
            failed.fold(state) { s, (id, _) -> searchFailed(s, brainSearch(id), null) }
        }
        is ServerEvent.SearchResults -> if (event.lane != "default") state else searchShown(state, brainSearch(event.toolId), event.results)
        is ServerEvent.TurnAccepted -> {
            val sent = state.messages.firstOrNull { it.id == event.turnId + ":user" }?.attachments.orEmpty()
            if (sent.isEmpty()) state else state.copy(chips = state.chips - sent.toSet())
        }
        is ServerEvent.Model -> state.copy(brainModel = event.model, availableModels = event.available, modelError = null)
        is ServerEvent.ModelRefused -> state.copy(modelError = event.error)
        is ServerEvent.Backoff -> state.log(now, "backoff", "Model busy; retrying in ${event.delaySeconds.toInt()}s")
        is ServerEvent.Restart -> state.log(now, "restart", event.reason)
        is ServerEvent.Notice -> state.log(now, "notice", event.detail)
        is ServerEvent.ApprovalsRequested -> state.copy(
            approvals = state.approvals.filterNot { a -> event.approvals.any { it.id == a.id } } + event.approvals,
            activity = if (event.lane == "default") Activity.Waiting else state.activity,
        ).let { if (event.resent) it else it.log(now, "approval", asked(event.approvals)) }
        is ServerEvent.ApprovalExpired -> state.copy(
            approvals = state.approvals.filterNot { it.turnId == event.turnId && it.lane == event.lane },
        ).log(now, "approval", EXPIRED)
        is ServerEvent.Rules -> state.copy(removingRules = emptySet(), rulesError = null)
        is ServerEvent.RulesRefused -> state.copy(removingRules = emptySet(), rulesError = event.error)
        is ServerEvent.RulesApplied -> state.log(now, "approval", byRule(event.decision, event.label))
        is ServerEvent.TurnCompleted -> state.finishTurn(event, now)
        is ServerEvent.TurnFailed -> state.copy(
            activity = Activity.Failed,
            activeTurn = if (event.lane == "default") null else state.activeTurn,
            lastError = event.error,
            messages = state.closeStreaming(failedText = "I couldn't finish that: ${event.error}"),
        ).log(now, "error", event.error).let { if (event.lane == "default") it.dropPlaceholders() else it }
        is ServerEvent.Error -> state.copy(lastError = event.message).log(now, "error", event.message)
        is ServerEvent.CallAnswering -> state.log(now, "call", "Emrys is answering a call from ${event.from.ifBlank { "an unknown number" }}")
        is ServerEvent.CallMessage -> state.copy(liveCall = state.liveCall?.withMessage(event))
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
    fun takeOverRefusal(state: EmrysState, callId: String, ownNumber: String, forwardsAllCalls: Boolean): String? {
        val call = state.liveCall?.takeIf { it.callId == callId }
        return when {
            call == null || call.status == LiveCallStatus.Ended -> "That call has already ended."
            call.status == LiveCallStatus.Transferring -> "Emrys is already transferring that call to you."
            state.connection != Connection.Online -> "Emrys is offline, so it can't transfer the call."
            !isE164(ownNumber) -> "Add your own number, like +18015550100, in Settings › Call answering to take over calls."
            forwardsAllCalls ->
                "All your calls forward to Emrys, so the transfer would come straight back. Forward only unanswered calls to take over."
            else -> null
        }
    }

    /** Clears a finished call; a live one stays. */
    fun dismissCall(state: EmrysState): EmrysState =
        state.copy(liveCall = state.liveCall?.takeIf { it.status != LiveCallStatus.Ended })

    fun answered(state: EmrysState, ids: Set<String>, lane: String): EmrysState = state.copy(
        approvals = state.approvals.filterNot { it.id in ids },
        activity = if (lane == "default") Activity.Thinking else state.activity,
    )

    const val EXPIRED = "Nobody answered in time, so Emrys didn't do it."

    /** The activity line for approvals Emrys asked for. */
    fun asked(approvals: List<ApprovalRequest>): String = "Asked: " + approvals.joinToString("; ") { it.headline }

    /** The activity line for one answer: a standing rule names its label, a one-time answer the headline. */
    fun answer(request: ApprovalRequest, answer: Protocol.Answer): String {
        val label = request.offer?.label
        return when {
            answer.standing == Decision.Allow && label != null -> "Always allowed: $label"
            answer.standing == Decision.Deny && label != null -> "Always declined: $label"
            answer.approved -> "Approved: ${request.headline}"
            else -> "Declined: ${request.headline}"
        }
    }

    /** The activity line for a call a standing rule decided. */
    fun byRule(decision: Decision, label: String): String =
        if (decision == Decision.Allow) "Allowed by your rule: $label" else "Declined by your rule: $label"

    fun connection(state: EmrysState, connection: Connection): EmrysState = state.copy(
        connection = connection,
        lastError = if (connection == Connection.Online) null else state.lastError,
    )

    fun note(state: EmrysState, now: Long, kind: String, text: String): EmrysState = state.log(now, kind, text)

    /** A search started: placeholder cards at the end of the conversation, under [id]. */
    fun searchStarted(state: EmrysState, id: String, kind: SearchKind): EmrysState = state.copy(
        messages = state.closeStreaming() + ChatMessage(id, false, "", searching = kind),
        activity = Activity.Searching,
    )

    /** The search under [id] finished: its cards replace the placeholders, or join the end when there were none. */
    fun searchShown(state: EmrysState, id: String, results: SearchResults): EmrysState {
        val shown = ChatMessage(id, false, "", search = results)
        val at = state.messages.indexOfFirst { it.id == id }
        val messages = if (at < 0) state.closeStreaming() + shown else state.messages.toMutableList().also { it[at] = shown }
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

    /** The message id of the brain's search tool call [toolId]. */
    fun brainSearch(toolId: String) = "search:$toolId"

    fun activityFor(tool: String): Activity = when {
        SearchKind.ofBrainTool(tool) != null -> Activity.Searching
        tool == "sms_send" || tool == "notification_reply" -> Activity.Texting
        tool == "email_draft" || tool == "email_send" || tool == "email_reply" -> Activity.Emailing
        else -> Activity.Working
    }

    private fun EmrysState.appendAssistant(delta: String): EmrysState {
        val last = messages.lastOrNull()
        val updated = if (last != null && !last.fromUser && last.streaming) {
            messages.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            messages + ChatMessage((activeTurn ?: "turn") + ":emrys:" + messages.size, false, delta, streaming = true)
        }
        return copy(messages = updated)
    }

    private fun EmrysState.finishTurn(event: ServerEvent.TurnCompleted, now: Long): EmrysState {
        if (event.lane != "default") {
            return log(now, "task", "Task finished${event.goal?.let { " ($it)" } ?: ""}: ${event.text.take(120)}")
        }
        val last = messages.lastOrNull()
        val finished = if (last != null && !last.fromUser && last.streaming) {
            messages.dropLast(1) + last.copy(text = event.text.ifBlank { last.text }, streaming = false)
        } else if (event.text.isNotBlank()) {
            messages + ChatMessage(event.turnId + ":emrys", false, event.text)
        } else {
            messages
        }
        val done = copy(messages = finished, activity = Activity.Done, activeTurn = null).dropPlaceholders()
        return if (event.restarts > 0) done.log(now, "restart", "Finished after ${event.restarts} restart(s)") else done
    }

    /** Placeholders for searches whose results never came. */
    private fun EmrysState.dropPlaceholders(): EmrysState = copy(messages = messages.filter { it.searching == null || !it.id.startsWith("search:") })

    /** The conversation with its streaming reply, if any, finished as it stands. */
    private fun EmrysState.closeStreaming(): List<ChatMessage> {
        val last = messages.lastOrNull()
        return if (last != null && !last.fromUser && last.streaming) messages.dropLast(1) + last.copy(streaming = false) else messages
    }

    private fun EmrysState.closeStreaming(failedText: String): List<ChatMessage> {
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

    private fun LiveCall.withMessage(event: ServerEvent.CallMessage): LiveCall {
        if (status == LiveCallStatus.Ended) return this
        val named = if (event.callerName.isBlank()) this else copy(caller = event.callerName)
        return if (event.callId.isNotBlank() && event.callId == callId) named.copy(message = event.message, urgent = event.urgent) else named
    }

    private fun EmrysState.log(now: Long, kind: String, text: String): EmrysState =
        copy(log = (log + LogEntry(now, kind, text)).takeLast(LOG_LIMIT))
}
