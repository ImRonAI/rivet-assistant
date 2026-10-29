package com.hiron.rivet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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

/** How the page frames Rivet; [wire] is the `window.rivet.focus` mode it sends. */
enum class RobotFocus(val wire: String) {
    /** A full-height stage (Home with a conversation). */
    Stage("stage"),

    /** A short card or band (Onboarding, the mini-stage). */
    Card("card"),

    /** The upper part of the stage, with the greeting under Rivet's feet (Home at rest). */
    Greet("greet"),
}

/** Identifies one slot's claim on the stage. */
@JvmInline
value class StageClaim(val id: Long)

/**
 * Which slot holds the robot page: the newest claim that hasn't been released. Snapshot state, so a change
 * recomposes the slots that read [owner].
 */
@Stable
class StageClaims {
    private val held = mutableStateListOf<StageClaim>()
    private var next = 0L

    val owner: StageClaim? get() = held.lastOrNull()

    fun claim(): StageClaim = StageClaim(next++).also { held.add(it) }

    /** Releasing a claim that isn't held does nothing. */
    fun release(claim: StageClaim) {
        held.remove(claim)
    }
}

/**
 * The activity's one robot page, lent to whichever slot is in front so Rivet keeps its moves, mood and mouth
 * across screens. Commands reach the page whether or not a slot shows it.
 */
@Stable
class RobotStage internal constructor(private val context: Context, private val runtime: RivetRuntime) {
    internal val claims = StageClaims()

    // Declared before [host]: its initializer reads them.
    private var dark = true
    private var focus = RobotFocus.Stage

    /** Replaced when the renderer dies; the owning slot keys its view on it. */
    internal var host: RobotHost by mutableStateOf(newHost())
        private set

    fun run(command: RobotCommand) = host.run(command)

    fun theme(dark: Boolean) {
        this.dark = dark
        host.theme(dark)
    }

    /** Frames Rivet for the slot that now holds the page, and replays its state there. */
    internal fun adopt(focus: RobotFocus) {
        this.focus = focus
        host.focus(focus)
        host.replay()
    }

    internal fun destroy() = host.destroy()

    private fun newHost(): RobotHost = RobotHost(context, runtime, dark, focus) {
        host.destroy()
        host = newHost()
    }
}

/** The stage the app provides; null outside the app (previews, screen tests), where a slot makes its own. */
val LocalRobotStage = staticCompositionLocalOf<RobotStage?> { null }

/** Builds the activity's stage, feeds it the runtime's robot commands, and destroys it with the composition. */
@Composable
fun rememberRobotStage(runtime: RivetRuntime): RobotStage {
    val context = LocalContext.current
    val dark = LocalPalette.current.dark
    val stage = remember(context, runtime) { RobotStage(context, runtime) }
    LaunchedEffect(stage) { runtime.robot.collect(stage::run) }
    LaunchedEffect(stage, dark) { stage.theme(dark) }
    DisposableEffect(stage) { onDispose { stage.destroy() } }
    return stage
}

/**
 * The robot, drawn in this slot whenever it holds the app's stage. [focus] frames the camera for this slot.
 */
@Composable
fun RobotView(runtime: RivetRuntime, modifier: Modifier = Modifier, focus: RobotFocus = RobotFocus.Stage) {
    val stage = LocalRobotStage.current ?: rememberRobotStage(runtime)
    // Claimed in an effect, so composing a slot writes no state.
    var claim by remember(stage) { mutableStateOf<StageClaim?>(null) }
    DisposableEffect(stage) {
        val held = stage.claims.claim()
        claim = held
        onDispose { stage.claims.release(held) }
    }
    val owned = claim != null && stage.claims.owner == claim
    LaunchedEffect(owned, focus) { if (owned) stage.adopt(focus) }
    if (owned) {
        // No onReset (a slot is never reused) and no onRelease: the stage, not the slot, destroys the WebView.
        key(stage.host) { AndroidView(factory = { stage.host.adopt() }, modifier = modifier) }
    }
}

/**
 * Owns the WebView. Pages load only from the app's assets (no network, no file access) and talk back only
 * through the `RivetHost` listener for the asset origin. Commands sent before the page says `ready` are
 * dropped in favour of a full state replay once it does.
 */
internal class RobotHost(
    context: Context,
    private val runtime: RivetRuntime,
    private var dark: Boolean,
    private var focus: RobotFocus,
    private val onCrash: () -> Unit,
) {
    private var ready = false

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
                // The renderer died (OOM or crash): the stage drops this WebView and builds a fresh one.
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

    fun focus(mode: RobotFocus) {
        focus = mode
        run(RobotCommand("focus", listOf(mode.wire)))
    }

    /** Sends the theme, framing and robot state again; before `ready` the handshake does this anyway. */
    fun replay() {
        if (!ready) return
        theme(dark)
        focus(focus)
        runtime.robotSnapshot().forEach(::run)
    }

    /** Hands the WebView to a new slot, taking it out of the one that showed it before. */
    fun adopt(): WebView = webView.also { (it.parent as? ViewGroup)?.removeView(it) }

    fun destroy() {
        ready = false
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun onMessage(data: String) {
        val type = runCatching { JSONObject(data).optString("type") }.getOrDefault("")
        if (type != "ready") return
        ready = true
        replay()
    }
}
