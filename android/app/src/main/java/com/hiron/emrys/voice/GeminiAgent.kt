package com.hiron.emrys.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.core.content.ContextCompat
import com.hiron.emrys.core.Activity
import com.hiron.emrys.core.ChatMessage
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.EmrysStore
import com.hiron.emrys.core.RobotCommand
import com.hiron.emrys.core.RobotDirector
import com.hiron.emrys.core.Settings
import com.hiron.emrys.core.VoiceTurn
import com.hiron.emrys.device.ScreenOperator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID

/** The Gemini text models; the default is the most capable, as on the ElevenLabs text agent. */
enum class GeminiTextModel(val id: String, val label: String) {
    Argon("gemini-4-argon", "Gemini 4 Argon"),
    Flash("gemini-3.8-flash", "Gemini 3.8 Flash"),
    FlashLite("gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite"),
}

/**
 * Emrys's Gemini agents, which run in Strands on the workspace VM (`deploy/vm/gemini/app.py`): a text session is the
 * Strands harness, a voice session a BidiAgent on Gemini Live with this phone's microphone and speaker ([GeminiAudio]).
 * Each opens a WebSocket with the user's id, their `perm_*` grants and the text model, and the agents reach the phone
 * tools, `request_approval` and the searches through the phone's MCP server. The chat is built from Strands' events
 * ([GeminiEvents]).
 */
class GeminiAgent(
    private val context: Context,
    private val settings: Settings,
    private val http: OkHttpClient,
    private val update: ((EmrysState) -> EmrysState) -> Unit,
    private val robot: (RobotCommand) -> Unit,
    private val scope: CoroutineScope,
) : Assistant {
    // Main thread only. Every start and stop moves [generation] on, so a stale callback can tell.
    private var generation = 0
    private var socket: WebSocket? = null
    private val audio = GeminiAudio(scope)

    @Volatile
    override var mode: AgentMode? = null
        private set

    override fun start(mode: AgentMode, firstMessage: String?) {
        stop()
        val gen = generation
        this.mode = mode
        update { it.copy(voice = mode == AgentMode.Voice) }
        if (mode == AgentMode.Voice && !hasMicrophone()) {
            fail(gen, EmrysAgent.NEEDS_MICROPHONE)
            return
        }
        val request = Request.Builder().url(url(mode)).header("Authorization", "Bearer ${settings.geminiToken}").build()
        // OkHttp holds messages sent before the connection opens and sends them once it does.
        socket = http.newWebSocket(request, listener(gen, mode)).also { live -> firstMessage?.let(live::send) }
    }

    override fun sendText(text: String): String {
        val id = said(text)
        val live = socket
        if (mode != AgentMode.Text || live == null || !live.send(text)) start(AgentMode.Text, firstMessage = text)
        return id
    }

    override fun switchMode(mode: AgentMode) {
        if (this.mode != mode) start(mode)
    }

    override fun stop() {
        generation++
        val ending = socket
        socket = null
        audio.stop()
        robot(RobotCommand("quiet", emptyList()))
        if (mode == AgentMode.Voice) robot(RobotDirector.voice(VoiceTurn.None))
        mode = null
        ending?.close(NORMAL_CLOSURE, null)
        update { it.copy(activity = if (it.activity == Activity.Listening) Activity.Idle else it.activity, voice = false) }
    }

    override fun hasMicrophone() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun url(mode: AgentMode): HttpUrl = SERVICE.toHttpUrl().newBuilder()
        .addPathSegment(if (mode == AgentMode.Voice) "voice" else "text")
        .addQueryParameter("conversation", UUID.randomUUID().toString())
        .addQueryParameter("model", settings.geminiTextModel.id)
        .addQueryParameter("user_id", settings.userId)
        .apply { settings.permissionVariables().forEach { (name, value) -> addQueryParameter(name, value) } }
        .build()

    private fun listener(gen: Int, mode: AgentMode) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (mode == AgentMode.Voice) onMain(gen) { listen(gen, webSocket) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (mode == AgentMode.Voice) sound(gen, event)
            onMain(gen) {
                if (event.optString("type") == "bidi_transcript_block" && event.optString("role") == "user") ScreenOperator.resume()
                update { GeminiEvents.apply(it, event) }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            onMain(gen) { stop() }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onMain(gen) {
                stop()
                failed(COULD_NOT_REACH)
            }
        }
    }

    /** The microphone goes to the agent once the voice session is open. */
    private fun listen(gen: Int, webSocket: WebSocket) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(gen, EmrysAgent.NEEDS_MICROPHONE)
            stop()
            return
        }
        audio.start(send = { webSocket.send(it.toByteString()) }, loudness = { level -> onMain(gen) { robot(RobotDirector.level(level)) } })
        robot(RobotDirector.voice(VoiceTurn.Listening))
        update { it.copy(activity = Activity.Listening) }
    }

    /** The voice: Emrys's audio plays as it arrives, and a barge-in drops what hasn't played. */
    private fun sound(gen: Int, event: JSONObject) {
        when (event.optString("type")) {
            "bidi_audio_delta" -> audio.play(Base64.decode(event.optString("audio"), Base64.DEFAULT))
            "bidi_audio_start" -> onMain(gen) {
                robot(RobotDirector.voice(VoiceTurn.Speaking))
                update { it.copy(activity = Activity.Idle) }
            }
            "bidi_barge_in", "bidi_response_stop" -> {
                if (event.optString("type") == "bidi_barge_in") audio.clear()
                onMain(gen) {
                    robot(RobotCommand("quiet", emptyList()))
                    robot(RobotDirector.voice(VoiceTurn.Listening))
                    update { it.copy(activity = Activity.Listening) }
                }
            }
            // `end_conversation` cancelled the agent.
            "bidi_connection_stop" -> onMain(gen) { stop() }
        }
    }

    private fun onMain(gen: Int, block: () -> Unit) {
        scope.launch(Dispatchers.Main) { if (gen == generation) block() }
    }

    /** Adds the user's typed message to the conversation; returns its id. */
    private fun said(text: String): String {
        // The user asking Emrys anything lifts a Stop they pressed on the app operator.
        ScreenOperator.resume()
        val message = ChatMessage("agent:" + UUID.randomUUID().toString().take(12), true, text)
        update { it.copy(messages = it.messages + message, activity = Activity.Thinking, lastError = null) }
        return message.id
    }

    private fun fail(gen: Int, error: String) {
        if (gen != generation) return
        mode = null
        failed(error)
    }

    private fun failed(error: String) =
        update { EmrysStore.note(it.copy(lastError = error, activity = Activity.Failed), System.currentTimeMillis(), "error", error) }

    companion object {
        /** The Gemini service on the workspace VM, behind Caddy's bearer token. */
        const val SERVICE = "https://rivet.ron-ai.dev/gemini"
        const val COULD_NOT_REACH = "Couldn't reach the Gemini agents. Check your connection and try again."
        private const val NORMAL_CLOSURE = 1000
    }
}
