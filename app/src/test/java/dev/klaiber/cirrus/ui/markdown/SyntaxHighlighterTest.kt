package dev.klaiber.cirrus.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import dev.klaiber.cirrus.ui.theme.CodeColors
import org.junit.Assert.assertEquals
import org.junit.Test

class SyntaxHighlighterTest {

    private val colors = CodeColors.Light

    private fun AnnotatedString.spanText(color: Color): String? =
        spanStyles.firstOrNull { it.item.color == color }
            ?.let { text.substring(it.start, it.end) }

    @Test
    fun `highlights kotlin keywords and numbers`() {
        val result = SyntaxHighlighter.highlight("val x = 1", "kotlin", colors)
        assertEquals("val x = 1", result.text)
        assertEquals("val", result.spanText(colors.keyword))
        assertEquals("1", result.spanText(colors.number))
    }

    @Test
    fun `highlights function calls`() {
        val result = SyntaxHighlighter.highlight("fun main() {}", "kotlin", colors)
        assertEquals("fun", result.spanText(colors.keyword))
        assertEquals("main", result.spanText(colors.function))
    }

    @Test
    fun `highlights strings`() {
        val result = SyntaxHighlighter.highlight("""val s = "hello"""", "kotlin", colors)
        assertEquals("\"hello\"", result.spanText(colors.string))
    }

    @Test
    fun `highlights comments`() {
        val result = SyntaxHighlighter.highlight("// comment\nval x = 1", "kotlin", colors)
        assertEquals("// comment", result.spanText(colors.comment))
    }

    @Test
    fun `highlights json keys and values`() {
        val result = SyntaxHighlighter.highlight("""{"key": "value"}""", "json", colors)
        assertEquals("\"key\"", result.spanText(colors.attribute))
        assertEquals("\"value\"", result.spanText(colors.string))
    }

    @Test
    fun `highlights diff additions and removals`() {
        val result = SyntaxHighlighter.highlight("+added\n-removed", "diff", colors)
        assertEquals("+added", result.spanText(colors.string))
        assertEquals("-removed", result.spanText(colors.number))
    }

    @Test
    fun `highlights markup tags`() {
        val result = SyntaxHighlighter.highlight("""<div class="x">""", "html", colors)
        assertEquals("div", result.spanText(colors.keyword))
        assertEquals("class", result.spanText(colors.attribute))
        assertEquals("\"x\"", result.spanText(colors.string))
    }

    @Test
    fun `unknown language falls back to generic profile`() {
        val result = SyntaxHighlighter.highlight("val x = 1", "unknownlang", colors)
        assertEquals("val", result.spanText(colors.keyword))
    }
}
