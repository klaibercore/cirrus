package dev.klaiber.cirrus.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What read-aloud actually says.
 *
 * Every case here is something that sounds wrong when the raw markdown is handed to a speech
 * engine, which is the whole reason this conversion exists.
 */
class SpeakableTextTest {

    private fun speak(markdown: String) = markdownToSpeech(markdown)

    @Test
    fun `emphasis markers are not read out`() {
        assertEquals("Important note.", speak("**Important** note"))
        assertEquals("really matters.", speak("*really* matters"))
    }

    @Test
    fun `code blocks are announced rather than dictated`() {
        val spoken = speak(
            """
            Here you go:

            ```python
            for row in rows:
                print(row)
            ```
            """.trimIndent(),
        )
        assertEquals("Here you go:\nA python code block.", spoken)
        assertFalse(spoken.contains("print"))
    }

    @Test
    fun `an unlabelled code block still says what it is`() {
        assertEquals("Code block.", speak("```\nls -la\n```"))
    }

    @Test
    fun `inline code is read, because it is usually one word`() {
        assertEquals("Run npm test now.", speak("Run `npm test` now."))
    }

    @Test
    fun `links keep their label and lose their target`() {
        assertEquals("See the docs for more.", speak("See [the docs](https://example.com) for more."))
    }

    @Test
    fun `a bare url is not spelled out`() {
        assertEquals("Try link today.", speak("Try https://example.com/a/b?c=d today."))
    }

    @Test
    fun `maths is spoken as words`() {
        assertEquals("E equals m c squared.", speak("\$\$E = mc^2\$\$"))
        assertEquals("Complexity is O ( n log n ) here.", speak("Complexity is \$O(n \\log n)\$ here."))
        assertEquals("a over b.", speak("\$\\frac{a}{b}\$"))
        assertEquals("the square root of 2.", speak("\$\\sqrt{2}\$"))
    }

    @Test
    fun `an operator says its bounds the way a person would`() {
        assertEquals(
            "the sum from i equals 1 to n, of x sub i.",
            speak("\$\$\\sum_{i=1}^{n} x_i\$\$"),
        )
        assertEquals(
            "the limit as x goes to 0, of f ( x ).",
            speak("\$\$\\lim_{x \\to 0} f(x)\$\$"),
        )
        assertEquals(
            "the integral from 0 to 1, of x squared.",
            speak("\$\$\\int_0^1 x^2\$\$"),
        )
    }

    @Test
    fun `headings become sentences, because a full stop is a pause`() {
        assertEquals("Results.\nIt worked.", speak("## Results\n\nIt worked"))
    }

    @Test
    fun `list items are separated so they do not run together`() {
        assertEquals("1. First.\n2. Second.", speak("1. First\n2. Second"))
        assertEquals("Apples.\nPears.", speak("- Apples\n- Pears"))
    }

    @Test
    fun `task lists say whether they are done`() {
        assertEquals("Done: Ship it.\nTo do: Write it up.", speak("- [x] Ship it\n- [ ] Write it up"))
    }

    @Test
    fun `table rows are read as heading and value pairs`() {
        val table = """
            | Name | Role |
            | --- | --- |
            | Ada | Engineer |
        """.trimIndent()
        assertEquals("Name: Ada, Role: Engineer.", speak(table))
    }

    @Test
    fun `a horizontal rule is silent`() {
        assertEquals("Before.\nAfter.", speak("Before\n\n---\n\nAfter"))
    }

    @Test
    fun `nothing in the output would be read as punctuation noise`() {
        val spoken = speak("## A **bold** heading\n\n- `code` item\n- [link](https://x.com)")
        assertFalse(spoken.contains("#"))
        assertFalse(spoken.contains("*"))
        assertFalse(spoken.contains("`"))
        assertFalse(spoken.contains("]("))
    }

    @Test
    fun `empty and whitespace input speak nothing`() {
        assertTrue(speak("").isEmpty())
        assertTrue(speak("   \n\n  ").isEmpty())
    }
}
