package com.hiron.rivet.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class TimesTest {
    private val denver = ZoneId.of("America/Denver")

    @Test
    fun readsTheFormsTheModelSends() {
        val expected = 1_761_512_400_000L // 2025-10-26T15:00:00-06:00
        assertEquals(expected, Times.parse("2025-10-26T15:00:00-06:00", denver))
        assertEquals(expected, Times.parse("2025-10-26T21:00:00Z", denver))
        assertEquals(expected, Times.parse("2025-10-26T15:00", denver))
        assertEquals(expected, Times.parse("1761512400", denver))
        assertEquals(expected, Times.parse("1761512400000", denver))
        assertEquals(1_761_458_400_000L, Times.parse("2025-10-26", denver))
        assertNull(Times.parse("next tuesday", denver))
    }

    @Test
    fun formatsInThePhonesZone() {
        assertEquals("2025-10-26T15:00:00-06:00", Times.format(1_761_512_400_000L, denver))
    }
}
