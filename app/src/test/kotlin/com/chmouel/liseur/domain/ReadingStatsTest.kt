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
    fun `a seven day span has seven days, including the empty ones`() {
        val stats = readingStats(
            sessions = listOf(SessionSpan("a", noonOn(today.minusDays(3)), 60_000)),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
            range = StatsRange.LAST_7_DAYS,
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
    fun `reading older than the chart still counts in the total`() {
        val stats = readingStats(
            sessions = listOf(SessionSpan("a", noonOn(today.minusDays(90)), 60_000)),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
            range = StatsRange.ALL_TIME,
        )
        assertEquals(60_000, stats.totalMs)
    }

    @Test
    fun `a session is counted in the local zone, not in UTC`() {
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

    /**
     * liseur-sync places a sitting on the day it ended, for both the
     * summary and the per-book rows. This device has to agree, or a
     * stretch that began before a span and finished inside it is in the
     * server's headline and missing from the rows underneath it.
     */
    @Test
    fun `a session that runs past midnight belongs to the day it ended`() {
        val yesterday = today.minusDays(1)
        val startedLastNight = yesterday.atTime(23, 40).atZone(zone).toInstant().toEpochMilli()
        val endedThisMorning = today.atTime(0, 20).atZone(zone).toInstant().toEpochMilli()
        val stats = readingStats(
            sessions = listOf(
                SessionSpan("a", startedLastNight, 40 * 60_000, lastReadAt = endedThisMorning),
            ),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
        )
        assertEquals(40 * 60_000L, stats.recent.single { it.date == today }.totalMs)
        assertEquals(0L, stats.recent.single { it.date == yesterday }.totalMs)
    }

    @Test
    fun `a sitting finishing inside the span is inside it, wherever it began`() {
        val stats = readingStats(
            sessions = listOf(
                SessionSpan(
                    "a",
                    noonOn(today.minusDays(8)),
                    60_000,
                    lastReadAt = noonOn(today.minusDays(3)),
                ),
            ),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
            range = StatsRange.LAST_7_DAYS,
        )
        assertEquals(60_000L, stats.totalMs)
        assertEquals(1, stats.sessions)
    }

    // --- The span the reader chose ------------------------------------

    @Test
    fun `each span reaches exactly as far back as it says`() {
        val sessions = listOf(
            SessionSpan("a", noonOn(today), 1_000),
            SessionSpan("a", noonOn(today.minusDays(6)), 1_000),
            SessionSpan("a", noonOn(today.minusDays(29)), 1_000),
            SessionSpan("a", noonOn(today.minusDays(89)), 1_000),
            SessionSpan("a", noonOn(today.minusDays(364)), 1_000),
            SessionSpan("a", noonOn(today.minusDays(1_000)), 1_000),
        )
        val books = mapOf("a" to book("a"))
        fun countedIn(range: StatsRange) =
            readingStats(sessions, books, zone, today, range).sessions

        assertEquals(2, countedIn(StatsRange.LAST_7_DAYS))
        assertEquals(3, countedIn(StatsRange.LAST_30_DAYS))
        assertEquals(4, countedIn(StatsRange.LAST_90_DAYS))
        assertEquals(5, countedIn(StatsRange.LAST_YEAR))
        assertEquals(6, countedIn(StatsRange.ALL_TIME))
    }

    @Test
    fun `the day on the far edge of a span is inside it`() {
        // Off-by-one here silently drops a day's reading, which is the
        // sort of error nobody notices and everybody is wronged by.
        val stats = readingStats(
            sessions = listOf(SessionSpan("a", noonOn(today.minusDays(6)), 60_000)),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
            range = StatsRange.LAST_7_DAYS,
        )
        assertEquals(60_000, stats.totalMs)
    }

    @Test
    fun `this year starts in January, not three hundred and sixty five days ago`() {
        val newYear = today.withDayOfYear(1)
        val stats = readingStats(
            sessions = listOf(
                SessionSpan("a", noonOn(newYear), 60_000),
                // The last day of the previous year: within 365 days,
                // but not within this one.
                SessionSpan("a", noonOn(newYear.minusDays(1)), 60_000),
            ),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
            range = StatsRange.THIS_YEAR,
        )
        assertEquals(60_000, stats.totalMs)
    }

    @Test
    fun `all time draws its chart from the first day with reading on it`() {
        val stats = readingStats(
            sessions = listOf(SessionSpan("a", noonOn(today.minusDays(3)), 60_000)),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
            range = StatsRange.ALL_TIME,
        )
        assertEquals(today.minusDays(3), stats.recent.first().date)
        assertEquals(today, stats.recent.last().date)
        assertEquals(4, stats.recent.size)
    }

    @Test
    fun `a span with nothing in it reads as empty, not as a screen of zeroes`() {
        val stats = readingStats(
            sessions = listOf(SessionSpan("a", noonOn(today.minusDays(200)), 60_000)),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
            range = StatsRange.LAST_7_DAYS,
        )
        assertTrue(stats.isEmpty)
        assertEquals(0, stats.sessions)
    }

    // --- The streak ---------------------------------------------------

    @Test
    fun `the streak counts back to the first day without reading`() {
        val sessions = (0..4).map { SessionSpan("a", noonOn(today.minusDays(it.toLong())), 60_000) } +
            // A gap on day five, then more reading that must not join on.
            listOf(SessionSpan("a", noonOn(today.minusDays(6)), 60_000))
        val stats = readingStats(sessions, mapOf("a" to book("a")), zone, today, StatsRange.ALL_TIME)
        assertEquals(5, stats.streakDays)
    }

    @Test
    fun `a streak is not broken by a morning with no reading in it yet`() {
        // Nothing read today. Ending the streak before the reader has
        // had a chance to open a book would be a punishment for waking up.
        val sessions = (1..3).map { SessionSpan("a", noonOn(today.minusDays(it.toLong())), 60_000) }
        val stats = readingStats(sessions, mapOf("a" to book("a")), zone, today, StatsRange.ALL_TIME)
        assertEquals(3, stats.streakDays)
    }

    @Test
    fun `a streak that ended before yesterday is over`() {
        val sessions = (2..6).map { SessionSpan("a", noonOn(today.minusDays(it.toLong())), 60_000) }
        val stats = readingStats(sessions, mapOf("a" to book("a")), zone, today, StatsRange.ALL_TIME)
        assertEquals(0, stats.streakDays)
    }

    @Test
    fun `the streak is not narrowed by the span being looked at`() {
        val sessions = (0..19).map { SessionSpan("a", noonOn(today.minusDays(it.toLong())), 60_000) }
        val stats = readingStats(
            sessions,
            mapOf("a" to book("a")),
            zone,
            today,
            StatsRange.LAST_7_DAYS,
        )
        assertEquals(7, stats.sessions)
        assertEquals(20, stats.streakDays)
    }

    /**
     * A server's totals can only describe what reached it, so the sums
     * carry the part that has not — per book, per day, and overall — for
     * the merge to add back on rather than guess at.
     */
    @Test
    fun `time not yet uploaded is counted separately as well as in the total`() {
        val stats = readingStats(
            sessions = listOf(
                SessionSpan("a", noonOn(today), 3_600_000, uploaded = true),
                SessionSpan("a", noonOn(today), 1_200_000, uploaded = false),
                SessionSpan("b", noonOn(today.minusDays(1)), 600_000, uploaded = false),
            ),
            books = mapOf("a" to book("a"), "b" to book("b")),
            zone = zone,
            today = today,
            range = StatsRange.LAST_7_DAYS,
        )
        assertEquals(5_400_000, stats.totalMs)
        assertEquals(1_800_000, stats.pendingMs)
        assertEquals(1_200_000, stats.books.first { it.bookUrl == "a" }.pendingMs)
        assertEquals(600_000, stats.recent.first { it.date == today.minusDays(1) }.pendingMs)
        assertEquals(1_200_000, stats.recent.first { it.date == today }.pendingMs)
        assertEquals(3, stats.sessions)
        assertEquals(2, stats.pendingSessions)
    }

    // --- Pace ---------------------------------------------------------

    @Test
    fun `pace is how much of a book an hour of reading gets through`() {
        val stats = readingStats(
            sessions = listOf(
                SessionSpan(
                    bookUrl = "a",
                    startedAt = noonOn(today),
                    durationMs = 3_600_000,
                    startProgression = 0.0,
                    endProgression = 0.25,
                ),
            ),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
        )
        assertEquals(0.25, stats.progressionPerHour!!, 1e-9)
    }

    @Test
    fun `re-reading costs time but is not progress`() {
        // An hour forward and an hour back over the same chapter. The
        // second hour was genuinely spent, so it stays in the divisor,
        // but it moved nobody closer to the end.
        val stats = readingStats(
            sessions = listOf(
                SessionSpan("a", noonOn(today), 3_600_000, startProgression = 0.0, endProgression = 0.4),
                SessionSpan("a", noonOn(today), 3_600_000, startProgression = 0.4, endProgression = 0.2),
            ),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
        )
        assertEquals(0.2, stats.progressionPerHour!!, 1e-9)
    }

    @Test
    fun `pace is unknown rather than nought when nothing says where reading happened`() {
        val stats = readingStats(
            sessions = listOf(SessionSpan("a", noonOn(today), 3_600_000)),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
        )
        assertEquals(null, stats.progressionPerHour)
    }

    /**
     * The server divides by the time of the sessions it holds, and a
     * session that cannot say where it happened is never uploaded to it.
     * Counting that time here anyway would make the same reader slower
     * on this screen than on any other device's, and slower the longer
     * they had owned the app.
     */
    @Test
    fun `time that could not say where it happened is left out of both halves`() {
        val stats = readingStats(
            sessions = listOf(
                SessionSpan("a", noonOn(today), 3_600_000, startProgression = 0.0, endProgression = 0.25),
                SessionSpan("a", noonOn(today), 3_600_000),
            ),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
        )
        // A quarter of the book in the one hour that can be accounted
        // for, not an eighth over two.
        assertEquals(0.25, stats.progressionPerHour!!, 1e-9)
    }

    // --- Sittings -----------------------------------------------------

    @Test
    fun `a book knows how many sittings it took`() {
        val stats = readingStats(
            sessions = listOf(
                SessionSpan("a", noonOn(today), 60_000),
                SessionSpan("a", noonOn(today.minusDays(1)), 60_000),
                SessionSpan("b", noonOn(today), 60_000),
            ),
            books = mapOf("a" to book("a"), "b" to book("b")),
            zone = zone,
            today = today,
        )
        assertEquals(3, stats.sessions)
        assertEquals(2, stats.books.single { it.bookUrl == "a" }.sessions)
        assertEquals(1, stats.books.single { it.bookUrl == "b" }.sessions)
    }

    // --- When the book was begun ----------------------------------------

    @Test
    fun `a book remembers when its first sitting began`() {
        val begun = noonOn(today.minusDays(2))
        val stats = readingStats(
            sessions = listOf(
                SessionSpan("a", begun, 60_000),
                SessionSpan("a", noonOn(today), 60_000),
            ),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
        )
        assertEquals(begun, stats.books.single().firstReadAt)
    }

    @Test
    fun `when a book was begun is not narrowed by the span being looked at`() {
        // Begun three months ago, still being read this week. The week
        // view must not claim the book was started on Tuesday.
        val begun = noonOn(today.minusDays(90))
        val stats = readingStats(
            sessions = listOf(
                SessionSpan("a", begun, 60_000),
                SessionSpan("a", noonOn(today), 60_000),
            ),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
            range = StatsRange.LAST_7_DAYS,
        )
        assertEquals(begun, stats.books.single().firstReadAt)
    }

    @Test
    fun `a sitting of no length does not date the beginning of a book`() {
        // Opened and shut a month ago, actually read yesterday. The
        // glance is not when reading began.
        val stats = readingStats(
            sessions = listOf(
                SessionSpan("a", noonOn(today.minusDays(30)), 0),
                SessionSpan("a", noonOn(today.minusDays(1)), 60_000),
            ),
            books = mapOf("a" to book("a")),
            zone = zone,
            today = today,
        )
        assertEquals(noonOn(today.minusDays(1)), stats.books.single().firstReadAt)
    }
}
