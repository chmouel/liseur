package com.chmouel.liseur.ui.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.material3.ColorProviders
import com.chmouel.liseur.ui.theme.Ink
import com.chmouel.liseur.ui.theme.InkInverse
import com.chmouel.liseur.ui.theme.InkSoft
import com.chmouel.liseur.ui.theme.Leather
import com.chmouel.liseur.ui.theme.LeatherDark
import com.chmouel.liseur.ui.theme.LeatherInk
import com.chmouel.liseur.ui.theme.LeatherLight
import com.chmouel.liseur.ui.theme.LeatherNight
import com.chmouel.liseur.ui.theme.LeatherNightDeep
import com.chmouel.liseur.ui.theme.LeatherSoft
import com.chmouel.liseur.ui.theme.LeatherWash
import com.chmouel.liseur.ui.theme.NightRule
import com.chmouel.liseur.ui.theme.NightRuleStrong
import com.chmouel.liseur.ui.theme.NightSurface
import com.chmouel.liseur.ui.theme.NightSurfaceBright
import com.chmouel.liseur.ui.theme.NightSurfaceContainer
import com.chmouel.liseur.ui.theme.NightSurfaceHigh
import com.chmouel.liseur.ui.theme.NightSurfaceHighest
import com.chmouel.liseur.ui.theme.NightSurfaceLow
import com.chmouel.liseur.ui.theme.NightSurfaceLowest
import com.chmouel.liseur.ui.theme.NightText
import com.chmouel.liseur.ui.theme.NightTextSoft
import com.chmouel.liseur.ui.theme.NightVariant
import com.chmouel.liseur.ui.theme.Paper
import com.chmouel.liseur.ui.theme.PaperCard
import com.chmouel.liseur.ui.theme.PaperDim
import com.chmouel.liseur.ui.theme.PaperHigh
import com.chmouel.liseur.ui.theme.PaperHighest
import com.chmouel.liseur.ui.theme.PaperInverse
import com.chmouel.liseur.ui.theme.PaperRaised
import com.chmouel.liseur.ui.theme.PaperWarm
import com.chmouel.liseur.ui.theme.Rule
import com.chmouel.liseur.ui.theme.RuleStrong
import com.chmouel.liseur.ui.theme.Teal
import com.chmouel.liseur.ui.theme.TealLight
import com.chmouel.liseur.ui.theme.TealNight
import com.chmouel.liseur.ui.theme.TealNightDeep

/**
 * Paper-and-ink Glance colours. Always the brand scheme — never wallpaper
 * dynamic colour, which would paint the cover widget purple on Android 12+.
 */
object LiseurGlanceColorScheme {
    val colors = ColorProviders(
        light = lightColorScheme(
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
        ),
        dark = darkColorScheme(
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
            surfaceDim = NightSurfaceLowest,
            surfaceContainerLowest = NightSurfaceLowest,
            surfaceContainerLow = NightSurfaceLow,
            surfaceContainer = NightSurfaceContainer,
            surfaceContainerHigh = NightSurfaceHigh,
            surfaceContainerHighest = NightSurfaceHighest,
            outline = NightRuleStrong,
            outlineVariant = NightRule,
            inverseSurface = PaperInverse,
            inverseOnSurface = InkInverse,
        ),
    )
}
