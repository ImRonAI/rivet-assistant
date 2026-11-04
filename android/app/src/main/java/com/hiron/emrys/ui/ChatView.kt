package com.hiron.emrys.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hiron.emrys.core.Activity
import com.hiron.emrys.core.ChatMessage
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.ImageItem
import com.hiron.emrys.core.PlaceItem
import com.hiron.emrys.core.Reply
import com.hiron.emrys.core.SearchKind
import com.hiron.emrys.core.ToolState
import com.hiron.emrys.core.ToolStep
import com.hiron.emrys.core.VideoItem
import com.hiron.emrys.core.WebItem
import com.hiron.emrys.voice.SearchTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.math.roundToInt

const val CHAT_TAG = "chat"

/** The chat page in `assets/emrys/chat/` (android/stage-ui/src/chat) and what it is sent (its `model.ts`). */
object ChatPage {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val PAGE = "$ORIGIN/assets/emrys/chat/index.html"

    /** The image and video results a step shows: two rows of the page's grid. */
    private const val PICTURES = 6
    private const val VIDEOS = 4

    private val BUSY = setOf(Activity.Thinking, Activity.Searching, Activity.Working, Activity.Texting, Activity.Emailing, Activity.Waiting)

    /** The user's message is in and nothing of Emrys's reply has arrived yet. */
    fun working(state: EmrysState): Boolean = state.messages.lastOrNull()?.fromUser == true && state.activity in BUSY

    /** `window.emrysChat.show`'s argument. */
    fun payload(state: EmrysState): String = JSONObject()
        .put("messages", JSONArray(state.messages.map(::message)))
        .put("working", working(state))
        .put("error", state.lastError ?: JSONObject.NULL)
        .toString()

    private fun message(m: ChatMessage): JSONObject = when {
        m.system -> JSONObject().put("from", "app").put("id", m.id).put("text", m.text)
        m.fromUser -> JSONObject().put("from", "user").put("id", m.id).put("text", m.text)
        else -> JSONObject().put("from", "assistant").put("id", m.id)
            .put(
                "reasoning",
                m.reasoning?.takeIf { it.text.isNotBlank() }?.let {
                    JSONObject().put("text", it.text).put("streaming", it.streaming).put("seconds", it.seconds ?: JSONObject.NULL)
                } ?: JSONObject.NULL,
            )
            .put(
                "parts",
                JSONArray(
                    m.parts.mapNotNull { part ->
                        when (part) {
                            is Reply -> if (part.text.isBlank()) null else JSONObject().put("type", "text").put("id", part.id).put("text", part.text)
                            is ToolStep -> step(part)
                        }
                    },
                ),
            )
    }

    private fun step(step: ToolStep): JSONObject {
        val kind = SearchTools.NAMES[step.name]
        val results = step.search
        val items = results?.items.orEmpty()
        val links = when (kind) {
            SearchKind.Web, SearchKind.News, SearchKind.Context -> items.filterIsInstance<WebItem>().map { link(it.title, it.url, it.site ?: host(it.url)) }
            SearchKind.Places -> items.filterIsInstance<PlaceItem>().map { link(it.title, geo(it), it.title) }
            SearchKind.Answers -> results?.citations.orEmpty().map { link(it.snippet ?: it.url, it.url, host(it.url)) }
            else -> emptyList()
        }
        val pictures = items.filterIsInstance<ImageItem>().take(PICTURES).mapIndexed { i, image ->
            JSONObject().put("n", i + 1).put("title", image.title).put("thumbnail", image.thumbnail)
        }
        val videos = items.filterIsInstance<VideoItem>().take(VIDEOS).map { video ->
            JSONObject().put("title", video.title).put("url", video.url)
                .put("thumbnail", video.thumbnail ?: JSONObject.NULL).put("duration", video.duration ?: JSONObject.NULL)
        }
        val detail = when {
            step.state == ToolState.Failed -> "Didn't work"
            results != null -> (results.altered ?: results.query).takeIf { it.isNotBlank() }?.let { "“$it”" }
            else -> null
        }
        return JSONObject().put("type", "tool").put("id", step.id)
            .put("kind", kind?.wire ?: "tool")
            .put("label", label(step, kind))
            .put("detail", detail ?: JSONObject.NULL)
            .put(
                "state",
                when (step.state) {
                    ToolState.Running -> "running"
                    ToolState.Done -> "done"
                    ToolState.Failed -> "failed"
                },
            )
            .put("links", JSONArray(links))
            .put("pictures", JSONArray(pictures))
            .put("videos", JSONArray(videos))
    }

    /** A search says what it does while it runs and what it did once it's back; any other tool goes by its name. */
    internal fun label(step: ToolStep, kind: SearchKind?): String {
        val running = step.state == ToolState.Running
        return when (kind) {
            SearchKind.Web -> if (running) "Searching the web" else "Searched the web"
            SearchKind.News -> if (running) "Searching the news" else "Searched the news"
            SearchKind.Images -> if (running) "Searching images" else "Searched images"
            SearchKind.Videos -> if (running) "Searching videos" else "Searched videos"
            SearchKind.Places -> if (running) "Searching places" else "Searched places"
            SearchKind.Context -> if (running) "Reading pages" else "Read pages"
            SearchKind.Answers -> if (running) "Looking for an answer" else "Looked for an answer"
            null -> {
                // An MCP tool is named after its server first (`oubliai-telnyx_search`).
                val name = if (step.type == "mcp") step.name.substringAfter('_') else step.name
                name.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
            }
        }
    }

    private fun link(title: String, url: String, name: String) = JSONObject().put("title", title).put("url", url).put("name", name)

    private fun host(url: String): String = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: url

    /** Maps' `geo:` search at the place, labelled with its name, or by its address when it has no coordinates. */
    private fun geo(place: PlaceItem): String = place.at?.let { "geo:0,0?q=${it.lat},${it.lng}(${Uri.encode(place.title)})" }
        ?: "geo:0,0?q=${Uri.encode(place.address ?: place.title)}"

    /** The image result [n] (from 1) of the step [step]. */
    fun image(state: EmrysState, step: String, n: Int): ImageItem? = state.messages.asSequence()
        .flatMap { it.parts }
        .filterIsInstance<ToolStep>()
        .firstOrNull { it.id == step }
        ?.search?.items?.filterIsInstance<ImageItem>()?.getOrNull(n - 1)
}

/**
 * The conversation as ElevenLabs UI's Conversation, Message and Response, with AI Elements' Reasoning and Chain of
 * Thought for what Emrys thought and the tools it used, on the chat page. Its text follows the system font size.
 * Links open outside Emrys; an image result opens full size here.
 */
@Composable
fun ChatView(state: EmrysState, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(state)
    val zoom = (LocalDensity.current.fontScale * 100).roundToInt()
    val background = LocalPalette.current.bg.toArgb()
    var viewing by remember { mutableStateOf<Pair<ImageItem, Int>?>(null) }
    // A page whose renderer died comes back as a new one.
    var life by remember { mutableIntStateOf(0) }
    key(life) {
        val context = LocalContext.current
        val host = remember {
            ChatHost(context, background, onImage = { step, n -> ChatPage.image(latest, step, n)?.let { viewing = it to n } }, onGone = { life++ })
        }
        AndroidView(
            factory = { host.webView },
            update = { it.settings.textZoom = zoom },
            onRelease = { it.destroy() },
            modifier = modifier.testTag(CHAT_TAG),
        )
        LaunchedEffect(host) {
            // The newest chat, as often as the page can take it: built off the main thread, at most one a frame.
            snapshotFlow { latest }
                .map(ChatPage::payload)
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .conflate()
                .collect { payload ->
                    host.show(payload)
                    delay(FRAME_MS)
                }
        }
    }
    viewing?.let { (item, n) -> ImageViewer(item, n) { viewing = null } }
}

private const val FRAME_MS = 32L

/**
 * The chat page's WebView: the page and its chunks load from the app's assets, result pictures from the web, and it
 * talks back through the `EmrysChat` web message listener.
 */
private class ChatHost(context: Context, background: Int, private val onImage: (String, Int) -> Unit, onGone: () -> Unit) {
    private var ready = false
    private var pending: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context).apply {
        // A WebView that wraps its content gets a 0 px layout viewport, and the chat never draws.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(background)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                openExternally(view.context, request.url.toString())
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                ready = false
                onGone()
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) onMessage("""{"type":"ready"}""")
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "EmrysChat", setOf(ChatPage.ORIGIN)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) onMessage(message.data.orEmpty())
            }
        }
        loadUrl(ChatPage.PAGE)
    }

    fun show(payload: String) {
        pending = payload
        if (ready) webView.evaluateJavascript("window.emrysChat.show($payload)", null)
    }

    private fun onMessage(data: String) {
        val message = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (message.optString("type")) {
            "ready" -> {
                ready = true
                pending?.let(::show)
            }
            "image" -> onImage(message.optString("step"), message.optInt("n"))
        }
    }
}
