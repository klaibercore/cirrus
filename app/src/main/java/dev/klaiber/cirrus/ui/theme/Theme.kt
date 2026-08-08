package dev.klaiber.cirrus.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import dev.klaiber.cirrus.domain.model.ThemeMode

/** Code token colours, provided here so any nesting depth can render highlighted code. */
val LocalCodeColors = staticCompositionLocalOf { CodeColors.Light }

@Composable
fun CirrusTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(
        LocalCodeColors provides if (darkTheme) CodeColors.Dark else CodeColors.Light,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CirrusTypography,
            content = content,
        )
    }
}
