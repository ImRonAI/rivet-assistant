package com.hiron.rivet.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.RivetState
import com.hiron.rivet.testing.textApproval
import com.hiron.rivet.testing.shot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.hiron.rivet.core.Activity as RivetActivity

/** The shared v3 components on one page each, saved to build/screens/kit-gallery*.png for review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1760dp-xxhdpi")
class KitGalleryTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun page(content: @Composable BoxScope.() -> Unit) = compose.setContent {
        RivetTheme {
            val p = LocalPalette.current
            Box(Modifier.fillMaxSize().background(p.bg)) { content() }
        }
    }

    private val approval = textApproval()

    @Test
    fun cardsKeysAndChips() {
        var toggled = false
        page {
            Column(Modifier.fillMaxWidth()) {
                HudHeader(RivetState(connection = Connection.Online, activity = RivetActivity.Waiting, approvals = listOf(approval, approval)), "Assistant")
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepIndicator(2, 4)
                    StepHeadline("TWO MODELS, ONE RIVET", Modifier.fillMaxWidth())
                    Eyebrow("VOICE CONFIGURATION")
                    HeroTitle("RIVET'S VOICE")
                    SectionLabel("Ways to make one", trailing = "4 WAYS")
                    FeatureCard(
                        Icons.Outlined.GraphicEq, "Voice model", "Talk out loud and Rivet answers in its own voice, powered by ElevenLabs.",
                        tone = Tone.Accent, tag = "WHEN YOU TALK" to Tone.Accent,
                        trailing = { RivetToggle(true, { toggled = it }) },
                        nested = { NestedRow("Read replies aloud when I type", true, {}) },
                    )
                    FeatureCard(
                        Icons.Outlined.Psychology, "Text model", "Reads, plans and writes every answer, and asks before it acts.",
                        tag = "ALWAYS ON" to Tone.Cyan, trailing = { RivetToggle(true, null, enabled = false) },
                    )
                    FeatureCard(
                        Icons.Outlined.PowerSettingsNew, "Long-press power button", "Hold the power button to call Rivet from anywhere.",
                        tone = Tone.Accent, tag = "PRIMARY" to Tone.Accent, selected = true, onClick = {}, role = Role.RadioButton,
                        trailing = { RadioDot(true) },
                    )
                    FeatureCard(
                        Icons.Outlined.Mic, "Record a sample", "Record 30 seconds of your own voice", tone = Tone.Neutral, onClick = {},
                        trailing = { androidx.compose.material3.Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = LocalPalette.current.ink2) },
                    )
                    InfoStrip("Previews play through your phone's voice until Rivet's ElevenLabs voice is connected.")
                    InfoStrip("This came up while Rivet was reading a message. Check it carefully.", icon = Icons.Outlined.WarningAmber, tone = Tone.Amber)
                    CheckCard("Clean speech", "No heavy background noise or music", "REQUIRED", Tone.Neutral)
                    StatusLine("File looks good", Tone.Green, bars = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tone.entries.forEach { StatusTag(it.name, it) }
                        StatusTag("BEST", Tone.Accent, outline = true)
                    }
                    TrustFooter("REVOCABLE ANY TIME IN ANDROID SETTINGS · NOTHING GOES OUT WITHOUT YOUR OK")
                    PrimaryKey("Get Started", {})
                    PrimaryKey("Continue", {}, style = CtaStyle.Display)
                    PrimaryKey("Finish recording", {}, style = CtaStyle.Display, leading = Icons.Outlined.StopCircle, trailingArrow = false)
                    PrimaryKey("Continue", {}, style = CtaStyle.Display, enabled = false)
                    SecondaryKey("Skip voice setup", {})
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextKey("Not now", {})
                        TextKey("Choose Rivet's voice", {}, leading = Icons.Outlined.GraphicEq, tone = Tone.Cyan)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip("ALL", true, {})
                        FilterChip("WARM", false, {})
                        FilterChip("CRISP", false, {})
                        FilterChip("DEEP", false, {})
                    }
                }
                ChipRail {
                    item { PillChip("Task", {}, leading = Icons.Outlined.Bolt, selected = true) }
                    listOf("Catch me up", "My day", "Texts", "Calls").forEach { item { PillChip(it, {}) } }
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Segmented(listOf(1 to "Unanswered calls", 2 to "Every call"), 1, {})
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Keycap("Review", {}, tone = KeyTone.Accent)
                        Keycap("Dismiss", {})
                        SysKey("Details", true, {})
                        SysKey("Type", false, {})
                        CompactToggle(false, {})
                        RadioDot(false)
                    }
                }
            }
        }
        compose.onNodeWithText("Voice model").assertExists()
        shot(compose, compose.activity, "kit-gallery")
        compose.onNodeWithText("Read replies aloud when I type").assertIsOn()
    }

    @Test
    fun stageMetersAndInputs() {
        var text by mutableStateOf("")
        page {
            Column(Modifier.fillMaxWidth()) {
                StickyHeader("02 / 02", onBack = {})
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StageFrame(Modifier.fillMaxWidth().height(248.dp), hint = STAGE_HINT, mood = LocalPalette.current.cy) {}
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("RECORDING", tone = Tone.Red, live = true)
                        TimerPill(14, 30)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        HaloTalkKey(TalkState.Idle, {}, "Start recording", large = true)
                    }
                    RivetCard {
                        LevelMeter(listOf(0.2f, 0.5f, 0.8f, 0.4f, 0.95f, 0.6f, 0.3f, 0.7f, 0.5f, 0.35f, 0.2f, 0.1f), Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        HaloTalkKey(TalkState.Idle, {}, "Speak to Rivet")
                        HaloTalkKey(TalkState.Listening, {}, "Stop listening")
                        SendKey({})
                        PlayKey(false, {})
                        PlayKey(true, {}, style = PlayStyle.Ghost)
                    }
                    MiniWave(listOf(0.3f, 0.6f, 0.9f, 0.5f, 0.7f, 0.4f, 0.8f, 0.6f, 0.3f, 0.5f, 0.7f, 0.4f, 0.2f, 0.5f), 0.45f)
                    ComposerCard(text, { text = it }, "e.g., A calm, witty radio operator with dry warmth and crisp articulation…", 280)
                    DropZone("CHOOSE AUDIO", "Tap to pick a file from your phone (at least 30 seconds)", {})
                    RecessedField("", {}, label = "Server address", placeholder = "https://rivet-brain.fly.dev")
                    StatTile(12, "Things done", LocalPalette.current.cy)
                }
            }
            FadeFooter(Modifier.align(Alignment.BottomCenter)) {
                PrimaryKey("Use sample", {}, style = CtaStyle.Display)
            }
        }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("x".repeat(300))
        compose.waitForIdle()
        assertEquals(280, text.length)
        compose.onNodeWithText("280 / 280").assertExists()
        compose.onNodeWithContentDescription("Speak to Rivet").assertExists()
        compose.onNodeWithContentDescription("Back").assertExists()
        shot(compose, compose.activity, "kit-gallery-meters")
    }
}
