package com.chmouel.liseur.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What one book's reading adds up to. */
data class BookReadingStats(
    /**
     * The book on this device, or null for one only a server knows.
     *
     * A work read on another phone, or one this device has never
     * resolved, is still the reader's reading and is still in the total
     * above the list (ADR-0021). It has no file here, so it has no
     * cover and nothing to open — but it can still carry [progression]
     * and [finished], read off the server's own `current_progression`
     * for that work, and nothing that reads a row must assume otherwise.
     */
    val bookUrl: String?,
    val title: String,
    val author: String?,
    val totalMs: Long,
    /**
     * Local time not yet acknowledged by a server. This is bookkeeping,
     * not evidence that a cached server snapshot excludes it.
     */
    val pendingMs: Long = 0,
    val lastReadAt: Long,
    /**
     * When the very first sitting began, over everything on record.
     *
     * A fact about the book, not the window: like the streak, it is
     * answered before the range is applied, because "when did I start
     * this" does not change with the span the reader chose to look at.
     * Null only for a book this device never recorded a sitting for.
     */
    val firstReadAt: Long? = null,
    /**
     * Where the reader is in it, if known.
     *
     * For a local book, this is what the reader's own device knows. For
     * a [bookUrl]-less row it comes from the server instead — its
     * `current_progression` for that work — since this device has no
     * file to read a locator off; null there means the server itself had
     * nothing recent enough to say.
     */
    val progression: Double?,
    val finished: Boolean,
    /**
     * How many separate sittings it took, in the window being counted.
     * Pauses shorter than [SITTING_GAP_MS] do not end a sitting.
     */
    val sessions: Int = 0,
    /** How many of [sessions] no server has been told about yet. */
    val pendingSessions: Int = 0,
    val coverPath: String? = null,
    val coverUrl: String? = null,
    /** The saved catalog [coverUrl] comes from, whose login fetches it. */
    val browseServerId: Long? = null,
    /**
     * The server's name for the work, when this row came from one.
     *
     * Only ever set on a row with no [bookUrl], where it is the row's
     * whole identity: a list needs a stable key, and a book this device
     * does not have has no URL to be keyed by.
     */
    val workId: String? = null,
) {
    /** A stable identity for a list, whichever kind of row this is. */
    val key: String get() = bookUrl ?: "work:${workId.orEmpty()}"

    /** Whether the book is on this device, and so can be opened. */
    val isLocal: Boolean get() = bookUrl != null
}

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
    /** Sittings in the window: a book's sessions less than [SITTING_GAP_MS] apart on one day count once. */
    val sessions: Int = 0,
    /** How many of [sessions] no server has been told about yet. */
    val pendingSessions: Int = 0,
    /**
     * Length of the longest single sitting in the window, in milliseconds.
     *
     * Computed from local sittings on [THIS_DEVICE]; 0 when no local
     * sittings are available or on merged cross-device snapshots where
     * the server supplies only daily and per-work totals.
     */
    val longestSessionMs: Long = 0,
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
    /**
     * Books read on each day in the window, sorted by time spent on that day.
     *
     * Populated for local stats (`THIS_DEVICE`); empty on cross-device
     * merged snapshots where the server only supplies whole-window per-work
     * totals rather than a day-by-work matrix.
     */
    val booksByDay: Map<LocalDate, List<BookReadingStats>> = emptyMap(),
) {
    val isEmpty: Boolean get() = totalMs <= 0 && books.isEmpty()

    /**
     * Books read within `[from..to]` inclusive, sorted by time spent in
     * that span.
     */
    fun booksIn(from: LocalDate, to: LocalDate): List<BookReadingStats> {
        if (booksByDay.isEmpty() || to.isBefore(from)) return emptyList()
        if (from == to) return booksByDay[from].orEmpty()
        return booksByDay.entries
            .asSequence()
            .filter { (date, _) -> !date.isBefore(from) && !date.isAfter(to) }
            .flatMap { (_, dayBooks) -> dayBooks.asSequence() }
            .groupBy { it.key }
            .values
            .map { rows ->
                val latest = rows.maxBy { it.lastReadAt }
                latest.copy(
                    totalMs = rows.sumOf { it.totalMs },
                    pendingMs = rows.sumOf { it.pendingMs },
                    lastReadAt = latest.lastReadAt,
                    firstReadAt = rows.mapNotNull { it.firstReadAt }.minOrNull(),
                    sessions = rows.sumOf { it.sessions },
                    pendingSessions = rows.sumOf { it.pendingSessions },
                )
            }
            .sortedWith(compareByDescending<BookReadingStats> { it.totalMs }.thenBy { it.title })
    }

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
     * This is acknowledgement bookkeeping only. A lost reply can leave
     * an accepted sitting unacknowledged, and a cached server snapshot
     * can predate a successful upload. Exact merges use transmission
     * evidence rather than this flag.
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
    val coverPath: String? = null,
    val coverUrl: String? = null,
    /** The saved catalog [coverUrl] comes from, whose login fetches it. */
    val browseServerId: Long? = null,
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
 *
 * [weekStart] is which day the reader's calendar begins on, and only
 * [StatsRange.THIS_WEEK] consults it. It defaults to the ISO Monday so
 * that a caller with no locale to hand still gets a defined week rather
 * than whichever one the JVM was started in.
 */
fun readingStats(
    sessions: List<SessionSpan>,
    books: Map<String, StatsBook>,
    zone: ZoneId,
    today: LocalDate,
    range: StatsRange = StatsRange.Default,
    weekStart: DayOfWeek = WeekFields.ISO.firstDayOfWeek,
): ReadingStats {
    val recorded = sessions.filter { it.durationMs > 0 }
    // The streak is answered from everything, before the window is
    // applied, so that asking about the last week cannot report a
    // months-long run as seven days.
    val streak = streakDays(recorded, zone, today)
    // First-read dates are likewise answered from everything: when a
    // book was begun is a fact about the book, not about the window.
    val firstStarts = recorded.groupBy { it.bookUrl }
        .mapValues { (_, spans) -> spans.minOf { it.startedAt } }
    val start = range.startDate(today, weekStart)
    // Per-day book breakdowns cover the union of the selected range and
    // the recent 28-day consistency window so tapping any day in either
    // chart can show its books without building breakdowns for older history
    // when a bounded range is selected.
    val breakdownStart = start?.let { minOf(it, today.minusDays(27)) }
    val booksByDay = recorded
        .filter { session ->
            val day = session.day(zone)
            !day.isAfter(today) && (breakdownStart == null || !day.isBefore(breakdownStart))
        }
        .groupBy { it.day(zone) }
        .mapValues { (_, daySpans) ->
            booksFromSpans(daySpans, books, firstStarts, zone)
        }
    // Bounded at both ends, and the upper bound matters as much as the
    // lower one. A session dated after today — a clock corrected
    // backwards, an imported row, a flight east — would otherwise land
    // in the total while `dailySeries`, which stops at today, left it
    // off the chart, and while `readingTotals` left it out of the
    // comparison this total is measured against.
    val counted = recorded.filter { session ->
        val day = session.day(zone)
        !day.isAfter(today) && (start == null || !day.isBefore(start))
    }
    if (counted.isEmpty()) return ReadingStats.Empty.copy(
        streakDays = streak,
        recent = dailySeries(emptyList(), zone, today, range, weekStart),
        booksByDay = booksByDay,
    )

    val stats = booksFromSpans(counted, books, firstStarts, zone)

    return ReadingStats(
        totalMs = stats.sumOf { it.totalMs },
        pendingMs = stats.sumOf { it.pendingMs },
        booksRead = stats.size,
        booksFinished = stats.count { it.finished },
        books = stats,
        recent = dailySeries(counted, zone, today, range, weekStart),
        sessions = stats.sumOf { it.sessions },
        pendingSessions = stats.sumOf { it.pendingSessions },
        longestSessionMs = longestSittingMs(counted, zone),
        streakDays = streak,
        progressionPerHour = progressionPerHour(counted),
        booksByDay = booksByDay,
    )
}

private fun booksFromSpans(
    spans: List<SessionSpan>,
    books: Map<String, StatsBook>,
    firstStarts: Map<String, Long>,
    zone: ZoneId,
): List<BookReadingStats> = spans.groupBy { it.bookUrl }.map { (url, bookSpans) ->
    val book = books[url]
    val sittings = sittings(bookSpans, zone)
    BookReadingStats(
        bookUrl = url,
        // A book opened directly through Android may never have had a
        // library row. Its URL is still a more honest label than
        // dropping time that was genuinely recorded.
        title = book?.title ?: url.substringAfterLast('/'),
        author = book?.author,
        totalMs = bookSpans.sumOf { it.durationMs },
        pendingMs = bookSpans.filterNot { it.uploaded }.sumOf { it.durationMs },
        lastReadAt = bookSpans.maxOf { it.lastReadAt },
        firstReadAt = firstStarts[url],
        progression = book?.progression,
        finished = book?.finished == true,
        sessions = sittings.total,
        pendingSessions = sittings.pending,
        coverPath = book?.coverPath,
        coverUrl = book?.coverUrl,
        browseServerId = book?.browseServerId,
    )
}.sortedWith(compareByDescending<BookReadingStats> { it.totalMs }.thenBy { it.title })

/** The pause that ends a sitting; liseur-sync counts with the same one. */
internal const val SITTING_GAP_MS = 5 * 60_000L

private data class Sittings(val total: Int, val pending: Int)

/**
 * How many sittings one book's [spans] make, and how many of those no
 * server has any part of.
 *
 * The reader records a new session every time it is paused, so locking
 * the screen would otherwise turn an evening into dozens of sittings.
 * Sessions on the same day belong to one sitting while each starts less
 * than [SITTING_GAP_MS] after the ones before it ended. A sitting never
 * crosses midnight, which is what lets this count be subtracted from and
 * added to the server's, which applies the same rule day by day.
 */
private fun sittings(spans: List<SessionSpan>, zone: ZoneId): Sittings {
    var total = 0
    var pending = 0
    for (day in spans.groupBy { it.day(zone) }.values) {
        var end = 0L
        var unsent = true
        day.sortedWith(compareBy({ it.startedAt }, { it.lastReadAt })).forEachIndexed { index, span ->
            if (index == 0 || span.startedAt - end >= SITTING_GAP_MS) {
                if (index > 0 && unsent) pending++
                total++
                unsent = true
                end = span.lastReadAt
            } else {
                end = maxOf(end, span.lastReadAt)
            }
            if (span.uploaded) unsent = false
        }
        if (unsent) pending++
    }
    return Sittings(total, pending)
}

/**
 * The longest single sitting across [spans], grouped by book and day
 * using the same [SITTING_GAP_MS] rule as [sittings].
 */
internal fun longestSittingMs(spans: List<SessionSpan>, zone: ZoneId): Long {
    var longest = 0L
    for (bookSpans in spans.groupBy { it.bookUrl }.values) {
        for (day in bookSpans.groupBy { it.day(zone) }.values) {
            var end = 0L
            var sittingMs = 0L
            day.sortedWith(compareBy({ it.startedAt }, { it.lastReadAt }))
                .forEachIndexed { index, span ->
                    if (index == 0 || span.startedAt - end >= SITTING_GAP_MS) {
                        if (sittingMs > longest) longest = sittingMs
                        sittingMs = span.durationMs
                        end = span.lastReadAt
                    } else {
                        sittingMs += span.durationMs
                        end = maxOf(end, span.lastReadAt)
                    }
                }
            if (sittingMs > longest) longest = sittingMs
        }
    }
    return longest
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
internal fun dailySeries(
    sessions: List<SessionSpan>,
    zone: ZoneId,
    today: LocalDate,
    range: StatsRange,
    weekStart: DayOfWeek,
): List<ReadingDay> {
    val byDay = sessions.groupBy { it.day(zone) }
    val start = range.startDate(today, weekStart)
        ?: byDay.keys.minOrNull()
        ?: today.minusDays((RECENT_DAYS - 1).toLong())
    return dailyWindowSeries(sessions, zone, start, today)
}

/**
 * The days in `[start..today]` inclusive, including empty ones.
 */
internal fun dailyWindowSeries(
    sessions: List<SessionSpan>,
    zone: ZoneId,
    start: LocalDate,
    today: LocalDate,
): List<ReadingDay> {
    val span = ChronoUnit.DAYS.between(start, today)
    if (span < 0) return emptyList()
    val byDay = sessions.filter { it.durationMs > 0 }.groupBy { it.day(zone) }
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
    return activeDayStreak(sessions.mapTo(mutableSetOf()) { it.day(zone) }, today)
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
internal fun progressionPerHour(sessions: List<SessionSpan>): Double? {
    var advance = 0.0
    var millis = 0L
    for (span in sessions) {
        val from = span.startProgression ?: continue
        val to = span.endProgression ?: continue
        if (!from.isFinite() || !to.isFinite()) continue
        millis += span.durationMs
        val delta = to - from
        if (delta > 0) advance += delta
    }
    if (advance <= 0 || millis <= 0) return null
    return advance / (millis.toDouble() / MILLIS_PER_HOUR)
}

private const val MILLIS_PER_HOUR = 3_600_000.0

internal fun SessionSpan.day(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(lastReadAt).atZone(zone).toLocalDate()

/** Reading time over one span, and how much of it no server has yet. */
data class SpanTotals(val totalMs: Long, val pendingMs: Long) {
    companion object {
        val Empty = SpanTotals(0, 0)
    }
}

/**
 * What [sessions] add up to over [span], on both ends inclusive.
 *
 * The bounded sibling of [readingStats], for the two halves of a
 * period-over-period comparison. It deliberately shares the same
 * "a sitting of no length is not a sitting" filter: a comparison whose
 * halves disagreed about what counted would report a difference the
 * reader did not make.
 *
 * [through] stops the count part-way through the span's last day, which
 * is what makes a finished period comparable with one that has only
 * reached this afternoon. Every earlier day in the span is counted
 * whole, whatever hour its reading happened at.
 *
 * The time of day is resolved to a moment on that last day rather than
 * compared as a wall clock, so that the two weekends a year the clocks
 * move are answered rather than ignored. Where the hour is struck twice
 * it is the first of them; where it does not exist at all it is moved on
 * by the length of the hour that was skipped. Either way there is exactly
 * one cutoff, on the day being counted, and reading is measured against
 * the same instants it was recorded at.
 *
 * A sitting still running at that cutoff is counted for the share of its
 * length that had elapsed. This is the one place a sitting is divided,
 * and it is divided because the sitting it is being compared with — the
 * one open on the reader's screen right now — is already only recorded
 * as far as its last checkpoint. Dropping the older one whole, or
 * keeping it whole, would compare an afternoon with something else.
 *
 * Midnight is not such a cutoff. With no time of day named the span runs
 * to the end of its last day, and a sitting that ran past that midnight
 * belongs whole to the day it ended on, exactly as it does in the
 * headline above and on liseur-sync. Splitting it here would make the
 * two halves of a comparison disagree with the total over them.
 *
 * When a time of day *is* named, the span is an interval between two
 * moments rather than a run of whole days, and the day a sitting is
 * dated to stops deciding the matter. A sitting begun before the cutoff
 * and still running at it counts for its elapsed part whether it went on
 * to end that evening or past the following midnight. That is the point
 * rather than an oversight: the sitting on the other side of the
 * comparison is the one open on the reader's screen at this moment, and
 * it will not be dated to any day until they put the book down. Dropping
 * the older one for having ended on the Wednesday would measure a reader
 * mid-chapter against nothing at all.
 */
fun readingTotals(
    sessions: List<SessionSpan>,
    zone: ZoneId,
    span: DateSpan,
    through: LocalTime = LocalTime.MAX,
): SpanTotals {
    val from = span.from.atStartOfDay(zone).toInstant().toEpochMilli()
    val cutoff = span.to.atTime(through).atZone(zone).toInstant().toEpochMilli()
    val cutShort = through != LocalTime.MAX
    var total = 0L
    var pending = 0L
    for (session in sessions) {
        if (session.durationMs <= 0) continue
        if (session.lastReadAt < from) continue
        val counted =
            if (cutShort) session.countedBy(cutoff)
            else if (session.lastReadAt <= cutoff) session.durationMs
            else 0
        if (counted <= 0) continue
        total += counted
        if (!session.uploaded) pending += counted
    }
    return SpanTotals(total, pending)
}

/**
 * How much of a sitting had happened by [cutoff].
 *
 * Whole when it had already ended, nothing when it had not yet begun,
 * and otherwise the share of its length that the wall clock says had
 * gone by. The length is active time from a monotonic clock and the
 * extent is wall-clock, so the share is an estimate; it is a far closer
 * one than nought or all of it.
 */
private fun SessionSpan.countedBy(cutoff: Long): Long {
    if (lastReadAt <= cutoff) return durationMs
    if (startedAt >= cutoff) return 0
    val extent = lastReadAt - startedAt
    if (extent <= 0) return 0
    return (durationMs.toDouble() * (cutoff - startedAt) / extent).roundToLong()
}

/** Which way a period went against the one before it. */
enum class ComparisonDirection { MORE, LESS, SAME }

enum class ComparisonScope { THIS_DEVICE, ALL_DEVICES }

/**
 * How this period's reading compares with the last one's.
 *
 * [percent] is null when there is no honest percentage to give: a
 * baseline of nothing has no denominator, and inventing one would print
 * an infinity or a number in the millions the first time a reader picks
 * the app up again.
 */
data class ReadingComparison(
    val period: ComparisonPeriod,
    val direction: ComparisonDirection,
    val percent: Int?,
    val scope: ComparisonScope = ComparisonScope.THIS_DEVICE,
)

/**
 * Compares two totals, without ever dividing by nothing.
 *
 * Equal to the nearest whole percent reads as the same. "0% more than
 * last week" is a sentence that says nothing twice, and the rounding
 * that produced it is not something the reader can see.
 *
 * The arithmetic is done in [Double] from the start. Subtracting two
 * [Long]s and taking the absolute value can overflow, and
 * `abs(Long.MIN_VALUE)` is still negative; neither is reachable from
 * real sessions, but this is pure and total and should not depend on
 * its callers to keep it so. The clamp is written out for the same
 * reason, rather than left to `roundToInt`'s saturation.
 */
fun compareReading(
    period: ComparisonPeriod,
    currentMs: Long,
    previousMs: Long,
): ReadingComparison {
    val current = currentMs.coerceAtLeast(0L).toDouble()
    val previous = previousMs.coerceAtLeast(0L).toDouble()
    if (previous <= 0.0) {
        return ReadingComparison(
            period = period,
            direction = if (current > 0.0) ComparisonDirection.MORE else ComparisonDirection.SAME,
            percent = null,
        )
    }
    val percent = (abs(current - previous) / previous * 100.0)
        .takeIf { it.isFinite() }
        ?.coerceAtMost(Int.MAX_VALUE.toDouble())
        ?.roundToInt()
    val direction = when {
        percent == null || percent == 0 -> ComparisonDirection.SAME
        current > previous -> ComparisonDirection.MORE
        else -> ComparisonDirection.LESS
    }
    return ReadingComparison(
        period = period,
        direction = direction,
        percent = percent.takeIf { direction != ComparisonDirection.SAME },
    )
}
