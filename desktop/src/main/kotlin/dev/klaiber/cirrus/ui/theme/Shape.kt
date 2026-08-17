package dev.klaiber.cirrus.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Two radii and nothing between them.
 *
 * ollama.com resolves every corner to one of two answers: a full pill on anything you can press or
 * that labels something, and a modest rounded rectangle on anything that holds content. There is no
 * ladder of six radii and therefore no chance of two adjacent controls disagreeing by 2dp, which is
 * the kind of mismatch that reads as sloppiness without anyone being able to name it.
 *
 * [Pill] is a percentage rather than a large dp value on purpose: a fixed 9999dp radius behaves
 * correctly on a 40dp button and turns a 200dp-tall sheet into a lozenge, whereas 50% is always
 * exactly half the shorter side.
 */
val Pill = RoundedCornerShape(percent = 50)

/**
 * The container radius, for cards, sheets, code blocks and text fields.
 *
 * 12dp is deliberately restrained. The Material default of 28dp on an extra-large surface makes a
 * card look like a button, and on a page whose only other geometry is a hairline rule that softness
 * is the first thing to go wrong.
 */
val ContainerShape = RoundedCornerShape(12.dp)

/** The same corner, for surfaces large enough that 12dp would look accidental. */
val LargeContainerShape = RoundedCornerShape(16.dp)

/**
 * The Material scale, flattened onto that pair.
 *
 * Components reach for these tokens without asking us — chips take `small`, cards `medium`, bottom
 * sheets `extraLarge` — so aligning the scale here is what makes an untouched Material component
 * land in the same visual language as a hand-built one.
 */
val CirrusShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
