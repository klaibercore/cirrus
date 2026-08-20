package dev.klaiber.cirrus.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.klaiber.cirrus.ui.components.CirrusMarkGeometry
import java.awt.image.BufferedImage

/**
 * The app icon, for the two places the desktop asks for one as a picture rather than as a
 * composable: the window (which is also the taskbar and the dock) and the system tray.
 *
 * Rendered rather than shipped. A packaged desktop app would otherwise need an `.icns`, an `.ico`
 * and a `.png` of the same drawing, three formats that go out of agreement the first time one of
 * them is regenerated and the others are not; the tray icon this replaces had already given up and
 * drawn a plain dark square. Drawing it from [CirrusMarkGeometry] instead means the dock, the tray,
 * the drawer wordmark and the working indicator are all quoting the same coordinates.
 *
 * Both sizes are drawn at the size they will be shown at, not scaled down from one big one, and
 * each gets the glyph its size can carry — the same split `CirrusMark` makes. The tray's icon is
 * the stream glyph: three sweeps, no braces, heavier. A tray shrinks whatever it is given to the
 * height of the panel it sits in, which on a dense display is a couple of dozen pixels, and braces
 * do not survive that. The window's icon gets the full mark, which at 256 has room for them.
 */
private const val TRAY_PX = 32
private const val WINDOW_PX = 256

/** High-altitude night, the same plate the Android launcher's background paints. */
private val PlateTop = Color(0xFF101B2F)
private val PlateBottom = Color(0xFF05090F)
private val Brand = Color(0xFF6FB4F0)
private val Ice = Color(0xFFEEF5FD)

/**
 * The window icon: the full mark on its plate, at a size every desktop will downscale rather than
 * stretch.
 *
 * 256 is what macOS and Windows both want the largest representation to be, and asking for it once
 * lets each of them pick its own filtering rather than being handed a 32px image to blow up.
 */
fun cirrusWindowIcon(): Painter = BitmapPainter(renderMark(WINDOW_PX, braced = true))

/**
 * The tray icon.
 *
 * Opaque to its own edges, plate and all. A tray sits on whatever colour the user's panel happens
 * to be — a light dock on one machine, a near-black taskbar on the next — and a bare glyph tuned
 * for either disappears into the other.
 */
fun cirrusTrayImage(): BufferedImage = renderMark(TRAY_PX, braced = false).toBufferedImage()

private fun renderMark(sizePx: Int, braced: Boolean): ImageBitmap {
    val bitmap = ImageBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val size = Size(sizePx.toFloat(), sizePx.toFloat())
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, size) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(PlateTop, PlateBottom),
                start = Offset(size.width * 0.19f, 0f),
                end = Offset(size.width * 0.81f, size.height),
            ),
        )
        val sweeps = if (braced) CirrusMarkGeometry.MARK_SWEEPS else CirrusMarkGeometry.STREAM_SWEEPS
        val width = if (braced) CirrusMarkGeometry.MARK_STROKE else CirrusMarkGeometry.STREAM_STROKE
        // The mark keeps 78% of the plate, which is the design system's own squircle proportion.
        onMarkGrid(size, inset = 0.78f) {
            if (braced) {
                CirrusMarkGeometry.BRACES.forEach { stroke(it, Brand, width) }
            }
            sweeps.forEachIndexed { index, data ->
                stroke(data, if (braced || index == 1) Ice else Brand, width)
            }
        }
    }
    return bitmap
}

private fun DrawScope.onMarkGrid(size: Size, inset: Float, block: DrawScope.() -> Unit) {
    val extent = minOf(size.width, size.height) * inset
    val factor = extent / CirrusMarkGeometry.VIEWPORT
    translate((size.width - extent) / 2f, (size.height - extent) / 2f) {
        scale(factor, factor, Offset.Zero) { block() }
    }
}

private fun DrawScope.stroke(pathData: String, color: Color, width: Float) {
    drawPath(
        path = pathData.toPath(),
        color = color,
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

private fun String.toPath(): Path = PathParser().parsePathString(this).toPath()

/**
 * Hands the rendered mark to AWT, which is what the tray takes.
 *
 * Read out pixel by pixel rather than through a Skia-to-AWT bridge: the plate is opaque to every
 * edge, so there is no premultiplied alpha to undo, and a straight `IntArray` copy is one API
 * surface instead of two.
 */
private fun ImageBitmap.toBufferedImage(): BufferedImage {
    val pixels = IntArray(width * height)
    readPixels(pixels)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image
}
