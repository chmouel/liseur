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

/*
 * Every role M3 draws from is set here, deliberately.
 *
 * Only a handful used to be, and the rest kept Material's own defaults,
 * which are neutrals mixed towards the baseline purple. Nothing looked
 * obviously broken because nothing was obviously wrong: the page was
 * warm and the menus, sheets, cards and scrolled app bars sitting on it
 * were faintly lilac, which reads as the app being slightly grubby
 * rather than as a bug. The containers below are what fixes that.
 */
private val LightColors = lightColorScheme(
    primary = Leather,
    onPrimary = Color.White,
    primaryContainer = LeatherLight,
    onPrimaryContainer = LeatherDark,
    inversePrimary = LeatherNight,
    secondary = LeatherSoft,
    onSecondary = Color.White,
    secondaryContainer = LeatherWash,
    onSecondaryContainer = LeatherInk,
    tertiary = Teal,
    onTertiary = Color.White,
    tertiaryContainer = TealLight,
    onTertiaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperWarm,
    onSurfaceVariant = InkSoft,
    surfaceBright = Paper,
    surfaceDim = PaperDim,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = PaperRaised,
    surfaceContainer = PaperCard,
    surfaceContainerHigh = PaperHigh,
    surfaceContainerHighest = PaperHighest,
    outline = RuleStrong,
    outlineVariant = Rule,
    inverseSurface = InkInverse,
    inverseOnSurface = PaperInverse,
)

private val DarkColors = darkColorScheme(
    primary = LeatherNight,
    onPrimary = LeatherDark,
    primaryContainer = Leather,
    onPrimaryContainer = LeatherLight,
    inversePrimary = Leather,
    secondary = LeatherNight,
    onSecondary = LeatherDark,
    secondaryContainer = LeatherNightDeep,
    onSecondaryContainer = LeatherLight,
    tertiary = TealNight,
    onTertiary = Ink,
    tertiaryContainer = TealNightDeep,
    onTertiaryContainer = TealLight,
    background = NightSurface,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightVariant,
    onSurfaceVariant = NightTextSoft,
    surfaceBright = NightSurfaceBright,
    surfaceDim = NightSurface,
    surfaceContainerLowest = NightSurfaceLowest,
    surfaceContainerLow = NightSurfaceLow,
    surfaceContainer = NightSurfaceContainer,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerHighest = NightSurfaceHighest,
    outline = NightRuleStrong,
    outlineVariant = NightRule,
    inverseSurface = NightText,
    inverseOnSurface = NightSurface,
)

/** Whether this device can take its colours from the wallpaper. */
val dynamicColorAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun LiseurTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Wallpaper colours where the system offers them; the palette above
    // is the fallback, and what you get back by turning this off.
    dynamicColor: Boolean = dynamicColorAvailable,
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
