package com.chmouel.liseur.ui.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
import com.chmouel.liseur.data.settings.ReaderTheme
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

/** Which palette policy the resolved e-ink setting asks the app to use. */
internal enum class EInkPalette {
    NONE,
    MONOCHROME,
    COLOR,
}

/** A saved colour preference has no effect while the central mode is off. */
internal fun eInkPalette(eInk: Boolean, colorEInk: Boolean): EInkPalette = when {
    !eInk -> EInkPalette.NONE
    colorEInk -> EInkPalette.COLOR
    else -> EInkPalette.MONOCHROME
}

/** A palette at both its lightnesses, so either can be asked for by name. */
internal class PalettePair(val light: ColorScheme, val dark: ColorScheme) {
    fun at(dark: Boolean): ColorScheme = if (dark) this.dark else light
}

/** Liseur's own paper-and-ink palette. */
internal val BrandPalette = PalettePair(LightColors, DarkColors)

/** The greyscale palette electronic paper gets instead. */
internal val MonoPalette = PalettePair(MonoLightColors, MonoDarkColors)

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

/**
 * The fixed palette pair this configuration draws from.
 *
 * Wallpaper colours are not in here, because a pair is a palette at both
 * its lightnesses and this is the fixed half of the answer: the one that
 * does not depend on a [android.content.Context]. Anything that needs both
 * lightnesses of the same palette — which is what painting chrome on a
 * reading page needs — starts here.
 */
internal fun palettePairFor(eInk: EInkPalette): PalettePair =
    if (eInk == EInkPalette.MONOCHROME) MonoPalette else BrandPalette

/**
 * Whether this configuration takes its colours from the wallpaper.
 *
 * Split out of [schemeFor] so the other branch can be checked without a
 * [android.content.Context]. The loading indicator's exception rests on
 * what happens when this is false, and that half is worth a test.
 *
 * [available] is a parameter only so a test can shut the gate by hand; a
 * unit test sees no API level at all, so every answer would otherwise be
 * false for the wrong reason. Nothing in the app passes it.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
internal fun usesWallpaperColours(
    eInk: EInkPalette,
    dynamicColor: Boolean,
    available: Boolean = dynamicColorAvailable,
): Boolean = eInk == EInkPalette.NONE && dynamicColor && available

/** The single scheme in force, wallpaper colours included. */
@Composable
private fun schemeFor(
    dark: Boolean,
    eInk: EInkPalette,
    dynamicColor: Boolean,
): ColorScheme {
    val context = LocalContext.current
    if (usesWallpaperColours(eInk, dynamicColor)) {
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return palettePairFor(eInk).at(dark)
}

/**
 * The accent the app itself would draw in, asked for at a given lightness.
 *
 * The reader installs the reading page over `MaterialTheme`, so inside it
 * there is no way left to ask what the app's own colours are. The loading
 * indicator needs to: see [loadingAccentOn]. The lightness is a parameter
 * rather than the app's own because the indicator sits on the reading page,
 * and it is the page's darkness that decides which half of a palette
 * belongs on it.
 *
 * Returns the same colour the page would have given when wallpaper colour
 * is off or unavailable, since both then come from the same palette at the
 * same lightness.
 */
@Composable
internal fun appAccent(
    dark: Boolean,
    eInk: Boolean,
    colorEInk: Boolean,
    dynamicColor: Boolean,
): Color = schemeFor(
    dark = dark,
    eInk = eInkPalette(eInk, colorEInk),
    dynamicColor = dynamicColor,
).primary

@Composable
fun LiseurTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Wallpaper colours where the system offers them; the palette above
    // is the fallback, and what you get back by turning this off.
    dynamicColor: Boolean = dynamicColorAvailable,
    // Electronic paper never uses wallpaper-derived colours: its useful
    // palette must be stable and small. A colour panel keeps Liseur's own
    // restrained accents; a monochrome one spends the full range on contrast.
    eInk: Boolean = false,
    colorEInk: Boolean = false,
    /**
     * The page the chrome inside is sitting on, when it is sitting on one.
     *
     * Non-null only in the reader. Everything Material draws under it —
     * every sheet, dialog, menu, slider and text field — is then already
     * the colour of the paper, instead of each one having to remember to
     * ask. Null everywhere else, which is the app's own theme unchanged.
     *
     * Wallpaper colour is deliberately dropped when this is set: see
     * [readingColorScheme].
     */
    readingPage: ReaderTheme? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val palette = eInkPalette(eInk, colorEInk)
    val colorScheme = if (readingPage != null) {
        val pair = palettePairFor(palette)
        readingColorScheme(
            light = pair.light,
            dark = pair.dark,
            page = readingPage,
            eInk = eInk,
        )
    } else {
        schemeFor(dark = darkTheme, eInk = palette, dynamicColor = dynamicColor)
    }

    val typography = remember { liseurTypography(literataFamily(context.assets)) }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content,
    )
}
