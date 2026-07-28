package com.chmouel.liseur.reader.progress

/**
 * Estimates how fast the reader moves through a book, in Readium
 * positions per minute, so the reader can be told how much time is
 * left in the chapter or the book.
 *
 * Samples come from consecutive reading positions. Jumps (table of
 * contents, scrubber), backwards moves, very quick flicks and long
 * pauses are ignored, and the remaining samples are blended into a
 * rolling average so the estimate settles quickly but keeps adapting.
 */
class ReadingSpeedEstimator(initialSpeed: Double? = null) {

    /** Positions per minute, or null while nothing has been measured. */
    var speed: Double? = initialSpeed?.takeIf { it > 0 }
        private set

    private var lastPosition: Double? = null
    private var lastAt: Long? = null

    /** Speed used for estimates, falling back to a typical reading pace. */
    val effectiveSpeed: Double get() = speed ?: DEFAULT_SPEED

    /** True once the reader's own pace has been measured. */
    val isMeasured: Boolean get() = speed != null

    /**
     * Records the reader being at [position] (a fractional Readium
     * position) at [atMillis], and refines the estimate when the move
     * looks like actual reading.
     */
    fun record(position: Double, atMillis: Long) {
        val previousPosition = lastPosition
        val previousAt = lastAt
        lastPosition = position
        lastAt = atMillis
        if (previousPosition == null || previousAt == null) return

        val advanced = position - previousPosition
        val minutes = (atMillis - previousAt) / MILLIS_PER_MINUTE
        if (advanced <= 0 || advanced > MAX_ADVANCE) return
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) return

        val sample = advanced / minutes
        val current = speed
        speed = if (current == null) sample else current * (1 - WEIGHT) + sample * WEIGHT
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

    companion object {
        /**
         * A Readium position is about a thousand characters, roughly
         * 170 words, so an average pace of 250 words per minute lands
         * near 1.5 positions per minute.
         */
        const val DEFAULT_SPEED = 1.5

        private const val MILLIS_PER_MINUTE = 60_000.0

        /** Larger moves are jumps rather than reading. */
        private const val MAX_ADVANCE = 5.0

        /** Below this, the page was flicked rather than read. */
        private const val MIN_MINUTES = 0.05

        /** Above this, the book was left open rather than read. */
        private const val MAX_MINUTES = 10.0

        /** Weight given to the newest sample in the rolling average. */
        private const val WEIGHT = 0.3

        private const val MAX_ESTIMATE_MINUTES = 60.0 * 99
    }
}
