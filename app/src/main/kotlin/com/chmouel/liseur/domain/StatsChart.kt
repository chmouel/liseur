package com.chmouel.liseur.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** One bar in the reading activity chart. */
data class ReadingPeriod(
    val from: LocalDate,
    val to: LocalDate,
    val totalMs: Long,
)

/**
 * Adds daily reading into the calendar unit that suits [range].
 *
 * The first and last week of a month are clipped to that month. This
 * keeps every bar inside the range the headline describes while still
 * respecting the first day of the reader's local calendar week.
 */
fun readingPeriods(
    days: List<ReadingDay>,
    range: StatsRange,
    weekStart: DayOfWeek,
): List<ReadingPeriod> {
    if (days.isEmpty()) return emptyList()
    if (range == StatsRange.THIS_WEEK) {
        val start = days.first().date.with(TemporalAdjusters.previousOrSame(weekStart))
        val byDate = days.associateBy { it.date }
        return (0L..6L).map { offset ->
            val date = start.plusDays(offset)
            ReadingPeriod(
                from = date,
                to = date,
                totalMs = byDate[date]?.totalMs ?: 0L,
            )
        }
    }
    return days
        .groupBy { day ->
            when (range.chartPeriod) {
                StatsChartPeriod.DAY -> day.date
                StatsChartPeriod.WEEK -> day.date.with(TemporalAdjusters.previousOrSame(weekStart))
                StatsChartPeriod.MONTH -> day.date.withDayOfMonth(1)
                StatsChartPeriod.YEAR -> day.date.withDayOfYear(1)
            }
        }
        .values
        .map { period ->
            ReadingPeriod(
                from = period.first().date,
                to = period.last().date,
                totalMs = period.sumOf { it.totalMs },
            )
        }
}
