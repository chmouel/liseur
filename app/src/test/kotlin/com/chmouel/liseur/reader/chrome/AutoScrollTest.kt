package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScrollTickerTest {

    private val ticker = AutoScrollTicker()

    private fun millis(value: Long) = value * 1_000_000L

    /** Sixty frames of a second, without rounding the frames themselves. */
    private fun frameNanos(frame: Int) = frame * 1_000_000_000L / 60

    @Test
    fun `the first step only starts the clock`() {
        assertEquals(0, ticker.step(millis(0), pixelsPerSecond = 100.0))
    }

    @Test
    fun `a whole second at a hundred pixels a second is a hundred pixels`() {
        ticker.step(frameNanos(0), 100.0)
        // Paid out in frames, because that is how it will be asked.
        var moved = 0
        for (frame in 1..60) moved += ticker.step(frameNanos(frame), 100.0)
        assertEquals(100.0, moved.toDouble(), 1.0)
    }

    @Test
    fun `a pace slower than a pixel a frame still moves the page`() {
        ticker.step(frameNanos(0), 6.0)
        var moved = 0
        for (frame in 1..60) moved += ticker.step(frameNanos(frame), 6.0)
        assertEquals(6.0, moved.toDouble(), 1.0)
    }

    @Test
    fun `the fraction is carried rather than rounded away`() {
        ticker.step(millis(0), 10.0)
        // Each of these is worth a third of a pixel. Rounded on its own
        // every one would be nothing at all.
        val first = ticker.step(millis(33), 10.0)
        val second = ticker.step(millis(66), 10.0)
        val third = ticker.step(millis(100), 10.0)
        assertEquals(0, first)
        assertEquals(0, second)
        assertEquals(1, third)
    }

    @Test
    fun `a frame that is not a whole millisecond is not rounded away`() {
        // Sixty frames a second are 16 and two thirds of a millisecond
        // each. Counting them in whole milliseconds loses that third
        // sixty times over, and the page quietly runs four percent slow.
        ticker.step(0L, 100.0)
        var moved = 0
        for (frame in 1..600) moved += ticker.step(frameNanos(frame), 100.0)
        assertEquals(1_000.0, moved.toDouble(), 1.0)
    }

    @Test
    fun `a long stall is clamped instead of jumping the page`() {
        ticker.step(millis(0), 100.0)
        // Ten seconds away would otherwise be a thousand pixels at once.
        assertEquals(25, ticker.step(millis(10_000), 100.0))
    }

    @Test
    fun `a reset drops the time that passed while the page was still`() {
        ticker.step(millis(0), 100.0)
        ticker.reset()
        assertEquals(0, ticker.step(millis(5_000), 100.0))
        assertEquals(1, ticker.step(millis(5_010), 100.0))
    }

    @Test
    fun `a reset also drops the fraction owed`() {
        ticker.step(millis(0), 10.0)
        ticker.step(millis(90), 10.0)
        ticker.reset()
        ticker.step(millis(200), 10.0)
        assertEquals(0, ticker.step(millis(210), 10.0))
    }

    @Test
    fun `a clock that goes backwards is not elapsed time`() {
        ticker.step(millis(1_000), 100.0)
        assertEquals(0, ticker.step(millis(900), 100.0))
    }
}

class AutoScrollSpeedTest {

    @Test
    fun `the ends of the slider are the documented paces`() {
        assertEquals(
            AutoScrollSpeed.SLOWEST_DP_PER_SECOND,
            AutoScrollSpeed.dpPerSecond(AutoScrollSpeed.MIN_STEP.toFloat()),
            0.001,
        )
        assertEquals(
            AutoScrollSpeed.FASTEST_DP_PER_SECOND,
            AutoScrollSpeed.dpPerSecond(AutoScrollSpeed.MAX_STEP.toFloat()),
            0.001,
        )
    }

    @Test
    fun `every notch is faster than the one before it`() {
        val paces = (AutoScrollSpeed.MIN_STEP..AutoScrollSpeed.MAX_STEP)
            .map { AutoScrollSpeed.dpPerSecond(it.toFloat()) }
        assertEquals(paces.sorted(), paces)
        assertEquals(paces.distinct().size, paces.size)
    }

    @Test
    fun `a step off the end of the slider is held at the end`() {
        assertEquals(
            AutoScrollSpeed.dpPerSecond(AutoScrollSpeed.MIN_STEP.toFloat()),
            AutoScrollSpeed.dpPerSecond(-4f),
            0.001,
        )
        assertEquals(
            AutoScrollSpeed.dpPerSecond(AutoScrollSpeed.MAX_STEP.toFloat()),
            AutoScrollSpeed.dpPerSecond(99f),
            0.001,
        )
    }

    @Test
    fun `larger text travels proportionally further`() {
        val normal = AutoScrollSpeed.dpPerSecond(5f, fontSize = 1.0)
        assertEquals(normal * 1.5, AutoScrollSpeed.dpPerSecond(5f, fontSize = 1.5), 0.001)
        assertEquals(normal * 0.6, AutoScrollSpeed.dpPerSecond(5f, fontSize = 0.6), 0.001)
    }

    @Test
    fun `the slow end is spread more finely than the fast end`() {
        val slowGap = AutoScrollSpeed.dpPerSecond(2f) - AutoScrollSpeed.dpPerSecond(1f)
        val fastGap = AutoScrollSpeed.dpPerSecond(10f) - AutoScrollSpeed.dpPerSecond(9f)
        assertTrue("a notch at the slow end should be a smaller change", slowGap < fastGap)
    }

    @Test
    fun `the default is a notch on the slider`() {
        assertEquals(AutoScrollSpeed.DEFAULT_STEP, AutoScrollSpeed.snap(AutoScrollSpeed.DEFAULT_STEP))
    }

    @Test
    fun `snapping lands on a whole notch inside the slider`() {
        assertEquals(3f, AutoScrollSpeed.snap(3.4f))
        assertEquals(4f, AutoScrollSpeed.snap(3.6f))
        assertEquals(AutoScrollSpeed.MIN_STEP.toFloat(), AutoScrollSpeed.snap(0f))
        assertEquals(AutoScrollSpeed.MAX_STEP.toFloat(), AutoScrollSpeed.snap(42f))
    }
}
