package dev.klaiber.cirrus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.Pill

/**
 * The design vocabulary, in one place.
 *
 * ollama.com is built from a handful of parts repeated everywhere — a bordered panel, a black pill,
 * an outlined pill, a tinted tag, a hairline rule — and its consistency comes from there being no
 * sixth part rather than from anyone policing the first five. Defining them here means a screen is
 * assembled rather than styled, and a change to the border colour is one edit instead of forty.
 */

/** Border weight for every outlined surface. Stated once so nothing drifts to 1.5dp. */
private val HairlineWidth = 1.dp

/**
 * A container that shows its edges instead of floating above the page.
 *
 * The whole point of the reference design is that it has no shadows: depth is carried by a hairline
 * border and, at most, a half-step change in background. A `Surface` with `shadowElevation` would
 * reintroduce exactly the soft grey halo the palette exists to avoid, and on a white page that halo
 * is the most visible thing on the screen.
 */
@Composable
fun OutlinedPanel(
    modifier: Modifier = Modifier,
    shape: Shape = ContainerShape,
    color: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        color = color,
        shape = shape,
        border = BorderStroke(HairlineWidth, borderColor),
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        content = content,
    )
}

/** How much visual weight a [PillButton] claims. */
enum class PillStyle {
    /** Black on white, white on black: the one committed action on a screen. */
    Primary,

    /** Outlined, for the alternative that should still be easy to find. */
    Secondary,

    /** Borderless, for an action that would be noise if it were boxed. */
    Ghost,
}

/**
 * The reference design's single button.
 *
 * Everything pressable on ollama.com is a full pill, and the radius is what ties a 32dp tag to a
 * 48dp call to action. [Pill] is a percentage, so the same shape is correct at both sizes without
 * anyone choosing a number.
 */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
) {
    val scheme = MaterialTheme.colorScheme
    val container = when (style) {
        PillStyle.Primary -> scheme.primary
        PillStyle.Secondary -> scheme.surface
        PillStyle.Ghost -> Color.Transparent
    }
    val content = when (style) {
        PillStyle.Primary -> scheme.onPrimary
        PillStyle.Secondary -> scheme.onSurface
        PillStyle.Ghost -> scheme.onSurfaceVariant
    }
    // Disabled state is a fade rather than a different grey: on a palette made only of greys, a
    // "disabled grey" is indistinguishable from an enabled one two steps along the ramp.
    val alpha = if (enabled) 1f else 0.38f

    Surface(
        color = container.copy(alpha = container.alpha * alpha),
        contentColor = content.copy(alpha = alpha),
        shape = Pill,
        border = if (style == PillStyle.Secondary) {
            BorderStroke(HairlineWidth, scheme.outline.copy(alpha = alpha))
        } else {
            null
        },
        modifier = modifier.then(
            if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
        ),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A tinted capability pill, as seen under a model name on ollama.com.
 *
 * Deliberately small and low-contrast. A row of these sits beneath a heading and has to be
 * scannable without ever becoming the reason your eye went there.
 */
@Composable
fun Tag(
    label: String,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(color = background, contentColor = contentColor, shape = Pill, modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * The rule between list rows.
 *
 * Rows separated by a hairline rather than spaced apart or boxed individually is how the reference
 * design gets a dense list to read as one object. Insetting it past the row's own padding keeps the
 * left edge of the text unbroken down the column.
 */
@Composable
fun Hairline(modifier: Modifier = Modifier, startIndent: androidx.compose.ui.unit.Dp = 0.dp) {
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent),
        thickness = HairlineWidth,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * A square, pill-cropped icon target.
 *
 * `Modifier.size` on an `IconButton` shrinks its hit rectangle along with its bounds, so the target
 * is left alone and only the glyph inside is sized. See the note on the same trap in CLAUDE.md.
 */
@Composable
fun CircularIconSlot(
    size: androidx.compose.ui.unit.Dp,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(color = color, shape = Pill, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
