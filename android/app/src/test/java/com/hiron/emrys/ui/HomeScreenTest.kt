package com.hiron.emrys.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.hiron.emrys.R
import com.hiron.emrys.core.ChatMessage
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.emrys
import com.hiron.emrys.testing.textApproval
import com.hiron.emrys.testing.shot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.hiron.emrys.core.Activity as EmrysActivity

/** Home in the states people see, at the narrowest phone width, saved to build/screens/g2-*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class HomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val approval = textApproval()
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
        home(mutableStateOf(EmrysState()))
        compose.onNodeWithText(STAGE_HINT).assertExists()
        compose.onNodeWithText("Tap the mic or pick a key below.").assertExists()
        compose.onAllNodes(hasContentDescription("Speak to Emrys")).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Send")).assertCountEquals(0)
        shot("g2-home-idle")
    }

    @Test
    fun withoutTheVoiceModelTheComposerKeepsOneSendKey() {
        home(mutableStateOf(EmrysState()), voice = false)
        compose.onAllNodes(hasContentDescription("Send")).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Speak to Emrys")).assertCountEquals(0)
        compose.onNodeWithTag(COMPOSER_TAG).performTextInput("Text Dana")
        compose.onAllNodes(hasContentDescription("Send")).assertCountEquals(1)
        shot("g2-home-typed")
    }

    @Test
    fun waitingApprovalsShowABannerAndALargeFontStillFits() {
        home(mutableStateOf(EmrysState(activity = EmrysActivity.Waiting, approvals = listOf(approval, approval.copy(id = "a2")))), fontScale = 1.3f)
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
                    activity = EmrysActivity.Done,
                    messages = listOf(
                        ChatMessage("m1", true, "What's on my calendar tomorrow?"),
                        ChatMessage("m2", false, "You have **two things** tomorrow: the dentist at 10 and lunch with Sam at 12:30."),
                    ),
                ),
            ),
        )
        compose.onNodeWithText("Conversation").assertExists()
        compose.onNodeWithText("Thinking…").assertDoesNotExist()
        shot("g2-home-conversation")
    }

    @Test
    fun aTurnInFlightShowsEmrysThinking() {
        home(
            mutableStateOf(
                EmrysState(
                    activity = EmrysActivity.Thinking,
                    messages = listOf(
                        ChatMessage("m1", true, "Find a quiet coffee place near the office"),
                        ChatMessage("m2", false, "There are three good ones within a short walk. Blue Anchor opens at 7 [1], and Fieldwork has long tables [2]."),
                        ChatMessage("m3", true, "Book the one with long tables for 10?"),
                    ),
                ),
            ),
        )
        compose.onNodeWithText("Thinking…").assertExists()
        // The header's status and the line in the chat.
        compose.onAllNodesWithText("Thinking").assertCountEquals(2)
        shot("g2-home-working")
    }
}
