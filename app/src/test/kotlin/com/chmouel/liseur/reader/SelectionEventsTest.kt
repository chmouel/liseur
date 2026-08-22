package com.chmouel.liseur.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The web view reports a selection several times per gesture, and reading
 * one back is slow enough to still be in progress when the next report
 * arrives. What is checked here is that a gesture costs one read, that the
 * read describes where the gesture ended rather than where it began, and —
 * the one that matters for what ends up stored against a book — that a
 * selection the page has let go can never be handed back as though it were
 * still there.
 *
 * The collector runs unconfined so that an event reaches it the moment it
 * is sent, the way the real one on the main thread does; the settle window
 * is still virtual time, so these run instantly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionEventsTest {

    private val settle = 90L

    private fun events() = MutableSharedFlow<SelectionEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private fun TestScope.follow(
        events: MutableSharedFlow<SelectionEvent>,
        read: suspend () -> String?,
        onSelection: (String?) -> Unit,
    ) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            events.collectSettledSelection(settle, read, onSelection)
        }
    }

    @Test
    fun `the pair of reports an action mode makes on the way up costs one read`() = runTest {
        val events = events()
        var reads = 0
        val seen = mutableListOf<String?>()
        follow(events, read = { reads++; "a word" }, onSelection = seen::add)

        // onCreateActionMode, then the invalidate that always follows it.
        events.emit(SelectionEvent.CHANGED)
        events.emit(SelectionEvent.CHANGED)
        advanceTimeBy(settle + 1)

        assertEquals(1, reads)
        assertEquals(listOf<String?>(null, "a word"), seen)
    }

    @Test
    fun `a handle drag is read once, at the passage it was let go on`() = runTest {
        val events = events()
        var reads = 0
        var passage = "a"
        val seen = mutableListOf<String?>()
        follow(events, read = { reads++; passage }, onSelection = seen::add)

        events.emit(SelectionEvent.CHANGED)
        advanceTimeBy(settle / 3)
        passage = "a whole"
        events.emit(SelectionEvent.CHANGED)
        advanceTimeBy(settle / 3)
        passage = "a whole sentence"
        events.emit(SelectionEvent.CHANGED)
        advanceTimeBy(settle + 1)

        assertEquals(1, reads)
        assertEquals(listOf<String?>(null, "a whole sentence"), seen)
    }

    @Test
    fun `a selection let go while the read is still waiting is never read`() = runTest {
        val events = events()
        var reads = 0
        val seen = mutableListOf<String?>()
        follow(events, read = { reads++; "gone" }, onSelection = seen::add)

        events.emit(SelectionEvent.CHANGED)
        advanceTimeBy(settle / 3)
        events.emit(SelectionEvent.CLEARED)
        advanceTimeBy(settle + 1)

        assertEquals(0, reads)
        assertEquals(listOf<String?>(null, null), seen)
    }

    @Test
    fun `a selection let go mid-read is dropped rather than reported`() = runTest {
        val events = events()
        val reading = CompletableDeferred<Unit>()
        var reads = 0
        val seen = mutableListOf<String?>()
        follow(
            events,
            read = { reads++; reading.await(); "stale" },
            onSelection = seen::add,
        )

        events.emit(SelectionEvent.CHANGED)
        advanceTimeBy(settle + 1)
        assertEquals("the read should be in flight", 1, reads)

        events.emit(SelectionEvent.CLEARED)
        advanceTimeBy(settle + 1)

        // The passage the read was about to answer with is never reported,
        // so nothing downstream can act on it or store it against the book.
        assertEquals(listOf<String?>(null, null), seen)
    }

    @Test
    fun `a read that answers nothing leaves the passage standing`() = runTest {
        val events = events()
        val seen = mutableListOf<String?>()
        var answer: String? = "a word"
        follow(events, read = { answer }, onSelection = seen::add)

        events.emit(SelectionEvent.CHANGED)
        advanceTimeBy(settle + 1)
        answer = null
        events.emit(SelectionEvent.CHANGED)
        advanceTimeBy(settle + 1)

        assertEquals(listOf<String?>(null, "a word"), seen)
    }

    @Test
    fun `a selection made again after one was let go is reported`() = runTest {
        val events = events()
        val seen = mutableListOf<String?>()
        follow(events, read = { "a word" }, onSelection = seen::add)

        events.emit(SelectionEvent.CLEARED)
        advanceTimeBy(settle + 1)
        events.emit(SelectionEvent.CHANGED)
        advanceTimeBy(settle + 1)

        assertEquals(listOf<String?>(null, null, "a word"), seen)
    }
    @Test
    fun `following a page opens by reporting nothing selected`() = runTest {
        val events = events()
        val seen = mutableListOf<String?>()
        follow(events, read = { "a word" }, onSelection = seen::add)

        // A clear raised while the page was being swapped has nowhere to
        // land, so a collector that has just started says so itself rather
        // than letting the passage from the page before it stand.
        assertEquals(listOf<String?>(null), seen)
    }

    @Test
    fun `a bar dismissed by its own action does not come back when the page catches up`() = runTest {
        val events = events()
        var reads = 0
        val seen = mutableListOf<String?>()
        follow(events, read = { reads++; "a word" }, onSelection = seen::add)

        events.emit(SelectionEvent.CHANGED)
        advanceTimeBy(settle + 1)
        // The bar is up, and the page reports itself once more — a read is
        // now under way behind it.
        events.emit(SelectionEvent.CHANGED)
        advanceTimeBy(settle / 3)

        // The reader taps an action. dismissSelection() raises this before
        // asking the web view to drop the selection.
        events.emit(SelectionEvent.CLEARED)
        advanceTimeBy(settle * 2)

        // The web view gets round to destroying the action mode, which
        // raises the same event again.
        events.emit(SelectionEvent.CLEARED)
        advanceTimeBy(settle + 1)

        assertEquals("the read behind the bar must never have finished", 1, reads)
        assertEquals(listOf<String?>(null, "a word", null, null), seen)
    }
}
