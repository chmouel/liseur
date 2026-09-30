package com.chmouel.liseur.ui.widget

import androidx.datastore.preferences.core.stringPreferencesKey
import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.StatsBook
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.domain.activeDayStreak
import com.chmouel.liseur.domain.readingStats
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The span a stats widget covers, chosen per widget.
 *
 * [id] is written to the widget's Glance state and must not change once
 * released.
 */
enum class WidgetPeriod(val id: String) {
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    ;

    companion object {
        val Default = DAY

        fun fromId(id: String?): WidgetPeriod = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** Where a stats widget keeps its [WidgetPeriod]. */
internal val WidgetPeriodKey = stringPreferencesKey("stats_period")

data class WidgetBar(val date: LocalDate, val totalMs: Long)

/** The figures a stats widget shows for one [WidgetPeriod]. */
data class PeriodStats(
    val period: WidgetPeriod,
    val totalMs: Long,
    val booksRead: Int,
    val sessions: Int,
    val streakDays: Int,
    val bars: List<WidgetBar>,
    val today: LocalDate,
    val remoteCovered: Boolean = false,
    val remoteUpdatedAt: Long? = null,
) {
    val peakMs: Long get() = bars.maxOfOrNull { it.totalMs } ?: 0L

    /** Only the day view singles today out; the others show the period as a whole. */
    val highlightsToday: Boolean get() = period == WidgetPeriod.DAY
}

/**
 * What the last proven liseur-sync snapshot counted on other devices.
 *
 * Saved by the shared refresher, never fetched during rendering. Only rows
 * in the account timezone and for the current account belong here, and
 * none of it includes this device's captured sittings, so it is added to
 * the local figures rather than compared with them.
 */
data class WidgetRemote(
    val zone: ZoneId? = null,
    /** Other-device reading per day; a day that is absent was not covered. */
    val days: Map<LocalDate, Long> = emptyMap(),
    val windows: List<WidgetRemoteWindow> = emptyList(),
    /** This device's book URLs by server work id, to count a book read on both once. */
    val workIdByUrl: Map<String, String> = emptyMap(),
    val refreshedAtByDay: Map<LocalDate, Long> = emptyMap(),
)

/** A week or month snapshot, as the stats screen last proved it. */
data class WidgetRemoteWindow(
    val range: StatsRange,
    val from: LocalDate,
    /** The last day the snapshot covered. */
    val today: LocalDate,
    val sessions: Int,
    val workIds: Set<String>,
    val combinedStreak: Int,
    val refreshedAt: Long = 0,
)

/**
 * Reduces the recorded sessions to what a widget shows for [period].
 *
 * Week and month reuse [readingStats], so the widget agrees with the
 * stats screen. The day view counts today alone but charts the last
 * seven days, since one bar is not a chart. The streak always comes from
 * the whole history, which is why every session is passed in.
 *
 * Week and month bars run to the end of the period, with the days still
 * ahead left empty, so the chart keeps its shape as the period fills.
 *
 * [remote] adds the reading done on other devices. Minutes are added day
 * by day wherever a snapshot covered the day. Sessions and books are
 * added only from a snapshot of the same week or month, since the server
 * does not count them per day; the day view keeps this device's.
 */
fun periodStats(
    sessions: List<SessionSpan>,
    books: Map<String, StatsBook>,
    zone: ZoneId,
    today: LocalDate,
    weekStart: DayOfWeek,
    period: WidgetPeriod,
    remote: WidgetRemote? = null,
): PeriodStats {
    val range = if (period == WidgetPeriod.MONTH) StatsRange.THIS_MONTH else StatsRange.THIS_WEEK
    val stats = readingStats(sessions, books, zone, today, range, weekStart)
    val remoteDays = remote?.days.orEmpty().filterKeys { !it.isAfter(today) }
    val from = when (period) {
        WidgetPeriod.DAY -> today
        WidgetPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(weekStart))
        WidgetPeriod.MONTH -> today.withDayOfMonth(1)
    }
    val dates = generateSequence(from) { it.plusDays(1).takeUnless { date -> date > today } }.toList()
    val covered = dates.all { it in remoteDays }
    val pick = remote?.let { streakWith(sessions, zone, today, stats.streakDays, it, remoteDays) }
    val streak = pick?.days ?: stats.streakDays
    // A streak taken from a window is only as fresh as that window.
    val updatedAt = (dates.mapNotNull { remote?.refreshedAtByDay?.get(it) } + listOfNotNull(pick?.windowRefreshedAt))
        .minOrNull()
    return when (period) {
        WidgetPeriod.DAY -> {
            // Matches readingStats: a sitting counts on the day it was last read.
            val byDay = sessions.filter { it.durationMs > 0 }
                .groupBy { Instant.ofEpochMilli(it.lastReadAt).atZone(zone).toLocalDate() }
            val todays = byDay[today].orEmpty()
            PeriodStats(
                period = period,
                totalMs = todays.sumOf { it.durationMs } + remoteDays[today].orZero(),
                booksRead = todays.map { it.bookUrl }.distinct().size,
                sessions = todays.size,
                streakDays = streak,
                bars = (DAY_BARS - 1 downTo 0).map { back ->
                    val date = today.minusDays(back.toLong())
                    WidgetBar(date, byDay[date].orEmpty().sumOf { it.durationMs } + remoteDays[date].orZero())
                },
                today = today,
                remoteCovered = covered,
                remoteUpdatedAt = updatedAt,
            )
        }
        WidgetPeriod.WEEK, WidgetPeriod.MONTH -> {
            val (start, length) = if (period == WidgetPeriod.WEEK) {
                today.with(TemporalAdjusters.previousOrSame(weekStart)) to 7
            } else {
                today.withDayOfMonth(1) to today.lengthOfMonth()
            }
            val recorded = stats.recent.associate { it.date to it.totalMs }
            val window = remote?.windows?.firstOrNull {
                it.range == range && it.from == start && !it.today.isAfter(today)
            }
            val booksRead = if (window == null) stats.booksRead else {
                val here = stats.books.mapNotNull { it.bookUrl }
                    .mapTo(mutableSetOf()) { url -> remote.workIdByUrl[url] ?: "url:$url" }
                (here + window.workIds).size
            }
            val bars = (0 until length).map { forward ->
                val date = start.plusDays(forward.toLong())
                WidgetBar(date, (recorded[date] ?: 0L) + remoteDays[date].orZero())
            }
            PeriodStats(
                period = period,
                totalMs = stats.totalMs + bars.sumOf { remoteDays[it.date].orZero() },
                booksRead = booksRead,
                sessions = stats.sessions + (window?.sessions ?: 0),
                streakDays = streak,
                bars = bars,
                today = today,
                remoteCovered = covered,
                remoteUpdatedAt = updatedAt,
            )
        }
    }
}

private data class StreakPick(val days: Int, val windowRefreshedAt: Long?)

/**
 * The longest run the reader can be shown: this device's, the one across
 * the days either side read on, or the server's own for today. When the
 * server's wins, its window's refresh time comes along.
 */
private fun streakWith(
    sessions: List<SessionSpan>,
    zone: ZoneId,
    today: LocalDate,
    local: Int,
    remote: WidgetRemote,
    remoteDays: Map<LocalDate, Long>,
): StreakPick {
    val active = sessions.filter { it.durationMs > 0 }
        .mapTo(mutableSetOf()) { Instant.ofEpochMilli(it.lastReadAt).atZone(zone).toLocalDate() }
    remoteDays.filterValues { it > 0 }.keys.forEach { active += it }
    val seen = maxOf(local, activeDayStreak(active, today))
    val server = remote.windows.filter { it.today == today }.maxByOrNull { it.combinedStreak }
    return if (server != null && server.combinedStreak > seen) {
        StreakPick(server.combinedStreak, server.refreshedAt)
    } else {
        StreakPick(seen, null)
    }
}

private fun Long?.orZero(): Long = this ?: 0L

/** A short reading time for a chart label: 45m, 1h, 1h20. */
sealed interface CompactDuration {
    data object UnderMinute : CompactDuration
    data class Minutes(val minutes: Int) : CompactDuration
    data class Hours(val hours: Int, val minutes: Int) : CompactDuration
}

fun compactDuration(millis: Long): CompactDuration {
    val totalMinutes = millis / 60_000L
    return when {
        totalMinutes < 1 -> CompactDuration.UnderMinute
        totalMinutes < 60 -> CompactDuration.Minutes(totalMinutes.toInt())
        else -> CompactDuration.Hours((totalMinutes / 60).toInt(), (totalMinutes % 60).toInt())
    }
}

/**
 * How many bars go in each row of the chart. Glance truncates a container
 * after ten children and only offers equal weights, so the bars are split
 * into at most ten rows of at most [max] bars each, padded with empty slots to keep every bar the
 * same width. The size that needs the least padding wins: a 30-day month
 * becomes five rows of six instead of leaving two empty slots at the end.
 */
fun barChunkSize(count: Int, max: Int = MAX_BAR_CHUNK): Int {
    if (count <= max) return count.coerceAtLeast(1)
    val smallest = (count + MAX_CHILDREN - 1) / MAX_CHILDREN
    return (max downTo smallest.coerceAtMost(max)).minBy { size -> (count + size - 1) / size * size - count }
}

private const val MAX_BAR_CHUNK = 8
private const val MAX_CHILDREN = 10
private const val DAY_BARS = 7
