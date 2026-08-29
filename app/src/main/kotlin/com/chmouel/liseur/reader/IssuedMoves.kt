package com.chmouel.liseur.reader

import com.chmouel.liseur.reader.progress.RestorePoint
import kotlin.math.abs

/**
 * The moves the reader has been sent on, and whether they have arrived.
 *
 * Readium says it has moved only once it has moved, and it lays a
 * resource out and puts it on screen before it says even that. A `go`
 * that has been issued and not yet landed is therefore invisible to
 * every question that can be asked about the page: the navigator's
 * position still names where the reader was, and the web view under the
 * middle of the screen is still the one they were reading.
 *
 * That is the blind spot of anything which captures a place, does
 * something slow to the document, and then puts the reader back — the
 * wide-content fit and a run of preference changes both do. Between the
 * capture and the restore the reader can tap a contents entry or turn a
 * page, and neither the position nor the views on screen will admit it
 * for another moment; the restore then carries them back to the page
 * they just asked to leave, which is a book that changes page by
 * itself.
 *
 * Two orderings do that, and counting moves alone answers only the
 * first:
 *
 *  - the move is asked for *after* the restore took its bearings, which
 *    a count catches, because the count has moved on; and
 *  - the move is asked for *before*, and is still in the air when the
 *    restore looks — which no count catches, since nothing has been
 *    issued since. This is the one a table of contents pointing at
 *    fragments of a single resource produces: the resource never
 *    changes, so every guard written in terms of the resource on screen
 *    agrees the reader is where they were.
 *
 * So a move is remembered along with where it was going and stays
 * outstanding until the navigator reports being there. A restore takes
 * a [mark] before it starts, which is [NONE] while anything is
 * outstanding and is retired by anything asked for after it. Refusing
 * is always the safe answer: a fit left unrestored moves the text by a
 * line, a restore that should have been refused moves the reader out of
 * the chapter they chose.
 *
 * What arrival can be recognised from depends on how much the caller
 * knew:
 *
 *  - a move with a destination is answered by the navigator reporting
 *    that destination, unless a fragment was asked for, which nothing
 *    reports back and only the clock ends;
 *  - one move with no destination, and nothing else in the air — a
 *    single page turn — is answered by any position other than the one
 *    it started from;
 *  - anything overlapping is not answered at all. Readium publishes a
 *    resource's own idea of where it is and then the real position, so
 *    emissions and moves do not pair off, and guessing which arrival
 *    belongs to which move would let one of them stand for both. They
 *    are held together until the reader stops and the clock runs out.
 *
 * Nothing here is synchronised, and nothing here needs to be: moves are
 * issued and positions read on the main thread, as `chrome/HeldPlace`
 * is.
 */
class IssuedMoves(
    private val settleTimeoutMs: Long = SETTLE_TIMEOUT_MS,
    private val restlessMs: Long = RESTLESS_MS,
) {

    /**
     * Where a move is going, in the terms that can be checked against
     * what the navigator later reports.
     *
     * Not a Readium `Locator` on purpose: the decision below is worth
     * testing without a navigator to ask.
     */
    data class Destination(
        val href: String,
        /** Whole-book, as `Locator.locations.totalProgression`. */
        val progression: Double?,
        /** The `#fragment` a link asked for, if it named one. */
        val fragment: String?,
    )

    private var issued = 0

    private var target: Destination? = null
    private var targetDeadline = 0L
    private var targetToken = NONE

    private var restless = false
    private var restlessFrom: RestorePoint? = null
    private var attributable = false
    private var restlessDeadline = 0L
    private var restlessToken = NONE

    /**
     * The reader is being sent somewhere.
     *
     * [from] is where they are as it is asked for and [to] where they
     * are being sent. A page turn knows no destination, and is held
     * against [from] instead — until a second one joins it, at which
     * point neither can be told from the other and both wait for the
     * clock.
     *
     * Answers a token to hand back to [cancel] if the move turns out
     * not to be happening.
     */
    fun issue(from: RestorePoint? = null, to: Destination? = null, nowMs: Long = 0L): Int {
        issued++
        if (to != null) {
            // The newest destination is what the reader asked for last,
            // and reaching it accounts for whatever was asked before.
            target = to
            targetDeadline = nowMs + settleTimeoutMs
            // A turn waiting alongside it can no longer be recognised
            // by a position that differs, because the arrival of this
            // one differs too.
            attributable = false
            restlessFrom = null
            targetToken = issued
            return issued
        }
        attributable = !restless && target == null
        restlessFrom = from.takeIf { attributable }
        restless = true
        restlessDeadline = nowMs + restlessMs
        restlessToken = issued
        return issued
    }

    /**
     * The reader is moving a scrolled page with their finger.
     *
     * No `go` is issued for this and no arrival is reported, so it is
     * held open for as long as the dragging goes on and let go of a
     * moment after it stops. A fit settling over a page being dragged
     * pulls it back under the finger.
     */
    fun scrolled(nowMs: Long = 0L) {
        issued++
        attributable = false
        restlessFrom = null
        restless = true
        restlessDeadline = nowMs + restlessMs
        restlessToken = issued
    }

    /**
     * What was announced is not happening: a `go` the navigator
     * declined, a probe that came back to find the page already gone.
     * Said so that a turn which moved nothing does not keep every later
     * restore waiting for an arrival that was never on its way.
     *
     * The [token] the announcement answered with is required, and ends
     * only the wait that token opened. A page turn giving up must not
     * take a jump's wait with it, and an answer that arrives late must
     * not end a wait opened since — an asynchronous probe finding the
     * page gone is exactly the case where both are true at once.
     *
     * The count is not wound back. A restore that took its bearings
     * before the reader asked for anything at all stays refused,
     * because the asking is what makes those bearings suspect, not the
     * outcome.
     */
    fun cancel(token: Int) {
        if (token == NONE) return
        if (token == targetToken) {
            target = null
            targetToken = NONE
            return
        }
        if (token == restlessToken) stopRestless()
    }

    /** A position the navigator reported. */
    fun onPosition(here: RestorePoint, nowMs: Long = 0L) {
        expire(nowMs)
        target?.let {
            if (it.arrived(here)) {
                target = null
                targetToken = NONE
            }
        }
        if (restless && attributable && here != restlessFrom) stopRestless()
    }

    /**
     * The state to hold a later restore against, or [NONE] while a move
     * is still in the air — a restore that begins then has taken its
     * bearings from a page the reader is already leaving.
     */
    fun mark(nowMs: Long = 0L): Int {
        expire(nowMs)
        return if (target == null && !restless) issued else NONE
    }

    /** Whether nothing has been asked for since [mark] answered [since]. */
    fun unchangedSince(since: Int): Boolean = since != NONE && since == issued

    private fun stopRestless() {
        restless = false
        attributable = false
        restlessFrom = null
        restlessToken = NONE
    }

    /**
     * Stop waiting.
     *
     * A `go` to where the reader already is publishes nothing to say so,
     * a fragment jump is never reported as arriving, and turns in a
     * flurry cannot be told apart. Waiting forever would leave the book
     * with no restores for the rest of the session, so both waits are
     * bounded — the one for a destination widely enough to outlast a
     * resource loading and being scrolled to a fragment on a slow
     * phone, the one for a hand on the page only long enough to see
     * that it has come off.
     */
    private fun expire(nowMs: Long) {
        if (target != null && nowMs - targetDeadline >= 0) {
            target = null
            targetToken = NONE
        }
        if (restless && nowMs - restlessDeadline >= 0) stopRestless()
    }

    private fun Destination.arrived(here: RestorePoint): Boolean {
        // A fragment is asked for and never reported back: Readium
        // publishes the resource's own idea of where it is, which as it
        // loads is the top of it. Taking that for the arrival is the
        // reported bug exactly — the reader asks for a section and a
        // restore puts them back at the start of the file it lives in.
        if (fragment != null) return false
        if (here.href != href) return false
        val wanted = progression ?: return true
        val reached = here.progression ?: return false
        return abs(reached - wanted) <= TOLERANCE
    }

    companion object {
        /**
         * No mark: a restore that asked while a move was in the air.
         * Also the token of nothing, since a move's token is the count
         * it was announced at and counting starts at one.
         */
        const val NONE = -1

        /**
         * How close to where it was sent a move has to land to count as
         * having landed. The same tolerance the opening gate uses, and
         * for the same reason: this asks whether the navigator went
         * roughly where it was told, not whether two devices agree.
         */
        const val TOLERANCE = 0.01

        /** How long to wait for a move that named where it was going. */
        const val SETTLE_TIMEOUT_MS = 3_000L

        /** How long a page that was being moved by hand stays moving. */
        const val RESTLESS_MS = 1_000L
    }
}
