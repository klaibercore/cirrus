package dev.klaiber.cirrus.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * A monochrome palette, after ollama.com.
 *
 * The site carries no chroma at all: every surface, every rule and every piece of text is a step on
 * one neutral ramp between white and black, and depth comes from a hairline border or a half-step
 * change in background rather than from a shadow. Colour is reserved for the capability tags on a
 * model card, where it is the entire point of the mark.
 *
 * That restraint is worth copying rather than merely admiring. A chat transcript is mostly prose,
 * code and tables; a tinted surface behind any of it competes with the syntax highlighting, and an
 * accent applied to controls turns every toggle into something that wants attention. Reserving
 * colour for the two places that encode meaning — a model's capabilities and a token's role in a
 * code block — leaves it able to say something when it does appear.
 */
private object Neutral {
    val N0 = Color(0xFFFFFFFF)
    val N50 = Color(0xFFFAFAFA)
    val N100 = Color(0xFFF5F5F5)
    val N150 = Color(0xFFEFEFEF)
    val N200 = Color(0xFFE5E5E5)
    val N300 = Color(0xFFD4D4D4)
    val N400 = Color(0xFFA3A3A3)
    val N500 = Color(0xFF737373)
    val N600 = Color(0xFF525252)
    val N700 = Color(0xFF404040)
    val N800 = Color(0xFF262626)
    val N850 = Color(0xFF1F1F1F)
    val N900 = Color(0xFF171717)
    val N925 = Color(0xFF0F0F0F)
    val N950 = Color(0xFF0A0A0A)
    val N1000 = Color(0xFF000000)
}

/**
 * The one hue in the interface, and only where a failure has to be unmissable.
 *
 * Monochrome is a design position, not an accessibility one: an error rendered as "slightly darker
 * grey" is invisible to a hurried reader and indistinguishable from ordinary chrome. The red is
 * held to the same restrained family as the capability tags rather than a warning-label scarlet.
 */
private val Red600 = Color(0xFFDC2626)
private val Red300 = Color(0xFFFCA5A5)

/**
 * `primary` is near-black rather than a hue, which is what makes the black pill button read as the
 * one committed action on a screen. Every "container" role collapses onto the neutral ramp, so a
 * selected row is a half-step lighter surface and nothing more.
 */
internal val LightColors = lightColorScheme(
    primary = Neutral.N900,
    onPrimary = Neutral.N0,
    primaryContainer = Neutral.N100,
    onPrimaryContainer = Neutral.N900,
    secondary = Neutral.N600,
    onSecondary = Neutral.N0,
    secondaryContainer = Neutral.N100,
    onSecondaryContainer = Neutral.N900,
    tertiary = Neutral.N700,
    onTertiary = Neutral.N0,
    tertiaryContainer = Neutral.N150,
    onTertiaryContainer = Neutral.N800,
    background = Neutral.N0,
    onBackground = Neutral.N950,
    surface = Neutral.N0,
    onSurface = Neutral.N950,
    surfaceVariant = Neutral.N100,
    onSurfaceVariant = Neutral.N600,
    surfaceContainerLowest = Neutral.N0,
    surfaceContainerLow = Neutral.N50,
    surfaceContainer = Neutral.N100,
    surfaceContainerHigh = Neutral.N150,
    surfaceContainerHighest = Neutral.N200,
    outline = Neutral.N300,
    outlineVariant = Neutral.N200,
    scrim = Neutral.N1000,
    error = Red600,
    onError = Neutral.N0,
    errorContainer = Color(0xFFFEF2F2),
    onErrorContainer = Color(0xFF7F1D1D),
    inverseSurface = Neutral.N900,
    inverseOnSurface = Neutral.N50,
    inversePrimary = Neutral.N0,
)

/**
 * The same ramp read from the other end.
 *
 * ollama.com is light-only, so this half is a translation rather than a copy. Keeping it to the
 * identical neutral steps — rather than reaching for the warm greys Android tends to default to —
 * is what stops the dark theme reading as a different product.
 */
internal val DarkColors = darkColorScheme(
    primary = Neutral.N50,
    onPrimary = Neutral.N950,
    primaryContainer = Neutral.N800,
    onPrimaryContainer = Neutral.N50,
    secondary = Neutral.N400,
    onSecondary = Neutral.N950,
    secondaryContainer = Neutral.N800,
    onSecondaryContainer = Neutral.N50,
    tertiary = Neutral.N400,
    onTertiary = Neutral.N950,
    tertiaryContainer = Neutral.N850,
    onTertiaryContainer = Neutral.N200,
    background = Neutral.N950,
    onBackground = Neutral.N50,
    surface = Neutral.N950,
    onSurface = Neutral.N50,
    surfaceVariant = Neutral.N800,
    onSurfaceVariant = Neutral.N400,
    surfaceContainerLowest = Neutral.N1000,
    surfaceContainerLow = Neutral.N925,
    surfaceContainer = Neutral.N900,
    surfaceContainerHigh = Neutral.N850,
    surfaceContainerHighest = Neutral.N800,
    outline = Neutral.N700,
    outlineVariant = Neutral.N800,
    scrim = Neutral.N1000,
    error = Red300,
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF2C1416),
    onErrorContainer = Color(0xFFFECACA),
    inverseSurface = Neutral.N50,
    inverseOnSurface = Neutral.N900,
    inversePrimary = Neutral.N900,
)

/**
 * The tinted pills on a model card.
 *
 * These are the only saturated colours in the interface, and they are lifted straight from
 * ollama.com's capability tags, where a wash of cyan or indigo behind two words does more to
 * separate "vision" from "tools" at a glance than any amount of typographic weight. Each is a very
 * pale fill under a mid-saturation label, which keeps a row of four badges quiet enough to sit
 * under a model name without becoming the loudest thing on the card.
 *
 * Kept out of [androidx.compose.material3.ColorScheme] for the same reason [CodeColors] is: a
 * capability has no Material role, and mapping it onto one would mean it changed meaning whenever
 * the scheme did.
 */
data class TagColors(
    val cyanBackground: Color,
    val cyanText: Color,
    val blueBackground: Color,
    val blueText: Color,
    val indigoBackground: Color,
    val indigoText: Color,
    val violetBackground: Color,
    val violetText: Color,
    val neutralBackground: Color,
    val neutralText: Color,
    /**
     * A hyperlink, which is the third thing in the app that colour has to carry.
     *
     * Monochrome has one genuine casualty: a link rendered in `primary` is now near-black, which
     * makes it identical to the sentence around it and leaves underlining as the only signal. Blue
     * is not a decoration here, it is the affordance, and it is the same blue as the `tools` tag so
     * the palette still holds together.
     */
    val linkText: Color,
    /**
     * The wash behind a search hit.
     *
     * Also load-bearing: a grey highlight on a grey ramp is invisible, and worse, a 30% black wash
     * is indistinguishable from the inline-code background it frequently lands on top of. Amber is
     * the one convention strong enough that nobody has to be told what it means.
     */
    val searchHighlight: Color,
) {
    companion object {
        val Light = TagColors(
            cyanBackground = Color(0xFFECFEFF),
            cyanText = Color(0xFF0891B2),
            blueBackground = Color(0xFFEFF6FF),
            blueText = Color(0xFF2563EB),
            indigoBackground = Color(0xFFEEF2FF),
            indigoText = Color(0xFF4F46E5),
            violetBackground = Color(0xFFF5F3FF),
            violetText = Color(0xFF7C3AED),
            neutralBackground = Color(0xFFF5F5F5),
            neutralText = Color(0xFF525252),
            linkText = Color(0xFF2563EB),
            searchHighlight = Color(0xFFFDE68A),
        )

        /**
         * The pale fills cannot simply be darkened — a 6% wash of cyan on near-black is invisible,
         * and the full-strength label is glaring against it. Each pair instead becomes a deep,
         * desaturated fill under a light tint of the same hue, which preserves the badge's identity
         * at the contrast the dark theme needs.
         */
        val Dark = TagColors(
            cyanBackground = Color(0xFF0C2E33),
            cyanText = Color(0xFF67E8F9),
            blueBackground = Color(0xFF0F2039),
            blueText = Color(0xFF93C5FD),
            indigoBackground = Color(0xFF1B1B3A),
            indigoText = Color(0xFFA5B4FC),
            violetBackground = Color(0xFF241B3A),
            violetText = Color(0xFFC4B5FD),
            neutralBackground = Color(0xFF262626),
            neutralText = Color(0xFFA3A3A3),
            linkText = Color(0xFF93C5FD),
            searchHighlight = Color(0xFF5A4708),
        )
    }
}

/**
 * Token colours for code blocks.
 *
 * Kept outside [androidx.compose.material3.ColorScheme] because syntax roles have no Material
 * equivalent, and because the palette must never be repainted by anything — readability there is
 * fixed. The hues are cooler and less saturated than the warm set they replace, so that a code
 * block sits inside the monochrome page as a quiet inset rather than as the one colourful panel on
 * the screen; the background is a plain step on the neutral ramp, exactly as on ollama.com.
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
            background = Color(0xFFF7F7F7),
            plain = Color(0xFF262626),
            keyword = Color(0xFF7C3AED),
            string = Color(0xFF047857),
            number = Color(0xFF9A3412),
            comment = Color(0xFF8A8A8A),
            function = Color(0xFF2563EB),
            type = Color(0xFF0E7490),
            punctuation = Color(0xFF737373),
            attribute = Color(0xFFB45309),
        )

        val Dark = CodeColors(
            background = Color(0xFF121212),
            plain = Color(0xFFE5E5E5),
            keyword = Color(0xFFC4B5FD),
            string = Color(0xFF86EFAC),
            number = Color(0xFFFDBA74),
            comment = Color(0xFF7A7A7A),
            function = Color(0xFF93C5FD),
            type = Color(0xFF67E8F9),
            punctuation = Color(0xFFA3A3A3),
            attribute = Color(0xFFFCD34D),
        )
    }
}
