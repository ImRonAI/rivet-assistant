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
import com.hiron.rivet.service.Notifications
import com.hiron.rivet.service.RivetService
import com.hiron.rivet.voice.Speaker
import com.hiron.rivet.widget.RivetWidgets
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
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.UUID
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
 * command stream, device tool execution (through the [ActionLedger]) and approvals.
 */
class RivetRuntime(private val app: Context) {
    val settings = Settings(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val _state = MutableStateFlow(RivetState())
    val state: StateFlow<RivetState> = _state.asStateFlow()

    private val _robot = MutableSharedFlow<RobotCommand>(extraBufferCapacity = 256)
    val robot: SharedFlow<RobotCommand> = _robot.asSharedFlow()

    /** Whether Rivet's screen is in front of the user (lets device tools open apps directly). */
    @Volatile
    var visible = false

    private val ledger = ActionLedger(File(app.filesDir, "actions.json"))
    val reminders = Reminders(app, ReminderStore(File(app.filesDir, ReminderStoreFile)))
    val tools = DeviceTools(app, reminders, settings) { visible }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val gateway = GatewayClient(http, scope, ::onEvent, ::onConnection)

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

    /** Starts a goal-driven background task on its own lane. */
    fun startTask(goal: String): String {
        val taskId = UUID.randomUUID().toString().take(12)
        gateway.send(Protocol.task(goal, taskId))
        note("task", "Task queued: $goal")
        return taskId
    }

    /** Answers every approval waiting in [lane]; anything not in [answers] is denied by the brain. */
    fun answer(lane: String, answers: Map<String, Protocol.Answer>) {
        gateway.send(Protocol.approvalResponse(lane, answers))
        answers.keys.forEach { Notifications.clearApproval(app, it) }
        update { RivetStore.answered(it, answers.keys).let { s -> RivetStore.note(s, now(), "approval", describe(answers)) } }
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
            is ServerEvent.ApprovalsRequested -> if (!visible) {
                val lone = event.approvals.size == 1
                event.approvals.forEach { Notifications.approval(app, it, reviewOnly = it.high || !lone) }
            }
            is ServerEvent.ApprovalExpired -> state.value.approvals
                .filter { it.turnId == event.turnId && it.lane == event.lane }
                .forEach { Notifications.clearApproval(app, it.id) }
            is ServerEvent.CallAnswering -> Notifications.callAnswering(app, event.from)
            is ServerEvent.CallMessage -> Notifications.callMessage(app, event)
            is ServerEvent.TurnCompleted -> if (event.lane == "default" && settings.speakReplies && visible) {
                speaker.speak(event.text)
            }
            else -> Unit
        }
        update { RivetStore.reduce(it, event, now()) }
        // These render the reduced call (with every line so far), so they follow the update.
        val call = state.value.liveCall?.takeIf { it.callId == callIdOf(event) } ?: return
        when (event) {
            is ServerEvent.CallLive -> Notifications.callLive(app, call)
            is ServerEvent.CallTakeOverFailed -> Notifications.callLive(app, call, notice = event.error)
            is ServerEvent.CallEnded -> Notifications.callEnded(app, call)
            else -> Unit
        }
    }

    private fun callIdOf(event: ServerEvent): String? = when (event) {
        is ServerEvent.CallLive -> event.callId
        is ServerEvent.CallTakeOverFailed -> event.callId
        is ServerEvent.CallEnded -> event.callId
        else -> null
    }

    private fun onConnection(connection: Connection) {
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
        startTask(
            "A new ${record.optString("app")} message notification arrived (key ${record.optString("key")}). Read it with " +
                "notifications_list. If it needs a reply, draft a short one in the user's voice and send it with " +
                "notification_reply; the user approves before anything is sent. If it needs no reply, do nothing.",
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

    private fun describe(answers: Map<String, Protocol.Answer>): String {
        val approved = answers.values.count { it.approved }
        val denied = answers.size - approved
        return listOfNotNull(
            if (approved > 0) "$approved approved" else null,
            if (denied > 0) "$denied denied" else null,
        ).joinToString(", ")
    }

    private fun now() = System.currentTimeMillis()
}
