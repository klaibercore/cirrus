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
        val result = buildInlineMarkdown("**bold**", styles)
        assertEquals("bold", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `renders italic`() {
        val result = buildInlineMarkdown("*italic*", styles)
        assertEquals("italic", result.text)
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `renders inline code`() {
        val result = buildInlineMarkdown("`code`", styles)
        assertEquals("code", result.text)
        assertTrue(result.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `renders strikethrough`() {
        val result = buildInlineMarkdown("~~strike~~", styles)
        assertEquals("strike", result.text)
        assertTrue(result.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun `renders markdown links`() {
        val result = buildInlineMarkdown("[label](https://example.com)", styles)
        assertEquals("label", result.text)
        assertEquals(listOf("https://example.com"), result.linkUrls())
    }

    @Test
    fun `renders angle bracket autolinks`() {
        val result = buildInlineMarkdown("<https://example.com>", styles)
        assertEquals("https://example.com", result.text)
        assertEquals(listOf("https://example.com"), result.linkUrls())
    }

    @Test
    fun `renders bare urls`() {
        val result = buildInlineMarkdown("see https://example.com now", styles)
        assertEquals("see https://example.com now", result.text)
        assertEquals(listOf("https://example.com"), result.linkUrls())
    }

    @Test
    fun `leaves unclosed emphasis literal`() {
        val result = buildInlineMarkdown("**bold", styles)
        assertEquals("**bold", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `does not emphasize snake case`() {
        val result = buildInlineMarkdown("foo_bar", styles)
        assertEquals("foo_bar", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `supports nested emphasis`() {
        val result = buildInlineMarkdown("**bold *italic* text**", styles)
        assertEquals("bold italic text", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }
}
