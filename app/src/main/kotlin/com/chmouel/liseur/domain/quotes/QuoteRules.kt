package com.chmouel.liseur.domain.quotes

import java.security.MessageDigest
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
     * Where a locator says it is: the resource, and how far into it.
     *
     * The resource-relative fraction is the one measurement two clients
     * take the same way, because it is a fraction of one file. The
     * whole-book fractions are not: each app interpolates its own
     * position list, and they disagree
     * ([com.chmouel.liseur.reader.progress.ResourceAnchor]).
     */
    data class PassagePlace(val resource: String, val progression: Double)

    /**
     * A short, stable name for [value].
     *
     * Used for what Home remembers about the line it is showing. The
     * text and the book's address must not be written into the store
     * that holds it: `files/datastore` is carried by Android's backup,
     * and a quote is the one thing here that is meant to stay on the
     * device.
     */
    fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * Whether a mark at [progression] is inside what the reader has
     * read, [readPoint].
     *
     * A progression nobody wrote down is not evidence of having read
     * there, so it is refused rather than waved through: the cost of a
     * wrong yes is a passage the reader has not reached.
     */
    fun withinCeiling(progression: Double?, readPoint: Double): Boolean =
        progression != null && progression.isFinite() && progression <= readPoint + SLACK

    /**
     * Whether a mark is inside what the reader has read, said in terms
     * that survive a mark arriving from another device.
     *
     * [mark] and [place] are where the mark and the furthest place the
     * reader reached are — not the place they are on now, which can be
     * behind it. When the two name the same resource both fractions are
     * of the same file and are compared directly: a whole-book fraction
     * written elsewhere is on that device's scale, and could otherwise
     * sit on the wrong side of the ceiling by a few pages. When they do
     * not, the whole-book fractions are all there is.
     *
     * A caller with no chapter to speak of — a book marked read, whose
     * whole-book ceiling already covers all of it — passes no place.
     */
    fun markWithinCeiling(
        mark: PassagePlace?,
        markTotalProgression: Double?,
        place: PassagePlace?,
        readPoint: Double,
    ): Boolean {
        if (mark != null && place != null && mark.resource == place.resource) {
            return mark.progression.isFinite() && mark.progression <= place.progression + SLACK
        }
        return withinCeiling(markTotalProgression, readPoint)
    }

    /**
     * Whether a cached paragraph is inside what the reader has read.
     *
     * A mark names where a passage opens, but a cached line is a whole
     * paragraph: [start] is where it opens, in the scale two clients
     * share, and [end] is its whole-book fraction, which is where it
     * closes. A paragraph that opens before the ceiling and closes after
     * it is not inside it.
     */
    fun lineWithinCeiling(
        start: PassagePlace?,
        end: Double?,
        place: PassagePlace?,
        readPoint: Double,
    ): Boolean {
        if (!withinCeiling(end, readPoint)) return false
        if (start != null && place != null && start.resource == place.resource) {
            return start.progression.isFinite() && start.progression <= place.progression + SLACK
        }
        return true
    }

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
