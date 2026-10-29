package com.hiron.rivet.core

import android.app.Application
import android.content.Context
import com.hiron.rivet.device.ActionLedger
import com.hiron.rivet.device.DeviceTools
import com.hiron.rivet.device.ForwardMode
import com.hiron.rivet.device.ReminderStore
import com.hiron.rivet.device.ReminderStoreFile
import com.hiron.rivet.device.Reminders
import com.hiron.rivet.device.RivetCallScreening
import com.hiron.rivet.device.RivetNotificationListener
import com.hiron.rivet.service.CallNote
import com.hiron.rivet.service.Notifications
import com.hiron.rivet.service.RivetService
import com.hiron.rivet.ui.Plain
import com.hiron.rivet.voice.AgentTools
import com.hiron.rivet.voice.RivetAgent
import com.hiron.rivet.voice.Speaker
import com.hiron.rivet.widget.RivetWidgets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RivetApp : Application() {
    lateinit var runtime: RivetRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        runtime = RivetRuntime(this)
        RivetWidgets.follow(this, runtime)
        if (runtime.settings.autoConnect && runtime.settings.gateway().complete) RivetService.start(this)
    }
}

val Context.rivet: RivetRuntime get() = (applicationContext as RivetApp).runtime

/**
 * The app's single brain connection and everything hanging off it: the rendered [state], the robot's
 * command stream, device tool execution (through the [ActionLedger]), approvals and the brain's standing rules.
 */
class RivetRuntime(private val app: Context, private val names: NameLookup = ContactNames(app)) {
    val settings = Settings(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    /** The phone's copy of the brain's standing rules. */
    private val ruleBook = RuleBook(File(app.filesDir, "rules.json")).apply { load() }

    /** The user's recent approval answers, for the recommended standing denies. */
    val decisions = DecisionLog(File(app.filesDir, "decisions.json"))

    private val _state = MutableStateFlow(RivetState(rules = ruleBook.state.value))
    val state: StateFlow<RivetState> = _state.asStateFlow()

    private val _robot = MutableSharedFlow<RobotCommand>(extraBufferCapacity = 256)
    val robot: SharedFlow<RobotCommand> = _robot.asSharedFlow()

    @Volatile
    private var shown = false

    /**
     * Whether Rivet's screen is in front of the user (lets device tools open apps directly). Coming to the front
     * clears the approval notifications, since the sheet shows those approvals.
     */
    var visible: Boolean
        get() = shown
        set(value) {
            val appeared = value && !shown
            shown = value
            if (appeared) Notifications.clearApprovals(app, state.value.approvals.map { it.id })
        }

    /** Whether the assistant overlay is on screen (its replies are read aloud only with [Settings.speakInOverlay]). */
    @Volatile
    var overlayShowing = false

    /** The next `rules` event is the brain's answer to a new connection's `resume`: the full list for this session. */
    @Volatile
    private var resync = true

    /** Calls Rivet is answering that the brain hasn't reported live yet, by call id. */
    private val answering = ConcurrentHashMap<String, CallNote>()

    private val ledger = ActionLedger(File(app.filesDir, "actions.json"))
    val reminders = Reminders(app, ReminderStore(File(app.filesDir, ReminderStoreFile)))
    val tools = DeviceTools(app, reminders, settings) { visible }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val gateway = GatewayClient(http, scope, ::onEvent, ::onConnection)

    /** Approvals the ElevenLabs agents are waiting on, answered on this phone instead of by the brain. */
    private val localApprovals = ConcurrentHashMap<String, CompletableDeferred<Protocol.Answer>>()

    /** The ElevenLabs agents, used instead of the brain's turns when [Settings.elevenLabsAgents] is on. */
    val agent = RivetAgent(
        app, http, settings::gateway, tools::run, { approveOnPhone(it) }, { ruleBook.state.value }, ::setRule, ::update,
        { _robot.tryEmit(it) }, scope,
    )

    /** Turns sent but not yet acknowledged with `turn.accepted`; re-sent on reconnect (the brain dedups by id). */
    private val unacked = LinkedHashMap<String, String>()

    // Built here, on the main thread (the runtime is created in Application.onCreate), as TextToSpeech expects.
    private val speaker = Speaker(
        app,
        onWord = { word, seconds -> _robot.tryEmit(RobotCommand("mouth", listOf(word, seconds))) },
        onDone = { _robot.tryEmit(RobotCommand("quiet", emptyList())) },
    )

    init {
        RivetNotificationListener.onMessage = ::onMessageNotification
        RivetCallScreening.onIncoming = { number -> note("call", "Incoming call from ${number.ifBlank { "unknown number" }}") }
    }

    fun connect() {
        val config = settings.gateway()
        if (!config.complete) {
            update { it.copy(lastError = "Set the brain URL and device token in Settings first.") }
            return
        }
        gateway.connect(config)
    }

    fun disconnect() = gateway.disconnect()

    /** Sends a chat turn; returns its id. */
    fun send(text: String): String {
        val turnId = "t-" + UUID.randomUUID().toString().take(12)
        val message = Protocol.turn(text, turnId)
        synchronized(lock) { unacked[turnId] = message }
        update { RivetStore.userSent(it, turnId, text) }
        speaker.stop()
        gateway.send(message)
        return turnId
    }

    /** Starts a goal-driven background task on its own lane; [reason] is why, in plain words, for its approvals. */
    fun startTask(goal: String, reason: String? = null): String {
        val taskId = UUID.randomUUID().toString().take(12)
        gateway.send(Protocol.task(goal, taskId, reason))
        note("task", "Task queued: $goal")
        return taskId
    }

    /** Answers every approval waiting in [lane]; anything not in [answers] is denied by the brain. */
    fun answer(lane: String, answers: Map<String, Protocol.Answer>) {
        val requests = state.value.approvals.filter { it.id in answers }
        for (request in requests) {
            val offer = request.offer ?: continue
            val answer = answers[request.id] ?: continue
            if (offer.deny) decisions.record(offer.target, offer.label, answer.approved)
        }
        val lines = requests.mapNotNull { r -> answers[r.id]?.let { RivetStore.answer(r, it) } }
        if (lane == AgentTools.LANE) {
            answers.forEach { (id, answer) -> localApprovals[id]?.complete(answer) }
            update { s -> lines.fold(s) { acc, line -> RivetStore.note(acc, now(), "approval", line) } }
            return
        }
        gateway.send(Protocol.approvalResponse(lane, answers))
        Notifications.clearApprovals(app, answers.keys)
        update { s -> lines.fold(RivetStore.answered(s, answers.keys)) { acc, line -> RivetStore.note(acc, now(), "approval", line) } }
    }

    /**
     * Shows [request] on the approval sheet (and as a notification while Rivet is in the background) and waits for
     * the user. No answer within [timeoutMs] is a no.
     */
    suspend fun approveOnPhone(request: ApprovalRequest, timeoutMs: Long = AgentTools.APPROVAL_TIMEOUT_MS): Protocol.Answer {
        val answer = CompletableDeferred<Protocol.Answer>()
        localApprovals[request.id] = answer
        var answered = false
        return try {
            withContext(Dispatchers.IO) { learnNames(listOf(request)) }
            update { it.copy(approvals = it.approvals + request, activity = Activity.Waiting) }
            if (!visible) Notifications.approval(app, request, names = state.value.names)
            val given = withTimeoutOrNull(timeoutMs) { answer.await() }
            answered = given != null
            given ?: Protocol.Answer(false)
        } finally {
            localApprovals.remove(request.id)
            Notifications.clearApproval(app, request.id)
            update { s ->
                val approvals = s.approvals.filterNot { it.id == request.id }
                val activity = if (s.activity == Activity.Waiting && approvals.isEmpty()) Activity.Working else s.activity
                val next = s.copy(approvals = approvals, activity = activity)
                if (answered) next else RivetStore.note(next, now(), "approval", RivetStore.EXPIRED)
            }
        }
    }

    /**
     * Applies recommended settings (task 4): the settings at once, a standing deny per [Recommendation.StopAsking].
     * Permissions and the assistant screen are the caller's.
     */
    fun applyRecommended(chosen: List<Recommendation>) {
        Recommended.apply(chosen, settings, now())
        chosen.filterIsInstance<Recommendation.StopAsking>().forEach { setRule(Decision.Deny, it.candidate.target) }
        note("settings", "Applied recommended settings: ${chosen.joinToString(", ") { it.title }}")
    }

    /**
     * Puts back what the last recommended settings changed and asks the brain to remove the rules they made, also
     * ones this phone hasn't seen listed yet. False, changing nothing, when there is nothing to undo or when there
     * are rules to remove while offline, since only the brain can remove them.
     */
    fun undoRecommended(): Boolean {
        val pending = settings.recommendedUndo ?: return false
        if (pending.ruleTargets.isNotEmpty() && state.value.connection != Connection.Online) return false
        val undo = Recommended.undo(settings) ?: return false
        for (target in undo.ruleTargets) {
            val rule = state.value.rules.match(target)
            if (rule == null || !revokeRule(rule)) gateway.send(Protocol.rulesRevoke(target))
        }
        note("settings", "Undid recommended settings")
        return true
    }

    /** Asks the brain for a standing rule about [target]; the phone's copy changes when the brain says so. */
    fun setRule(decision: Decision, target: RuleTarget) = gateway.send(Protocol.rulesSet(decision, target))

    /** Asks the brain to remove [rule]; false while offline, since only the brain can change rules. */
    fun revokeRule(rule: StandingRule): Boolean {
        if (state.value.connection != Connection.Online) return false
        gateway.send(Protocol.rulesRevoke(rule.key))
        update { it.copy(removingRules = it.removingRules + rule.key, rulesError = null) }
        return true
    }

    /** From a notification action: only offered when [id] is the lane's only pending approval. */
    fun answerOne(lane: String, id: String, approved: Boolean) {
        val pending = state.value.approvals.filter { it.lane == lane }
        if (pending.size > 1 || (pending.isNotEmpty() && pending.single().id != id)) return
        answer(lane, mapOf(id to Protocol.Answer(approved)))
    }

    /**
     * Asks the brain to transfer the call Rivet is answering to the user's own number ([Settings.ownNumber]); the
     * tap is the approval. Returns false, with the reason in [RivetState.lastError], when it can't be sent.
     */
    fun takeOverCall(callId: String): Boolean {
        val forwardsAllCalls = settings.answeringMode == ForwardMode.Always && settings.forwardedTo.isNotBlank()
        val refusal = RivetStore.takeOverRefusal(state.value, callId, settings.ownNumber, forwardsAllCalls)
        if (refusal != null) {
            update { RivetStore.note(it.copy(lastError = refusal), now(), "call", refusal) }
            state.value.liveCall?.let { Notifications.callLive(app, it, notice = refusal) }
            return false
        }
        gateway.send(Protocol.takeOver(callId, settings.ownNumber))
        note("call", "Taking over the call; your phone will ring")
        return true
    }

    /** Clears a finished call from [RivetState.liveCall]. */
    fun dismissLiveCall() = update { RivetStore.dismissCall(it) }

    fun listening(on: Boolean) = update { it.copy(activity = if (on) Activity.Listening else Activity.Idle) }

    fun stopSpeaking() = speaker.stop()

    /** Replays the robot's full state (after the page loads). */
    fun robotSnapshot(): List<RobotCommand> = RobotDirector.diff(null, state.value)

    private fun onEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.TurnAccepted -> event.turnId?.let { synchronized(lock) { unacked.remove(it) } }
            is ServerEvent.DeviceCall -> runDevice(event)
            is ServerEvent.ApprovalsRequested -> {
                learnNames(event.approvals)
                if (!visible) {
                    val lone = event.approvals.size == 1
                    event.approvals.forEach { Notifications.approval(app, it, reviewOnly = it.high || !lone, names = state.value.names) }
                }
            }
            is ServerEvent.ApprovalExpired -> Notifications.clearApprovals(
                app, state.value.approvals.filter { it.turnId == event.turnId && it.lane == event.lane }.map { it.id },
            )
            is ServerEvent.CallAnswering -> CallNote(event.from).let { note ->
                if (event.callId.isNotBlank()) answering[event.callId] = note
                Notifications.callAnswering(app, event.callId, note)
            }
            is ServerEvent.Rules -> {
                ruleBook.replace(event.set, authoritative = resync)
                resync = false
                learnNames(event.set.rules.map { it.label })
                update { it.copy(rules = ruleBook.state.value) }
            }
            is ServerEvent.TurnCompleted -> if (event.lane == "default" && speaksNow()) speaker.speak(event.text)
            else -> Unit
        }
        update { RivetStore.reduce(it, event, now()) }
        if (event is ServerEvent.CallMessage) return callMessage(event)
        // These render the reduced call (with every line so far), so they follow the update.
        val call = state.value.liveCall?.takeIf { it.callId == callIdOf(event) } ?: return
        when (event) {
            is ServerEvent.CallLive -> Notifications.callLive(app, call.with(answering[call.callId]))
            is ServerEvent.CallTakeOverFailed -> Notifications.callLive(app, call.with(answering[call.callId]), notice = event.error)
            is ServerEvent.CallEnded -> Notifications.callEnded(app, call.with(answering.remove(call.callId)))
            else -> Unit
        }
    }

    /** A caller's message joins its call's notification; one for a call this phone doesn't know gets its own. */
    private fun callMessage(event: ServerEvent.CallMessage) {
        val live = state.value.liveCall?.takeIf { event.callId.isNotBlank() && it.callId == event.callId && it.status != LiveCallStatus.Ended }
        val note = answering[event.callId]
        when {
            live != null -> Notifications.callLive(app, live, alert = event.urgent)
            note != null -> note.copy(message = event.message, urgent = event.urgent).let {
                answering[event.callId] = it
                Notifications.callAnswering(app, event.callId, it, alert = event.urgent)
            }
            else -> Notifications.callMessage(app, event)
        }
    }

    /** [this] call with the message its notification already showed while it was only answering. */
    private fun LiveCall.with(note: CallNote?): LiveCall =
        if (note == null || message.isNotBlank() || note.message.isBlank()) this else copy(message = note.message, urgent = note.urgent)

    private fun speaksNow(): Boolean = settings.speakReplies && visible && !(overlayShowing && !settings.speakInOverlay)

    /** Looks up contact names for the numbers in [requests] (off the main thread: it queries Contacts). */
    private fun learnNames(requests: List<ApprovalRequest>) = learnNames(
        requests.flatMap { r ->
            listOfNotNull(r.headline, r.offer?.label) + r.blocks.filterIsInstance<AlertBlock.Field>().map { it.value }
        },
    )

    @JvmName("learnNamesIn")
    private fun learnNames(texts: List<String>) {
        val known = state.value.names
        val found = texts.flatMap(Plain::numbersIn).distinct().filter { it !in known }
            .mapNotNull { number -> names.name(number)?.let { number to it } }
        if (found.isNotEmpty()) update { it.copy(names = it.names + found) }
    }

    private fun callIdOf(event: ServerEvent): String? = when (event) {
        is ServerEvent.CallLive -> event.callId
        is ServerEvent.CallTakeOverFailed -> event.callId
        is ServerEvent.CallEnded -> event.callId
        else -> null
    }

    private fun onConnection(connection: Connection) {
        if (connection == Connection.Connecting) resync = true
        update { RivetStore.connection(it, connection) }
        RivetService.status(app, connection)
        if (connection == Connection.Online) {
            val pending = synchronized(lock) { unacked.values.toList() }
            pending.forEach(gateway::send)
        }
    }

    private fun runDevice(call: ServerEvent.DeviceCall) {
        RobotDirector.forDeviceCall(call.tool, call.input).forEach { _robot.tryEmit(it) }
        scope.launch {
            val result = ledger.run(call.actionId, call.tool) { tools.run(call.tool, call.input).toJson() } ?: return@launch
            gateway.send(Protocol.deviceResult(call.actionId, result))
            val ok = result.optBoolean("ok")
            note("device", "${call.tool}: ${if (ok) "done" else result.optString("data").take(120)}")
        }
    }

    private fun onMessageNotification(record: JSONObject) {
        if (!settings.offerReplies || state.value.connection != Connection.Online) return
        // The message text stays out of the goal: the task reads it with notifications_list, which marks the
        // turn untrusted, so any reply it drafts needs the user's approval.
        val source = record.optString("app")
        startTask(
            "A new $source message notification arrived (key ${record.optString("key")}). Read it with " +
                "notifications_list. If it needs a reply, draft a short one in the user's voice and send it with " +
                "notification_reply; the user approves before anything is sent. If it needs no reply, do nothing.",
            reason = if (source.isBlank()) "A new message arrived." else "A new $source message arrived.",
        )
    }

    private fun note(kind: String, text: String) = update { RivetStore.note(it, now(), kind, text) }

    private fun update(change: (RivetState) -> RivetState) {
        val (before, after) = synchronized(lock) {
            val before = _state.value
            val after = change(before)
            _state.value = after
            before to after
        }
        RobotDirector.diff(before, after).forEach { _robot.tryEmit(it) }
    }

    private fun now() = System.currentTimeMillis()
}
