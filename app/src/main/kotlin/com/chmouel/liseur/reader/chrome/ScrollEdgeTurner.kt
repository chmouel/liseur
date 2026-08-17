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
                val step = pull.onMove(
                    offsetY = event.offset.y,
                    canScrollDown = web.canScrollVertically(1),
                    canScrollUp = web.canScrollVertically(-1),
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

    private var atBottomSince: Float? = null
    private var atTopSince: Float? = null
    private var stepped = false

    fun reset() {
        atBottomSince = null
        atTopSince = null
        stepped = false
    }

    /**
     * [offsetY] is how far the finger has travelled since the drag
     * began, in pixels, negative upwards — the direction that moves the
     * reader forwards through the text.
     */
    fun onMove(
        offsetY: Float,
        canScrollDown: Boolean,
        canScrollUp: Boolean,
        threshold: Float,
    ): Step {
        if (stepped) return Step.NONE
        atBottomSince = if (canScrollDown) null else atBottomSince ?: offsetY
        atTopSince = if (canScrollUp) null else atTopSince ?: offsetY

        atBottomSince?.let {
            if (it - offsetY >= threshold) {
                stepped = true
                return Step.FORWARD
            }
        }
        atTopSince?.let {
            if (offsetY - it >= threshold) {
                stepped = true
                return Step.BACKWARD
            }
        }
        return Step.NONE
    }
}
