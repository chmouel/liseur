package com.chmouel.liseur.reader.chrome

import android.content.pm.ActivityInfo
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDisplayModeTest {

    private val eps = 1e-4f

    @Test
    fun `null brightness follows the system without dimming`() {
        val resolved = resolveScreenBrightness(brightness = null)
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, resolved.windowBrightness, eps)
        assertEquals(0f, resolved.dimAlpha, eps)
    }

    @Test
    fun `zero brightness reaches zero window brightness and maximum ultra-dim alpha`() {
        val resolved = resolveScreenBrightness(brightness = 0f)
        assertEquals(0f, resolved.windowBrightness, eps)
        assertEquals(MAX_ULTRA_DIM_ALPHA, resolved.dimAlpha, eps)
    }

    @Test
    fun `midpoint below threshold interpolates both window brightness and dim alpha`() {
        val resolved = resolveScreenBrightness(brightness = 0.075f)
        assertEquals(0.005f, resolved.windowBrightness, eps)
        assertEquals(0.325f, resolved.dimAlpha, eps)
    }

    @Test
    fun `threshold reaches one percent window brightness with zero dim alpha`() {
        val resolved = resolveScreenBrightness(brightness = ULTRA_DIM_THRESHOLD)
        assertEquals(0.01f, resolved.windowBrightness, eps)
        assertEquals(0f, resolved.dimAlpha, eps)
    }

    @Test
    fun `values above threshold scale window brightness linearly with zero dim alpha`() {
        val half = resolveScreenBrightness(brightness = 0.5f)
        val expectedHalf = 0.01f + ((0.5f - 0.15f) / 0.85f) * 0.99f
        assertEquals(expectedHalf, half.windowBrightness, eps)
        assertEquals(0f, half.dimAlpha, eps)

        val full = resolveScreenBrightness(brightness = 1.0f)
        assertEquals(1.0f, full.windowBrightness, eps)
        assertEquals(0f, full.dimAlpha, eps)
    }

    @Test
    fun `out-of-range brightness is clamped`() {
        val below = resolveScreenBrightness(brightness = -0.5f)
        assertEquals(0f, below.windowBrightness, eps)
        assertEquals(MAX_ULTRA_DIM_ALPHA, below.dimAlpha, eps)

        val above = resolveScreenBrightness(brightness = 1.5f)
        assertEquals(1.0f, above.windowBrightness, eps)
        assertEquals(0f, above.dimAlpha, eps)
    }

    @Test
    fun `e-ink never applies ultra-dim overlay and passes brightness straight through`() {
        val system = resolveScreenBrightness(brightness = null, eInk = true)
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, system.windowBrightness, eps)
        assertEquals(0f, system.dimAlpha, eps)

        val zero = resolveScreenBrightness(brightness = 0f, eInk = true)
        assertEquals(0f, zero.windowBrightness, eps)
        assertEquals(0f, zero.dimAlpha, eps)

        val low = resolveScreenBrightness(brightness = 0.075f, eInk = true)
        assertEquals(0.075f, low.windowBrightness, eps)
        assertEquals(0f, low.dimAlpha, eps)

        val high = resolveScreenBrightness(brightness = 0.5f, eInk = true)
        assertEquals(0.5f, high.windowBrightness, eps)
        assertEquals(0f, high.dimAlpha, eps)
    }

    @Test
    fun `window brightness increases and dim alpha decreases monotonically across sweep`() {
        var prevWindow = -1f
        var prevDim = Float.MAX_VALUE
        for (step in 0..100) {
            val brightness = step / 100f
            val resolved = resolveScreenBrightness(brightness)
            assertTrue(
                "windowBrightness at $brightness (${resolved.windowBrightness}) < $prevWindow",
                resolved.windowBrightness >= prevWindow - eps,
            )
            assertTrue(
                "dimAlpha at $brightness (${resolved.dimAlpha}) > $prevDim",
                resolved.dimAlpha <= prevDim + eps,
            )
            prevWindow = resolved.windowBrightness
            prevDim = resolved.dimAlpha
        }
    }

    @Test
    fun `desiredHdrHeadroom caps to SDR only when ultra-dim is active and image viewer is closed`() {
        assertEquals(1.0f, desiredHdrHeadroom(dimAlpha = 0.25f, imageViewerOpen = false), eps)
        assertEquals(0.0f, desiredHdrHeadroom(dimAlpha = 0.25f, imageViewerOpen = true), eps)
        assertEquals(0.0f, desiredHdrHeadroom(dimAlpha = 0f, imageViewerOpen = false), eps)
        assertEquals(0.0f, desiredHdrHeadroom(dimAlpha = 0f, imageViewerOpen = true), eps)
    }

    @Test
    fun `resolveWindowColorMode requests wide color gamut only when viewer is open on wide-gamut panel`() {
        assertEquals(
            ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT,
            resolveWindowColorMode(imageViewerOpen = true, isWideColorGamutDisplay = true),
        )
        assertEquals(
            ActivityInfo.COLOR_MODE_DEFAULT,
            resolveWindowColorMode(imageViewerOpen = true, isWideColorGamutDisplay = false),
        )
        assertEquals(
            ActivityInfo.COLOR_MODE_DEFAULT,
            resolveWindowColorMode(imageViewerOpen = false, isWideColorGamutDisplay = true),
        )
        assertEquals(
            ActivityInfo.COLOR_MODE_DEFAULT,
            resolveWindowColorMode(imageViewerOpen = false, isWideColorGamutDisplay = false),
        )
    }
}
