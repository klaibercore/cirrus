package dev.klaiber.cirrus.ui.markdown

/** Block-level markdown structure. Inline spans are resolved later, at render time. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock

    data class Heading(val level: Int, val text: String) : MdBlock

    /**
     * A fenced or indented code block. [isComplete] is false while a fence is still open,
     * which happens constantly during streaming and must render as code rather than as prose.
     */
    data class Code(val language: String?, val code: String, val isComplete: Boolean) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock

    data class BulletList(val items: List<MdListItem>) : MdBlock

    data class OrderedList(val start: Int, val items: List<MdListItem>) : MdBlock

    data class Table(
        val header: List<String>,
        val alignments: List<MdAlignment>,
        val rows: List<List<String>>,
    ) : MdBlock

    data object Rule : MdBlock
}

data class MdListItem(
    val text: String,
    val children: List<MdBlock> = emptyList(),
    /** Non-null for task-list syntax (`- [ ]` / `- [x]`). */
    val checked: Boolean? = null,
)

enum class MdAlignment { START, CENTER, END }

/**
 * A pragmatic CommonMark subset covering what chat models actually emit: headings, fenced code,
 * lists (nested, including task lists), block quotes, tables, rules and paragraphs.
 *
 * Written to be tolerant of truncated input, because it re-parses on every streamed token and
 * the tail of the document is routinely mid-construct.
 */
object MarkdownParser {

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val RULE = Regex("""^\s{0,3}(\*\s*\*\s*\*[\s*]*|-\s*-\s*-[\s-]*|_\s*_\s*_[\s_]*)$""")
    private val BULLET = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val ORDERED = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
    private val FENCE = Regex("""^(\s*)(`{3,}|~{3,})\s*([^`\s]*)\s*$""")
    private val TASK = Regex("""^\[([ xX])]\s+(.*)$""")
    private val TABLE_DIVIDER = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")

    fun parse(markdown: String): List<MdBlock> = parseLines(markdown.replace("\r\n", "\n").split('\n'))

    private fun parseLines(lines: List<String>): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]

            if (line.isBlank()) {
                index++
                continue
            }

            when {
                FENCE.matches(line) -> {
                    val match = FENCE.matchEntire(line)!!
                    val fence = match.groupValues[2]
                    val language = match.groupValues[3].takeIf { it.isNotBlank() }
                    val (block, next) = readFence(lines, index + 1, fence, language)
                    blocks += block
                    index = next
                }

                RULE.matches(line) -> {
                    blocks += MdBlock.Rule
                    index++
                }

                HEADING.matches(line) -> {
                    val match = HEADING.matchEntire(line)!!
                    blocks += MdBlock.Heading(
                        level = match.groupValues[1].length,
                        text = match.groupValues[2].trim().trimEnd('#').trim(),
                    )
                    index++
                }

                line.trimStart().startsWith(">") -> {
                    val (block, next) = readQuote(lines, index)
                    blocks += block
                    index = next
                }

                isTableStart(lines, index) -> {
                    val (block, next) = readTable(lines, index)
                    blocks += block
                    index = next
                }

                BULLET.matches(line) || ORDERED.matches(line) -> {
                    val (block, next) = readList(lines, index)
                    blocks += block
                    index = next
                }

                else -> {
                    val (block, next) = readParagraph(lines, index)
                    if (block != null) blocks += block
                    index = next
                }
            }
        }
        return blocks
    }

    private fun readFence(
        lines: List<String>,
        start: Int,
        fence: String,
        language: String?,
    ): Pair<MdBlock.Code, Int> {
        val fenceChar = fence.first()
        val body = mutableListOf<String>()
        var index = start
        var closed = false

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()
            // A closing fence is the same character, at least as long, with nothing after it.
            if (trimmed.startsWith(fence.take(3)) &&
                trimmed.all { it == fenceChar || it.isWhitespace() } &&
                trimmed.count { it == fenceChar } >= fence.length
            ) {
                closed = true
                index++
                break
            }
            body += line
            index++
        }

        return MdBlock.Code(
            language = language?.lowercase()?.takeIf { it.isNotBlank() },
            code = body.joinToString("\n"),
            isComplete = closed,
        ) to index
    }

    private fun readQuote(lines: List<String>, start: Int): Pair<MdBlock.Quote, Int> {
        val inner = mutableListOf<String>()
        var index = start
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()
            if (trimmed.startsWith(">")) {
                inner += trimmed.removePrefix(">").removePrefix(" ")
                index++
            } else if (line.isBlank()) {
                break
            } else {
                // Lazy continuation: a plain line still belongs to the quote paragraph.
                inner += line
                index++
            }
        }
        return MdBlock.Quote(parseLines(inner)) to index
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (!lines[index].contains('|')) return false
        val divider = lines.getOrNull(index + 1) ?: return false
        return divider.contains('-') && TABLE_DIVIDER.matches(divider)
    }

    private fun readTable(lines: List<String>, start: Int): Pair<MdBlock.Table, Int> {
        val header = splitRow(lines[start])
        val alignments = splitRow(lines[start + 1]).map { cell ->
            val trimmed = cell.trim()
            when {
                trimmed.startsWith(":") && trimmed.endsWith(":") -> MdAlignment.CENTER
                trimmed.endsWith(":") -> MdAlignment.END
                else -> MdAlignment.START
            }
        }

        val rows = mutableListOf<List<String>>()
        var index = start + 2
        while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
            val cells = splitRow(lines[index])
            // Pad or trim so every row lines up with the header.
            rows += List(header.size) { column -> cells.getOrNull(column).orEmpty() }
            index++
        }

        return MdBlock.Table(header, alignments, rows) to index
    }

    private fun splitRow(line: String): List<String> =
        line.trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split('|')
            .map { it.trim() }

    private fun readList(lines: List<String>, start: Int): Pair<MdBlock, Int> {
        val firstBullet = BULLET.matchEntire(lines[start])
        val ordered = firstBullet == null
        val baseIndent = (firstBullet ?: ORDERED.matchEntire(lines[start])!!).groupValues[1].length
        val startNumber = if (ordered) {
            ORDERED.matchEntire(lines[start])!!.groupValues[2].toIntOrNull() ?: 1
        } else {
            1
        }

        val items = mutableListOf<MdListItem>()
        var index = start

        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                // A blank line ends the list unless the next line continues it.
                val next = lines.getOrNull(index + 1)
                val continues = next != null && (BULLET.matches(next) || ORDERED.matches(next) ||
                    next.takeWhile { it == ' ' }.length > baseIndent)
                if (!continues) break
                index++
                continue
            }

            val match = if (ordered) ORDERED.matchEntire(line) else BULLET.matchEntire(line)
            if (match == null) break
            val indent = match.groupValues[1].length
            if (indent < baseIndent) break
            if (indent > baseIndent) {
                // Deeper marker without an intervening parent: treat as this level.
                index++
                continue
            }

            val rawText = match.groupValues[3]
            val task = TASK.matchEntire(rawText)
            val text = task?.groupValues?.get(2) ?: rawText
            val checked = task?.let { it.groupValues[1] != " " }

            // Gather indented continuation lines that belong to this item.
            val childLines = mutableListOf<String>()
            index++
            while (index < lines.size) {
                val candidate = lines[index]
                if (candidate.isBlank()) {
                    val next = lines.getOrNull(index + 1) ?: break
                    if (next.takeWhile { it == ' ' }.length <= baseIndent) break
                    childLines += ""
                    index++
                    continue
                }
                val candidateIndent = candidate.takeWhile { it == ' ' }.length
                if (candidateIndent <= baseIndent) break
                childLines += candidate.drop(baseIndent + CHILD_INDENT_UNIT).ifEmpty { candidate.trim() }
                index++
            }

            items += MdListItem(
                text = text,
                children = if (childLines.isEmpty()) emptyList() else parseLines(childLines),
                checked = checked,
            )
        }

        val block = if (ordered) MdBlock.OrderedList(startNumber, items) else MdBlock.BulletList(items)
        return block to index
    }

    private fun readParagraph(lines: List<String>, start: Int): Pair<MdBlock.Paragraph?, Int> {
        val body = mutableListOf<String>()
        var index = start
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank() ||
                FENCE.matches(line) ||
                HEADING.matches(line) ||
                RULE.matches(line) ||
                BULLET.matches(line) ||
                ORDERED.matches(line) ||
                line.trimStart().startsWith(">") ||
                isTableStart(lines, index)
            ) {
                if (index == start) {
                    // Never stall: consume the line so the caller always advances.
                    body += line
                    index++
                }
                break
            }
            body += line
            index++
        }
        val text = body.joinToString("\n").trim()
        return (if (text.isEmpty()) null else MdBlock.Paragraph(text)) to index
    }

    /** Spaces stripped from continuation lines before re-parsing them as nested blocks. */
    private const val CHILD_INDENT_UNIT = 2
}
