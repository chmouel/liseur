package com.chmouel.liseur.reader.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSpeedEstimatorTest {

    private val minute = 60_000L

    @Test
    fun `no estimate before a sample`() {
        val estimator = ReadingSpeedEstimator()
        assertNull(estimator.speed)
        assertTrue(!estimator.isMeasured)
        assertEquals(ReadingSpeedEstimator.DEFAULT_SPEED, estimator.effectiveSpeed, 0.001)
    }

    @Test
    fun `measures a steady pace`() {
        val estimator = ReadingSpeedEstimator()
        estimator.record(position = 0.0, atMillis = 0)
        estimator.record(position = 2.0, atMillis = minute)
        assertEquals(2.0, estimator.speed!!, 0.001)
        assertTrue(estimator.isMeasured)
    }

    @Test
    fun `blends new samples into the estimate`() {
        val estimator = ReadingSpeedEstimator()
        estimator.record(0.0, 0)
        estimator.record(2.0, minute)
        estimator.record(6.0, 2 * minute)
        // A faster stretch pulls the estimate up without overreacting.
        assertTrue(estimator.speed!! in 2.0..4.0)
    }

    @Test
    fun `ignores jumps, backtracking, flicks and long pauses`() {
        val estimator = ReadingSpeedEstimator()
        estimator.record(0.0, 0)
        estimator.record(500.0, minute) // jump through the book
        estimator.record(499.0, 2 * minute) // page back
        estimator.record(500.0, 2 * minute + 10) // flicked past a page
        estimator.record(502.0, 60 * minute) // left open on the table
        assertNull(estimator.speed)
    }

    @Test
    fun `starts from a saved speed`() {
        val estimator = ReadingSpeedEstimator(initialSpeed = 3.0)
        assertEquals(3.0, estimator.effectiveSpeed, 0.001)
        assertEquals(20, estimator.minutesFor(60.0))
    }

    @Test
    fun `ignores a nonsense saved speed`() {
        assertNull(ReadingSpeedEstimator(initialSpeed = 0.0).speed)
        assertNull(ReadingSpeedEstimator(initialSpeed = -2.0).speed)
    }

    @Test
    fun `forgetting the last position skips the next sample`() {
        val estimator = ReadingSpeedEstimator()
        estimator.record(0.0, 0)
        estimator.forgetLastPosition()
        estimator.record(2.0, minute)
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
