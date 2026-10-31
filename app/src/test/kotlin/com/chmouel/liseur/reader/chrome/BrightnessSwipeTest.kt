package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.data.settings.EdgeBrightness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessSwipeTest {

    private val width = 1080f
    private val height = 2400f
    private val density = 2.625f

    private fun strip(edge: EdgeBrightness, x: Float, y: Float = height / 2) =
        BrightnessSwipe.inStrip(edge, x, y, width, height, density, topExcluded = 100f, bottomExcluded = 150f)

    @Test
    fun `the strip is only on the chosen edge`() {
        assertTrue(strip(EdgeBrightness.LEFT, 10f))
        assertFalse(strip(EdgeBrightness.LEFT, width - 10f))
        assertTrue(strip(EdgeBrightness.RIGHT, width - 10f))
        assertFalse(strip(EdgeBrightness.RIGHT, 10f))
        assertFalse(strip(EdgeBrightness.OFF, 10f))
        assertFalse(strip(EdgeBrightness.OFF, width - 10f))
    }

    @Test
    fun `the strip is capped in dp on a wide page`() {
        // A phone: 10% of 1080px is 108px, inside the 32-56dp bounds.
        // A wide page: 10% would be 240px, so the 56dp cap applies.
        assertTrue(strip(EdgeBrightness.LEFT, 100f))
        assertFalse(strip(EdgeBrightness.LEFT, 120f))
        val cap = BrightnessSwipe.MAX_STRIP_DP * density
        assertTrue(BrightnessSwipe.inStrip(EdgeBrightness.LEFT, cap - 1f, 500f, 2400f, 1000f, density))
        assertFalse(BrightnessSwipe.inStrip(EdgeBrightness.LEFT, cap + 1f, 500f, 2400f, 1000f, density))
    }

    @Test
    fun `the system gesture bands are left to the system`() {
        assertFalse(strip(EdgeBrightness.LEFT, 10f, y = 50f))
        assertFalse(strip(EdgeBrightness.LEFT, 10f, y = height - 50f))
        assertTrue(strip(EdgeBrightness.LEFT, 10f, y = 150f))
    }

    @Test
    fun `only a steep drag before the long press is a swipe`() {
        assertTrue(BrightnessSwipe.claims(dx = 5f, dy = -30f, elapsedMs = 100, longPressMs = 400))
        assertTrue(BrightnessSwipe.claims(dx = -5f, dy = 30f, elapsedMs = 100, longPressMs = 400))
        assertFalse(BrightnessSwipe.claims(dx = 30f, dy = 10f, elapsedMs = 100, longPressMs = 400))
        assertFalse(BrightnessSwipe.claims(dx = 20f, dy = 30f, elapsedMs = 100, longPressMs = 400))
        assertFalse(BrightnessSwipe.claims(dx = 0f, dy = 30f, elapsedMs = 400, longPressMs = 400))
    }

    @Test
    fun `up is brighter and the ends hold`() {
        val span = height * BrightnessSwipe.FULL_RANGE_OF_HEIGHT
        assertEquals(0.75f, BrightnessSwipe.valueFor(0.5f, -span / 4, height), 0f)
        assertEquals(0.25f, BrightnessSwipe.valueFor(0.5f, span / 4, height), 0f)
        assertEquals(1f, BrightnessSwipe.valueFor(0.5f, -span * 2, height), 0f)
        assertEquals(0f, BrightnessSwipe.valueFor(0.5f, span * 2, height), 0f)
    }

    @Test
    fun `the value follows the finger back to where it started`() {
        assertEquals(0.42f, BrightnessSwipe.valueFor(0.42f, 0f, height), 0f)
    }
}
