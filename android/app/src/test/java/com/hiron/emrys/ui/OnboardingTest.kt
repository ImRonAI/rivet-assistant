package com.hiron.emrys.ui

import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hiron.emrys.core.emrys
import com.hiron.emrys.testing.shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The four first-run steps with the real runtime; saves build/screens/00a–00d (and the narrow 00an–00dn) for review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var finished = 0
    private var voiceSetups = 0

    private fun show() = compose.setContent {
        EmrysTheme {
            CompositionLocalProvider(LocalOpenVoiceSetup provides { voiceSetups++ }) {
                Onboarding(compose.activity.emrys, onFinish = { finished++ })
            }
        }
    }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    private fun shot(name: String) = shot(compose, compose.activity, name)

    @Test
    fun walksThroughEveryStep() {
        show()
        compose.onNodeWithText("Meet Emrys.").assertIsDisplayed()
        compose.onNodeWithText("Use recommended settings").assertIsDisplayed()
        compose.onNodeWithText("Set up step by step").assertIsDisplayed()
        compose.onNodeWithText("You stay in control").assertIsDisplayed()
        shot("00a-onboarding-meet")

        tap("Set up step by step")
        compose.onNodeWithText("Talk or type.").assertIsDisplayed()
        compose.onNodeWithText("Emrys's own brain").assertExists()
        shot("00b-onboarding-models")

        tap("Save & continue")
        compose.onNodeWithText("Default assistant.").assertIsDisplayed()
        compose.onNodeWithText("Long-press power button").assertIsSelected()
        compose.onNodeWithText("Then hold the power button", substring = true).assertIsDisplayed()
        shot("00c-onboarding-assistant")
        tap("Corner swipe")
        compose.onNodeWithText("Corner swipe").assertIsSelected()
        compose.onNodeWithText("Then swipe up from a bottom corner", substring = true).assertIsDisplayed()

        tap("Skip for now")
        compose.onNodeWithText("System access.").assertIsDisplayed()
        compose.onNodeWithText("Complete setup").assertIsDisplayed()
        shot("00d-onboarding-access")

        // Back walks one step back, not out of the app.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Default assistant.").assertIsDisplayed()
        assertEquals(0, finished)
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    fun everyStepFitsANarrowPhoneWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        show()
        compose.onNodeWithText("Meet Emrys.").assertIsDisplayed()
        compose.onNodeWithText("Use recommended settings").assertIsDisplayed()
        shot("00an-onboarding-meet-narrow")
        tap("Set up step by step")
        compose.onNodeWithText("Talk or type.").assertIsDisplayed()
        shot("00bn-onboarding-models-narrow")
        tap("Save & continue")
        compose.onNodeWithText("Default assistant.").assertIsDisplayed()
        shot("00cn-onboarding-assistant-narrow")
        tap("Skip for now")
        compose.onNodeWithText("System access.").assertIsDisplayed()
        shot("00dn-onboarding-access-narrow")
    }

    @Test
    fun recommendedSettingsOpenFromTheFirstScreen() {
        val settings = compose.activity.emrys.settings
        settings.autoConnect = false
        settings.speakInOverlay = true
        show()
        tap("Use recommended settings")
        compose.onNodeWithText("Recommended").assertIsDisplayed()
        compose.onNodeWithText("Connect automatically").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(600)
        shot("onboarding-recommended")
        // Dismissing the sheet stays on the first screen.
        compose.onAllNodesWithText("Not now").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Use recommended settings").assertIsDisplayed()
        assertEquals(0, finished)
    }

    @Test
    fun notNowFinishesInOneTap() {
        show()
        tap("Not now")
        assertEquals(1, finished)
    }

    @Test
    fun theModelsStepWritesSettings() {
        val settings = compose.activity.emrys.settings
        settings.voiceModel = true
        settings.speakReplies = false
        settings.elevenLabsAgents = true
        show()
        tap("Set up step by step")

        val agents = compose.onNode(isToggleable() and hasText("ElevenLabs agents"))
        agents.assertIsOn().performClick()
        compose.waitForIdle()
        agents.assertIsOff()
        assertFalse(settings.elevenLabsAgents)

        val voice = compose.onNode(isToggleable() and hasText("Talk key"))
        voice.assertIsOn().performClick()
        compose.waitForIdle()
        voice.assertIsOff()
        assertFalse(settings.voiceModel)

        compose.onNode(isToggleable() and hasText("Read replies aloud when I type")).performClick()
        compose.waitForIdle()
        assertTrue(settings.speakReplies)
    }

    @Test
    fun theVoiceLinkOpensVoiceSetup() {
        show()
        tap("Set up step by step")
        tap("Choose Emrys's voice")
        assertEquals(1, voiceSetups)
    }

    @Test
    fun openAssistantSettingsGoesToAndroidAndMovesOn() {
        show()
        tap("Set up step by step")
        tap("Save & continue")
        tap("Open assistant settings")
        assertEquals(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
        compose.onNodeWithText("System access.").assertIsDisplayed()
    }

    @Test
    fun completeSetupOpensScreenAccessWhenTheRestIsGranted() {
        shadowOf(compose.activity.application).grantPermissions(
            *ACCESS_GROUPS.flatMap { it.permissions }.toTypedArray(),
        )
        show()
        tap("Set up step by step")
        tap("Save & continue")
        tap("Skip for now")
        compose.onNode(isToggleable() and hasText("Messages & actions")).assertIsOn()
        compose.onNode(isToggleable() and hasText("Screen context")).assertIsOn()
        tap("Complete setup")
        assertEquals(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
        assertEquals(1, finished)
    }

    @Test
    fun customizeLaterFinishesWithoutAsking() {
        show()
        tap("Set up step by step")
        tap("Save & continue")
        tap("Skip for now")
        compose.onNode(isToggleable() and hasText("Screen context")).performClick()
        compose.waitForIdle()
        compose.onNode(isToggleable() and hasText("Screen context")).assertIsOff()
        tap("Customize later in Settings")
        assertEquals(1, finished)
        assertEquals(null, shadowOf(compose.activity).nextStartedActivity)
    }
}
