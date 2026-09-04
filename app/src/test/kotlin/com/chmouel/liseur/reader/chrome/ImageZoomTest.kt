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

    /**
     * A picture at fit is clamped back to centre after every frame, so
     * the travel that dismisses it cannot be read off its own offset:
     * one frame of a drag is never 96dp.
     */
    @Test
    fun `a downward drag accumulates across frames`() {
        var travelled = 0f
        repeat(20) { travelled = ImageZoom.dragTravel(travelled, 15f) }
        assertEquals(300f, travelled, 0.01f)
    }

    @Test
    fun `travelling back up takes the drag off again`() {
        var travelled = ImageZoom.dragTravel(0f, 100f)
        travelled = ImageZoom.dragTravel(travelled, -40f)
        assertEquals(60f, travelled, 0.01f)
    }

    @Test
    fun `a drag that starts upwards is not a debt to pay back`() {
        var travelled = 0f
        repeat(5) { travelled = ImageZoom.dragTravel(travelled, -50f) }
        assertEquals(0f, travelled, 0.01f)
        travelled = ImageZoom.dragTravel(travelled, 30f)
        assertEquals(30f, travelled, 0.01f)
    }
}

/**
 * The gesture that is counted rather than drawn, on electronic paper.
 *
 * The arithmetic tested here is the whole of what a pinch does on such a
 * panel: nothing is written to the screen until the last of it, so a
 * mistake in the accumulation is a picture that jumps somewhere the
 * fingers never went.
 */
class PendingTransformTest {

    private fun start(scale: Float = ImageZoom.MIN_SCALE) =
        PendingTransform(scale = scale, offsetX = 0f, offsetY = 0f)

    @Test
    fun `a pinch out and back again commits nothing`() {
        val done = start(2f).fold(1.5f, 0f, 0f).fold(1f / 1.5f, 0f, 0f)
        assertEquals(2f, done.scale, 1e-4f)
    }

    @Test
    fun `pan accumulates across the frames of one gesture`() {
        var gesture = start(3f)
        repeat(10) { gesture = gesture.fold(1f, 4f, -2f) }
        assertEquals(40f, gesture.offsetX, 0.01f)
        assertEquals(-20f, gesture.offsetY, 0.01f)
    }

    @Test
    fun `the committed scale is held inside the same bounds as the drawn one`() {
        var gesture = start()
        repeat(20) { gesture = gesture.fold(2f, 0f, 0f) }
        assertEquals(ImageZoom.MAX_SCALE, gesture.scale, 1e-4f)
        repeat(40) { gesture = gesture.fold(0.5f, 0f, 0f) }
        assertEquals(ImageZoom.MIN_SCALE, gesture.scale, 1e-4f)
    }

    @Test
    fun `a long drag down on a fitted picture asks to be put away`() {
        var gesture = start()
        repeat(10) { gesture = gesture.fold(1f, 0f, 20f) }
        assertTrue(gesture.dismisses(96f))
    }

    @Test
    fun `a hand that wanders down and comes back has asked for nothing`() {
        var gesture = start()
        repeat(10) { gesture = gesture.fold(1f, 0f, 20f) }
        repeat(10) { gesture = gesture.fold(1f, 0f, -20f) }
        assertFalse(gesture.dismisses(96f))
    }

    @Test
    fun `dragging a zoomed-in picture reads the corner rather than dismissing`() {
        var gesture = start(3f)
        repeat(10) { gesture = gesture.fold(1f, 0f, 20f) }
        assertEquals(0f, gesture.travelDown, 0.01f)
        assertFalse(gesture.dismisses(96f))
    }

    @Test
    fun `zooming in mid-drag takes the travel off again`() {
        var gesture = start()
        repeat(10) { gesture = gesture.fold(1f, 0f, 20f) }
        gesture = gesture.fold(2f, 0f, 20f)
        assertEquals(0f, gesture.travelDown, 0.01f)
    }
}
