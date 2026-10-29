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
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.hiron.rivet.core.Connection
import com.hiron.rivet.core.RivetState
import com.hiron.rivet.testing.textApproval
import com.hiron.rivet.testing.shot
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
                    SectionLabel("Ways to make one")
                    FeatureCard(
                        Icons.Outlined.GraphicEq, "Voice model", "Talk out loud and Rivet answers in its own voice, powered by ElevenLabs.",
                        tone = Tone.Accent, tag = "WHEN YOU TALK" to Tone.Accent,
                        trailing = { RivetToggle(true, { toggled = it }) },
                    )
                    FeatureCard(
                        Icons.Outlined.Psychology, "Text model", "Reads, plans and writes every answer, and asks before it acts.",
                        tag = "ALWAYS ON" to Tone.Cyan, trailing = { RivetToggle(true, null, enabled = false) },
                    )
                    FeatureCard(
                        Icons.Outlined.Mic, "Record a sample", "Record 30 seconds of your own voice", tone = Tone.Neutral, onClick = {},
                        trailing = { androidx.compose.material3.Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = LocalPalette.current.ink2) },
                    )
                    InfoStrip("Previews play through your phone's voice until Rivet's ElevenLabs voice is connected.")
                    InfoStrip("This came up while Rivet was reading a message. Check it carefully.", icon = Icons.Outlined.WarningAmber, tone = Tone.Amber)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tone.entries.forEach { StatusTag(it.name, it) }
                    }
                    TrustFooter("REVOCABLE ANY TIME IN ANDROID SETTINGS")
                    PrimaryKey("Get Started", {})
                    SecondaryKey("Skip voice setup", {})
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextKey("Not now", {})
                        TextKey("Choose Rivet's voice", {}, leading = Icons.Outlined.GraphicEq)
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
    }

    @Test
    fun stageMetersAndInputs() {
        page {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StageFrame(Modifier.fillMaxWidth().height(248.dp), hint = STAGE_HINT) {}
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("RECORDING", tone = Tone.Red, live = true)
                        TimerPill(14)
                    }
                    RivetCard {
                        LevelMeter(listOf(0.2f, 0.5f, 0.8f, 0.4f, 0.95f, 0.6f, 0.3f, 0.7f, 0.5f, 0.35f, 0.2f, 0.1f), Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        HaloTalkKey(TalkState.Idle, {}, "Speak to Rivet")
                        HaloTalkKey(TalkState.Listening, {}, "Stop listening")
                        SendKey({})
                    }
                    RecessedField("", {}, label = "Server address", placeholder = "https://rivet-brain.fly.dev")
                }
            }
            FadeFooter(Modifier.align(Alignment.BottomCenter)) {
                PrimaryKey("Use sample", {})
            }
        }
        compose.onNodeWithContentDescription("Speak to Rivet").assertExists()
        shot(compose, compose.activity, "kit-gallery-meters")
    }
}
