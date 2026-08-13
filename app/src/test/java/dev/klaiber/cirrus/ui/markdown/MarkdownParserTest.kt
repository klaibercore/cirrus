package dev.klaiber.cirrus.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `parses headings`() {
        val blocks = MarkdownParser.parse("# Title")
        val heading = blocks.single() as MdBlock.Heading
        assertEquals(1, heading.level)
        assertEquals("Title", heading.text)
    }

    @Test
    fun `strips trailing hashes from headings`() {
        val blocks = MarkdownParser.parse("## Foo ##")
        val heading = blocks.single() as MdBlock.Heading
        assertEquals(2, heading.level)
        assertEquals("Foo", heading.text)
    }

    @Test
    fun `parses complete fenced code`() {
        val blocks = MarkdownParser.parse("```kotlin\nval x = 1\n```")
        val code = blocks.single() as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
        assertTrue(code.isComplete)
    }

    @Test
    fun `marks unterminated fence as incomplete`() {
        val blocks = MarkdownParser.parse("```kotlin\nval x = 1")
        val code = blocks.single() as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
        assertFalse(code.isComplete)
    }

    @Test
    fun `parses tilde fences`() {
        val blocks = MarkdownParser.parse("~~~\ncode\n~~~")
        val code = blocks.single() as MdBlock.Code
        assertNull(code.language)
        assertEquals("code", code.code)
        assertTrue(code.isComplete)
    }

    @Test
    fun `parses bullet lists`() {
        val blocks = MarkdownParser.parse("- a\n- b")
        val list = blocks.single() as MdBlock.BulletList
        assertEquals(listOf("a", "b"), list.items.map { it.text })
    }

    @Test
    fun `parses task lists`() {
        val blocks = MarkdownParser.parse("- [x] done\n- [ ] todo")
        val list = blocks.single() as MdBlock.BulletList
        assertEquals(true, list.items[0].checked)
        assertEquals(false, list.items[1].checked)
        assertEquals("done", list.items[0].text)
        assertEquals("todo", list.items[1].text)
    }

    @Test
    fun `parses ordered lists with start number`() {
        val blocks = MarkdownParser.parse("3. third\n4. fourth")
        val list = blocks.single() as MdBlock.OrderedList
        assertEquals(3, list.start)
        assertEquals(listOf("third", "fourth"), list.items.map { it.text })
    }

    @Test
    fun `parses nested lists`() {
        val blocks = MarkdownParser.parse("- a\n  - b")
        val list = blocks.single() as MdBlock.BulletList
        val item = list.items.single()
        assertEquals("a", item.text)
        val nested = item.children.single() as MdBlock.BulletList
        assertEquals(listOf("b"), nested.items.map { it.text })
    }

    @Test
    fun `parses block quotes`() {
        val blocks = MarkdownParser.parse("> hello")
        val quote = blocks.single() as MdBlock.Quote
        val paragraph = quote.blocks.single() as MdBlock.Paragraph
        assertEquals("hello", paragraph.text)
    }

    @Test
    fun `parses multi line quotes`() {
        val blocks = MarkdownParser.parse("> line1\n> line2")
        val quote = blocks.single() as MdBlock.Quote
        val paragraph = quote.blocks.single() as MdBlock.Paragraph
        assertEquals("line1\nline2", paragraph.text)
    }

    @Test
    fun `parses tables`() {
        val blocks = MarkdownParser.parse("| a | b |\n|---|---|\n| 1 | 2 |")
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(MdAlignment.START, MdAlignment.START), table.alignments)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun `parses table alignment`() {
        val blocks = MarkdownParser.parse("| a | b |\n|:---:|---:|\n| 1 | 2 |")
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf(MdAlignment.CENTER, MdAlignment.END), table.alignments)
    }

    @Test
    fun `parses horizontal rules`() {
        val blocks = MarkdownParser.parse("---")
        assertTrue(blocks.single() is MdBlock.Rule)
    }

    @Test
    fun `parses paragraphs`() {
        val blocks = MarkdownParser.parse("hello world")
        val paragraph = blocks.single() as MdBlock.Paragraph
        assertEquals("hello world", paragraph.text)
    }

    @Test
    fun `splits paragraphs on blank lines`() {
        val blocks = MarkdownParser.parse("a\n\nb")
        assertEquals(2, blocks.size)
        assertEquals("a", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals("b", (blocks[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun `normalizes CRLF`() {
        val blocks = MarkdownParser.parse("a\r\nb")
        val paragraph = blocks.single() as MdBlock.Paragraph
        assertEquals("a\nb", paragraph.text)
    }

    @Test
    fun `tolerates truncated emphasis`() {
        val blocks = MarkdownParser.parse("**bold")
        val paragraph = blocks.single() as MdBlock.Paragraph
        assertEquals("**bold", paragraph.text)
    }

    @Test
    fun `a display formula on its own line is its own block`() {
        val blocks = MarkdownParser.parse("Before\n\n\$\$E = mc^2\$\$\n\nAfter")
        assertEquals(3, blocks.size)
        val math = blocks[1] as MdBlock.Math
        assertEquals("E = mc^2", math.latex)
        assertTrue(math.isComplete)
    }

    @Test
    fun `a display formula may span several lines`() {
        val blocks = MarkdownParser.parse("\$\$\na = b\n+ c\n\$\$")
        assertEquals("a = b\n+ c", (blocks.single() as MdBlock.Math).latex)
    }

    @Test
    fun `bracket delimiters open a display block too`() {
        assertEquals("x = 1", (MarkdownParser.parse("\\[x = 1\\]").single() as MdBlock.Math).latex)
    }

    @Test
    fun `a maths environment keeps its own delimiters for the maths parser`() {
        val source = "\\begin{pmatrix}\na & b\n\\end{pmatrix}"
        val math = MarkdownParser.parse(source).single() as MdBlock.Math
        assertTrue(math.latex.startsWith("\\begin{pmatrix}"))
        assertTrue(math.latex.endsWith("\\end{pmatrix}"))
    }

    @Test
    fun `an unterminated formula still renders what has arrived`() {
        // Exactly what the last block of a streaming answer looks like.
        val math = MarkdownParser.parse("\$\$\n\\frac{a}{b}").single() as MdBlock.Math
        assertEquals("\\frac{a}{b}", math.latex)
        assertFalse(math.isComplete)
    }

    @Test
    fun `a formula with prose after it stays a paragraph`() {
        // "$$x$$ and so on" is a sentence with a formula in it, not a displayed equation.
        val blocks = MarkdownParser.parse("\$\$x\$\$ and so on")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun `a paragraph is not swallowed by the formula that follows it`() {
        val blocks = MarkdownParser.parse("The result:\n\$\$x = 1\$\$")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Math)
    }

}
