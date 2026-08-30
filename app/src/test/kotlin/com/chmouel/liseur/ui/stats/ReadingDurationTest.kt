package com.chmouel.liseur.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The short form of a duration, as drawn above a chart bar. */
class ReadingDurationTest {

    @Test
    fun `an empty day carries no figure at all`() {
        assertNull(compactDuration(0))
        assertNull(compactDuration(-5))
    }

    @Test
    fun `under a minute rounds up rather than reading as nothing`() {
        assertEquals("1m", compactDuration(30_000))
    }

    @Test
    fun `minutes alone need no hour`() {
        assertEquals("45m", compactDuration(TimeUnit.MINUTES.toMillis(45)))
    }

    @Test
    fun `a round hour needs no minutes`() {
        assertEquals("2h", compactDuration(TimeUnit.HOURS.toMillis(2)))
    }

    @Test
    fun `hours and minutes pack together`() {
        assertEquals("1h20", compactDuration(TimeUnit.MINUTES.toMillis(80)))
    }

    @Test
    fun `single-digit minutes keep their leading nought`() {
        // "1h5" reads as an hour and a half at a glance; "1h05" does not.
        assertEquals("1h05", compactDuration(TimeUnit.MINUTES.toMillis(65)))
    }
}
