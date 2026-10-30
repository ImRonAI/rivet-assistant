package com.hiron.emrys.ui

import android.content.Context
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.hiron.emrys.core.EmrysRuntime
import com.hiron.emrys.core.NameLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/** The robot host's WebView: it fills its slot, and its one request outside the app's assets is the Orb's texture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StageAssetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theRobotsWebViewFillsItsSlot() {
        val host = RobotHost(context, EmrysRuntime(context, names = NameLookup { null }), dark = true, focus = RobotFocus.Stage) {}
        // A WebView that wraps its content gets a 0 px layout viewport, and the robot never draws.
        val params = checkNotNull(host.webView.layoutParams)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.height)
        host.destroy()
    }

    @Test
    fun theOrbsNoiseTextureComesFromTheBundledCopy() {
        val response = checkNotNull(stageAsset(context, "GET", ORB_NOISE_URL))
        assertEquals(200, response.statusCode)
        assertEquals("image/png", response.mimeType)
        // three.js loads it as a crossorigin="anonymous" image: WebGL needs the page's origin allowed.
        assertEquals(mapOf("Access-Control-Allow-Origin" to "https://appassets.androidplatform.net"), response.responseHeaders)
        val bytes = response.data.use { it.readBytes() }
        assertEquals(45_385, bytes.size)
        // assets/emrys/vendor/SOURCE.txt
        assertEquals(
            "6327c82c7700d6774794f7b06517e45bfedba50ef3b71abac280bd2595e98e6c",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun nothingElseOutsideTheAssetsIsAnswered() {
        assertNull(stageAsset(context, "POST", ORB_NOISE_URL))
        assertNull(stageAsset(context, "HEAD", ORB_NOISE_URL))
        assertNull(stageAsset(context, "GET", "$ORB_NOISE_URL?v=2"))
        assertNull(stageAsset(context, "GET", ORB_NOISE_URL.replace("https://", "http://")))
        assertNull(stageAsset(context, "GET", "https://storage.googleapis.com/eleven-public-cdn/images/other.png"))
        assertNull(stageAsset(context, "GET", "https://fonts.googleapis.com/css2?family=Geist"))
    }
}
