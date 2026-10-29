package com.hiron.rivet.ui

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoPlayerTest {
    @Test
    fun theEmbedIsYouTubesWithTheAppAsReferer() {
        assertEquals("https://www.youtube.com/embed/abc_DEF-123?playsinline=1", VideoEmbed.url("abc_DEF-123"))
        assertEquals(mapOf("Referer" to "https://com.hiron.rivet"), VideoEmbed.headers("com.hiron.rivet"))
    }

    @Test
    fun videosThatDontPlayHereOpenOutside() {
        val intent = VideoEmbed.external("https://vimeo.com/1")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://vimeo.com/1", intent.dataString)
    }
}
