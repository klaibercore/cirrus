package dev.klaiber.cirrus.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Explanations for controls whose names are shorter than their meaning.
 *
 * Android has no hover for touch, so the same copy is reachable three ways: hovering with a
 * mouse or stylus, long-pressing the control itself, or tapping the adjacent question mark.
 * Tooltips opened deliberately are persistent — a timed dismissal would race the reader.
 *
 * Neither entry point exposes Material's experimental tooltip types, so call sites need no
 * opt-in of their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpTooltip(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipScaffold(title = title, text = text, modifier = modifier, content = content)
}

/**
 * A question mark that reveals [text] when tapped, hovered or long-pressed.
 *
 * Sized to its 16dp icon rather than the usual 48dp target because it sits inside a settings row
 * that is itself tappable; a full-size button there would swallow half the row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpBadge(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    TooltipScaffold(title = title, text = text, modifier = modifier, state = state) {
        IconButton(
            onClick = { scope.launch { state.show() } },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = "What does \"$title\" do?",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TooltipScaffold(
    title: String,
    text: String,
    modifier: Modifier,
    state: TooltipState = rememberTooltipState(isPersistent = true),
    content: @Composable () -> Unit,
) {
    TooltipBox(
        // Anchored above: settings rows sit low enough that a tooltip below is often clipped.
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above,
        ),
        tooltip = {
            RichTooltip(
                title = { Text(title) },
                action = { TextButton(onClick = { state.dismiss() }) { Text("Got it") } },
                text = { Text(text) },
            )
        },
        state = state,
        modifier = modifier,
    ) {
        content()
    }
}
