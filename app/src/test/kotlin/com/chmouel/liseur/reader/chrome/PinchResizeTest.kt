package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.data.settings.ReaderPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchResizeTest {

    @Test
    fun `the ends of the range are positions`() {
        assertEquals(ReaderPrefs.MIN_FONT_SIZE, PinchResize.sizeAt(0), 1e-9)
        assertEquals(
            ReaderPrefs.MAX_FONT_SIZE,
            PinchResize.sizeAt(PinchResize.POSITIONS - 1),
            1e-9,
        )
    }

    @Test
    fun `every position round-trips through its size`() {
        for (i in 0 until PinchResize.POSITIONS) {
            assertEquals(i, PinchResize.positionOf(PinchResize.sizeAt(i)))
        }
    }

    /**
     * The gesture and the Size slider have to offer the *same* sizes, or
     * a book resized by one and then nudged by the other jumps. A
     * `Slider` counts the notches between its ends, so its `steps` is two
     * fewer than the number of sizes it can actually be left on.
     */
    @Test
    fun `the pinch lands on the sizes the slider offers`() {
        assertEquals(ReaderPrefs.FONT_SIZE_POSITIONS, PinchResize.POSITIONS)
        assertEquals(PinchResize.POSITIONS - 2, ReaderPrefs.FONT_SIZE_SLIDER_STEPS)
    }

    /**
     * Fingers that spread and then come home again have asked for
     * nothing. The answer has to be `null` rather than the last target,
     * because the caller assigns it: a `null` that was merged instead of
     * assigned would commit whatever the gesture passed through on the
     * way out.
     */
    @Test
    fun `coming back to the starting span asks for nothing`() {
        val start = PinchResize.sizeAt(6)
        assertTrue(PinchResize.targetFor(start, 200f, 320f) != null)
        assertNull(PinchResize.targetFor(start, 200f, 200f))
        assertNull(PinchResize.targetFor(start, 200f, 206f))
    }

    @Test
    fun `a size between two positions snaps to the nearer`() {
        val below = PinchResize.sizeAt(4)
        val above = PinchResize.sizeAt(5)
        assertEquals(below, PinchResize.snap(below + (above - below) * 0.2), 1e-9)
        assertEquals(above, PinchResize.snap(below + (above - below) * 0.8), 1e-9)
    }

    @Test
    fun `a snapped size is always one the slider offers`() {
        var s = ReaderPrefs.MIN_FONT_SIZE - 1.0
        while (s < ReaderPrefs.MAX_FONT_SIZE + 1.0) {
            val snapped = PinchResize.snap(s)
            assertEquals(snapped, PinchResize.sizeAt(PinchResize.positionOf(snapped)), 1e-9)
            s += 0.017
        }
    }

    @Test
    fun `spreading the fingers makes the text bigger`() {
        val target = PinchResize.targetFor(startSize = 1.0, startSpan = 200f, currentSpan = 300f)
        assertTrue(target != null && target > 1.0)
    }

    @Test
    fun `pinching the fingers together makes the text smaller`() {
        val target = PinchResize.targetFor(startSize = 1.0, startSpan = 300f, currentSpan = 200f)
        assertTrue(target != null && target < 1.0)
    }

    @Test
    fun `a rest inside the dead zone lands on nothing`() {
        val nudge = 1f + (PinchResize.DEAD_ZONE / 2).toFloat()
        assertNull(PinchResize.targetFor(1.0, 300f, 300f * nudge))
        assertNull(PinchResize.targetFor(1.0, 300f, 300f / nudge))
    }

    @Test
    fun `it never lands outside the range the slider offers`() {
        assertEquals(
            ReaderPrefs.MAX_FONT_SIZE,
            PinchResize.targetFor(2.0, 100f, 4000f)!!,
            1e-9,
        )
        assertEquals(
            ReaderPrefs.MIN_FONT_SIZE,
            PinchResize.targetFor(0.8, 4000f, 100f)!!,
            1e-9,
        )
    }

    @Test
    fun `a span too small to divide by lands on nothing`() {
        assertNull(PinchResize.targetFor(1.0, 4f, 400f))
        assertNull(PinchResize.targetFor(1.0, 300f, 0f))
    }

    @Test
    fun `bringing the fingers back where they started leaves the size alone`() {
        // The ratio applies to the size the gesture began with, not to
        // whatever the last frame produced, so the value cannot walk away:
        // an excursion out and back lands on nothing at all.
        val start = PinchResize.sizeAt(6)
        assertEquals(PinchResize.sizeAt(PinchResize.POSITIONS - 1), PinchResize.targetFor(start, 200f, 900f))
        assertEquals(PinchResize.sizeAt(0), PinchResize.targetFor(start, 200f, 40f))
        assertNull(PinchResize.targetFor(start, 200f, 200f))
    }

    @Test
    fun `the gap between two fingers is the distance between them`() {
        assertEquals(5f, PinchResize.spanOf(0f, 0f, 3f, 4f), 1e-4f)
        assertEquals(0f, PinchResize.spanOf(7f, 9f, 7f, 9f), 1e-4f)
    }

    @Test
    fun `resting two fingers has not moved them`() {
        assertFalse(PinchResize.moved(startSpan = 300f, currentSpan = 300f))
        assertFalse(PinchResize.moved(startSpan = 300f, currentSpan = 310f))
    }

    @Test
    fun `a real pinch has`() {
        assertTrue(PinchResize.moved(startSpan = 300f, currentSpan = 400f))
        assertTrue(PinchResize.moved(startSpan = 400f, currentSpan = 300f))
    }

    @Test
    fun `fingers too close together to divide by have not moved`() {
        assertFalse(PinchResize.moved(startSpan = 1f, currentSpan = 500f))
        assertFalse(PinchResize.moved(startSpan = 300f, currentSpan = 0f))
    }
}
