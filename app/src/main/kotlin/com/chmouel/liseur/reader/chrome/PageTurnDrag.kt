package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.data.settings.PageTurnStyle
import kotlin.math.abs

/**
 * Makes a sideways drag turn the page the way the reader asked for.
 *
 * Readium's answer to a drag is the seamless slide: the columns follow
 * the finger and snap when it lifts. That is one of the three page-turn
 * styles, not all of them, and a reader who chose the lifted page or the
 * instant jump got the slide back the moment they used a thumb instead
 * of tapping. So under [PageTurnStyle.SLIDE] this stands aside and the
 * drag stays Readium's; under the other two it takes the gesture over
 * and hands it to the same [PageTurner] a tap uses.
 *
 * Taking it over means taking the touches themselves. The columns are
 * moved by `R2WebView`'s own native gesture code — its own slop, its own
 * velocity tracker, its own scroller — which answers to nothing the page
 * or the navigator can say. The only thing above it is the pointer loop
 * in `ReaderScreen`, which sees every touch before the web view does and
 * can consume it, exactly as the image viewer already does. So this is a
 * plain state machine driven from there rather than an `InputListener`.
 *
 * What is left is a swipe: nothing follows the finger, and lifting it
 * after [SWIPE_DP] turns the page. A drag that sets off up or down the
 * page is never claimed, and a second finger arriving hands the gesture
 * back, so scrolling, pinching and stretching a selection are untouched.
 */
class PageTurnDrag(
    private val style: () -> PageTurnStyle,
    private val canTurn: () -> Boolean,
    private val isRtl: () -> Boolean,
    private val density: () -> Float,
    private val onTurnPage: (forward: Boolean) -> Unit,
) {

    private var decided = false
    private var claimed = false
    private var travel = 0f

    /**
     * Offers a gesture in progress: [pointers] fingers are down, and the
     * first has travelled [dx] across and [dy] down from where it
     * landed. Answers whether the gesture is ours, and so whether the
     * caller should consume it.
     *
     * Only worth asking once the finger has passed the touch slop: below
     * it a drag has no direction to read, and the web view has not begun
     * to move the columns either.
     *
     * The answer is decided once and then kept until [reset]. A gesture
     * that set off downwards is the web view's for as long as it lasts,
     * however it curves later, and a second finger settles it for good:
     * taking a gesture over halfway through would cancel a touch the web
     * view is already acting on, and turn a page nobody asked to turn.
     */
    fun offer(pointers: Int, dx: Float, dy: Float): Boolean {
        if (pointers != 1) {
            decided = true
            claimed = false
            return false
        }
        if (!decided) {
            decided = true
            claimed = style() != PageTurnStyle.SLIDE && canTurn() && sideways(dx, dy)
        }
        if (claimed) travel = dx
        return claimed
    }

    /**
     * The fingers have left. Turns the page if the swipe went far
     * enough, and answers whether the gesture was ours at all — a
     * gesture that was, is consumed to the end, so that the lift out of
     * a swipe is never also read as a tap on the page.
     */
    fun release(): Boolean {
        if (!claimed) return false
        val turn = forward(travel, SWIPE_DP * density(), isRtl())
        reset()
        turn?.let(onTurnPage)
        return true
    }

    /** Forgets a gesture: a fresh touch owes nothing to the last one. */
    fun reset() {
        decided = false
        claimed = false
        travel = 0f
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
