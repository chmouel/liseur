package com.chmouel.liseur.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the e-ink sheet goes, whatever it was called from. */
class WindowBottomPositionTest {

    private val window = IntSize(1264, 1680)
    private val sheet = IntSize(1000, 600)

    @Test
    fun `scrolling content taller than the screen does not push the sheet off it`() {
        // The read aloud settings column once the device voices have loaded.
        val anchor = IntRect(left = 40, top = 200, right = 1224, bottom = 3400)

        val position = WindowBottomPosition.calculatePosition(anchor, window, LayoutDirection.Ltr, sheet)

        assertEquals(IntOffset(132, 1080), position)
    }

    @Test
    fun `a small anchor near the top still puts the sheet at the bottom`() {
        val anchor = IntRect(left = 0, top = 100, right = 200, bottom = 150)

        val position = WindowBottomPosition.calculatePosition(anchor, window, LayoutDirection.Rtl, sheet)

        assertEquals(IntOffset(132, 1080), position)
    }
}
