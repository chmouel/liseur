package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rules about what counts as reading.
 *
 * Foreground time is deliberately counted without an inactivity cap:
 * the clock cannot distinguish a book left open from a difficult page
 * read slowly. Android lifecycle pauses are the boundary instead.
 */
class ReadingSessionClockTest {

    private val minute = 60_000L

    @Test
    fun `time between page turns is reading`() {
        val clock = ReadingSessionClock()
        clock.resume(0)
        assertEquals(2 * minute, clock.checkpoint(2 * minute))
        assertEquals(5 * minute, clock.checkpoint(5 * minute))
    }

    @Test
    fun `a long foreground stretch is fully counted`() {
        val clock = ReadingSessionClock()
        clock.resume(0)
        assertEquals(9 * 60 * minute, clock.checkpoint(9 * 60 * minute))
    }

    @Test
    fun `checkpoints divide a session without changing its total`() {
        val clock = ReadingSessionClock()
        clock.resume(0)
        assertEquals(60 * minute, clock.checkpoint(60 * minute))
        assertEquals(120 * minute, clock.checkpoint(120 * minute))
        assertEquals(125 * minute, clock.pause(125 * minute))
    }

    @Test
    fun `pausing collects the time since the last page`() {
        val clock = ReadingSessionClock()
        clock.resume(0)
        clock.checkpoint(minute)
        assertEquals(3 * minute, clock.pause(3 * minute))
    }

    @Test
    fun `pausing twice does not pay twice`() {
        val clock = ReadingSessionClock()
        clock.resume(0)
        assertEquals(minute, clock.pause(minute))
        assertEquals(0, clock.pause(5 * minute))
    }

    @Test
    fun `pausing without ever resuming earns nothing`() {
        val clock = ReadingSessionClock()
        assertEquals(0, clock.pause(60 * minute))
    }

    @Test
    fun `resuming twice does not restart the clock`() {
        val clock = ReadingSessionClock()
        clock.resume(0)
        // A dialog dismissed over the book comes back through the same
        // door. The minute already read must survive it.
        clock.resume(minute)
        assertEquals(2 * minute, clock.pause(2 * minute))
    }

    @Test
    fun `a checkpoint while paused earns nothing`() {
        val clock = ReadingSessionClock()
        clock.resume(0)
        clock.pause(minute)
        assertEquals(0, clock.checkpoint(2 * minute))
    }

    @Test
    fun `time between sessions is not reading`() {
        val clock = ReadingSessionClock()
        clock.resume(0)
        assertEquals(minute, clock.pause(minute))
        // Away for an hour, then back. The hour is not reading.
        clock.resume(61 * minute)
        assertEquals(minute, clock.pause(62 * minute))
    }

    @Test
    fun `a clock that goes backwards earns nothing rather than a negative`() {
        val clock = ReadingSessionClock()
        clock.resume(10 * minute)
        assertEquals(0, clock.checkpoint(5 * minute))
        // The rejected checkpoint did not move the clock backwards, so
        // catching up only earns time after the original ten-minute mark.
        assertEquals(minute, clock.checkpoint(11 * minute))
        assertEquals(2 * minute, clock.pause(12 * minute))
    }

    @Test
    fun `is running says whether the clock is on`() {
        val clock = ReadingSessionClock()
        assertEquals(false, clock.isRunning)
        clock.resume(0)
        assertEquals(true, clock.isRunning)
        clock.pause(minute)
        assertEquals(false, clock.isRunning)
    }
}
