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

    @Test
    fun `a day past midnight is said in days, not in forty-one hours`() {
        assertEquals("1d17h", compactDuration(TimeUnit.MINUTES.toMillis(41 * 60 + 30)))
        assertEquals("2d", compactDuration(TimeUnit.HOURS.toMillis(48)))
    }
}

/** How a length of time is broken up before it is worded. */
class DurationPartsTest {

    @Test
    fun `nothing and less than nothing are both nothing`() {
        assertEquals(DurationParts.None, durationParts(0))
        assertEquals(DurationParts.None, durationParts(-1))
    }

    @Test
    fun `seconds are not a unit anybody reads in`() {
        assertEquals(DurationParts.UnderMinute, durationParts(59_000))
        assertEquals(DurationParts.Minutes(1), durationParts(TimeUnit.MINUTES.toMillis(1)))
    }

    @Test
    fun `an hour short of a day is still said in hours`() {
        assertEquals(
            DurationParts.Hours(23, 59),
            durationParts(TimeUnit.MINUTES.toMillis(23 * 60 + 59)),
        )
    }

    @Test
    fun `a full day turns over into days`() {
        assertEquals(DurationParts.Days(1, 0), durationParts(TimeUnit.HOURS.toMillis(24)))
    }

    @Test
    fun `days keep their hours but drop their minutes`() {
        // 51 h 40 min. The forty minutes are a rounding error beside two
        // days, and naming them makes the figure unreadable.
        assertEquals(
            DurationParts.Days(2, 3),
            durationParts(TimeUnit.MINUTES.toMillis(51 * 60 + 40)),
        )
    }

    @Test
    fun `every unit truncates so a total never outgrows its parts`() {
        // Not "2 d": rounding up here would let the headline claim more
        // than the rows it is a sum of.
        assertEquals(
            DurationParts.Days(1, 23),
            durationParts(TimeUnit.MINUTES.toMillis(47 * 60 + 59)),
        )
    }

    @Test
    fun `nothing above days, however long the reader has owned the app`() {
        assertEquals(
            DurationParts.Days(312, 4),
            durationParts(TimeUnit.HOURS.toMillis(312 * 24 + 4)),
        )
    }
}

