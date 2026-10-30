package com.hiron.emrys.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * YouTube's embedded player loaded straight from `https://www.youtube.com/embed/<id>`, identified by a Referer of
 * `https://<package name>` as YouTube's Required Minimum Functionality asks of apps without a local HTML page.
 */
object VideoEmbed {
    fun url(youtubeId: String): String = "https://www.youtube.com/embed/$youtubeId?playsinline=1"

    fun headers(packageName: String): Map<String, String> = mapOf("Referer" to "https://${packageName.lowercase()}")

    /** Opens [url] in YouTube or the browser: the way to play anything that doesn't play here. */
    fun external(url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** The one inline player alive in the app, by its card's key; starting another stops it. */
object InlinePlayer {
    var playing: String? by mutableStateOf(null)
}

/** Starts [url] outside Emrys; false when nothing on the phone can open it. */
fun openExternally(context: Context, url: String): Boolean = try {
    context.startActivity(VideoEmbed.external(url))
    true
} catch (e: ActivityNotFoundException) {
    false
}

/**
 * The inline player for [youtubeId] under the card [key]. It plays after a tap inside it (WebView's default
 * user-gesture rule), and is destroyed when the card leaves the screen, another player starts, or Emrys goes to
 * the background.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VideoPlayer(key: String, youtubeId: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    DisposableEffect(key) { onDispose { if (InlinePlayer.playing == key) InlinePlayer.playing = null } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (InlinePlayer.playing == key) InlinePlayer.playing = null }
    Box(modifier.background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
        // Under the player until its page draws: a breathing glacier dot and a quiet line.
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalDot(p.cy, live = true)
            Spacer(Modifier.width(10.dp))
            Text("Loading the player…", style = Type.labelLg, color = p.ink2)
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        // The player's renderer died: the card goes back to its thumbnail.
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            if (InlinePlayer.playing == key) InlinePlayer.playing = null
                            return true
                        }

                        // The player stays on its embed page; anything it links to opens in YouTube or the browser.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            if (!request.isForMainFrame || url.startsWith("https://www.youtube.com/embed/")) return false
                            openExternally(context, url)
                            return true
                        }
                    }
                    loadUrl(VideoEmbed.url(youtubeId), VideoEmbed.headers(context.packageName))
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
