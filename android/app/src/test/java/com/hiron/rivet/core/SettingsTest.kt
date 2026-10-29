package com.hiron.rivet.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aFreshInstallTalksToTheElevenLabsAgentsAndSuggests() {
        val settings = Settings(app)
        assertTrue(settings.elevenLabsAgents)
        assertTrue(settings.searchSuggestions)
    }

    @Test
    fun aStoredChoiceIsKept() {
        app.getSharedPreferences("rivet", Context.MODE_PRIVATE).edit().putBoolean("elevenlabs_agents", false).putBoolean("search_suggestions", false).commit()
        val settings = Settings(app)
        assertFalse(settings.elevenLabsAgents)
        assertFalse(settings.searchSuggestions)
    }
}
