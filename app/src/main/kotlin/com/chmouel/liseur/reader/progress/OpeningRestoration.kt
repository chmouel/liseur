package com.chmouel.liseur.reader.progress

import kotlin.math.abs

/**
 * Where a book is being reopened to, in the only terms the gate needs.
 *
 * Deliberately not a Readium `Locator`: the decision below is worth
 * testing without an emulator, and it turns on three facts.
 */
data class RestorePoint(
    val href: String,
    /** Whole-book, stable across fonts and columns. Null when unknown. */
    val progression: Double?,
    /** Whether the point carries an app-owned text anchor. */
    val exact: Boolean,
)

/** Whether an emission may be treated as the reader having moved. */
enum class OpeningRestorationVerdict { SUPPRESS, RELEASE }

/**
 * The gate that keeps a book's opening out of the sync log.
 *
 * A reflowable navigator reports where it is before it has finished
 * being told where to go. The first emission after the replayed initial
 * locator is the WebView's own idea of the position — the top of the
 * resource, near enough — and by then the reader is already active, so
 * without a gate it is persisted and pushed as a page turn. On the
 * account this was written for, that produced a position of exactly 0.0
 * in a chapter the reader was two thirds of the way through, sixteen
 * seconds before the correct position followed it.
 *
 * The gate suppresses until it can see that restoration has landed, and
 * gives up after a deadline rather than risk holding a session's writes
 * forever. The emission that shows restoration landed is suppressed too:
 * it is the restoration arriving, not the reader moving, and writing it
 * back would republish the stored position under a fresh revision. That
 * is the other half of the same bug — a device reopening a book exactly
 * where it left it pushed a ten-hour-old position as new reading, which
 * on every other device read as the reader jumping backwards.
 *
 * Suppression costs at most one unrecorded page turn: the next emission
 * carries the same place, and the progress bar is driven from an earlier
 * point in the pipeline so the reader sees nothing.
 *
 * One instance per navigator. A new navigator — a column or scroll mode
 * change rebuilds one — restores again and so needs a fresh gate.
 */
class OpeningRestoration(
    private val target: RestorePoint?,
    private val timeoutMs: Long,
) {
    private var released = target == null
    private var current = target

    val isGated: Boolean get() = !released

    /**
     * A position the navigator reported.
     *
     * [anchorVerified] answers whether the exact anchor was found on the
     * page this emission describes; it is only consulted for an exact
     * target, and the caller only pays for the check while gated.
     */
    fun onEmission(
        here: RestorePoint,
        anchorVerified: Boolean,
        elapsedMs: Long,
    ): OpeningRestorationVerdict {
        if (released) return OpeningRestorationVerdict.RELEASE
        if (current?.let { arrived(it, here, anchorVerified) } != false) {
            // The emission that shows restoration landed *is* the
            // restoration, so it is the last one suppressed rather than
            // the first one let through. Publishing it would rewrite the
            // stored position with itself, and every write bumps the
            // revision — which is how a book reopened where it was left
            // came to republish a ten-hour-old locator as fresh reading
            // and drag the shared position backwards.
            released = true
            return OpeningRestorationVerdict.SUPPRESS
        }
        if (elapsedMs >= timeoutMs) {
            // Restoration never visibly landed. Fail open: a position
            // that might be wrong is recoverable, a reader whose place
            // silently stops being saved is not.
            released = true
            return OpeningRestorationVerdict.RELEASE
        }
        return OpeningRestorationVerdict.SUPPRESS
    }

    /**
     * The deadline passed.
     *
     * Driven by a clock rather than by an emission, because a navigator
     * that goes quiet while gated would otherwise stay gated for the
     * whole session however much time went by.
     */
    fun onDeadline() {
        released = true
    }

    /**
     * The book is being sent somewhere: restoration falling back to an
     * approximate point, or the reader tapping a contents entry, a
     * search hit or a catch-up offer while the book is still opening.
     *
     * This retargets rather than releases, and the distinction matters.
     * The navigation is asynchronous and the caller's own marker for it
     * is single use, so an emission from the position being left behind
     * can arrive first and take that marker. Releasing here would hand
     * that emission through wearing the label of a move the reader made
     * — which is the pre-restore position the gate exists to catch,
     * published as reading. Suppressing until the destination is reached
     * costs nothing instead: the arrival is held, so closing the book
     * still saves it.
     *
     * The deadline is not restarted. Retargeting must not be able to
     * extend the window, however often it happens.
     */
    fun onNavigationIssued(point: RestorePoint) {
        if (released) return
        current = point
    }

    private fun arrived(want: RestorePoint, here: RestorePoint, anchorVerified: Boolean): Boolean {
        if (want.exact) return anchorVerified
        if (here.href != want.href) return false
        val wanted = want.progression ?: return true
        val reached = here.progression ?: return false
        return abs(reached - wanted) <= TOLERANCE
    }

    companion object {
        /**
         * How far off an approximate restoration may land.
         *
         * Wider than the sync layer's epsilon on purpose: this is asking
         * "did the navigator go roughly where it was sent", not "are two
         * devices in the same place".
         */
        const val TOLERANCE = 0.01

        /** Long enough for a slow first layout, short enough to notice. */
        const val DEFAULT_TIMEOUT_MS = 5_000L

        /**
         * A cold open gets longer than an in-book jump to let the WebView
         * finish applying the initial locator before the exact anchor is
         * declared missing. The wall-clock cap is the real bound: thirty
         * two-frame waits are about one second at 60Hz and two seconds at
         * 30Hz, but a starved frame clock must not run into the fail-open
         * deadline.
         */
        const val EXACT_OPEN_VERIFY_ATTEMPTS = 30
        const val EXACT_OPEN_VERIFY_BUDGET_MS = 3_000L

        /**
         * Time reserved between giving up on the exact anchor and the
         * gate's fail-open deadline, so the approximate fallback is
         * issued while the opening gate is still closed.
         */
        const val FALLBACK_BEFORE_DEADLINE_MS = 1_000L

        fun exactOpenVerifyBudgetMs(elapsedMs: Long): Long =
            minOf(
                EXACT_OPEN_VERIFY_BUDGET_MS,
                DEFAULT_TIMEOUT_MS - FALLBACK_BEFORE_DEADLINE_MS - elapsedMs,
            ).coerceAtLeast(0L)
    }
}
