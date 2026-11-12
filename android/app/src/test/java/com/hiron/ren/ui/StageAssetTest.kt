package com.hiron.ren.ui

import android.content.Context
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.hiron.ren.core.RenRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The robot host's WebView: it fills its slot, and its page and the font the page asks for are in the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StageAssetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theRobotsWebViewFillsItsSlot() {
        val host = RobotHost(context, RenRuntime(context), dark = true, focus = RobotFocus.Stage) {}
        // A WebView that wraps its content gets a 0 px layout viewport, and the robot never draws.
        val params = checkNotNull(host.webView.layoutParams)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.height)
        host.destroy()
    }

    @Test
    fun theRobotsPageIsBundled() {
        val path = ROBOT_PAGE.substringAfter("/assets/")
        val page = context.assets.open(path).use { it.readBytes().decodeToString() }
        // The page loads its script and stylesheet from beside it, never from the network.
        assertTrue(page.contains("../assets/robot-"))
        assertTrue(!page.contains("http://") && !page.contains("https://"))
    }
}
