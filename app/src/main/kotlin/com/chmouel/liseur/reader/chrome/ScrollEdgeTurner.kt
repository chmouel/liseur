package com.chmouel.liseur.reader.chrome

import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Carries a scrolled book across a chapter boundary: keep dragging when
 * the chapter has no more to give and the next one opens.
 *
 * Readium lays every chapter out in its own web view, so a book read by
 * scrolling stops dead at the end of each one, and the only way onwards
 * it offers is a sideways swipe — a page turn in a book that has no
 * pages. Sideways swipes are switched off here (see
 * `epubNavigatorConfiguration`), and this listener puts the way onwards
 * where the reader's thumb already is.
 *
 * The drags come from Readium's own gesture script, which reports them
 * whether or not the page is scrolling; nothing is consumed, so the page
 * scrolls exactly as it did.
 */
@OptIn(ExperimentalReadiumApi::class)
class ScrollEdgeTurner(
    private val navigator: OverflowableNavigator,
    private val isScrolling: () -> Boolean,
    private val isVerticalText: () -> Boolean = { false },
    private val onStepChapter: (forward: Boolean) -> Unit,
) : InputListener {

    private val pull = EdgePull()

    override fun onDrag(event: DragEvent): Boolean {
        if (!isScrolling()) return false
        when (event.type) {
            DragEvent.Type.Start -> pull.reset()

            DragEvent.Type.Move -> {
                val view = navigator.publicationView
                val web = visibleWebView(view) ?: return false
                // A book set in vertical lines is scrolled sideways, and
                // the reader carries the text along with the finger just
                // the same: later text lies to the left, so it is brought
                // in by dragging right, as later text below is brought in
                // by dragging up.
                val vertical = isVerticalText()
                val step = pull.onMove(
                    forwardTravel = if (vertical) event.offset.x else -event.offset.y,
                    atForwardEdge = if (vertical) {
                        !web.canScrollHorizontally(-1)
                    } else {
                        !web.canScrollVertically(1)
                    },
                    atBackwardEdge = if (vertical) {
                        !web.canScrollHorizontally(1)
                    } else {
                        !web.canScrollVertically(-1)
                    },
                    threshold = PULL_DP * view.resources.displayMetrics.density,
                )
                when (step) {
                    EdgePull.Step.FORWARD -> onStepChapter(true)
                    EdgePull.Step.BACKWARD -> onStepChapter(false)
                    EdgePull.Step.NONE -> Unit
                }
            }

            DragEvent.Type.End -> pull.reset()
        }
        return false
    }

    private companion object {
        /**
         * How far past the end the reader has to keep dragging. Short
         * enough to be found by anyone who simply carries on reading,
         * long enough that a drag which merely arrives at the end of a
         * chapter leaves them there to finish the last line.
         */
        const val PULL_DP = 64f
    }
}

/**
 * Watches one drag for a pull past an edge the page cannot scroll past.
 *
 * The distance is measured from where the edge was met rather than from
 * where the drag began, so a single long drag that scrolls the rest of
 * the chapter and keeps going crosses over, while a drag that only just
 * reaches the end does not. A drag turns at most one chapter, however
 * far it goes: a book is not scrolled through by leaning on it.
 *
 * Kept free of Android types so the gesture can be checked without one.
 */
class EdgePull {

    enum class Step { NONE, FORWARD, BACKWARD }

    private var atEndSince: Float? = null
    private var atStartSince: Float? = null
    private var stepped = false

    fun reset() {
        atEndSince = null
        atStartSince = null
        stepped = false
    }

    /**
     * [forwardTravel] is how far the finger has travelled since the drag
     * began, in pixels, counted positive in the direction that moves the
     * reader forwards through the text. Which way that is on the screen
     * is the caller's business: it is up the page in a book set in lines
     * across, and rightwards in one set in lines down.
     */
    fun onMove(
        forwardTravel: Float,
        atForwardEdge: Boolean,
        atBackwardEdge: Boolean,
        threshold: Float,
    ): Step {
        if (stepped) return Step.NONE
        atEndSince = if (atForwardEdge) atEndSince ?: forwardTravel else null
        atStartSince = if (atBackwardEdge) atStartSince ?: forwardTravel else null

        atEndSince?.let {
            if (forwardTravel - it >= threshold) {
                stepped = true
                return Step.FORWARD
            }
        }
        atStartSince?.let {
            if (it - forwardTravel >= threshold) {
                stepped = true
                return Step.BACKWARD
            }
        }
        return Step.NONE
    }
}
