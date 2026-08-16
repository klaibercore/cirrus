package dev.klaiber.cirrus.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/** The LaTeX subset models actually emit, mapped to Unicode. */
class MathToUnicodeTest {

    private fun render(latex: String) = renderMathToUnicode(latex)

    @Test
    fun `the complexity notation that started this`() {
        // Straight off a real answer, where it previously rendered with every backslash intact.
        assertEquals("O(n) + O(n) = O(n)", render("O(n) + O(n) = O(n)"))
        assertEquals("Total Parameters ≈ Compute", render("Total Parameters \\approx Compute"))
        assertEquals("Total Parameters ≫ Compute", render("Total Parameters \\gg Compute"))
    }

    @Test
    fun `relations and operators become symbols`() {
        assertEquals("≤ ≥ ≠ ≈ ≡ ∝ ≪ ≫", render("\\leq \\geq \\neq \\approx \\equiv \\propto \\ll \\gg"))
        assertEquals("× ÷ ± · ∑ ∏ ∫ ∞", render("\\times \\div \\pm \\cdot \\sum \\prod \\int \\infty"))
    }

    @Test
    fun `greek letters become greek letters`() {
        assertEquals("α β γ δ θ λ μ π σ φ ω", render("\\alpha \\beta \\gamma \\delta \\theta \\lambda \\mu \\pi \\sigma \\phi \\omega"))
        assertEquals("Γ Δ Θ Λ Σ Φ Ω", render("\\Gamma \\Delta \\Theta \\Lambda \\Sigma \\Phi \\Omega"))
    }

    @Test
    fun `superscripts are raised when unicode has the character`() {
        assertEquals("x²", render("x^2"))
        assertEquals("n⁻¹", render("n^{-1}"))
        assertEquals("2ⁿ", render("2^n"))
        assertEquals("O(n²)", render("O(n^2)"))
    }

    @Test
    fun `an unrepresentable superscript keeps its marker rather than being flattened`() {
        // "xk" would be a lie; "x^k" is at least what was written.
        assertEquals("x^k", render("x^k"))
        assertEquals("x^(a+b)", render("x^{a+b}"))
    }

    @Test
    fun `subscripts are lowered`() {
        assertEquals("x₁", render("x_1"))
        assertEquals("aₙ", render("a_n"))
        assertEquals("xᵢ", render("x_i"))
    }

    @Test
    fun `fractions become a slash, with brackets only where they are needed`() {
        assertEquals("a/b", render("\\frac{a}{b}"))
        assertEquals("½", render("\\frac{1}{2}"))
        assertEquals("(a+b)/c", render("\\frac{a+b}{c}"))
        assertEquals("n/2", render("\\frac{n}{2}"))
    }

    @Test
    fun `roots take the radical sign`() {
        assertEquals("√2", render("\\sqrt{2}"))
        assertEquals("√n", render("\\sqrt n"))
        assertEquals("√(a+b)", render("\\sqrt{a+b}"))
    }

    @Test
    fun `text and sizing commands are unwrapped`() {
        assertEquals("where n is the input", render("\\text{where n is the input}"))
        assertEquals("(x)", render("\\left(x\\right)"))
    }

    @Test
    fun `an unknown command degrades to its own name`() {
        // Strictly more readable than leaving the backslash in.
        assertEquals("widehat", render("\\widehat"))
        assertEquals("argmax", render("\\argmax"))
    }

    @Test
    fun `blackboard bold number sets`() {
        assertEquals("ℝ ℕ ℤ", render("\\mathbb{R} \\mathbb{N} \\mathbb{Z}"))
    }

    @Test
    fun `thin spaces disappear and escaped punctuation survives`() {
        assertEquals("ab", render("a\\,b"))
        assertEquals("{}", render("\\{\\}"))
        assertEquals("50%", render("50\\%"))
    }

    @Test
    fun `an unclosed group does not throw, because streaming produces them`() {
        assertEquals("√2", render("\\sqrt{2"))
        assertEquals("a/b", render("\\frac{a}{b"))
        assertEquals("\\", render("\\"))
    }

    @Test
    fun `arrows`() {
        assertEquals("→ ⇒ ↔ ⇔ ↦", render("\\to \\Rightarrow \\leftrightarrow \\iff \\mapsto"))
    }
}
