package com.chmouel.liseur.domain

import java.time.DayOfWeek
import java.time.LocalDate
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
    LAST_30_DAYS("30d"),
    LAST_90_DAYS("90d"),
    LAST_YEAR("365d"),
    THIS_YEAR("this_year"),
    ALL_TIME("all"),
    ;

    /**
     * The first day counted, or null for a span with no beginning.
     *
     * "This week" runs from the most recent [weekStart] to today, so it
     * holds one to seven days and grows through the week. A rolling
     * seven days would be a longer span more of the time, but it begins
     * on a different weekday every morning, which makes the chart
     * impossible to compare with the one the reader saw yesterday and
     * puts the same weekday at both ends of it.
     *
     * "This year" is resolved against [today] rather than a day count,
     * because the first of January is a different distance away every
     * time it is asked, and a fixed 365 would call last December part of
     * this year for most of the spring.
     */
    fun startDate(today: LocalDate, weekStart: DayOfWeek): LocalDate? = when (this) {
        THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(weekStart))
        LAST_30_DAYS -> today.minusDays(29)
        LAST_90_DAYS -> today.minusDays(89)
        LAST_YEAR -> today.minusDays(364)
        THIS_YEAR -> today.withDayOfYear(1)
        ALL_TIME -> null
    }

    /**
     * How many days the span covers on [today], or null for all time.
     *
     * Used for the "in the last N days" caption and to decide how the
     * day-by-day chart is drawn, so it counts both endpoints: a seven
     * day range is seven bars, not six.
     */
    fun days(today: LocalDate, weekStart: DayOfWeek): Int? =
        startDate(today, weekStart)?.let {
            java.time.temporal.ChronoUnit.DAYS.between(it, today).toInt() + 1
        }

    /**
     * Whether the span is short enough to read as a row of daily bars.
     *
     * Past this a bar per day is a picket fence nobody can read a
     * weekday off, and the heatmap takes over.
     */
    fun suitsDailyBars(today: LocalDate, weekStart: DayOfWeek): Boolean =
        (days(today, weekStart) ?: Int.MAX_VALUE) <= MAX_BAR_DAYS

    companion object {
        val Default = THIS_WEEK

        /** As many bars as fit across a phone without becoming hatching. */
        const val MAX_BAR_DAYS = 31

        fun fromId(id: String?): StatsRange = entries.firstOrNull { it.id == id } ?: Default
    }
}
