package com.chmouel.liseur.reader

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * What a page has just done with the selection on it.
 *
 * The two live on one flow rather than two because the order between them
 * is the whole point. A selection that has been let go has to be able to
 * overtake the reading of the selection before it — reading one is slow
 * enough to still be in progress — or the bar of things to do with a
 * passage comes back pointing at words that are no longer marked, and
 * offers to highlight a passage the reader can no longer see.
 */
enum class SelectionEvent {
    /** Text has been selected, or what was selected has been stretched. */
    CHANGED,

    /** The page has no selection on it any more. */
    CLEARED,
}

/**
 * Follows a page's selection and reports the passage the reader settled on.
 *
 * A single gesture produces a run of [SelectionEvent.CHANGED]: one when the
 * action mode is created, another when it is immediately invalidated, and
 * one more every time a handle stretches the selection. Only the last of
 * them describes what the reader meant, and [read] is expensive — the
 * navigator walks the chapter's text nodes to work out where the selection
 * falls — so a run is left to go quiet for [settleMs] before it is asked.
 *
 * [SelectionEvent.CLEARED] reports `null` at once and cancels any read
 * still waiting or in flight, which is what keeps a passage that has since
 * been let go from being handed back as if it were still marked.
 *
 * Following begins by reporting `null`, because a page nobody has been
 * watching cannot be assumed to have anything marked on it — and because a
 * clear raised while nothing is collecting has nowhere to land. That is the
 * case when the page is being swapped for another one: whoever was
 * following the old page has stopped, and the selection belonging to it
 * must not outlive it.
 *
 * A read that answers nothing leaves the last report standing: the page can
 * momentarily say it has no selection in the middle of a gesture that
 * plainly does, and a bar that blinked out there would be worse than one
 * that waits. Only [SelectionEvent.CLEARED] takes a selection away.
 */
suspend fun <T> Flow<SelectionEvent>.collectSettledSelection(
    settleMs: Long,
    read: suspend () -> T?,
    onSelection: (T?) -> Unit,
) {
    onSelection(null)
    collectLatest { event ->
        when (event) {
            SelectionEvent.CLEARED -> onSelection(null)
            SelectionEvent.CHANGED -> {
                delay(settleMs)
                val selected = read() ?: return@collectLatest
                onSelection(selected)
            }
        }
    }
}
