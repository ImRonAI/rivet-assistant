package com.hiron.rivet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hiron.rivet.core.RivetRuntime
import com.hiron.rivet.core.RobotCommand
import org.json.JSONObject

private const val ORIGIN = "https://appassets.androidplatform.net"
private const val PAGE = "$ORIGIN/assets/rivet/index.html"

/** A slot gives up on a renderer that dies this many times and offers Retry instead. */
internal const val MAX_ROBOT_CRASHES = 3

/** How long a loaded page has to say `ready` before the slot says it didn't start. */
private const val START_TIMEOUT_MS = 4_000L

/** How the page frames Rivet; [wire] is the `window.rivet.focus` mode it sends. */
enum class RobotFocus(val wire: String) {
    /** A full-height stage (Home with a conversation). */
    Stage("stage"),

    /** A short card or band (Onboarding, the mini-stage). */
    Card("card"),

    /** The upper part of the stage, with the greeting under Rivet's feet (Home at rest). */
    Greet("greet"),
}

/**
 * The robot, on its own page in this slot; the page replays Rivet's state once it loads. [focus] frames the camera.
 * A renderer that dies brings a fresh page, up to [MAX_ROBOT_CRASHES] times; a page that fails says why, with Retry.
 */
@Composable
fun RobotView(runtime: RivetRuntime, modifier: Modifier = Modifier, focus: RobotFocus = RobotFocus.Stage) {
    val context = LocalContext.current
    val dark = LocalPalette.current.dark
    var generation by remember { mutableIntStateOf(0) }
    var crashes by remember { mutableIntStateOf(0) }
    Box(modifier) {
        if (crashes >= MAX_ROBOT_CRASHES) {
            RobotProblem(CRASHING, Modifier.align(Alignment.TopCenter)) {
                crashes = 0
                generation++
            }
        } else {
            key(generation) {
                val host = remember {
                    RobotHost(context, runtime, dark, focus) {
                        crashes++
                        generation++
                    }
                }
                LaunchedEffect(host) { runtime.robot.collect(host::run) }
                LaunchedEffect(host, dark) { host.theme(dark) }
                LaunchedEffect(host, focus) { host.focus(focus) }
                DisposableEffect(host) { onDispose { host.destroy() } }
                AndroidView(factory = { host.webView }, modifier = Modifier.fillMaxSize())
                host.problem?.let { RobotProblem(it, Modifier.align(Alignment.TopCenter)) { generation++ } }
            }
        }
    }
}

private const val CRASHING = "Rivet's robot keeps crashing on this phone."

/** Why the robot isn't showing, in one plain sentence, with a key that loads it again. */
@Composable
private fun RobotProblem(text: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth().padding(8.dp).background(p.raised, shape).border(1.dp, p.edgeSoft, shape)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Tone.Amber.color(), modifier = Modifier.size(16.dp))
        Text(text, Modifier.weight(1f), style = Type.cardBody, color = p.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextKey("Retry", onRetry)
    }
}

/**
 * Owns one WebView. Pages load only from the app's assets (no network, no file access) and talk back only
 * through the `RivetHost` listener for the asset origin. Commands sent before the page says `ready` are
 * dropped in favour of a full state replay once it does. [problem] says why the page isn't showing, if it isn't.
 */
internal class RobotHost(
    context: Context,
    private val runtime: RivetRuntime,
    private var dark: Boolean,
    private var focus: RobotFocus,
    private val onCrash: () -> Unit,
) {
    private var ready = false
    private var destroyed = false
    private var lastError: String? = null

    var problem: String? by mutableStateOf(null)
        private set

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.mediaPlaybackRequiresUserGesture = true
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "utf-8", 404, "blocked", null, null)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) problem = "Rivet's robot didn't load (error ${errorResponse.statusCode})."
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The renderer died (OOM or crash): this WebView can't be used again, so it goes, and the slot builds a fresh one.
                destroy()
                onCrash()
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // Old WebViews without the message listener never hear "ready"; the bridge has run by now.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) onMessage("""{"type":"ready"}""")
                view.postDelayed({ if (!ready && !destroyed) problem = notStarted() }, START_TIMEOUT_MS)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) lastError = "${message.message()} (line ${message.lineNumber()})"
                return false
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "RivetHost", setOf(ORIGIN)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) onMessage(message.data.orEmpty())
            }
        }
        loadUrl(PAGE)
    }

    fun run(command: RobotCommand) {
        if (ready) webView.evaluateJavascript(command.script(), null)
    }

    fun theme(dark: Boolean) {
        this.dark = dark
        run(RobotCommand("theme", listOf(if (dark) "dark" else "light")))
    }

    fun focus(mode: RobotFocus) {
        focus = mode
        run(RobotCommand("focus", listOf(mode.wire)))
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        ready = false
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun notStarted(): String =
        lastError?.let { "Rivet's robot didn't start: $it" } ?: "Rivet's robot didn't start on this phone."

    private fun onMessage(data: String) {
        val type = runCatching { JSONObject(data).optString("type") }.getOrDefault("")
        if (type != "ready" || destroyed) return
        ready = true
        problem = null
        theme(dark)
        focus(focus)
        runtime.robotSnapshot().forEach(::run)
    }
}
