package dev.klaiber.cirrus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
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
 * There is no dynamic-colour branch, and that is the design rather than an omission. Material You
 * derives a scheme from the wallpaper, which is a good default for an app with no visual position
 * of its own and directly destructive of one that has committed to zero chroma: a lilac-tinted
 * "monochrome" interface is neither. Removing the option also removes the state in which a
 * screenshot of Cirrus is unrecognisable as Cirrus.
 *
 * What remains configurable is light versus dark, which is a question about the room the phone is
 * in rather than about the design.
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

    val colorScheme = if (darkTheme) DarkColors else LightColors

    // Edge-to-edge draws the transcript under both system bars, so the icons in them have to be
    // told which way to invert. Without this the status-bar clock is white on white the moment the
    // light theme is picked on a device whose system default is dark.
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalCodeColors provides if (darkTheme) CodeColors.Dark else CodeColors.Light,
        LocalTagColors provides if (darkTheme) TagColors.Dark else TagColors.Light,
        LocalAccents provides if (darkTheme) CirrusAccents.Dark else CirrusAccents.Light,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CirrusTypography,
            shapes = CirrusShapes,
            content = content,
        )
    }
}
