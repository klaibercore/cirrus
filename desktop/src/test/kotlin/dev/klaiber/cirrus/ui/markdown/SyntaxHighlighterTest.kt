package dev.klaiber.cirrus.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import dev.klaiber.cirrus.ui.theme.CodeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- Why this is a lexer and not a set of regexes ---------------------------------------
    //
    // The cases below are the whole justification for hand-writing a scanner: each one is a place
    // where a pattern match over the raw text gets the answer wrong, because whether a character
    // starts a token depends entirely on what the scanner was in the middle of at the time.

    @Test
    fun `a comment marker inside a string is part of the string`() {
        val result = SyntaxHighlighter.highlight("""val url = "https://ollama.com"""", "kotlin", colors)

        assertEquals(""""https://ollama.com"""", result.spanText(colors.string))
        assertNull("the // inside the URL is not a comment", result.spanText(colors.comment))
    }

    @Test
    fun `a quote inside a line comment does not open a string`() {
        val result = SyntaxHighlighter.highlight("""// it's fine""", "kotlin", colors)

        assertEquals("""// it's fine""", result.spanText(colors.comment))
        assertNull("the apostrophe must not start a string", result.spanText(colors.string))
    }

    @Test
    fun `an escaped quote does not end the string early`() {
        val result = SyntaxHighlighter.highlight("""val s = "a\"b" + c""", "kotlin", colors)

        assertEquals("""a\"b""", result.spanText(colors.string)?.removeSurrounding("\""))
    }

    @Test
    fun `a keyword inside a string is not a keyword`() {
        val result = SyntaxHighlighter.highlight("""val s = "return val fun"""", "kotlin", colors)

        // The only keyword span is the leading `val`; the words inside the quotes are string.
        assertEquals("val", result.spanText(colors.keyword))
        assertEquals(""""return val fun"""", result.spanText(colors.string))
    }

    @Test
    fun `a block comment swallows the code inside it`() {
        val result = SyntaxHighlighter.highlight("/* val x = 1 */ val y = 2", "kotlin", colors)

        assertEquals("/* val x = 1 */", result.spanText(colors.comment))
    }

    // ---- Truncated input, which is the normal case while a reply streams ----------------------
    //
    // The highlighter re-runs on every token, so it spends most of its life looking at code that
    // stops mid-construct. None of these may throw, and none may swallow the rest of the document.

    @Test
    fun `an unterminated string ends at the newline rather than eating the rest`() {
        val result = SyntaxHighlighter.highlight("val a = \"oops\nval b = 2", "kotlin", colors)

        assertEquals("\"oops", result.spanText(colors.string))
        // The second line is still scanned as code, so `val` is found again after the break.
        assertEquals(2, result.spanStyles.count { it.item.color == colors.keyword })
    }

    @Test
    fun `an unterminated block comment runs to the end without throwing`() {
        val result = SyntaxHighlighter.highlight("/* still typing", "kotlin", colors)

        assertEquals("/* still typing", result.spanText(colors.comment))
    }

    @Test
    fun `an unterminated triple quoted string does not throw`() {
        val result = SyntaxHighlighter.highlight("val s = \"\"\"partial", "kotlin", colors)

        assertEquals("\"\"\"partial", result.spanText(colors.string))
    }

    @Test
    fun `a trailing backslash at the very end does not run off the string`() {
        // readString advances two characters for an escape, which at the last character would step
        // past the end of the input.
        val result = SyntaxHighlighter.highlight("""val s = "abc\""", "kotlin", colors)

        assertEquals("""val s = "abc\""", result.text)
    }

    @Test
    fun `an empty snippet is not an error`() {
        val result = SyntaxHighlighter.highlight("", "kotlin", colors)

        assertEquals("", result.text)
        assertTrue("nothing should be highlighted in an empty snippet", result.tokenSpans().isEmpty())
    }

    // ---- The text must always survive intact -------------------------------------------------

    @Test
    fun `highlighting never alters the code it was given`() {
        val samples = listOf(
            "val x = 1",
            "/* unclosed",
            "\"unterminated",
            "0xFF + 1.5e-3f",
            "@Composable fun A() {}",
            "  \t mixed\twhitespace  ",
            "emoji 🎉 and ünïcödé",
            "",
        )
        samples.forEach { sample ->
            listOf("kotlin", "python", "json", "diff", "html", null).forEach { language ->
                assertEquals(
                    "text changed for language=$language",
                    sample,
                    SyntaxHighlighter.highlight(sample, language, colors).text,
                )
            }
        }
    }

    @Test
    fun `spans never overlap or point outside the text`() {
        val code = """
            // header
            fun f(a: Int = 0x1F): String {
                val s = "with // slashes and \"escapes\""
                /* block */ return s
            }
        """.trimIndent()

        val result = SyntaxHighlighter.highlight(code, "kotlin", colors)

        // Token spans are layered over one base span of the plain colour covering the whole
        // snippet, so overlap is only a fault *between tokens*: that is how a highlighter ends up
        // painting a keyword colour over half a string. Out-of-range spans are a fault anywhere,
        // since AnnotatedString rejects them at construction.
        val ordered = result.tokenSpans().sortedBy { it.start }
        result.spanStyles.forEach { span ->
            assertTrue("span starts before 0", span.start >= 0)
            assertTrue("span ends past the text", span.end <= result.text.length)
            assertTrue("span is inverted", span.start <= span.end)
        }
        ordered.zipWithNext().forEach { (first, second) ->
            assertTrue(
                "token spans overlap: ${first.start}..${first.end} and ${second.start}..${second.end}",
                first.end <= second.start,
            )
        }
    }

    /**
     * The highlighted tokens, without the base layer.
     *
     * [SyntaxHighlighter] paints one span of the plain colour across the whole snippet and then
     * layers coloured tokens over it, so the base span overlaps everything by design and has to be
     * excluded before overlap means anything.
     */
    private fun AnnotatedString.tokenSpans() = spanStyles.filterNot { it.item.color == colors.plain }
}
