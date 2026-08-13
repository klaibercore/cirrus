package dev.klaiber.cirrus.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import dev.klaiber.cirrus.R

/**
 * One weight axis, one file.
 *
 * Nunito ships as a variable font, so all six weights the interface uses come out of a single 277KB
 * resource instead of six separate static cuts — which is the only reason bundling a typeface at all
 * is defensible in an app this size. The `wght` axis is set per registered weight, so
 * `FontWeight.SemiBold` resolves to a real 600 instance rather than to a synthetically emboldened
 * 400. Variable fonts have been honoured since API 26 and the app's floor is 29.
 */
@OptIn(ExperimentalTextApi::class)
private fun nunito(weight: Int) = Font(
    resId = R.font.nunito_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/**
 * The display face, after ollama.com's use of SF Pro Rounded.
 *
 * The site sets every heading in a rounded face and every paragraph in the platform's own UI font,
 * and that split is most of its voice: the rounded terminals make a page of black-on-white feel
 * approachable rather than clinical, while the body text stays in whatever the reader's device has
 * already tuned for long-form reading. Nunito is the closest freely licensable equivalent — humanist
 * proportions, genuinely rounded stroke ends — and it is used here exactly as SF Pro Rounded is
 * there: headings, titles and nothing else.
 *
 * Licensed under the SIL Open Font License; the licence ships in `assets/nunito-ofl-license.txt`.
 */
val DisplayFamily = FontFamily(
    nunito(400),
    nunito(500),
    nunito(600),
    nunito(700),
)

/**
 * The reading face: whatever the device calls its UI sans.
 *
 * Not a stylistic shrug. ollama.com's body stack is `system-ui`, and on Android the honest
 * translation of that is the platform default rather than a second bundled face — a phone with a
 * user-chosen system font should keep it for the thing the user actually reads.
 */
private val ReadingFamily = FontFamily.Default

/**
 * Body styles are tuned for sustained reading: slightly larger than Material's defaults with
 * generous line height, and trimmed line-height padding so message bubbles hug their text.
 */
private val ReadingLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * Every style sets `letterSpacing` explicitly to zero.
 *
 * Material tracks its small labels out by up to 0.5sp, which is a sensible default for Roboto at
 * a glanceable size and completely wrong here — ollama.com sets normal tracking at every size, and
 * that tighter setting is a surprising amount of why a screenshot of the site reads as the site.
 * Leaving the field unset would silently inherit the Material value, so it is stated each time.
 */
internal val CirrusTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = ReadingFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = ReadingLineHeightStyle,
    ),
    bodyMedium = TextStyle(
        fontFamily = ReadingFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = ReadingLineHeightStyle,
    ),
    bodySmall = TextStyle(
        fontFamily = ReadingFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = ReadingFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = ReadingFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = ReadingFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.sp,
    ),
)

/** Monospace style shared by code blocks, inline code and the JSON inspector. */
internal val CodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp,
)
