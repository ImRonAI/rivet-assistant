package com.hiron.ren.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hiron.ren.core.Activity
import com.hiron.ren.core.ApprovalAnswer
import com.hiron.ren.core.ApprovalRequest
import com.hiron.ren.core.ChatMessage
import com.hiron.ren.core.RenState
import com.hiron.ren.core.RenStore
import com.hiron.ren.core.RobotCommand
import com.hiron.ren.core.RobotDirector
import com.hiron.ren.core.SearchQuery
import com.hiron.ren.core.SearchResults
import com.hiron.ren.core.Settings
import com.hiron.ren.core.VoiceTurn
import com.hiron.ren.device.ScreenOperator
import com.hiron.ren.device.ToolResult
import com.hiron.ren.mcp.PhoneMcpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Ren's Telnyx AI Assistants, through Telnyx's own client (`@telnyx/ai-agent-lib`) in a WebView
 * (`android/stage-ui/src/telnyx`): [start] a voice or typed conversation, [sendText], [switchMode], [stop]. Every
 * conversation starts with the user's id and their `perm_*` grants as `X-` headers, which Telnyx turns into dynamic
 * variables; the assistants call the phone tools, `request_approval` and the search tools as client-side tools. A typed
 * conversation is the client's chat mode: no microphone, and the assistant's voice isn't played. The chat is built from
 * what the client reports ([TelnyxEvents]).
 */
class TelnyxAgent(
    private val context: Context,
    private val settings: Settings,
    private val run: suspend (String, JSONObject) -> ToolResult,
    private val approve: suspend (ApprovalRequest) -> ApprovalAnswer,
    private val update: ((RenState) -> RenState) -> Unit,
    private val robot: (RobotCommand) -> Unit,
    private val scope: CoroutineScope,
    private val search: suspend (SearchQuery) -> Result<SearchResults>,
) : Assistant {
    // Main thread only. Every start and stop moves [generation] on, so a stale event can tell; stop cancels [calls].
    private var generation = 0
    private var page: TelnyxPage? = null
    private val calls = mutableMapOf<String, Job>()

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
        val assistant = settings.telnyxAssistant(mode)
        if (assistant.isBlank()) {
            fail(gen, NO_ASSISTANT)
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            fail(gen, OLD_WEBVIEW)
            return
        }
        val options = JSONObject()
            .put("session", gen)
            .put("agentId", assistant)
            .put("chat", mode == AgentMode.Text)
            .put("headers", headers())
            .put("tools", JSONArray(TOOLS))
            .put("first", firstMessage ?: JSONObject.NULL)
        val host = page ?: TelnyxPage(context.applicationContext, ::hasMicrophone) { event -> heard(event) }.also { page = it }
        host.call("window.renTelnyx.start($options)")
    }

    override fun sendText(text: String): String {
        val id = said(text)
        val live = page
        if (mode != AgentMode.Text || live == null) start(AgentMode.Text, firstMessage = text)
        else live.call("window.renTelnyx.send(${JSONObject.quote(text)})")
        return id
    }

    override fun switchMode(mode: AgentMode) {
        if (this.mode != mode) start(mode)
    }

    override fun stop() {
        generation++
        calls.values.forEach { it.cancel() }
        calls.clear()
        page?.call("window.renTelnyx.stop()")
        robot(RobotCommand("quiet", emptyList()))
        if (mode == AgentMode.Voice) robot(RobotDirector.voice(VoiceTurn.None))
        mode = null
        update { it.copy(activity = if (it.activity == Activity.Listening) Activity.Idle else it.activity, voice = false) }
    }

    override fun hasMicrophone() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** `user_id` and the `perm_*` grants; Telnyx maps `X-Perm-Use-Apps` to `{{perm_use_apps}}`. */
    private fun headers(): JSONArray = JSONArray().apply {
        (settings.permissionVariables() + ("user_id" to settings.userId)).forEach { (name, value) ->
            put(JSONObject().put("name", "X-" + name.replace('_', '-')).put("value", value))
        }
    }

    /** What the page reports, for the conversation that is running; an event from an earlier one is dropped. */
    private fun heard(event: JSONObject) {
        if (event.optString("type") == "gone") page = null
        else if (event.optInt("session", -1) != generation) return
        val current = mode ?: return
        val voice = current == AgentMode.Voice
        when (event.optString("type")) {
            "tool" -> call(generation, event.optString("id"), event.optString("name"), event.optString("arguments"))
            // Telnyx answered the call itself (the tool's Portal timeout, or the call ending): the phone stops, and an
            // open approval is withdrawn.
            "dropped" -> {
                val id = event.optString("id")
                calls.remove(id)?.cancel()
                note("Telnyx dropped the tool call $id: ${event.optString("reason")}")
                update { TelnyxEvents.answered(it, id, ok = false) }
            }
            "ended" -> stop()
            "error", "gone" -> {
                note("Telnyx error: ${event.optString("message", "the page stopped")}")
                stop()
                failed(COULD_NOT_REACH)
            }
            else -> {
                if (event.optString("type") == "user") ScreenOperator.resume()
                if (voice) turn(event)
                update { TelnyxEvents.apply(it, event, voice) }
            }
        }
    }

    private fun turn(event: JSONObject) {
        if (event.optString("type") != "state") return
        when (event.optString("state")) {
            "speaking" -> robot(RobotDirector.voice(VoiceTurn.Speaking))
            "listening" -> {
                robot(RobotCommand("quiet", emptyList()))
                robot(RobotDirector.voice(VoiceTurn.Listening))
            }
        }
    }

    /** One client-side tool call: the phone runs it and the page hands the answer back to the assistant. */
    private fun call(gen: Int, id: String, name: String, arguments: String) {
        update { TelnyxEvents.called(it, id, name) }
        calls[id] = scope.launch(Dispatchers.Main) {
            val input = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrDefault(JSONObject())
            val answer = withContext(Dispatchers.Default) { answer(name, input) }
            if (gen != generation) return@launch
            calls.remove(id)
            update { TelnyxEvents.answered(it, id, answer.isSuccess) }
            val output = answer.getOrElse { JSONObject().put("error", it.message.orEmpty()).toString() }
            page?.call("window.renTelnyx.result(${JSONObject.quote(id)}, ${JSONObject.quote(output)})")
        }
    }

    private suspend fun answer(name: String, input: JSONObject): Result<String> = try {
        handle(name, input)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(IllegalStateException("This action failed on the phone: ${e.message ?: e.javaClass.simpleName}"))
    }

    private suspend fun handle(name: String, input: JSONObject): Result<String> = when (name) {
        AgentTools.REQUEST_APPROVAL -> AgentTools.askApproval(input.optString("action_type"), input.optString("details"), approve)
        in SearchTools.NAMES -> SearchTools.call(name, PhoneMcpServer.parameters(input), search, update)
        else -> {
            val result = run(name, input)
            note("$name: ${if (result.ok) "done" else result.data.toString().take(120)}", kind = "device")
            val text = when (val data = result.data) {
                null, JSONObject.NULL -> ""
                else -> data.toString()
            }
            if (result.ok) Result.success(text) else Result.failure(IllegalStateException(text.ifBlank { "The action failed on the phone." }))
        }
    }

    /** Adds the user's typed message to the conversation; returns its id. */
    private fun said(text: String): String {
        // The user asking Ren anything lifts a Stop they pressed on the app operator.
        ScreenOperator.resume()
        val message = ChatMessage("agent:" + UUID.randomUUID().toString().take(12), true, text)
        update { it.copy(messages = it.messages + message, activity = Activity.Thinking, lastError = null) }
        return message.id
    }

    private fun note(text: String, kind: String = "agent") = update { RenStore.note(it, System.currentTimeMillis(), kind, text) }

    private fun fail(gen: Int, error: String) {
        if (gen != generation) return
        mode = null
        failed(error)
    }

    private fun failed(error: String) =
        update { RenStore.note(it.copy(lastError = error, activity = Activity.Failed), System.currentTimeMillis(), "error", error) }

    companion object {
        /** Every client-side tool the assistants call: the phone tools, `request_approval` and the searches. */
        val TOOLS = AgentTools.NAMES + AgentTools.REQUEST_APPROVAL + SearchTools.NAMES.keys

        const val NO_ASSISTANT = "No Telnyx assistant is set for this. Choose another provider in Settings › Agents."
        const val OLD_WEBVIEW = "Telnyx needs a newer Android System WebView. Update it from the Play Store."
        const val COULD_NOT_REACH = "Couldn't reach the Telnyx assistant. Check your connection and try again."
    }
}

/**
 * The WebView that runs Telnyx's client: the page loads from the app's assets, connects only to Telnyx, and reports
 * through the `RenTelnyx` web message listener on the main thread. It gets the microphone only while the app has it.
 */
private class TelnyxPage(context: Context, private val microphone: () -> Boolean, private val onEvent: (JSONObject) -> Unit) {
    private var ready = false
    private val pending = mutableListOf<String>()

    @SuppressLint("SetJavaScriptEnabled")
    private val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // The assistant's voice plays as soon as the call is up.
        settings.mediaPlaybackRequiresUserGesture = false
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            // A WebView whose page process is gone can't be used again; the next conversation makes a new one.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                ready = false
                view.destroy()
                onEvent(JSONObject().put("type", "gone"))
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val mic = PermissionRequest.RESOURCE_AUDIO_CAPTURE
                if (request.origin.host == HOST && request.resources.contentEquals(arrayOf(mic)) && microphone()) request.grant(arrayOf(mic))
                else request.deny()
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "RenTelnyx", setOf(ORIGIN)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) received(message.data.orEmpty())
            }
        }
        loadUrl(PAGE)
    }

    /** Runs [script] on the page, once it is ready. */
    fun call(script: String) {
        if (ready) webView.evaluateJavascript(script, null) else pending += script
    }

    private fun received(data: String) {
        val event = runCatching { JSONObject(data) }.getOrNull() ?: return
        if (event.optString("type") != "ready") {
            onEvent(event)
            return
        }
        ready = true
        pending.forEach { webView.evaluateJavascript(it, null) }
        pending.clear()
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        const val PAGE = "$ORIGIN/assets/ren/telnyx/index.html"
    }
}
