package com.chmouel.liseur.reader.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSpeedEstimatorTest {

    private val second = 1_000L
    private val minute = 60 * second

    private fun pace(
        secondsPerPosition: Double,
        samples: Int = 20,
        elapsedMs: Long = 10 * minute,
        evidence: Double = 10.0,
    ) = ReadingPace(secondsPerPosition, samples, elapsedMs, evidence)

    private fun ReadingSpeedEstimator.read(
        positions: Int,
        secondsPerPosition: Double,
        from: Long = 0,
    ) {
        var at = from
        var position = 0.0
        record(position, at)
        repeat(positions) {
            at += (secondsPerPosition * second).toLong()
            position += 1.0
            record(position, at)
        }
    }

    @Test
    fun `no measured estimate before anything is watched`() {
        val estimator = ReadingSpeedEstimator()

        assertNull(estimator.secondsPerPosition)
        assertTrue(!estimator.isMeasured)
        assertEquals(ReadingPace.DEFAULT_SECONDS_PER_POSITION, estimator.effectiveSecondsPerPosition, 0.0)
    }

    @Test
    fun `a reader with enough persisted evidence is trusted immediately`() {
        val estimator = ReadingSpeedEstimator(learned = pace(60.0))

        assertTrue(estimator.isMeasured)
        assertEquals(60, estimator.minutesFor(60.0))
    }

    @Test
    fun `warm up requires samples and actual reading time`() {
        val estimator = ReadingSpeedEstimator()
        estimator.read(positions = 5, secondsPerPosition = 20.0)
        assertTrue(!estimator.isMeasured)

        estimator.read(positions = 5, secondsPerPosition = 40.0, from = 10 * minute)
        assertTrue(estimator.isMeasured)
    }

    @Test
    fun `a handful of fast turns cannot collapse a long estimate`() {
        val estimator = ReadingSpeedEstimator(learned = pace(60.0))
        estimator.read(positions = 4, secondsPerPosition = 8.0)

        val minutes = estimator.minutesFor(220.0)

        assertTrue("estimate was $minutes minutes", minutes >= 150)
    }

    @Test
    fun `ordinary slow pages are retained`() {
        val estimator = ReadingSpeedEstimator(learned = pace(60.0))
        estimator.record(0.0, 0)

        val sample = estimator.record(1.0, 4 * minute)

        assertNotNull(sample)
        assertTrue(estimator.secondsPerPosition!! > 60.0)
    }

    @Test
    fun `a sustained pace change is followed`() {
        val estimator = ReadingSpeedEstimator(learned = pace(60.0))
        estimator.read(positions = 40, secondsPerPosition = 90.0)

        assertEquals(90.0, estimator.secondsPerPosition!!, 8.0)
    }

    @Test
    fun `fractional stable positions measure pages sharing an integer anchor`() {
        val estimator = ReadingSpeedEstimator()
        estimator.record(10.1, 0)

        val sample = estimator.record(10.6, 30 * second)

        assertNotNull(sample)
        assertEquals(60.0, sample!!.secondsPerPosition, 0.001)
    }

    @Test
    fun `jumps backtracking flicks and awake idle are rejected`() {
        val estimator = ReadingSpeedEstimator()
        estimator.record(0.0, 0)
        assertNull(estimator.record(4.0, minute))
        assertNull(estimator.record(3.0, 2 * minute))
        assertNull(estimator.record(4.0, 2 * minute + 7 * second))
        assertNull(estimator.record(5.0, 13 * minute))
        assertNull(estimator.secondsPerPosition)
    }

    @Test
    fun `timing gates are inclusive where documented`() {
        val tooQuick = ReadingSpeedEstimator()
        tooQuick.record(0.0, 0)
        assertNull(tooQuick.record(1.0, 8 * second - 1))

        val quickEnough = ReadingSpeedEstimator()
        quickEnough.record(0.0, 0)
        assertNotNull(quickEnough.record(1.0, 8 * second))

        val slowEnough = ReadingSpeedEstimator()
        slowEnough.record(0.0, 0)
        assertNotNull(slowEnough.record(1.0, 10 * minute))

        val tooSlow = ReadingSpeedEstimator()
        tooSlow.record(0.0, 0)
        assertNull(tooSlow.record(1.0, 10 * minute + 1))
    }

    @Test
    fun `book and global priors blend by bounded evidence`() {
        val estimator = ReadingSpeedEstimator(
            learned = pace(60.0, evidence = 20.0),
            bookPace = pace(120.0, evidence = 5.0),
        )

        assertEquals(90.0, estimator.secondsPerPosition!!, 0.001)
    }

    @Test
    fun `invalid persisted pace is ignored`() {
        assertNull(ReadingSpeedEstimator(bookPace = ReadingPace.Unknown).secondsPerPosition)
        assertEquals(
            ReadingPace.Unknown,
            ReadingPace.of(Double.NaN, 4, minute, 4.0),
        )
        assertEquals(
            ReadingPace.Unknown,
            ReadingPace.of(60.0, 4, 0, 4.0),
        )
    }

    @Test
    fun `forgetting the baseline skips the next transition`() {
        val estimator = ReadingSpeedEstimator()
        estimator.record(0.0, 0)
        estimator.forgetLastPosition()

        assertNull(estimator.record(1.0, minute))
        assertNull(estimator.secondsPerPosition)
    }

    @Test
    fun `remaining time rounds upward`() {
        val estimator = ReadingSpeedEstimator(learned = pace(40.0))

        assertEquals(0, estimator.minutesFor(0.0))
        assertEquals(1, estimator.minutesFor(0.1))
        assertEquals(20, ReadingSpeedEstimator().minutesFor(30.0))
    }
}

class ReadingPaceTest {

    private val minute = 60_000L

    private fun sample(
        seconds: Double,
        positions: Double = 1.0,
        elapsedMs: Long = (seconds * 1_000).toLong(),
    ) = PaceSample(seconds, positions, elapsedMs)

    @Test
    fun `persisted v2 pace round trips`() {
        val pace = ReadingPace(55.0, 17, 12 * minute, 8.5)
        assertEquals(
            pace,
            ReadingPace.of(
                pace.secondsPerPosition,
                pace.samples,
                pace.elapsedMs,
                pace.evidence,
            ),
        )
    }

    @Test
    fun `the first valid sample is taken as it stands`() {
        assertEquals(40.0, ReadingPace.Unknown.after(sample(40.0)).secondsPerPosition, 0.0)
    }

    @Test
    fun `fast outlier is winsorized symmetrically in time domain`() {
        val pace = ReadingPace(60.0, 10, 10 * minute, 10.0)
            .after(sample(1.0))

        assertTrue(pace.secondsPerPosition > 50.0)
    }

    @Test
    fun `invalid samples change nothing`() {
        val pace = ReadingPace(60.0, 10, 10 * minute, 10.0)

        assertEquals(pace, pace.after(sample(Double.NaN)))
        assertEquals(pace, pace.after(sample(-1.0)))
        assertEquals(pace, pace.after(sample(60.0, positions = 0.0)))
    }
}
