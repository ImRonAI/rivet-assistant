package com.hiron.ren.app

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hiron.ren.MainActivity
import com.hiron.ren.core.ApprovalAnswer
import com.hiron.ren.core.ren
import com.hiron.ren.testing.textApproval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.hiron.ren.testing.shot
import com.hiron.ren.testing.windowRoots
import com.hiron.ren.ui.COMPOSER_TAG
import com.hiron.ren.ui.MAX_ROBOT_CRASHES
import com.hiron.ren.ui.MINI_STAGE_TAG
import com.hiron.ren.ui.ROBOT_PAGE
import com.hiron.ren.ui.STAGE_TAG
import com.hiron.ren.ui.TAB_TAG_PREFIX
import androidx.webkit.WebViewFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val CRASHING = "Ren's robot keeps crashing on this phone."
private const val NOT_LOADED = "Ren's robot didn't load (error 404)."

/** Drives the real app (MainActivity, runtime, screens) and saves a screenshot of every screen to build/screens for review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) = shot(compose, compose.activity, name)

    private fun until(timeoutMs: Long = 10_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(50)
            compose.waitForIdle()
        }
    }

    @Test
    fun anAgentsApprovalShowsTheSheetAndDenyAnswersIt() {
        compose.onNodeWithText("Set up").assertIsDisplayed()
        shot("00-onboarding")
        skipOnboarding()
        shot("01-home-start")

        // The text agent called request_approval: the sheet asks, and Deny goes back as the tool's result.
        val runtime = compose.activity.ren
        val request = textApproval()
        val answer = CompletableDeferred<ApprovalAnswer>()
        CoroutineScope(Dispatchers.Default).launch { answer.complete(runtime.approve(request)) }
        until { compose.onAllNodesWithTextCount(request.details) > 0 }
        shot("02-approval-sheet")
        compose.onNodeWithText("Deny").performClick()
        until { answer.isCompleted }
        assertEquals(ApprovalAnswer(false), runBlocking { answer.await() })
        assertTrue(runtime.state.value.approvals.isEmpty())

        tab("activity")
        until { compose.onAllNodesWithTextCount("Declined: ${request.details}") > 0 }
        shot("03-activity")

        tab("settings")
        shot("04-settings-top")
        compose.onNode(hasText("Turn on")).performScrollTo()
        shot("05-settings-call-answering")
        compose.onNode(hasText("Open Ren's app info")).performScrollTo().assertIsDisplayed()
        shot("06-settings-bottom")
    }

    @Test
    fun firstRunWalksEveryStep() {
        compose.onNodeWithText("Set up").performClick()
        compose.onNodeWithText("Save & continue").assertIsDisplayed()
        compose.onNodeWithText("Save & continue").performClick()
        compose.onNodeWithText("Skip for now").performClick()
        compose.onNodeWithText("Customize later in Settings").performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.ren.settings.onboarded)
    }

    /** Skips the power-on screens; the app then opens on Home. */
    private fun skipOnboarding() {
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.ren.settings.onboarded)
    }

    /** The slot showing Ren on the screen the app opens on after onboarding: Home's stage. */
    private fun landing(): Slot = Slot.Stage

    private fun tab(name: String) {
        compose.onNodeWithTag(TAB_TAG_PREFIX + name).performClick()
        compose.waitForIdle()
    }

    private fun webViews(): List<WebView> = windowRoots().flatMap(::webViewsIn)

    private fun webViewsIn(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViewsIn(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** A slot that can show the robot page. */
    private enum class Slot {
        /** The big stage: onboarding's or Home's. */
        Stage,

        /** The band under the app's header on Activity and Settings. */
        Band,
    }

    private fun boundsOf(tag: String): List<Rect> = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().map { it.boundsInWindow }

    private fun Rect.holds(inner: Rect): Boolean =
        inner.left >= left - 1f && inner.top >= top - 1f && inner.right <= right + 1f && inner.bottom <= bottom + 1f

    /**
     * The robot frame of each slot on screen. Every slot draws Ren in a [STAGE_TAG] frame; a band's frame sits
     * inside its [MINI_STAGE_TAG] band.
     */
    private fun slotFrames(): Map<Slot, Rect> {
        val bands = boundsOf(MINI_STAGE_TAG)
        val (inBands, stages) = boundsOf(STAGE_TAG).partition { frame -> bands.any { it.holds(frame) } }
        return buildMap {
            stages.singleOrNull()?.let { put(Slot.Stage, it) }
            inBands.firstOrNull()?.let { put(Slot.Band, it) }
        }
    }

    /** Where the page is drawn: the view Compose holds it in, since the stand-in WebView is never measured. */
    private fun WebView.drawnBounds(): Rect {
        val holder = parent as? View ?: error("the page is not in a slot")
        val at = IntArray(2).also(holder::getLocationInWindow)
        return Rect(at[0].toFloat(), at[1].toFloat(), (at[0] + holder.width).toFloat(), (at[1] + holder.height).toFloat())
    }

    /** Each of [slots] shows its own live robot page, drawn in its frame, and no other page is on screen. */
    private fun assertPages(step: String, vararg slots: Slot): Map<Slot, WebView> {
        compose.waitForIdle()
        val pages = webViews()
        val frames = slotFrames()
        assertEquals("robot pages after $step", slots.size, pages.size)
        return slots.toList().associateWith { slot ->
            val frame = frames[slot] ?: error("no $slot on screen after $step: $frames")
            val inSlot = pages.filter { page -> frame.holds(page.drawnBounds()) }
            val page = inSlot.singleOrNull() ?: error("${inSlot.size} pages in the $slot after $step: frames $frames")
            assertTrue("the page in the $slot is drawn after $step", page.drawnBounds().let { it.width > 0f && it.height > 0f })
            assertFalse("the page in the $slot is alive after $step", shadowOf(page).wasDestroyCalled())
            assertEquals(ROBOT_PAGE, shadowOf(page).lastLoadedUrl)
            page
        }
    }

    /** Robolectric's WebView has no message listener, so the host takes a page as ready once it finishes loading. */
    private fun ready(page: WebView) {
        compose.runOnUiThread { shadowOf(page).webViewClient.onPageFinished(page, ROBOT_PAGE) }
        compose.waitForIdle()
    }

    private fun crash(page: WebView) {
        compose.runOnUiThread { shadowOf(page).webViewClient.onRenderProcessGone(page, null) }
        compose.waitForIdle()
    }

    @Test
    fun everyScreenShowsTheRobot() {
        val onboarding = assertPages("launch", Slot.Stage).getValue(Slot.Stage)
        skipOnboarding()
        assertTrue("onboarding's page is destroyed", shadowOf(onboarding).wasDestroyCalled())
        assertPages("finishing onboarding", landing())

        tab("activity")
        val band = assertPages("Activity", Slot.Band).getValue(Slot.Band)
        compose.onNodeWithTag(MINI_STAGE_TAG).assertIsDisplayed()
        tab("settings")
        assertSame("the band keeps its page from Activity to Settings", band, assertPages("Settings", Slot.Band).getValue(Slot.Band))
        tab("ren")
        assertPages("Home", Slot.Stage)
        assertTrue("the band's page is destroyed", shadowOf(band).wasDestroyCalled())
        compose.onNodeWithTag(MINI_STAGE_TAG).assertDoesNotExist()
        tab("settings")
        assertPages("back to Settings", Slot.Band)

        tab("ren")
        assertPages("Home again", Slot.Stage)
        compose.onNodeWithTag(MINI_STAGE_TAG).assertDoesNotExist()
    }

    /** Taps a tab on a paused clock: the tap's state change is sent, and no frame runs until the test advances it. */
    private fun tapTabNow(name: String) {
        val key = compose.onNodeWithTag(TAB_TAG_PREFIX + name).fetchSemanticsNode()
        compose.runOnUiThread { key.config[SemanticsActions.OnClick].action?.invoke() }
        compose.waitForIdle()
    }

    @Test
    fun aQuickRoundTripLeavesOneRobotOnHome() {
        skipOnboarding()
        tab("ren")
        assertPages("Home", Slot.Stage)

        // Back to Home before the tab crossfade (220 ms) and the band's entrance finish.
        compose.mainClock.autoAdvance = false
        tapTabNow("settings")
        compose.mainClock.advanceTimeBy(100)
        tapTabNow("ren")
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.autoAdvance = true
        assertPages("tapping Ren mid-fade", Slot.Stage)
    }

    @Test
    fun aRendererCrashBringsAFreshPageToTheSlotShowingIt() {
        skipOnboarding()
        tab("settings")
        val page = assertPages("Settings", Slot.Band).getValue(Slot.Band)

        crash(page)
        assertTrue("the dead page is destroyed", shadowOf(page).wasDestroyCalled())
        val fresh = assertPages("the renderer crash", Slot.Band).getValue(Slot.Band)
        assertNotSame(page, fresh)

        // Commands wait for the fresh page; once it loads, Ren's state is replayed there.
        assertNull(shadowOf(fresh).lastEvaluatedJavascript)
        ready(fresh)
        assertEquals("""window.ren && window.ren.state("idle");""", shadowOf(fresh).lastEvaluatedJavascript)
    }

    @Test
    fun aRendererThatKeepsCrashingSaysSoAndRetries() {
        skipOnboarding()
        tab("ren")
        repeat(MAX_ROBOT_CRASHES) { crash(webViews().single()) }
        assertEquals("no page after $MAX_ROBOT_CRASHES crashes", emptyList<WebView>(), webViews())
        compose.onNodeWithText(CRASHING).assertIsDisplayed()
        shot("app-robot-crashing")

        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText(CRASHING).assertDoesNotExist()
        assertPages("Retry", Slot.Stage)
    }

    @Test
    fun aPageThatFailsToLoadSaysWhyAndRetries() {
        skipOnboarding()
        tab("ren")
        val page = assertPages("Home", Slot.Stage).getValue(Slot.Stage)
        val request = object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(ROBOT_PAGE)

            override fun isForMainFrame(): Boolean = true

            override fun isRedirect(): Boolean = false

            override fun hasGesture(): Boolean = false

            override fun getMethod(): String = "GET"

            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        val missing = WebResourceResponse("text/plain", "utf-8", 404, "blocked", null, null)
        compose.runOnUiThread { shadowOf(page).webViewClient.onReceivedHttpError(page, request, missing) }
        compose.onNodeWithText(NOT_LOADED).assertIsDisplayed()
        shot("app-robot-not-loaded")

        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText(NOT_LOADED).assertDoesNotExist()
        val fresh = assertPages("Retry", Slot.Stage).getValue(Slot.Stage)
        assertNotSame(page, fresh)
        assertTrue("the failed page is destroyed", shadowOf(page).wasDestroyCalled())
    }

    @Test
    fun miniStageOpensHome() {
        skipOnboarding()
        tab("activity")
        compose.onNodeWithTag(MINI_STAGE_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MINI_STAGE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAGE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TAG).assertIsDisplayed()
    }

    @Test
    fun commandsReachEveryRobot() {
        assertFalse(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        skipOnboarding()
        tab("settings")
        val band = assertPages("Settings", Slot.Band).getValue(Slot.Band)
        assertNull("commands wait for the page", shadowOf(band).lastEvaluatedJavascript)
        ready(band)
        assertEquals("""window.ren && window.ren.state("idle");""", shadowOf(band).lastEvaluatedJavascript)

        compose.activity.ren.listening(true)
        compose.waitForIdle()
        assertEquals("""window.ren && window.ren.state("listening");""", shadowOf(band).lastEvaluatedJavascript)

        compose.activity.ren.listening(false)
        compose.waitForIdle()
        assertEquals("""window.ren && window.ren.state("idle");""", shadowOf(band).lastEvaluatedJavascript)
    }

    private fun miniStageScreens(suffix: String) {
        skipOnboarding()
        tab("activity")
        shot("app-activity-mini-stage$suffix")
        tab("settings")
        shot("app-settings-mini-stage$suffix")
    }

    @Test
    fun miniStageScreens() = miniStageScreens("")

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.3f)
    fun miniStageScreensNarrowLargeText() = miniStageScreens("-360-font130")

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size
}
