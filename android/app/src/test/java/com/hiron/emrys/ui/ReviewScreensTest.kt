package com.hiron.emrys.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hiron.emrys.core.AlertBlock
import com.hiron.emrys.core.ApprovalRequest
import com.hiron.emrys.core.Connection
import com.hiron.emrys.core.Current
import com.hiron.emrys.core.Decision
import com.hiron.emrys.core.DenyCandidate
import com.hiron.emrys.core.LogEntry
import com.hiron.emrys.core.NoAllow
import com.hiron.emrys.core.PermissionGroups
import com.hiron.emrys.core.Protocol
import com.hiron.emrys.core.Recommendation
import com.hiron.emrys.core.Recommended
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.RuleOffer
import com.hiron.emrys.core.RuleSet
import com.hiron.emrys.core.RuleTarget
import com.hiron.emrys.core.StandingRule
import com.hiron.emrys.core.Tier
import com.hiron.emrys.core.emrys
import com.hiron.emrys.device.ForwardMode
import com.hiron.emrys.device.ReminderStore
import com.hiron.emrys.device.ReminderStoreFile
import com.hiron.emrys.testing.shot
import com.hiron.emrys.testing.textApproval
import com.hiron.emrys.voice.LocalVoiceStudio
import com.hiron.emrys.voice.SampleRecorder
import com.hiron.emrys.voice.VoiceChoice
import com.hiron.emrys.voice.VoicePreset
import com.hiron.emrys.voice.VoiceSample
import com.hiron.emrys.voice.VoiceStudio
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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

    private var answered: Pair<String, Map<String, Protocol.Answer>>? = null

    private fun page(content: @Composable () -> Unit) = compose.setContent {
        EmrysTheme {
            Box(Modifier.fillMaxSize().background(LocalPalette.current.bg)) { content() }
        }
    }

    private val names = mapOf("+18015550100" to "Dana")

    private fun sheet(approvals: List<ApprovalRequest>) = page {
        ApprovalSheet(approvals, onAnswer = { lane, a -> answered = lane to a }, onDismiss = {}, names = names)
    }

    private val text = textApproval()

    private val buy = ApprovalRequest(
        "a2", "default", "t1", "telnyx_execute", Tier.Purchase, "Create a number order on your Telnyx account",
        listOf(AlertBlock.Note("- Create a number order\nRuns on your Telnyx account.")),
        "You asked: “Get me a local number in San Diego”",
        "call_tool('CreateNumberOrder', {'phone_numbers': [{'phone_number': '+17605550142'}]})", true,
        listOf(ApprovalRequest.Operation("CreateNumberOrder", "POST", "Create a number order")),
        RuleOffer(
            RuleTarget("telnyx_execute", mapOf("operation" to "CreateNumberOrder")), "Create a number order on your Telnyx account",
            allow = false, deny = true, noAllow = NoAllow.Purchase,
        ),
        null,
    )

    @Test
    fun oneTextToApprove() {
        sheet(listOf(text))
        compose.onNodeWithText("Text Dana: “Friday at 9 works!”").assertIsDisplayed()
        compose.onNodeWithText("You asked: “Text Dana that Friday at 9 works”").assertIsDisplayed()
        compose.onNodeWithText("Dana · +1 801-555-0100").assertIsDisplayed()
        compose.onAllNodesWithText("Approve").assertCountEquals(1)
        compose.onAllNodesWithText("sms_send").assertCountEquals(0)
        compose.onNodeWithText("Don't ask again about Texts to Dana").performScrollTo()
        shot(compose, compose.activity, "review-approval-text")
        compose.onNodeWithText("Don't ask again about Texts to Dana").performClick()
        compose.onNodeWithText("Always allow").assertIsDisplayed()
        compose.onNodeWithText("Always deny").assertIsDisplayed()
        shot(compose, compose.activity, "review-approval-text-ticked")
        compose.onNodeWithText("Always deny").performClick()
        assertEquals("default" to mapOf("a1" to Protocol.Answer(false, Decision.Deny)), answered)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.3f)
    fun oneTextOnANarrowPhone() {
        sheet(listOf(text))
        compose.onNodeWithText("Text Dana: “Friday at 9 works!”").assertIsDisplayed()
        shot(compose, compose.activity, "review-approval-text-narrow")
    }

    @Test
    fun aPurchaseShowsItsRiskAndDetails() {
        sheet(listOf(buy))
        compose.onNodeWithText("Spends money · you can't undo it").assertIsDisplayed()
        compose.onNodeWithText("Approve with unlock").assertIsDisplayed()
        compose.onNodeWithText("Emrys always asks before spending money, so this works only with Deny.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Technical details").performClick()
        compose.onNodeWithText("telnyx_execute").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(600)
        shot(compose, compose.activity, "review-approval-purchase")
        compose.onNodeWithText("If I say no, don't ask again about Create a number order on your Telnyx account").performClick()
        compose.onNodeWithText("Approve with unlock").assertIsDisplayed()
        compose.onNodeWithText("Always deny").performClick()
        assertEquals("default" to mapOf("a2" to Protocol.Answer(false, Decision.Deny)), answered)
    }

    @Test
    fun afterReadingAMessageAlwaysAllowIsOff() {
        sheet(listOf(textApproval(untrusted = true)))
        compose.onNodeWithText("This came up while Emrys was reading a message, an email or a web page. Check it carefully.").assertIsDisplayed()
        compose.onNodeWithText("If I say no, don't ask again about Texts to Dana").performScrollTo().performClick()
        compose.onNodeWithText("Emrys read a message, an email or a web page while working on this, so it can't be always allowed right now. This works only with Deny.")
            .assertIsDisplayed()
        compose.onAllNodesWithText("Always allow").assertCountEquals(0)
        compose.onNodeWithText("Approve").assertIsDisplayed()
        shot(compose, compose.activity, "review-approval-untrusted")
        compose.onNodeWithText("Approve").performClick()
        assertEquals("default" to mapOf("a1" to Protocol.Answer(true)), answered)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.3f)
    fun severalAtOnceOnANarrowPhone() {
        val email = ApprovalRequest(
            "a3", "default", "t1", "email_send", Tier.Send, "Email dana@wasatchdental.com: “Friday cleaning”",
            listOf(
                AlertBlock.Field("To", "dana@wasatchdental.com"), AlertBlock.Field("Subject", "Friday cleaning"),
                AlertBlock.Quote("Message", "Confirmed for Friday at 9."),
            ),
            null,
            """{"to": "dana@wasatchdental.com", "subject": "Friday cleaning", "body": "Confirmed for Friday at 9."}""",
            false, emptyList(), null, null,
        )
        sheet(listOf(text, email, buy))
        compose.onNodeWithText("Emrys wants to do 3 things:").assertIsDisplayed()
        compose.onNodeWithText("Send answers").assertIsDisplayed()
        shot(compose, compose.activity, "review-approval-batch-narrow")
        compose.onNodeWithText("Deny all").performClick()
        assertEquals(setOf(false), answered!!.second.values.map { it.approved }.toSet())
    }

    private fun rulesState(connection: Connection) = EmrysState(
        connection = connection,
        names = names,
        rules = RuleSet(
            4,
            listOf(
                StandingRule("sms_send?to=%2B18015550100", RuleTarget("sms_send", mapOf("to" to "+18015550100")), Decision.Allow, "Texts to +18015550100", 1_761_700_000.0),
                StandingRule("screen_act?label=Pay%20now", RuleTarget("screen_act", mapOf("label" to "Pay now")), Decision.Deny, "Tapping “Pay now” in apps", 1_761_800_000.0),
            ),
        ),
        removingRules = setOf("screen_act?label=Pay%20now"),
    )

    @Test
    fun approvalRulesInSettings() {
        val runtime = compose.activity.emrys
        page { Box(Modifier.padding(20.dp)) { EmrysCard { ApprovalRules(runtime, rulesState(Connection.Online)) } } }
        compose.onNodeWithText("Texts to Dana").assertIsDisplayed()
        compose.onNodeWithText("Always allow").assertIsDisplayed()
        compose.onNodeWithText("Always deny").assertIsDisplayed()
        compose.onNodeWithText("Removing…").assertIsDisplayed()
        compose.onNodeWithText("+1 801-555-0100 · since", substring = true).assertIsDisplayed()
        shot(compose, compose.activity, "review-settings-rules")
    }

    @Test
    fun approvalRulesOffline() {
        val runtime = compose.activity.emrys
        page { Box(Modifier.padding(20.dp)) { EmrysCard { ApprovalRules(runtime, rulesState(Connection.Offline).copy(removingRules = emptySet())) } } }
        compose.onNodeWithText("Connect to Emrys to change rules.").assertIsDisplayed()
        shot(compose, compose.activity, "review-settings-rules-offline")
    }

    @Test
    fun noApprovalRulesYet() {
        val runtime = compose.activity.emrys
        page { Box(Modifier.padding(20.dp)) { EmrysCard { ApprovalRules(runtime, EmrysState(connection = Connection.Online)) } } }
        compose.onNodeWithText("No rules yet. When Emrys asks, tick “Don't ask again” to make one.").assertIsDisplayed()
    }

    private val recommendations = Recommended.list(
        Current(
            autoConnect = false, speakInOverlay = true, offerReplies = false, answeringMode = ForwardMode.Always, forwardingAllCalls = true,
            missingPermissions = PermissionGroups.ALL.take(2), assistantHeld = false,
            denyCandidates = listOf(DenyCandidate(RuleTarget("phone_call", mapOf("number" to "+18015550199")), "Calls to +18015550199", 4)),
        ),
    )

    @Test
    fun recommendedSettingsSheet() {
        var applied: List<Recommendation>? = null
        page { RecommendedSheet(recommendations, onApply = { applied = it }, onDismiss = {}) }
        compose.onNodeWithText("Emrys suggests 7 changes").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(600)
        shot(compose, compose.activity, "review-recommended-sheet")
        compose.onNodeWithContentDescription("Quiet assistant overlay").performClick()
        compose.onNodeWithText("Apply 6 changes").performClick()
        assertEquals(6, applied?.size)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.3f)
    fun recommendedSettingsOnANarrowPhone() {
        page { RecommendedSheet(recommendations, onApply = {}, onDismiss = {}) }
        compose.mainClock.advanceTimeBy(600)
        shot(compose, compose.activity, "review-recommended-sheet-narrow")
    }

    @Test
    fun nothingToRecommend() {
        var dismissed = false
        page { RecommendedSheet(emptyList(), onApply = {}, onDismiss = { dismissed = true }) }
        compose.onNodeWithText("Everything already matches Emrys's recommendations.").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        assertTrue(dismissed)
    }

    private fun activityState(): EmrysState {
        val now = System.currentTimeMillis()
        val min = 60_000L
        val day = 24 * 60 * min
        return EmrysState(
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
        val runtime = context.emrys
        val state = activityState()
        page { ActivityScreen(runtime, state) }
        compose.onNodeWithText("Sent a text").assertExists()
        compose.onNodeWithText("Emrys's model is busy. Trying again in 8s.").assertExists()
        compose.onNode(hasText("Dana from Wasatch Dental", substring = true)).assertExists()
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
        val runtime = compose.activity.emrys
        val state = EmrysState(connection = Connection.Online)
        page {
            CompositionLocalProvider(LocalVoiceStudio provides studio, LocalOpenVoiceSetup provides onOpen) {
                SettingsScreen(runtime, state)
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h3800dp-xxhdpi")
    fun settingsWholePage() {
        var opened = 0
        settings(FakeStudio("Copper")) { opened++ }
        compose.onNodeWithText("Copper").assertIsDisplayed()
        compose.onAllNodesWithText("Turn on").assertCountEquals(1)
        compose.onAllNodesWithText("Open Emrys's app info").assertCountEquals(1)
        compose.mainClock.advanceTimeBy(800)
        shot(compose, compose.activity, "review-settings")
        compose.onNodeWithText("Emrys's voice").performClick()
        assertEquals(1, opened)
    }

    @Test
    @Config(qualifiers = "w360dp-h5600dp-xxhdpi", fontScale = 1.3f)
    fun settingsOnANarrowPhone() {
        settings(FakeStudio(null)) {}
        compose.onNodeWithText("Not chosen yet").assertIsDisplayed()
        compose.onNodeWithText("Talk key").performScrollTo()
        compose.mainClock.advanceTimeBy(800)
        shot(compose, compose.activity, "review-settings-narrow")
    }
}
