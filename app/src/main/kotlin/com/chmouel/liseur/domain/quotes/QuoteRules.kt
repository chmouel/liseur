package com.chmouel.liseur.domain.quotes

import java.time.LocalDate
import java.time.ZoneId

/**
 * The rules the quote cache obeys, kept pure so they can be asked
 * without a database, a book or a clock.
 */
object QuoteRules {
    /**
     * How much a written-down progression may overshoot the read point
     * and still count as read. Two writers of the same fraction differ
     * in the last bits; a page's worth of difference is a spoiler.
     */
    const val SLACK = 1e-6

    /** Shortest wait between asks what day it is. */
    private const val MIN_ROLLOVER_MS = 60_000L

    /**
     * Whether something at [progression] is inside what the reader has
     * read, [readPoint].
     *
     * A progression nobody wrote down is not evidence of having read
     * there, so it is refused rather than waved through: the cost of a
     * wrong yes is a passage the reader has not reached.
     */
    fun withinCeiling(progression: Double?, readPoint: Double): Boolean =
        progression != null && progression.isFinite() && progression <= readPoint + SLACK

    /**
     * Whether a scan recorded under [scanned] still describes the file
     * [current] holds.
     *
     * Both null means neither side could name the work, and the two are
     * then the same claim as far as anything here can tell. One known
     * and one unknown is a difference: a scan that could not name its
     * book cannot be shown to be about this one.
     */
    fun stillSameWork(scanned: String?, current: String?): Boolean = scanned == current

    /**
     * How long to wait before asking what day it is again, in
     * milliseconds.
     *
     * Home can stay subscribed across midnight, and the day is half the
     * seed of the quote. A flow that only moved when a preference was
     * written would keep showing yesterday's line, and reuse yesterday's
     * nonce with it, until Home was rebuilt.
     */
    fun millisUntilTomorrow(today: LocalDate, nowMillis: Long, zone: ZoneId): Long {
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return (midnight - nowMillis).coerceIn(MIN_ROLLOVER_MS, Long.MAX_VALUE)
    }
}
