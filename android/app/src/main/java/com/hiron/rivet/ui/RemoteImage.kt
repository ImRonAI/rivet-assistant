package com.hiron.rivet.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fetches result images. Only `https` images load. */
interface Images {
    /** [url] decoded for about [widthPx] pixels across, or null when it can't be had. */
    suspend fun bitmap(url: String, widthPx: Int): ImageBitmap?

    /** Saves [url] to [to]; false when it can't be had. */
    suspend fun download(url: String, to: File): Boolean
}

/** [Images] over OkHttp, decoded with BitmapFactory at a sample size for the width, cached in memory (1/8 of the heap). */
class ImageLoader(http: OkHttpClient) : Images {
    private val http = http.newBuilder().readTimeout(20, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    override suspend fun bitmap(url: String, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$url@$widthPx"
        cache.get(key)?.let { return@withContext it.asImageBitmap() }
        val bytes = fetch(url) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (widthPx > 0 && bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        cache.put(key, bitmap)
        bitmap.asImageBitmap()
    }

    override suspend fun download(url: String, to: File): Boolean = withContext(Dispatchers.IO) {
        val bytes = fetch(url) ?: return@withContext false
        runCatching { to.writeBytes(bytes) }.isSuccess
    }

    private fun fetch(url: String): ByteArray? {
        if (!url.startsWith("https://")) return null
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null || body.contentLength() > MAX_BYTES) null else body.bytes().takeIf { it.size <= MAX_BYTES }
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val MAX_BYTES = 15L * 1024 * 1024
    }
}

private const val UNBOUNDED_WIDTH_PX = 1080

/** The app's [Images]; screens without one show the placeholders. */
val LocalImages = staticCompositionLocalOf<Images?> { null }

/** A result image: graphite while it loads, a broken-image glyph when it can't. */
@Composable
fun RemoteImage(
    url: String?,
    description: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    backdrop: Color = LocalPalette.current.tile,
) {
    val p = LocalPalette.current
    val images = LocalImages.current
    BoxWithConstraints(modifier.background(backdrop), contentAlignment = Alignment.Center) {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth.coerceAtLeast(1) else UNBOUNDED_WIDTH_PX
        // Null while loading; a value once it has loaded or failed.
        val loaded = produceState<Result<ImageBitmap>?>(null, url, images, width) {
            value = if (url == null || images == null) {
                Result.failure(IOException("no image"))
            } else {
                images.bitmap(url, width)?.let { Result.success(it) } ?: Result.failure(IOException("unavailable"))
            }
        }.value
        val bitmap = loaded?.getOrNull()
        when {
            bitmap != null -> Image(bitmap, description, Modifier.fillMaxSize(), contentScale = contentScale)
            loaded != null && url != null && images != null ->
                Icon(Icons.Outlined.BrokenImage, contentDescription = description, tint = p.ink2, modifier = Modifier.size(24.dp))
            else -> Box(Modifier.fillMaxSize())
        }
    }
}
