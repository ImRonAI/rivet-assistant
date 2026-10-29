package com.hiron.rivet.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.hiron.rivet.core.ImageItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShareTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun target(chooser: Intent): Intent {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        return IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
    }

    @Test
    fun anImageIsSharedAsThePictureItself() {
        val uri = Uri.parse("content://com.hiron.rivet.files/shared/rivet-image.jpeg")
        val send = target(Share.image(uri, "Muddy shoes"))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/*", send.type)
        assertEquals(uri, IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("Muddy shoes", send.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
    }

    @Test
    fun withoutTheImageItSharesTheLink() {
        val send = target(Share.text("Muddy shoes", "https://photos.example.com/1"))
        assertEquals("text/plain", send.type)
        assertEquals("Muddy shoes\nhttps://photos.example.com/1", send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun aDownloadedImageGoesThroughTheFileProvider() {
        // A 1x1 PNG.
        val png = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", 0)
        val images = object : Images {
            override suspend fun bitmap(url: String, widthPx: Int) = null
            override suspend fun download(url: String, to: File): Boolean {
                to.writeBytes(png)
                return true
            }
        }
        val item = ImageItem("Dot", "https://photos.example.com/1", "https://img/full", "https://img/thumb", 1, 1, null)
        runBlocking { Share.image(app, images, item) }
        val send = target(shadowOf(app).nextStartedActivity)
        assertEquals("image/*", send.type)
        val stream = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", stream.scheme)
        assertEquals("com.hiron.rivet.files", stream.authority)
        assertTrue(File(app.cacheDir, "shared/rivet-image.png").exists())

        val broken = object : Images {
            override suspend fun bitmap(url: String, widthPx: Int) = null
            override suspend fun download(url: String, to: File) = false
        }
        runBlocking { Share.image(app, broken, item) }
        assertEquals("text/plain", target(shadowOf(app).nextStartedActivity).type)
    }
}
