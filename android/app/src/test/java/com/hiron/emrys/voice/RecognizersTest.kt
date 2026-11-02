package com.hiron.emrys.voice

import com.hiron.emrys.voice.Recognizers.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognizersTest {
    private val own = "com.hiron.emrys"
    private val emrys = Candidate(own, "com.hiron.emrys.voice.EmrysRecognitionService")
    private val google = Candidate("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService", preinstalled = true)
    private val other = Candidate("org.example.voice", "org.example.voice.Recognizer")

    @Test
    fun usesThePhonesDefaultWhenItIsNotEmryss() {
        assertEquals(other, Recognizers.pick(own, "org.example.voice/.Recognizer", listOf(emrys, google, other)))
        assertEquals(google, Recognizers.pick(own, "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService", listOf(emrys, other, google)))
    }

    @Test
    fun neverRelaysToItselfWhenEmrysIsTheDefault() {
        // Android 10 and 11 make the assistant's recognizer the phone's default.
        assertEquals(google, Recognizers.pick(own, "com.hiron.emrys/.voice.EmrysRecognitionService", listOf(emrys, other, google)))
        assertEquals(other, Recognizers.pick(own, null, listOf(emrys, other)))
        assertNull(Recognizers.pick(own, null, listOf(emrys)))
    }

    @Test
    fun aDefaultThatIsNotInstalledIsSkipped() {
        assertEquals(google, Recognizers.pick(own, "gone.app/.Recognizer", listOf(other, google)))
    }

    @Test
    fun parsesShortAndFullComponentNames() {
        assertEquals("a.b" to "a.b.C", Recognizers.parse("a.b/.C"))
        assertEquals("a.b" to "x.y.Z", Recognizers.parse("a.b/x.y.Z"))
        assertNull(Recognizers.parse(""))
        assertNull(Recognizers.parse("a.b"))
    }
}
