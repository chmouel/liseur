package com.chmouel.liseur.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Which day [locale] considers a week to begin on.
 *
 * Spelled once, because a screen that starts its week on Monday beside
 * a chart that starts it on Sunday is not a formatting difference, it
 * is two different weeks.
 */
fun localeWeekStart(locale: Locale): DayOfWeek = WeekFields.of(locale).firstDayOfWeek

/**
 * How far back the reading dashboard is looking.
 *
 * One type for both halves of the screen. The local sums and the sync
 * server's aggregates are asked the same question in the same words, so
 * the headline, the bars, the heatmap and the by-book rows all describe
 * one span instead of quietly describing several — which is what
 * happened while the server was pinned to thirty days and the book list
 * to a lifetime.
 *
 * [id] is what gets written to DataStore and must not change once
 * released. The sync server is asked for the same span by naming its
 * first and last day, rather than a count of days: a count would be
 * resolved against the moment of the request and reach back into a
 * further partial day, and a headline that covered one more evening
 * than the list beneath it could not be explained to anybody.
 */
enum class StatsRange(val id: String) {
    THIS_WEEK("7d"),
    THIS_MONTH("this_month"),
    THIS_YEAR("this_year"),
    ALL_TIME("all"),
    ;

    /**
     * The first day counted, or null for a span with no beginning.
     *
     * Every span here is calendar-aligned and ends today, which is what
     * lets each of them be compared with the same elapsed portion of the
     * period before it. A rolling window has no previous calendar period
     * to name: "the thirty days before these thirty" is not last month,
     * and both its endpoints move every morning, so the chart cannot be
     * compared with the one the reader saw yesterday.
     *
     * "This week" runs from the most recent [weekStart] to today, so it
     * holds one to seven days and grows through the week. "This month"
     * and "this year" are resolved against [today] rather than a day
     * count, because the first of the month and the first of January are
     * a different distance away every time they are asked, and a fixed
     * 365 would call last December part of this year for most of the
     * spring.
     */
    fun startDate(today: LocalDate, weekStart: DayOfWeek): LocalDate? = when (this) {
        THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(weekStart))
        THIS_MONTH -> today.withDayOfMonth(1)
        THIS_YEAR -> today.withDayOfYear(1)
        ALL_TIME -> null
    }

    /**
     * How many days the span covers on [today], or null for all time.
     *
     * Counts both endpoints, so the first day of a period is a period of
     * one day rather than of none, and a whole week is seven bars.
     */
    fun days(today: LocalDate, weekStart: DayOfWeek): Int? =
        startDate(today, weekStart)?.let {
            ChronoUnit.DAYS.between(it, today).toInt() + 1
        }

    /**
     * Whether the span is short enough to read as a row of daily bars.
     *
     * Past this a bar per day is a picket fence nobody can read a
     * weekday off, and the heatmap takes over. A week is always bars and
     * a year never is; a month is bars right up to its thirty-first day,
     * which is what [MAX_BAR_DAYS] is sized for.
     */
    fun suitsDailyBars(today: LocalDate, weekStart: DayOfWeek): Boolean =
        (days(today, weekStart) ?: Int.MAX_VALUE) <= MAX_BAR_DAYS

    /**
     * This span and the matching elapsed portion of the period before it.
     *
     * Null for a span with no previous period to name, which is only
     * [ALL_TIME] — and would be any rolling window, were one added back.
     *
     * The baseline is deliberately partial. Comparing the first Wednesday
     * of a month with the whole of the month before it would report every
     * reader as falling behind on the second of every month, so the
     * baseline stops at the same point in its own period that today is at
     * in this one.
     */
    fun comparison(today: LocalDate, weekStart: DayOfWeek): ComparisonSpans? {
        val period = when (this) {
            THIS_WEEK -> ComparisonPeriod.WEEK
            THIS_MONTH -> ComparisonPeriod.MONTH
            THIS_YEAR -> ComparisonPeriod.YEAR
            ALL_TIME -> return null
        }
        val from = startDate(today, weekStart) ?: return null
        val previous = when (period) {
            // Exactly seven days back on both ends, so the same weekdays
            // are compared whichever day the reader's calendar starts on.
            ComparisonPeriod.WEEK -> DateSpan(from.minusWeeks(1), today.minusWeeks(1))
            ComparisonPeriod.MONTH -> {
                val first = from.minusMonths(1)
                // The 31st of March has no counterpart in February. Ending
                // on the last day the previous month has is the closest
                // thing to the same elapsed portion of it; the alternative
                // is to show nothing on the three or four days a year when
                // the reader most has something to compare.
                val day = minOf(today.dayOfMonth, first.lengthOfMonth())
                DateSpan(first, first.withDayOfMonth(day))
            }
            // minusYears already clamps the 29th of February to the 28th.
            ComparisonPeriod.YEAR -> DateSpan(from.minusYears(1), today.minusYears(1))
        }
        return ComparisonSpans(
            period = period,
            current = DateSpan(from, today),
            previous = previous,
        )
    }

    companion object {
        val Default = THIS_WEEK

        /** As many bars as fit across a phone without becoming hatching. */
        const val MAX_BAR_DAYS = 31

        /**
         * Spans that were once offered and are still on readers' disks.
         *
         * Resolved to the nearest surviving span rather than falling
         * through to [Default]: someone who had asked for a year of
         * history should not silently be shown this week's because the
         * rolling windows were retired. Ninety days has no quarter to
         * land on, and the year is the only survivor that does not
         * shrink what they were looking at.
         *
         * These ids can never be selected again — they are not entries —
         * but they must stay resolvable for as long as any installation
         * might still hold one.
         */
        private val RETIRED = mapOf(
            "30d" to THIS_MONTH,
            "90d" to THIS_YEAR,
            "365d" to THIS_YEAR,
        )

        fun fromId(id: String?): StatsRange =
            entries.firstOrNull { it.id == id } ?: RETIRED[id] ?: Default
    }
}

/** A span of days, counted from [from] to [to] with both included. */
data class DateSpan(val from: LocalDate, val to: LocalDate)

/** Which calendar period a comparison is against. */
enum class ComparisonPeriod { WEEK, MONTH, YEAR }

/**
 * A span and the one it is measured against.
 *
 * A value rather than a pair of dates worked out where they are needed,
 * so that the month-end and leap-year rules are written once and can be
 * tested without a screen or an emulator.
 */
data class ComparisonSpans(
    val period: ComparisonPeriod,
    val current: DateSpan,
    val previous: DateSpan,
)
