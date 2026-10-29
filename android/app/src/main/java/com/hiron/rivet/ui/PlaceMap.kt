package com.hiron.rivet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hiron.rivet.core.LatLng
import org.json.JSONArray
import org.json.JSONObject

/** One numbered pin. */
data class MapPin(val n: Int, val at: LatLng, val title: String)

/** The map page in `assets/map/` and what it is sent. */
object MapPage {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val PAGE = "$ORIGIN/assets/map/index.html"

    /** OpenFreeMap's public instance serves the style, tiles, fonts and sprites; nothing else loads from the network. */
    const val TILES = "https://tiles.openfreemap.org/"

    /** `window.rivetMap.show`'s argument; the page swaps each pin to MapLibre's `[lng, lat]`. */
    fun payload(pins: List<MapPin>, interactive: Boolean, selected: Int?): String = JSONObject()
        .put("places", JSONArray(pins.map { JSONObject().put("n", it.n).put("lat", it.at.lat).put("lng", it.at.lng).put("title", it.title) }))
        .put("interactive", interactive)
        .put("selected", selected ?: JSONObject.NULL)
        .toString()
}

/**
 * [pins] on an OpenFreeMap map (MapLibre GL in a WebView). Under it, numbered pins drawn on graphite show until the
 * map draws, and wherever WebGL doesn't. [interactive] false takes no gestures, so the map never fights a
 * scrolling list; [onSelect] hears a tapped pin.
 */
@Composable
fun PlaceMap(pins: List<MapPin>, interactive: Boolean, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val payload = MapPage.payload(pins, interactive, selected)
    val select by rememberUpdatedState(onSelect)
    Box(modifier) {
        PinBoard(pins, selected, Modifier.fillMaxSize())
        AndroidView(
            factory = { context -> MapHost(context) { select(it) }.webView },
            update = { (it.tag as? MapHost)?.show(payload) },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
        )
    }
}

/** The pins laid out by longitude and latitude on a graphite grid. */
@Composable
private fun PinBoard(pins: List<MapPin>, selected: Int?, modifier: Modifier) {
    val p = LocalPalette.current
    val placed = remember(pins) { layout(pins) }
    BoxWithConstraints(modifier.background(p.tile)) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 28.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(p.line, Offset(x, 0f), Offset(x, size.height))
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(p.line, Offset(0f, y), Offset(size.width, y))
                y += step
            }
        }
        val pad = 24.dp
        placed.forEach { (pin, fx, fy) ->
            val on = pin.n == selected
            Box(
                Modifier.offset(x = pad + (maxWidth - pad * 2) * fx - 13.dp, y = pad + (maxHeight - pad * 2) * fy - 13.dp)
                    .size(26.dp).clip(CircleShape).background(if (on) p.accent else p.high)
                    .border(2.dp, if (on) p.white else p.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("${pin.n}", style = Type.labelMd, color = if (on) p.accentInk else p.ink)
            }
        }
    }
}

/** Each pin with its position as fractions of the board, north up. */
private fun layout(pins: List<MapPin>): List<Triple<MapPin, Float, Float>> {
    if (pins.isEmpty()) return emptyList()
    val lats = pins.map { it.at.lat }
    val lngs = pins.map { it.at.lng }
    val (south, north) = lats.min() to lats.max()
    val (west, east) = lngs.min() to lngs.max()
    fun frac(v: Double, lo: Double, hi: Double) = if (hi - lo < 1e-9) 0.5f else ((v - lo) / (hi - lo)).toFloat()
    return pins.map { Triple(it, frac(it.at.lng, west, east), 1f - frac(it.at.lat, south, north)) }
}

/**
 * The map's WebView: its page loads from the app's assets, map data only from [MapPage.TILES], and it talks back
 * only through the `RivetMap` listener for the asset origin. Links (the attribution) open in the browser.
 */
private class MapHost(context: Context, private val onSelect: (Int) -> Unit) {
    private var ready = false
    private var pending: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context).apply {
        tag = this@MapHost
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                loader.shouldInterceptRequest(request.url)?.let { return it }
                return if (request.url.toString().startsWith(MapPage.TILES)) null else WebResourceResponse("text/plain", "utf-8", 404, "blocked", null, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                openExternally(view.context, request.url.toString())
                return true
            }

            // The map's renderer died: the drawn pins underneath stay.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                ready = false
                view.visibility = android.view.View.GONE
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) onMessage("""{"type":"ready"}""")
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "RivetMap", setOf(MapPage.ORIGIN)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) onMessage(message.data.orEmpty())
            }
        }
        loadUrl(MapPage.PAGE)
    }

    fun show(payload: String) {
        pending = payload
        if (ready) webView.evaluateJavascript("window.rivetMap.show($payload)", null)
    }

    private fun onMessage(data: String) {
        val message = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (message.optString("type")) {
            "ready" -> {
                ready = true
                pending?.let(::show)
            }
            "select" -> onSelect(message.optInt("n"))
        }
    }
}
