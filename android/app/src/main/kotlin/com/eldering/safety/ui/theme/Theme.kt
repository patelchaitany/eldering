package com.eldering.safety.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalElderingPalette = staticCompositionLocalOf { LightPalette }

/**
 * App theme. Dynamic (wallpaper) color is intentionally not used: alert and status colors carry
 * meaning (green = protected, orange = accident) and must look the same on every phone.
 */
@Composable
fun ElderingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalElderingPalette provides if (darkTheme) DarkPalette else LightPalette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = ElderingTypography,
            content = content,
        )
    }
}

/** Design tokens beyond Material's color scheme. */
object Eldering {
    val palette: ElderingPalette
        @Composable @ReadOnlyComposable
        get() = LocalElderingPalette.current

    val type: ElderingTextStyles get() = ElderingTextStyles
}
