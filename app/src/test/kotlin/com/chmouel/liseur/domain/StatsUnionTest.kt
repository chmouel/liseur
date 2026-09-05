package com.chmouel.liseur.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsUnionTest {
    @Test
    fun `tolerated wire excess is normalized before opposite millisecond rounding`() {
        assertEquals(20_000L, unionMinutes(
            1000.4999999999998 / 60_000.0, 20_000, 1000.5000000000001 / 60_000.0,
        ))
        assertNull(unionMinutes(1000.0 / 60_000, 0, 1001.25 / 60_000))
        assertNull(unionMillis(1000, 0, 1001))
        assertNull(unionSessions(1, 0, 2))
    }

    @Test
    fun `lost acknowledgement does not add the same sitting twice`() {
        assertEquals(90L, unionMillis(server = 90, local = 20, overlap = 20))
        assertEquals(4, unionSessions(server = 4, local = 1, overlap = 1))
    }

    @Test
    fun `legacy wall duration is subtracted rather than measured local duration`() {
        assertEquals(110L, unionMillis(server = 90, local = 30, overlap = 10))
    }

    @Test
    fun `cached answer keeps reading uploaded after that snapshot`() {
        assertEquals(110L, unionMillis(server = 90, local = 20, overlap = 0))
    }

    @Test
    fun `impossible proof and overflowing unions are refused`() {
        assertNull(unionMillis(1, 2, 3))
        assertNull(unionMillis(Long.MAX_VALUE, 1, 0))
        assertNull(unionSessions(Int.MAX_VALUE, 1, 0))
    }

    @Test
    fun `local days bridge and extend server streaks`() {
        val today = LocalDate.of(2026, 9, 5)
        val remote = (1L..10L).map { today.minusDays(it) }.toSet()
        assertEquals(11, activeDayStreak(remote + today, today))
        assertEquals(11, activeDayStreak((remote - today.minusDays(5)) + today + today.minusDays(5), today))
    }

    @Test
    fun `all history calendar uses contiguous bounded inclusive chunks`() {
        val start = LocalDate.of(1990, 1, 1)
        val end = LocalDate.of(2026, 9, 5)
        val chunks = calendarChunks(start, end)
        assertEquals(start, chunks.first().first)
        assertEquals(end, chunks.last().second)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.second.plusDays(1), b.first) }
        chunks.forEach { (a, b) -> assert(b.toEpochDay() - a.toEpochDay() < 4_000) }
    }
}
