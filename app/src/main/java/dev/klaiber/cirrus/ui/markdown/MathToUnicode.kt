package dev.klaiber.cirrus.ui.markdown

import dev.klaiber.cirrus.ui.markdown.math.MATH_BLACKBOARD
import dev.klaiber.cirrus.ui.markdown.math.MATH_SYMBOLS

/**
 * Flattens a LaTeX maths span to a single line of Unicode.
 *
 * Formulas are typeset properly on screen — see `math/MathTypesetter` — but a drawn formula is not
 * text, and plenty of places need text: the string the clipboard receives when a message is
 * copied, the alternate text behind an inline placeholder so that selecting a paragraph selects
 * something sensible, the plain-text export, and what read-aloud speaks.
 *
 * There is no layout here on purpose: fractions become `a/b`, and a superscript is only raised
 * when Unicode happens to have the character. Anything unrecognised degrades to its own name
 * without the backslash, which is still strictly more readable than the source.
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
                    out.append(MATH_BLACKBOARD[body.text] ?: body.text)
                }

                name == "text" || name == "mathrm" || name == "operatorname" ||
                    name == "mathbf" || name == "mathit" || name == "textbf" -> {
                    val body = readGroup(latex, index)
                    index = body.end
                    out.append(body.text)
                }

                else -> out.append(MATH_SYMBOLS[name] ?: name)
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
