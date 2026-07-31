package com.chmouel.liseur.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much width the app has been given, in the three bands Material
 * uses.
 *
 * There is no dependency here on the adaptive libraries: one number is
 * all this app needs, and the window already knows it. Everything that
 * reads this treats [COMPACT] as the shape it already had, so a phone
 * keeps the layout it has always had whatever else changes around it.
 */
enum class WidthClass {
    /** Phones, and anything given a phone-sized window. */
    COMPACT,

    /** Small tablets, e-ink readers, a phone turned sideways. */
    MEDIUM,

    /** Tablets from about 10" up. */
    EXPANDED,
    ;

    val isAtLeastMedium: Boolean get() = this != COMPACT

    companion object {
        /** Width in dp at which a window stops counting as [COMPACT]. */
        const val MEDIUM_MIN_DP = 600f

        /** Width in dp at which a window becomes [EXPANDED]. */
        const val EXPANDED_MIN_DP = 840f
    }
}

/** Width of the window in dp, whatever it is currently sharing the screen with. */
@Composable
@ReadOnlyComposable
fun windowWidth(): Dp {
    val width = LocalWindowInfo.current.containerSize.width
    return with(LocalDensity.current) { width.toDp() }
}

/** [WidthClass] of the current window. */
@Composable
@ReadOnlyComposable
fun widthClass(): WidthClass = widthClassOf(windowWidth())

/** The band [width] falls in. Split out so it can be tested without a window. */
fun widthClassOf(width: Dp): WidthClass = when {
    width.value < WidthClass.MEDIUM_MIN_DP -> WidthClass.COMPACT
    width.value < WidthClass.EXPANDED_MIN_DP -> WidthClass.MEDIUM
    else -> WidthClass.EXPANDED
}

/**
 * Widest a sheet or dialog should let its content grow.
 *
 * Left alone on a phone, where the content is already as wide as the
 * screen and capping it would only take space away. Above that the cap
 * is what keeps a slider from spanning a foot of tablet, where one step
 * of font size costs the whole width of the screen to drag.
 */
fun contentWidthCap(width: Dp): Dp = when (widthClassOf(width)) {
    WidthClass.COMPACT -> Dp.Unspecified
    else -> 560.dp
}

/**
 * Smallest a library cover may be laid out at.
 *
 * The grid is adaptive, so this is the knob that decides how many covers
 * fit on a line. A phone value spread across a tablet gives ten columns
 * of thumbnails too small to recognise, which is a worse way of showing
 * more books than simply showing bigger ones.
 */
fun coverMinSize(width: Dp): Dp = when (widthClassOf(width)) {
    WidthClass.COMPACT -> 108.dp
    WidthClass.MEDIUM -> 132.dp
    WidthClass.EXPANDED -> 156.dp
}
