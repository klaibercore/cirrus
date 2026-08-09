package dev.klaiber.cirrus.ui.markdown

/**
 * Renders a practical subset of LaTeX maths as Unicode.
 *
 * Models reach for `$...$` constantly on technical questions — complexity bounds, Greek letters,
 * a stray `\approx` — and until now those arrived on screen verbatim, so an answer about big-O
 * read `$O(n) + O(n) = O(n)$`. Delimiters and backslashes are noise to everyone.
 *
 * This is not a typesetting engine and does not try to be. There is no layout: fractions become
 * `a/b`, and a superscript is only raised when Unicode happens to have the character. Anything
 * unrecognised degrades to its own name without the backslash, which is still strictly more
 * readable than the source. Rendering real LaTeX would mean a WebView or a font-level layout
 * engine, and neither is worth it to make `\approx` show up as ≈.
 *
 * Deliberately pure and string-in, string-out, so the whole mapping is testable without a
 * composition.
 */
internal fun renderMathToUnicode(latex: String): String {
    val out = StringBuilder(latex.length)
    var index = 0

    while (index < latex.length) {
        val char = latex[index]

        if (char == '\\') {
            val nameEnd = commandEnd(latex, index + 1)
            if (nameEnd == index + 1) {
                // A backslash before punctuation: LaTeX's thin spaces (\, \; \!) vanish, and an
                // escaped brace or percent is just that character.
                val next = latex.getOrNull(index + 1)
                when (next) {
                    ',', ';', '!', ':', ' ' -> Unit
                    null -> out.append('\\')
                    else -> out.append(next)
                }
                index += if (next == null) 1 else 2
                continue
            }

            val name = latex.substring(index + 1, nameEnd)
            index = nameEnd
            when {
                // Sizing hints have no meaning without layout.
                name == "left" || name == "right" || name == "big" || name == "Big" -> Unit

                name == "frac" || name == "dfrac" || name == "tfrac" -> {
                    val numerator = readGroup(latex, index)
                    val denominator = readGroup(latex, numerator.end)
                    index = denominator.end
                    out.append(fraction(numerator.text, denominator.text))
                }

                name == "sqrt" -> {
                    val radicand = readGroup(latex, index)
                    index = radicand.end
                    out.append('√').append(wrapIfCompound(renderMathToUnicode(radicand.text)))
                }

                name == "mathbb" -> {
                    val body = readGroup(latex, index)
                    index = body.end
                    out.append(BLACKBOARD[body.text] ?: body.text)
                }

                name == "text" || name == "mathrm" || name == "operatorname" ||
                    name == "mathbf" || name == "mathit" || name == "textbf" -> {
                    val body = readGroup(latex, index)
                    index = body.end
                    out.append(body.text)
                }

                else -> out.append(SYMBOLS[name] ?: name)
            }
            continue
        }

        if (char == '^' || char == '_') {
            val group = readGroup(latex, index + 1)
            index = group.end
            val rendered = renderMathToUnicode(group.text)
            val mapped = mapScript(rendered, superscript = char == '^')
            // Falling back to the literal marker keeps "x^{n+1}" legible rather than "xn+1".
            out.append(mapped ?: (char + wrapIfCompound(rendered)))
            continue
        }

        // Grouping braces carry no meaning once the group has been consumed by whatever wanted it.
        if (char == '{' || char == '}') {
            index++
            continue
        }

        out.append(char)
        index++
    }

    return out.toString()
}

/** A `{...}` group, or the single character that follows when there are no braces. */
private data class Group(val text: String, val end: Int)

private fun readGroup(source: String, start: Int): Group {
    var index = start
    while (index < source.length && source[index] == ' ') index++
    if (index >= source.length) return Group("", index)

    if (source[index] != '{') {
        // `x^2` and `\sqrt 2` take exactly one token, and a command counts as one.
        if (source[index] == '\\') {
            val end = commandEnd(source, index + 1)
            return Group(source.substring(index, end), end)
        }
        return Group(source[index].toString(), index + 1)
    }

    var depth = 0
    val contentStart = index + 1
    while (index < source.length) {
        when (source[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return Group(source.substring(contentStart, index), index + 1)
            }
        }
        index++
    }
    // Unclosed, which is what a half-streamed token looks like.
    return Group(source.substring(contentStart), source.length)
}

/** LaTeX command names are letters only; `\alpha2` is `\alpha` followed by a 2. */
private fun commandEnd(source: String, start: Int): Int {
    var index = start
    while (index < source.length && source[index].isLetter()) index++
    return index
}

private fun fraction(numerator: String, denominator: String): String {
    VULGAR_FRACTIONS["$numerator/$denominator"]?.let { return it }
    val top = renderMathToUnicode(numerator)
    val bottom = renderMathToUnicode(denominator)
    return "${wrapIfCompound(top)}/${wrapIfCompound(bottom)}"
}

/** `a+b` over `c` has to keep its brackets or it reads as `a + b/c`. */
private fun wrapIfCompound(text: String): String =
    if (text.length > 1 && text.any { it in "+-*/ " }) "($text)" else text

/** Null when even one character has no Unicode form — a partial raise looks broken. */
private fun mapScript(text: String, superscript: Boolean): String? {
    if (text.isEmpty()) return null
    val table = if (superscript) SUPERSCRIPTS else SUBSCRIPTS
    val out = StringBuilder(text.length)
    for (char in text) {
        out.append(table[char] ?: return null)
    }
    return out.toString()
}

private val SUPERSCRIPTS: Map<Char, Char> = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
    'n' to 'ⁿ', 'i' to 'ⁱ',
)

private val SUBSCRIPTS: Map<Char, Char> = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
    'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ', 'h' to 'ₕ',
    'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'p' to 'ₚ',
    's' to 'ₛ', 't' to 'ₜ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'r' to 'ᵣ',
    'u' to 'ᵤ', 'v' to 'ᵥ',
)

private val VULGAR_FRACTIONS: Map<String, String> = mapOf(
    "1/2" to "½", "1/3" to "⅓", "2/3" to "⅔", "1/4" to "¼", "3/4" to "¾",
)

/**
 * The commands that actually turn up in model output. Anything absent degrades to its own name,
 * so the cost of an omission is small and the table does not need to be exhaustive.
 */
private val SYMBOLS: Map<String, String> = mapOf(
    // Relations
    "approx" to "≈", "neq" to "≠", "ne" to "≠", "leq" to "≤", "le" to "≤",
    "geq" to "≥", "ge" to "≥", "equiv" to "≡", "sim" to "∼", "simeq" to "≃",
    "cong" to "≅", "propto" to "∝", "ll" to "≪", "gg" to "≫", "asymp" to "≍",
    // Operators
    "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "cdot" to "·",
    "ast" to "∗", "star" to "⋆", "circ" to "∘", "bullet" to "•", "oplus" to "⊕",
    "otimes" to "⊗", "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
    "partial" to "∂", "nabla" to "∇", "infty" to "∞", "surd" to "√",
    // Sets and logic
    "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "supset" to "⊃",
    "subseteq" to "⊆", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩",
    "emptyset" to "∅", "varnothing" to "∅", "setminus" to "∖",
    "forall" to "∀", "exists" to "∃", "nexists" to "∄",
    "land" to "∧", "lor" to "∨", "lnot" to "¬", "neg" to "¬",
    "therefore" to "∴", "because" to "∵",
    // Arrows
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
    "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔",
    "mapsto" to "↦", "implies" to "⇒", "iff" to "⇔", "uparrow" to "↑", "downarrow" to "↓",
    // Greek, lower case
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
    "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ",
    "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    // Greek, upper case
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
    "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ",
    "Omega" to "Ω",
    // Punctuation and spacing
    "ldots" to "…", "cdots" to "⋯", "dots" to "…", "vdots" to "⋮", "ddots" to "⋱",
    "quad" to "  ", "qquad" to "    ", "space" to " ",
    "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "degree" to "°",
    "prime" to "′", "hbar" to "ℏ", "ell" to "ℓ", "aleph" to "ℵ",
)

/** Blackboard bold, for the number sets that turn up in complexity discussions. */
private val BLACKBOARD: Map<String, String> = mapOf(
    "R" to "ℝ", "N" to "ℕ", "Z" to "ℤ", "Q" to "ℚ", "C" to "ℂ", "P" to "ℙ", "E" to "𝔼",
)
