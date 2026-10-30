package com.hiron.emrys.ui

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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
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
import com.hiron.emrys.core.Connection
import com.hiron.emrys.core.EmrysState
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

/** The shared Noir components on one page each, saved to build/screens/kit-gallery*.png for review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1960dp-xxhdpi")
class KitGalleryTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun page(content: @Composable BoxScope.() -> Unit) = compose.setContent {
        EmrysTheme {
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
                HudHeader(EmrysState(connection = Connection.Online, activity = EmrysActivity.Waiting, approvals = listOf(approval, approval)), "Assistant")
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepIndicator(2, 4)
                    StepHeadline("Two models, one Emrys", Modifier.fillMaxWidth())
                    Eyebrow("Voice configuration")
                    HeroTitle("Emrys's voice")
                    SectionLabel("Ways to make one", trailing = "4 ways")
                    FeatureCard(
                        Icons.Outlined.GraphicEq, "Voice model", "Talk out loud and Emrys answers in its own voice, powered by ElevenLabs.",
                        tone = Tone.Accent, tag = "When you talk" to Tone.Accent,
                        trailing = { EmrysToggle(true, { toggled = it }) },
                        nested = { NestedRow("Read replies aloud when I type", true, {}) },
                    )
                    FeatureCard(
                        Icons.Outlined.Psychology, "Text model", "Reads, plans and writes every answer, and asks before it acts.",
                        tag = "Always on" to Tone.Cyan, trailing = { EmrysToggle(true, null, enabled = false) },
                    )
                    FeatureCard(
                        Icons.Outlined.PowerSettingsNew, "Long-press power button", "Hold the power button to call Emrys from anywhere.",
                        tone = Tone.Accent, tag = "Primary" to Tone.Accent, selected = true, onClick = {}, role = Role.RadioButton,
                        trailing = { RadioDot(true) },
                    )
                    FeatureCard(
                        Icons.Outlined.Mic, "Record a sample", "Record 30 seconds of your own voice", tone = Tone.Neutral, onClick = {},
                        trailing = { androidx.compose.material3.Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = LocalPalette.current.ink2) },
                    )
                    InfoStrip("Previews play through your phone's voice until Emrys's ElevenLabs voice is connected.")
                    InfoStrip("This came up while Emrys was reading a message. Check it carefully.", icon = Icons.Outlined.WarningAmber, tone = Tone.Amber)
                    CheckCard("Clean speech", "No heavy background noise or music", "Required", Tone.Neutral)
                    StatusLine("File looks good", Tone.Green, bars = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Tone.entries.forEach { StatusTag(it.name, it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusTag("Best", Tone.Accent, outline = true)
                        Tag("Live", LocalPalette.current.rec, hot = true)
                        Capsule("Listening", LocalPalette.current.rec, live = true)
                        Pip(LocalPalette.current.cy, live = true)
                    }
                    TrustFooter("Revocable any time in Android settings · nothing goes out without your OK")
                    PrimaryKey("Get started", {})
                    PrimaryKey("Continue", {}, style = CtaStyle.Display)
                    PrimaryKey("Finish recording", {}, style = CtaStyle.Display, leading = Icons.Outlined.StopCircle, trailingArrow = false)
                    PrimaryKey("Continue", {}, style = CtaStyle.Display, enabled = false)
                    SecondaryKey("Skip voice setup", {})
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextKey("Not now", {})
                        TextKey("Choose Emrys's voice", {}, leading = Icons.Outlined.GraphicEq, tone = Tone.Cyan)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip("All", true, {})
                        FilterChip("Warm", false, {})
                        FilterChip("Crisp", false, {})
                        FilterChip("Deep", false, {})
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
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Keycap("Turn off", {}, tone = KeyTone.Danger)
                        Keycap("Remove", {}, enabled = false)
                        Keycap("Share", {}, icon = Icons.Outlined.Share, tag = "2")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconKey(Icons.Outlined.Close, "Close", {})
                        IconKey(Icons.Outlined.Mic, "Microphone", {}, ring = LocalPalette.current.rec)
                        EmrysToggle(false, {})
                        CompactToggle(false, {})
                        CompactToggle(true, {})
                        RadioDot(false)
                        RadioDot(true)
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter)) {
                PendantTabs(
                    listOf(
                        PendantTab("emrys", "Emrys", Icons.Outlined.Home, badge = 2, tag = "emrys"),
                        PendantTab("activity", "Activity", Icons.Outlined.Timeline),
                        PendantTab("settings", "Settings", Icons.Outlined.Tune),
                    ),
                    "emrys",
                    {},
                )
            }
        }
        compose.onNodeWithText("Voice model").assertExists()
        shot(compose, compose.activity, "kit-gallery")
        compose.onNodeWithText("Read replies aloud when I type").assertIsOn()
    }

    @Test
    @Config(qualifiers = "w411dp-h2160dp-xxhdpi")
    fun stageMetersAndInputs() {
        var text by mutableStateOf("")
        page {
            Column(Modifier.fillMaxWidth()) {
                StickyHeader("02 / 02", onBack = {})
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StageFrame(Modifier.fillMaxWidth().height(248.dp), hint = STAGE_HINT, mood = LocalPalette.current.cy) {}
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("Recording", tone = Tone.Red, live = true)
                        TimerPill(14, 30)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        HaloTalkKey(TalkState.Idle, {}, "Start recording", large = true)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        HaloTalkKey(TalkState.Listening, {}, "Finish recording", large = true)
                    }
                    EmrysCard {
                        LevelMeter(listOf(0.2f, 0.5f, 0.8f, 0.4f, 0.95f, 0.6f, 0.3f, 0.7f, 0.5f, 0.35f, 0.2f, 0.1f), Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        HaloTalkKey(TalkState.Idle, {}, "Speak to Emrys")
                        HaloTalkKey(TalkState.Listening, {}, "Stop listening")
                        SendKey({})
                        PlayKey(false, {})
                        PlayKey(true, {}, style = PlayStyle.Ghost)
                    }
                    MiniWave(listOf(0.3f, 0.6f, 0.9f, 0.5f, 0.7f, 0.4f, 0.8f, 0.6f, 0.3f, 0.5f, 0.7f, 0.4f, 0.2f, 0.5f), 0.45f)
                    ComposerCard(text, { text = it }, "e.g., A calm, witty radio operator with dry warmth and crisp articulation…", 280)
                    DropZone("Choose audio", "Tap to pick a file from your phone (at least 30 seconds)", {})
                    RecessedField("", {}, label = "Server address", placeholder = "https://emrys-brain.fly.dev")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile(12, "Things done", LocalPalette.current.cy, Modifier.weight(1f))
                        StatTile(2, "Waiting on you", LocalPalette.current.amb, Modifier.weight(1f))
                    }
                    HudCard("Connection", pip = LocalPalette.current.ok, meta = "Online") {
                        Readout("Server", "rivet-assistant.fly.dev", mono = true)
                        Readout("Made by", "Ron AI · hi-ron.com")
                    }
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
        compose.onNodeWithContentDescription("Speak to Emrys").assertExists()
        compose.onNodeWithContentDescription("Back").assertExists()
        shot(compose, compose.activity, "kit-gallery-meters")
    }
}
