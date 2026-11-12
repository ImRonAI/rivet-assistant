package com.hiron.ren.ui

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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.hiron.ren.R
import com.hiron.ren.core.ChatMessage
import com.hiron.ren.core.RenState
import com.hiron.ren.core.ren
import com.hiron.ren.testing.textApproval
import com.hiron.ren.testing.shot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.hiron.ren.core.Activity as RenActivity

/** Home in the states people see, at the narrowest phone width, saved to build/screens/g2-*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class HomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val approval = textApproval()
    private fun home(state: MutableState<RenState>, fontScale: Float = 1f, voice: Boolean = true) {
        compose.activity.ren.settings.voiceModel = voice
        compose.setContent {
            RenTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    val p = LocalPalette.current
                    Column(Modifier.fillMaxSize().background(p.bg)) {
                        HudHeader(state.value, "Assistant", rule = false)
                        Column(Modifier.weight(1f)) { HomeScreen(compose.activity.ren, state.value, onListen = {}, onReviewApprovals = {}) }
                        Tabs(state.value.approvals.size)
                    }
                }
            }
        }
    }

    @Composable
    private fun Tabs(badge: Int) = PendantTabs(
        listOf(
            PendantTab("ren", "Ren", ImageVector.vectorResource(R.drawable.ic_ren), badge = badge, tag = "ren"),
            PendantTab("activity", "Activity", Icons.Outlined.Timeline),
            PendantTab("settings", "Settings", Icons.Outlined.Tune),
        ),
        "ren",
        {},
    )

    private fun shot(name: String) = shot(compose, compose.activity, name)

    @Test
    fun anIdleHomeGreetsAndOffersTheTalkKey() {
        home(mutableStateOf(RenState()))
        compose.onNodeWithText(STAGE_HINT).assertExists()
        compose.onNodeWithText("Tap the mic or pick a key below.").assertExists()
        compose.onAllNodes(hasContentDescription("Speak to Ren")).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Send")).assertCountEquals(0)
        shot("g2-home-idle")
    }

    @Test
    fun withoutTheVoiceModelTheComposerKeepsOneSendKey() {
        home(mutableStateOf(RenState()), voice = false)
        compose.onAllNodes(hasContentDescription("Send")).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Speak to Ren")).assertCountEquals(0)
        compose.onNodeWithTag(COMPOSER_TAG).performTextInput("Text Dana")
        compose.onAllNodes(hasContentDescription("Send")).assertCountEquals(1)
        shot("g2-home-typed")
    }

    @Test
    fun waitingApprovalsShowABannerAndALargeFontStillFits() {
        home(mutableStateOf(RenState(activity = RenActivity.Waiting, approvals = listOf(approval, approval.copy(id = "a2")))), fontScale = 1.3f)
        compose.onNodeWithText("Ren is waiting on 2 actions").assertExists()
        compose.onNodeWithText("2 waiting on you").assertExists()
        compose.onAllNodesWithText("Review").assertCountEquals(1)
        shot("g2-home-approvals-large-font")
    }

    @Test
    fun aConversationTakesTheWholeScreen() {
        home(
            mutableStateOf(
                RenState(activity = RenActivity.Thinking, messages = listOf(ChatMessage("m1", true, "What's on my calendar tomorrow?"))),
            ),
        )
        // The chat page itself runs in the app's WebView; ChatEventsTest and the on-device run cover what it shows.
        compose.onNodeWithTag(STAGE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CHAT_TAG).assertExists()
        compose.onNodeWithText("New chat").assertExists()
        compose.onNodeWithText(Quick.CatchUp.label).assertDoesNotExist()
        compose.onNodeWithTag(COMPOSER_TAG).assertExists()
    }

    @Test
    fun aVoiceConversationKeepsRenOnStageWithItsTranscriptFolded() {
        home(
            mutableStateOf(
                RenState(voice = true, activity = RenActivity.Listening, messages = listOf(ChatMessage("m1", true, "What's on my calendar tomorrow?"))),
            ),
        )
        compose.onNodeWithTag(STAGE_TAG).assertExists()
        compose.onNodeWithTag(CHAT_TAG).assertDoesNotExist()
        compose.onNodeWithText(Quick.CatchUp.label).assertDoesNotExist()
        shot("g2-home-voice")
        compose.onNodeWithText(TRANSCRIPT).performClick()
        compose.onNodeWithTag(STAGE_TAG).assertExists()
        compose.onNodeWithTag(CHAT_TAG).assertExists()
    }
}
