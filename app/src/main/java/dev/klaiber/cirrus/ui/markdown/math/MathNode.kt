package dev.klaiber.cirrus.ui.markdown.math

/**
 * The tree a LaTeX maths expression is parsed into, before it is given a size and a position.
 *
 * Kept free of Compose types on purpose: the parser is the half of the renderer that can be tested
 * on the JVM, and every layout decision belongs to [MathTypesetter] instead.
 */
internal sealed interface MathNode {

    /** A horizontal run. Spacing between the children is decided from their [Atom] classes. */
    data class Row(val children: List<MathNode>) : MathNode

    data class Glyph(
        val text: String,
        val atom: Atom,
        val font: MathFont = MathFont.UPRIGHT,
        /**
         * Grows in display style and takes its scripts above and below rather than beside —
         * `\sum`, `\prod`, `\lim`. Integrals are big but keep their limits at the side, which is
         * why this is not simply "is an operator".
         */
        val big: Boolean = false,
        val limitsAbove: Boolean = false,
    ) : MathNode

    data class Fraction(
        val numerator: MathNode,
        val denominator: MathNode,
        /** `\binom` stacks its arguments with no rule between them. */
        val rule: Boolean = true,
    ) : MathNode

    data class Scripts(
        val base: MathNode,
        val superscript: MathNode? = null,
        val subscript: MathNode? = null,
    ) : MathNode

    data class Root(val radicand: MathNode, val index: MathNode? = null) : MathNode

    /** `\left( … \right)`, with delimiters drawn tall enough for the body. */
    data class Fence(val left: String?, val body: MathNode, val right: String?) : MathNode

    /**
     * Matrices, `cases`, and the aligned environments — all the same grid with different
     * delimiters and column alignment.
     */
    data class Grid(
        val rows: List<List<MathNode>>,
        val left: String? = null,
        val right: String? = null,
        val alignment: GridAlign = GridAlign.CENTER,
        /**
         * True for `align`/`aligned`, where columns pair up as right-then-left around the relation
         * and so must not be evenly spaced.
         */
        val alternating: Boolean = false,
    ) : MathNode

    data class Accent(val base: MathNode, val kind: AccentKind) : MathNode

    /** A run of text set upright, from `\text{…}`. */
    data class TextRun(val text: String, val bold: Boolean = false, val italic: Boolean = false) : MathNode

    /** Explicit spacing, in ems: `\,` `\quad` and friends. */
    data class Space(val em: Float) : MathNode

    /** Nothing at all — an empty group, or a `{}` used only to bound a script. */
    data object Empty : MathNode
}

/**
 * TeX's atom classes, which is how maths gets its spacing: `a+b` is looser than `ab`, and `a=b`
 * looser still. Without this a formula reads as one undifferentiated string of symbols.
 */
internal enum class Atom { ORD, OP, BIN, REL, OPEN, CLOSE, PUNCT, INNER }

internal enum class MathFont { ITALIC, UPRIGHT, BOLD, BOLD_ITALIC, DOUBLE_STRUCK, MONO }

internal enum class GridAlign { START, CENTER, END }

internal enum class AccentKind { HAT, BAR, VEC, TILDE, DOT, DDOT }

/** Convenience for the common case of wrapping a list that may hold exactly one node. */
internal fun List<MathNode>.asRow(): MathNode = when (size) {
    0 -> MathNode.Empty
    1 -> single()
    else -> MathNode.Row(this)
}
