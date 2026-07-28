package com.chmouel.liseur.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Leather,
    onPrimary = Color.White,
    primaryContainer = LeatherLight,
    onPrimaryContainer = LeatherDark,
    secondary = Sage,
    onSecondary = Color.White,
    secondaryContainer = SageLight,
    onSecondaryContainer = Ink,
    tertiary = Teal,
    onTertiary = Color.White,
    tertiaryContainer = TealLight,
    onTertiaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDim,
    onSurfaceVariant = InkSoft,
)

private val DarkColors = darkColorScheme(
    primary = LeatherNight,
    onPrimary = LeatherDark,
    primaryContainer = Leather,
    onPrimaryContainer = LeatherLight,
    secondary = SageNight,
    onSecondary = Ink,
    tertiary = TealNight,
    onTertiary = Ink,
    background = NightSurface,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = SageNight,
)

@Composable
fun LiseurTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand palette by default; dynamic color stays available as an option.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LiseurTypography,
        content = content,
    )
}
