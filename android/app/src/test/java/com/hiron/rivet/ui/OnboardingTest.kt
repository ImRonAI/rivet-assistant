package com.hiron.rivet.ui

import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hiron.rivet.core.rivet
import com.hiron.rivet.testing.shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The four first-run steps with the real runtime; saves build/screens/00a–00d for review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var finished = 0
    private var voiceSetups = 0

    private fun show() = compose.setContent {
        RivetTheme {
            CompositionLocalProvider(LocalOpenVoiceSetup provides { voiceSetups++ }) {
                Onboarding(compose.activity.rivet, onFinish = { finished++ })
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
        compose.onNodeWithText("Get Started").assertIsDisplayed()
        compose.onNodeWithText("You stay in control").assertIsDisplayed()
        shot("00a-onboarding-meet")

        tap("Get Started")
        compose.onNodeWithText("TWO MODELS, ONE RIVET").assertIsDisplayed()
        compose.onNode(hasText("Text model") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Always on")).assertExists()
        shot("00b-onboarding-models")

        tap("Save & Continue")
        compose.onNodeWithText("DEFAULT ASSISTANT").assertIsDisplayed()
        compose.onNodeWithText("Long-press power button").assertIsSelected()
        compose.onNodeWithText("Then hold the power button", substring = true).assertIsDisplayed()
        shot("00c-onboarding-assistant")
        tap("Corner swipe")
        compose.onNodeWithText("Corner swipe").assertIsSelected()
        compose.onNodeWithText("Then swipe up from a bottom corner", substring = true).assertIsDisplayed()

        tap("Skip for now")
        compose.onNodeWithText("SYSTEM ACCESS").assertIsDisplayed()
        compose.onNodeWithText("Complete Setup").assertIsDisplayed()
        shot("00d-onboarding-access")

        // Back walks one step back, not out of the app.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("DEFAULT ASSISTANT").assertIsDisplayed()
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
        val settings = compose.activity.rivet.settings
        settings.voiceModel = true
        settings.speakReplies = false
        show()
        tap("Get Started")

        val voice = compose.onNode(isToggleable() and hasText("Voice model"))
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
        tap("Get Started")
        tap("Choose Rivet's voice")
        assertEquals(1, voiceSetups)
    }

    @Test
    fun openAssistantSettingsGoesToAndroidAndMovesOn() {
        show()
        tap("Get Started")
        tap("Save & Continue")
        tap("Open Assistant Settings")
        assertEquals(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
        compose.onNodeWithText("SYSTEM ACCESS").assertIsDisplayed()
    }

    @Test
    fun completeSetupOpensScreenAccessWhenTheRestIsGranted() {
        shadowOf(compose.activity.application).grantPermissions(
            *ACCESS_GROUPS.flatMap { it.permissions }.toTypedArray(),
        )
        show()
        tap("Get Started")
        tap("Save & Continue")
        tap("Skip for now")
        compose.onNode(isToggleable() and hasText("Messages & actions")).assertIsOn()
        compose.onNode(isToggleable() and hasText("Screen context")).assertIsOn()
        tap("Complete Setup")
        assertEquals(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
        assertEquals(1, finished)
    }

    @Test
    fun customizeLaterFinishesWithoutAsking() {
        show()
        tap("Get Started")
        tap("Save & Continue")
        tap("Skip for now")
        compose.onNode(isToggleable() and hasText("Screen context")).performClick()
        compose.waitForIdle()
        compose.onNode(isToggleable() and hasText("Screen context")).assertIsOff()
        tap("Customize later in Settings")
        assertEquals(1, finished)
        assertEquals(null, shadowOf(compose.activity).nextStartedActivity)
    }
}
