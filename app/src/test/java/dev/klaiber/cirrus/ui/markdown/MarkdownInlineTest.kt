package dev.klaiber.cirrus.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTextApi::class)
class MarkdownInlineTest {

    private val styles = MarkdownStyles(
        linkColor = Color.Blue,
        inlineCodeColor = Color.Red,
        inlineCodeBackground = Color.LightGray,
    )

    private fun AnnotatedString.linkUrls(): List<String> =
        getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }

    @Test
    fun `renders bold`() {
        val result = buildInlineMarkdown("**bold**", styles).annotated
        assertEquals("bold", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `renders italic`() {
        val result = buildInlineMarkdown("*italic*", styles).annotated
        assertEquals("italic", result.text)
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `renders inline code`() {
        val result = buildInlineMarkdown("`code`", styles).annotated
        assertEquals("code", result.text)
        assertTrue(result.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `renders strikethrough`() {
        val result = buildInlineMarkdown("~~strike~~", styles).annotated
        assertEquals("strike", result.text)
        assertTrue(result.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun `renders markdown links`() {
        val result = buildInlineMarkdown("[label](https://example.com)", styles).annotated
        assertEquals("label", result.text)
        assertEquals(listOf("https://example.com"), result.linkUrls())
    }

    @Test
    fun `renders angle bracket autolinks`() {
        val result = buildInlineMarkdown("<https://example.com>", styles).annotated
        assertEquals("https://example.com", result.text)
        assertEquals(listOf("https://example.com"), result.linkUrls())
    }

    @Test
    fun `renders bare urls`() {
        val result = buildInlineMarkdown("see https://example.com now", styles).annotated
        assertEquals("see https://example.com now", result.text)
        assertEquals(listOf("https://example.com"), result.linkUrls())
    }

    @Test
    fun `leaves unclosed emphasis literal`() {
        val result = buildInlineMarkdown("**bold", styles).annotated
        assertEquals("**bold", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `does not emphasize snake case`() {
        val result = buildInlineMarkdown("foo_bar", styles).annotated
        assertEquals("foo_bar", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `supports nested emphasis`() {
        val result = buildInlineMarkdown("**bold *italic* text**", styles).annotated
        assertEquals("bold italic text", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `inline maths loses its delimiters and is set in italic`() {
        val result = buildInlineMarkdown("Time is \$O(n \\log n)\$ overall.", styles).annotated

        assertEquals("Time is O(n log n) overall.", result.text)
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `display maths is unwrapped too`() {
        assertEquals("a² + b² = c²", buildInlineMarkdown("\$\$a^2 + b^2 = c^2\$\$", styles).annotated.text)
        assertEquals("x → ∞", buildInlineMarkdown("\\(x \\to \\infty\\)", styles).annotated.text)
        assertEquals("E = mc²", buildInlineMarkdown("\\[E = mc^2\\]", styles).annotated.text)
    }

    @Test
    fun `currency is not mistaken for maths`() {
        // The closing candidate is preceded by a space, so this is prose about money.
        assertEquals("It costs \$5 and \$10.", buildInlineMarkdown("It costs \$5 and \$10.", styles).annotated.text)
        assertEquals("\$100 or \$200", buildInlineMarkdown("\$100 or \$200", styles).annotated.text)
    }

    @Test
    fun `a lone dollar sign is left alone`() {
        assertEquals("costs \$5", buildInlineMarkdown("costs \$5", styles).annotated.text)
        assertEquals("\$", buildInlineMarkdown("\$", styles).annotated.text)
    }

    @Test
    fun `maths does not swallow a paragraph break`() {
        val result = buildInlineMarkdown("\$x + 1\n\nand later \$y\$", styles).annotated

        assertTrue(result.text.startsWith("\$x + 1"))
    }

    @Test
    fun `maths inside code stays literal`() {
        // A code span is claimed first, so nothing inside it is reinterpreted.
        val result = buildInlineMarkdown("`\$x^2\$`", styles).annotated

        assertEquals("\$x^2\$", result.text)
        assertTrue(result.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }
}
