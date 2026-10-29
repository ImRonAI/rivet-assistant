package com.hiron.rivet.app

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.hiron.rivet.MainActivity
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.rivet
import com.hiron.rivet.testing.FakeAndroidKeyStore
import com.hiron.rivet.testing.shot
import com.hiron.rivet.testing.windowRoots
import com.hiron.rivet.ui.COMPOSER_TAG
import com.hiron.rivet.ui.MAX_ROBOT_CRASHES
import com.hiron.rivet.ui.MINI_STAGE_TAG
import com.hiron.rivet.ui.STAGE_TAG
import com.hiron.rivet.ui.TAB_TAG_PREFIX
import androidx.webkit.WebViewFeature
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val ROBOT_PAGE = "https://appassets.androidplatform.net/assets/rivet/index.html"
private const val CRASHING = "Rivet's robot keeps crashing on this phone."
private const val NOT_LOADED = "Rivet's robot didn't load (error 404)."

/**
 * Drives the real app (MainActivity, runtime, gateway client, screens) against a stand-in brain over a real
 * WebSocket, and saves a screenshot of every screen to build/screens for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<JSONObject>()
    private val sockets = LinkedBlockingQueue<WebSocket>()

    @After
    fun tearDown() {
        compose.activity.rivet.disconnect()
        sockets.forEach { it.close(1000, null) }
        brainSockets.forEach { it.close(1000, null) }
        Thread.sleep(200)
        runCatching { server.close() }
    }

    private val brainSockets = mutableListOf<WebSocket>()

    private fun shot(name: String) = shot(compose, compose.activity, name)

    private fun next(type: String): JSONObject {
        while (true) {
            val m = received.poll(10, TimeUnit.SECONDS) ?: error("brain never got $type")
            if (m.optString("type") == type) return m
        }
    }

    private fun until(timeoutMs: Long = 10_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(50)
            compose.waitForIdle()
        }
    }

    private fun brain(socket: WebSocket, vararg events: String) = events.forEach { socket.send(it) }

    @Test
    fun theWholeAppWorksAgainstABrain() {
        // First run: the power-on screen, then straight into the app.
        compose.onNodeWithText("Use recommended settings").assertIsDisplayed()
        shot("00-onboarding")
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.rivet.settings.onboarded)
        shot("01-home-start")

        // Point the runtime at the stand-in brain and connect, as the baked-in config does with the real one.
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    brainSockets += webSocket
                    sockets.put(webSocket)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.put(JSONObject(text))
                }
            }),
        )
        server.start()
        val runtime = compose.activity.rivet
        runtime.settings.gatewayUrl = server.url("/").toString()
        runtime.settings.sessionId = "flow-test"
        // This flow is Rivet's own brain; the ElevenLabs agents are the default, and suggestions would call the brain too.
        runtime.settings.elevenLabsAgents = false
        runtime.settings.searchSuggestions = false
        // The token is sealed with the Android keystore, as on a phone; no baked-in build config is needed.
        FakeAndroidKeyStore.install()
        runtime.settings.deviceToken = "flow-test-device-token"
        assertEquals("flow-test-device-token", runtime.settings.deviceToken)
        runtime.connect()
        val socket = sockets.poll(10, TimeUnit.SECONDS)!!
        assertEquals("resume", next("resume").getString("type"))
        until { runtime.state.value.connection == Connection.Online }
        // Without a baked-in config the app opened on Settings; back to Rivet now that it is connected.
        compose.onNodeWithTag(TAB_TAG_PREFIX + "rivet").performClick()
        shot("02-home-online")

        // Chat: type, send, stream a reply.
        compose.onNodeWithTag(COMPOSER_TAG).performTextInput("What's on my calendar tomorrow?")
        compose.onNodeWithContentDescription("Send").performClick()
        val turn = next("turn")
        assertEquals("What's on my calendar tomorrow?", turn.getString("text"))
        val id = turn.getString("turn_id")
        brain(
            socket,
            """{"type":"turn.accepted","turn_id":"$id"}""",
            """{"type":"turn.started","turn_id":"$id","lane":"default"}""",
            """{"type":"tool","id":"c1","name":"calendar_list","lane":"default"}""",
        )
        // The brain asks the phone for its calendar; the app runs the tool and answers.
        brain(socket, """{"type":"device.call","action_id":"c1","tool":"device_status","input":{}}""")
        val result = next("device.result")
        assertEquals("c1", result.getString("action_id"))
        assertTrue(result.getJSONObject("result").getBoolean("ok"))
        brain(
            socket,
            """{"type":"text","delta":"Tomorrow you have ","lane":"default"}""",
            """{"type":"text","delta":"a **dentist cleaning** at 9 AM.","lane":"default"}""",
            """{"type":"turn.completed","turn_id":"$id","lane":"default","text":"Tomorrow you have a **dentist cleaning** at 9 AM.","restarts":0}""",
        )
        until { compose.onAllNodesWithTextCount("dentist cleaning") > 0 }
        compose.onNodeWithText("Tomorrow you have a dentist cleaning at 9 AM.").assertIsDisplayed()
        shot("03-home-conversation")

        // The transcript sheet pulls up over the stage and back down.
        compose.onNodeWithContentDescription("Expand transcript").performClick()
        until { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Show Rivet")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(600)
        shot("03b-home-transcript-expanded")
        compose.onNodeWithContentDescription("Show Rivet").performClick()
        compose.mainClock.advanceTimeBy(600)

        // An approval pops the review sheet; "don't ask again" with Deny answers the brain with a standing deny.
        brain(
            socket,
            """{"type":"approval.request","turn_id":"$id","lane":"default","expires_at":9999999999,
               "approvals":[{"id":"rivet-approval:a1","name":"rivet-approval","because":"You asked: “Text Dana that Friday at 9 works”",
               "request":{"v":2,"tool":"sms_send","tier":"send","high":false,"headline":"Text +18015550123: “Friday at 9 works!”",
               "blocks":[{"kind":"field","label":"To","value":"+18015550123"},{"kind":"quote","label":"Message","text":"Friday at 9 works!"}],
               "preview":"{\"to\": \"+18015550123\", \"body\": \"Friday at 9 works!\"}",
               "offer":{"key":"sms_send?to=%2B18015550123","tool":"sms_send","scope":{"to":"+18015550123"},"label":"Texts to +18015550123",
               "allow":true,"deny":true}}}]}""",
        )
        until { compose.onAllNodesWithTextCount("Text +1 801-555-0123: “Friday at 9 works!”") > 0 }
        shot("04-approval-sheet")
        compose.onNodeWithText("Don't ask again about Texts to +1 801-555-0123").performClick()
        compose.onNodeWithText("Always deny").performClick()
        val answer = next("approval.response").getJSONObject("answers").getJSONObject("rivet-approval:a1")
        assertFalse(answer.getBoolean("approved"))
        assertEquals("deny", answer.getString("standing"))

        // A call Rivet is answering: the lines come in live, the user takes it over, then Rivet's summary.
        runtime.settings.ownNumber = "+18015550100"
        val started = System.currentTimeMillis() / 1000.0 - 42
        brain(
            socket,
            """{"type":"call.live","call_id":"c-1","from":"+18015550123","verified":true,"started_at":$started,"status":"answering",
               "lines":[{"speaker":"rivet","text":"Hi, this is Rivet, Tim's assistant. How can I help?"},
               {"speaker":"caller","text":"Hi, it's Dana from Wasatch Dental, confirming Friday at 9."}],"snapshot":true}""",
        )
        until { compose.onAllNodesWithTextCount("confirming Friday at 9") > 0 }
        shot("04b-live-call")
        compose.onNodeWithText("Take over").performClick()
        val takeOver = next("call.take_over")
        assertEquals("c-1", takeOver.getString("call_id"))
        assertEquals("+18015550100", takeOver.getString("to"))
        brain(
            socket,
            """{"type":"call.ended","call_id":"c-1","from":"+18015550123","caller_name":"Dana from Wasatch Dental","started_at":$started,
               "ended_at":${started + 95},"duration":95,"summary":"Dana confirmed Friday at 9 for the cleaning.","outcome":"message_taken",
               "taken_over":true}""",
        )
        until { compose.onAllNodesWithTextCount("Dismiss") > 0 }
        shot("04c-call-ended")
        compose.onNodeWithText("Dismiss").performClick()

        // A call message arrives.
        brain(
            socket,
            """{"type":"call.message","caller_name":"Dana from Wasatch Dental","callback_number":"+18015550123",
               "message":"Confirming Friday at 9.","urgent":false,"hung_up_early":false,"at":1793080062.0}""",
        )
        compose.onNodeWithTag(TAB_TAG_PREFIX + "activity").performClick()
        until { compose.onAllNodesWithTextCount("Dana from Wasatch Dental") > 0 }
        shot("05-activity")

        compose.onNodeWithTag(TAB_TAG_PREFIX + "settings").performClick()
        compose.waitForIdle()
        shot("06-settings-top")
        compose.onNode(hasText("Turn on")).performScrollTo()
        shot("07-settings-call-answering")
        compose.onNode(hasText("Open Rivet's app info")).performScrollTo().assertIsDisplayed()
        shot("08-settings-bottom")
    }

    @Test
    fun firstRunWalksEveryStep() {
        compose.onNodeWithText("Set up step by step").performClick()
        compose.onNodeWithText("Save & Continue").assertIsDisplayed()
        compose.onNodeWithText("Save & Continue").performClick()
        compose.onNodeWithText("Skip for now").performClick()
        compose.onNodeWithText("Customize later in Settings").performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.rivet.settings.onboarded)
    }

    /**
     * Skips the power-on screens. The app then opens on [landing]: Home with a complete brain config (one baked in from
     * `rivet.local.properties` counts), Settings without one.
     */
    private fun skipOnboarding() {
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.rivet.settings.onboarded)
    }

    /** The slot showing Rivet on the screen the app opens on after onboarding. */
    private fun landing(): Slot = if (compose.activity.rivet.settings.gateway().complete) Slot.Stage else Slot.Band

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
     * The robot frame of each slot on screen. Every slot draws Rivet in a [STAGE_TAG] frame; a band's frame sits
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
        val gone = object : RenderProcessGoneDetail() {
            override fun didCrash(): Boolean = true

            override fun rendererPriorityAtExit(): Int = 0
        }
        compose.runOnUiThread { shadowOf(page).webViewClient.onRenderProcessGone(page, gone) }
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
        tab("rivet")
        assertPages("Home", Slot.Stage)
        assertTrue("the band's page is destroyed", shadowOf(band).wasDestroyCalled())
        compose.onNodeWithTag(MINI_STAGE_TAG).assertDoesNotExist()
        tab("settings")
        assertPages("back to Settings", Slot.Band)

        tab("rivet")
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
        tab("rivet")
        assertPages("Home", Slot.Stage)

        // Back to Home before the tab crossfade (220 ms) and the band's entrance finish.
        compose.mainClock.autoAdvance = false
        tapTabNow("settings")
        compose.mainClock.advanceTimeBy(100)
        tapTabNow("rivet")
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.autoAdvance = true
        assertPages("tapping Rivet mid-fade", Slot.Stage)
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

        // Commands wait for the fresh page; once it loads, Rivet's state is replayed there.
        assertNull(shadowOf(fresh).lastEvaluatedJavascript)
        ready(fresh)
        assertEquals("""window.rivet && window.rivet.state("idle");""", shadowOf(fresh).lastEvaluatedJavascript)
    }

    @Test
    fun aRendererThatKeepsCrashingSaysSoAndRetries() {
        skipOnboarding()
        tab("rivet")
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
        tab("rivet")
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
        assertEquals("""window.rivet && window.rivet.state("idle");""", shadowOf(band).lastEvaluatedJavascript)

        compose.activity.rivet.listening(true)
        compose.waitForIdle()
        assertEquals("""window.rivet && window.rivet.state("listening");""", shadowOf(band).lastEvaluatedJavascript)

        compose.activity.rivet.listening(false)
        compose.waitForIdle()
        assertEquals("""window.rivet && window.rivet.state("idle");""", shadowOf(band).lastEvaluatedJavascript)
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
