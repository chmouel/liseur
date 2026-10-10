package com.chmouel.liseur.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * This padding without its bottom edge.
 *
 * A Scaffold's inner padding put on a scrolling list's container stops the
 * list at the navigation bar, so the rows never pass under it. The
 * container takes the top and sides from here instead, and the list takes
 * the bottom as content padding: the last row still comes to rest clear of
 * the bar, and the rows before it scroll on underneath.
 */
@Composable
fun PaddingValues.withoutBottom(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding(),
        end = calculateEndPadding(direction),
    )
}
