package com.chmouel.liseur.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What one book's reading adds up to. */
data class BookReadingStats(
    val bookUrl: String,
    val title: String,
    val author: String?,
    val totalMs: Long,
    /**
     * The part of [totalMs] that no server has been told about yet.
     *
     * A server's total can only ever describe what reached it, so a
     * figure that is meant to hold both has to add this back on. See
     * [SessionSpan.uploaded].
     */
    val pendingMs: Long = 0,
    val lastReadAt: Long,
    /** Where the reader is in it, if known. */
    val progression: Double?,
    val finished: Boolean,
    /** How many separate sittings it took, in the window being counted. */
    val sessions: Int = 0,
    /** How many of [sessions] no server has been told about yet. */
    val pendingSessions: Int = 0,
)

/** How much was read on one day. */
data class ReadingDay(
    val date: LocalDate,
    val totalMs: Long,
    /** The part of [totalMs] no server has been told about yet. */
    val pendingMs: Long = 0,
)

/** Everything the dashboard shows. */
data class ReadingStats(
    val totalMs: Long,
    /** The part of [totalMs] no server has been told about yet. */
    val pendingMs: Long = 0,
    val booksRead: Int,
    val booksFinished: Int,
    val books: List<BookReadingStats>,
    val recent: List<ReadingDay>,
    /** Separate sittings in the window. */
    val sessions: Int = 0,
    /** How many of [sessions] no server has been told about yet. */
    val pendingSessions: Int = 0,
    /**
     * Consecutive days with reading on them, ending today or yesterday.
     *
     * Counted over everything on record rather than the selected
     * window, matching what the sync server reports: a streak is a fact
     * about the reader, not about the span they chose to look at.
     */
    val streakDays: Int = 0,
    /**
     * Fraction of a book got through per hour, or null with nothing to
     * divide by.
     */
    val progressionPerHour: Double? = null,
) {
    val isEmpty: Boolean get() = totalMs <= 0 && books.isEmpty()

    companion object {
        val Empty = ReadingStats(
            totalMs = 0,
            booksRead = 0,
            booksFinished = 0,
            books = emptyList(),
            recent = emptyList(),
        )
    }
}

/** One session, reduced to what the sums need. */
data class SessionSpan(
    val bookUrl: String,
    val startedAt: Long,
    val durationMs: Long,
    /** The last persisted moment in the session. */
    val lastReadAt: Long = startedAt,
    /**
     * Whether this sitting has been sent to a sync server.
     *
     * What it is for is arithmetic, not bookkeeping: a server's total
     * counts the sittings it was given, this device's counts all of
     * them, and neither is the whole picture when some are still
     * queued. Knowing which are queued is what lets the two be added
     * rather than merely compared.
     *
     * A sitting with no progression on it is never uploaded at all, so
     * it stays pending for good — which is right, because no server can
     * ever have it.
     */
    val uploaded: Boolean = false,
    /**
     * How far into the book the stretch began and ended.
     *
     * Both null for sessions recorded before progressions were captured
     * and for a stretch in which no page turned. Only pace uses them,
     * and it simply has less to divide by when they are missing.
     */
    val startProgression: Double? = null,
    val endProgression: Double? = null,
)

/** What is known about a book, from everywhere that is not the sessions. */
data class StatsBook(
    val bookUrl: String,
    val title: String,
    val author: String?,
    val progression: Double?,
    val finished: Boolean,
)

/** How many days of history the dashboard shows day by day by default. */
const val RECENT_DAYS = 7

/**
 * Turns sessions and books into what the dashboard shows.
 *
 * Pure, and the sums are done here rather than in SQL, because
 * "which day was that" is not a question SQL can answer without being
 * told a timezone, and the timezone is the device's, and it changes
 * when the reader flies somewhere. Doing it in Kotlin means the answer
 * is computed against the zone in force when it is asked, and means it
 * can be tested by writing down a sequence of moments.
 *
 * [range] narrows every figure here except the streak, which is counted
 * over the whole of [sessions] on purpose — see [ReadingStats.streakDays].
 */
fun readingStats(
    sessions: List<SessionSpan>,
    books: Map<String, StatsBook>,
    zone: ZoneId,
    today: LocalDate,
    range: StatsRange = StatsRange.Default,
): ReadingStats {
    val recorded = sessions.filter { it.durationMs > 0 }
    // The streak is answered from everything, before the window is
    // applied, so that asking about the last week cannot report a
    // months-long run as seven days.
    val streak = streakDays(recorded, zone, today)
    val start = range.startDate(today)
    val counted = if (start == null) recorded else recorded.filter { !it.day(zone).isBefore(start) }
    if (counted.isEmpty()) return ReadingStats.Empty.copy(streakDays = streak)

    val stats = counted.groupBy { it.bookUrl }.map { (url, spans) ->
        val book = books[url]
        BookReadingStats(
            bookUrl = url,
            // A book opened directly through Android may never have had a
            // library row. Its URL is still a more honest label than
            // dropping time that was genuinely recorded.
            title = book?.title ?: url.substringAfterLast('/'),
            author = book?.author,
            totalMs = spans.sumOf { it.durationMs },
            pendingMs = spans.filterNot { it.uploaded }.sumOf { it.durationMs },
            lastReadAt = spans.maxOf { it.lastReadAt },
            progression = book?.progression,
            finished = book?.finished == true,
            sessions = spans.size,
            pendingSessions = spans.count { !it.uploaded },
        )
    }.sortedWith(compareByDescending<BookReadingStats> { it.totalMs }.thenBy { it.title })

    return ReadingStats(
        totalMs = stats.sumOf { it.totalMs },
        pendingMs = stats.sumOf { it.pendingMs },
        booksRead = stats.size,
        booksFinished = stats.count { it.finished },
        books = stats,
        recent = dailySeries(counted, zone, today, range),
        sessions = counted.size,
        pendingSessions = counted.count { !it.uploaded },
        streakDays = streak,
        progressionPerHour = progressionPerHour(counted),
    )
}

/**
 * The days in the window, including the ones with nothing on them.
 *
 * Empty days are kept deliberately: a week with two gaps in it is the
 * information, and a list that silently skipped them would read as an
 * unbroken run.
 *
 * A session that runs past midnight is counted whole, on the day it
 * ended. Splitting it is more truthful and much more code, and picking
 * the end rather than the beginning is what liseur-sync does for its
 * summary and its per-book rows — a stretch that began before the span
 * and finished inside it is the reader's answer either way, and the two
 * sides disagreeing about which day it landed on would put a headline
 * over rows that do not add up to it.
 *
 * A range with no beginning starts at the earliest day that has reading
 * on it, because a heatmap of every day since the epoch is mostly a
 * picture of before the reader owned the app.
 */
private fun dailySeries(
    sessions: List<SessionSpan>,
    zone: ZoneId,
    today: LocalDate,
    range: StatsRange,
): List<ReadingDay> {
    val byDay = sessions.groupBy { it.day(zone) }
    val start = range.startDate(today)
        ?: byDay.keys.minOrNull()
        ?: today.minusDays((RECENT_DAYS - 1).toLong())
    val span = ChronoUnit.DAYS.between(start, today)
    if (span < 0) return emptyList()
    return (0..span).map { forward ->
        val date = start.plusDays(forward)
        val onDay = byDay[date].orEmpty()
        ReadingDay(
            date = date,
            totalMs = onDay.sumOf { it.durationMs },
            pendingMs = onDay.filterNot { it.uploaded }.sumOf { it.durationMs },
        )
    }
}

/**
 * Consecutive days with reading, ending today or yesterday.
 *
 * Yesterday is allowed to start it so that a streak does not appear
 * broken every morning until the reader has opened a book. This is the
 * rule liseur-sync applies too, so the local answer and the server's
 * cannot contradict each other about the same reader.
 */
private fun streakDays(sessions: List<SessionSpan>, zone: ZoneId, today: LocalDate): Int {
    if (sessions.isEmpty()) return 0
    val active = sessions.mapTo(mutableSetOf()) { it.day(zone) }
    var day = today
    if (day !in active) {
        day = day.minusDays(1)
        if (day !in active) return 0
    }
    var streak = 0
    while (day in active) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}

/**
 * How much of a book the reader gets through in an hour.
 *
 * Only forward movement counts on top. Re-reading a chapter is time
 * genuinely spent and stays in the divisor, but it is not progress and
 * must not be allowed to look like it — the rule the sync server
 * applies too, so that a local figure and a server one are the same
 * quantity rather than two things sharing a label.
 *
 * A session that cannot say where in the book it happened is left out
 * of both halves of the division, not just the top. Those are old
 * sessions, recorded before progressions were captured, and they are
 * never uploaded either — so leaving them out is what makes this the
 * same sum the server does over the sessions it actually has. Counting
 * their time in the divisor alone would report a reader as slower the
 * longer they had owned the app.
 *
 * Null rather than zero when nothing is known: a pace of nought reads
 * as a verdict on the reader, and no figure at all is the truth.
 */
private fun progressionPerHour(sessions: List<SessionSpan>): Double? {
    var advance = 0.0
    var millis = 0L
    for (span in sessions) {
        val from = span.startProgression ?: continue
        val to = span.endProgression ?: continue
        millis += span.durationMs
        val delta = to - from
        if (delta > 0) advance += delta
    }
    if (advance <= 0 || millis <= 0) return null
    return advance / (millis.toDouble() / MILLIS_PER_HOUR)
}

private const val MILLIS_PER_HOUR = 3_600_000.0

private fun SessionSpan.day(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(lastReadAt).atZone(zone).toLocalDate()
