package dev.klaiber.cirrus.ui.markdown.math

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * A typeset piece of maths, measured but not yet placed.
 *
 * The box model is TeX's: everything is a rectangle with a width and a baseline that splits it
 * into an ascent and a descent. Composition is then just arithmetic on those three numbers, which
 * is what lets a fraction inside a superscript inside a matrix come out at the right size and the
 * right height without any of those cases being special.
 *
 * [paint] draws the content with its baseline at `baseline` and its left edge at `x`.
 */
internal class MathBox(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    /**
     * How far the last glyph leans past its advance width. A superscript after an italic `f` has
     * to clear the overhang or the two collide.
     */
    val italicCorrection: Float = 0f,
    private val paint: DrawScope.(x: Float, baseline: Float) -> Unit = { _, _ -> },
) {
    val height: Float get() = ascent + descent

    fun draw(scope: DrawScope, x: Float, baseline: Float) = scope.paint(x, baseline)

    companion object {
        fun empty(width: Float = 0f) = MathBox(width, 0f, 0f)

        /** Stacks pre-positioned children into one box. Offsets are relative to the parent's origin. */
        fun of(
            width: Float,
            ascent: Float,
            descent: Float,
            children: List<Placed>,
            italicCorrection: Float = 0f,
        ) = MathBox(width, ascent, descent, italicCorrection) { x, baseline ->
            children.forEach { placed ->
                placed.box.draw(this, x + placed.dx, baseline + placed.dy)
            }
        }
    }

    /** A child box and where its origin sits relative to its parent's. */
    data class Placed(val box: MathBox, val dx: Float, val dy: Float)
}

/** A horizontal rule: fraction bars, the vinculum over a radical, `\overline`. */
internal fun rule(width: Float, thickness: Float, color: Color, raise: Float): MathBox = MathBox(
    width = width,
    ascent = raise + thickness,
    descent = -raise,
) { x, baseline ->
    drawRect(
        color = color,
        topLeft = Offset(x, baseline - raise - thickness),
        size = Size(width, thickness),
    )
}

/**
 * Delimiters drawn as strokes rather than set as glyphs.
 *
 * A `(` from the font can only be scaled, and a scaled bracket gets a scaled stroke with it — thin
 * and spindly around a tall matrix, or fat around a fraction. Drawing them means the stroke weight
 * stays constant however far the delimiter has to stretch, which is exactly what a real maths font
 * buys you with its multiple sizes.
 */
internal fun delimiterBox(
    symbol: String,
    height: Float,
    axis: Float,
    em: Float,
    color: Color,
): MathBox {
    val half = height / 2f
    val ascent = axis + half
    val descent = half - axis
    val stroke = (em * DELIMITER_STROKE).coerceAtLeast(1f)
    val width = when (symbol) {
        "|", "‖" -> em * 0.28f
        "/", "\\" -> em * 0.45f
        else -> em * 0.34f
    }

    return MathBox(width, ascent, descent) { x, baseline ->
        val top = baseline - ascent
        val bottom = baseline + descent
        val path = delimiterPath(symbol, x, top, bottom, width, stroke)
        if (path != null) {
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

private fun delimiterPath(
    symbol: String,
    x: Float,
    top: Float,
    bottom: Float,
    width: Float,
    stroke: Float,
): Path? {
    val height = bottom - top
    val middle = (top + bottom) / 2f
    // Keep the stroke's own thickness inside the box, or round caps clip at the edges.
    val left = x + stroke / 2f
    val right = x + width - stroke / 2f
    val path = Path()

    when (symbol) {
        "(" -> {
            path.moveTo(right, top)
            path.quadraticTo(left - width * 0.35f, middle, right, bottom)
        }

        ")" -> {
            path.moveTo(left, top)
            path.quadraticTo(right + width * 0.35f, middle, left, bottom)
        }

        "[" -> {
            path.moveTo(right, top)
            path.lineTo(left, top)
            path.lineTo(left, bottom)
            path.lineTo(right, bottom)
        }

        "]" -> {
            path.moveTo(left, top)
            path.lineTo(right, top)
            path.lineTo(right, bottom)
            path.lineTo(left, bottom)
        }

        "{" -> {
            val spine = x + width * 0.62f
            val hook = height * 0.16f
            path.moveTo(right, top)
            path.quadraticTo(spine, top, spine, top + hook)
            path.lineTo(spine, middle - hook)
            path.quadraticTo(spine, middle, left, middle)
            path.quadraticTo(spine, middle, spine, middle + hook)
            path.lineTo(spine, bottom - hook)
            path.quadraticTo(spine, bottom, right, bottom)
        }

        "}" -> {
            val spine = x + width * 0.38f
            val hook = height * 0.16f
            path.moveTo(left, top)
            path.quadraticTo(spine, top, spine, top + hook)
            path.lineTo(spine, middle - hook)
            path.quadraticTo(spine, middle, right, middle)
            path.quadraticTo(spine, middle, spine, middle + hook)
            path.lineTo(spine, bottom - hook)
            path.quadraticTo(spine, bottom, left, bottom)
        }

        "|" -> {
            val centre = (left + right) / 2f
            path.moveTo(centre, top)
            path.lineTo(centre, bottom)
        }

        "‖" -> {
            path.moveTo(left, top)
            path.lineTo(left, bottom)
            path.moveTo(right, top)
            path.lineTo(right, bottom)
        }

        "⟨" -> {
            path.moveTo(right, top)
            path.lineTo(left, middle)
            path.lineTo(right, bottom)
        }

        "⟩" -> {
            path.moveTo(left, top)
            path.lineTo(right, middle)
            path.lineTo(left, bottom)
        }

        "⌊" -> {
            path.moveTo(left, top)
            path.lineTo(left, bottom)
            path.lineTo(right, bottom)
        }

        "⌋" -> {
            path.moveTo(right, top)
            path.lineTo(right, bottom)
            path.lineTo(left, bottom)
        }

        "⌈" -> {
            path.moveTo(right, top)
            path.lineTo(left, top)
            path.lineTo(left, bottom)
        }

        "⌉" -> {
            path.moveTo(left, top)
            path.lineTo(right, top)
            path.lineTo(right, bottom)
        }

        "/" -> {
            path.moveTo(left, bottom)
            path.lineTo(right, top)
        }

        "\\" -> {
            path.moveTo(left, top)
            path.lineTo(right, bottom)
        }

        else -> return null
    }
    return path
}

/**
 * The radical sign, drawn to fit whatever is under it.
 *
 * Three strokes and a vinculum: the little kick at the left, the descent to the bottom vertex, the
 * steep climb to the top, then the bar over the radicand.
 */
internal fun radicalBox(
    contentWidth: Float,
    ascent: Float,
    descent: Float,
    em: Float,
    color: Color,
): MathBox {
    val stroke = (em * RADICAL_STROKE).coerceAtLeast(1f)
    val hookWidth = em * 0.55f

    return MathBox(hookWidth + contentWidth, ascent, descent) { x, baseline ->
        val top = baseline - ascent + stroke / 2f
        val bottom = baseline + descent - stroke / 2f
        val height = bottom - top
        val path = Path().apply {
            moveTo(x + stroke / 2f, bottom - height * 0.42f)
            lineTo(x + hookWidth * 0.20f, bottom - height * 0.34f)
            lineTo(x + hookWidth * 0.52f, bottom)
            lineTo(x + hookWidth * 0.92f, top)
            lineTo(x + hookWidth + contentWidth, top)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Miter),
        )
    }
}

/** Accents that are shapes rather than characters: a hat, a vector arrow, a bar, dots. */
internal fun accentBox(
    kind: AccentKind,
    width: Float,
    em: Float,
    color: Color,
): MathBox {
    val stroke = (em * ACCENT_STROKE).coerceAtLeast(1f)
    val height = when (kind) {
        AccentKind.HAT, AccentKind.TILDE -> em * 0.20f
        AccentKind.VEC -> em * 0.16f
        AccentKind.BAR -> stroke
        AccentKind.DOT, AccentKind.DDOT -> em * 0.12f
    }

    return MathBox(width, height, 0f) { x, baseline ->
        val top = baseline - height
        val left = x + stroke / 2f
        val right = x + width - stroke / 2f
        val centre = (left + right) / 2f
        val strokeStyle = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (kind) {
            AccentKind.HAT -> drawPath(
                path = Path().apply {
                    moveTo(left, baseline)
                    lineTo(centre, top)
                    lineTo(right, baseline)
                },
                color = color,
                style = strokeStyle,
            )

            AccentKind.TILDE -> drawPath(
                path = Path().apply {
                    moveTo(left, baseline - height * 0.25f)
                    cubicTo(
                        left + width * 0.25f, top,
                        centre, baseline,
                        right, baseline - height * 0.75f,
                    )
                },
                color = color,
                style = strokeStyle,
            )

            AccentKind.BAR -> drawPath(
                path = Path().apply {
                    moveTo(left, baseline - height / 2f)
                    lineTo(right, baseline - height / 2f)
                },
                color = color,
                style = strokeStyle,
            )

            AccentKind.VEC -> {
                val head = height * 0.9f
                drawPath(
                    path = Path().apply {
                        moveTo(left, baseline - height / 2f)
                        lineTo(right, baseline - height / 2f)
                        moveTo(right - head, baseline - height / 2f - head * 0.55f)
                        lineTo(right, baseline - height / 2f)
                        lineTo(right - head, baseline - height / 2f + head * 0.55f)
                    },
                    color = color,
                    style = strokeStyle,
                )
            }

            AccentKind.DOT -> drawCircle(color, height / 2f, Offset(centre, baseline - height / 2f))

            AccentKind.DDOT -> {
                val radius = height / 2f
                drawCircle(color, radius, Offset(centre - radius * 1.8f, baseline - radius))
                drawCircle(color, radius, Offset(centre + radius * 1.8f, baseline - radius))
            }
        }
    }
}

private const val DELIMITER_STROKE = 0.055f
private const val RADICAL_STROKE = 0.05f
private const val ACCENT_STROKE = 0.05f
