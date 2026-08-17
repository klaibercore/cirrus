package dev.klaiber.cirrus.ui.markdown.math

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import kotlin.math.max

/**
 * Turns a [MathNode] tree into a positioned [MathBox].
 *
 * The rules are TeX's, in miniature: fractions hang off the axis rather than the baseline, scripts
 * shrink and then stop shrinking, delimiters grow to fit their contents, and the space between two
 * atoms depends on what kind of atoms they are. Those four things are most of what separates
 * "maths" from "a line of symbols".
 *
 * All measurements are in device pixels. Sizes are absolute rather than relative so a superscript
 * inside a fraction inside a matrix ends up the right size by construction.
 */
internal class MathTypesetter(
    private val measurer: TextMeasurer,
    val density: Density,
    val baseSize: Float,
    private val color: Color,
) {

    /**
     * The maths axis: where fraction bars, big operators and stretched delimiters centre, a quarter
     * of an em above the baseline. Callers placing a whole formula need it too, so it is not
     * private to the layout code.
     */
    val axis: Float get() = baseSize * AXIS

    private data class Style(val size: Float, val display: Boolean) {
        /** Scripts shrink, but only so far: below this they stop being legible at reading size. */
        fun script(): Style = Style(max(size * SCRIPT_SCALE, MIN_SCRIPT_SIZE_PX), false)
    }

    fun typeset(node: MathNode, display: Boolean): MathBox =
        box(node, Style(baseSize, display))

    private fun box(node: MathNode, style: Style): MathBox = when (node) {
        is MathNode.Row -> rowBox(node.children, style)
        is MathNode.Glyph -> glyphBox(node, style)
        is MathNode.Fraction -> fractionBox(node, style)
        is MathNode.Scripts -> scriptsBox(node, style)
        is MathNode.Root -> rootBox(node, style)
        is MathNode.Fence -> fenceBox(node, style)
        is MathNode.Grid -> gridBox(node, style)
        is MathNode.Accent -> accentedBox(node, style)
        is MathNode.TextRun -> textBox(node, style)
        is MathNode.Space -> MathBox.empty(node.em * style.size)
        MathNode.Empty -> MathBox.empty()
    }

    // ---- Horizontal runs -------------------------------------------------------------------

    private fun rowBox(children: List<MathNode>, style: Style): MathBox {
        if (children.isEmpty()) return MathBox.empty()

        val placed = mutableListOf<MathBox.Placed>()
        var x = 0f
        var ascent = 0f
        var descent = 0f
        var previous: Atom? = null
        var last: MathBox? = null

        children.forEach { child ->
            val childBox = box(child, style)
            val atom = when {
                child is MathNode.Space -> null
                previous == null -> spacingClass(child).let { if (it == Atom.BIN) Atom.ORD else it }
                else -> spacingClass(child, previous)
            }

            if (atom != null && previous != null) {
                x += spacing(previous, atom) * style.size
            }

            placed += MathBox.Placed(childBox, x, 0f)
            x += childBox.width
            ascent = max(ascent, childBox.ascent)
            descent = max(descent, childBox.descent)
            // An explicit space neither takes nor gives spacing to its neighbours.
            previous = atom ?: previous
            last = childBox
        }

        return MathBox.of(x, ascent, descent, placed, italicCorrection = last?.italicCorrection ?: 0f)
    }

    /**
     * A binary operator with nothing to bind on its left is a sign, not an operation: the `-` of
     * `-x` and of `(-1)` must not get the space that the `-` of `a - b` gets.
     */
    private fun spacingClass(node: MathNode, previous: Atom? = null): Atom {
        val atom = atomOf(node)
        if (atom != Atom.BIN) return atom
        val unary = previous == null ||
            previous == Atom.BIN || previous == Atom.REL || previous == Atom.OPEN ||
            previous == Atom.PUNCT || previous == Atom.OP
        return if (unary) Atom.ORD else Atom.BIN
    }

    private fun atomOf(node: MathNode): Atom = when (node) {
        is MathNode.Glyph -> node.atom
        is MathNode.Scripts -> if (node.base == MathNode.Empty) Atom.ORD else atomOf(node.base)
        is MathNode.Fence -> Atom.INNER
        is MathNode.Grid -> Atom.INNER
        is MathNode.Row -> node.children.firstOrNull()?.let(::atomOf) ?: Atom.ORD
        else -> Atom.ORD
    }

    /** TeX's inter-atom spacing table, in ems. */
    private fun spacing(left: Atom, right: Atom): Float = when {
        left == Atom.OPEN || right == Atom.CLOSE || right == Atom.PUNCT -> 0f
        left == Atom.PUNCT -> SPACE_THIN
        left == Atom.BIN || right == Atom.BIN -> SPACE_MEDIUM
        left == Atom.REL || right == Atom.REL -> SPACE_THICK
        left == Atom.OP || right == Atom.OP -> SPACE_THIN
        left == Atom.INNER || right == Atom.INNER -> SPACE_THIN
        else -> 0f
    }

    // ---- Leaves ----------------------------------------------------------------------------

    private fun glyphBox(node: MathNode.Glyph, style: Style): MathBox {
        val size = if (node.big && style.display) style.size * BIG_OPERATOR_SCALE else style.size
        val plain = textRun(node.text, size, node.font)
        // Big operators straddle the axis rather than standing on the baseline, which is what lets
        // a summation sit level with the fraction beside it.
        return if (node.big) centreOnAxis(plain, AXIS * style.size) else plain
    }

    private fun textBox(node: MathNode.TextRun, style: Style): MathBox {
        val font = when {
            node.bold -> MathFont.BOLD
            node.italic -> MathFont.ITALIC
            else -> MathFont.UPRIGHT
        }
        return textRun(node.text, style.size, font)
    }

    private fun textRun(text: String, size: Float, font: MathFont): MathBox {
        if (text.isEmpty()) return MathBox.empty()
        val layout = measure(text, size, font)
        val ascent = layout.firstBaseline
        val descent = layout.size.height - ascent
        val italic = if (font == MathFont.ITALIC || font == MathFont.BOLD_ITALIC) {
            size * ITALIC_CORRECTION
        } else {
            0f
        }

        return MathBox(
            width = layout.size.width.toFloat(),
            ascent = ascent,
            descent = descent,
            italicCorrection = italic,
        ) { x, baseline ->
            drawText(layout, color = color, topLeft = Offset(x, baseline - ascent))
        }
    }

    private fun measure(text: String, size: Float, font: MathFont): TextLayoutResult =
        measurer.measure(
            text = text,
            style = TextStyle(
                color = color,
                fontSize = with(density) { size.toSp() },
                fontFamily = if (font == MathFont.MONO) FontFamily.Monospace else FontFamily.Serif,
                fontStyle = when (font) {
                    MathFont.ITALIC, MathFont.BOLD_ITALIC -> FontStyle.Italic
                    else -> FontStyle.Normal
                },
                fontWeight = when (font) {
                    MathFont.BOLD, MathFont.BOLD_ITALIC -> FontWeight.Bold
                    else -> FontWeight.Normal
                },
                lineHeight = TextUnit.Unspecified,
                // The Android build switches font padding off here: it is a legacy quirk that adds
                // invisible slack above every glyph, which a box model built on ascents notices
                // immediately. There is no such padding off Android, so there is nothing to switch.
            ),
            softWrap = false,
            maxLines = 1,
        )

    private fun centreOnAxis(source: MathBox, axis: Float): MathBox {
        val half = source.height / 2f
        val shift = (half - axis) - source.descent
        return MathBox(source.width, half + axis, half - axis, source.italicCorrection) { x, baseline ->
            source.draw(this, x, baseline + shift)
        }
    }

    // ---- Fractions -------------------------------------------------------------------------

    private fun fractionBox(node: MathNode.Fraction, style: Style): MathBox {
        // In display style the parts stay full size; inline they shrink, or a single fraction
        // doubles the height of the paragraph line it sits in.
        val childStyle = if (style.display) {
            Style(style.size, false)
        } else {
            Style(max(style.size * INLINE_FRACTION_SCALE, MIN_SCRIPT_SIZE_PX), false)
        }

        val numerator = box(node.numerator, childStyle)
        val denominator = box(node.denominator, childStyle)

        val axis = AXIS * style.size
        val thickness = if (node.rule) RULE_THICKNESS * style.size else 0f
        val gap = (if (style.display) FRACTION_GAP_DISPLAY else FRACTION_GAP_TEXT) * style.size
        val padding = FRACTION_PADDING * style.size
        val width = max(numerator.width, denominator.width) + padding * 2

        val numeratorDy = -(axis + thickness / 2f + gap + numerator.descent)
        val denominatorDy = denominator.ascent + gap + thickness / 2f - axis

        val children = listOf(
            MathBox.Placed(numerator, (width - numerator.width) / 2f, numeratorDy),
            MathBox.Placed(denominator, (width - denominator.width) / 2f, denominatorDy),
        )

        val ascent = -numeratorDy + numerator.ascent
        val descent = denominatorDy + denominator.descent

        return MathBox(width, ascent, descent) { x, baseline ->
            children.forEach { it.box.draw(this, x + it.dx, baseline + it.dy) }
            if (node.rule) {
                drawRect(
                    color = color,
                    topLeft = Offset(x + padding / 2f, baseline - axis - thickness / 2f),
                    size = Size(width - padding, thickness),
                )
            }
        }
    }

    // ---- Scripts ---------------------------------------------------------------------------

    private fun scriptsBox(node: MathNode.Scripts, style: Style): MathBox {
        val base = box(node.base, style)
        val scriptStyle = style.script()
        val superscript = node.superscript?.let { box(it, scriptStyle) }
        val subscript = node.subscript?.let { box(it, scriptStyle) }

        if (superscript == null && subscript == null) return base

        val glyph = node.base as? MathNode.Glyph
        val stacked = glyph != null && glyph.limitsAbove && style.display
        return if (stacked) {
            stackedScripts(base, superscript, subscript, style)
        } else {
            sideScripts(base, superscript, subscript, style)
        }
    }

    private fun sideScripts(
        base: MathBox,
        superscript: MathBox?,
        subscript: MathBox?,
        style: Style,
    ): MathBox {
        var supShift = max(base.ascent - style.size * SUPERSCRIPT_DROP, style.size * SUPERSCRIPT_SHIFT)
        var subShift = max(base.descent + style.size * SUBSCRIPT_DROP, style.size * SUBSCRIPT_SHIFT)

        if (superscript != null && subscript != null) {
            // Both present: push them apart until the gap between them is visible.
            val clearance = (supShift - superscript.descent) - (subscript.ascent - subShift)
            val required = SCRIPT_CLEARANCE * style.size
            if (clearance < required) {
                val extra = (required - clearance) / 2f
                supShift += extra
                subShift += extra
            }
        }

        val children = mutableListOf<MathBox.Placed>()
        var width = base.width
        children += MathBox.Placed(base, 0f, 0f)

        superscript?.let {
            children += MathBox.Placed(it, base.width + base.italicCorrection, -supShift)
            width = max(width, base.width + base.italicCorrection + it.width)
        }
        subscript?.let {
            children += MathBox.Placed(it, base.width, subShift)
            width = max(width, base.width + it.width)
        }

        val ascent = max(base.ascent, (superscript?.let { supShift + it.ascent } ?: 0f))
        val descent = max(base.descent, (subscript?.let { subShift + it.descent } ?: 0f))
        return MathBox.of(width + style.size * SCRIPT_TRAILING, ascent, descent, children)
    }

    /** `\sum_{i=1}^{n}` in display: the limits belong above and below, centred on the operator. */
    private fun stackedScripts(
        base: MathBox,
        superscript: MathBox?,
        subscript: MathBox?,
        style: Style,
    ): MathBox {
        val gap = LIMIT_GAP * style.size
        val width = maxOf(base.width, superscript?.width ?: 0f, subscript?.width ?: 0f)
        val children = mutableListOf(MathBox.Placed(base, (width - base.width) / 2f, 0f))

        var ascent = base.ascent
        var descent = base.descent

        superscript?.let {
            val dy = -(base.ascent + gap + it.descent)
            children += MathBox.Placed(it, (width - it.width) / 2f, dy)
            ascent = -dy + it.ascent
        }
        subscript?.let {
            val dy = base.descent + gap + it.ascent
            children += MathBox.Placed(it, (width - it.width) / 2f, dy)
            descent = dy + it.descent
        }

        return MathBox.of(width, ascent, descent, children)
    }

    // ---- Radicals --------------------------------------------------------------------------

    private fun rootBox(node: MathNode.Root, style: Style): MathBox {
        val radicand = box(node.radicand, style)
        val thickness = RULE_THICKNESS * style.size
        val gap = ROOT_GAP * style.size
        val padding = ROOT_PADDING * style.size

        val contentWidth = radicand.width + padding
        val ascent = radicand.ascent + gap + thickness * 2f
        val descent = max(radicand.descent, style.size * 0.1f)
        val radical = radicalBox(contentWidth, ascent, descent, style.size, color)
        val hookWidth = radical.width - contentWidth

        // The degree of a cube root tucks into the crook of the radical.
        val index = node.index?.let { box(it, style.script().script()) }
        val indexWidth = index?.let { max(0f, it.width - hookWidth * 0.5f) } ?: 0f

        val children = mutableListOf(
            MathBox.Placed(radical, indexWidth, 0f),
            MathBox.Placed(radicand, indexWidth + hookWidth + padding / 2f, 0f),
        )
        index?.let {
            children += MathBox.Placed(it, 0f, -(ascent * 0.55f))
        }

        val totalAscent = max(ascent, index?.let { ascent * 0.55f + it.ascent } ?: 0f)
        return MathBox.of(indexWidth + radical.width, totalAscent, descent, children)
    }

    // ---- Fences ----------------------------------------------------------------------------

    private fun fenceBox(node: MathNode.Fence, style: Style): MathBox {
        val body = box(node.body, style)
        val axis = AXIS * style.size
        // Symmetric about the axis, which is what makes `\left(` look centred on its contents.
        val reach = max(body.ascent - axis, body.descent + axis)
        val height = max(reach * 2f + FENCE_SLACK * style.size, style.size * MIN_FENCE_HEIGHT)

        val left = node.left?.takeIf { it.isNotEmpty() }
            ?.let { delimiterBox(it, height, axis, style.size, color) }
        val right = node.right?.takeIf { it.isNotEmpty() }
            ?.let { delimiterBox(it, height, axis, style.size, color) }

        val children = mutableListOf<MathBox.Placed>()
        var x = 0f
        left?.let {
            children += MathBox.Placed(it, x, 0f)
            x += it.width + FENCE_PADDING * style.size
        }
        children += MathBox.Placed(body, x, 0f)
        x += body.width
        right?.let {
            x += FENCE_PADDING * style.size
            children += MathBox.Placed(it, x, 0f)
            x += it.width
        }

        val ascent = maxOf(body.ascent, left?.ascent ?: 0f, right?.ascent ?: 0f)
        val descent = maxOf(body.descent, left?.descent ?: 0f, right?.descent ?: 0f)
        return MathBox.of(x, ascent, descent, children)
    }

    // ---- Grids -----------------------------------------------------------------------------

    private fun gridBox(node: MathNode.Grid, style: Style): MathBox {
        val cellStyle = Style(style.size, false)
        val cells = node.rows.map { row -> row.map { box(it, cellStyle) } }
        val columnCount = cells.maxOfOrNull { it.size } ?: 0
        if (columnCount == 0) return MathBox.empty()

        val columnWidths = FloatArray(columnCount) { column ->
            cells.maxOf { row -> row.getOrNull(column)?.width ?: 0f }
        }
        val rowAscents = cells.map { row -> row.maxOfOrNull { it.ascent } ?: 0f }
        val rowDescents = cells.map { row -> row.maxOfOrNull { it.descent } ?: 0f }

        val rowGap = GRID_ROW_GAP * style.size
        val columnGap = GRID_COLUMN_GAP * style.size

        // Baselines first, then the whole stack is shifted so its middle lands on the axis.
        val baselines = FloatArray(cells.size)
        var cursor = 0f
        cells.indices.forEach { row ->
            cursor += rowAscents[row]
            baselines[row] = cursor
            cursor += rowDescents[row] + rowGap
        }
        val totalHeight = cursor - rowGap
        val axis = AXIS * style.size
        val top = -(totalHeight / 2f - axis)

        val columnOffsets = FloatArray(columnCount)
        var x = 0f
        for (column in 0 until columnCount) {
            if (column > 0) {
                // Aligned environments pair a right-aligned column with a left-aligned one so the
                // relation sits between them; a gap there would pull the `=` off its neighbours.
                x += if (node.alternating && column % 2 == 1) 0f else columnGap
            }
            columnOffsets[column] = x
            x += columnWidths[column]
        }
        val bodyWidth = x

        val children = mutableListOf<MathBox.Placed>()
        cells.forEachIndexed { row, columns ->
            columns.forEachIndexed { column, cell ->
                val alignment = when {
                    node.alternating -> if (column % 2 == 0) GridAlign.END else GridAlign.START
                    else -> node.alignment
                }
                val slack = columnWidths[column] - cell.width
                val dx = columnOffsets[column] + when (alignment) {
                    GridAlign.START -> 0f
                    GridAlign.CENTER -> slack / 2f
                    GridAlign.END -> slack
                }
                children += MathBox.Placed(cell, dx, top + baselines[row])
            }
        }

        val ascent = -top
        val descent = totalHeight + top

        val height = totalHeight + GRID_FENCE_SLACK * style.size
        val left = node.left?.let { delimiterBox(it, height, axis, style.size, color) }
        val right = node.right?.let { delimiterBox(it, height, axis, style.size, color) }

        val padded = mutableListOf<MathBox.Placed>()
        var offset = 0f
        left?.let {
            padded += MathBox.Placed(it, 0f, 0f)
            offset = it.width + GRID_FENCE_PADDING * style.size
        }
        children.forEach { padded += it.copy(dx = it.dx + offset) }
        var total = offset + bodyWidth
        right?.let {
            total += GRID_FENCE_PADDING * style.size
            padded += MathBox.Placed(it, total, 0f)
            total += it.width
        }

        return MathBox.of(
            width = total,
            ascent = maxOf(ascent, left?.ascent ?: 0f, right?.ascent ?: 0f),
            descent = maxOf(descent, left?.descent ?: 0f, right?.descent ?: 0f),
            children = padded,
        )
    }

    // ---- Accents ---------------------------------------------------------------------------

    private fun accentedBox(node: MathNode.Accent, style: Style): MathBox {
        val base = box(node.base, style)
        val width = max(base.width, style.size * MIN_ACCENT_WIDTH)
        val accent = accentBox(node.kind, width * ACCENT_WIDTH_FRACTION, style.size, color)
        val gap = ACCENT_GAP * style.size

        val dy = -(base.ascent + gap)
        val children = listOf(
            MathBox.Placed(base, (width - base.width) / 2f, 0f),
            MathBox.Placed(accent, (width - accent.width) / 2f, dy),
        )
        return MathBox.of(width, -dy + accent.ascent, base.descent, children)
    }

    private companion object {
        /** The height the fraction bar sits at, and everything else centres on. */
        const val AXIS = 0.25f
        const val RULE_THICKNESS = 0.055f
        const val FRACTION_GAP_DISPLAY = 0.20f
        const val FRACTION_GAP_TEXT = 0.12f
        const val FRACTION_PADDING = 0.20f
        const val INLINE_FRACTION_SCALE = 0.85f

        const val SCRIPT_SCALE = 0.72f
        const val MIN_SCRIPT_SIZE_PX = 9f
        const val SUPERSCRIPT_SHIFT = 0.42f
        const val SUPERSCRIPT_DROP = 0.28f
        const val SUBSCRIPT_SHIFT = 0.20f
        const val SUBSCRIPT_DROP = 0.05f
        const val SCRIPT_CLEARANCE = 0.16f
        const val SCRIPT_TRAILING = 0.04f
        const val LIMIT_GAP = 0.18f
        const val BIG_OPERATOR_SCALE = 1.4f

        const val ROOT_GAP = 0.14f
        const val ROOT_PADDING = 0.24f

        const val FENCE_SLACK = 0.18f
        const val FENCE_PADDING = 0.05f
        const val MIN_FENCE_HEIGHT = 1.1f

        const val GRID_ROW_GAP = 0.42f
        const val GRID_COLUMN_GAP = 0.7f
        const val GRID_FENCE_SLACK = 0.3f
        const val GRID_FENCE_PADDING = 0.12f

        const val ACCENT_GAP = 0.06f
        const val ACCENT_WIDTH_FRACTION = 0.7f
        const val MIN_ACCENT_WIDTH = 0.5f

        const val ITALIC_CORRECTION = 0.06f

        const val SPACE_THIN = 3f / 18f
        const val SPACE_MEDIUM = 4f / 18f
        const val SPACE_THICK = 5f / 18f
    }
}
