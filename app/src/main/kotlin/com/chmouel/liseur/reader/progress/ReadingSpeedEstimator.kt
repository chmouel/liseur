package com.chmouel.liseur.reader.progress

import kotlin.math.max

/**
 * How fast this reader reads, in Readium positions per minute, and how
 * much reading that answer is based on.
 *
 * Two numbers, because one is not enough. A speed on its own cannot say
 * whether it came from a single lucky page or from a hundred, which is
 * exactly what has to survive being closed and reopened: without the
 * count, every restart would either throw away what was learned or
 * trust a guess as though it were knowledge.
 */
data class ReadingPace(val speed: Double, val samples: Int) {

    val isKnown: Boolean get() = speed.isFinite() && speed > 0 && samples > 0

    /**
     * This pace, having watched one more page being read.
     *
     * Newer samples count for less as the count grows — the first few
     * move the answer a long way, later ones nudge it — down to a floor,
     * so a pace that genuinely changes is still followed rather than
     * frozen. That whole rule is two numbers wide, which is what lets it
     * be written down and picked up again.
     *
     * A sample far from what is already known is pulled back to the edge
     * of the plausible rather than thrown away. Throwing it away sounds
     * safer and is not: someone who reads novels quickly and then opens
     * a dense technical book would have every single page rejected for
     * being too slow, and would be told the same wrong estimate forever.
     * Pulled in instead, the answer walks towards the truth.
     */
    fun after(sample: Double): ReadingPace {
        if (!sample.isFinite() || sample <= 0) return this
        if (!isKnown) return ReadingPace(sample, 1)
        val bounded = sample.coerceIn(speed / OUTLIER_FACTOR, speed * OUTLIER_FACTOR)
        val weight = max(1.0 / (samples + 1), MIN_WEIGHT)
        return ReadingPace(
            speed = speed * (1 - weight) + bounded * weight,
            samples = (samples + 1).coerceAtMost(MAX_SAMPLES),
        )
    }

    companion object {
        val Unknown = ReadingPace(speed = 0.0, samples = 0)

        /**
         * Reads a stored pace, refusing anything that cannot be one.
         *
         * A preference file can be edited, restored from another device
         * or half written, and an infinite or negative speed would make
         * every estimate nonsense rather than merely wrong.
         */
        fun of(speed: Double?, samples: Int?): ReadingPace {
            if (speed == null || !speed.isFinite() || speed <= 0) return Unknown
            val counted = (samples ?: 0).coerceIn(0, MAX_SAMPLES)
            return if (counted <= 0) Unknown else ReadingPace(speed, counted)
        }

        /** Below this many samples, the estimate is not worth showing. */
        const val WARM_UP_SAMPLES = 5

        /**
         * How far a page may differ from the known pace before it is
         * treated as an unusual page rather than a new pace.
         */
        const val OUTLIER_FACTOR = 3.0

        /**
         * The least a new page can count for, so the pace keeps
         * following the reader instead of settling for good. A tenth
         * gives it a memory about ten pages long.
         */
        const val MIN_WEIGHT = 0.1

        /**
         * Past this the count changes nothing, so it stops climbing
         * rather than growing without bound.
         */
        const val MAX_SAMPLES = 1000
    }
}

/**
 * Watches the reader move through a book and works out how long is
 * left.
 *
 * The estimate starts from what is already known rather than from
 * nothing: this book's own pace if it has been read before, otherwise
 * the pace learned from every other book. A first-time reader gets a
 * stock figure and is not told how long is left until enough pages have
 * been watched to mean it.
 *
 * Only time plausibly spent reading is counted. Jumps, backwards moves,
 * pages flicked past and pages the book was left open on are all thrown
 * out, which matters more than any of the arithmetic: a phone put down
 * for a quarter of an hour with the book open is not a slow reader.
 */
class ReadingSpeedEstimator(
    /** What every book so far says about this reader. */
    learned: ReadingPace = ReadingPace.Unknown,
    /** What this book said last time it was open. */
    bookSpeed: Double? = null,
) {

    /** This book's running estimate. */
    var pace: ReadingPace = seed(learned, bookSpeed)
        private set

    /** How much reading has been watched overall, this book included. */
    private var watched: Int = learned.samples

    private var lastPosition: Double? = null
    private var lastAt: Long? = null

    /** Positions per minute, or null while nothing is known. */
    val speed: Double? get() = pace.speed.takeIf { pace.isKnown }

    /** Speed used for estimates, falling back to a typical reading pace. */
    val effectiveSpeed: Double get() = speed ?: DEFAULT_SPEED

    /**
     * True once the estimate is this reader's own rather than a stock
     * figure, counting every book and not only this one — which is the
     * point of learning it at all. Someone who has read for an hour
     * should not be told the app has forgotten them because they opened
     * something new.
     */
    val isMeasured: Boolean get() = pace.isKnown && watched >= ReadingPace.WARM_UP_SAMPLES

    /**
     * Records the reader being at [position] (a fractional Readium
     * position) at [atMillis].
     *
     * Returns the page's pace when the move looked like reading, so it
     * can be added to what is known across every book, or null when it
     * did not and nothing should be learned from it.
     */
    fun record(position: Double, atMillis: Long): Double? {
        val previousPosition = lastPosition
        val previousAt = lastAt
        lastPosition = position
        lastAt = atMillis
        if (previousPosition == null || previousAt == null) return null

        val advanced = position - previousPosition
        val minutes = (atMillis - previousAt) / MILLIS_PER_MINUTE
        if (advanced <= 0 || advanced > MAX_ADVANCE) return null
        if (minutes <= MIN_MINUTES || minutes >= MAX_MINUTES) return null

        val sample = advanced / minutes
        pace = pace.after(sample)
        watched = (watched + 1).coerceAtMost(ReadingPace.MAX_SAMPLES)
        return sample
    }

    /** Forgets the last position, so a jump or a pause is not measured. */
    fun forgetLastPosition() {
        lastPosition = null
        lastAt = null
    }

    /** Minutes needed to read [positions] more positions. */
    fun minutesFor(positions: Double): Int {
        if (positions <= 0) return 0
        val minutes = positions / effectiveSpeed
        return minutes.coerceAtMost(MAX_ESTIMATE_MINUTES).toInt()
    }

    private fun seed(learned: ReadingPace, bookSpeed: Double?): ReadingPace {
        val start = bookSpeed?.takeIf { it.isFinite() && it > 0 }
            ?: learned.speed.takeIf { learned.isKnown }
            ?: return ReadingPace.Unknown
        // Not the full weight of everything learned elsewhere: this book
        // may well read at its own pace, and should be able to say so
        // within a few pages rather than a few hundred.
        return ReadingPace(start, SEED_SAMPLES)
    }

    companion object {
        /**
         * A Readium position is about a thousand characters, roughly
         * 170 words, so an average pace of 250 words per minute lands
         * near 1.5 positions per minute.
         */
        const val DEFAULT_SPEED = 1.5

        /**
         * How settled a book's estimate starts out. Enough that one odd
         * page does not throw it, few enough that a book read at its own
         * pace can say so.
         */
        const val SEED_SAMPLES = 3

        private const val MILLIS_PER_MINUTE = 60_000.0

        /** Larger moves are jumps rather than reading. */
        private const val MAX_ADVANCE = 5.0

        /** Five seconds. Below this, the page was flicked past. */
        private const val MIN_MINUTES = 5.0 / 60

        /**
         * Two minutes. Above this the book was sitting open rather than
         * being read — put down, interrupted, or the screen went dark
         * somewhere this could not be noticed.
         */
        private const val MAX_MINUTES = 2.0

        private const val MAX_ESTIMATE_MINUTES = 60.0 * 99
    }
}
