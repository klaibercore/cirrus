package dev.klaiber.cirrus.ui.markdown

import dev.klaiber.cirrus.ui.markdown.math.speakMath

/**
 * Turns a rendered answer back into something worth listening to.
 *
 * Handing raw markdown to a speech engine produces "asterisk asterisk important asterisk
 * asterisk", forty seconds of a URL being spelled out, and an entire code listing read as prose.
 * The structure that markdown encodes visually has to be re-encoded as pauses and short spoken
 * labels instead, because that is all speech has.
 *
 * The rules, in order of how much they matter:
 * - fenced code is announced, not read — nobody wants a shell script dictated;
 * - maths is spoken as words, so `x^2` is "x squared" rather than "x two";
 * - links keep their label and lose their target;
 * - tables are read as "heading: value" pairs, which is the only way a row makes sense in a
 *   medium with no columns;
 * - headings and list items end in a full stop, because a full stop is a pause.
 *
 * Pure and string-in, string-out, so what gets spoken can be asserted in a test.
 */
fun markdownToSpeech(markdown: String): String {
    val blocks = MarkdownParser.parse(markdown)
    return blocks.joinToString("\n") { speakBlock(it) }
        .replace(BLANK_LINES, "\n")
        .trim()
}

private fun speakBlock(block: MdBlock): String = when (block) {
    is MdBlock.Paragraph -> sentence(speakInline(block.text))

    is MdBlock.Heading -> sentence(speakInline(block.text))

    // The language is the useful part: "a Python code block" tells a listener what was skipped.
    is MdBlock.Code -> when (val language = block.language) {
        null -> "Code block."
        else -> "A $language code block."
    }

    is MdBlock.Math -> sentence(speakMath(block.latex))

    is MdBlock.Quote -> block.blocks.joinToString(" ") { speakBlock(it) }

    is MdBlock.BulletList -> block.items.joinToString("\n") { item ->
        val prefix = when (item.checked) {
            true -> "Done: "
            false -> "To do: "
            null -> ""
        }
        sentence(prefix + speakInline(item.text)) + speakChildren(item.children)
    }

    is MdBlock.OrderedList -> block.items.mapIndexed { index, item ->
        sentence("${block.start + index}. " + speakInline(item.text)) + speakChildren(item.children)
    }.joinToString("\n")

    is MdBlock.Table -> block.rows.joinToString("\n") { row ->
        row.mapIndexedNotNull { column, cell ->
            val value = speakInline(cell)
            if (value.isBlank()) {
                null
            } else {
                val heading = block.header.getOrNull(column)?.let(::speakInline).orEmpty()
                if (heading.isBlank()) value else "$heading: $value"
            }
        }.joinToString(", ").let(::sentence)
    }

    MdBlock.Rule -> ""
}

private fun speakChildren(children: List<MdBlock>): String =
    if (children.isEmpty()) "" else " " + children.joinToString(" ") { speakBlock(it) }

/**
 * Strips inline markup down to what should be heard.
 *
 * Deliberately not a second parser: the inline grammar is small enough that a scan for the four
 * things that matter — maths, code spans, links, emphasis — beats keeping two implementations of
 * the same syntax in step.
 */
private fun speakInline(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0

    while (index < text.length) {
        val char = text[index]

        when {
            char == '$' || (char == '\\' && (text.startsWith("\\(", index) || text.startsWith("\\[", index))) -> {
                val math = readMathSpan(text, index)
                if (math == null) {
                    out.append(char)
                    index++
                } else {
                    out.append(' ').append(speakMath(math.first)).append(' ')
                    index = math.second
                }
            }

            char == '`' -> {
                val close = text.indexOf('`', index + 1)
                if (close < 0) {
                    index++
                } else {
                    out.append(text, index + 1, close)
                    index = close + 1
                }
            }

            char == '[' -> {
                val label = text.indexOf(']', index)
                if (label > 0 && text.getOrNull(label + 1) == '(') {
                    val end = text.indexOf(')', label)
                    if (end > 0) {
                        out.append(speakInline(text.substring(index + 1, label)))
                        index = end + 1
                        continue
                    }
                }
                index++
            }

            char == '*' || char == '_' || char == '~' || char == '#' -> index++

            // A bare URL read character by character is unlistenable, and its text carries nothing.
            text.startsWith("http://", index, ignoreCase = true) ||
                text.startsWith("https://", index, ignoreCase = true) -> {
                out.append("link")
                while (index < text.length && !text[index].isWhitespace()) index++
            }

            else -> {
                out.append(char)
                index++
            }
        }
    }

    return out.toString().replace(WHITESPACE, " ").trim()
}

/** The body of a maths span at [start] and the index just past it, or null if it is not one. */
private fun readMathSpan(text: String, start: Int): Pair<String, Int>? {
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
    if (open == "$" && body.none { it.isLetterOrDigit() || it == '\\' }) return null
    return body to closeIndex + close.length
}

/** Speech has no line breaks; a full stop is how a pause gets into the audio. */
private fun sentence(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.last() in TERMINATORS) trimmed else "$trimmed."
}

private val TERMINATORS = setOf('.', '!', '?', ':', ';', ',')
private val WHITESPACE = Regex("\\s+")
private val BLANK_LINES = Regex("\n{2,}")
