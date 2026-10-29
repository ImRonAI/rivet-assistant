package com.hiron.rivet.app

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import com.hiron.rivet.ui.MINI_STAGE_TAG
import com.hiron.rivet.ui.STAGE_TAG
import com.hiron.rivet.ui.TAB_TAG_PREFIX
import com.hiron.rivet.widget.RivetWidgets
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlin.math.abs

private const val ROBOT_PAGE = "https://appassets.androidplatform.net/assets/rivet/index.html"
private const val MARKER = "/* marker */"

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
    fun firstRunWalksEveryStepAndTheVoiceSetupOpensFromOnboardingAndSettings() {
        compose.onNodeWithText("Set up step by step").performClick()
        compose.onNodeWithText("Save & Continue").assertIsDisplayed()

        // The voice link on step 2 opens the voice setup over onboarding; skipping it lands back on step 2.
        compose.onNodeWithText("Choose Rivet's voice").performClick()
        compose.onNodeWithText("RIVET'S VOICE.").assertIsDisplayed()
        shot("09-voice-from-onboarding")
        compose.onAllNodesWithText("Skip voice setup")[0].performClick()
        compose.onNodeWithText("RIVET'S VOICE.").assertDoesNotExist()

        compose.onNodeWithText("Save & Continue").performClick()
        compose.onNodeWithText("Skip for now").performClick()
        compose.onNodeWithText("Customize later in Settings").performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.rivet.settings.onboarded)

        // Settings' Voice section opens the same setup.
        compose.onNodeWithTag(TAB_TAG_PREFIX + "settings").performClick()
        compose.onNodeWithText("Not chosen yet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rivet's voice").performScrollTo().performClick()
        compose.onNodeWithText("Choose or make a voice").performClick()
        compose.onNodeWithText("PRESET LIBRARY.").assertIsDisplayed()
        compose.onNodeWithText("Sol").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("RIVET'S VOICE.").assertIsDisplayed()

        // Back closes the setup, and the Voice section shows the saved voice.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("RIVET'S VOICE.").assertDoesNotExist()
        compose.onNodeWithText("Sol").performScrollTo().assertIsDisplayed()
        shot("10-settings-voice-chosen")
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

        /** The band in the voice setup, drawn over the whole app. */
        VoiceBand,
    }

    private fun boundsOf(tag: String): List<Rect> = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().map { it.boundsInWindow }

    private fun Rect.holds(inner: Rect): Boolean =
        inner.left >= left - 1f && inner.top >= top - 1f && inner.right <= right + 1f && inner.bottom <= bottom + 1f

    /**
     * The robot frame of each slot on screen. Every slot draws Rivet in a [STAGE_TAG] frame; a band's frame sits
     * inside its [MINI_STAGE_TAG] band. The voice setup is composed after the app under it, so its band comes last.
     */
    private fun slotFrames(): Map<Slot, Rect> {
        val bands = boundsOf(MINI_STAGE_TAG)
        val (inBands, stages) = boundsOf(STAGE_TAG).partition { frame -> bands.any { it.holds(frame) } }
        return buildMap {
            stages.singleOrNull()?.let { put(Slot.Stage, it) }
            inBands.firstOrNull()?.let { put(Slot.Band, it) }
            inBands.lastOrNull()?.let { put(Slot.VoiceBand, it) }
        }
    }

    /** Where the page is drawn: the view Compose holds it in, since the stand-in WebView is never measured. */
    private fun WebView.drawnBounds(): Rect {
        val holder = parent as? View ?: error("the page is not in a slot")
        val at = IntArray(2).also(holder::getLocationInWindow)
        return Rect(at[0].toFloat(), at[1].toFloat(), (at[0] + holder.width).toFloat(), (at[1] + holder.height).toFloat())
    }

    /** The page fills [slot]'s frame: the frame holds it, and no other frame starts nearer to it (bands overlap). */
    private fun assertPageIn(page: WebView, slot: Slot, step: String) {
        val frames = slotFrames()
        val drawn = page.drawnBounds()
        val expected = frames[slot] ?: error("no $slot on screen after $step: $frames")
        val nearest = frames.values.minBy { abs(it.left - drawn.left) + abs(it.top - drawn.top) }
        assertTrue("the page is drawn after $step: $drawn", drawn.width > 0f && drawn.height > 0f)
        assertTrue("the page is in the $slot after $step: page $drawn, frames $frames", expected.holds(drawn) && nearest == expected)
    }

    /** The robot page on screen is [first], still alive, the only one, and drawn in [slot]. */
    private fun assertOnlyPage(first: WebView, step: String, slot: Slot) {
        compose.waitForIdle()
        val shown = webViews()
        assertEquals("one robot page after $step", 1, shown.size)
        assertSame("the same robot page after $step", first, shown.single())
        assertFalse("the page survived $step", shadowOf(first).wasDestroyCalled())
        assertEquals(ROBOT_PAGE, shadowOf(first).lastLoadedUrl)
        assertPageIn(first, slot, step)
    }

    private fun openVoiceSetupFromSettings() {
        compose.onNodeWithText("Rivet's voice").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("RIVET'S VOICE.").assertIsDisplayed()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun oneRobotPageAcrossScreens() {
        compose.waitForIdle()
        val first = webViews().single()
        assertOnlyPage(first, "launch", Slot.Stage)
        skipOnboarding()
        assertOnlyPage(first, "finishing onboarding", landing())

        tab("activity")
        assertOnlyPage(first, "Activity", Slot.Band)
        compose.onNodeWithTag(MINI_STAGE_TAG).assertIsDisplayed()
        tab("settings")
        assertOnlyPage(first, "Settings", Slot.Band)
        compose.onNodeWithTag(MINI_STAGE_TAG).assertIsDisplayed()
        tab("rivet")
        assertOnlyPage(first, "Home", Slot.Stage)
        compose.onNodeWithTag(MINI_STAGE_TAG).assertDoesNotExist()
        tab("settings")
        assertOnlyPage(first, "back to Settings", Slot.Band)

        openVoiceSetupFromSettings()
        // The voice setup's band over the one Settings keeps underneath it.
        compose.onAllNodesWithTag(MINI_STAGE_TAG).assertCountEquals(2)
        assertOnlyPage(first, "opening the voice setup", Slot.VoiceBand)
        pressBack()
        compose.onNodeWithText("RIVET'S VOICE.").assertDoesNotExist()
        assertOnlyPage(first, "closing the voice setup", Slot.Band)
        compose.onNodeWithTag(MINI_STAGE_TAG).assertIsDisplayed()

        tab("rivet")
        assertOnlyPage(first, "Home again", Slot.Stage)
        compose.onNodeWithTag(MINI_STAGE_TAG).assertDoesNotExist()
    }

    @Test
    fun theVoiceSetupKeepsThePageWhenAWidgetOpensHome() {
        compose.waitForIdle()
        val page = webViews().single()
        skipOnboarding()
        tab("settings")
        openVoiceSetupFromSettings()
        assertOnlyPage(page, "opening the voice setup", Slot.VoiceBand)

        // A widget's quick key switches the app under the voice setup to Home.
        val quick = Intent(RivetWidgets.ACTION_QUICK, Uri.parse("rivet://quick/MyDay"))
        compose.runOnUiThread { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(compose.activity, quick) }
        compose.waitForIdle()
        compose.onNodeWithText("RIVET'S VOICE.").assertIsDisplayed()
        compose.onAllNodesWithTag(MINI_STAGE_TAG).assertCountEquals(1)
        assertOnlyPage(page, "a widget opening Home under the voice setup", Slot.VoiceBand)

        pressBack()
        compose.onNodeWithText("RIVET'S VOICE.").assertDoesNotExist()
        assertOnlyPage(page, "closing the voice setup over Home", Slot.Stage)
    }

    /** Taps a tab on a paused clock: the tap's state change is sent, and no frame runs until the test advances it. */
    private fun tapTabNow(name: String) {
        val key = compose.onNodeWithTag(TAB_TAG_PREFIX + name).fetchSemanticsNode()
        compose.runOnUiThread { key.config[SemanticsActions.OnClick].action?.invoke() }
        compose.waitForIdle()
    }

    @Test
    fun aQuickRoundTripHandsThePageBackToHome() {
        compose.waitForIdle()
        val page = webViews().single()
        skipOnboarding()
        tab("rivet")
        assertOnlyPage(page, "Home", Slot.Stage)

        // Back to Home before the tab crossfade (220 ms) and the band's entrance finish.
        compose.mainClock.autoAdvance = false
        tapTabNow("settings")
        compose.mainClock.advanceTimeBy(100)
        assertFalse("the band took the page", slotFrames().getValue(Slot.Stage).holds(page.drawnBounds()))
        tapTabNow("rivet")
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("one robot page", listOf(page), webViews())
        assertPageIn(page, Slot.Stage, "tapping Rivet mid-fade")
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun aRendererCrashBringsAFreshPageToTheSlotShowingIt() {
        compose.waitForIdle()
        val page = webViews().single()
        skipOnboarding()
        tab("settings")
        assertOnlyPage(page, "Settings", Slot.Band)

        val gone = object : RenderProcessGoneDetail() {
            override fun didCrash(): Boolean = true

            override fun rendererPriorityAtExit(): Int = 0
        }
        compose.runOnUiThread { shadowOf(page).webViewClient.onRenderProcessGone(page, gone) }
        compose.waitForIdle()
        assertTrue("the dead page is destroyed", shadowOf(page).wasDestroyCalled())
        val fresh = webViews().single()
        assertNotSame(page, fresh)
        assertOnlyPage(fresh, "the renderer crash", Slot.Band)

        // Commands wait for the fresh page; once it loads, Rivet's state is replayed there.
        assertNull(shadowOf(fresh).lastEvaluatedJavascript)
        compose.runOnUiThread { shadowOf(fresh).webViewClient.onPageFinished(fresh, ROBOT_PAGE) }
        compose.waitForIdle()
        assertEquals("""window.rivet && window.rivet.state("idle");""", shadowOf(fresh).lastEvaluatedJavascript)
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
    fun commandsReachThePageOffHome() {
        // Robolectric's WebView has no message listener, so the host takes the page as ready once it finishes loading.
        assertFalse(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        compose.waitForIdle()
        val page = webViews().single()
        compose.runOnUiThread { shadowOf(page).webViewClient.onPageFinished(page, ROBOT_PAGE) }
        assertEquals("""window.rivet && window.rivet.state("idle");""", shadowOf(page).lastEvaluatedJavascript)
        skipOnboarding()
        tab("settings")
        assertOnlyPage(page, "Settings", Slot.Band)

        compose.activity.rivet.listening(true)
        compose.waitForIdle()
        assertEquals("""window.rivet && window.rivet.state("listening");""", shadowOf(page).lastEvaluatedJavascript)

        // Opening the voice setup frames Rivet for its band and replays the state there; only a replay
        // overwrites the marker.
        compose.runOnUiThread { page.evaluateJavascript(MARKER, null) }
        assertEquals(MARKER, shadowOf(page).lastEvaluatedJavascript)
        openVoiceSetupFromSettings()
        assertEquals("""window.rivet && window.rivet.state("listening");""", shadowOf(page).lastEvaluatedJavascript)
        compose.activity.rivet.listening(false)
        compose.waitForIdle()
        assertEquals("""window.rivet && window.rivet.state("idle");""", shadowOf(page).lastEvaluatedJavascript)
    }

    private fun miniStageScreens(suffix: String) {
        skipOnboarding()
        tab("activity")
        shot("app-activity-mini-stage$suffix")
        tab("settings")
        shot("app-settings-mini-stage$suffix")
        openVoiceSetupFromSettings()
        shot("app-voice-setup-mini-stage$suffix")
    }

    @Test
    fun miniStageScreens() = miniStageScreens("")

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.3f)
    fun miniStageScreensNarrowLargeText() = miniStageScreens("-360-font130")

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size
}
