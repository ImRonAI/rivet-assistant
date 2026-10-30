package com.hiron.emrys.voice

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hiron.emrys.testing.textApproval
import com.hiron.emrys.core.Connection
import com.hiron.emrys.core.EmrysState
import com.hiron.emrys.core.EmrysStore
import com.hiron.emrys.core.ServerEvent
import com.hiron.emrys.ui.ASSIST_FIELD_TAG
import com.hiron.emrys.ui.ASSIST_PANEL_TAG
import com.hiron.emrys.ui.ASSIST_SCRIM_TAG
import com.hiron.emrys.ui.AssistOverlay
import com.hiron.emrys.ui.AssistUi
import com.hiron.emrys.ui.EmrysTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The assistant overlay over another app in every state it can be in, built from real runtime states through
 * [AssistModel] and saved to build/screens/assist-*.png for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AssistOverlayTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val asked = "What's on my calendar tomorrow afternoon?"
    private val mine = AssistLocal(turnId = "t1", asked = asked)
    private val sent = EmrysStore.userSent(EmrysStore.connection(EmrysState(), Connection.Online), "t1", asked)

    private val speech = listOf(0.08f, 0.22f, 0.41f, 0.66f, 0.52f, 0.31f, 0.74f, 0.88f, 0.61f, 0.37f, 0.18f, 0.29f, 0.57f, 0.81f, 0.69f, 0.44f, 0.26f, 0.12f, 0.35f, 0.58f, 0.47f, 0.63f, 0.39f, 0.62f)

    private fun EmrysState.then(vararg events: ServerEvent) = events.fold(this) { s, e -> EmrysStore.reduce(s, e, 1) }

    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = File(System.getProperty("emrys.screenshots") ?: "build/screens").apply { mkdirs() }
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { decor.draw(android.graphics.Canvas(bitmap)) }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun everyStateRendersOverAnotherApp() {
        var ui by mutableStateOf(AssistModel.ui(AssistLocal(listening = true, partial = "What's on my calendar tomorrow aft", level = 0.62f), sent))
        compose.setContent { EmrysTheme { Box(Modifier.fillMaxSize()) { SomeOtherApp(); AssistOverlay(ui) } } }

        // A second of speech, so the meter has a history to show.
        speech.forEach { level ->
            ui = AssistModel.ui(AssistLocal(listening = true, partial = "What's on my calendar tomorrow aft", level = level), sent)
            compose.waitForIdle()
        }
        compose.onNodeWithContentDescription("Stop listening").assertIsDisplayed()
        compose.onNodeWithText("What's on my calendar tomorrow aft", substring = true).assertIsDisplayed()
        shot("assist-listening")

        ui = AssistModel.ui(mine, sent.then(ServerEvent.Searching(listOf("calendar tomorrow"), "default")))
        compose.onNodeWithText("Searching").assertIsDisplayed()
        shot("assist-working")

        val streaming = sent.then(ServerEvent.TextDelta("Tomorrow afternoon you have a dentist cleaning at 2 PM, then ", "default"))
        ui = AssistModel.ui(mine, streaming)
        compose.onNodeWithText("dentist cleaning", substring = true).assertIsDisplayed()
        shot("assist-replying")

        ui = AssistModel.ui(
            mine,
            streaming.then(
                ServerEvent.TurnCompleted(
                    "t1", "default",
                    "Tomorrow afternoon you have a dentist cleaning at 2 PM, then Maya's soccer practice at 5:30 PM at Liberty Park.",
                    0, null,
                ),
            ),
        )
        compose.onNodeWithText("Done").assertIsDisplayed()
        shot("assist-done")

        ui = AssistModel.ui(mine, sent.then(ServerEvent.TurnFailed("t1", "default", "the brain is offline")))
        compose.onNodeWithText("I couldn't finish that: the brain is offline").assertIsDisplayed()
        shot("assist-error")

        val approval = textApproval(turn = "t2")
        val texting = EmrysStore.userSent(EmrysStore.connection(EmrysState(), Connection.Online), "t2", "Text Dana that Friday at 9 works")
            .then(
                ServerEvent.TextDelta("I'll text Dana: \"Friday at 9 works!\"", "default"),
                ServerEvent.ApprovalsRequested("t2", "default", listOf(approval), false),
            )
        ui = AssistModel.ui(AssistLocal(turnId = "t2", asked = "Text Dana that Friday at 9 works"), texting)
        compose.onNodeWithText("Review").assertIsDisplayed()
        shot("assist-waiting")

        ui = AssistModel.ui(AssistLocal(needsMic = true), EmrysState())
        compose.onNodeWithText("Allow mic").assertIsDisplayed()
        shot("assist-mic")

        ui = AssistModel.ui(AssistLocal(typing = true), EmrysState())
        compose.onNodeWithTag(ASSIST_FIELD_TAG).assertIsDisplayed()
        shot("assist-typing")

        ui = AssistModel.ui(AssistLocal(locked = true), EmrysState())
        compose.onNodeWithText("Unlock and open Emrys").assertIsDisplayed()
        shot("assist-locked")
    }

    @Test
    fun theScrimClosesItTheKeysActAndTypedTextIsSent() {
        var ui by mutableStateOf(AssistModel.ui(AssistLocal(), EmrysState()))
        val calls = mutableListOf<String>()
        compose.setContent {
            EmrysTheme {
                AssistOverlay(
                    ui,
                    onMic = { calls += "mic" },
                    onTyping = { calls += "typing:$it" },
                    onSend = { calls += "send:$it" },
                    onOpen = { calls += "open" },
                    onDismiss = { calls += "dismiss" },
                )
            }
        }
        compose.onNodeWithTag(ASSIST_PANEL_TAG).performClick()
        compose.onNodeWithContentDescription("Speak to Emrys").performClick()
        compose.onNodeWithText("Type").performClick()
        compose.onNodeWithText("Open Emrys").performClick()
        compose.onNodeWithTag(ASSIST_SCRIM_TAG).performTouchInput { click(topCenter.copy(y = 40f)) }
        assertEquals(listOf("mic", "typing:true", "open", "dismiss"), calls)

        calls.clear()
        ui = ui.copy(typing = true)
        compose.onNodeWithTag(ASSIST_FIELD_TAG).performTextInput("Remind me to stretch at 3")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("send:Remind me to stretch at 3"), calls)
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    fun nothingClipsOnANarrowPhoneWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        val approval = textApproval(turn = "t2")
        val waiting = EmrysStore.userSent(EmrysStore.connection(EmrysState(), Connection.Offline), "t2", "Text Dana that Friday at 9 works")
            .then(ServerEvent.ApprovalsRequested("t2", "default", listOf(approval), false))
        var ui by mutableStateOf(AssistModel.ui(AssistLocal(listening = true, partial = "Text Dana that Friday at 9", level = 0.5f), EmrysState()))
        compose.setContent { EmrysTheme { Box(Modifier.fillMaxSize()) { SomeOtherApp(); AssistOverlay(ui) } } }
        speech.forEach { level ->
            ui = AssistModel.ui(AssistLocal(listening = true, partial = "Text Dana that Friday at 9", level = level), EmrysState())
            compose.waitForIdle()
        }
        compose.onNodeWithText("Open Emrys").assertIsDisplayed()
        shot("assist-narrow-listening")

        ui = AssistModel.ui(AssistLocal(turnId = "t2", asked = "Text Dana that Friday at 9 works"), waiting)
        compose.onNodeWithText("Review").assertIsDisplayed()
        shot("assist-narrow-waiting")

        ui = AssistModel.ui(AssistLocal(needsMic = true), EmrysState())
        compose.onNodeWithText("Allow mic").assertIsDisplayed()
        compose.onNodeWithText("Open Emrys").assertIsDisplayed()
        shot("assist-narrow-mic")
    }

    @Test
    fun theIdleOverlayInvitesAQuestion() {
        compose.setContent { EmrysTheme { AssistOverlay(AssistUi(com.hiron.emrys.ui.AssistPhase.Ready, status = "READY")) } }
        compose.onNodeWithText("Ask Emrys anything").assertIsDisplayed()
        compose.onNodeWithText("Ready").assertIsDisplayed()
    }
}

/** A stand-in for whatever app the user had open: a light inbox, so the scrim and plate read against it. */
@Composable
private fun SomeOtherApp() {
    Column(Modifier.fillMaxSize().background(Color(0xFFF4F5F7))) {
        Box(Modifier.fillMaxWidth().height(132.dp).background(Color(0xFF3B6FD8)))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(9) { i ->
                Row {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(listOf(Color(0xFFE57373), Color(0xFF81C784), Color(0xFFFFB74D))[i % 3]))
                    Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.width((140 + (i * 37) % 90).dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF9AA3AF)))
                        Box(Modifier.width((220 + (i * 53) % 80).dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFCBD2DA)))
                    }
                }
            }
        }
    }
}
