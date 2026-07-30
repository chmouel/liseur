package com.chmouel.liseur.reader.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSpeedEstimatorTest {

    private val minute = 60_000L

    /** Walks the reader forward one page every [minutes] minutes. */
    private fun ReadingSpeedEstimator.read(pages: Int, minutes: Double, from: Long = 0) {
        var at = from
        var position = 0.0
        record(position, at)
        repeat(pages) {
            at += (minutes * minute).toLong()
            position += 1.0
            record(position, at)
        }
    }

    @Test
    fun `no estimate before anything is watched`() {
        val estimator = ReadingSpeedEstimator()
        assertNull(estimator.speed)
        assertTrue(!estimator.isMeasured)
        assertEquals(ReadingSpeedEstimator.DEFAULT_SPEED, estimator.effectiveSpeed, 0.001)
    }

    @Test
    fun `a reader the app already knows is trusted straight away`() {
        val estimator = ReadingSpeedEstimator(learned = ReadingPace(speed = 3.0, samples = 40))
        assertTrue(estimator.isMeasured)
        assertEquals(3.0, estimator.effectiveSpeed, 0.001)
        assertEquals(20, estimator.minutesFor(60.0))
    }

    @Test
    fun `a new reader is not told an estimate until enough pages are watched`() {
        val estimator = ReadingSpeedEstimator()
        repeat(ReadingPace.WARM_UP_SAMPLES - 1) { turn ->
            estimator.record(turn.toDouble(), turn * minute)
            estimator.record(turn + 1.0, (turn + 1) * minute)
            assertTrue("measured after ${turn + 1} pages", !estimator.isMeasured)
        }
        estimator.record(0.0, 100 * minute)
        estimator.record(1.0, 101 * minute)
        assertTrue(estimator.isMeasured)
    }

    @Test
    fun `one strange page cannot throw the estimate`() {
        val estimator = ReadingSpeedEstimator(learned = ReadingPace(speed = 2.0, samples = 40))
        // Thirty positions in a minute: a chapter skimmed, not read.
        estimator.record(0.0, 0)
        estimator.record(4.0, minute / 2)
        // Eight positions a minute claimed, clamped to three times the
        // known pace and then weighted, so it lands nowhere near.
        assertTrue("speed was ${estimator.speed}", estimator.speed!! <= 3.0)
    }

    @Test
    fun `a reader who really has slowed down is followed`() {
        val estimator = ReadingSpeedEstimator(learned = ReadingPace(speed = 3.0, samples = 40))
        // A dense book at a steady 0.8 positions per minute, far below
        // the clamp band, so every single page is an outlier.
        estimator.read(pages = 60, minutes = 1 / 0.8)
        assertEquals(0.8, estimator.speed!!, 0.1)
    }

    @Test
    fun `settles on a steady pace within a couple of chapters`() {
        val estimator = ReadingSpeedEstimator(learned = ReadingPace(speed = 1.5, samples = 3))
        var pages = 0
        var at = 0L
        var position = 0.0
        estimator.record(position, at)
        while (pages < 40) {
            at += (minute / 0.75).toLong()
            position += 1.0
            estimator.record(position, at)
            pages++
            if (kotlin.math.abs(estimator.speed!! - 0.75) <= 0.075) break
        }
        assertTrue("took $pages pages", pages <= 22)
    }

    @Test
    fun `ignores jumps, backtracking, flicks and long pauses`() {
        val estimator = ReadingSpeedEstimator()
        estimator.record(0.0, 0)
        assertNull(estimator.record(500.0, minute)) // jump through the book
        assertNull(estimator.record(499.0, 2 * minute)) // page back
        assertNull(estimator.record(500.0, 2 * minute + 10)) // flicked past
        assertNull(estimator.record(502.0, 60 * minute)) // left on the table
        assertNull(estimator.speed)
    }

    @Test
    fun `the gates fall exactly where they say they do`() {
        val fiveSeconds = 5_000L
        val twoMinutes = 2 * minute

        val tooQuick = ReadingSpeedEstimator()
        tooQuick.record(0.0, 0)
        assertNull(tooQuick.record(1.0, fiveSeconds))

        val quickEnough = ReadingSpeedEstimator()
        quickEnough.record(0.0, 0)
        assertNotNull(quickEnough.record(1.0, fiveSeconds + 100))

        val tooSlow = ReadingSpeedEstimator()
        tooSlow.record(0.0, 0)
        assertNull(tooSlow.record(1.0, twoMinutes))

        val slowEnough = ReadingSpeedEstimator()
        slowEnough.record(0.0, 0)
        assertNotNull(slowEnough.record(1.0, twoMinutes - 100))
    }

    @Test
    fun `this book's own pace is preferred to the general one`() {
        val estimator = ReadingSpeedEstimator(
            learned = ReadingPace(speed = 3.0, samples = 40),
            bookSpeed = 0.5,
        )
        assertEquals(0.5, estimator.effectiveSpeed, 0.001)
        // Still measured: the reader is known even if this book is slow.
        assertTrue(estimator.isMeasured)
    }

    @Test
    fun `ignores a nonsense stored speed`() {
        assertNull(ReadingSpeedEstimator(bookSpeed = 0.0).speed)
        assertNull(ReadingSpeedEstimator(bookSpeed = -2.0).speed)
        assertNull(ReadingSpeedEstimator(bookSpeed = Double.NaN).speed)
        assertNull(ReadingSpeedEstimator(bookSpeed = Double.POSITIVE_INFINITY).speed)
    }

    @Test
    fun `forgetting the last position skips the next sample`() {
        val estimator = ReadingSpeedEstimator()
        estimator.record(0.0, 0)
        estimator.forgetLastPosition()
        assertNull(estimator.record(2.0, minute))
        assertNull(estimator.speed)
    }

    @Test
    fun `estimates minutes from the default pace`() {
        val estimator = ReadingSpeedEstimator()
        assertEquals(0, estimator.minutesFor(0.0))
        assertEquals(0, estimator.minutesFor(-5.0))
        assertEquals(20, estimator.minutesFor(30.0))
    }
}

class ReadingPaceTest {

    @Test
    fun `a stored pace comes back as it went in`() {
        val pace = ReadingPace(speed = 1.25, samples = 17)
        assertEquals(pace, ReadingPace.of(pace.speed, pace.samples))
    }

    @Test
    fun `refuses a pace that cannot be one`() {
        assertEquals(ReadingPace.Unknown, ReadingPace.of(null, 4))
        assertEquals(ReadingPace.Unknown, ReadingPace.of(2.0, null))
        assertEquals(ReadingPace.Unknown, ReadingPace.of(2.0, 0))
        assertEquals(ReadingPace.Unknown, ReadingPace.of(0.0, 4))
        assertEquals(ReadingPace.Unknown, ReadingPace.of(-1.0, 4))
        assertEquals(ReadingPace.Unknown, ReadingPace.of(Double.NaN, 4))
        assertEquals(ReadingPace.Unknown, ReadingPace.of(Double.POSITIVE_INFINITY, 4))
    }

    @Test
    fun `the first sample is taken as it stands`() {
        assertEquals(ReadingPace(2.0, 1), ReadingPace.Unknown.after(2.0))
    }

    @Test
    fun `a nonsense sample changes nothing`() {
        val pace = ReadingPace(2.0, 10)
        assertEquals(pace, pace.after(0.0))
        assertEquals(pace, pace.after(-1.0))
        assertEquals(pace, pace.after(Double.NaN))
    }

    @Test
    fun `an extreme sample is pulled in rather than thrown away`() {
        val pace = ReadingPace(2.0, 10).after(100.0)
        // Clamped to 6.0, then weighted at a tenth.
        assertEquals(2.0 * 0.9 + 6.0 * 0.1, pace.speed, 0.001)
        assertEquals(11, pace.samples)
    }

    @Test
    fun `the count stops climbing rather than growing without bound`() {
        var pace = ReadingPace(2.0, ReadingPace.MAX_SAMPLES)
        pace = pace.after(2.0)
        assertEquals(ReadingPace.MAX_SAMPLES, pace.samples)
    }
}
