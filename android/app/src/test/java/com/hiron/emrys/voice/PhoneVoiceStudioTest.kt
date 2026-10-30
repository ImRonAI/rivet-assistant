package com.hiron.emrys.voice

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.hiron.emrys.core.Settings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the phone's voice studio saves, and where; nothing leaves the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneVoiceStudioTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val settings = Settings(app)
    private val studio = PhoneVoiceStudio(app, settings)

    @Test
    fun eightPresetsAndNothingSavedAtFirst() {
        assertFalse(studio.connected)
        assertEquals(listOf("copper", "sol", "slate", "vesper", "ember", "flint", "basalt", "lumen"), studio.presets().map { it.id })
        assertNull(studio.current())
    }

    @Test
    fun choosingAPresetOrDescriptionSavesIt() {
        val slate = studio.presets().first { it.id == "slate" }
        assertEquals("Slate", studio.choose(VoiceChoice.Preset(slate)))
        assertEquals("preset", settings.voiceKind)
        assertEquals("slate", settings.voicePreset)
        assertEquals("Slate", studio.current())

        assertEquals("Described voice", studio.choose(VoiceChoice.Described("A calm robot")))
        assertEquals("described", settings.voiceKind)
        assertEquals("A calm robot", settings.voiceDescription)
    }

    @Test
    fun aRecordingIsCopiedIntoAppStorage() {
        val take = File(app.cacheDir, "voice-sample.m4a").apply { writeBytes(ByteArray(2048) { 7 }) }
        assertEquals("Your recording", studio.choose(VoiceChoice.Recorded(VoiceSample(take, 12))))
        assertEquals("recorded", settings.voiceKind)
        assertEquals(2048L, File(app.filesDir, "voice/sample.m4a").length())
    }

    @Test
    fun uploadsOver20MbAreRefused() {
        val small = File(app.cacheDir, "note.mp3").apply { writeBytes(ByteArray(4096)) }
        assertEquals("Uploaded recording", studio.choose(VoiceChoice.Uploaded(Uri.fromFile(small), "note.mp3")))
        assertTrue(File(app.filesDir, "voice/upload").exists())

        val big = File(app.cacheDir, "long.wav").apply { outputStream().use { out -> repeat(21) { out.write(ByteArray(1024 * 1024)) } } }
        assertEquals(PhoneVoiceStudio.TOO_BIG, studio.choose(VoiceChoice.Uploaded(Uri.fromFile(big), "long.wav")))
        assertEquals("Uploaded recording", studio.current())
        assertEquals(4096L, File(app.filesDir, "voice/upload").length())
    }
}
