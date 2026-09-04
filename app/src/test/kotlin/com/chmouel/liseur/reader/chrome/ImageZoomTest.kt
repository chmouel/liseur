package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageZoomTest {

    @Test
    fun `the viewer opens at fit and cannot go further out`() {
        assertEquals(ImageZoom.MIN_SCALE, ImageZoom.clampScale(0.1f), 1e-6f)
        assertTrue(ImageZoom.atFit(ImageZoom.MIN_SCALE))
        assertFalse(ImageZoom.atFit(ImageZoom.DOUBLE_TAP_SCALE))
    }

    @Test
    fun `it cannot be zoomed past its ceiling`() {
        assertEquals(ImageZoom.MAX_SCALE, ImageZoom.clampScale(1000f), 1e-6f)
    }

    @Test
    fun `a picture that does not fill the screen cannot be dragged off it`() {
        // Drawn narrower than the viewport: there is nothing to pan to,
        // and letting it move would only drag the scrim into view.
        assertEquals(0f, ImageZoom.maxPan(viewport = 1080f, drawn = 800f), 1e-6f)
        assertEquals(0f, ImageZoom.clampPan(-500f, viewport = 1080f, drawn = 800f), 1e-6f)
    }

    @Test
    fun `a picture wider than the screen pans as far as its own edges`() {
        assertEquals(460f, ImageZoom.maxPan(viewport = 1080f, drawn = 2000f), 1e-6f)
        assertEquals(460f, ImageZoom.clampPan(9000f, viewport = 1080f, drawn = 2000f), 1e-6f)
        assertEquals(-460f, ImageZoom.clampPan(-9000f, viewport = 1080f, drawn = 2000f), 1e-6f)
        assertEquals(100f, ImageZoom.clampPan(100f, viewport = 1080f, drawn = 2000f), 1e-6f)
    }

    @Test
    fun `fitting a wide picture fills the width and letterboxes the height`() {
        val (w, h) = ImageZoom.fitted(2000f, 1000f, viewW = 1000f, viewH = 1000f)
        assertEquals(1000f, w, 1e-6f)
        assertEquals(500f, h, 1e-6f)
    }

    @Test
    fun `fitting a tall picture fills the height`() {
        val (w, h) = ImageZoom.fitted(500f, 2000f, viewW = 1000f, viewH = 1000f)
        assertEquals(250f, w, 1e-6f)
        assertEquals(1000f, h, 1e-6f)
    }

    @Test
    fun `a picture smaller than the screen is still drawn to the screen`() {
        val (w, h) = ImageZoom.fitted(100f, 100f, viewW = 1000f, viewH = 800f)
        assertEquals(800f, w, 1e-6f)
        assertEquals(800f, h, 1e-6f)
    }

    @Test
    fun `an unmeasured picture falls back to the viewport`() {
        assertEquals(1000f to 800f, ImageZoom.fitted(0f, 0f, 1000f, 800f))
        assertEquals(0f to 0f, ImageZoom.fitted(100f, 100f, 0f, 0f))
    }
}
