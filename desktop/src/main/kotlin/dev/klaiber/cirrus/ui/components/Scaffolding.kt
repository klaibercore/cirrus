package dev.klaiber.cirrus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.ui.theme.LargeContainerShape
import dev.klaiber.cirrus.ui.theme.Pill

/**
 * The "there is nothing here yet, and that is fine" state.
 *
 * An empty list with no explanation reads as a bug. One icon, one line of what this screen is for,
 * and one line of how something gets into it turns the same screen into an invitation.
 *
 * The icon sits in an outlined ring rather than a filled colour blob. On a page whose only other
 * shapes are hairlines, a solid disc of container colour is the single heaviest mark on the screen —
 * and it lands on the one state where nothing important is happening.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = Pill,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(18.dp).size(26.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The label above a group of rows.
 *
 * Sentence case in muted ink, not tracked-out coloured caps. Two reasons it changed: `primary` is
 * now near-black, so a "coloured" label would simply be a second heading competing with the real
 * one; and uppercase tracking is a strong typographic flavour that belongs to a different design
 * than this one. The reference site labels its groups quietly and lets the hairline above them do
 * the separating.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

/**
 * The header every screen that is not the chat wears.
 *
 * It exists because three screens had grown the same twelve lines independently, and because the
 * two insets below are not decoration — get either wrong on macOS and the back button ends up
 * underneath the close button, which is the single worst bug a full-window-content layout can
 * ship.
 *
 * [topInset] is the strip the transparent title bar reaches down over, and [leadingInset] the
 * gutter the traffic lights sit in. Both are zero when the sidebar is standing to the left, since
 * the sidebar has already paid them; both are zero on every platform but macOS.
 *
 * The action slot is a real toolbar rather than Android's floating button. A FAB is an answer to a
 * thumb reaching across a phone, and a circle hovering over the bottom-right of a 1180pt window is
 * a phone idiom that has followed the code across rather than a decision anybody made.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(
    title: String,
    onBack: () -> Unit,
    topInset: Dp = 0.dp,
    leadingInset: Dp = 0.dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column {
        Spacer(Modifier.height(topInset))
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(start = leadingInset),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        // Same rule as the chat header, for the same reason: the page scrolls under an edge, not
        // under a shadow.
        Hairline()
    }
}

/**
 * The measure a column of prose or settings rows is allowed to reach.
 *
 * A desktop window is wider than any line anybody wants to read, and a settings row stretched to
 * 1180pt puts its switch so far from its label that the two stop looking related. Every screen
 * that is a single column centres itself in the window at this width instead of filling it.
 */
val ReadingMeasure = 780.dp

/**
 * [ReadingMeasure], applied.
 *
 * The order is the whole of it, and it is the opposite of the one that reads naturally.
 * `fillMaxWidth().widthIn(max)` does nothing: `fillMaxWidth` hands its child a *fixed* width, and
 * `widthIn` may only raise a maximum to meet a minimum it has been given, never lower it below
 * one. Capping first and then filling the cap is what actually holds a measure.
 */
fun Modifier.readingMeasure(max: Dp = ReadingMeasure): Modifier =
    this.widthIn(max = max).fillMaxWidth()

/**
 * A panel over the window: the model picker, the parameters, an agent's editor.
 *
 * These are `ModalBottomSheet`s on Android and were still bottom sheets here, which is the most
 * quietly wrong thing a ported interface can do. A sheet rising from the bottom edge is an answer
 * to a thumb at the bottom of a tall phone. In an 1180x820 window it puts a model picker at the
 * far edge of the screen from the pointer, anchors a form to the bottom of a window whose bottom is
 * nowhere near the eye, and leaves eight hundred points of dimmed application above it doing
 * nothing. A desktop panel is centred, and its size comes from its content rather than from which
 * edge it grew out of.
 *
 * The signature deliberately matches the sheet it replaces — `onDismissRequest` plus a
 * `ColumnScope` body — so the call sites are one word different and nothing inside them moved.
 * Scrolling is left to the content: several of these hold a `LazyColumn` with a cap of its own,
 * and nesting that inside a scroller in the same direction is a crash rather than a layout choice.
 */
@Composable
fun CirrusSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 580.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        // Compose's default caps a dialog at a phone's idea of a sensible width. These size
        // themselves against their content, so the platform default is declined and the cap above
        // is the real one.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = LargeContainerShape,
            color = MaterialTheme.colorScheme.surface,
            // A border rather than the elevation a dialog would default to. Depth is a hairline
            // everywhere else in this app, and a panel is not the place to make an exception.
            border = BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outlineVariant),
            modifier = modifier.widthIn(max = maxWidth).padding(vertical = 24.dp),
        ) {
            Column(content = content)
        }
    }
}
