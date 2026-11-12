package com.hiron.ren.core

import com.hiron.ren.browser.Browser
import com.hiron.ren.browser.BrowserStore
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.hiron.ren.BuildConfig
import com.hiron.ren.device.DeviceTools
import com.hiron.ren.device.RenCallScreening
import com.hiron.ren.device.ReminderStore
import com.hiron.ren.device.ReminderStoreFile
import com.hiron.ren.device.Reminders
import com.hiron.ren.mcp.PhoneMcpServer
import com.hiron.ren.mcp.PhoneMcpService
import com.hiron.ren.service.Notifications
import com.hiron.ren.ui.ImageLoader
import com.hiron.ren.voice.AgentTools
import com.hiron.ren.voice.Assistant
import com.hiron.ren.voice.RenAgent
import com.hiron.ren.voice.Provider
import com.hiron.ren.voice.StrandsAgent
import com.hiron.ren.voice.SearchTools
import com.hiron.ren.widget.RenWidgets
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
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class RenApp : Application() {
    lateinit var runtime: RenRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        runtime = RenRuntime(this)
        if (runtime.settings.phoneMcpEnabled) ContextCompat.startForegroundService(this, Intent(this, PhoneMcpService::class.java))
        RenWidgets.follow(this, runtime)
    }
}

val Context.ren: RenRuntime get() = (applicationContext as RenApp).runtime

/**
 * The rendered [state], the robot's command stream, the phone tools, the agents ([agent], ElevenLabs or Strands) and the
 * approvals they ask for with `request_approval`.
 */
class RenRuntime(private val app: Context) {
    val settings = Settings(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val _state = MutableStateFlow(RenState())
    val state: StateFlow<RenState> = _state.asStateFlow()

    private val _robot = MutableSharedFlow<RobotCommand>(extraBufferCapacity = 256)
    val robot: SharedFlow<RobotCommand> = _robot.asSharedFlow()

    @Volatile
    private var shown = false

    /**
     * Whether Ren's screen is in front of the user (lets device tools open apps directly). Coming to the front
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

    private val http = OkHttpClient()

    /** Brave Search for the agents' search tools, with the keys in Settings. */
    val search = BraveSearch(http, settings::braveSearchKey, settings::braveAnswersKey)

    /** The browser's tabs. */
    val browser = Browser(app, BrowserStore({ settings.browserTabs }, { settings.browserTabs = it }))

    /** Result thumbnails and images, cached in memory only. */
    val images = ImageLoader(http)

    private val elevenLabs = RenAgent(app, settings, tools::run, ::approve, ::update, { _robot.tryEmit(it) }, scope, search::search)
    /** Gemini, NVIDIA NIM and Mistral in Strands on the workspace VM. */
    val strands = StrandsAgent(app, settings, http, ::update, { _robot.tryEmit(it) }, scope)

    /** The agents for the provider chosen in Settings. */
    val agent: Assistant
        get() = when (settings.provider) {
            Provider.ElevenLabs -> elevenLabs
            Provider.Gemini, Provider.Nim, Provider.Mistral -> strands
        }

    /** Switches provider; the running conversation ends, and the next one starts with [provider]. */
    fun choose(provider: Provider) {
        if (provider == settings.provider) return
        agent.stop()
        settings.provider = provider
    }

    /** Switches the text model (null: the provider's default) and its reasoning; the next conversation uses them. */
    fun chooseModel(provider: Provider, model: String?) {
        if (model == settings.textModel(provider)) return
        if (provider == settings.provider) agent.stop()
        settings.setTextModel(provider, model)
    }

    fun chooseEffort(provider: Provider, effort: String) {
        if (effort == settings.effort(provider)) return
        if (provider == settings.provider) agent.stop()
        settings.setEffort(provider, effort)
    }

    /** The same phone tools, approvals and searches, for agents that reach the phone over MCP ([PhoneMcpService]). */
    val phoneServer = PhoneMcpServer(
        BuildConfig.VERSION_NAME,
        approve = { input -> AgentTools.askApproval(input.opt("action_type") as? String, input.opt("details") as? String, ::approve) },
        search = { name, input -> SearchTools.call(name, PhoneMcpServer.parameters(input), search::search, ::update) },
        run = tools::run,
    )

    init {
        RenCallScreening.onIncoming = { number -> note("call", "Incoming call from ${number.ifBlank { "unknown number" }}") }
    }

    /** Sends [text] to the text agent; returns the id of the user's message. */
    fun ask(text: String): String = agent.sendText(text)

    /** Answers a waiting approval; "Always allow" grants its action to every session from now on. */
    fun answer(id: String, answer: ApprovalAnswer) {
        val request = state.value.approvals.firstOrNull { it.id == id } ?: return
        if (answer.approved && answer.remember) settings.grant(request.action, true)
        pending[id]?.complete(answer)
        note("approval", RenStore.answer(request, answer))
    }

    /**
     * Shows [request] on the approval sheet (and as a notification while Ren is in the background) and waits for
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
                if (answered) next else RenStore.note(next, now(), "approval", RenStore.EXPIRED)
            }
        }
    }

    /** Ends the conversation: its session stops and the chat clears, back to Ren on its stage. */
    fun newChat() {
        agent.stop()
        update { it.copy(messages = emptyList(), activity = Activity.Idle, lastError = null) }
    }

    fun listening(on: Boolean) = update { it.copy(activity = if (on) Activity.Listening else Activity.Idle) }

    /** Replays the robot's full state (after the page loads). */
    fun robotSnapshot(): List<RobotCommand> = RobotDirector.diff(null, state.value)

    private fun note(kind: String, text: String) = update { RenStore.note(it, now(), kind, text) }

    private fun update(change: (RenState) -> RenState) {
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
