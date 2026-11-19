package com.chmouel.liseur.reader.chrome

import androidx.compose.ui.unit.IntRect
import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.ui.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFoldPostureTest {

    @Test
    fun `flat non-separating feature resolves to Flat`() {
        val posture = resolveReaderFoldPosture(
            windowWidthPx = 1800,
            windowHeightPx = 2200,
            orientationVertical = true,
            isHalfOpened = false,
            isSeparating = false,
            boundsLeft = 890,
            boundsTop = 0,
            boundsRight = 910,
            boundsBottom = 2200,
        )
        assertEquals(ReaderFoldPosture.Flat, posture)
    }

    @Test
    fun `non-positive window dimensions resolve to Flat`() {
        assertEquals(
            ReaderFoldPosture.Flat,
            resolveReaderFoldPosture(
                windowWidthPx = 0,
                windowHeightPx = 2200,
                orientationVertical = true,
                isHalfOpened = true,
                isSeparating = true,
                boundsLeft = 400,
                boundsTop = 0,
                boundsRight = 420,
                boundsBottom = 2200,
            ),
        )
        assertEquals(
            ReaderFoldPosture.Flat,
            resolveReaderFoldPosture(
                windowWidthPx = 1800,
                windowHeightPx = -1,
                orientationVertical = false,
                isHalfOpened = true,
                isSeparating = true,
                boundsLeft = 0,
                boundsTop = 1000,
                boundsRight = 1800,
                boundsBottom = 1020,
            ),
        )
    }

    @Test
    fun `vertical half-opened fold resolves to Book with minimum hinge gap`() {
        val posture = resolveReaderFoldPosture(
            windowWidthPx = 1800,
            windowHeightPx = 2200,
            orientationVertical = true,
            isHalfOpened = true,
            isSeparating = true,
            boundsLeft = 900,
            boundsTop = 0,
            boundsRight = 900,
            boundsBottom = 2200,
        )
        val book = posture as ReaderFoldPosture.Book
        assertEquals(IntRect(900, 0, 900, 2200), book.hingeBoundsPx)
        assertTrue(book.isHalfOpened)
        assertEquals(48, book.hingeGapPx)
    }

    @Test
    fun `vertical separating flat foldable resolves to Book with isHalfOpened false and padded hinge gap`() {
        val posture = resolveReaderFoldPosture(
            windowWidthPx = 1800,
            windowHeightPx = 2200,
            orientationVertical = true,
            isHalfOpened = false,
            isSeparating = true,
            boundsLeft = 880,
            boundsTop = 0,
            boundsRight = 920,
            boundsBottom = 2200,
        )
        val book = posture as ReaderFoldPosture.Book
        assertEquals(IntRect(880, 0, 920, 2200), book.hingeBoundsPx)
        assertFalse(book.isHalfOpened)
        assertEquals(72, book.hingeGapPx)
    }

    @Test
    fun `horizontal half-opened fold resolves to Tabletop with clamped pane heights`() {
        val posture = resolveReaderFoldPosture(
            windowWidthPx = 2200,
            windowHeightPx = 1800,
            orientationVertical = false,
            isHalfOpened = true,
            isSeparating = true,
            boundsLeft = 0,
            boundsTop = 890,
            boundsRight = 2200,
            boundsBottom = 910,
        )
        val tabletop = posture as ReaderFoldPosture.Tabletop
        assertEquals(IntRect(0, 890, 2200, 910), tabletop.hingeBoundsPx)
        assertEquals(890, tabletop.topPaneHeightPx)
        assertEquals(890, tabletop.bottomPaneHeightPx)
    }

    @Test
    fun `horizontal separating but not half-opened fold resolves to Flat`() {
        val posture = resolveReaderFoldPosture(
            windowWidthPx = 2200,
            windowHeightPx = 1800,
            orientationVertical = false,
            isHalfOpened = false,
            isSeparating = true,
            boundsLeft = 0,
            boundsTop = 890,
            boundsRight = 2200,
            boundsBottom = 910,
        )
        assertEquals(ReaderFoldPosture.Flat, posture)
    }

    @Test
    fun `ColumnMode effectiveFor promotes AUTO to TWO in Book posture when leaf is at least 240px wide`() {
        val wideBook = ReaderFoldPosture.Book(
            hingeBoundsPx = IntRect(900, 0, 920, 2200),
            isHalfOpened = true,
            hingeGapPx = 52,
        )
        assertEquals(
            ColumnMode.TWO,
            ColumnMode.AUTO.effectiveFor(WidthClass.COMPACT, wideBook),
        )
        assertEquals(
            ColumnMode.TWO,
            ColumnMode.AUTO.effectiveFor(WidthClass.MEDIUM, wideBook),
        )
        assertEquals(
            ColumnMode.ONE,
            ColumnMode.ONE.effectiveFor(WidthClass.MEDIUM, wideBook),
        )
    }

    @Test
    fun `ColumnMode effectiveFor falls back to widthClass when Book leaf is narrower than 240px or posture is not Book`() {
        val narrowBook = ReaderFoldPosture.Book(
            hingeBoundsPx = IntRect(200, 0, 220, 2200),
            isHalfOpened = true,
            hingeGapPx = 52,
        )
        assertEquals(
            ColumnMode.AUTO,
            ColumnMode.AUTO.effectiveFor(WidthClass.COMPACT, narrowBook),
        )
        assertEquals(
            ColumnMode.AUTO,
            ColumnMode.AUTO.effectiveFor(WidthClass.COMPACT, ReaderFoldPosture.Flat),
        )
        val tabletop = ReaderFoldPosture.Tabletop(
            hingeBoundsPx = IntRect(0, 890, 2200, 910),
            topPaneHeightPx = 890,
            bottomPaneHeightPx = 890,
        )
        assertEquals(
            ColumnMode.AUTO,
            ColumnMode.AUTO.effectiveFor(WidthClass.COMPACT, tabletop),
        )
        assertEquals(
            ColumnMode.TWO,
            ColumnMode.AUTO.effectiveFor(WidthClass.EXPANDED, tabletop),
        )
    }

    @Test
    fun `bookPostureCssOverrides emits colGap in css pixels only for Book posture with positive density`() {
        val book = ReaderFoldPosture.Book(
            hingeBoundsPx = IntRect(890, 0, 910, 2200),
            isHalfOpened = true,
            hingeGapPx = 60,
        )
        assertEquals(
            mapOf("--RS__colGap" to "30px"),
            bookPostureCssOverrides(book, density = 2f),
        )
        assertEquals(
            emptyMap<String, String>(),
            bookPostureCssOverrides(book, density = 0f),
        )
        assertEquals(
            emptyMap<String, String>(),
            bookPostureCssOverrides(ReaderFoldPosture.Flat, density = 2f),
        )
        val tabletop = ReaderFoldPosture.Tabletop(
            hingeBoundsPx = IntRect(0, 890, 2200, 910),
            topPaneHeightPx = 890,
            bottomPaneHeightPx = 890,
        )
        assertEquals(
            emptyMap<String, String>(),
            bookPostureCssOverrides(tabletop, density = 2f),
        )
    }

    @Test
    fun `resolveBookLeafBounds selects the tapped leaf and avoids straddling the hinge`() {
        val book = ReaderFoldPosture.Book(
            hingeBoundsPx = IntRect(890, 0, 910, 2200),
            isHalfOpened = true,
            hingeGapPx = 52,
        )
        assertEquals(
            BookLeafBounds(leftPx = 0, widthPx = 890),
            resolveBookLeafBounds(windowWidthPx = 1800, posture = book, anchorX = 400f),
        )
        assertEquals(
            BookLeafBounds(leftPx = 910, widthPx = 890),
            resolveBookLeafBounds(windowWidthPx = 1800, posture = book, anchorX = 1200f),
        )
        assertNull(resolveBookLeafBounds(windowWidthPx = 1800, posture = ReaderFoldPosture.Flat, anchorX = 400f))
    }
}
