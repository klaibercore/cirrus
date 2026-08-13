package dev.klaiber.cirrus.ui.markdown

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import dev.klaiber.cirrus.ui.markdown.math.MathTypesetter
import dev.klaiber.cirrus.ui.markdown.math.inlineMath

internal data class MarkdownStyles(
    val linkColor: Color,
    val inlineCodeColor: Color,
    val inlineCodeBackground: Color,
    /**
     * Typesets `$…$` spans properly. Null falls back to the Unicode approximation, which is what
     * the JVM tests use and what any caller without a composition gets.
     */
    val math: MathTypesetter? = null,
    /** What find-in-conversation is looking for; every occurrence gets a background. */
    val highlight: String = "",
    val highlightColor: Color = Color.Unspecified,
)

/**
 * Inline markdown, ready for [androidx.compose.material3.Text].
 *
 * [inlineContent] holds the typeset formulas. They are placeholders in [annotated] rather than
 * characters, so the string still reads as text — the Unicode form of each formula stands in its
 * place, which is what the clipboard gets when the paragraph is selected and copied.
 */
internal data class InlineMarkdown(
    val annotated: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent> = emptyMap(),
)

/**
 * Renders inline markdown into an [AnnotatedString].
 *
 * Emphasis is resolved recursively so nesting (`**bold with *italic* inside**`) works, and any
 * delimiter without a partner is emitted as literal text — which is what a half-streamed
 * `**bold` looks like a moment before its closing marker arrives.
 */
internal fun buildInlineMarkdown(text: String, styles: MarkdownStyles): InlineMarkdown {
    val contents = mutableMapOf<String, InlineTextContent>()
    val annotated = buildAnnotatedString { appendInline(text, styles, contents) }
    return InlineMarkdown(annotated, contents)
}

private fun AnnotatedString.Builder.appendInline(
    text: String,
    styles: MarkdownStyles,
    contents: MutableMap<String, InlineTextContent>,
) {
    var index = 0
    val plain = StringBuilder()

    fun flush() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.setLength(0)
        }
    }

    while (index < text.length) {
        val char = text[index]

        // Maths delimiters are checked before the escape rule, which would otherwise eat the
        // backslash in `\(` and leave a bare bracket.
        if (char == '$' || (char == '\\' && (text.startsWith("\\(", index) ||
                text.startsWith("\\[", index)))
        ) {
            val math = parseMath(text, index)
            if (math != null) {
                flush()
                appendMath(math.body, styles, contents)
                index = math.endIndex
                continue
            }
        }

        // Backslash escapes only apply to punctuation, per CommonMark.
        if (char == '\\' && index + 1 < text.length && !text[index + 1].isLetterOrDigit()) {
            plain.append(text[index + 1])
            index += 2
            continue
        }

        if (char == '`') {
            val runLength = text.runLengthAt(index, '`')
            val closeIndex = text.indexOfRun(index + runLength, '`', runLength)
            if (closeIndex < 0) {
                plain.append(text, index, index + runLength)
                index += runLength
            } else {
                flush()
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = styles.inlineCodeColor,
                        background = styles.inlineCodeBackground,
                    ),
                ) {
                    append(text.substring(index + runLength, closeIndex).trim(' '))
                }
                index = closeIndex + runLength
            }
            continue
        }

        if (char == '~' && text.startsWith("~~", index)) {
            val closeIndex = text.indexOf("~~", index + 2)
            if (closeIndex < 0) {
                plain.append("~~")
                index += 2
            } else {
                flush()
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInline(text.substring(index + 2, closeIndex), styles, contents)
                }
                index = closeIndex + 2
            }
            continue
        }

        if (char == '*' || char == '_') {
            // `_` inside a word (snake_case) must not start emphasis.
            val isIntraWord = char == '_' &&
                index > 0 && text[index - 1].isLetterOrDigit() &&
                text.getOrNull(index + 1)?.isLetterOrDigit() == true
            if (isIntraWord) {
                plain.append(char)
                index++
                continue
            }

            val isStrong = text.startsWith("$char$char", index)
            val delimiter = if (isStrong) "$char$char" else "$char"
            val contentStart = index + delimiter.length
            val closeIndex = text.indexOfDelimiter(contentStart, delimiter)

            if (closeIndex <= contentStart) {
                plain.append(char)
                index++
            } else {
                flush()
                val span = if (isStrong) {
                    SpanStyle(fontWeight = FontWeight.Bold)
                } else {
                    SpanStyle(fontStyle = FontStyle.Italic)
                }
                withStyle(span) {
                    appendInline(text.substring(contentStart, closeIndex), styles, contents)
                }
                index = closeIndex + delimiter.length
            }
            continue
        }

        if (char == '[') {
            val parsed = parseLink(text, index)
            if (parsed != null) {
                flush()
                withLink(linkAnnotation(parsed.url, styles)) {
                    appendInline(parsed.label, styles, contents)
                }
                index = parsed.endIndex
                continue
            }
        }

        // Angle-bracket autolinks: <https://example.com>
        if (char == '<') {
            val closeIndex = text.indexOf('>', index + 1)
            val candidate = if (closeIndex > index) text.substring(index + 1, closeIndex) else ""
            if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                flush()
                withLink(linkAnnotation(candidate, styles)) { append(candidate) }
                index = closeIndex + 1
                continue
            }
        }

        // Bare URLs, but only at a word boundary so "foo.https://" is left alone.
        if ((char == 'h' || char == 'H') &&
            (text.startsWith("http://", index, ignoreCase = true) ||
                text.startsWith("https://", index, ignoreCase = true)) &&
            (index == 0 || !text[index - 1].isLetterOrDigit())
        ) {
            var end = index
            while (end < text.length && !text[end].isWhitespace() && text[end] != '<') end++
            // Trailing punctuation belongs to the sentence, not the URL.
            while (end > index && text[end - 1] in TRAILING_PUNCTUATION) end--
            val url = text.substring(index, end)
            flush()
            withLink(linkAnnotation(url, styles)) { append(url) }
            index = end
            continue
        }

        plain.append(char)
        index++
    }
    flush()
}

/**
 * Places one inline formula.
 *
 * The typeset box goes in as a placeholder; the Unicode form of the same formula goes in as the
 * text behind it. That is what makes a rendered paragraph copyable — select it and the clipboard
 * gets `x² + 1`, not a gap where the maths was.
 */
private fun AnnotatedString.Builder.appendMath(
    body: String,
    styles: MarkdownStyles,
    contents: MutableMap<String, InlineTextContent>,
) {
    val fallback = renderMathToUnicode(body).ifBlank { body }
    val typesetter = styles.math
    if (typesetter == null || fallback.isEmpty()) {
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(fallback) }
        return
    }
    val id = "math-${contents.size}"
    contents[id] = typesetter.inlineMath(body)
    appendInlineContent(id, fallback)
}

private class Math(val body: String, val endIndex: Int)

/**
 * Matches a maths span at [start], or returns null to leave the character as literal text.
 *
 * The `$` form needs care, because prose is full of dollar signs. Two rules keep currency out:
 * the opening `$` must be followed immediately by a non-space, and the closing one preceded by a
 * non-space. "$5 and $10" fails the second — the candidate content "5 and " ends in a space — so
 * it stays as written. A span may not cross a blank line either, which stops one stray `$` from
 * swallowing the rest of a message.
 */
private fun parseMath(text: String, start: Int): Math? {
    val (open, close) = when {
        text.startsWith("$$", start) -> "$$" to "$$"
        text.startsWith("\\(", start) -> "\\(" to "\\)"
        text.startsWith("\\[", start) -> "\\[" to "\\]"
        text[start] == '$' -> "$" to "$"
        else -> return null
    }

    val contentStart = start + open.length
    if (contentStart >= text.length) return null
    if (open == "$" && text[contentStart].isWhitespace()) return null

    val closeIndex = text.indexOf(close, contentStart)
    if (closeIndex <= contentStart) return null
    if (open == "$" && text[closeIndex - 1].isWhitespace()) return null

    val body = text.substring(contentStart, closeIndex)
    if (body.contains("\n\n")) return null
    // Without this, "100$ or 200$" would render "or" as maths.
    if (open == "$" && body.none { it.isLetterOrDigit() || it == '\\' }) return null

    return Math(body, closeIndex + close.length)
}

private fun linkAnnotation(url: String, styles: MarkdownStyles) = LinkAnnotation.Url(
    url = url,
    styles = TextLinkStyles(
        style = SpanStyle(
            color = styles.linkColor,
            textDecoration = TextDecoration.Underline,
        ),
    ),
)

private data class ParsedLink(val label: String, val url: String, val endIndex: Int)

/** Parses `[label](url "optional title")`, tolerating nested brackets in the label. */
private fun parseLink(text: String, start: Int): ParsedLink? {
    var depth = 0
    var labelEnd = -1
    var i = start
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i++
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) {
                    labelEnd = i
                    break
                }
            }
        }
        i++
    }
    if (labelEnd < 0 || text.getOrNull(labelEnd + 1) != '(') return null

    val urlStart = labelEnd + 2
    var parenDepth = 1
    var j = urlStart
    while (j < text.length) {
        when (text[j]) {
            '(' -> parenDepth++
            ')' -> {
                parenDepth--
                if (parenDepth == 0) break
            }
        }
        j++
    }
    if (j >= text.length) return null

    val target = text.substring(urlStart, j).trim()
    // Strip an optional title: [x](url "title")
    val url = target.substringBefore(' ').trim()
    if (url.isEmpty()) return null

    return ParsedLink(
        label = text.substring(start + 1, labelEnd),
        url = url,
        endIndex = j + 1,
    )
}

private fun String.runLengthAt(index: Int, char: Char): Int {
    var count = 0
    while (index + count < length && this[index + count] == char) count++
    return count
}

/** Finds a run of exactly [length] occurrences of [char], used to close inline code spans. */
private fun String.indexOfRun(from: Int, char: Char, length: Int): Int {
    var i = from
    while (i < this.length) {
        if (this[i] == char) {
            val run = runLengthAt(i, char)
            if (run == length) return i
            i += run
        } else {
            i++
        }
    }
    return -1
}

/** Finds the closing emphasis delimiter, skipping escapes and inline-code spans. */
private fun String.indexOfDelimiter(from: Int, delimiter: String): Int {
    var i = from
    while (i < length) {
        when {
            this[i] == '\\' -> i += 2
            this[i] == '`' -> {
                val run = runLengthAt(i, '`')
                val close = indexOfRun(i + run, '`', run)
                i = if (close < 0) i + run else close + run
            }
            startsWith(delimiter, i) -> return i
            else -> i++
        }
    }
    return -1
}

private val TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')

/**
 * Marks every occurrence of [query], leaving the spans already on the string intact.
 *
 * Applied after the markdown is built rather than during it: a match can straddle a bold run or
 * an inline code span, and trying to catch that while parsing would mean the search knowing about
 * every construct in the grammar.
 */
internal fun AnnotatedString.highlighting(query: String, color: Color): AnnotatedString {
    if (query.isBlank() || color == Color.Unspecified) return this
    var at = text.indexOf(query, ignoreCase = true)
    if (at < 0) return this

    return buildAnnotatedString {
        append(this@highlighting)
        while (at >= 0) {
            addStyle(SpanStyle(background = color), at, at + query.length)
            at = text.indexOf(query, at + query.length, ignoreCase = true)
        }
    }
}
