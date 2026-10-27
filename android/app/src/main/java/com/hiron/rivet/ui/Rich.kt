package com.hiron.rivet.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/**
 * Rivet's replies as readable text: the markdown the model writes (headings, bullets, bold, italics, code,
 * links) becomes styling instead of stray symbols, and citation markers like `[2]` become small cyan
 * superscripts. Anything unfinished mid-stream stays as typed until it closes.
 */
object Rich {
    private val INLINE = Regex(
        listOf(
            """\[([^\]\n]+)]\((https?://[^)\s]+)\)""", // [label](url)
            """\*\*(.+?)\*\*""", // **bold**
            """__(.+?)__""", // __bold__
            """`([^`\n]+)`""", // `code`
            """(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])""", // *italic*
            """\[(\d{1,2})]""", // [3] citation
            """https?://[^\s)\]]+""", // bare url
        ).joinToString("|"),
    )
    private val BULLET = Regex("""^(\s*)[-*•]\s+""")
    private val HEADING = Regex("""^#{1,4}\s+""")

    /** The same text with the markup removed, for places that can't style it (widgets, notifications). */
    fun plain(text: String): String = format(text, DarkPalette).text

    fun format(text: String, p: Palette): AnnotatedString = buildAnnotatedString {
        text.lines().forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            var line = raw
            val heading = HEADING.find(line)
            if (heading != null) line = line.substring(heading.range.last + 1)
            BULLET.find(line)?.let { m ->
                append(m.groupValues[1])
                withStyle(SpanStyle(color = p.accent)) { append("•  ") }
                line = line.substring(m.range.last + 1)
            }
            if (heading != null) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(line, p) } else inline(line, p)
        }
    }

    private fun AnnotatedString.Builder.inline(line: String, p: Palette) {
        var at = 0
        for (m in INLINE.findAll(line)) {
            append(line, at, m.range.first)
            val g = m.groups
            when {
                g[1] != null -> link(g[1]!!.value, g[2]!!.value, p)
                g[3] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[3]!!.value) }
                g[4] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[4]!!.value) }
                g[5] != null -> withStyle(SpanStyle(fontFamily = Mono, fontSize = 0.9.em, background = p.high)) { append(g[5]!!.value) }
                g[6] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6]!!.value) }
                g[7] != null -> withStyle(SpanStyle(fontFamily = Mono, fontSize = 0.7.em, color = p.cy, baselineShift = BaselineShift.Superscript)) {
                    append(g[7]!!.value)
                }
                else -> link(m.value.removePrefix("https://").removePrefix("http://").trimEnd('/'), m.value, p)
            }
            at = m.range.last + 1
        }
        append(line, at, line.length)
    }

    private fun AnnotatedString.Builder.link(label: String, url: String, p: Palette) {
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = p.cy, textDecoration = TextDecoration.Underline)))) { append(label) }
    }
}
