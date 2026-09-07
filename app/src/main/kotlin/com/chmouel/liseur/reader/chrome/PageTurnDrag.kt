package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.data.settings.PageTurnStyle
import kotlin.math.abs

/**
 * Makes a sideways drag turn the page, and keeps the page under the
 * finger while it does.
 *
 * Readium's answer to a drag is the seamless slide: the columns follow
 * the finger and snap when it lifts. That is [PageTurnStyle.SLIDE], and
 * under it this stands aside and the drag stays Readium's. Under the
 * other two the drag is taken over, and the page is curled off the book
 * under the finger instead — held halfway, put back, or let go — so
 * that a reader who chose the lifted page or the instant jump for a
 * *tap* still has a page that moves when they move it. The style
 * governs the tap, the volume key and the D-pad; a finger on the page
 * is a finger on the page.
 *
 * Taking the drag over means taking the touches themselves. The columns
 * are moved by `R2WebView`'s own native gesture code — its own slop,
 * its own velocity tracker, its own scroller — which answers to nothing
 * the page or the navigator can say. The only thing above it is the
 * pointer loop in `ReaderScreen`, which sees every touch before the web
 * view does and can consume it, exactly as the image viewer already
 * does. So this is a plain state machine driven from there rather than
 * an `InputListener`, and the curl itself is behind [Curl], so that
 * everything here is arithmetic.
 *
 * The curl is a page turn that has already happened underneath: the
 * navigator jumps as the drag is claimed and the departing page is
 * photographed over it. That takes a frame or two, and the finger goes
 * on moving meanwhile, so the travel is held and handed over when the
 * page is in hand. Electronic paper leaves the drag to the navigator.
 * When there is no curl to be had — the last page, whose
 * turn finishes the book and cannot be tentative; the first page going
 * back — the drag is a swipe: nothing follows the finger, and lifting
 * it after [SWIPE_DP] turns the page the way a tap would.
 *
 * A drag that sets off up or down the page is never claimed, so
 * scrolling, pinching and stretching a selection are untouched. A
 * second finger arriving hands a swipe back to the web view; it cannot
 * hand a curl back, since the book has already turned, so it puts the
 * page down instead and the gesture stays consumed until every finger
 * has left.
 */
class PageTurnDrag(
    private val style: () -> PageTurnStyle,
    private val canTurn: () -> Boolean,
    /** Whether the page can be drawn moving at all; false on electronic paper. */
    private val interactive: () -> Boolean,
    private val isRtl: () -> Boolean,
    private val density: () -> Float,
    private val width: () -> Float,
    private val onTurnPage: (forward: Boolean) -> Unit,
    private val curl: Curl,
) {

    /** The page under the finger, as the screen draws it. */
    interface Curl {
        /**
         * Photographs the page and turns the book underneath, going
         * [forward] or not, with the finger [grabY] down the page.
         * Answers [onReady] once, later, with whether the page is in
         * hand; false means there is no curl and the drag is a swipe.
         */
        fun begin(forward: Boolean, grabY: Float, onReady: (Boolean) -> Unit)

        /** The edge has come [travel] pixels in, and the finger [dy] down from where it landed. */
        fun follow(travel: Float, dy: Float)

        /** Let go: the page leaves at [velocity] pixels a second. */
        fun finish(velocity: Float)

        /** Let go: the page lies back down, from [velocity] pixels a second, and the book goes back. */
        fun restore(velocity: Float)

        /**
         * Everything stops now: the page goes and the book returns to
         * where the turn started, with no motion and nothing to wait
         * for. For a reader that is closing or a page that has changed
         * size under the finger, where an animation would outlive what
         * it was animating.
         */
        fun abandon()
    }

    private enum class Mode { SWIPE, PENDING, CURL, PUT_DOWN }

    private class Gesture(val forward: Boolean, val movesLeft: Boolean) {
        var mode = Mode.SWIPE
        var dx = 0f
        var dy = 0f
        var released = false
        var velocity = 0f

        /** How far the edge has come along the turn; a finger back past its start is a flat page. */
        val travel: Float get() = (if (movesLeft) -dx else dx).coerceAtLeast(0f)
    }

    private var decided = false
    private var gesture: Gesture? = null

    /**
     * Offers a gesture in progress: [pointers] fingers are down, and the
     * first landed [downY] down the page and has travelled [dx] across
     * and [dy] down from there. Answers whether the gesture is ours, and
     * so whether the caller should consume it.
     *
     * Only worth asking once the finger has passed the touch slop: below
     * it a drag has no direction to read, and the web view has not begun
     * to move the columns either.
     *
     * The answer is decided once and then kept until [reset]. A gesture
     * that set off downwards is the web view's for as long as it lasts,
     * however it curves later: taking it over halfway through would
     * cancel a touch the web view is already acting on, and turn a page
     * nobody asked to turn. The direction is settled at the same moment,
     * so a finger that comes back flattens the page rather than starting
     * the other turn.
     */
    fun offer(pointers: Int, dx: Float, dy: Float, downY: Float = 0f): Boolean {
        val g = gesture
        if (pointers != 1) {
            decided = true
            if (g == null) return false
            return when (g.mode) {
                // Nothing has moved yet; the web view can have it.
                Mode.SWIPE -> {
                    gesture = null
                    false
                }
                Mode.PENDING -> {
                    g.mode = Mode.PUT_DOWN
                    true
                }
                Mode.CURL -> {
                    g.mode = Mode.PUT_DOWN
                    curl.restore(0f)
                    true
                }
                Mode.PUT_DOWN -> true
            }
        }
        if (!decided) {
            decided = true
            if (style() != PageTurnStyle.SLIDE && interactive() && canTurn() && sideways(dx, dy)) {
                val rtl = isRtl()
                val movesLeft = dx < 0f
                val fresh = Gesture(forward = movesLeft != rtl, movesLeft = movesLeft)
                gesture = fresh
                fresh.mode = Mode.PENDING
                curl.begin(fresh.forward, downY) { inHand -> ready(fresh, inHand) }
            }
        }
        val current = gesture ?: return false
        current.dx = dx
        current.dy = dy
        if (current.mode == Mode.CURL) curl.follow(current.travel, dy)
        return true
    }

    private fun ready(g: Gesture, inHand: Boolean) {
        if (g.mode != Mode.PENDING) {
            // Put down or forgotten while the page was being photographed.
            if (inHand) curl.restore(0f)
            return
        }
        if (!inHand) {
            g.mode = Mode.SWIPE
            if (g.released) swipe(g)
            return
        }
        g.mode = Mode.CURL
        if (g.released) {
            letGo(g)
        } else {
            curl.follow(g.travel, g.dy)
        }
    }

    /**
     * The fingers have left, the first moving at [velocityX] pixels a
     * second across the screen. Finishes or puts back a curled page,
     * turns the page after a long enough swipe, and answers whether the
     * gesture was ours at all — a gesture that was, is consumed to the
     * end, so that the lift out of it is never also read as a tap on
     * the page.
     */
    fun release(velocityX: Float = 0f): Boolean {
        val g = gesture ?: return false
        g.released = true
        g.velocity = velocityX
        gesture = null
        decided = false
        when (g.mode) {
            Mode.SWIPE -> swipe(g)
            Mode.CURL -> letGo(g)
            // Decided when the page comes to hand.
            Mode.PENDING -> Unit
            Mode.PUT_DOWN -> Unit
        }
        return true
    }

    private fun swipe(g: Gesture) {
        forward(g.dx, SWIPE_DP * density(), isRtl())?.let(onTurnPage)
    }

    private fun letGo(g: Gesture) {
        val along = if (g.movesLeft) -g.velocity else g.velocity
        if (PageCurl.commits(g.travel, along, width(), density())) {
            curl.finish(along)
        } else {
            curl.restore(along)
        }
    }

    /** Forgets a gesture: a fresh touch owes nothing to the last one. */
    fun reset() {
        val g = gesture
        decided = false
        gesture = null
        if (g == null) return
        when (g.mode) {
            Mode.CURL -> curl.restore(0f)
            // Answered when the page comes to hand: see [ready].
            Mode.PENDING -> g.mode = Mode.PUT_DOWN
            Mode.SWIPE, Mode.PUT_DOWN -> Unit
        }
    }

    /**
     * Gives the gesture up altogether, putting any page back without
     * animating it. For the reader closing or the page changing size
     * under the finger: [reset] starts a motion that wants a next
     * frame, and there may not be one.
     */
    fun abandon() {
        val g = gesture
        decided = false
        gesture = null
        // A photograph still being taken finds nothing left to hold.
        if (g != null) g.mode = Mode.PUT_DOWN
        curl.abandon()
    }

    companion object {
        /**
         * How far sideways a swipe has to go to turn the page. Short
         * enough not to need a run-up, long enough that a thumb resting
         * on the page and shifting slightly leaves it alone.
         */
        const val SWIPE_DP = 48f

        /**
         * Whether a drag that has moved [dx] across and [dy] down is one
         * going sideways.
         */
        fun sideways(dx: Float, dy: Float): Boolean = abs(dx) > abs(dy)

        /**
         * Which way a swipe of [travel] pixels across turns the page, or
         * null if it did not go far enough to turn it at all.
         *
         * Dragging leftwards brings in what lies to the right of the
         * page, which is the next page in a book read left to right and
         * the previous one in a book read the other way.
         */
        fun forward(travel: Float, threshold: Float, rtl: Boolean): Boolean? = when {
            abs(travel) < threshold -> null
            rtl -> travel > 0f
            else -> travel < 0f
        }
    }
}
