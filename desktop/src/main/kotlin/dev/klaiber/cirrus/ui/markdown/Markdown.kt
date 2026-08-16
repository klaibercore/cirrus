package dev.klaiber.cirrus.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A small CommonMark subset, rendered to Compose text.
 *
 * The Android build carries a hand-written parser that re-parses on every streamed token; the
 * desktop build renders the whole message on each recomposition instead, which is the same
 * result with none of the incremental machinery. The subset is the part people actually type in
 * a chat: headings, emphasis, inline code, fenced code blocks, lists, links and blockquotes.
 */

/** One block of a markdown document. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class ListItem(val text: String, val ordered: Boolean, val index: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
}

/** Splits markdown into blocks. Tolerant of truncated input, since it runs mid-stream. */
fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.isBlank() -> i++

            line.startsWith("```") -> {
                val language = line.removePrefix("```").trim()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    code.add(lines[i])
                    i++
                }
                i++ // skip the closing fence, if there is one
                blocks.add(MdBlock.Code(language.takeIf { it.isNotEmpty() }, code.joinToString("\n")))
            }

            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length.coerceAtMost(6)
                blocks.add(MdBlock.Heading(level, line.drop(level).trim()))
                i++
            }

            line.startsWith(">") -> {
                blocks.add(MdBlock.Quote(line.removePrefix(">").trim()))
                i++
            }

            line.startsWith("- ") || line.startsWith("* ") -> {
                blocks.add(MdBlock.ListItem(line.drop(2).trim(), ordered = false, index = 0))
                i++
            }

            ORDERED_ITEM.matches(line) -> {
                val index = line.substringBefore('.').toIntOrNull() ?: 0
                blocks.add(MdBlock.ListItem(line.substringAfter(". ").trim(), ordered = true, index = index))
                i++
            }

            else -> {
                val paragraph = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() && !isBlockStart(lines[i])) {
                    paragraph.add(lines[i].trim())
                    i++
                }
                blocks.add(MdBlock.Paragraph(paragraph.joinToString(" ")))
            }
        }
    }
    return blocks
}

private val ORDERED_ITEM = Regex("^\\d+\\.\\s+.*")

private fun isBlockStart(line: String): Boolean =
    line.startsWith("#") || line.startsWith(">") || line.startsWith("```") ||
        line.startsWith("- ") || line.startsWith("* ") || ORDERED_ITEM.matches(line)

/**
 * Renders inline emphasis, code and links into an [AnnotatedString].
 *
 * [linkColor] is the one colour that survives the monochrome scheme, because a link in the same
 * colour as the sentence around it is invisible.
 */
fun renderInline(text: String, baseStyle: SpanStyle, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(baseStyle.copy(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i]); i++
                    }
                }

                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(baseStyle.copy(fontFamily = FontFamily.Monospace)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }

                text.startsWith("[", i) -> {
                    val close = text.indexOf("](", i)
                    val end = if (close > i) text.indexOf(')', close + 2) else -1
                    if (close > i && end > close) {
                        val label = text.substring(i + 1, close)
                        val url = text.substring(close + 2, end)
                        withStyle(
                            baseStyle.copy(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) {
                            pushStringAnnotation("URL", url)
                            append(label)
                            pop()
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }

                text.startsWith("*", i) -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i) {
                        withStyle(baseStyle.copy(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }

                else -> {
                    append(text[i]); i++
                }
            }
        }
    }

/** Renders a markdown document as a column of blocks. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    linkColor: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier = modifier) {
        parseMarkdown(text).forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = renderInline(block.text, SpanStyle(), linkColor),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is MdBlock.Heading -> Text(
                    text = renderInline(block.text, SpanStyle(), linkColor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.Bold,
                )

                is MdBlock.Code -> CodeBlock(block)

                is MdBlock.ListItem -> Text(
                    text = renderInline(
                        if (block.ordered) "${block.index}. ${block.text}" else "•  ${block.text}",
                        SpanStyle(),
                        linkColor,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is MdBlock.Quote -> Text(
                    text = renderInline(block.text, SpanStyle(fontStyle = FontStyle.Italic), linkColor),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    Text(
        text = block.code,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
    )
}
