package com.hiron.ren.voice

import com.hiron.ren.voice.Recognizers.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognizersTest {
    private val own = "com.hiron.ren"
    private val ren = Candidate(own, "com.hiron.ren.voice.RenRecognitionService")
    private val google = Candidate("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService", preinstalled = true)
    private val other = Candidate("org.example.voice", "org.example.voice.Recognizer")

    @Test
    fun usesThePhonesDefaultWhenItIsNotRens() {
        assertEquals(other, Recognizers.pick(own, "org.example.voice/.Recognizer", listOf(ren, google, other)))
        assertEquals(google, Recognizers.pick(own, "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService", listOf(ren, other, google)))
    }

    @Test
    fun neverRelaysToItselfWhenRenIsTheDefault() {
        // Android 10 and 11 make the assistant's recognizer the phone's default.
        assertEquals(google, Recognizers.pick(own, "com.hiron.ren/.voice.RenRecognitionService", listOf(ren, other, google)))
        assertEquals(other, Recognizers.pick(own, null, listOf(ren, other)))
        assertNull(Recognizers.pick(own, null, listOf(ren)))
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
