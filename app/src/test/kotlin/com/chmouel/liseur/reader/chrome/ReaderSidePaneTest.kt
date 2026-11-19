package com.chmouel.liseur.reader.chrome

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSidePaneTest {

    @Test
    fun `compact phone width 411dp disables side pane`() {
        val metrics = resolveReaderSidePane(windowWidthDp = 411f)
        assertFalse(metrics.enabled)
        assertEquals(411f, metrics.paneWidthDp, 0.01f)
        assertFalse(metrics.bookLeaveOpenOnNavigate)
    }

    @Test
    fun `narrow medium width 578dp disables side pane`() {
        val metrics = resolveReaderSidePane(windowWidthDp = 578f)
        assertFalse(metrics.enabled)
        assertEquals(578f, metrics.paneWidthDp, 0.01f)
        assertFalse(metrics.bookLeaveOpenOnNavigate)
    }

    @Test
    fun `760dp window enables side pane capped by 44 percent fraction`() {
        val metrics = resolveReaderSidePane(windowWidthDp = 760f)
        assertTrue(metrics.enabled)
        assertEquals(334.4f, metrics.paneWidthDp, 0.01f)
        assertTrue(metrics.bookLeaveOpenOnNavigate)
    }

    @Test
    fun `960dp window enables side pane capped at 380dp`() {
        val metrics = resolveReaderSidePane(windowWidthDp = 960f)
        assertTrue(metrics.enabled)
        assertEquals(SIDE_PANE_WIDTH_DP, metrics.paneWidthDp, 0.01f)
        assertTrue(metrics.bookLeaveOpenOnNavigate)
    }

    @Test
    fun `Book posture sizes side pane to right leaf width`() {
        val book = ReaderFoldPosture.Book(
            hingeBoundsPx = IntRect(880, 0, 920, 2200),
            isHalfOpened = true,
            hingeGapPx = 72,
        )
        val metrics = resolveReaderSidePane(
            windowWidthDp = 900f,
            foldPosture = book,
            density = 2f,
        )
        assertTrue(metrics.enabled)
        assertEquals(440f, metrics.paneWidthDp, 0.01f)
        assertTrue(metrics.bookLeaveOpenOnNavigate)
    }

    @Test
    fun `Book posture clamps narrow right leaf to 280dp minimum`() {
        val book = ReaderFoldPosture.Book(
            hingeBoundsPx = IntRect(1400, 0, 1440, 2200),
            isHalfOpened = true,
            hingeGapPx = 72,
        )
        val metrics = resolveReaderSidePane(
            windowWidthDp = 800f,
            foldPosture = book,
            density = 2f,
        )
        assertTrue(metrics.enabled)
        assertEquals(280f, metrics.paneWidthDp, 0.01f)
        assertTrue(metrics.bookLeaveOpenOnNavigate)
    }

    @Test
    fun `Book posture with non-positive density falls through to window width rule`() {
        val book = ReaderFoldPosture.Book(
            hingeBoundsPx = IntRect(880, 0, 920, 2200),
            isHalfOpened = true,
            hingeGapPx = 72,
        )
        val wideFallback = resolveReaderSidePane(
            windowWidthDp = 760f,
            foldPosture = book,
            density = 0f,
        )
        assertTrue(wideFallback.enabled)
        assertEquals(334.4f, wideFallback.paneWidthDp, 0.01f)
        assertTrue(wideFallback.bookLeaveOpenOnNavigate)

        val compactFallback = resolveReaderSidePane(
            windowWidthDp = 500f,
            foldPosture = book,
            density = -1f,
        )
        assertFalse(compactFallback.enabled)
        assertEquals(500f, compactFallback.paneWidthDp, 0.01f)
        assertFalse(compactFallback.bookLeaveOpenOnNavigate)
    }
}
