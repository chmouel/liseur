package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.data.settings.AutoScrollPreference
import kotlin.math.pow

/**
 * The page carrying itself, for a book read by scrolling.
 *
 * Everything here is arithmetic, and deliberately knows nothing about
 * web views, densities or frames: how fast the text should move, how far
 * it has moved since the last time anyone asked. The screen owns the
 * parts that need a device.
 *
 * See `docs/adr/0006-auto-scroll.md`.
 */

/**
 * Turns elapsed time into whole pixels of movement.
 *
 * A view scrolls by whole pixels, and at a comfortable reading pace a
 * frame is worth less than one. Rounding each frame on its own would
 * either stand still forever or run at a pixel a frame whatever the
 * setting, so the fraction left over is kept and spent later: what is
 * asked for over a second is what is delivered over a second.
 *
 * The ticker is not told what a frame is. It is told when it last
 * moved and when it is being asked again, and it counts in nanoseconds
 * throughout, so a frame is never rounded down to a whole millisecond on
 * its way in — sixty of those a second is a page that quietly runs slow.
 * A dropped frame is then nothing more than a longer gap, until the gap
 * stops being a gap at all: a reader who leaves a chapter loading, or
 * whose device thinks about something else for half a second, must not
 * come back to the page having jumped. Past [MAX_STEP_NANOS] the gap is
 * taken as an interruption and only that much of it is paid out.
 */
class AutoScrollTicker {

    private var lastNanos: Long? = null
    private var carried = 0.0

    /**
     * Forgets the time that has passed and the fraction owed.
     *
     * Called whenever the page stops moving — a finger on the screen,
     * the chrome coming up, a chapter turning — so that the first step
     * after it is a step, and not the whole pause paid out at once.
     */
    fun reset() {
        lastNanos = null
        carried = 0.0
    }

    /**
     * How far to move now, in whole pixels, at [pixelsPerSecond].
     *
     * The first call after a [reset] establishes when "now" is and
     * returns zero: there is no elapsed time to convert yet.
     */
    fun step(nowNanos: Long, pixelsPerSecond: Double): Int {
        val last = lastNanos
        lastNanos = nowNanos
        if (last == null) return 0
        // A pace that is not a number would poison the carried fraction
        // for good, and the page would never move again.
        if (!pixelsPerSecond.isFinite()) return 0
        val elapsed = nowNanos - last
        // A clock that went backwards is not elapsed time.
        if (elapsed <= 0L) return 0
        val paid = elapsed.coerceAtMost(MAX_STEP_NANOS)
        carried += pixelsPerSecond * paid / NANOS_PER_SECOND
        val whole = carried.toInt()
        carried -= whole
        return whole
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0

        /**
         * The longest gap still treated as reading rather than as an
         * interruption. Comfortably more than a dropped frame or two,
         * and far less than the time it takes to notice the page has
         * gone somewhere it should not have.
         */
        const val MAX_STEP_NANOS = 250_000_000L
    }
}

/**
 * The reading pace, as pixels a second.
 *
 * The notch itself belongs to the setting, not to the movement, so its
 * bounds live in [AutoScrollPreference] and every step that arrives here
 * is normalised through it — a curve with its own idea of the range
 * would drift from the slider the moment either changed.
 *
 * The pace is multiplied by the font size because the two are the same
 * question asked twice: text set half again as large has half again as
 * far to travel to show the same words, so scaling by it holds *lines*
 * per minute roughly steady across sizes. Words per minute also depends
 * on how wide the measure is and how the book is set, which nothing here
 * pretends to model.
 */
object AutoScrollSpeed {

    /** The pace at [AutoScrollPreference.MIN_STEP], in dp a second, before font size. */
    const val SLOWEST_DP_PER_SECOND = 6.0

    /** The pace at [AutoScrollPreference.MAX_STEP], in dp a second, before font size. */
    const val FASTEST_DP_PER_SECOND = 90.0

    /**
     * Dp a second at [step], for text set at [fontSize].
     *
     * The steps are spread geometrically rather than evenly. The slow
     * end is where a reader actually chooses — the difference between 8
     * and 12 dp a second is the difference between keeping up and not —
     * while at the fast end, where the page is being skimmed rather than
     * read, a few dp either way is nothing. Even steps would spend most
     * of the slider on speeds nobody reads at.
     */
    fun dpPerSecond(step: Float, fontSize: Double = 1.0): Double {
        val notch = AutoScrollPreference.sanitize(step).toDouble()
        val span = (AutoScrollPreference.MAX_STEP - AutoScrollPreference.MIN_STEP).toDouble()
        val across = (notch - AutoScrollPreference.MIN_STEP) / span
        val ratio = FASTEST_DP_PER_SECOND / SLOWEST_DP_PER_SECOND
        return SLOWEST_DP_PER_SECOND * ratio.pow(across) * fontSize
    }
}
