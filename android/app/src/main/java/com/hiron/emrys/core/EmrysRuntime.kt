package com.hiron.emrys.core

import android.app.Application
import android.content.Context
import com.hiron.emrys.device.DeviceTools
import com.hiron.emrys.device.EmrysCallScreening
import com.hiron.emrys.device.ReminderStore
import com.hiron.emrys.device.ReminderStoreFile
import com.hiron.emrys.device.Reminders
import com.hiron.emrys.service.Notifications
import com.hiron.emrys.voice.EmrysAgent
import com.hiron.emrys.widget.EmrysWidgets
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class EmrysApp : Application() {
    lateinit var runtime: EmrysRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        runtime = EmrysRuntime(this)
        EmrysWidgets.follow(this, runtime)
    }
}

val Context.emrys: EmrysRuntime get() = (applicationContext as EmrysApp).runtime

/**
 * The rendered [state], the robot's command stream, the phone tools, the ElevenLabs agents ([agent]) and the
 * approvals they ask for with `request_approval`.
 */
class EmrysRuntime(private val app: Context) {
    val settings = Settings(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val _state = MutableStateFlow(EmrysState())
    val state: StateFlow<EmrysState> = _state.asStateFlow()

    private val _robot = MutableSharedFlow<RobotCommand>(extraBufferCapacity = 256)
    val robot: SharedFlow<RobotCommand> = _robot.asSharedFlow()

    @Volatile
    private var shown = false

    /**
     * Whether Emrys's screen is in front of the user (lets device tools open apps directly). Coming to the front
     * clears the approval notifications, since the sheet shows those approvals.
     */
    var visible: Boolean
        get() = shown
        set(value) {
            val appeared = value && !shown
            shown = value
            if (appeared) Notifications.clearApprovals(app, state.value.approvals.map { it.id })
        }

    val reminders = Reminders(app, ReminderStore(File(app.filesDir, ReminderStoreFile)))
    val tools = DeviceTools(app, reminders) { visible }

    /** Approvals the agents are waiting on, by request id. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ApprovalAnswer>>()

    val agent = EmrysAgent(app, settings, tools::run, ::approve, ::update, { _robot.tryEmit(it) }, scope)

    init {
        EmrysCallScreening.onIncoming = { number -> note("call", "Incoming call from ${number.ifBlank { "unknown number" }}") }
    }

    /** Sends [text] to the text agent; returns the id of the user's message. */
    fun ask(text: String): String = agent.sendText(text)

    /** Answers a waiting approval; "Always allow" grants its action to every session from now on. */
    fun answer(id: String, answer: ApprovalAnswer) {
        val request = state.value.approvals.firstOrNull { it.id == id } ?: return
        if (answer.approved && answer.remember) settings.grant(request.action, true)
        pending[id]?.complete(answer)
        note("approval", EmrysStore.answer(request, answer))
    }

    /**
     * Shows [request] on the approval sheet (and as a notification while Emrys is in the background) and waits for
     * the user. When the agent stops waiting first, the request leaves the sheet.
     */
    suspend fun approve(request: ApprovalRequest): ApprovalAnswer {
        val answer = CompletableDeferred<ApprovalAnswer>()
        pending[request.id] = answer
        var answered = false
        return try {
            update { it.copy(approvals = it.approvals + request, activity = Activity.Waiting) }
            if (!visible) Notifications.approval(app, request)
            answer.await().also { answered = true }
        } finally {
            pending.remove(request.id)
            Notifications.clearApproval(app, request.id)
            update { s ->
                val approvals = s.approvals.filterNot { it.id == request.id }
                val activity = if (s.activity == Activity.Waiting && approvals.isEmpty()) Activity.Working else s.activity
                val next = s.copy(approvals = approvals, activity = activity)
                if (answered) next else EmrysStore.note(next, now(), "approval", EmrysStore.EXPIRED)
            }
        }
    }

    fun listening(on: Boolean) = update { it.copy(activity = if (on) Activity.Listening else Activity.Idle) }

    /** Replays the robot's full state (after the page loads). */
    fun robotSnapshot(): List<RobotCommand> = RobotDirector.diff(null, state.value)

    private fun note(kind: String, text: String) = update { EmrysStore.note(it, now(), kind, text) }

    private fun update(change: (EmrysState) -> EmrysState) {
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
