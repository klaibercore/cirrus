package dev.klaiber.cirrus.ui.markdown.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the maths renderer that can be checked without a screen.
 *
 * Layout is judged by eye; structure is not. If `\frac{a}{b}` parses as a fraction with the right
 * two children, the typesetter will draw it correctly, and if it does not, no amount of tuning
 * constants will help.
 */
class MathParserTest {

    private fun parse(latex: String) = MathParser.parse(latex)

    private fun children(node: MathNode): List<MathNode> = when (node) {
        is MathNode.Row -> node.children
        else -> listOf(node)
    }

    @Test
    fun `a letter is an italic variable and a number is upright`() {
        val row = children(parse("x2"))
        assertEquals(MathNode.Glyph("x", Atom.ORD, MathFont.ITALIC), row[0])
        assertEquals(MathNode.Glyph("2", Atom.ORD, MathFont.UPRIGHT), row[1])
    }

    @Test
    fun `digits of one number stay one atom`() {
        // "1 2 8" spaced as three atoms would read as a product, not as a number.
        assertEquals(MathNode.Glyph("128", Atom.ORD, MathFont.UPRIGHT), parse("128"))
        assertEquals(MathNode.Glyph("3.14", Atom.ORD, MathFont.UPRIGHT), parse("3.14"))
    }

    @Test
    fun `superscripts and subscripts attach to their base`() {
        val squared = parse("x^2") as MathNode.Scripts
        assertEquals(MathNode.Glyph("x", Atom.ORD, MathFont.ITALIC), squared.base)
        assertEquals(MathNode.Glyph("2", Atom.ORD, MathFont.UPRIGHT), squared.superscript)
        assertEquals(null, squared.subscript)

        val both = parse("x_i^n") as MathNode.Scripts
        assertEquals(MathNode.Glyph("i", Atom.ORD, MathFont.ITALIC), both.subscript)
        assertEquals(MathNode.Glyph("n", Atom.ORD, MathFont.ITALIC), both.superscript)
    }

    @Test
    fun `a braced script keeps all of its content`() {
        // Without the braces this would raise only the minus sign and drop "x/2" to the baseline.
        val scripts = parse("e^{-x/2}") as MathNode.Scripts
        val exponent = children(scripts.superscript!!)
        assertEquals(listOf("−", "x", "/", "2"), exponent.map { (it as MathNode.Glyph).text })
    }

    @Test
    fun `a prime is a superscript`() {
        val scripts = parse("f'") as MathNode.Scripts
        assertEquals(MathNode.Glyph("f", Atom.ORD, MathFont.ITALIC), scripts.base)
        assertEquals(MathNode.Glyph("′", Atom.ORD, MathFont.UPRIGHT), scripts.superscript)
    }

    @Test
    fun `fractions keep their two arguments`() {
        val fraction = parse("\\frac{a+b}{c}") as MathNode.Fraction
        assertEquals(3, children(fraction.numerator).size)
        assertEquals(MathNode.Glyph("c", Atom.ORD, MathFont.ITALIC), fraction.denominator)
        assertTrue(fraction.rule)
    }

    @Test
    fun `binomials are a fraction without a rule, in parentheses`() {
        val fence = parse("\\binom{n}{k}") as MathNode.Fence
        assertEquals("(", fence.left)
        assertEquals(")", fence.right)
        assertEquals(false, (fence.body as MathNode.Fraction).rule)
    }

    @Test
    fun `roots take an optional degree`() {
        assertEquals(null, (parse("\\sqrt{2}") as MathNode.Root).index)
        assertEquals(
            MathNode.Glyph("3", Atom.ORD, MathFont.UPRIGHT),
            (parse("\\sqrt[3]{x}") as MathNode.Root).index,
        )
    }

    @Test
    fun `left and right build a fence around whatever is between them`() {
        val fence = parse("\\left( \\frac{a}{b} \\right)") as MathNode.Fence
        assertEquals("(", fence.left)
        assertEquals(")", fence.right)
        assertTrue(fence.body is MathNode.Fraction)
    }

    @Test
    fun `an invisible delimiter leaves that side open`() {
        val fence = parse("\\left\\{ x \\right.") as MathNode.Fence
        assertEquals("{", fence.left)
        assertEquals(null, fence.right)
    }

    @Test
    fun `big operators take their limits above and below`() {
        val scripts = parse("\\sum_{i=1}^{n} i") .let { children(it).first() } as MathNode.Scripts
        val base = scripts.base as MathNode.Glyph
        assertEquals("∑", base.text)
        assertTrue(base.big)
        assertTrue(base.limitsAbove)
    }

    @Test
    fun `an integral grows but keeps its limits at the side`() {
        val scripts = parse("\\int_0^1 x") .let { children(it).first() } as MathNode.Scripts
        val base = scripts.base as MathNode.Glyph
        assertEquals("∫", base.text)
        assertTrue(base.big)
        assertEquals(false, base.limitsAbove)
    }

    @Test
    fun `function names are one upright atom, not a product of letters`() {
        val row = children(parse("\\log n"))
        assertEquals(MathNode.Glyph("log", Atom.OP, MathFont.UPRIGHT), row[0])
    }

    @Test
    fun `matrices become a grid with the environment's delimiters`() {
        val grid = parse("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}") as MathNode.Grid
        assertEquals("(", grid.left)
        assertEquals(")", grid.right)
        assertEquals(2, grid.rows.size)
        assertEquals(2, grid.rows[0].size)
        assertEquals(MathNode.Glyph("d", Atom.ORD, MathFont.ITALIC), grid.rows[1][1])
    }

    @Test
    fun `cases opens a brace and aligns left`() {
        val grid = parse("\\begin{cases} 1 & x > 0 \\\\ 0 & x = 0 \\end{cases}") as MathNode.Grid
        assertEquals("{", grid.left)
        assertEquals(null, grid.right)
        assertEquals(GridAlign.START, grid.alignment)
        assertEquals(2, grid.rows.size)
    }

    @Test
    fun `aligned environments alternate their column alignment`() {
        val grid = parse("\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}") as MathNode.Grid
        assertTrue(grid.alternating)
        assertEquals(2, grid.rows.size)
    }

    @Test
    fun `a double backslash at the top level stacks the lines`() {
        val grid = parse("a = 1 \\\\ b = 2") as MathNode.Grid
        assertEquals(2, grid.rows.size)
    }

    @Test
    fun `relations and operators carry their spacing class`() {
        val row = children(parse("a + b = c"))
        assertEquals(Atom.BIN, (row[1] as MathNode.Glyph).atom)
        assertEquals(Atom.REL, (row[3] as MathNode.Glyph).atom)
    }

    @Test
    fun `a hyphen becomes a real minus sign`() {
        assertEquals(MathNode.Glyph("−", Atom.BIN, MathFont.UPRIGHT), children(parse("a - b"))[1])
    }

    @Test
    fun `text is kept verbatim, spaces included`() {
        assertEquals(MathNode.TextRun("if and only if"), parse("\\text{if and only if}"))
    }

    @Test
    fun `an operator name is one atom rather than a string of variables`() {
        assertEquals(
            MathNode.Glyph("softmax", Atom.OP, MathFont.UPRIGHT),
            parse("\\operatorname{softmax}"),
        )
    }

    @Test
    fun `blackboard bold maps to the character that already looks like it`() {
        assertEquals(MathNode.Glyph("ℝ", Atom.ORD, MathFont.DOUBLE_STRUCK), parse("\\mathbb{R}"))
    }

    @Test
    fun `an unknown command degrades to its own name`() {
        assertEquals(MathNode.Glyph("nonsense", Atom.ORD, MathFont.UPRIGHT), parse("\\nonsense"))
    }

    /**
     * Every one of these is a real formula caught mid-stream. None may throw, and none may hang:
     * the parser runs again on every token that arrives.
     */
    @Test
    fun `truncated and malformed input is survivable`() {
        val broken = listOf(
            "", "\\", "\\frac", "\\frac{a}", "\\frac{a}{", "x^", "x_{", "\\sqrt[",
            "\\left(", "\\right)", "\\begin{pmatrix} a &", "\\end{pmatrix}", "}}}", "{{{",
            "\\begin{", "a \\\\", "^2", "_i", "\\text{", "$", "&&&",
        )
        // The assertion is that every one of these returns at all: a parser that throws takes the
        // message down with it, and one that loops takes the frame rate.
        assertEquals(broken.size, broken.map { MathParser.parse(it) }.size)
    }

    @Test
    fun `a stray closing brace does not swallow the rest of the formula`() {
        // The scan has to step over the brace and keep going, not stop at it.
        val row = children(parse("a} + b"))
        assertTrue(row.any { it == MathNode.Glyph("b", Atom.ORD, MathFont.ITALIC) })
    }
}
