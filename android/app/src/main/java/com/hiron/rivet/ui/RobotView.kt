package com.hiron.rivet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/**
 * The Rivet robot: the bundled page in a locked-down WebView, driven by the runtime's robot commands. [focus] frames
 * the camera: `"stage"` for a full-height stage, `"card"` for a short card.
 */
@Composable
fun RobotView(runtime: RivetRuntime, dark: Boolean, modifier: Modifier = Modifier, focus: String = "stage") {
    val context = LocalContext.current
    var generation by remember { mutableIntStateOf(0) }
    key(generation) {
        val host = remember { RobotHost(context, runtime) { generation++ } }
        LaunchedEffect(host) { runtime.robot.collect { host.run(it) } }
        LaunchedEffect(host, dark) { host.theme(dark) }
        LaunchedEffect(host, focus) { host.focus(focus) }
        DisposableEffect(host) { onDispose { host.destroy() } }
        AndroidView(factory = { host.webView }, modifier = modifier)
    }
}

/**
 * Owns the WebView. Pages load only from the app's assets (no network, no file access) and talk back only
 * through the `RivetHost` listener for the asset origin. Commands sent before the page says `ready` are
 * dropped in favour of a full state replay once it does.
 */
private class RobotHost(context: Context, private val runtime: RivetRuntime, private val onCrash: () -> Unit) {
    private var ready = false
    private var dark = false
    private var focus = "stage"

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

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The renderer died (OOM or crash): drop this WebView and let Compose build a fresh one.
                ready = false
                onCrash()
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // Old WebViews without the message listener never hear "ready"; the bridge has run by now.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) onMessage("""{"type":"ready"}""")
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

    fun focus(mode: String) {
        focus = mode
        run(RobotCommand("focus", listOf(mode)))
    }

    fun destroy() {
        ready = false
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun onMessage(data: String) {
        val type = runCatching { JSONObject(data).optString("type") }.getOrDefault("")
        if (type != "ready") return
        ready = true
        theme(dark)
        focus(focus)
        runtime.robotSnapshot().forEach(::run)
    }
}
