package dev.klaiber.cirrus.ui.markdown.math

/**
 * Says a formula out loud.
 *
 * Read-aloud is the one place where the Unicode form is worse than useless: a speech engine given
 * `∑` either skips it or spells out a character name, and `x²` becomes "x two". Walking the parsed
 * tree instead means the structure survives — `\frac{a}{b}` is "a over b", and a superscript is
 * "squared" rather than a digit that changes the meaning of the sentence.
 */
internal fun speakMath(latex: String): String =
    speak(MathParser.parse(latex)).replace(WHITESPACE, " ").trim()

private fun speak(node: MathNode): String = when (node) {
    is MathNode.Row -> node.children.joinToString(" ") { speak(it) }

    // A bare big operator still wants its "of": "the sum of x" rather than "the sum x".
    is MathNode.Glyph -> if (node.big) "${word(node)} of" else word(node)

    is MathNode.Fraction -> {
        val numerator = speak(node.numerator)
        val denominator = speak(node.denominator)
        if (node.rule) "$numerator over $denominator" else "$numerator choose $denominator"
    }

    is MathNode.Scripts -> {
        val base = node.base
        if (base is MathNode.Glyph && (base.big || base.limitsAbove)) {
            // Limits are bounds, not scripts: "from i equals 1 to n" is what a person would say,
            // and "sub i equals 1 to the power of n" is what a transcriber would write.
            spokenLimits(base, node)
        } else {
            buildString {
                append(speak(base))
                node.subscript?.let { append(" sub ").append(speak(it)) }
                node.superscript?.let { append(" ").append(power(speak(it))) }
            }
        }
    }

    is MathNode.Root -> when (val degree = node.index?.let(::speak)) {
        null -> "the square root of ${speak(node.radicand)}"
        else -> "the $degree root of ${speak(node.radicand)}"
    }

    // Brackets are heard as a pause, which is what a comma buys in a spoken sentence.
    is MathNode.Fence -> ", ${speak(node.body)} ,"

    is MathNode.Grid -> node.rows.joinToString(", ") { row -> row.joinToString(" ") { speak(it) } }

    is MathNode.Accent -> "${speak(node.base)} ${SPOKEN_ACCENTS.getValue(node.kind)}"

    is MathNode.TextRun -> node.text

    is MathNode.Space -> " "

    MathNode.Empty -> ""
}

private fun word(glyph: MathNode.Glyph): String = SPOKEN_SYMBOLS[glyph.text] ?: glyph.text

/**
 * An operator and its bounds, said the way it would be said out loud.
 *
 * `\lim` takes "as" rather than "from", because a limit approaches a value while a sum runs
 * between two of them.
 */
private fun spokenLimits(base: MathNode.Glyph, node: MathNode.Scripts): String = buildString {
    append(word(base))
    val approaches = base.text in APPROACHING
    node.subscript?.let { append(if (approaches) " as " else " from ").append(speak(it)) }
    node.superscript?.let { append(" to ").append(speak(it)) }
    append(", of")
}

private val APPROACHING = setOf("lim", "limsup", "liminf")

private fun power(exponent: String): String = when (exponent) {
    "2" -> "squared"
    "3" -> "cubed"
    "′" -> "prime"
    else -> "to the power of $exponent"
}

private val SPOKEN_ACCENTS: Map<AccentKind, String> = mapOf(
    AccentKind.HAT to "hat",
    AccentKind.BAR to "bar",
    AccentKind.VEC to "vector",
    AccentKind.TILDE to "tilde",
    AccentKind.DOT to "dot",
    AccentKind.DDOT to "double dot",
)

/**
 * Only the symbols whose spoken form differs from the character itself. Greek letters are absent
 * on purpose — every engine already reads `α` as "alpha".
 */
private val SPOKEN_SYMBOLS: Map<String, String> = mapOf(
    "+" to "plus", "−" to "minus", "-" to "minus", "±" to "plus or minus",
    "×" to "times", "·" to "times", "∗" to "times", "÷" to "divided by", "/" to "over",
    "=" to "equals", "≠" to "does not equal", "≈" to "is approximately",
    "≡" to "is equivalent to", "∼" to "is similar to", "∝" to "is proportional to",
    "<" to "is less than", ">" to "is greater than",
    "≤" to "is less than or equal to", "≥" to "is greater than or equal to",
    "≪" to "is much less than", "≫" to "is much greater than",
    "∑" to "the sum", "∏" to "the product", "∫" to "the integral",
    "∮" to "the contour integral", "⋃" to "the union", "⋂" to "the intersection",
    "lim" to "the limit", "max" to "the maximum", "min" to "the minimum",
    "sup" to "the supremum", "inf" to "the infimum",
    "∂" to "partial", "∇" to "gradient", "∞" to "infinity",
    "∈" to "is in", "∉" to "is not in", "⊂" to "is a subset of", "⊆" to "is a subset of",
    "∪" to "union", "∩" to "intersection", "∅" to "the empty set", "∖" to "without",
    "∀" to "for all", "∃" to "there exists", "¬" to "not", "∧" to "and", "∨" to "or",
    "→" to "goes to", "←" to "from", "↔" to "if and only if",
    "⇒" to "implies", "⇐" to "is implied by", "⇔" to "if and only if", "↦" to "maps to",
    "∴" to "therefore", "∵" to "because", "…" to "and so on", "⋯" to "and so on",
    "ℝ" to "the reals", "ℕ" to "the naturals", "ℤ" to "the integers",
    "ℚ" to "the rationals", "ℂ" to "the complex numbers", "𝔼" to "the expectation",
    "°" to "degrees", "′" to "prime", "%" to "percent",
    "|" to "given", "!" to "factorial",
)

private val WHITESPACE = Regex("\\s+")
