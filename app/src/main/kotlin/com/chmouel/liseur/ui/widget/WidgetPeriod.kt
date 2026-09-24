package com.chmouel.liseur.ui.widget

import androidx.datastore.preferences.core.stringPreferencesKey
import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.StatsBook
import com.chmouel.liseur.domain.StatsRange
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
) {
    val peakMs: Long get() = bars.maxOfOrNull { it.totalMs } ?: 0L

    /** Only the day view singles today out; the others show the period as a whole. */
    val highlightsToday: Boolean get() = period == WidgetPeriod.DAY
}

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
 */
fun periodStats(
    sessions: List<SessionSpan>,
    books: Map<String, StatsBook>,
    zone: ZoneId,
    today: LocalDate,
    weekStart: DayOfWeek,
    period: WidgetPeriod,
): PeriodStats {
    val range = if (period == WidgetPeriod.MONTH) StatsRange.THIS_MONTH else StatsRange.THIS_WEEK
    val stats = readingStats(sessions, books, zone, today, range, weekStart)
    return when (period) {
        WidgetPeriod.DAY -> {
            // Matches readingStats: a sitting counts on the day it was last read.
            val byDay = sessions.filter { it.durationMs > 0 }
                .groupBy { Instant.ofEpochMilli(it.lastReadAt).atZone(zone).toLocalDate() }
            val todays = byDay[today].orEmpty()
            PeriodStats(
                period = period,
                totalMs = todays.sumOf { it.durationMs },
                booksRead = todays.map { it.bookUrl }.distinct().size,
                sessions = todays.size,
                streakDays = stats.streakDays,
                bars = (DAY_BARS - 1 downTo 0).map { back ->
                    val date = today.minusDays(back.toLong())
                    WidgetBar(date, byDay[date].orEmpty().sumOf { it.durationMs })
                },
                today = today,
            )
        }
        WidgetPeriod.WEEK, WidgetPeriod.MONTH -> {
            val (start, length) = if (period == WidgetPeriod.WEEK) {
                today.with(TemporalAdjusters.previousOrSame(weekStart)) to 7
            } else {
                today.withDayOfMonth(1) to today.lengthOfMonth()
            }
            val recorded = stats.recent.associate { it.date to it.totalMs }
            PeriodStats(
                period = period,
                totalMs = stats.totalMs,
                booksRead = stats.booksRead,
                sessions = stats.sessions,
                streakDays = stats.streakDays,
                bars = (0 until length).map { forward ->
                    val date = start.plusDays(forward.toLong())
                    WidgetBar(date, recorded[date] ?: 0L)
                },
                today = today,
            )
        }
    }
}

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
