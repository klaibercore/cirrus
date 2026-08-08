package dev.klaiber.cirrus.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * A warm, paper-toned palette rather than Material's default cool grey.
 *
 * Long-form reading is the primary activity in this app, so surfaces sit slightly off-white and
 * the accent is a muted clay that stays legible against both themes without vibrating.
 */
private val Clay = Color(0xFFC15F3C)
private val ClayLight = Color(0xFFFFB59B)

internal val LightColors = lightColorScheme(
    primary = Clay,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF77574C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCF),
    onSecondaryContainer = Color(0xFF2C160E),
    tertiary = Color(0xFF6B5D2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5E1A7),
    onTertiaryContainer = Color(0xFF221B00),
    background = Color(0xFFFAF9F7),
    onBackground = Color(0xFF201A18),
    surface = Color(0xFFFAF9F7),
    onSurface = Color(0xFF201A18),
    surfaceVariant = Color(0xFFF0E3DE),
    onSurfaceVariant = Color(0xFF53433F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2EF),
    surfaceContainer = Color(0xFFF1EBE7),
    surfaceContainerHigh = Color(0xFFEBE4DF),
    surfaceContainerHighest = Color(0xFFE5DDD8),
    outline = Color(0xFF85736E),
    outlineVariant = Color(0xFFD8C2BC),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF362F2C),
    inverseOnSurface = Color(0xFFFBEEEA),
    inversePrimary = ClayLight,
)

internal val DarkColors = darkColorScheme(
    primary = ClayLight,
    onPrimary = Color(0xFF5A1B00),
    primaryContainer = Color(0xFF7D2C0B),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE7BEB0),
    onSecondary = Color(0xFF442A21),
    secondaryContainer = Color(0xFF5D4036),
    onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFFD8C58D),
    onTertiary = Color(0xFF3A3005),
    tertiaryContainer = Color(0xFF52461A),
    onTertiaryContainer = Color(0xFFF5E1A7),
    background = Color(0xFF16130F),
    onBackground = Color(0xFFEDE0DC),
    surface = Color(0xFF16130F),
    onSurface = Color(0xFFEDE0DC),
    surfaceVariant = Color(0xFF53433F),
    onSurfaceVariant = Color(0xFFD8C2BC),
    surfaceContainerLowest = Color(0xFF0F0D0A),
    surfaceContainerLow = Color(0xFF1E1A16),
    surfaceContainer = Color(0xFF221E1A),
    surfaceContainerHigh = Color(0xFF2D2824),
    surfaceContainerHighest = Color(0xFF38322E),
    outline = Color(0xFFA08C87),
    outlineVariant = Color(0xFF53433F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFEDE0DC),
    inverseOnSurface = Color(0xFF362F2C),
    inversePrimary = Clay,
)

/**
 * Token colours for code blocks.
 *
 * Kept outside [androidx.compose.material3.ColorScheme] because syntax roles have no Material
 * equivalent, and because dynamic colour must never repaint code — readability there is fixed.
 */
data class CodeColors(
    val background: Color,
    val plain: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val type: Color,
    val punctuation: Color,
    val attribute: Color,
) {
    companion object {
        val Light = CodeColors(
            background = Color(0xFFF3EEEA),
            plain = Color(0xFF2B2622),
            keyword = Color(0xFF9A3E9E),
            string = Color(0xFF2C7A3F),
            number = Color(0xFFB05A00),
            comment = Color(0xFF8A817B),
            function = Color(0xFF1E63C4),
            type = Color(0xFF0F7A86),
            punctuation = Color(0xFF6B615B),
            attribute = Color(0xFFA8500D),
        )

        val Dark = CodeColors(
            background = Color(0xFF1E1A16),
            plain = Color(0xFFE4DAD3),
            keyword = Color(0xFFE58AD8),
            string = Color(0xFF8FD68A),
            number = Color(0xFFF2B36B),
            comment = Color(0xFF8E827A),
            function = Color(0xFF83B7F5),
            type = Color(0xFF6FD2DE),
            punctuation = Color(0xFFB0A49C),
            attribute = Color(0xFFF0A868),
        )
    }
}
