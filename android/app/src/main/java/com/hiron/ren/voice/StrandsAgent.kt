package com.hiron.ren.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.core.content.ContextCompat
import com.hiron.ren.core.Activity
import com.hiron.ren.core.ChatMessage
import com.hiron.ren.core.RenState
import com.hiron.ren.core.RenStore
import com.hiron.ren.core.RobotCommand
import com.hiron.ren.core.RobotDirector
import com.hiron.ren.core.Settings
import com.hiron.ren.core.VoiceTurn
import com.hiron.ren.device.ScreenOperator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** One model in a provider's live catalogue on the workspace VM. */
data class TextModel(val id: String, val ownedBy: String)

/** A provider's live models and the model the VM runs when none is chosen. */
data class Catalogue(val models: List<TextModel>, val default: String)

/**
 * Ren's Strands agents on the workspace VM (`deploy/vm/gemini/app.py`), with the text model on Gemini, NVIDIA NIM or
 * Mistral: a text session is the Strands harness, a voice session a BidiAgent on Gemini Live with this phone's
 * microphone and speaker ([GeminiAudio]). Each opens a WebSocket with the user's id, their `perm_*` grants, the
 * provider, text model and reasoning level, and the agents reach the phone tools, `request_approval` and the searches
 * through the phone's MCP server. The chat is built from Strands' events ([GeminiEvents]).
 */
class StrandsAgent(
    private val context: Context,
    private val settings: Settings,
    private val http: OkHttpClient,
    private val update: ((RenState) -> RenState) -> Unit,
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
            fail(gen, RenAgent.NEEDS_MICROPHONE)
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

    /** The [provider]'s live models, as the VM lists them from the provider itself. */
    suspend fun models(provider: Provider): Catalogue {
        val json = get(SERVICE.toHttpUrl().newBuilder().addPathSegment("models").addQueryParameter("provider", provider.strands).build())
        val models = json.getJSONArray("models")
        return Catalogue(
            (0 until models.length()).map { models.getJSONObject(it) }.map { TextModel(it.getString("id"), it.optString("owned_by")) },
            json.getString("default"),
        )
    }

    /** The reasoning levels [model] accepts, lowest first; empty when it has none to choose from. */
    suspend fun efforts(provider: Provider, model: String): List<String> {
        val url = SERVICE.toHttpUrl().newBuilder().addPathSegment("efforts")
            .addQueryParameter("provider", provider.strands).addQueryParameter("model", model).build()
        val efforts = get(url).getJSONArray("efforts")
        return (0 until efforts.length()).map(efforts::getString)
    }

    private suspend fun get(url: HttpUrl): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${settings.geminiToken}").build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("The workspace answered ${response.code}")
            JSONObject(body)
        }
    }

    private fun url(mode: AgentMode): HttpUrl {
        val provider = settings.provider
        return SERVICE.toHttpUrl().newBuilder()
            .addPathSegment(if (mode == AgentMode.Voice) "voice" else "text")
            .addQueryParameter("conversation", UUID.randomUUID().toString())
            .addQueryParameter("provider", provider.strands)
            .apply { settings.textModel(provider)?.let { addQueryParameter("model", it) } }
            .addQueryParameter("effort", settings.effort(provider))
            .addQueryParameter("user_id", settings.userId)
            .apply { settings.permissionVariables().forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
    }

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
            onMain(gen) {
                stop()
                // Anything but a normal close is the workspace ending the conversation on an error.
                if (code != NORMAL_CLOSE) failed(ENDED_BY_WORKSPACE + reason.ifBlank { "code $code" })
            }
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
            fail(gen, RenAgent.NEEDS_MICROPHONE)
            stop()
            return
        }
        audio.start(send = { webSocket.send(it.toByteString()) }, loudness = { level -> onMain(gen) { robot(RobotDirector.level(level)) } })
        robot(RobotDirector.voice(VoiceTurn.Listening))
        update { it.copy(activity = Activity.Listening) }
    }

    /** The voice: Ren's audio plays as it arrives, and a barge-in drops what hasn't played. */
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
        // The user asking Ren anything lifts a Stop they pressed on the app operator.
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
        update { RenStore.note(it.copy(lastError = error, activity = Activity.Failed), System.currentTimeMillis(), "error", error) }

    companion object {
        /** The Strands service on the workspace VM, behind Caddy's bearer token. */
        const val SERVICE = "https://rivet.ron-ai.dev/gemini"
        const val ENDED_BY_WORKSPACE = "The agents on the workspace ended the conversation: "
        private const val NORMAL_CLOSE = 1000
        const val COULD_NOT_REACH = "Couldn't reach the agents on the workspace. Check your connection and try again."
        private const val NORMAL_CLOSURE = 1000
    }
}
