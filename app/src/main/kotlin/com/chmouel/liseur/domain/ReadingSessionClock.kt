package com.chmouel.liseur.domain

/**
 * Works out how much of the time a book was on screen was reading.
 *
 * Time accrues while a successfully opened reader is in the foreground
 * and stops when it is paused. The caller supplies monotonic timestamps,
 * so correcting the device clock cannot add or remove reading time.
 * There is deliberately no inactivity cap: a clock cannot distinguish a
 * book left open from a reader spending a long time on a difficult page.
 * Revisit that tradeoff if the app ever keeps the screen awake itself.
 *
 * Kept free of Room and Android so the rules above can be tested by
 * stating a sequence of moments and reading off the answer, which is
 * the only way anyone will ever check them.
 */
class ReadingSessionClock {

    /** The last moment already included in [totalMs], while running. */
    private var lastAccountedAt: Long? = null
    private var totalMs = 0L

    val isRunning: Boolean get() = lastAccountedAt != null

    /**
     * Starts counting, if it is not already.
     *
     * Idempotent, because a resume can arrive twice: a dialog dismissed
     * over the reader and a rotation both come back through the same
     * door, and the second must not throw away the first one's time.
     */
    fun resume(atMillis: Long) {
        if (isRunning) return
        totalMs = 0
        lastAccountedAt = atMillis
    }

    /**
     * Records a safe persistence point and returns the session's total
     * reading time so far.
     *
     * Returns zero while stopped so callers can persist the result without
     * a second nullable state.
     */
    fun checkpoint(atMillis: Long): Long {
        val since = lastAccountedAt ?: return 0
        val earned = credit(from = since, to = atMillis)
        // A monotonic clock should never move backwards, but refusing
        // to move the checkpoint back keeps a bad injected clock from
        // being paid for twice when it catches up again.
        if (atMillis > since) {
            totalMs += earned
            lastAccountedAt = atMillis
        }
        return totalMs
    }

    /**
     * Stops counting and returns the session's final total.
     *
     * A pause with nothing running earns nothing. Android will send
     * `onPause` for a reader that was never resumed — a book opened
     * straight into a permission dialog, an activity finished before it
     * was ever looked at — and those must be worth nothing, not worth
     * whatever the clock happened to say.
     */
    fun pause(atMillis: Long): Long {
        val since = lastAccountedAt ?: return 0
        val finalTotal = totalMs + credit(from = since, to = atMillis)
        lastAccountedAt = null
        totalMs = 0
        return finalTotal
    }

    /**
     * Time to be credited for a stretch in which no page turned.
     *
     * The production clock is monotonic, but an injected or broken clock
     * can still go backwards. A negative stretch is not reading and must
     * never make a stored total shrink.
     */
    private fun credit(from: Long, to: Long): Long {
        val elapsed = to - from
        if (elapsed <= 0) return 0
        return elapsed
    }
}
