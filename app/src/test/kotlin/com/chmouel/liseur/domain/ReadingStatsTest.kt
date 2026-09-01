package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The arithmetic behind the dashboard. */
class ReadingStatsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val today: LocalDate = LocalDate.of(2026, 8, 9)

    /** Midday on a given day, in the zone the sums are done in. */
    private fun noonOn(date: LocalDate): Long =
        date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /** A given hour on a given day, in that same zone. */
    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

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
            range = StatsRange.THIS_WEEK,
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
            range = StatsRange.THIS_WEEK,
        )
        assertEquals(60_000L, stats.totalMs)
        assertEquals(1, stats.sessions)
    }

    // --- The span the reader chose ------------------------------------

    @Test
    fun `each span reaches exactly as far back as it says`() {
        // Today is Sunday the 9th of August 2026, so a Monday-first week
        // began on the 3rd and the month on the 1st.
        val sessions = listOf(
            SessionSpan("a", noonOn(today), 1_000),
            SessionSpan("a", noonOn(today.minusDays(6)), 1_000),
            SessionSpan("a", noonOn(today.minusDays(8)), 1_000),
            SessionSpan("a", noonOn(today.minusDays(29)), 1_000),
            SessionSpan("a", noonOn(today.minusDays(89)), 1_000),
            SessionSpan("a", noonOn(today.minusDays(364)), 1_000),
            SessionSpan("a", noonOn(today.minusDays(1_000)), 1_000),
        )
        val books = mapOf("a" to book("a"))
        fun countedIn(range: StatsRange) =
            readingStats(sessions, books, zone, today, range).sessions

        assertEquals(2, countedIn(StatsRange.THIS_WEEK))
        assertEquals(3, countedIn(StatsRange.THIS_MONTH))
        assertEquals(5, countedIn(StatsRange.THIS_YEAR))
        assertEquals(7, countedIn(StatsRange.ALL_TIME))
    }

    @Test
    fun `a sitting dated after today is in no span at all`() {
        // A clock corrected backwards, a row imported from elsewhere, a
        // flight east. The chart has always stopped at today, so time
        // counted into the total but drawn nowhere was a figure the
        // reader could not account for — and the period it is now
        // compared against is bounded at both ends, so leaving it in one
        // side only would invent a difference.
        val sessions = listOf(
            SessionSpan("a", noonOn(today), 60_000),
            SessionSpan("a", noonOn(today.plusDays(3)), 60_000),
        )
        val books = mapOf("a" to book("a"))
        for (range in StatsRange.entries) {
            val stats = readingStats(sessions, books, zone, today, range)
            assertEquals("$range counted a session from the future", 60_000L, stats.totalMs)
            assertEquals(1, stats.sessions)
            assertTrue(stats.recent.none { it.date.isAfter(today) })
        }
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
            range = StatsRange.THIS_WEEK,
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
            range = StatsRange.THIS_WEEK,
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
            StatsRange.THIS_WEEK,
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
            range = StatsRange.THIS_WEEK,
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
            range = StatsRange.THIS_WEEK,
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

    // --- The baseline a period is measured against --------------------

    @Test
    fun `a bounded span counts both of its endpoints and nothing outside`() {
        val span = DateSpan(today.minusDays(6), today.minusDays(4))
        val totals = readingTotals(
            sessions = listOf(
                SessionSpan("a", noonOn(today.minusDays(7)), 1_000),
                SessionSpan("a", noonOn(today.minusDays(6)), 2_000),
                SessionSpan("a", noonOn(today.minusDays(5)), 4_000),
                SessionSpan("a", noonOn(today.minusDays(4)), 8_000),
                SessionSpan("a", noonOn(today.minusDays(3)), 16_000),
            ),
            zone = zone,
            span = span,
        )
        assertEquals(14_000L, totals.totalMs)
    }

    @Test
    fun `the baseline keeps what no server has heard about apart`() {
        val totals = readingTotals(
            sessions = listOf(
                SessionSpan("a", noonOn(today.minusDays(8)), 60_000, uploaded = true),
                SessionSpan("a", noonOn(today.minusDays(8)), 30_000, uploaded = false),
            ),
            zone = zone,
            span = DateSpan(today.minusDays(13), today.minusDays(7)),
        )
        assertEquals(90_000L, totals.totalMs)
        assertEquals(30_000L, totals.pendingMs)
    }

    @Test
    fun `the baseline puts a sitting past midnight where the headline does`() {
        // Half eleven until half twelve. Both sides have to agree which
        // day that was, or the comparison reports a difference nobody
        // made. Sessions are dated by where they ended.
        val startedAt = today.minusDays(8).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val endedAt = today.minusDays(7).atTime(0, 30).atZone(zone).toInstant().toEpochMilli()
        val session = SessionSpan("a", startedAt, 3_600_000, lastReadAt = endedAt)

        assertEquals(
            0L,
            readingTotals(listOf(session), zone, DateSpan(today.minusDays(9), today.minusDays(8)))
                .totalMs,
        )
        assertEquals(
            3_600_000L,
            readingTotals(listOf(session), zone, DateSpan(today.minusDays(7), today.minusDays(7)))
                .totalMs,
        )
    }

    @Test
    fun `a sitting of no length is not a sitting, on either side`() {
        val totals = readingTotals(
            sessions = listOf(SessionSpan("a", noonOn(today.minusDays(3)), 0)),
            zone = zone,
            span = DateSpan(today.minusDays(6), today),
        )
        assertEquals(SpanTotals.Empty, totals)
    }

    /**
     * The baseline's last day stops where today's clock has got to.
     *
     * A period that has only reached four in the afternoon measured
     * against a completed evening reports the reader as falling behind
     * every day, and back level at midnight.
     */
    @Test
    fun `a baseline leaves out what came after the hour today has reached`() {
        val lastDay = today.minusDays(7)
        val morning = SessionSpan("a", at(lastDay, 10, 0), 60_000)
        val evening = SessionSpan("a", at(lastDay, 20, 0), 60_000)

        val totals = readingTotals(
            sessions = listOf(morning, evening),
            zone = zone,
            span = DateSpan(lastDay, lastDay),
            through = LocalTime.of(16, 0),
        )
        assertEquals(60_000L, totals.totalMs)

        // Sitting still, the reader crosses the same hour and it counts.
        assertEquals(
            120_000L,
            readingTotals(
                sessions = listOf(morning, evening),
                zone = zone,
                span = DateSpan(lastDay, lastDay),
                through = LocalTime.of(21, 0),
            ).totalMs,
        )
    }

    /** Only the last day is cut short; every day before it is whole. */
    @Test
    fun `an earlier day in the baseline keeps its evening`() {
        val lastDay = today.minusDays(7)
        val totals = readingTotals(
            sessions = listOf(
                SessionSpan("a", at(lastDay.minusDays(1), 23, 0), 60_000),
                SessionSpan("a", at(lastDay, 23, 0), 60_000),
            ),
            zone = zone,
            span = DateSpan(lastDay.minusDays(1), lastDay),
            through = LocalTime.of(9, 0),
        )
        assertEquals(60_000L, totals.totalMs)
    }

    /**
     * The cutoff dates a sitting by where it ended, as everything does.
     *
     * A stretch begun before the hour and still running past it is one
     * the reader had not finished by this time last week either.
     */
    @Test
    fun `a sitting that ran past the hour is dated by where it ended`() {
        val lastDay = today.minusDays(7)
        val startedAt = at(lastDay, 15, 30)
        val session = SessionSpan(
            "a",
            startedAt,
            3_600_000,
            lastReadAt = startedAt + 3_600_000,
        )

        // Half past four is half an hour in.
        assertEquals(
            1_800_000L,
            readingTotals(listOf(session), zone, DateSpan(lastDay, lastDay), LocalTime.of(16, 0))
                .totalMs,
        )
        assertEquals(
            3_600_000L,
            readingTotals(listOf(session), zone, DateSpan(lastDay, lastDay), LocalTime.of(17, 0))
                .totalMs,
        )
    }

    /**
     * Midnight divides nothing, whichever way the sitting crossed it.
     *
     * The end of a day is where one belongs, not where one is cut: a
     * sitting is dated by where it ended, here and in the headline and
     * on liseur-sync alike. Were the last midnight of a span a cutoff
     * like any other, the two halves of a comparison would not add up
     * to the whole the reader is shown above them.
     */
    @Test
    fun `the end of a day divides no sitting`() {
        val startedAt = at(today.minusDays(8), 23, 30)
        val session = SessionSpan("a", startedAt, 3_600_000, lastReadAt = startedAt + 3_600_000)

        val before = DateSpan(today.minusDays(9), today.minusDays(8))
        val after = DateSpan(today.minusDays(7), today.minusDays(7))
        assertEquals(0L, readingTotals(listOf(session), zone, before, LocalTime.MAX).totalMs)
        assertEquals(3_600_000L, readingTotals(listOf(session), zone, after, LocalTime.MAX).totalMs)
    }

    /**
     * A sitting still running at the cutoff counts for what had elapsed.
     *
     * The sitting it is being compared with is the one open on the
     * reader's screen right now, and that one is only recorded as far as
     * its last checkpoint. Dropping this one whole would compare an
     * afternoon with nothing; keeping it whole would compare it with an
     * evening.
     */
    @Test
    fun `a sitting straddling the cutoff counts for the part that had gone`() {
        val lastDay = today.minusDays(7)
        val startedAt = at(lastDay, 15, 0)
        // An hour on the clock, of which forty minutes was actually read.
        val session = SessionSpan(
            "a",
            startedAt,
            2_400_000,
            lastReadAt = startedAt + 3_600_000,
        )

        // A quarter of the way in: a quarter of the forty minutes.
        assertEquals(
            600_000L,
            readingTotals(listOf(session), zone, DateSpan(lastDay, lastDay), LocalTime.of(15, 15))
                .totalMs,
        )
        // Begun but not yet reached.
        assertEquals(
            0L,
            readingTotals(listOf(session), zone, DateSpan(lastDay, lastDay), LocalTime.of(15, 0))
                .totalMs,
        )
        // Over and done with.
        assertEquals(
            2_400_000L,
            readingTotals(listOf(session), zone, DateSpan(lastDay, lastDay), LocalTime.of(16, 0))
                .totalMs,
        )
    }

    /**
     * A sitting running past the cutoff counts, wherever it ended.
     *
     * The reader's own sitting at this moment has not ended anywhere
     * yet. It will be dated to tonight or to tomorrow depending on when
     * they put the book down, and the comparison cannot wait to find
     * out. So the evening a week ago that ran past midnight is counted
     * for the part of it that had gone by this time, exactly as today's
     * is — the day it was eventually filed under decides the headline,
     * not this.
     */
    @Test
    fun `a sitting that outlived the day still counts up to the cutoff`() {
        val lastDay = today.minusDays(7)
        val startedAt = at(lastDay, 18, 0)
        // Six hours on the clock, ending at half past midnight, of which
        // three were active reading.
        val session = SessionSpan(
            "a",
            startedAt,
            10_800_000,
            lastReadAt = startedAt + 21_600_000,
        )
        val span = DateSpan(lastDay.minusDays(1), lastDay)

        // Forty minutes in: a ninth of the six hours, so a ninth of the
        // three that were read.
        assertEquals(
            1_200_000L,
            readingTotals(listOf(session), zone, span, LocalTime.of(18, 40)).totalMs,
        )

        // The same sitting is not in that span at all once the span runs
        // to the end of its day: it ended on the next one, and that is
        // where the headline files it.
        assertEquals(0L, readingTotals(listOf(session), zone, span, LocalTime.MAX).totalMs)
    }

    /**
     * The hour struck twice is the first of them.
     *
     * On the morning the clocks go back, 02:30 happens twice in Paris.
     * A cutoff that named the wall clock and nothing else would count
     * reading from the second of them against a period that had only
     * reached the first, which is the extra hour this rule exists to
     * refuse.
     */
    @Test
    fun `an hour the clocks repeat is counted once, at its first turn`() {
        // 25 October 2026: 03:00 CEST becomes 02:00 CET in Paris.
        val fallBack = LocalDate.of(2026, 10, 25)
        val first = ZonedDateTime.of(fallBack, LocalTime.of(2, 30), zone)
            .withEarlierOffsetAtOverlap()
        val second = first.withLaterOffsetAtOverlap()
        assertTrue(second.toInstant() > first.toInstant())

        val sessions = listOf(
            SessionSpan("a", first.toInstant().toEpochMilli(), 60_000),
            SessionSpan("a", second.toInstant().toEpochMilli(), 60_000),
        )

        assertEquals(
            60_000L,
            readingTotals(sessions, zone, DateSpan(fallBack, fallBack), LocalTime.of(2, 30))
                .totalMs,
        )
        // Later in the day both are behind the reader.
        assertEquals(
            120_000L,
            readingTotals(sessions, zone, DateSpan(fallBack, fallBack), LocalTime.of(9, 0))
                .totalMs,
        )
    }

    /**
     * An hour the clocks skip is moved on by the hour that was skipped.
     *
     * 02:30 does not exist in Paris on the morning the clocks go
     * forward, and a comparison cannot simply refuse to happen on it.
     * There is one defined answer, it is the one `java.time` gives, and
     * it stays on the day the reader is looking at.
     */
    @Test
    fun `an hour the clocks skip is moved on by the length of the gap`() {
        // 29 March 2026: 02:00 CET becomes 03:00 CEST in Paris, so a
        // cutoff of 02:30 resolves to 03:30.
        val springForward = LocalDate.of(2026, 3, 29)
        val resolved = ZonedDateTime.of(springForward, LocalTime.of(3, 30), zone)
        val justBefore = SessionSpan("a", resolved.toInstant().toEpochMilli() - 60_000, 60_000)
        val justAfter = SessionSpan("a", resolved.toInstant().toEpochMilli() + 60_000, 60_000)

        val totals = readingTotals(
            sessions = listOf(justBefore, justAfter),
            zone = zone,
            span = DateSpan(springForward, springForward),
            through = LocalTime.of(2, 30),
        )
        assertEquals(60_000L, totals.totalMs)
    }

    /** A cut-short day keeps unsent time apart just as a whole one does. */
    @Test
    fun `a cut short baseline still says what no server has heard`() {
        val lastDay = today.minusDays(7)
        val totals = readingTotals(
            sessions = listOf(
                SessionSpan("a", at(lastDay, 9, 0), 60_000, uploaded = true),
                SessionSpan("a", at(lastDay, 10, 0), 30_000, uploaded = false),
                SessionSpan("a", at(lastDay, 20, 0), 90_000, uploaded = false),
            ),
            zone = zone,
            span = DateSpan(lastDay, lastDay),
            through = LocalTime.of(12, 0),
        )
        assertEquals(90_000L, totals.totalMs)
        assertEquals(30_000L, totals.pendingMs)
    }

    // --- More, less, or the same --------------------------------------

    @Test
    fun `more and less are stated as whole percents`() {
        val more = compareReading(ComparisonPeriod.WEEK, 125_000, 100_000)
        assertEquals(ComparisonDirection.MORE, more.direction)
        assertEquals(25, more.percent)

        val less = compareReading(ComparisonPeriod.MONTH, 88_000, 100_000)
        assertEquals(ComparisonDirection.LESS, less.direction)
        assertEquals(12, less.percent)
    }

    @Test
    fun `a difference too small to name is the same, not nought per cent`() {
        // "0% more than last week" says nothing twice, and the rounding
        // that produced it is not something the reader can see.
        val same = compareReading(ComparisonPeriod.YEAR, 100_400, 100_000)
        assertEquals(ComparisonDirection.SAME, same.direction)
        assertNull(same.percent)

        val exactly = compareReading(ComparisonPeriod.WEEK, 100_000, 100_000)
        assertEquals(ComparisonDirection.SAME, exactly.direction)
        assertNull(exactly.percent)
    }

    @Test
    fun `nothing to divide by is not an infinity`() {
        val started = compareReading(ComparisonPeriod.WEEK, 60_000, 0)
        assertEquals(ComparisonDirection.MORE, started.direction)
        // No percentage at all: the screen says "More than last week".
        assertNull(started.percent)

        val neither = compareReading(ComparisonPeriod.WEEK, 0, 0)
        assertEquals(ComparisonDirection.SAME, neither.direction)
        assertNull(neither.percent)
    }

    @Test
    fun `a period with nothing in it is a hundred per cent less`() {
        // The screen shows its empty state instead of drawing this, but
        // the arithmetic has to be defined either way.
        val nothing = compareReading(ComparisonPeriod.WEEK, 0, 60_000)
        assertEquals(ComparisonDirection.LESS, nothing.direction)
        assertEquals(100, nothing.percent)
    }

    @Test
    fun `absurd totals neither overflow nor go negative`() {
        // Unreachable from real sessions, but this is pure arithmetic
        // and should not depend on its callers to stay sane. Subtracting
        // these as Longs wraps; abs of the result stays negative.
        val huge = compareReading(ComparisonPeriod.WEEK, Long.MAX_VALUE, 1)
        assertEquals(ComparisonDirection.MORE, huge.direction)
        assertEquals(Int.MAX_VALUE, huge.percent)

        val negative = compareReading(ComparisonPeriod.WEEK, Long.MIN_VALUE, 100)
        assertEquals(ComparisonDirection.LESS, negative.direction)
        assertEquals(100, negative.percent)
    }

    @Test
    fun `the period asked about is the period answered for`() {
        for (period in ComparisonPeriod.entries) {
            assertEquals(period, compareReading(period, 1, 2).period)
        }
    }
}
