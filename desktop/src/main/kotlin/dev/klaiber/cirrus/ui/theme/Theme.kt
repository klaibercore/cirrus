package dev.klaiber.cirrus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.klaiber.cirrus.domain.model.ThemeMode

/** Code token colours, provided here so any nesting depth can render highlighted code. */
val LocalCodeColors = staticCompositionLocalOf { CodeColors.Light }

/** Capability-badge colours, on the same footing and for the same reason. */
val LocalTagColors = staticCompositionLocalOf { TagColors.Light }

/**
 * The mark's accents, likewise: a brace is structure and a streaking sweep is electricity, and
 * neither is a Material role that a scheme could be trusted to keep meaning the same thing.
 */
val LocalAccents = staticCompositionLocalOf { CirrusAccents.Light }

/**
 * The app's one theme.
 *
 * There is no dynamic-colour branch, and that is the design rather than an omission: a scheme
 * derived from the desktop wallpaper is directly destructive of one that has committed to zero
 * chroma, and removing the option removes the state in which a screenshot of Cirrus is
 * unrecognisable as Cirrus.
 *
 * What remains configurable is light versus dark, which is a question about the room the machine
 * is in rather than about the design. There is no window-insets branch here — that was Android's
 * edge-to-edge drawing under the system bars, and a desktop window has none.
 */
@Composable
fun CirrusTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    CompositionLocalProvider(
        LocalCodeColors provides if (darkTheme) CodeColors.Dark else CodeColors.Light,
        LocalTagColors provides if (darkTheme) TagColors.Dark else TagColors.Light,
        LocalAccents provides if (darkTheme) CirrusAccents.Dark else CirrusAccents.Light,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = CirrusTypography,
            shapes = CirrusShapes,
            content = content,
        )
    }
}
