package com.chmouel.liseur.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowSizeTest {

    @Test
    fun `a phone is compact`() {
        assertEquals(WidthClass.COMPACT, widthClassOf(360.dp))
        assertEquals(WidthClass.COMPACT, widthClassOf(411.dp))
        assertEquals(WidthClass.COMPACT, widthClassOf(599.dp))
    }

    @Test
    fun `a small tablet or e-ink reader is medium`() {
        assertEquals(WidthClass.MEDIUM, widthClassOf(600.dp))
        // Boox Go 7, 1264px at 300dpi.
        assertEquals(WidthClass.MEDIUM, widthClassOf(674.dp))
        assertEquals(WidthClass.MEDIUM, widthClassOf(839.dp))
    }

    @Test
    fun `a tablet is expanded`() {
        assertEquals(WidthClass.EXPANDED, widthClassOf(840.dp))
        assertEquals(WidthClass.EXPANDED, widthClassOf(1280.dp))
    }

    @Test
    fun `a phone keeps the cover size it had`() {
        assertEquals(108.dp, coverMinSize(411.dp))
    }

    @Test
    fun `covers grow with the screen`() {
        assertTrue(coverMinSize(674.dp) > coverMinSize(411.dp))
        assertTrue(coverMinSize(1280.dp) > coverMinSize(674.dp))
    }

    @Test
    fun `a phone shows the reading tile small`() {
        assertEquals(48.dp, brandTileHeight(360.dp))
    }

    @Test
    fun `an e-reader under the tablet threshold still gets a big tile`() {
        // Boox Go Color 7: 578dp, twenty short of Material's 600.
        assertEquals(100.dp, brandTileHeight(578.dp))
    }

    @Test
    fun `the reading tile stops growing on a big tablet`() {
        assertEquals(112.dp, brandTileHeight(2000.dp))
    }

    @Test
    fun `the reading tile grows with the screen`() {
        // Phone, then the Boox Go 7, then the cap.
        assertTrue(brandTileHeight(578.dp) > brandTileHeight(411.dp))
        assertTrue(brandTileHeight(1280.dp) > brandTileHeight(578.dp))
    }

    @Test
    fun `a phone keeps Material's own bar height`() {
        assertEquals(64.dp, libraryBarHeight(411.dp))
    }

    @Test
    fun `the bar always has room for the tile`() {
        for (width in listOf(360.dp, 674.dp, 1280.dp)) {
            assertTrue(libraryBarHeight(width) > brandTileHeight(width))
        }
    }

    @Test
    fun `a phone caps nothing, so sheets stay full width`() {
        assertEquals(androidx.compose.ui.unit.Dp.Unspecified, contentWidthCap(411.dp))
    }

    @Test
    fun `wider screens cap sheet content`() {
        assertEquals(560.dp, contentWidthCap(1280.dp))
    }
}
