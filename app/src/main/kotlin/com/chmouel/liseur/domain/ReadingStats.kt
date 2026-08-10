package com.chmouel.liseur.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What one book's reading adds up to. */
data class BookReadingStats(
    val bookUrl: String,
    val title: String,
    val author: String?,
    val totalMs: Long,
    val lastReadAt: Long,
    /** Where the reader is in it, if known. */
    val progression: Double?,
    val finished: Boolean,
)

/** How much was read on one day. */
data class ReadingDay(val date: LocalDate, val totalMs: Long)

/** Everything the dashboard shows. */
data class ReadingStats(
    val totalMs: Long,
    val booksRead: Int,
    val booksFinished: Int,
    val books: List<BookReadingStats>,
    val recent: List<ReadingDay>,
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
)

/** What is known about a book, from everywhere that is not the sessions. */
data class StatsBook(
    val bookUrl: String,
    val title: String,
    val author: String?,
    val progression: Double?,
    val finished: Boolean,
)

/** How many days of history the dashboard shows day by day. */
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
 */
fun readingStats(
    sessions: List<SessionSpan>,
    books: Map<String, StatsBook>,
    zone: ZoneId,
    today: LocalDate,
): ReadingStats {
    val counted = sessions.filter { it.durationMs > 0 }
    if (counted.isEmpty()) return ReadingStats.Empty

    val perBook = counted.groupBy { it.bookUrl }
    val stats = perBook.map { (url, spans) ->
        val book = books[url]
        BookReadingStats(
            bookUrl = url,
            // A book opened directly through Android may never have had a
            // library row. Its URL is still a more honest label than
            // dropping time that was genuinely recorded.
            title = book?.title ?: url.substringAfterLast('/'),
            author = book?.author,
            totalMs = spans.sumOf { it.durationMs },
            lastReadAt = spans.maxOf { it.lastReadAt },
            progression = book?.progression,
            finished = book?.finished == true,
        )
    }.sortedWith(compareByDescending<BookReadingStats> { it.totalMs }.thenBy { it.title })

    return ReadingStats(
        totalMs = stats.sumOf { it.totalMs },
        booksRead = stats.size,
        booksFinished = stats.count { it.finished },
        books = stats,
        recent = recentDays(counted, zone, today),
    )
}

/**
 * The last [RECENT_DAYS] days, including the ones with nothing on them.
 *
 * Empty days are kept deliberately: a week with two gaps in it is the
 * information, and a list that silently skipped them would read as an
 * unbroken run.
 *
 * A session that runs past midnight is counted on the day it began.
 * Splitting it is more truthful and much more code, and nobody reading
 * at one in the morning thinks of it as tomorrow.
 */
private fun recentDays(
    sessions: List<SessionSpan>,
    zone: ZoneId,
    today: LocalDate,
): List<ReadingDay> {
    val byDay = sessions.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
    return (RECENT_DAYS - 1 downTo 0).map { back ->
        val date = today.minusDays(back.toLong())
        ReadingDay(date, byDay[date]?.sumOf { it.durationMs } ?: 0)
    }
}
