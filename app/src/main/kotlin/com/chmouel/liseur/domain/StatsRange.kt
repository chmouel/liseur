package com.chmouel.liseur.domain

import java.time.LocalDate

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
    LAST_7_DAYS("7d"),
    LAST_30_DAYS("30d"),
    LAST_90_DAYS("90d"),
    LAST_YEAR("365d"),
    THIS_YEAR("this_year"),
    ALL_TIME("all"),
    ;

    /**
     * The first day counted, or null for a span with no beginning.
     *
     * "This year" is resolved against [today] rather than a day count,
     * because the first of January is a different distance away every
     * time it is asked, and a fixed 365 would call last December part of
     * this year for most of the spring.
     */
    fun startDate(today: LocalDate): LocalDate? = when (this) {
        LAST_7_DAYS -> today.minusDays(6)
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
    fun days(today: LocalDate): Int? =
        startDate(today)?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today).toInt() + 1 }

    /**
     * Whether the span is short enough to read as a row of daily bars.
     *
     * Past this a bar per day is a picket fence nobody can read a
     * weekday off, and the heatmap takes over.
     */
    fun suitsDailyBars(today: LocalDate): Boolean = (days(today) ?: Int.MAX_VALUE) <= MAX_BAR_DAYS

    companion object {
        val Default = LAST_30_DAYS

        /** As many bars as fit across a phone without becoming hatching. */
        const val MAX_BAR_DAYS = 31

        fun fromId(id: String?): StatsRange = entries.firstOrNull { it.id == id } ?: Default
    }
}
