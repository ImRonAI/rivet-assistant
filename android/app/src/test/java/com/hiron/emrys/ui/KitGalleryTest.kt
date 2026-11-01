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
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.testing.textApproval
import com.hiron.emrys.testing.shot
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
@Config(sdk = [35], qualifiers = "w411dp-h1760dp-xxhdpi")
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
        page {
            Column(Modifier.fillMaxWidth()) {
                HudHeader(EmrysState(activity = EmrysActivity.Waiting, approvals = listOf(approval, approval.copy(id = "a2"))), "Assistant")
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepIndicator(2, 4)
                    Eyebrow("Voice configuration")
                    EmrysCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconWell(Icons.Outlined.GraphicEq, tone = Tone.Accent)
                            Readout("Voice model", "When you talk", Modifier.weight(1f))
                            EmrysToggle(true, {})
                        }
                    }
                    EmrysCard(selected = true, onClick = {}) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconWell(Icons.Outlined.Psychology, tone = Tone.Cyan, selected = true)
                            Readout("Text model", "Always on", Modifier.weight(1f))
                            EmrysToggle(true, null, enabled = false)
                        }
                    }
                    InfoStrip("Tapping below will open Android Assistant settings to select Emrys.")
                    InfoStrip("This came up while Emrys was reading a message. Check it carefully.", icon = Icons.Outlined.WarningAmber, tone = Tone.Amber)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tone.entries.forEach { StatusTag(it.name, it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusTag("Best", Tone.Accent, outline = true)
                        Capsule("Listening", LocalPalette.current.rec, live = true)
                    }
                    TrustFooter("Revocable any time in Android settings")
                    PrimaryKey("Get started", {})
                    SecondaryKey("Customize later in Settings", {})
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextKey("Not now", {})
                        TextKey("Open settings", {}, leading = Icons.Outlined.GraphicEq)
                    }
                }
                ChipRail {
                    listOf("All" to true, "Calls" to false, "Texts" to false, "Email" to false).forEach { (label, on) ->
                        item { FilterChip(label, on, {}) }
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Keycap("Review", {}, tone = KeyTone.Accent)
                        Keycap("Dismiss", {})
                        SysKey("Details", true, {})
                        SysKey("Type", false, {})
                        RadioDot(true)
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
                    Eyebrow("Listening", tone = Tone.Red, live = true)
                    EmrysCard {
                        LevelMeter(listOf(0.2f, 0.5f, 0.8f, 0.4f, 0.95f, 0.6f, 0.3f, 0.7f, 0.5f, 0.35f, 0.2f, 0.1f), Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        HaloTalkKey(TalkState.Idle, {}, "Speak to Emrys")
                        HaloTalkKey(TalkState.Listening, {}, "Stop listening")
                        SendKey({})
                    }
                    RecessedField("", {}, label = "Server address", placeholder = "https://emrys-brain.fly.dev")
                }
            }
            FadeFooter(Modifier.align(Alignment.BottomCenter)) {
                PrimaryKey("Connect", {})
            }
        }
        compose.onNodeWithContentDescription("Speak to Emrys").assertExists()
        shot(compose, compose.activity, "kit-gallery-meters")
    }
}
