package dev.klaiber.cirrus.ui.markdown.math

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.ui.util.rememberClipboard
import kotlinx.coroutines.delay

/**
 * A typesetter bound to the current text size, colour and density.
 *
 * Remembered rather than rebuilt, because it owns the text-measurement cache — a formula is
 * re-typeset on every recomposition of the message it sits in, and a streaming answer recomposes
 * on every token.
 */
@Composable
internal fun rememberMathTypesetter(fontSize: TextUnit, color: Color): MathTypesetter {
    val measurer = rememberTextMeasurer(cacheSize = MEASURE_CACHE)
    val density = LocalDensity.current
    val sizePx = with(density) { fontSize.toPx() }
    return remember(measurer, density, sizePx, color) {
        MathTypesetter(measurer, density, sizePx, color)
    }
}

/**
 * A displayed equation: its own line, centred, scrolling sideways when it is too wide.
 *
 * Long-press copies the LaTeX source. Selecting a formula the way you select a sentence is not
 * possible — it is drawn, not laid out as text — and the source is what anyone would want to paste
 * into a paper or another chat anyway.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = MaterialTheme.typography.bodyLarge.fontSize,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val typesetter = rememberMathTypesetter(fontSize, color)
    val box = remember(latex, typesetter) {
        typesetter.typeset(MathParser.parse(latex), display = true)
    }
    val density = LocalDensity.current
    val clipboard = rememberClipboard()
    val haptics = LocalHapticFeedback.current
    var justCopied by remember { mutableStateOf(false) }

    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(COPY_FEEDBACK_MS)
            justCopied = false
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = maxWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = available)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            clipboard.copy(latex, label = "LaTeX")
                            justCopied = !clipboard.showsSystemConfirmation
                        },
                        onLongClickLabel = "Copy LaTeX",
                    )
                    .padding(vertical = DISPLAY_PADDING.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    modifier = Modifier
                        .size(
                            width = with(density) { box.width.toDp() },
                            height = with(density) { box.height.toDp() },
                        ),
                ) {
                    box.draw(this, 0f, box.ascent)
                }
            }
        }

        if (justCopied) {
            Text(
                text = "LaTeX copied",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/**
 * An inline formula, as a placeholder inside a paragraph.
 *
 * The placeholder is made symmetric about the maths axis so that Compose's `TextCenter` alignment
 * — which centres it on the line's own middle — puts the axis where a fraction bar or a minus sign
 * belongs. Anchoring to the baseline instead would leave anything with a descender hanging below
 * the line.
 */
internal fun MathTypesetter.inlineMath(latex: String): InlineTextContent {
    val box = typeset(MathParser.parse(latex), display = false)
    val reach = maxOf(box.ascent - axis, box.descent + axis, MIN_INLINE_REACH * baseSize)
    val height = reach * 2f
    val baseline = reach + axis
    val width = box.width.coerceAtLeast(1f)

    return InlineTextContent(
        placeholder = Placeholder(
            width = with(density) { width.toSp() },
            height = with(density) { height.toSp() },
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        ),
    ) {
        Canvas(Modifier.fillMaxWidth().height(with(density) { height.toDp() })) {
            box.draw(this, 0f, baseline)
        }
    }
}

private const val MEASURE_CACHE = 96
private const val COPY_FEEDBACK_MS = 1_600L
private const val DISPLAY_PADDING = 4
private const val MIN_INLINE_REACH = 0.4f
