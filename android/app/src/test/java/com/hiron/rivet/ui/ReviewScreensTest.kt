package com.hiron.rivet.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hiron.rivet.core.ApprovalRequest
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.LogEntry
import com.hiron.rivet.core.Protocol
import com.hiron.rivet.core.RivetState
import com.hiron.rivet.core.rivet
import com.hiron.rivet.device.ReminderStore
import com.hiron.rivet.device.ReminderStoreFile
import com.hiron.rivet.testing.shot
import com.hiron.rivet.voice.LocalVoiceStudio
import com.hiron.rivet.voice.SampleRecorder
import com.hiron.rivet.voice.VoiceChoice
import com.hiron.rivet.voice.VoicePreset
import com.hiron.rivet.voice.VoiceSample
import com.hiron.rivet.voice.VoiceStudio
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The approval sheet, Activity and Settings with realistic content, saved to build/screens/review-*.png. The
 * narrow runs use a 360dp phone at 130% text size to catch clipping.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ReviewScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var answered: Pair<String, Map<String, Protocol.Answer>>? = null

    private fun page(content: @Composable () -> Unit) = compose.setContent {
        RivetTheme {
            Box(Modifier.fillMaxSize().background(LocalPalette.current.bg)) { content() }
        }
    }

    private fun sheet(approvals: List<ApprovalRequest>) = page {
        ApprovalSheet(approvals, onAnswer = { lane, a -> answered = lane to a }, onDismiss = {})
    }

    private val text = ApprovalRequest(
        "a1", "default", "t1", "sms_send", "send", false, "Text +18015550123",
        """{"to": "+18015550123", "body": "Friday at 9 works! See you then."}""", true, false, emptyList(), null,
    )

    private val buy = ApprovalRequest(
        "a2", "default", "t1", "telnyx_execute", "purchase", true, "Buy the number +1 760 555 0142",
        "call_tool('CreateNumberOrder', {'phone_numbers': [{'phone_number': '+17605550142'}]})", false, true,
        listOf(ApprovalRequest.Operation("CreateNumberOrder", "POST", "Create a number order")), null,
    )

    @Test
    fun oneTextToApprove() {
        sheet(listOf(text))
        compose.onNodeWithText("Send a text").assertIsDisplayed()
        compose.onAllNodesWithText("Approve").assertCountEquals(1)
        compose.onAllNodesWithText("sms_send").assertCountEquals(0)
        shot(compose, compose.activity, "review-approval-text")
        compose.onNodeWithText("Don't ask again for 30 minutes").performClick()
        compose.onNodeWithText("Approve").performClick()
        assertEquals("default" to mapOf("a1" to Protocol.Answer(true, 30)), answered)
    }

    @Test
    fun aPurchaseShowsItsRiskAndDetails() {
        sheet(listOf(buy))
        compose.onNodeWithText("SPENDS MONEY").assertIsDisplayed()
        compose.onNodeWithText("High risk").assertIsDisplayed()
        compose.onNodeWithText("Approve with unlock").assertIsDisplayed()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("telnyx_execute").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(600)
        shot(compose, compose.activity, "review-approval-purchase")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.3f)
    fun severalAtOnceOnANarrowPhone() {
        val email = ApprovalRequest(
            "a3", "default", "t1", "email_send", "send", false, "Email dana@wasatchdental.com",
            """{"to": "dana@wasatchdental.com", "subject": "Friday cleaning", "body": "Confirmed for Friday at 9."}""",
            true, false, emptyList(), null,
        )
        sheet(listOf(text, email, buy))
        compose.onNodeWithText("Rivet wants to do 3 things:").assertIsDisplayed()
        compose.onNodeWithText("Send answers").assertIsDisplayed()
        shot(compose, compose.activity, "review-approval-batch-narrow")
        compose.onNodeWithText("Deny all").performClick()
        assertEquals(setOf(false), answered!!.second.values.map { it.approved }.toSet())
    }

    private fun activityState(): RivetState {
        val now = System.currentTimeMillis()
        val min = 60_000L
        val day = 24 * 60 * min
        return RivetState(
            connection = Connection.Online,
            activeTurn = "t2",
            log = listOf(
                LogEntry(now - day - 30 * min, "turn", "Working on it"),
                LogEntry(now - day - 29 * min, "search", "dentist near Sandy UT"),
                LogEntry(now - day - 28 * min, "error", "The phone didn't answer in time."),
                LogEntry(now - 50 * min, "call", "Message from Dana from Wasatch Dental: Confirming Friday at 9."),
                LogEntry(now - 40 * min, "turn", "Working on it"),
                LogEntry(now - 39 * min, "tool", "calendar_list"),
                LogEntry(now - 38 * min, "device", "device_status: done"),
                LogEntry(now - 37 * min, "backoff", "Model busy; retrying in 8s"),
                LogEntry(now - 36 * min, "approval", "Text +18015550123"),
                LogEntry(now - 35 * min, "approval", "Approved: Text +18015550123"),
                LogEntry(now - 34 * min, "device", "sms_send: done"),
                LogEntry(now - 2 * min, "task", "Task queued: find a cheaper phone plan"),
            ),
        )
    }

    @Test
    fun activityInPlainWords() {
        val context = compose.activity
        ReminderStore(File(context.filesDir, ReminderStoreFile)).put("r1", "Call the dentist back", System.currentTimeMillis() + 3_600_000)
        val runtime = context.rivet
        val state = activityState()
        page { ActivityScreen(runtime, state) }
        compose.onNodeWithText("Sent a text").assertExists()
        compose.onNodeWithText("Rivet's model is busy. Trying again in 8s.").assertExists()
        compose.onNode(hasText("Dana from Wasatch Dental", substring = true)).assertExists()
        compose.onNodeWithText("Call the dentist back").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(800)
        shot(compose, compose.activity, "review-activity")
    }

    @Test
    @Config(qualifiers = "w360dp-h1400dp-xxhdpi", fontScale = 1.3f)
    fun activityOnANarrowPhone() {
        val runtime = compose.activity.rivet
        val state = activityState()
        page { ActivityScreen(runtime, state) }
        compose.mainClock.advanceTimeBy(800)
        shot(compose, compose.activity, "review-activity-narrow")
    }

    private class FakeStudio(var label: String?) : VoiceStudio {
        override val connected = false
        override fun presets(): List<VoicePreset> = emptyList()
        override fun preview(text: String, preset: VoicePreset?, onDone: () -> Unit) = Unit
        override fun play(sample: VoiceSample, onProgress: (Float) -> Unit, onDone: () -> Unit) = Unit
        override fun stopPreview() = Unit
        override fun recorder(): SampleRecorder = error("not used")
        override fun choose(choice: VoiceChoice): String = "Copper"
        override fun current(): String? = label
    }

    private fun settings(studio: VoiceStudio, onOpen: () -> Unit) {
        val runtime = compose.activity.rivet
        val state = RivetState(connection = Connection.Online)
        page {
            CompositionLocalProvider(LocalVoiceStudio provides studio, LocalOpenVoiceSetup provides onOpen) {
                SettingsScreen(runtime, state)
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h3000dp-xxhdpi")
    fun settingsWholePage() {
        var opened = 0
        settings(FakeStudio("Copper")) { opened++ }
        compose.onNodeWithText("Copper").assertIsDisplayed()
        compose.onAllNodesWithText("Turn on").assertCountEquals(1)
        compose.onAllNodesWithText("Open Rivet's app info").assertCountEquals(1)
        compose.mainClock.advanceTimeBy(800)
        shot(compose, compose.activity, "review-settings")
        compose.onNodeWithText("Set up Rivet's voice").performClick()
        compose.onNodeWithText("Rivet's voice").performClick()
        assertEquals(2, opened)
    }

    @Test
    @Config(qualifiers = "w360dp-h3600dp-xxhdpi", fontScale = 1.3f)
    fun settingsOnANarrowPhone() {
        settings(FakeStudio(null)) {}
        compose.onNodeWithText("Not chosen yet").assertIsDisplayed()
        compose.onNodeWithText("Voice model").performScrollTo()
        compose.mainClock.advanceTimeBy(800)
        shot(compose, compose.activity, "review-settings-narrow")
    }
}
