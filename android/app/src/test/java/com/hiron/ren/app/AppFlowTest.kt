package com.hiron.ren.app

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import com.hiron.ren.browser.Addresses
import com.hiron.ren.browser.SearchLevel
import com.hiron.ren.ui.ADDRESS_HINT
import com.hiron.ren.ui.ADDRESS_TAG
import com.hiron.ren.ui.ASK_REN_TAG
import com.hiron.ren.ui.HOME_SEARCH_TAG
import com.hiron.ren.ui.MAX_ROBOT_CRASHES
import com.hiron.ren.ui.MORE_TAG
import com.hiron.ren.ui.TABS_TAG
import com.hiron.ren.ui.ROBOT_PAGE
import com.hiron.ren.ui.STAGE_TAG
import androidx.webkit.WebViewFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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
private const val SEARCH_PAGE = "https://appassets.androidplatform.net/assets/ren/web/search/index.html"

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
        shot("01-browser-home")

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

        menu("Activity")
        until { compose.onAllNodesWithTextCount("Declined: ${request.details}") > 0 }
        shot("03-activity")
        close()

        menu("Settings")
        shot("04-settings-top")
        compose.onNode(hasText("Turn on")).performScrollTo()
        shot("05-settings-call-answering")
        compose.onNode(hasText("Open Ren's app info")).performScrollTo().assertIsDisplayed()
        shot("06-settings-bottom")
        close()
        compose.onNodeWithTag(ADDRESS_TAG).assertIsDisplayed()
    }

    @Test
    fun firstRunWalksEveryStep() {
        compose.onNodeWithText("Set up").performClick()
        compose.onNodeWithText("Save & continue").assertIsDisplayed()
        compose.onNodeWithText("Save & continue").performClick()
        compose.onNodeWithText("Skip for now").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Default browser.").assertIsDisplayed()
        // Skip where Android offers the browser role, Continue where it doesn't.
        compose.onNodeWithText(if (compose.onAllNodesWithTextCount("Skip for now") > 0) "Skip for now" else "Continue").performClick()
        compose.onNodeWithText("Customize later in Settings").performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.ren.settings.onboarded)
    }

    /** Skips the power-on screens; the app then opens on the browser's home page. */
    private fun skipOnboarding() {
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.ren.settings.onboarded)
    }

    /** Opens [item] from the browser's menu. */
    private fun menu(item: String) {
        compose.onNodeWithTag(MORE_TAG).performClick()
        compose.onNodeWithText(item).performClick()
        compose.waitForIdle()
    }

    /** Closes the agent panel, Activity or Settings back to the browser. */
    private fun close() {
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Close")).performClick()
        compose.waitForIdle()
    }

    private fun askRen() {
        compose.onNodeWithTag(ASK_REN_TAG).performClick()
        compose.waitForIdle()
    }

    private fun webViews(): List<WebView> = windowRoots().flatMap(::webViewsIn)

    private fun webViewsIn(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViewsIn(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun robots(): List<WebView> = webViews().filter { shadowOf(it).lastLoadedUrl == ROBOT_PAGE }

    private fun Rect.holds(inner: Rect): Boolean =
        inner.left >= left - 1f && inner.top >= top - 1f && inner.right <= right + 1f && inner.bottom <= bottom + 1f

    /** Where the page is drawn: the view Compose holds it in, since the stand-in WebView is never measured. */
    private fun WebView.drawnBounds(): Rect {
        val holder = parent as? View ?: error("the page is not in a slot")
        val at = IntArray(2).also(holder::getLocationInWindow)
        return Rect(at[0].toFloat(), at[1].toFloat(), (at[0] + holder.width).toFloat(), (at[1] + holder.height).toFloat())
    }

    /** The one live robot page on screen, drawn inside its stage. */
    private fun robot(step: String): WebView {
        compose.waitForIdle()
        val page = robots().singleOrNull() ?: error("${robots().size} robot pages after $step")
        val stage = compose.onAllNodesWithTag(STAGE_TAG).fetchSemanticsNodes().single().boundsInWindow
        assertTrue("the robot is drawn in its stage after $step", page.drawnBounds().let { it.width > 0f && it.height > 0f && stage.holds(it) })
        assertFalse("the robot's page is alive after $step", shadowOf(page).wasDestroyCalled())
        return page
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
    fun theRobotIsOnOnboardingAndInTheAgentPanel() {
        val onboarding = robot("launch")
        skipOnboarding()
        assertTrue("onboarding's page is destroyed", shadowOf(onboarding).wasDestroyCalled())
        assertEquals("the browser's home page shows no robot", emptyList<WebView>(), robots())

        askRen()
        val panel = robot("Ask Ren")
        shot("07-agent-panel")
        close()
        assertTrue("the panel's page is destroyed when it closes", shadowOf(panel).wasDestroyCalled())
        assertEquals(emptyList<WebView>(), robots())
        compose.onNodeWithTag(ADDRESS_TAG).assertIsDisplayed()
    }

    @Test
    fun aRendererCrashBringsAFreshPageToTheStage() {
        skipOnboarding()
        askRen()
        val page = robot("Ask Ren")

        crash(page)
        assertTrue("the dead page is destroyed", shadowOf(page).wasDestroyCalled())
        val fresh = robot("the renderer crash")
        assertNotSame(page, fresh)

        // Commands wait for the fresh page; once it loads, Ren's state is replayed there.
        assertNull(shadowOf(fresh).lastEvaluatedJavascript)
        ready(fresh)
        assertEquals("""window.ren && window.ren.state("idle");""", shadowOf(fresh).lastEvaluatedJavascript)
    }

    @Test
    fun aRendererThatKeepsCrashingSaysSoAndRetries() {
        skipOnboarding()
        askRen()
        repeat(MAX_ROBOT_CRASHES) { crash(robots().single()) }
        assertEquals("no page after $MAX_ROBOT_CRASHES crashes", emptyList<WebView>(), robots())
        compose.onNodeWithText(CRASHING).assertIsDisplayed()
        shot("app-robot-crashing")

        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText(CRASHING).assertDoesNotExist()
        robot("Retry")
    }

    @Test
    fun aPageThatFailsToLoadSaysWhyAndRetries() {
        skipOnboarding()
        askRen()
        val page = robot("Ask Ren")
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
        val fresh = robot("Retry")
        assertNotSame(page, fresh)
        assertTrue("the failed page is destroyed", shadowOf(page).wasDestroyCalled())
    }

    @Test
    fun commandsReachTheRobot() {
        assertFalse(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        skipOnboarding()
        askRen()
        val page = robot("Ask Ren")
        assertNull("commands wait for the page", shadowOf(page).lastEvaluatedJavascript)
        ready(page)
        // The page hears its theme and framing, then Ren's state.
        assertEquals("""window.ren && window.ren.state("idle");""", shadowOf(page).lastEvaluatedJavascript)

        compose.activity.ren.listening(true)
        compose.waitForIdle()
        assertEquals("""window.ren && window.ren.state("listening");""", shadowOf(page).lastEvaluatedJavascript)

        compose.activity.ren.listening(false)
        compose.waitForIdle()
        assertEquals("""window.ren && window.ren.state("idle");""", shadowOf(page).lastEvaluatedJavascript)
    }

    /** The active tab's page, once the tab has left home. */
    private fun tabPage(): WebView = webViews().single { shadowOf(it).lastLoadedUrl != ROBOT_PAGE }

    @Test
    fun anAddressTypedOnHomeLoadsInTheTab() {
        skipOnboarding()
        val browser = compose.activity.ren.browser
        compose.onNodeWithText(ADDRESS_HINT).assertIsDisplayed()
        assertEquals(emptyList<WebView>(), webViews())

        compose.onNodeWithTag(HOME_SEARCH_TAG).performTextInput("example.com/docs")
        compose.onNodeWithTag(HOME_SEARCH_TAG).performImeAction()
        compose.waitForIdle()
        assertEquals("https://example.com/docs", shadowOf(tabPage()).lastLoadedUrl)
        assertEquals("https://example.com/docs", browser.state.value.current?.url)
        // At rest the bar shows the page's identity, not its plumbing.
        compose.onNodeWithText("example.com/docs").assertIsDisplayed()
        shot("08-browser-page")

        // Back from a tab's first page returns to home, and Forward goes to the page again.
        compose.runOnUiThread { browser.back() }
        compose.waitForIdle()
        assertEquals(Addresses.HOME, browser.state.value.current?.url)
        compose.onNodeWithTag(HOME_SEARCH_TAG).assertIsDisplayed()
        assertTrue(browser.state.value.current?.canGoForward == true)
    }

    @Test
    fun aPhraseInTheAddressBarOpensRensSearchPage() {
        skipOnboarding()
        val runtime = compose.activity.ren
        runtime.settings.searchLevel = SearchLevel.Deep
        compose.onNodeWithTag(ADDRESS_TAG).performClick()
        compose.onNodeWithTag(ADDRESS_TAG).performTextInput("best ramen in reno")
        compose.onNodeWithTag(ADDRESS_TAG).performImeAction()
        compose.waitForIdle()
        assertEquals("$SEARCH_PAGE?q=best%20ramen%20in%20reno&level=medium", shadowOf(tabPage()).lastLoadedUrl)
        assertEquals("ren://search?q=best%20ramen%20in%20reno&level=medium", runtime.browser.state.value.current?.url)
        // The bar reads as the query.
        compose.onNodeWithText("best ramen in reno").assertIsDisplayed()
    }

    @Test
    fun tabsOpenSwitchAndClose() {
        skipOnboarding()
        val browser = compose.activity.ren.browser
        val first = checkNotNull(browser.state.value.active)
        compose.onNodeWithTag(TABS_TAG).performClick()
        shot("09-browser-tabs")
        compose.onNodeWithText("New tab").performClick()
        compose.waitForIdle()
        assertEquals(2, browser.state.value.tabs.size)
        val second = checkNotNull(browser.state.value.active)
        assertTrue(first != second)

        compose.runOnUiThread { browser.close(second) }
        compose.waitForIdle()
        assertEquals(listOf(first), browser.state.value.tabs.map { it.id })
        assertEquals(first, browser.state.value.active)
        // Closing the last tab leaves a fresh home tab, never an empty browser.
        compose.runOnUiThread { browser.close(first) }
        compose.waitForIdle()
        assertEquals(1, browser.state.value.tabs.size)
        assertTrue(browser.state.value.current?.home == true)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size
}
