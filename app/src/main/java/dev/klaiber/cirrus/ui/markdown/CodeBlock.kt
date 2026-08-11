package dev.klaiber.cirrus.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.ui.theme.CodeTextStyle
import dev.klaiber.cirrus.ui.theme.LocalCodeColors
import dev.klaiber.cirrus.ui.util.rememberClipboard
import kotlinx.coroutines.delay

/**
 * A fenced code block with a language label, copy action and a wrap toggle.
 *
 * Wrapping is off by default because indentation carries meaning in code; the toggle exists
 * because horizontal scrolling inside a vertically scrolling chat is awkward on a phone.
 */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
) {
    val codeColors = LocalCodeColors.current
    val clipboard = rememberClipboard()
    var wrapped by remember { mutableStateOf(false) }
    var justCopied by remember { mutableStateOf(false) }

    // Highlighting is the expensive part; recompute only when the inputs actually change.
    val highlighted = remember(code, language, codeColors) {
        SyntaxHighlighter.highlight(code, language, codeColors)
    }

    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(COPY_FEEDBACK_MS)
            justCopied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(codeColors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = language?.takeIf { it.isNotBlank() } ?: "text",
                style = MaterialTheme.typography.labelSmall,
                color = codeColors.comment,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { wrapped = !wrapped },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WrapText,
                        contentDescription = if (wrapped) "Disable wrapping" else "Wrap long lines",
                        tint = if (wrapped) MaterialTheme.colorScheme.primary else codeColors.comment,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.copy(code, label = language ?: "code")
                        justCopied = !clipboard.showsSystemConfirmation
                    },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = if (justCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = "Copy code",
                        tint = if (justCopied) MaterialTheme.colorScheme.primary else codeColors.comment,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (wrapped) Modifier else Modifier.horizontalScroll(rememberScrollState()),
                )
                .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 14.dp),
        ) {
            Text(
                text = highlighted,
                style = CodeTextStyle,
                softWrap = wrapped,
            )
        }
    }
}

/** Inline monospace text used by the inspector and other raw-JSON surfaces. */
@Composable
fun MonospaceBlock(
    text: String,
    modifier: Modifier = Modifier,
    language: String? = "json",
) {
    val codeColors = LocalCodeColors.current
    val highlighted = remember(text, language, codeColors) {
        SyntaxHighlighter.highlight(text, language, codeColors)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(codeColors.background)
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(text = highlighted, style = CodeTextStyle, softWrap = false)
    }
}

private const val COPY_FEEDBACK_MS = 1_600L
