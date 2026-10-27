package com.hiron.rivet.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTest {
    private val p = DarkPalette

    @Test
    fun markdownBecomesStylingNotSymbols() {
        val out = Rich.format("## Today\n- **Dentist** at 9 *sharp*\n- Call `Dana` back [2]", p)
        assertEquals("Today\n•  Dentist at 9 sharp\n•  Call Dana back 2", out.text)
        val bold = out.spanStyles.filter { it.item.fontWeight == FontWeight.SemiBold }.map { out.text.substring(it.start, it.end) }
        assertTrue(bold.containsAll(listOf("Today", "Dentist")))
    }

    @Test
    fun linksAreTappableAndBareUrlsAreShortened() {
        val out = Rich.format("See [the menu](https://example.com/menu) or https://wasatch.dental/hours/", p)
        assertEquals("See the menu or wasatch.dental/hours", out.text)
        val urls = out.getLinkAnnotations(0, out.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://example.com/menu", "https://wasatch.dental/hours/"), urls)
    }

    @Test
    fun plainTextAndUnfinishedMarkupPassThrough() {
        assertEquals("Tomorrow you have a dentist cleaning at 9 AM.", Rich.format("Tomorrow you have a dentist cleaning at 9 AM.", p).text)
        assertEquals("Working on **it", Rich.format("Working on **it", p).text)
        assertEquals("2 * 3 = 6", Rich.format("2 * 3 = 6", p).text)
    }
}
