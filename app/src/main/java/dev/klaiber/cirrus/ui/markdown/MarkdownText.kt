package dev.klaiber.cirrus.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.ui.theme.LocalCodeColors

/**
 * Renders a markdown document.
 *
 * Parsing is memoised on the source string, so a streaming message re-parses once per delta;
 * the parser is a single linear scan, which keeps that affordable for message-sized documents.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    MarkdownBlocks(blocks, modifier, textStyle, color)
}

@Composable
fun MarkdownBlocks(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val styles = markdownStyles()
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(blockSpacing(block)))
            RenderBlock(block, textStyle, color, styles)
        }
    }
}

@Composable
private fun markdownStyles(): MarkdownStyles {
    val codeColors = LocalCodeColors.current
    return MarkdownStyles(
        linkColor = MaterialTheme.colorScheme.primary,
        inlineCodeColor = codeColors.keyword,
        inlineCodeBackground = codeColors.background,
    )
}

private fun blockSpacing(block: MdBlock) = when (block) {
    is MdBlock.Heading -> 16.dp
    is MdBlock.Code -> 12.dp
    is MdBlock.Table -> 12.dp
    else -> 10.dp
}

@Composable
private fun RenderBlock(
    block: MdBlock,
    textStyle: TextStyle,
    color: Color,
    styles: MarkdownStyles,
) {
    when (block) {
        is MdBlock.Paragraph -> Text(
            text = buildInlineMarkdown(block.text, styles),
            style = textStyle,
            color = color,
        )

        is MdBlock.Heading -> Text(
            text = buildInlineMarkdown(block.text, styles),
            style = headingStyle(block.level),
            color = color,
        )

        is MdBlock.Code -> CodeBlock(
            code = block.code,
            language = block.language,
        )

        // IntrinsicSize.Min lets the accent bar match the height of the quoted content.
        is MdBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Spacer(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        RoundedCornerShape(2.dp),
                    ),
            )
            Column(Modifier.padding(start = 12.dp)) {
                block.blocks.forEachIndexed { index, child ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    RenderBlock(
                        child,
                        textStyle,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        styles,
                    )
                }
            }
        }

        is MdBlock.BulletList -> Column {
            block.items.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                ListRow(
                    marker = when (item.checked) {
                        true -> "☑"
                        false -> "☐"
                        null -> "•"
                    },
                    item = item,
                    textStyle = textStyle,
                    color = color,
                    styles = styles,
                )
            }
        }

        is MdBlock.OrderedList -> Column {
            block.items.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                ListRow(
                    marker = "${block.start + index}.",
                    item = item,
                    textStyle = textStyle,
                    color = color,
                    styles = styles,
                )
            }
        }

        is MdBlock.Table -> MarkdownTable(block, textStyle, color, styles)

        MdBlock.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun ListRow(
    marker: String,
    item: MdListItem,
    textStyle: TextStyle,
    color: Color,
    styles: MarkdownStyles,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = marker,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(if (marker.length > 2) 28.dp else 20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = buildInlineMarkdown(item.text, styles),
                style = textStyle,
                color = color,
            )
            if (item.children.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                item.children.forEachIndexed { index, child ->
                    if (index > 0) Spacer(Modifier.height(6.dp))
                    RenderBlock(child, textStyle, color, styles)
                }
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int) = when (level) {
    1 -> MaterialTheme.typography.headlineMedium
    2 -> MaterialTheme.typography.headlineSmall
    3 -> MaterialTheme.typography.titleLarge
    4 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}.copy(fontWeight = FontWeight.SemiBold)

@Composable
private fun MarkdownTable(
    table: MdBlock.Table,
    textStyle: TextStyle,
    color: Color,
    styles: MarkdownStyles,
) {
    // Columns are sized from their widest cell so the table scrolls instead of wrapping.
    val widths = remember(table) {
        List(table.header.size) { column ->
            val longest = (listOf(table.header.getOrNull(column).orEmpty()) +
                table.rows.map { it.getOrNull(column).orEmpty() })
                .maxOf { it.length }
            (longest * 8).coerceIn(72, 240).dp
        }
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState()),
    ) {
        Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
            table.header.forEachIndexed { column, cell ->
                Text(
                    text = buildInlineMarkdown(cell, styles),
                    style = textStyle.copy(fontWeight = FontWeight.SemiBold),
                    color = color,
                    textAlign = table.alignments.getOrNull(column).toTextAlign(),
                    modifier = Modifier
                        .width(widths.getOrElse(column) { 120.dp })
                        .padding(horizontal = 8.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        table.rows.forEach { row ->
            Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                row.forEachIndexed { column, cell ->
                    Text(
                        text = buildInlineMarkdown(cell, styles),
                        style = textStyle,
                        color = color,
                        textAlign = table.alignments.getOrNull(column).toTextAlign(),
                        modifier = Modifier
                            .width(widths.getOrElse(column) { 120.dp })
                            .padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

private fun MdAlignment?.toTextAlign(): TextAlign = when (this) {
    MdAlignment.CENTER -> TextAlign.Center
    MdAlignment.END -> TextAlign.End
    else -> TextAlign.Start
}
