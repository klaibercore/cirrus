package dev.klaiber.cirrus.ui.markdown

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

data class MarkdownStyles(
    val linkColor: Color,
    val inlineCodeColor: Color,
    val inlineCodeBackground: Color,
)

/**
 * Renders inline markdown into an [AnnotatedString].
 *
 * Emphasis is resolved recursively so nesting (`**bold with *italic* inside**`) works, and any
 * delimiter without a partner is emitted as literal text — which is what a half-streamed
 * `**bold` looks like a moment before its closing marker arrives.
 */
fun buildInlineMarkdown(text: String, styles: MarkdownStyles): AnnotatedString =
    buildAnnotatedString { appendInline(text, styles) }

private fun AnnotatedString.Builder.appendInline(text: String, styles: MarkdownStyles) {
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
                    appendInline(text.substring(index + 2, closeIndex), styles)
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
                    appendInline(text.substring(contentStart, closeIndex), styles)
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
                    appendInline(parsed.label, styles)
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
