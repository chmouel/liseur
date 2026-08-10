package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The arithmetic behind the dashboard. */
class ReadingStatsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val today: LocalDate = LocalDate.of(2026, 8, 9)

    /** Midday on a given day, in the zone the sums are done in. */
    private fun noonOn(date: LocalDate): Long =
        date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun book(url: String, finished: Boolean = false, progression: Double? = null) =
        StatsBook(url, "Title of $url", "An Author", progression, finished)

    @Test
    fun `nothing read is empty, not a screen of zeroes`() {
        val stats = readingStats(emptyList(), emptyMap(), zone, today)
        assertTrue(stats.isEmpty)
        assertEquals(ReadingStats.Empty, stats)
    }

    @Test
    fun `sessions of no length do not make a book look read`() {
        // A book opened and shut again. Listing it would turn the
        // dashboard into a list of everything ever tapped.
        val stats = readingStats(
            sessions = listOf(SessionSpan("a", noonOn(today), 0)),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
        )
        assertTrue(stats.isEmpty)
    }

    @Test
    fun `a book's sessions are added up and counted`() {
        val stats = readingStats(
            sessions = listOf(
                SessionSpan("a", noonOn(today.minusDays(2)), 600_000),
                SessionSpan("a", noonOn(today), 300_000),
            ),
            books = mapOf("a" to book("a", progression = 0.5)),
            zone = zone,
            today = today,
        )
        val line = stats.books.single()
        assertEquals(900_000, line.totalMs)
        assertEquals(noonOn(today), line.lastReadAt)
        assertEquals(0.5, line.progression!!, 1e-9)
        assertEquals(900_000, stats.totalMs)
        assertEquals(1, stats.booksRead)
    }

    @Test
    fun `last read comes from the final checkpoint rather than the session start`() {
        val started = noonOn(today.minusDays(1))
        val finished = noonOn(today)
        val stats = readingStats(
            sessions = listOf(
                SessionSpan(
                    bookUrl = "a",
                    startedAt = started,
                    durationMs = 60_000,
                    lastReadAt = finished,
                ),
            ),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
        )

        assertEquals(finished, stats.books.single().lastReadAt)
    }

    @Test
    fun `only books with reading behind them are counted as finished`() {
        val stats = readingStats(
            sessions = listOf(SessionSpan("a", noonOn(today), 60_000)),
            books = mapOf(
                "a" to book("a", finished = true),
                // Marked read by hand, never actually opened here. It is
                // not part of what this reader has read.
                "b" to book("b", finished = true),
            ),
            zone = zone,
            today = today,
        )
        assertEquals(1, stats.booksRead)
        assertEquals(1, stats.booksFinished)
    }

    @Test
    fun `a directly opened book without a library row is still shown`() {
        val stats = readingStats(
            sessions = listOf(SessionSpan("file:///gone/Middlemarch.epub", noonOn(today), 60_000)),
            books = emptyMap(),
            zone = zone,
            today = today,
        )
        assertEquals(60_000, stats.totalMs)
        assertEquals("Middlemarch.epub", stats.books.single().title)
    }

    @Test
    fun `books are listed by how much of them was read`() {
        val stats = readingStats(
            sessions = listOf(
                SessionSpan("a", noonOn(today), 60_000),
                SessionSpan("b", noonOn(today), 600_000),
            ),
            books = mapOf("a" to book("a"), "b" to book("b")),
            zone = zone,
            today = today,
        )
        assertEquals(listOf("b", "a"), stats.books.map { it.bookUrl })
    }

    @Test
    fun `the week has seven days, including the empty ones`() {
        val stats = readingStats(
            sessions = listOf(SessionSpan("a", noonOn(today.minusDays(3)), 60_000)),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
        )
        assertEquals(RECENT_DAYS, stats.recent.size)
        assertEquals(today, stats.recent.last().date)
        assertEquals(today.minusDays(6), stats.recent.first().date)
        assertEquals(60_000, stats.recent.single { it.date == today.minusDays(3) }.totalMs)
        assertEquals(0, stats.recent.single { it.date == today }.totalMs)
    }

    @Test
    fun `sessions on the same day are added together`() {
        val at = noonOn(today)
        val stats = readingStats(
            sessions = listOf(
                SessionSpan("a", at, 60_000),
                SessionSpan("b", at + 3_600_000, 120_000),
            ),
            books = mapOf("a" to book("a"), "b" to book("b")),
            zone = zone,
            today = today,
        )
        assertEquals(180_000, stats.recent.single { it.date == today }.totalMs)
    }

    @Test
    fun `reading older than the week still counts in the total`() {
        val stats = readingStats(
            sessions = listOf(SessionSpan("a", noonOn(today.minusDays(90)), 60_000)),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
        )
        assertEquals(60_000, stats.totalMs)
        assertTrue(stats.recent.all { it.totalMs == 0L })
    }

    @Test
    fun `a session is counted on the day it began, in the local zone`() {
        // Half past eleven at night in Paris. In UTC that is still the
        // day before, so grouping in the wrong zone puts this reader's
        // evening on yesterday.
        val lateEvening = today.atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val stats = readingStats(
            sessions = listOf(SessionSpan("a", lateEvening, 60_000)),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
        )
        assertEquals(60_000, stats.recent.single { it.date == today }.totalMs)
    }
}
