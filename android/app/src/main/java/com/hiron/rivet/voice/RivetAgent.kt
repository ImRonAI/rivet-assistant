package com.hiron.rivet.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.hiron.rivet.core.Activity
import com.hiron.rivet.core.ApprovalRequest
import com.hiron.rivet.core.ChatMessage
import com.hiron.rivet.core.GatewayConfig
import com.hiron.rivet.core.RivetState
import com.hiron.rivet.core.RivetStore
import com.hiron.rivet.core.RobotCommand
import com.hiron.rivet.core.RobotDirector
import com.hiron.rivet.device.ToolResult
import io.elevenlabs.ClientTool
import io.elevenlabs.ClientToolResult
import io.elevenlabs.ConversationClient
import io.elevenlabs.ConversationConfig
import io.elevenlabs.ConversationSession
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class AgentMode(val wire: String) { Voice("voice"), Text("text") }

/** What the brain's `/v1/agent/session` issued: a conversation token for voice, a signed URL for text. */
data class AgentCredential(val mode: AgentMode, val agentId: String, val credential: String)

/**
 * Rivet's ElevenLabs agents (brain/src/rivet_brain/gateway.py `ELEVENLABS_AGENTS`): [start] a voice or text
 * session, [sendText], [switchMode], [stop]. The agents call the phone tools as client tools; anything the brain
 * would ask about first waits for the user's OK on the approval sheet here.
 */
class RivetAgent(
    private val context: Context,
    http: OkHttpClient,
    private val gateway: () -> GatewayConfig,
    private val run: suspend (String, JSONObject) -> ToolResult,
    private val approve: suspend (ApprovalRequest) -> Boolean,
    private val update: ((RivetState) -> RivetState) -> Unit,
    private val robot: (RobotCommand) -> Unit,
    private val scope: CoroutineScope,
) {
    private val http = http.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()
    private var session: ConversationSession? = null
    private var starting: Job? = null

    /** The mode of the session that is starting or running; null when there is none. */
    @Volatile
    var mode: AgentMode? = null
        private set

    fun start(mode: AgentMode, firstMessage: String? = null) {
        stop()
        this.mode = mode
        starting = scope.launch {
            try {
                if (mode == AgentMode.Voice && !hasMicrophone()) {
                    fail("Rivet needs the microphone for voice. Allow it in Settings › Permissions.")
                    return@launch
                }
                val credential = fetchAgentCredential(http, gateway(), mode)
                val started = withContext(Dispatchers.Main) { ConversationClient.startSession(config(credential), context) }
                session = started
                firstMessage?.let(started::sendUserMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Couldn't start the ElevenLabs agent: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun sendText(text: String) {
        say(fromUser = true, text)
        val live = session
        if (mode == AgentMode.Text && live != null) live.sendUserMessage(text) else start(AgentMode.Text, firstMessage = text)
    }

    fun switchMode(mode: AgentMode) {
        if (this.mode != mode) start(mode)
    }

    fun stop() {
        starting?.cancel()
        starting = null
        val ending = session
        session = null
        mode = null
        robot(RobotCommand("quiet", emptyList()))
        if (ending != null) scope.launch(Dispatchers.Main) { runCatching { ending.endSession() } }
        update { if (it.activity == Activity.Listening) it.copy(activity = Activity.Idle) else it }
    }

    private fun config(credential: AgentCredential): ConversationConfig {
        val voice = credential.mode == AgentMode.Voice
        return ConversationConfig(
            conversationToken = credential.credential.takeIf { voice },
            signedUrl = credential.credential.takeUnless { voice },
            textOnly = !voice,
            clientTools = AgentTools.build(::runOnPhone, approve),
            onModeChange = { mode ->
                if (mode == ConversationMode.LISTENING) robot(RobotCommand("quiet", emptyList()))
                activity(if (mode == ConversationMode.LISTENING) Activity.Listening else Activity.Idle)
            },
            onStatusChange = { status ->
                if (status == ConversationStatus.DISCONNECTED || status == ConversationStatus.ERROR) activity(Activity.Idle)
            },
            onAudioAlignment = { alignment -> scope.launch { mouth(alignment) } },
            onUserTranscriptEvent = { text, _ -> if (voice && text.isNotBlank()) say(fromUser = true, text) },
            onAgentResponseEvent = { text, _ -> if (text.isNotBlank()) say(fromUser = false, text) },
            onError = { code, message -> note("ElevenLabs error $code${message?.let { ": $it" }.orEmpty()}") },
        )
    }

    private suspend fun runOnPhone(name: String, input: JSONObject): ToolResult {
        RobotDirector.forDeviceCall(name, input).forEach(robot)
        val result = withContext(Dispatchers.Default) { run(name, input) }
        note("$name: ${if (result.ok) "done" else result.data.toString().take(120)}", kind = "device")
        return result
    }

    private suspend fun mouth(alignment: Map<String, Any>) {
        var at = 0.0
        for (word in AgentTools.words(alignment)) {
            delay((word.startMs - at).toLong().coerceAtLeast(0))
            at = word.startMs
            robot(RobotCommand("mouth", listOf(word.text, word.seconds)))
        }
    }

    private fun hasMicrophone() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun say(fromUser: Boolean, text: String) = update {
        val message = ChatMessage("agent:" + UUID.randomUUID().toString().take(12), fromUser, text)
        it.copy(messages = it.messages + message, activity = if (fromUser) Activity.Thinking else Activity.Done)
    }

    private fun activity(activity: Activity) = update { it.copy(activity = activity) }

    private fun note(text: String, kind: String = "agent") = update { RivetStore.note(it, System.currentTimeMillis(), kind, text) }

    private fun fail(error: String) {
        mode = null
        update { RivetStore.note(it.copy(lastError = error, activity = Activity.Failed), System.currentTimeMillis(), "error", error) }
    }
}

/** Asks the brain for one session's credential; the ElevenLabs API key never reaches the phone. */
internal suspend fun fetchAgentCredential(http: OkHttpClient, config: GatewayConfig, mode: AgentMode): AgentCredential =
    withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${config.baseUrl()}/v1/agent/session?mode=${mode.wire}")
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrDefault(JSONObject())
            check(response.isSuccessful) { body.optString("error").ifBlank { "the brain answered ${response.code}" } }
            val field = if (mode == AgentMode.Voice) "conversation_token" else "signed_url"
            val credential = body.optString(field)
            check(credential.isNotBlank()) { "the brain sent no $field" }
            AgentCredential(mode, body.optString("agent_id"), credential)
        }
    }

/** The phone tools as ElevenLabs client tools, gated the way the brain gates them (device_tools.py `SPECS`). */
object AgentTools {
    /** Every phone tool the agents call; `call_answering_number` stays the brain's. */
    val NAMES = listOf(
        "sms_send", "sms_list", "contacts_search", "calls_recent", "phone_call", "calendar_list", "calendar_create",
        "notifications_list", "notification_reply", "reminder_create", "reminders_list", "reminder_cancel", "email_draft",
        "apps_list", "app_open", "device_status", "screen_read", "screen_act",
    )
    const val LANE = "agent"

    /** The SDK gives a client tool 30 seconds in all; the action itself needs the rest. */
    const val APPROVAL_TIMEOUT_MS = 20_000L
    const val DECLINED = "The user declined, so it was not done."
    private val HIGH_TIERS = setOf("destructive", "purchase", "unknown")

    fun build(
        run: suspend (String, JSONObject) -> ToolResult,
        approve: suspend (ApprovalRequest) -> Boolean,
    ): Map<String, ClientTool> = NAMES.associateWith { name ->
        object : ClientTool {
            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult = try {
                val input = json(parameters)
                val ask = request(name, input)
                if (ask != null && !approve(ask)) ClientToolResult.failure(DECLINED) else result(run(name, input))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ClientToolResult.failure("This action failed on the phone: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** The approval this call needs before it runs, or null when the brain would run it without asking. */
    fun request(name: String, input: JSONObject): ApprovalRequest? {
        val (tier, summary) = tier(name, input)
        if (tier == "read") return null
        return ApprovalRequest(
            id = "agent:" + UUID.randomUUID().toString(),
            lane = LANE,
            turnId = "",
            tool = name,
            tier = tier,
            high = tier in HIGH_TIERS,
            summary = summary,
            preview = input.toString(),
            canRemember = false,
            untrustedTurn = false,
            operations = emptyList(),
            expiresAt = (System.currentTimeMillis() + APPROVAL_TIMEOUT_MS) / 1000.0,
        )
    }

    fun tier(name: String, input: JSONObject): Pair<String, String> = when (name) {
        "sms_send" -> "send" to input.optString("to").let { if (it.isBlank()) "Text" else "Text $it" }
        "phone_call" -> "send" to input.optString("number").let { if (it.isBlank()) "Call" else "Call $it" }
        "notification_reply" -> "send" to "Reply to a message"
        "calendar_create" -> "write" to "Add a calendar event"
        "screen_act" -> screenActTier(input)
        else -> "read" to name
    }

    // Word lists from brain/src/rivet_brain/device_tools.py; the phone and the brain must agree.
    val PURCHASE_WORDS = listOf(
        "pay", "buy", "purchase", "checkout", "check out", "place order", "order now", "subscribe", "transfer",
        "send money", "donate", "book now", "rent", "upgrade",
    )
    val DESTRUCTIVE_WORDS = listOf(
        "delete", "remove", "erase", "uninstall", "unsubscribe", "block", "report", "discard", "reset", "clear all",
        "clear data", "clear history", "sign out", "log out", "deactivate", "close account", "cancel subscription",
        "cancel order", "cancel booking", "cancel reservation", "cancel membership", "cancel plan", "cancel ride",
        "cancel trip", "cancel appointment",
    )
    val SEND_WORDS = listOf("send", "post", "reply", "share", "tweet", "publish", "comment", "submit", "invite", "call", "forward")
    val COMMIT_WORDS = listOf(
        "confirm", "ok", "yes", "accept", "allow", "agree", "done", "finish", "save", "apply", "sign", "approve",
        "grant", "continue to payment",
    )
    val NAVIGATION = setOf("back", "home", "recents", "notifications", "scroll_down", "scroll_up")

    /** device_tools.screen_act_tier: navigation and typing run freely; a tap asks when its label reads like a commit point. */
    fun screenActTier(input: JSONObject): Pair<String, String> {
        val action = if (input.isNull("action")) "" else input.optString("action")
        val label = (if (input.isNull("label")) "" else input.optString("label")).trim()
        if (action in NAVIGATION) return "read" to "Navigate: ${action.replace('_', ' ')}"
        if (action == "type") return "read" to if (label.isNotEmpty()) "Type into \"$label\"" else "Type into a field"
        if (action != "tap") return "unknown" to "Unrecognised app action '$action'"
        if (label.isEmpty()) return "unknown" to "Tap an unlabelled element"
        val lowered = label.lowercase()
        val summary = "Tap \"$label\" in the open app"
        return when {
            mentions(lowered, PURCHASE_WORDS) -> "purchase" to summary
            mentions(lowered, DESTRUCTIVE_WORDS) -> "destructive" to summary
            mentions(lowered, SEND_WORDS) -> "send" to summary
            mentions(lowered, COMMIT_WORDS) -> "write" to summary
            else -> "read" to summary
        }
    }

    private fun mentions(label: String, words: List<String>) =
        words.any { Regex("(?<![a-z])${Regex.escape(it)}(?![a-z])").containsMatchIn(label) }

    fun result(result: ToolResult): ClientToolResult {
        val text = when (val data = result.data) {
            null, JSONObject.NULL -> ""
            else -> data.toString()
        }
        return if (result.ok) ClientToolResult.success(text) else ClientToolResult.failure(text.ifBlank { "The action failed on the phone." })
    }

    /** The SDK's parsed parameters (Gson numbers, lists and maps) as the JSON input [com.hiron.rivet.device.DeviceTools] reads. */
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
