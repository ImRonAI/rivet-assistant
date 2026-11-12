package com.hiron.ren.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume

/** One tab as the chrome draws it. [url] is what the address bar holds: a web address or one of Ren's `ren://` pages. */
data class BrowserTab(
    val id: String,
    val url: String,
    val title: String = "",
    val loading: Boolean = false,
    /** How much of the page has loaded, 0 to 100. */
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val favicon: Bitmap? = null,
) {
    /** The tab is on Ren's home page, which the app draws itself. */
    val home: Boolean get() = url == Addresses.HOME

    /** What a tab list calls it: the page's title, or its address until it has one. */
    val label: String
        get() = when {
            home -> "Home"
            title.isNotBlank() -> title
            else -> Addresses.resting(url)
        }
}

data class BrowserState(val tabs: List<BrowserTab> = emptyList(), val active: String? = null) {
    val current: BrowserTab? get() = tabs.firstOrNull { it.id == active }
}

/** A file a page wants to save, waiting for the user's yes. */
data class DownloadRequest(val url: String, val name: String, val host: String, val mime: String?, val bytes: Long, val userAgent: String?)

/**
 * Ren's browser: its tabs, each a real WebView once it leaves home, and everything the chrome and Ren's browser tools
 * do to them. All of it runs on the main thread, as WebView requires.
 *
 * A tab opened from home keeps home under its first page, so Back from that page returns to it. Ren's own pages
 * (`ren://search`) are the app's bundled pages, loaded from its assets; only they can talk to the app, and what they
 * can ask for is [token], the workspace VM's bearer token their requests to Ren's search carry.
 */
class Browser(private val context: Context, private val store: BrowserStore, private val token: () -> String) {
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    /** A download a page asked for; the chrome shows it and answers with [download] or [dismissDownload]. */
    val downloadRequest = MutableStateFlow<DownloadRequest?>(null)

    /** A page's full-screen view (a video), when one is showing; the chrome lays it over everything. */
    val fullscreen = MutableStateFlow<View?>(null)
    private var leaveFullscreen: WebChromeClient.CustomViewCallback? = null

    /** Shows the system's file picker for a page's upload field; set by the activity while it is on screen. */
    var fileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null

    private val views = HashMap<String, WebView>()

    /** Tabs standing on home with a page still loaded under them, for Forward. */
    private val overHome = HashSet<String>()
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(context))
        .build()

    init {
        val saved = store.load()
        if (saved.tabs.isEmpty()) open() else _state.value = saved
    }

    /** Opens a tab on [url] (home by default) and returns its id. */
    fun open(url: String = Addresses.HOME, select: Boolean = true): String {
        val id = UUID.randomUUID().toString()
        change { it.copy(tabs = it.tabs + BrowserTab(id, Addresses.HOME), active = if (select) id else it.active) }
        if (url != Addresses.HOME) load(id, url)
        return id
    }

    fun select(id: String) = change { if (it.tabs.any { tab -> tab.id == id }) it.copy(active = id) else it }

    /** Closes a tab; the one after it (or before it) becomes active, and the last tab closing leaves a fresh home. */
    fun close(id: String) {
        val before = _state.value
        val index = before.tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        views.remove(id)?.let(::destroy)
        overHome.remove(id)
        val tabs = before.tabs.filterNot { it.id == id }
        val active = if (before.active != id) before.active else (tabs.getOrNull(index) ?: tabs.lastOrNull())?.id
        change { it.copy(tabs = tabs, active = active) }
        if (tabs.isEmpty()) open()
    }

    /** Takes the active tab to what was typed: an address loads and a phrase searches at [level]. */
    fun go(typed: String, level: SearchLevel) {
        val id = _state.value.active ?: return
        val url = Addresses.resolve(typed, level)
        if (url.isNotEmpty()) load(id, url)
    }

    /** Loads [url] in the tab [id]: home, one of Ren's pages, or a web address. */
    fun load(id: String, url: String) {
        if (url == Addresses.HOME) return home(id)
        overHome.remove(id)
        view(id).loadUrl(internal(url) ?: url)
        update(id) { it.copy(url = url, title = "", loading = true, progress = 0, favicon = null) }
    }

    fun back() {
        val tab = _state.value.current ?: return
        val view = views[tab.id]
        when {
            tab.home -> Unit
            view != null && view.canGoBack() -> view.goBack()
            else -> home(tab.id)
        }
    }

    fun forward() {
        val tab = _state.value.current ?: return
        val view = views[tab.id] ?: return
        if (tab.home) {
            if (overHome.remove(tab.id)) sync(tab.id, view)
        } else if (view.canGoForward()) view.goForward()
    }

    fun reload() {
        val tab = _state.value.current ?: return
        if (!tab.home) views[tab.id]?.reload()
    }

    fun stop() {
        _state.value.current?.let { views[it.id]?.stopLoading() }
    }

    /** The tab's WebView for the chrome to show; null while the tab is on home. A tab restored from the last launch loads here. */
    fun shown(id: String): WebView? {
        val tab = _state.value.tabs.firstOrNull { it.id == id }?.takeUnless { it.home } ?: return null
        return views[id] ?: view(id).also { it.loadUrl(internal(tab.url) ?: tab.url) }
    }

    /** Says yes to [downloadRequest]: the system's download manager saves the file to Downloads and shows its progress. */
    fun download() {
        val request = downloadRequest.value ?: return
        downloadRequest.value = null
        val job = DownloadManager.Request(Uri.parse(request.url))
            .setTitle(request.name)
            .setMimeType(request.mime)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, request.name)
        request.userAgent?.let { job.addRequestHeader("User-Agent", it) }
        CookieManager.getInstance().getCookie(request.url)?.let { job.addRequestHeader("Cookie", it) }
        context.getSystemService(DownloadManager::class.java).enqueue(job)
    }

    fun dismissDownload() {
        downloadRequest.value = null
    }

    fun exitFullscreen() {
        leaveFullscreen?.onCustomViewHidden()
        leaveFullscreen = null
        fullscreen.value = null
    }

    /**
     * Runs [script] in the tab's page and returns what it evaluates to, as the JSON the WebView hands back. Null when
     * the tab has no page (it is on home).
     */
    suspend fun evaluate(id: String, script: String): String? {
        val view = shown(id) ?: return null
        return suspendCancellableCoroutine { answer -> view.evaluateJavascript(script) { answer.resume(it) } }
    }

    /**
     * Taps the tab's page at a point of its viewport, [x] and [y] as fractions of its width and height. False when the
     * tab isn't on screen, where nothing can be touched.
     */
    fun tap(id: String, x: Float, y: Float): Boolean {
        val view = shown(id)?.takeIf { it.isAttachedToWindow && it.isShown && it.width > 0 && it.height > 0 } ?: return false
        val at = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x * view.width, y * view.height, 0)
        val up = MotionEvent.obtain(at, at + TAP_MS, MotionEvent.ACTION_UP, x * view.width, y * view.height, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        return true
    }

    /** The tab's page as it is drawn now, as a PNG; null when the tab has no page or it isn't laid out on screen. */
    fun capture(id: String): ByteArray? {
        val view = shown(id)?.takeIf { it.width > 0 && it.height > 0 } ?: return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Pauses or resumes every page's timers and media with the app. */
    fun visible(visible: Boolean) {
        views.values.forEach { if (visible) it.onResume() else it.onPause() }
    }

    private fun home(id: String) {
        if (views.containsKey(id)) overHome.add(id)
        views[id]?.stopLoading()
        update(id) { it.copy(url = Addresses.HOME, title = "", loading = false, progress = 0, favicon = null, canGoBack = false, canGoForward = overHome.contains(id)) }
    }

    private fun view(id: String): WebView = views.getOrPut(id) { create(id) }

    private fun change(transform: (BrowserState) -> BrowserState) {
        _state.update(transform)
        store.save(_state.value)
    }

    private fun update(id: String, transform: (BrowserTab) -> BrowserTab) = change { state ->
        state.copy(tabs = state.tabs.map { if (it.id == id) transform(it) else it })
    }

    /** Brings the tab's state in line with its WebView after a navigation. */
    private fun sync(id: String, view: WebView) {
        if (overHome.contains(id)) return
        val url = view.url?.let(::display) ?: return
        update(id) { it.copy(url = url, title = view.title.orEmpty().takeUnless { title -> title == view.url }.orEmpty(), canGoBack = true, canGoForward = view.canGoForward()) }
    }

    private fun destroy(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(id: String): WebView = WebView(MutableContextWrapper(context)).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.safeBrowsingEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = Pages(id)
        webChromeClient = Chrome(id)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Ren's search page asks for the workspace token its requests carry; no other origin reaches this listener.
            WebViewCompat.addWebMessageListener(this, "RenHost", setOf("https://$ORIGIN_HOST")) { _, message, _, isMainFrame, reply ->
                if (isMainFrame && message.data == "token") reply.postMessage(token())
            }
        }
        setDownloadListener { url, userAgent, disposition, mime, bytes ->
            val name = URLUtil.guessFileName(url, disposition, mime)
            downloadRequest.value = DownloadRequest(url, name, Uri.parse(url).host.orEmpty(), mime, bytes, userAgent)
            // A download leaves the page where it was.
            update(id) { it.copy(loading = false) }
        }
    }

    private inner class Pages(private val id: String) : WebViewClientCompat() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (request.url.host == ORIGIN_HOST) loader.shouldInterceptRequest(request.url) else null

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            return when (url.scheme) {
                "http", "https" -> {
                    // The earliest sign of a navigation: the tab is loading from the tap, not from the first response.
                    if (request.isForMainFrame) update(id) { it.copy(loading = true, progress = 0) }
                    false
                }
                "ren" -> {
                    if (request.isForMainFrame) load(id, url.toString())
                    true
                }
                // Another app's address (tel:, mailto:, a store page): only when the user tapped it, and only by asking the system.
                else -> {
                    if (request.isForMainFrame && request.hasGesture()) external(url)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (overHome.contains(id)) return
            update(id) { it.copy(url = display(url), loading = true, progress = 0, favicon = null) }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = sync(id, view)

        override fun onPageFinished(view: WebView, url: String) {
            sync(id, view)
            update(id) { it.copy(loading = false, progress = 100) }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            // The page's renderer died: this WebView can't be used again, so the tab gets a fresh one on its address.
            val url = _state.value.tabs.firstOrNull { it.id == id }?.url
            views.remove(id)?.let(::destroy)
            if (url != null && url != Addresses.HOME) load(id, url)
            return true
        }
    }

    private inner class Chrome(private val id: String) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = update(id) { it.copy(progress = newProgress, loading = it.loading && newProgress < 100) }

        override fun onReceivedTitle(view: WebView, title: String?) = sync(id, view)

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) = update(id) { it.copy(favicon = icon) }

        /** A link that opens a new window opens a new tab, when the user tapped it. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val tab = UUID.randomUUID().toString()
            change { it.copy(tabs = it.tabs + BrowserTab(tab, "about:blank", loading = true), active = tab) }
            transport.webView = view(tab)
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) = close(id)

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean =
            fileChooser?.invoke(filePathCallback, fileChooserParams) ?: false

        /** Pages don't get the camera, the microphone or the location: Ren has no screen that asks for them yet. */
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            leaveFullscreen = callback
            fullscreen.value = view
        }

        override fun onHideCustomView() {
            leaveFullscreen = null
            fullscreen.value = null
        }
    }

    private fun external(url: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No app on the phone opens this kind of address; the page stays where it is.
        }
    }

    companion object {
        private const val ORIGIN_HOST = "appassets.androidplatform.net"

        /** How long a tap's finger stays down. */
        private const val TAP_MS = 60L
        private const val PAGES = "https://$ORIGIN_HOST/assets/ren/web"

        /** The bundled page behind one of Ren's `ren://` addresses; null for a web address. */
        internal fun internal(url: String): String? =
            if (Addresses.searchQuery(url) != null) "$PAGES/search/index.html?${Uri.parse(url).encodedQuery.orEmpty()}" else null

        /** The address the bar shows for a loaded URL: Ren's pages by their `ren://` names. */
        internal fun display(url: String): String =
            if (url.startsWith("$PAGES/search/index.html")) "ren://search?${Uri.parse(url).encodedQuery.orEmpty()}" else url
    }
}

/** Where the open tabs are kept between launches: their addresses and titles, and which was active. */
class BrowserStore(private val read: () -> String?, private val write: (String) -> Unit) {
    fun load(): BrowserState {
        val json = runCatching { JSONObject(read() ?: return BrowserState()) }.getOrNull() ?: return BrowserState()
        val list = json.optJSONArray("tabs") ?: return BrowserState()
        val tabs = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { tab ->
            val id = tab.optString("id").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            // A saved page comes back with its address and title, and loads when its tab is first shown.
            BrowserTab(id, tab.optString("url").ifEmpty { Addresses.HOME }, title = tab.optString("title"), canGoBack = tab.optString("url") != Addresses.HOME)
        }
        return BrowserState(tabs, json.optString("active").takeIf { active -> tabs.any { it.id == active } } ?: tabs.firstOrNull()?.id)
    }

    fun save(state: BrowserState) {
        val tabs = JSONArray()
        state.tabs.forEach { tabs.put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title)) }
        write(JSONObject().put("tabs", tabs).put("active", state.active ?: "").toString())
    }
}
