package com.hiron.ren.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.hiron.ren.core.Activity
import com.hiron.ren.core.ApprovalAnswer
import com.hiron.ren.core.ApprovalRequest
import com.hiron.ren.core.ChatMessage
import com.hiron.ren.core.RenState
import com.hiron.ren.core.RenStore
import com.hiron.ren.core.PermAction
import com.hiron.ren.core.RobotCommand
import com.hiron.ren.core.RobotDirector
import com.hiron.ren.core.SearchQuery
import com.hiron.ren.core.SearchResults
import com.hiron.ren.core.Settings
import com.hiron.ren.core.VoiceTurn
import com.hiron.ren.device.ScreenOperator
import com.hiron.ren.device.ToolResult
import io.elevenlabs.ClientTool
import io.elevenlabs.ClientToolResult
import io.elevenlabs.ConversationClient
import io.elevenlabs.ConversationConfig
import io.elevenlabs.ConversationSession
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import io.elevenlabs.models.DisconnectionDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Ren's ElevenLabs agents, connected straight through the ElevenLabs Android SDK: [start] a voice or text
 * session, [sendText], [switchMode], [stop]. Every session starts with the user's id and their `perm_*` grants
 * as dynamic variables; the agents call the phone tools, `request_approval` and the search tools ([SearchTools])
 * as client tools. The chat is built from the session's server messages ([ChatEvents]).
 */
class RenAgent(
    private val context: Context,
    private val settings: Settings,
    private val run: suspend (String, JSONObject) -> ToolResult,
    private val approve: suspend (ApprovalRequest) -> ApprovalAnswer,
    private val update: ((RenState) -> RenState) -> Unit,
    private val robot: (RobotCommand) -> Unit,
    private val scope: CoroutineScope,
    private val search: suspend (SearchQuery) -> Result<SearchResults>,
    private val connect: suspend (ConversationConfig, Context) -> ConversationSession = { config, context -> ConversationClient.startSession(config, context) },
) : Assistant {
    // Main thread only. Every start and stop moves [generation] on, so a stale start or callback can tell.
    private var generation = 0
    private var session: ConversationSession? = null
    private var starting: Job? = null
    private var queued = mutableListOf<String>()
    private var lips = Job()
    private var lastChunk: Job? = null

    @Volatile
    override var mode: AgentMode? = null
        private set

    override fun start(mode: AgentMode, firstMessage: String?) {
        stop()
        val gen = generation
        val messages = mutableListOf<String>().also { queued = it }
        firstMessage?.let(messages::add)
        this.mode = mode
        update { it.copy(voice = mode == AgentMode.Voice) }
        starting = scope.launch(Dispatchers.Main) {
            var started: ConversationSession? = null
            try {
                if (mode == AgentMode.Voice && !hasMicrophone()) {
                    fail(gen, NEEDS_MICROPHONE)
                    return@launch
                }
                val connected = CompletableDeferred<Unit>()
                // Not cancellable, so a session that did start is always handed over or ended below.
                started = withContext(NonCancellable) { connect(config(mode, gen, connected), context) }
                ensureActive()
                if (messages.isNotEmpty()) {
                    connected.await()
                    messages.forEach(started::sendUserMessage)
                    messages.clear()
                }
                session = started
                started = null
            } catch (e: Exception) {
                started?.let(::end)
                if (e is CancellationException) throw e
                fail(gen, COULD_NOT_START)
            }
        }
    }

    override fun sendText(text: String): String {
        val id = said(text)
        if (mode == AgentMode.Text && starting?.isActive == true) {
            queued += text
            return id
        }
        val live = session
        if (mode != AgentMode.Text || live == null) {
            start(AgentMode.Text, firstMessage = text)
            return id
        }
        try {
            live.sendUserMessage(text)
        } catch (e: IllegalStateException) {
            start(AgentMode.Text, firstMessage = text)
        }
        return id
    }

    override fun switchMode(mode: AgentMode) {
        if (this.mode != mode) start(mode)
    }

    override fun stop() {
        generation++
        starting?.cancel()
        starting = null
        hush()
        val ending = session
        session = null
        if (mode == AgentMode.Voice) robot(RobotDirector.voice(VoiceTurn.None))
        mode = null
        ending?.let(::end)
        update { it.copy(activity = if (it.activity == Activity.Listening) Activity.Idle else it.activity, voice = false) }
    }

    private fun end(ending: ConversationSession) {
        scope.launch(Dispatchers.Main) { runCatching { ending.endSession() } }
    }

    private fun config(mode: AgentMode, gen: Int, connected: CompletableDeferred<Unit>): ConversationConfig {
        val voice = mode == AgentMode.Voice
        var heard: ConversationMode? = null
        return ConversationConfig(
            agentId = AGENTS.getValue(mode),
            userId = settings.userId,
            dynamicVariables = settings.permissionVariables() + ("user_id" to settings.userId),
            textOnly = !voice,
            clientTools = AgentTools.build(::runOnPhone) + (AgentTools.REQUEST_APPROVAL to AgentTools.approval(approve)) +
                SearchTools.build(search, update),
            onConnect = { connected.complete(Unit) },
            onDisconnect = { details ->
                connected.completeExceptionally(IllegalStateException("the agent ended the session"))
                onMain(gen) {
                    // A session ElevenLabs closes with anything but a normal close says why (no credits left, for one).
                    if (details is DisconnectionDetails.Error) {
                        stop()
                        failed(ENDED_BY_ELEVENLABS + (details.exception.message ?: details.reason))
                    } else if (session != null) {
                        stop()
                    }
                }
            },
            // The SDK reports a mode with every data packet; only changes matter.
            onModeChange = { mode ->
                onMain(gen) {
                    if (mode != heard) {
                        heard = mode
                        if (mode == ConversationMode.LISTENING) robot(RobotCommand("quiet", emptyList()))
                        if (voice) {
                            val turn = when (mode) {
                                ConversationMode.LISTENING -> VoiceTurn.Listening
                                ConversationMode.SPEAKING -> VoiceTurn.Speaking
                            }
                            robot(RobotDirector.voice(turn))
                        }
                        activity(if (mode == ConversationMode.LISTENING) Activity.Listening else Activity.Idle)
                    }
                }
            },
            // The agent's audio level as the SDK reports it (every 50 ms in a voice session), for the robot.
            onAudioLevelChanged = { level -> if (voice) onMain(gen) { robot(RobotDirector.level(level)) } },
            onStatusChange = { status ->
                if (status == ConversationStatus.DISCONNECTED || status == ConversationStatus.ERROR) onMain(gen) { activity(Activity.Idle) }
            },
            onInterruption = { onMain(gen) { hush() } },
            onAudioAlignment = { alignment -> onMain(gen) { lipSync(alignment) } },
            // Every server message as it arrives, before the SDK parses it: the chat is built from these, in order.
            onMessage = { source, message -> if (source == "ai") chat(gen, message, voice) },
            onError = { code, message -> note("ElevenLabs error $code${message?.let { ": $it" }.orEmpty()}") },
        )
    }

    private fun chat(gen: Int, message: String, voice: Boolean) {
        val now = System.currentTimeMillis()
        val event = runCatching { JSONObject(message) }.getOrNull() ?: return
        onMain(gen) {
            if (event.optString("type") == "user_transcript") ScreenOperator.resume()
            update { ChatEvents.apply(it, event, now, voice) }
        }
    }

    private fun onMain(gen: Int, block: () -> Unit) {
        scope.launch(Dispatchers.Main) { if (gen == generation) block() }
    }

    private suspend fun runOnPhone(name: String, input: JSONObject): ToolResult {
        val result = withContext(Dispatchers.Default) { run(name, input) }
        note("$name: ${if (result.ok) "done" else result.data.toString().take(120)}", kind = "device")
        return result
    }

    /** Mouths each alignment chunk after the one before it has been spoken. */
    private fun lipSync(alignment: Map<String, Any>) {
        val before = lastChunk
        lastChunk = scope.launch(lips + Dispatchers.Main) {
            before?.join()
            mouth(AgentTools.words(alignment))
        }
    }

    /** Stops the mouth, including chunks still waiting their turn. */
    private fun hush() {
        lips.cancel()
        lips = Job()
        lastChunk = null
        robot(RobotCommand("quiet", emptyList()))
    }

    private suspend fun mouth(words: List<AgentTools.Spoken>) {
        var at = 0.0
        for (word in words) {
            delay((word.startMs - at).toLong().coerceAtLeast(0))
            at = word.startMs
            robot(RobotCommand("mouth", listOf(word.text, word.seconds)))
        }
        words.lastOrNull()?.let { delay((it.seconds * 1000).toLong()) }
    }

    override fun hasMicrophone() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Adds the user's typed message to the conversation; returns its id. */
    private fun said(text: String): String {
        // The user asking Ren anything lifts a Stop they pressed on the app operator.
        ScreenOperator.resume()
        val message = ChatMessage("agent:" + UUID.randomUUID().toString().take(12), true, text)
        update { it.copy(messages = it.messages + message, activity = Activity.Thinking, lastError = null) }
        return message.id
    }

    private fun activity(activity: Activity) = update { it.copy(activity = activity) }

    private fun note(text: String, kind: String = "agent") = update { RenStore.note(it, System.currentTimeMillis(), kind, text) }

    private fun fail(gen: Int, error: String) {
        if (gen != generation) return
        mode = null
        failed(error)
    }

    private fun failed(error: String) =
        update { RenStore.note(it.copy(lastError = error, activity = Activity.Failed), System.currentTimeMillis(), "error", error) }

    companion object {
        /** Ren's public ElevenLabs agents. */
        val AGENTS = mapOf(
            AgentMode.Voice to "agent_2901m620nzwrev2r7ekqpydcxe25",
            AgentMode.Text to "agent_9601m623qa72ffs9cyy3ezc0p6tb",
        )

        const val NEEDS_MICROPHONE = "Ren needs the microphone to talk. Tap the mic to allow it."
        const val COULD_NOT_START = "Couldn't reach the ElevenLabs agent. Check your connection and try again."
        const val ENDED_BY_ELEVENLABS = "ElevenLabs ended the conversation: "
    }
}

/** The phone tools and `request_approval` as ElevenLabs client tools. */
object AgentTools {
    /** Every phone tool the agents call, by its client tool name. */
    val NAMES = listOf(
        "sms_send", "sms_list", "contacts_search", "calls_recent", "phone_call", "calendar_list", "calendar_create",
        "notifications_list", "notification_reply", "reminder_create", "reminders_list", "reminder_cancel", "email_draft",
        "apps_list", "app_open", "device_status", "screen_read", "screen_act",
    )

    const val REQUEST_APPROVAL = "request_approval"

    fun build(run: suspend (String, JSONObject) -> ToolResult): Map<String, ClientTool> = NAMES.associateWith { name ->
        object : ClientTool {
            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult = try {
                result(run(name, json(parameters)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ClientToolResult.failure("This action failed on the phone: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * `request_approval(action_type, details)`: shows the user the approval and answers
     * `{"approved": …, "remember_decision": …}`.
     */
    fun approval(approve: suspend (ApprovalRequest) -> ApprovalAnswer): ClientTool = object : ClientTool {
        override suspend fun execute(parameters: Map<String, Any>): ClientToolResult = askApproval(parameters["action_type"] as? String, parameters["details"] as? String, approve).fold(
            onSuccess = { ClientToolResult.success(it) },
            onFailure = { ClientToolResult.failure(it.message.orEmpty()) },
        )
    }

    /** One `request_approval` call: the user's answer as the agent reads it, or why the request couldn't be shown. */
    suspend fun askApproval(type: String?, details: String?, approve: suspend (ApprovalRequest) -> ApprovalAnswer): Result<String> {
        val action = PermAction.of(type)
            ?: return Result.failure(IllegalArgumentException("Unknown action_type \"$type\". Use one of: ${PermAction.entries.joinToString { it.wire }}."))
        val answer = approve(ApprovalRequest(UUID.randomUUID().toString(), action, details.orEmpty()))
        return Result.success(JSONObject().put("approved", answer.approved).put("remember_decision", answer.remember).toString())
    }

    fun result(result: ToolResult): ClientToolResult {
        val text = when (val data = result.data) {
            null, JSONObject.NULL -> ""
            else -> data.toString()
        }
        return if (result.ok) ClientToolResult.success(text) else ClientToolResult.failure(text.ifBlank { "The action failed on the phone." })
    }

    /** The SDK's parsed parameters (Gson numbers, lists and maps) as the JSON input [com.hiron.ren.device.DeviceTools] reads. */
    fun json(parameters: Map<String, Any>): JSONObject = JSONObject().apply { parameters.forEach { (k, v) -> put(k, value(v)) } }

    private fun value(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Number -> v.toDouble().let { d -> if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) d.toLong() else d }
        is Boolean, is String -> v
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> put(k.toString(), value(x)) } }
        is Iterable<*> -> JSONArray().apply { v.forEach { put(value(it)) } }
        else -> v.toString()
    }

    /**
     * The agent's text without the expressive audio tags (`[warmly]`, `[clears throat]`) its voice performs, as the
     * ElevenLabs widget shows it (convai-widget-core `stripAudioTags`); markdown links like `[docs](url)` stay.
     */
    fun withoutAudioTags(text: String): String = AUDIO_TAG.replace(text, "").trim()

    private val AUDIO_TAG = Regex("""\[[\w\s]+\](?!\()\s*""")

    data class Spoken(val text: String, val startMs: Double, val seconds: Double)

    /** Words and their timing from an `audio_alignment` event (`chars`, `char_start_times_ms`, `char_durations_ms`). */
    fun words(alignment: Map<String, Any>): List<Spoken> {
        val inner = alignment["alignment"] as? Map<*, *> ?: alignment
        val chars = (inner["chars"] as? List<*>)?.map { it.toString() } ?: return emptyList()
        val starts = (inner["char_start_times_ms"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.0 } ?: return emptyList()
        val durations = (inner["char_durations_ms"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.0 } ?: return emptyList()
        val words = mutableListOf<Spoken>()
        var text = StringBuilder()
        var start = 0.0
        var length = 0.0
        for (i in chars.indices) {
            val c = chars[i]
            if (c.isBlank()) {
                if (text.isNotEmpty()) words += Spoken(text.toString(), start, length / 1000.0)
                text = StringBuilder()
                continue
            }
            if (text.isEmpty()) {
                start = starts.getOrElse(i) { 0.0 }
                length = 0.0
            }
            text.append(c)
            length += durations.getOrElse(i) { 0.0 }
        }
        if (text.isNotEmpty()) words += Spoken(text.toString(), start, length / 1000.0)
        return words
    }
}
