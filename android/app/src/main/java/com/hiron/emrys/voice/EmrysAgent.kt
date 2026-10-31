package com.hiron.emrys.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.hiron.emrys.core.Activity
import com.hiron.emrys.core.ApprovalAnswer
import com.hiron.emrys.core.ApprovalRequest
import com.hiron.emrys.core.ChatMessage
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.EmrysStore
import com.hiron.emrys.core.PermAction
import com.hiron.emrys.core.RobotCommand
import com.hiron.emrys.core.RobotDirector
import com.hiron.emrys.core.Settings
import com.hiron.emrys.core.VoiceTurn
import com.hiron.emrys.device.ToolResult
import io.elevenlabs.ClientTool
import io.elevenlabs.ClientToolResult
import io.elevenlabs.ConversationClient
import io.elevenlabs.ConversationConfig
import io.elevenlabs.ConversationSession
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
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

enum class AgentMode { Voice, Text }

/**
 * Emrys's ElevenLabs agents, connected straight through the ElevenLabs Android SDK: [start] a voice or text
 * session, [sendText], [switchMode], [stop]. Every session starts with the user's id and their `perm_*` grants
 * as dynamic variables; the agents call the phone tools and `request_approval` as client tools.
 */
class EmrysAgent(
    private val context: Context,
    private val settings: Settings,
    private val run: suspend (String, JSONObject) -> ToolResult,
    private val approve: suspend (ApprovalRequest) -> ApprovalAnswer,
    private val update: ((EmrysState) -> EmrysState) -> Unit,
    private val robot: (RobotCommand) -> Unit,
    private val scope: CoroutineScope,
    private val connect: suspend (ConversationConfig, Context) -> ConversationSession = { config, context -> ConversationClient.startSession(config, context) },
) {
    // Main thread only. Every start and stop moves [generation] on, so a stale start or callback can tell.
    private var generation = 0
    private var session: ConversationSession? = null
    private var starting: Job? = null
    private var queued = mutableListOf<String>()
    private var lips = Job()
    private var lastChunk: Job? = null

    /** The mode of the session that is starting or running; null when there is none. */
    @Volatile
    var mode: AgentMode? = null
        private set

    fun start(mode: AgentMode, firstMessage: String? = null) {
        stop()
        val gen = generation
        val messages = mutableListOf<String>().also { queued = it }
        firstMessage?.let(messages::add)
        this.mode = mode
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

    /** Sends [text] to the text agent, starting a text session when none is running; returns the message's id. */
    fun sendText(text: String): String {
        val id = say(fromUser = true, text)
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

    fun switchMode(mode: AgentMode) {
        if (this.mode != mode) start(mode)
    }

    fun stop() {
        generation++
        starting?.cancel()
        starting = null
        hush()
        val ending = session
        session = null
        if (mode == AgentMode.Voice) robot(RobotDirector.voice(VoiceTurn.None))
        mode = null
        ending?.let(::end)
        update { if (it.activity == Activity.Listening) it.copy(activity = Activity.Idle) else it }
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
            clientTools = AgentTools.build(::runOnPhone) + (AgentTools.REQUEST_APPROVAL to AgentTools.approval(approve)),
            onConnect = { connected.complete(Unit) },
            onDisconnect = {
                connected.completeExceptionally(IllegalStateException("the agent ended the session"))
                onMain(gen) { if (session != null) stop() }
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
            // The agent's audio level as the SDK reports it (every 50 ms in a voice session), for the stage's Orb.
            onAudioLevelChanged = { level -> if (voice) onMain(gen) { robot(RobotDirector.level(level)) } },
            onStatusChange = { status ->
                if (status == ConversationStatus.DISCONNECTED || status == ConversationStatus.ERROR) onMain(gen) { activity(Activity.Idle) }
            },
            onInterruption = { onMain(gen) { hush() } },
            onAudioAlignment = { alignment -> onMain(gen) { lipSync(alignment) } },
            onUserTranscriptEvent = { text, _ -> if (voice && text.isNotBlank()) say(fromUser = true, text) },
            onAgentResponseEvent = { text, _ -> if (text.isNotBlank()) say(fromUser = false, text) },
            onError = { code, message -> note("ElevenLabs error $code${message?.let { ": $it" }.orEmpty()}") },
        )
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

    fun hasMicrophone() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Adds a line to the conversation; returns its id. */
    private fun say(fromUser: Boolean, text: String): String {
        val message = ChatMessage("agent:" + UUID.randomUUID().toString().take(12), fromUser, text)
        update {
            it.copy(
                messages = it.messages + message,
                activity = if (fromUser) Activity.Thinking else Activity.Done,
                lastError = if (fromUser) null else it.lastError,
            )
        }
        return message.id
    }

    private fun activity(activity: Activity) = update { it.copy(activity = activity) }

    private fun note(text: String, kind: String = "agent") = update { EmrysStore.note(it, System.currentTimeMillis(), kind, text) }

    private fun fail(gen: Int, error: String) {
        if (gen != generation) return
        mode = null
        update { EmrysStore.note(it.copy(lastError = error, activity = Activity.Failed), System.currentTimeMillis(), "error", error) }
    }

    companion object {
        /** Emrys's public ElevenLabs agents. */
        val AGENTS = mapOf(
            AgentMode.Voice to "agent_2901m620nzwrev2r7ekqpydcxe25",
            AgentMode.Text to "agent_9601m623qa72ffs9cyy3ezc0p6tb",
        )

        const val NEEDS_MICROPHONE = "Emrys needs the microphone to talk. Tap the mic to allow it."
        const val COULD_NOT_START = "Couldn't reach the ElevenLabs agent. Check your connection and try again."
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
        override suspend fun execute(parameters: Map<String, Any>): ClientToolResult {
            val type = parameters["action_type"] as? String
            val action = PermAction.of(type)
                ?: return ClientToolResult.failure("Unknown action_type \"$type\". Use one of: ${PermAction.entries.joinToString { it.wire }}.")
            val details = (parameters["details"] as? String).orEmpty()
            val answer = approve(ApprovalRequest(UUID.randomUUID().toString(), action, details))
            return ClientToolResult.success(JSONObject().put("approved", answer.approved).put("remember_decision", answer.remember).toString())
        }
    }

    fun result(result: ToolResult): ClientToolResult {
        val text = when (val data = result.data) {
            null, JSONObject.NULL -> ""
            else -> data.toString()
        }
        return if (result.ok) ClientToolResult.success(text) else ClientToolResult.failure(text.ifBlank { "The action failed on the phone." })
    }

    /** The SDK's parsed parameters (Gson numbers, lists and maps) as the JSON input [com.hiron.emrys.device.DeviceTools] reads. */
    fun json(parameters: Map<String, Any>): JSONObject = JSONObject().apply { parameters.forEach { (k, v) -> put(k, value(v)) } }

    private fun value(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Number -> v.toDouble().let { d -> if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) d.toLong() else d }
        is Boolean, is String -> v
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> put(k.toString(), value(x)) } }
        is Iterable<*> -> JSONArray().apply { v.forEach { put(value(it)) } }
        else -> v.toString()
    }

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
