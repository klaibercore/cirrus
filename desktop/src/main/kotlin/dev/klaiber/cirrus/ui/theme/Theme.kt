package dev.klaiber.cirrus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The monochrome scheme the whole app shares with ollama.com.
 *
 * Colour is a neutral ramp from white to black; `primary` is near-black rather than a hue, which
 * is what makes a filled button read as the reference design's black pill for free. There is no
 * dynamic colour: a scheme derived from the wallpaper would be precisely destructive of a design
 * that has committed to zero chroma.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFFE0E0E0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF5F5F5),
    onPrimary = Color(0xFF111111),
    background = Color(0xFF111111),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF111111),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFF9A9A9A),
    outline = Color(0xFF2A2A2A),
)

@Composable
fun CirrusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
