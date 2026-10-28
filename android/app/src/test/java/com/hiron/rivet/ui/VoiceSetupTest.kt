package com.hiron.rivet.ui

import android.Manifest
import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hiron.rivet.testing.shot
import com.hiron.rivet.voice.PhoneVoiceStudio
import com.hiron.rivet.voice.SampleRecorder
import com.hiron.rivet.voice.VoiceChoice
import com.hiron.rivet.voice.VoicePreset
import com.hiron.rivet.voice.VoiceSample
import com.hiron.rivet.voice.VoiceStudio
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/** The seven voice setup screens with a studio that records nothing and saves to memory. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class VoiceSetupTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeRecorder : SampleRecorder {
        private val pattern = listOf(0.2f, 0.45f, 0.62f, 0.8f, 0.3f, 0.7f, 0.92f, 0.25f, 0.55f, 0.35f, 0.5f, 0.15f)
        private var i = 0
        var starts = 0
        var cancels = 0
        var stops = 0

        override fun start(): Boolean {
            starts++
            return true
        }

        override fun level(): Float = pattern[i++ % pattern.size]

        override fun stop(): VoiceSample {
            stops++
            return VoiceSample(File("voice-sample.m4a"), 24)
        }

        override fun cancel() {
            cancels++
        }
    }

    private class FakeStudio(var label: String? = null) : VoiceStudio {
        val recorder = FakeRecorder()
        val chosen = mutableListOf<VoiceChoice>()
        val previews = mutableListOf<Pair<String, VoicePreset?>>()
        var stops = 0
        override val connected = false
        override fun presets() = PhoneVoiceStudio.PRESETS
        override fun preview(text: String, preset: VoicePreset?, onDone: () -> Unit) {
            previews += text to preset
        }

        override fun play(sample: VoiceSample, onProgress: (Float) -> Unit, onDone: () -> Unit) = onProgress(0.3f)
        override fun stopPreview() {
            stops++
        }

        override fun recorder(): SampleRecorder = recorder
        override fun choose(choice: VoiceChoice): String {
            chosen += choice
            label = when (choice) {
                is VoiceChoice.Preset -> choice.preset.name
                is VoiceChoice.Described -> "Described voice"
                is VoiceChoice.Recorded -> "Your recording"
                is VoiceChoice.Uploaded -> "Uploaded recording"
            }
            return label!!
        }

        override fun current() = label
    }

    /** Steps the paused clock a frame-ish at a time so screen transitions and the level polling both run. */
    private fun advance(ms: Long) = repeat((ms / 50).toInt()) { compose.mainClock.advanceTimeBy(50) }

    private fun show(studio: VoiceStudio, start: VoiceScreen = VoiceScreen.Selector, onClose: () -> Unit = {}) =
        compose.setContent { RivetTheme { VoiceSetup(studio, onClose, start) } }

    @Test
    fun selectorShowsTheWaysAndSkipCloses() {
        val studio = FakeStudio()
        var closed = 0
        show(studio) { closed++ }
        compose.onNodeWithText("Choose or make a voice").assertExists()
        compose.onNodeWithText("No voice chosen yet").assertExists()
        compose.onNodeWithText("Upload a recording").assertExists()
        shot(compose, compose.activity, "v1-voice-selector")
        compose.onNodeWithText("Skip voice setup").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun describeCapsAt280AndChipsFillTheCard() {
        val studio = FakeStudio()
        show(studio, VoiceScreen.Describe)
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("x".repeat(300))
        compose.onNodeWithText("280 / 280").assertExists()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Vintage Radio").performClick()
        val fill = "A warm, slightly crackly mid-century radio announcer who speaks clearly and kindly."
        compose.onNodeWithText(fill).assertExists()
        compose.onNodeWithText("${fill.length} / 280").assertExists()
        compose.onNodeWithText("Hear a sample").performClick()
        assertEquals(SAMPLE_LINE, studio.previews.single().first)
        shot(compose, compose.activity, "v2-voice-describe")
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(VoiceChoice.Described(fill), studio.chosen.single())
        compose.onNodeWithText("Described voice").assertExists()
    }

    @Test
    fun presetsFilterSelectAndChoose() {
        val studio = FakeStudio(label = "Copper")
        show(studio, VoiceScreen.Presets)
        compose.onNodeWithText("Choose from 8 ready-made voices.").assertExists()
        compose.onNodeWithText("ACTIVE").assertExists()
        shot(compose, compose.activity, "v3-voice-presets")
        compose.onNodeWithText("DEEP").performClick()
        compose.onNodeWithText("Copper").assertDoesNotExist()
        compose.onNodeWithText("Basalt").assertExists().performClick()
        compose.onNodeWithText("Basalt").assertExists()
        compose.onNodeWithText("ALL").performClick()
        compose.onNodeWithText("Copper").assertExists()
        compose.onNodeWithText("Continue").performClick()
        val choice = studio.chosen.single() as VoiceChoice.Preset
        assertEquals("basalt", choice.preset.id)
        compose.onNodeWithText("Basalt").assertExists()
        compose.onNodeWithText("RIVET'S VOICE.").assertExists()
    }

    @Test
    fun presetCardsSelectAndPreview() {
        val studio = FakeStudio()
        show(studio, VoiceScreen.Presets)
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Sol").performClick()
        compose.onNodeWithText("Continue").assertIsEnabled()
        compose.onAllPlayKeys()[1].performClick()
        assertEquals("sol", studio.previews.single().second?.id)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllPlayKeys() =
        onAllNodes(androidx.compose.ui.test.hasContentDescription("Play"))

    @Test
    fun recordCaptureReviewAndUse() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val studio = FakeStudio()
        // The capture screen polls the microphone until 30 seconds; drive the clock by hand.
        compose.mainClock.autoAdvance = false
        show(studio, VoiceScreen.Record)
        advance(1_000)
        compose.onNodeWithText("TAP TO RECORD").assertExists()
        shot(compose, compose.activity, "v4-voice-record")

        compose.onNodeWithContentDescription("Start recording").performClick()
        assertEquals(1, studio.recorder.starts)
        advance(14_000)
        compose.onNodeWithText("CAPTURING.").assertExists()
        compose.onNodeWithText("LEVEL GOOD").assertExists()
        shot(compose, compose.activity, "v5-voice-capturing")
        compose.onNodeWithText("Show the line to read").assertExists()

        compose.onNodeWithText("Finish recording").performClick()
        advance(1_000)
        compose.mainClock.autoAdvance = true
        assertEquals(1, studio.recorder.stops)
        compose.onNodeWithText("SAMPLE REVIEW.").assertExists()
        compose.onNodeWithText("00:24").assertExists()
        compose.onNodeWithContentDescription("Play").performClick()
        shot(compose, compose.activity, "v6-voice-review")

        compose.onNodeWithText("Use sample").performClick()
        assertTrue(studio.chosen.single() is VoiceChoice.Recorded)
        compose.onNodeWithText("Your recording").assertExists()
    }

    @Test
    fun cancelGoesBackToRecord() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val studio = FakeStudio()
        compose.mainClock.autoAdvance = false
        show(studio, VoiceScreen.Record)
        advance(1_000)
        compose.onNodeWithContentDescription("Start recording").performClick()
        advance(1_000)
        compose.onNodeWithText("Cancel sample").performClick()
        advance(1_000)
        assertEquals(1, studio.recorder.cancels)
        compose.onNodeWithText("RECORD SAMPLE.").assertExists()
    }

    @Test
    fun recordingStopsItselfAtThirtySeconds() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val studio = FakeStudio()
        compose.mainClock.autoAdvance = false
        show(studio, VoiceScreen.Record)
        advance(1_000)
        compose.onNodeWithContentDescription("Start recording").performClick()
        advance(31_000)
        advance(1_000)
        assertEquals(1, studio.recorder.stops)
        compose.onNodeWithText("SAMPLE REVIEW.").assertExists()
    }

    @Test
    fun uploadPicksAFileAndChoosesIt() {
        val studio = FakeStudio()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = Uri.parse("content://com.android.providers.media.documents/document/morning-note.mp3")
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(app, file), MediaMetadataRetriever.METADATA_KEY_DURATION, "42000")
        val picker = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, file)
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = picker
        }
        compose.setContent {
            RivetTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    VoiceSetup(studio, {}, VoiceScreen.Upload)
                }
            }
        }
        compose.onNodeWithText("READY FOR A FILE").assertExists()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        shot(compose, compose.activity, "v7-voice-upload")
        compose.onNodeWithText("CHOOSE AUDIO").performClick()
        compose.onNodeWithText("morning-note.mp3").assertExists()
        compose.onNodeWithText("FILE LOOKS GOOD").assertExists()
        shot(compose, compose.activity, "v7b-voice-upload-picked")
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(VoiceChoice.Uploaded(file, "morning-note.mp3"), studio.chosen.single())
    }

    @Test
    fun presetIsSelectedSemantically() {
        show(FakeStudio(label = "Copper"), VoiceScreen.Presets)
        compose.onNode(androidx.compose.ui.test.hasText("Copper") and androidx.compose.ui.test.hasClickAction(), useUnmergedTree = false)
            .assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    fun fitsANarrowPhoneWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        show(FakeStudio(label = "Copper"), VoiceScreen.Describe)
        compose.onNodeWithText("2 / 2").assertExists()
        shot(compose, compose.activity, "v2n-voice-describe-narrow")
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    fun selectorOnANarrowPhoneWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        show(FakeStudio(label = "Copper"))
        compose.onNodeWithText("Copper").assertExists()
        shot(compose, compose.activity, "v1n-voice-selector-narrow")
    }
}
