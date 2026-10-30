package com.hiron.emrys.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.hiron.emrys.core.ContextItem
import com.hiron.emrys.core.ImageItem
import com.hiron.emrys.core.SearchKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Sharing results through the Android Sharesheet: an image as the picture itself, anything else as its link. */
object Share {
    /** Under `cacheDir`, served by the FileProvider (`res/xml/file_paths.xml`); emptied before each share. */
    const val DIR = "shared"

    fun authority(context: Context) = "${context.packageName}.files"

    fun image(uri: Uri, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).setType("image/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(title, uri)
        return Intent.createChooser(send, title)
    }

    fun text(title: String, url: String): Intent {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "$title\n$url")
            .putExtra(Intent.EXTRA_TITLE, title)
        return Intent.createChooser(send, title)
    }

    /** Downloads [item]'s full image and shares it; when it can't be had, shares the title and link instead. */
    suspend fun image(context: Context, images: Images?, item: ImageItem) {
        val uri = images?.let { saved(context, it, item) }
        start(context, if (uri != null) image(uri, item.title) else text(item.title, item.url))
    }

    fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(if (context is Activity) intent else intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // Nothing on the phone can take it.
        }
    }

    private suspend fun saved(context: Context, images: Images, item: ImageItem): Uri? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "image")
        val ok = images.download(item.image, file) || images.download(item.thumbnail, file)
        val mime = if (ok) BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }.outMimeType else null
        if (mime == null) return@withContext null
        val named = File(dir, "emrys-image." + mime.substringAfter('/').substringBefore('+'))
        if (!file.renameTo(named)) return@withContext null
        FileProvider.getUriForFile(context, authority(context), named)
    }
}

/**
 * An image result full screen: pinch to zoom, Close and Open page across the top, and a caption over a soft noir
 * fade with its number and title in serif, its source and size in Geist, Share (the image itself, the porcelain
 * pill) and Add to Emrys. A tap on the image hides or shows the caption; Back closes it.
 */
@Composable
fun ImageViewer(item: ImageItem, n: Int, onAdd: (ContextItem) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val images = LocalImages.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { change, pan, _ ->
        scale = (scale * change).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    var panel by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { panel = true }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            RemoteImage(
                item.image, item.title,
                Modifier.fillMaxSize().transformable(zoom)
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { panel = !panel },
                contentScale = ContentScale.Fit,
                backdrop = Color.Black,
            )
            IconKey(Icons.Outlined.Close, "Close", onDismiss, Modifier.statusBarsPadding().padding(12.dp).align(Alignment.TopStart))
            Keycap(
                "Open page", { openExternally(context, item.url) }, Modifier.statusBarsPadding().padding(12.dp).align(Alignment.TopEnd),
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
            )
            AnimatedVisibility(
                panel,
                Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(Motion.BASE, easing = Motion.Emphasized)) { it } + fadeIn(),
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.32f to p.bg.copy(alpha = 0.86f), 1f to p.bg))
                        .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = p.ink2)) { append("$n.") }
                            append(" ${item.title}")
                        },
                        style = Type.quote.copy(fontSize = 26.sp, lineHeight = 31.sp), color = p.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(item.source, if (item.width != null && item.height != null) "${item.width} × ${item.height}" else null)
                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = Type.cardBody, color = p.ink2)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Keycap("Share", { scope.launch { Share.image(context, images, item) } }, tone = KeyTone.Accent, icon = Icons.Outlined.Share)
                        ContextItem.of(SearchKind.Images, item)?.let { added -> Keycap("Add to Emrys", { onAdd(added) }, icon = Icons.Outlined.Add) }
                    }
                }
            }
        }
    }
}
