package com.hiron.emrys.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hiron.emrys.core.ApprovalAnswer
import com.hiron.emrys.core.ApprovalRequest
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.LogEntry
import com.hiron.emrys.core.PermAction
import com.hiron.emrys.core.emrys
import com.hiron.emrys.device.ReminderStore
import com.hiron.emrys.device.ReminderStoreFile
import com.hiron.emrys.testing.shot
import com.hiron.emrys.testing.textApproval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private var answered: Pair<String, ApprovalAnswer>? = null

    private fun page(content: @Composable () -> Unit) = compose.setContent {
        EmrysTheme {
            Box(Modifier.fillMaxSize().background(LocalPalette.current.bg)) { content() }
        }
    }

    private fun sheet(request: ApprovalRequest) = page {
        ApprovalSheet(request, onAnswer = { id, a -> answered = id to a }, onDismiss = {})
    }

    private val text = textApproval()

    private val buy = ApprovalRequest("a2", PermAction.Purchases, "Buy a local San Diego number on your Telnyx account for $1.00 a month")

    @Test
    fun oneTextToApprove() {
        sheet(text)
        compose.onNodeWithText(text.details).assertIsDisplayed()
        compose.onNodeWithText("Emrys wants to: Send messages").assertIsDisplayed()
        compose.onAllNodesWithText("Approve").assertCountEquals(1)
        shot(compose, compose.activity, "review-approval-text")
        compose.onNodeWithText("Deny").performClick()
        assertEquals("a1" to ApprovalAnswer(false), answered)
    }

    @Test
    fun alwaysAllowIsOffUntilTicked() {
        sheet(text)
        val always = compose.onNode(isToggleable() and hasText("Always allow: Send messages"))
        always.assertIsOff()
        always.performClick().assertIsOn()
        compose.onNodeWithText("Always allow").assertIsDisplayed()
        shot(compose, compose.activity, "review-approval-text-ticked")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.3f)
    fun aPurchaseOnANarrowPhone() {
        sheet(buy)
        compose.onNodeWithText(buy.details).assertIsDisplayed()
        compose.onNodeWithText("Emrys wants to: Purchases").assertIsDisplayed()
        shot(compose, compose.activity, "review-approval-purchase-narrow")
    }

    @Test
    fun permissionsInSettingsKeepAnAlwaysAllow() {
        val runtime = compose.activity.emrys
        page { SettingsScreen(runtime) }
        PermAction.entries.forEach { compose.onNode(isToggleable() and hasText(it.label)).assertExists() }
        compose.onNode(isToggleable() and hasText("Purchases")).performScrollTo().assertIsOff().performClick().assertIsOn()
        assertTrue(runtime.settings.granted(PermAction.Purchases))
        assertFalse(runtime.settings.granted(PermAction.Deploy))
    }

    private fun activityState(): EmrysState {
        val now = System.currentTimeMillis()
        val min = 60_000L
        val day = 24 * 60 * min
        return EmrysState(
            log = listOf(
                LogEntry(now - day - 28 * min, "error", "Couldn't reach the ElevenLabs agent. Check your connection and try again."),
                LogEntry(now - 50 * min, "call", "Incoming call from +18015550123"),
                LogEntry(now - 38 * min, "device", "device_status: done"),
                LogEntry(now - 35 * min, "approval", "Approved: Text Dana at +1 801-555-0123: “On my way”"),
                LogEntry(now - 34 * min, "device", "sms_send: done"),
                LogEntry(now - 2 * min, "approval", "Always allowed: Send messages"),
            ),
        )
    }

    @Test
    fun activityInPlainWords() {
        val context = compose.activity
        ReminderStore(File(context.filesDir, ReminderStoreFile)).put("r1", "Call the dentist back", System.currentTimeMillis() + 3_600_000)
        val runtime = context.emrys
        val state = activityState()
        page { ActivityScreen(runtime, state) }
        compose.onNodeWithText("Sent a text").assertExists()
        compose.onNodeWithText("Always allowed: Send messages").assertExists()
        compose.onNodeWithText("Call the dentist back").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(800)
        shot(compose, compose.activity, "review-activity")
    }

    @Test
    @Config(qualifiers = "w360dp-h1400dp-xxhdpi", fontScale = 1.3f)
    fun activityOnANarrowPhone() {
        val runtime = compose.activity.emrys
        val state = activityState()
        page { ActivityScreen(runtime, state) }
        compose.mainClock.advanceTimeBy(800)
        shot(compose, compose.activity, "review-activity-narrow")
    }

    private fun settings() {
        val runtime = compose.activity.emrys
        page { SettingsScreen(runtime) }
    }

    @Test
    @Config(qualifiers = "w411dp-h3800dp-xxhdpi")
    fun settingsWholePage() {
        settings()
        compose.onAllNodesWithText("Turn on").assertCountEquals(1)
        compose.onAllNodesWithText("Open Emrys's app info").assertCountEquals(1)
        compose.mainClock.advanceTimeBy(800)
        shot(compose, compose.activity, "review-settings")
    }

    @Test
    @Config(qualifiers = "w360dp-h5600dp-xxhdpi", fontScale = 1.3f)
    fun settingsOnANarrowPhone() {
        settings()
        compose.onNodeWithText("Talk key").performScrollTo()
        compose.mainClock.advanceTimeBy(800)
        shot(compose, compose.activity, "review-settings-narrow")
    }
}
