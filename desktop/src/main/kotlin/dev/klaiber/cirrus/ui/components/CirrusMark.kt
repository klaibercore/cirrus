package dev.klaiber.cirrus.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.ui.theme.CirrusAccents
import dev.klaiber.cirrus.ui.theme.LocalAccents
import kotlin.math.min

/**
 * The Cirrus mark, and the mark doing work.
 *
 * One primitive builds the whole thing: a curve that rises to the right and ends in a round cap —
 * a cirrus cloud is the only cloud shaped entirely by wind, and its form is a record of a current
 * passing through it, which is also what a token stream is. Three of those sweeps sit inside a
 * pair of braces: what comes through the API, and the API it comes through.
 *
 * The geometry is the design system's, character for character, on the same 24-unit grid the SVG
 * source in `design/cirrus-icons.svg` uses. Sharing the numbers rather than re-tracing them is the
 * only thing that keeps the launcher icon, the drawer wordmark and this animation the same drawing;
 * the launcher and notification vectors quote the same coordinates for the same reason.
 *
 * Two glyphs, not one, and which you get depends on size. Below [BRACE_FLOOR] the braces close up
 * into a blue smear, so the small mark drops them and switches to the stream glyph — three heavier
 * sweeps re-proportioned to fill the box on their own. Scaling the full mark down instead gives a
 * mark that is present but illegible, which is worse than a simpler mark that is neither.
 */

/**
 * Where the braces stop resolving and [CirrusMarkGeometry.STREAM_SWEEPS] takes over.
 *
 * The design system says 20 and 20 is optimistic. Rendered against a real pixel grid the braced
 * mark is a smudge at 20 and marginal at 24; it only separates into two braces and three sweeps at
 * about 28. The stream glyph, by contrast, is still clean at 14. So the boundary sits at 28, which
 * costs the braces at the two smallest sizes in the app and buys a mark that is legible everywhere
 * it appears.
 *
 * Stated in `dp` rather than in pixels on purpose, even though it is a pixel-resolution limit: a
 * threshold in physical pixels would hand the same row a different glyph on a 2x phone and a 3x
 * one, and which mark the app uses in a given place is a decision the design makes, not the device.
 * A `dp` threshold is the conservative reading of the same limit — it holds at 1x, where dp and
 * pixel are the same thing, and everything denser than that is only clearer.
 */
private val BRACE_FLOOR = 28.dp

/**
 * The mark on the 24-unit grid, quoted from `design/cirrus-icons.svg`.
 *
 * The sweeps are listed top to bottom, which is also the order they are animated in: a stagger
 * that ran bottom-up would read as wind blowing the wrong way, and the wind never changes.
 */
internal object CirrusMarkGeometry {
    const val VIEWPORT = 24f

    const val MARK_STROKE = 1.55f
    const val STREAM_STROKE = 1.9f

    val BRACES = listOf(
        "M7.4 5.2C5.5 5.2 5.8 7.5 5.8 9.6C5.8 11.2 4.5 12 3.5 12C4.5 12 5.8 12.8 5.8 14.4" +
            "C5.8 16.5 5.5 18.8 7.4 18.8",
        "M16.6 5.2C18.5 5.2 18.2 7.5 18.2 9.6C18.2 11.2 19.5 12 20.5 12C19.5 12 18.2 12.8 18.2 14.4" +
            "C18.2 16.5 18.5 18.8 16.6 18.8",
    )

    val MARK_SWEEPS = listOf(
        "M9.3 10C10.97 8.96 12.92 9.31 14.7 8.2",
        "M8 13.4C10.48 11.89 13.36 12.4 16 10.8",
        "M9.8 15.8C11.16 14.99 12.75 15.26 14.2 14.4",
    )

    val STREAM_SWEEPS = listOf(
        "M7.1 9.2c3.23-1.74 6.47-1.26 9.8-3",
        "M5.2 14.2c4.49-2.44 8.98-1.76 13.6-4.2",
        "M7.9 17.8c2.71-1.51 5.41-1.09 8.2-2.6",
    )
}

/** What the mark is being asked to say. */
enum class MarkActivity {
    /** Still. The logo, and nothing more. */
    Resting,

    /**
     * A prompt has gone out and nothing has come back. Sweeps streak cool, on periods of 2.2, 1.8
     * and 2.6 seconds — picked so the three do not line up again for four and a quarter minutes,
     * which is longer than anyone will sit watching one.
     */
    Thinking,

    /**
     * Something is drawing power: tokens are arriving, or a tool is out there running. Warmer and
     * roughly twice as fast. This is the only state that puts amber on the screen, which is what
     * makes amber worth reading.
     */
    Working,
}

/**
 * The static mark, for the places the app signs its own name.
 *
 * The braces take [CirrusAccents.brand] and the sweeps [CirrusAccents.contrast]; there is no tint
 * override, because a mark whose colours can be replaced from the call site is a mark that will end
 * up meaning something different on two screens. Somewhere that has already decided what colour
 * everything in it is — a status-bar mask, say — wants the vector asset, not this.
 */
@Composable
fun CirrusMark(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    val accents = LocalAccents.current
    val braceColor = accents.brand
    val sweepColor = accents.contrast
    val withBraces = size >= BRACE_FLOOR
    val description = contentDescription

    val braces = rememberPaths(if (withBraces) CirrusMarkGeometry.BRACES else emptyList())
    val sweeps = rememberPaths(
        if (withBraces) CirrusMarkGeometry.MARK_SWEEPS else CirrusMarkGeometry.STREAM_SWEEPS,
    )
    val stroke = if (withBraces) CirrusMarkGeometry.MARK_STROKE else CirrusMarkGeometry.STREAM_STROKE

    Canvas(
        modifier
            .size(size)
            .then(
                if (description == null) {
                    Modifier
                } else {
                    Modifier.semantics { this.contentDescription = description }
                },
            ),
    ) {
        onMarkGrid {
            braces.forEach { drawSweep(it, braceColor, 1f, stroke) }
            sweeps.forEachIndexed { index, path ->
                // The stream glyph alternates brand and contrast rather than running all three
                // sweeps in one colour: without the braces around them, the alternation is the
                // only thing left carrying "structure, then content, then structure".
                val color = if (withBraces || index == 1) sweepColor else braceColor
                drawSweep(path, color, 1f, stroke)
            }
        }
    }
}

/**
 * The mark while a turn is in flight — what used to be a pulsing dot.
 *
 * A dot pulsing on a timer says only "not finished yet". Three sweeps streaking left to right at
 * three different rates say the same thing while also being the app's own mark, and the colour says
 * which kind of waiting it is: cool while the model is still thinking, amber the moment something
 * is actually drawing power. That distinction is the one a person waiting on a slow local model
 * actually wants, and it previously cost a look at the transcript to answer.
 *
 * Each sweep is drawn as a window travelling along its own path — not a dash pattern — so the
 * streak keeps its round caps at both ends however far along it is, and fades in and out at the
 * ends of the run rather than being clipped by the edge of the glyph.
 */
@Composable
fun CirrusActivity(
    activity: MarkActivity = MarkActivity.Thinking,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val accents = LocalAccents.current
    val withBraces = size >= BRACE_FLOOR
    val braces = rememberPaths(if (withBraces) CirrusMarkGeometry.BRACES else emptyList())
    val sweeps = rememberPaths(
        if (withBraces) CirrusMarkGeometry.MARK_SWEEPS else CirrusMarkGeometry.STREAM_SWEEPS,
    )
    val stroke = if (withBraces) CirrusMarkGeometry.MARK_STROKE else CirrusMarkGeometry.STREAM_STROKE
    val measure = remember { PathMeasure() }
    val scratch = remember { Path() }

    val sweepColor = when (activity) {
        MarkActivity.Working -> accents.ember
        else -> accents.contrast
    }
    val periods = when (activity) {
        MarkActivity.Working -> WORKING_PERIODS
        else -> THINKING_PERIODS
    }
    val delays = when (activity) {
        MarkActivity.Working -> WORKING_DELAYS
        else -> THINKING_DELAYS
    }

    // Read unconditionally: a composable call short-circuited away by `&&` is a composition that
    // changes shape with its own state, which is a recomposition bug waiting for the state to move.
    val systemAnimates = animationsEnabled()
    val animate = activity != MarkActivity.Resting && systemAnimates
    val elapsed = if (animate) animationClockMillis() else 0L

    Canvas(modifier.size(size)) {
        onMarkGrid {
            braces.forEach { drawSweep(it, accents.brand, if (animate) 0.7f else 1f, stroke) }
            sweeps.forEachIndexed { index, path ->
                if (!animate) {
                    // Animations turned off at the system level, or nothing to say. The mark still
                    // has to be visible: a still frame of a streak is an empty box.
                    drawSweep(path, sweepColor, 1f, stroke)
                    return@forEachIndexed
                }
                val phase = phaseOf(elapsed, periods[index], delays[index])
                // The track the streak runs along, always drawn.
                //
                // Without it the indicator goes completely empty for a fraction of a second about
                // once every twenty, whenever all three streaks happen to be in their fade at the
                // same moment — the periods are coprime, so the coincidence is rare rather than
                // absent. An indicator that blinks out reads as one that has finished, which is
                // the single thing this must never say while a turn is still running. The braced
                // mark never had the problem because the braces held the glyph together; the
                // stream glyph is three strokes and nothing else.
                drawSweep(path, sweepColor, TRACK_ALPHA, stroke)
                val window = streakWindow(phase) ?: return@forEachIndexed
                measure.setPath(path, false)
                val length = measure.length
                scratch.reset()
                measure.getSegment(window.first * length, window.second * length, scratch, true)
                drawSweep(scratch, sweepColor, streakAlpha(phase), stroke)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Timing
//
// The periods are the design system's, and the point of the odd numbers is how long they take to
// agree again: 2.2 / 1.8 / 2.6 seconds return to the same relative positions every 4m17s, and
// 1.25 / 1.05 / 1.45 every 12m41s. A person waiting on a slow local model never sees the loop.
//
// Delays stagger the three so they do not all start together on the first frame, which would read
// as one thick stroke leaving rather than three wisps passing.
// ---------------------------------------------------------------------------------------------

private val THINKING_PERIODS = floatArrayOf(2200f, 1800f, 2600f)
private val THINKING_DELAYS = floatArrayOf(0f, 350f, 700f)
private val WORKING_PERIODS = floatArrayOf(1250f, 1050f, 1450f)
private val WORKING_DELAYS = floatArrayOf(0f, 180f, 360f)

/** How much of its own path a streak covers at once. */
private const val STREAK_WINDOW = 0.44f

/** The resting weight of a sweep under its own streak. Present, but not competing with it. */
private const val TRACK_ALPHA = 0.16f

/** Where a streak has faded fully in, and where it starts fading back out. */
private const val FADE_IN_UNTIL = 0.18f
private const val FADE_OUT_FROM = 0.72f

private fun phaseOf(elapsedMillis: Long, period: Float, delay: Float): Float {
    val shifted = (elapsedMillis + delay) % period
    return shifted / period
}

/**
 * The stretch of path a streak covers, as a fraction of the path, or null when it is entirely off
 * either end.
 *
 * The window travels a little further than the path is long — it enters from before the start and
 * leaves past the end — which is what stops both ends of the run looking like the streak was
 * switched on and off in place.
 */
private fun streakWindow(phase: Float): Pair<Float, Float>? {
    val head = phase * (1f + STREAK_WINDOW)
    val start = (head - STREAK_WINDOW).coerceIn(0f, 1f)
    val stop = head.coerceIn(0f, 1f)
    return if (stop - start < 0.005f) null else start to stop
}

private fun streakAlpha(phase: Float): Float = when {
    phase < FADE_IN_UNTIL -> phase / FADE_IN_UNTIL
    phase > FADE_OUT_FROM -> ((1f - phase) / (1f - FADE_OUT_FROM)).coerceAtLeast(0f)
    else -> 1f
}

/**
 * Milliseconds since this indicator first drew, from the frame clock rather than from the wall.
 *
 * `withInfiniteAnimationFrameMillis` is the clock the rest of Compose animates on, so this stops
 * with the composition and does not keep a frame callback alive behind a screen that has gone away.
 */
@Composable
private fun animationClockMillis(): Long {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        var origin = -1L
        while (true) {
            withInfiniteAnimationFrameMillis { frame ->
                if (origin < 0L) origin = frame
                elapsed = frame - origin
            }
        }
    }
    return elapsed
}

// ---------------------------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------------------------

@Composable
private fun rememberPaths(data: List<String>): List<Path> = remember(data) {
    data.map { PathParser().parsePathString(it).toPath() }
}

/**
 * Puts the 24-unit grid over whatever box the caller gave us, so every coordinate in this file is
 * the design system's own number and the stroke widths scale with them.
 *
 * Deliberately not `inline`. The body hands [block] to `scale`, which is to say into another
 * lambda, and an inline function may only do that with its parameter marked `crossinline` — which
 * would then forbid a non-local return from any caller's block for the sake of saving one lambda
 * allocation per frame in a 20dp box.
 */
private fun DrawScope.onMarkGrid(block: DrawScope.() -> Unit) {
    val factor = min(size.width, size.height) / CirrusMarkGeometry.VIEWPORT
    val insetX = (size.width - factor * CirrusMarkGeometry.VIEWPORT) / 2f
    val insetY = (size.height - factor * CirrusMarkGeometry.VIEWPORT) / 2f
    translate(insetX, insetY) {
        scale(factor, factor, Offset.Zero) {
            block()
        }
    }
}

private fun DrawScope.drawSweep(path: Path, color: Color, alpha: Float, width: Float) {
    drawPath(
        path = path,
        color = color,
        alpha = alpha.coerceIn(0f, 1f),
        // Round caps and round joins, always. Nothing in the sky has a corner.
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

/**
 * The desktop has no counterpart to Android's "remove animations" switch — none the JDK exposes,
 * and none that is the same setting across macOS, Windows and the various Linux desktops — so this
 * build always animates. Kept as a function rather than inlined so the two copies of this file
 * differ in exactly one place, which is what stops them drifting apart.
 */
@Composable
private fun animationsEnabled(): Boolean = true
