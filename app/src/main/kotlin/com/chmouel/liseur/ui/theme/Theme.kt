package com.chmouel.liseur.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.chmouel.liseur.data.settings.ThemeMode

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

/*
 * E-ink panels are greyscale. Any hue we send them is flattened to its
 * luminance, and the app's leather/teal palette happens to sit in the
 * middle of that range, so tinted chrome arrives as muddy mid-grey with
 * very little separation from the surfaces around it. These two schemes
 * spend the whole range on contrast instead of colour: pure black and
 * white at the ends, a handful of greys in between, nothing else.
 */
private val MonoLightColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCDCDC),
    onPrimaryContainer = Color.Black,
    inversePrimary = Color.White,
    secondary = Color(0xFF2B2B2B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF2B2B2B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8E8E8),
    onTertiaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF2B2B2B),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE4E4E4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    outline = Color(0xFF3D3D3D),
    outlineVariant = Color(0xFF9E9E9E),
    inverseSurface = Color.Black,
    inverseOnSurface = Color.White,
    error = Color.Black,
    onError = Color.White,
    errorContainer = Color(0xFFE0E0E0),
    onErrorContainer = Color.Black,
)

private val MonoDarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3D3D3D),
    onPrimaryContainer = Color.White,
    inversePrimary = Color.Black,
    secondary = Color(0xFFE0E0E0),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2B2B2B),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFE0E0E0),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF2B2B2B),
    onTertiaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFE0E0E0),
    surfaceBright = Color(0xFF2B2B2B),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0F0F0F),
    surfaceContainer = Color(0xFF1C1C1C),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF303030),
    outline = Color(0xFFC7C7C7),
    outlineVariant = Color(0xFF5C5C5C),
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    error = Color.White,
    onError = Color.Black,
    errorContainer = Color(0xFF3D3D3D),
    onErrorContainer = Color.White,
)

/** Whether this device can take its colours from the wallpaper. */
val dynamicColorAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Whether the app is dark right now.
 *
 * Only Compose can answer this, because [ThemeMode.SYSTEM] defers to a
 * setting that can change under a running activity. Both activities and
 * the reading theme ask through here, so there is one answer.
 */
@Composable
@ReadOnlyComposable
fun ThemeMode.isDark(): Boolean = isDark(isSystemInDarkTheme())

@Composable
fun LiseurTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Wallpaper colours where the system offers them; the palette above
    // is the fallback, and what you get back by turning this off.
    dynamicColor: Boolean = dynamicColorAvailable,
    // Greyscale hardware: drop hue entirely and spend the range on
    // contrast. Wins over dynamicColor, which is meaningless here.
    monochrome: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        monochrome -> if (darkTheme) MonoDarkColors else MonoLightColors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val typography = remember { liseurTypography(literataFamily(context.assets)) }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content,
    )
}
