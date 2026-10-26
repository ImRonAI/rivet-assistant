package com.hiron.rivet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    @Test
    fun stripsMarkdownLinksAndCitations() {
        assertEquals(
            "It's 54°F and sunny in Salt Lake City. See the forecast.",
            Speech.clean("It's **54°F** and sunny in Salt Lake City [1][2]. See [the forecast](https://weather.gov/slc)."),
        )
        assertEquals("Milk Eggs", Speech.clean("- Milk\n- Eggs"))
    }

    @Test
    fun longRepliesStopAtASentence() {
        val text = "First sentence here. " + "word ".repeat(200)
        val spoken = Speech.clean(text, limit = 100)
        assertTrue(spoken.length <= 101)
    }
}
