package com.hiron.rivet.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
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
        compose.onNodeWithText("Get Started").assertIsDisplayed()
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

        // An approval pops the review sheet; approving answers the brain.
        brain(
            socket,
            """{"type":"approval.request","turn_id":"$id","lane":"default","expires_at":9999999999,
               "approvals":[{"id":"rivet-approval:a1","name":"rivet-approval","request":{"v":1,"tool":"sms_send",
               "tier":"send","high":false,"summary":"Text +18015550123","preview":"{\"to\": \"+18015550123\", \"body\": \"Friday at 9 works!\"}",
               "remember":true}}]}""",
        )
        until { compose.onAllNodesWithTextCount("Text +1 801-555-0123") > 0 }
        shot("04-approval-sheet")
        compose.onNodeWithText("Approve").performClick()
        val answer = next("approval.response")
        assertTrue(answer.getJSONObject("answers").getJSONObject("rivet-approval:a1").getBoolean("approved"))

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
        compose.onNodeWithText("Get Started").performClick()
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

    /** Skips the power-on screens; with no baked-in brain the app then opens on Settings. */
    private fun skipOnboarding() {
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.rivet.settings.onboarded)
    }

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

    /** The robot page on screen is [first], still alive, and the only one. */
    private fun assertOnlyPage(first: WebView, step: String) {
        compose.waitForIdle()
        val shown = webViews()
        assertEquals("one robot page after $step", 1, shown.size)
        assertSame("the same robot page after $step", first, shown.single())
        assertFalse("the page survived $step", shadowOf(first).wasDestroyCalled())
        assertEquals(ROBOT_PAGE, shadowOf(first).lastLoadedUrl)
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
        assertOnlyPage(first, "launch")
        skipOnboarding()
        assertOnlyPage(first, "finishing onboarding")

        tab("activity")
        assertOnlyPage(first, "Activity")
        compose.onNodeWithTag(MINI_STAGE_TAG).assertIsDisplayed()
        tab("settings")
        assertOnlyPage(first, "Settings")
        compose.onNodeWithTag(MINI_STAGE_TAG).assertIsDisplayed()
        tab("rivet")
        assertOnlyPage(first, "Home")
        compose.onNodeWithTag(MINI_STAGE_TAG).assertDoesNotExist()
        tab("settings")
        assertOnlyPage(first, "back to Settings")

        openVoiceSetupFromSettings()
        assertOnlyPage(first, "opening the voice setup")
        // The voice setup's band over the one Settings keeps underneath it.
        compose.onAllNodesWithTag(MINI_STAGE_TAG).assertCountEquals(2)
        pressBack()
        compose.onNodeWithText("RIVET'S VOICE.").assertDoesNotExist()
        assertOnlyPage(first, "closing the voice setup")
        compose.onNodeWithTag(MINI_STAGE_TAG).assertIsDisplayed()

        tab("rivet")
        assertOnlyPage(first, "Home again")
        compose.onNodeWithTag(MINI_STAGE_TAG).assertDoesNotExist()
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
        assertOnlyPage(page, "Settings")

        compose.activity.rivet.listening(true)
        compose.waitForIdle()
        assertEquals("""window.rivet && window.rivet.state("listening");""", shadowOf(page).lastEvaluatedJavascript)

        // Opening the voice setup frames Rivet for its band and replays the state there.
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
