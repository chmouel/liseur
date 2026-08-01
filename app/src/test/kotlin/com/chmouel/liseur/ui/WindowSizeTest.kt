package com.chmouel.liseur.ui

import androidx.compose.ui.unit.dp
import com.chmouel.liseur.data.settings.EInkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a phone keeps the reading tile it had`() {
        assertEquals(44.dp, brandTileHeight(411.dp))
    }

    @Test
    fun `the reading tile grows with the screen`() {
        // Boox Go 7.
        assertTrue(brandTileHeight(674.dp) > brandTileHeight(411.dp))
        assertTrue(brandTileHeight(1280.dp) > brandTileHeight(674.dp))
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

class EInkTest {

    private fun noFeatures(name: String) = false

    @Test
    fun `an ordinary phone is not e-ink`() {
        assertFalse(
            isEInkDevice(
                manufacturer = "Google",
                brand = "google",
                model = "Pixel 8",
                device = "shiba",
                hasFeature = ::noFeatures,
            ),
        )
    }

    @Test
    fun `an Onyx Boox is e-ink by name`() {
        assertTrue(
            isEInkDevice(
                manufacturer = "ONYX",
                brand = "Onyx",
                model = "Go 7",
                device = "GO7",
                hasFeature = ::noFeatures,
            ),
        )
    }

    @Test
    fun `a device that declares the feature is taken at its word`() {
        assertTrue(
            isEInkDevice(
                manufacturer = "Nobody",
                brand = "nobody",
                model = "Unknown",
                device = "unknown",
                hasFeature = { it == "android.hardware.type.eink" },
            ),
        )
    }

    @Test
    fun `auto follows the device`() {
        assertTrue(EInkMode.AUTO.resolve(deviceLooksLikeEInk = true))
        assertFalse(EInkMode.AUTO.resolve(deviceLooksLikeEInk = false))
    }

    @Test
    fun `the manual settings overrule the guess`() {
        assertTrue(EInkMode.ON.resolve(deviceLooksLikeEInk = false))
        assertFalse(EInkMode.OFF.resolve(deviceLooksLikeEInk = true))
    }
}
