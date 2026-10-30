package com.hiron.emrys.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hiron.emrys.R
import com.hiron.emrys.core.CallLineText
import com.hiron.emrys.core.ChatMessage
import com.hiron.emrys.core.Connection
import com.hiron.emrys.core.LiveCall
import com.hiron.emrys.core.LiveCallStatus
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.emrys
import com.hiron.emrys.testing.textApproval
import com.hiron.emrys.testing.shot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.hiron.emrys.core.Activity as EmrysActivity

/** Home and the live call plate in the states people see, at the narrowest phone width, saved to build/screens/g2-*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class HomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val approval = textApproval()
    private val now = System.currentTimeMillis()

    private val lines = listOf(
        CallLineText("emrys", "Hi, this is Emrys, Tim's assistant. Who's calling?"),
        CallLineText("caller", "It's Dana from Wasatch Dental, about Friday's cleaning."),
        CallLineText("caller", "Can he do 9 instead of 10?"),
    )
    private val answering = LiveCall("c1", "Dana from Wasatch Dental", "+18015550123", true, now - 83_000, LiveCallStatus.Answering, lines)
    private val ended = answering.copy(
        status = LiveCallStatus.Ended,
        summary = "Dana from Wasatch Dental wants to move Friday's cleaning to 9. She asked for a call back to confirm.",
        outcome = "message_taken",
        durationSeconds = 94,
    )

    private fun home(state: MutableState<EmrysState>, fontScale: Float = 1f, voice: Boolean = true) {
        compose.activity.emrys.settings.voiceModel = voice
        compose.setContent {
            EmrysTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    val p = LocalPalette.current
                    Column(Modifier.fillMaxSize().background(p.bg)) {
                        HudHeader(state.value, "Assistant", rule = false)
                        Column(Modifier.weight(1f)) { HomeScreen(compose.activity.emrys, state.value, onListen = {}, onReviewApprovals = {}) }
                        Tabs(state.value.approvals.size)
                    }
                }
            }
        }
    }

    @Composable
    private fun Tabs(badge: Int) = PendantTabs(
        listOf(
            PendantTab("emrys", "Emrys", ImageVector.vectorResource(R.drawable.emrys_sigil), badge = badge, tag = "emrys"),
            PendantTab("activity", "Activity", Icons.Outlined.Timeline),
            PendantTab("settings", "Settings", Icons.Outlined.Tune),
        ),
        "emrys",
        {},
    )

    private fun shot(name: String) = shot(compose, compose.activity, name)

    @Test
    fun anIdleHomeGreetsAndOffersTheTalkKey() {
        home(mutableStateOf(EmrysState(connection = Connection.Online)))
        compose.onNodeWithText(STAGE_HINT).assertExists()
        compose.onNodeWithText("Tap the mic or pick a key below.").assertExists()
        compose.onAllNodes(hasContentDescription("Speak to Emrys")).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Send")).assertCountEquals(0)
        shot("g2-home-idle")
    }

    @Test
    fun withoutTheVoiceModelTheComposerKeepsOneSendKey() {
        home(mutableStateOf(EmrysState(connection = Connection.Offline)), voice = false)
        compose.onAllNodes(hasContentDescription("Send")).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Speak to Emrys")).assertCountEquals(0)
        compose.onNodeWithTag(COMPOSER_TAG).performTextInput("Text Dana")
        compose.onAllNodes(hasContentDescription("Send")).assertCountEquals(1)
        compose.onNodeWithText("Not connected").assertExists()
        shot("g2-home-offline-typed")
    }

    @Test
    fun theTaskLatchSwitchesTheComposerToABackgroundGoal() {
        home(mutableStateOf(EmrysState(connection = Connection.Online)))
        compose.onNodeWithText("Task").performClick()
        compose.onNodeWithText("Describe the goal for a background task…").assertExists()
        shot("g2-home-task")
        compose.onNodeWithText("Task").performClick()
        compose.onNodeWithText("Ask Emrys anything").assertExists()
    }

    @Test
    fun waitingApprovalsShowABannerAndALargeFontStillFits() {
        home(mutableStateOf(EmrysState(connection = Connection.Online, activity = EmrysActivity.Waiting, approvals = listOf(approval, approval))), fontScale = 1.3f)
        compose.onNodeWithText("Emrys is waiting on 2 actions").assertExists()
        compose.onNodeWithText("2 waiting on you").assertExists()
        compose.onAllNodesWithText("Review").assertCountEquals(1)
        shot("g2-home-approvals-large-font")
    }

    @Test
    fun aShortConversationKeepsEmrysInView() {
        home(
            mutableStateOf(
                EmrysState(
                    connection = Connection.Online,
                    activity = EmrysActivity.Thinking,
                    activeTurn = "t1",
                    messages = listOf(
                        ChatMessage("m1", true, "What's on my calendar tomorrow?"),
                        ChatMessage("m2", false, "You have **two things** tomorrow: the dentist at 10 and lunch with Sam at 12:30.", streaming = true),
                    ),
                ),
            ),
        )
        compose.onNodeWithText("Conversation").assertExists()
        compose.onNodeWithText("Replying…").assertExists()
        shot("g2-home-conversation")
    }

    @Test
    fun aTurnInFlightShowsEmrysThinking() {
        home(
            mutableStateOf(
                EmrysState(
                    connection = Connection.Online,
                    activity = EmrysActivity.Searching,
                    activeTurn = "t1",
                    messages = listOf(
                        ChatMessage("m1", true, "Find a quiet coffee place near the office"),
                        ChatMessage("m2", false, "There are three good ones within a short walk. Blue Anchor opens at 7 [1], and Fieldwork has long tables [2]."),
                        ChatMessage("m3", true, "Book the one with long tables for 10?"),
                    ),
                ),
            ),
        )
        compose.onNodeWithText("Thinking…").assertExists()
        compose.onNodeWithText("Thinking").assertExists()
        shot("g2-home-working")
    }

    @Test
    fun aLiveCallSitsOverTheConversation() {
        home(
            mutableStateOf(
                EmrysState(
                    connection = Connection.Online,
                    activity = EmrysActivity.Working,
                    liveCall = answering,
                    messages = listOf(
                        ChatMessage("m1", true, "Catch me up"),
                        ChatMessage("m2", false, "Nothing new since this morning. Dana from Wasatch Dental is calling now, and I'm taking a message."),
                        ChatMessage("m3", true, "Thanks"),
                        ChatMessage("m4", false, "Sorry, that didn't send.", failed = true),
                    ),
                ),
            ),
        )
        compose.onAllNodesWithText("Take over").assertCountEquals(1)
        shot("g2-home-live-call")
    }

    @Test
    fun theCallPlateInEveryState() {
        var tookOver = 0
        var dismissed = 0
        compose.setContent {
            EmrysTheme {
                val p = LocalPalette.current
                Column(Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    LiveCallCard(answering, onTakeOver = { tookOver++ }, onDismiss = {})
                    LiveCallCard(answering.copy(status = LiveCallStatus.Transferring, lines = emptyList(), verified = false, caller = "+18015550123"), onTakeOver = {}, onDismiss = {})
                    LiveCallCard(ended, onTakeOver = {}, onDismiss = { dismissed++ })
                    LiveCallCard(ended.copy(callId = "c2", takenOver = true, summary = ""), onTakeOver = {}, onDismiss = {}, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        compose.onNodeWithText("Live").assertExists()
        compose.onNodeWithText("Putting it through").assertExists()
        compose.onNodeWithText("Emrys picked up. What's said will show here.").assertExists()
        compose.onNodeWithText("You took it over").assertExists()
        compose.onNodeWithText("No summary.").assertExists()
        compose.onAllNodesWithText("Take over")[0].performClick()
        // Only the answering plate's key works; a call already being put through can't be taken over again.
        compose.onAllNodesWithText("Take over")[1].performClick()
        assertEquals(1, tookOver)
        compose.onAllNodesWithText("Dismiss")[0].performScrollTo().performClick()
        assertEquals(1, dismissed)
        shot("g2-call-plates")
    }

    @Test
    fun theCallPlateFitsWithALargeFont() {
        compose.setContent {
            EmrysTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) {
                    val p = LocalPalette.current
                    Column(Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                        LiveCallCard(answering, onTakeOver = {}, onDismiss = {})
                        LiveCallCard(ended, onTakeOver = {}, onDismiss = {})
                    }
                }
            }
        }
        shot("g2-call-plates-large-font")
    }
}
