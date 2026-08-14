package com.chmouel.liseur.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A message that outlives the screen that raised it.
 *
 * Reorder mode ends, sometimes, by the series screen going away
 * underneath it — a rename or the last volume leaving — which is the one
 * moment the screen's own snackbar host cannot show anything. So the
 * message is held here and picked up by whichever screen is mounted,
 * and these are the ways that handover goes wrong.
 */
class NoticesTest {

    @Test
    fun `a raised message waits to be shown`() {
        val notices = Notices()
        assertNull(notices.current.value)

        notices.raise(NoticeKind.SeriesChangedWhileReordering)

        assertEquals(
            NoticeKind.SeriesChangedWhileReordering,
            notices.current.value?.kind,
        )
    }

    @Test
    fun `a message survives the screen that raised it`() {
        // The series screen refuses a commit and closes; the library
        // appears and finds the message still waiting.
        val notices = Notices()
        notices.raise(NoticeKind.SeriesChangedWhileReordering)

        val seenByTheLibrary = notices.current.value

        assertEquals(NoticeKind.SeriesChangedWhileReordering, seenByTheLibrary?.kind)
        notices.shown(seenByTheLibrary!!.id)
        assertNull(notices.current.value)
    }

    @Test
    fun `the same failure twice is two messages`() {
        // Not one value set twice: a StateFlow given a value equal to
        // the one it holds emits nothing, and the second refusal would
        // be silent.
        val notices = Notices()
        notices.raise(NoticeKind.SeriesChangedWhileReordering)
        val first = notices.current.value!!
        notices.raise(NoticeKind.SeriesChangedWhileReordering)
        val second = notices.current.value!!

        assertNotEquals(first.id, second.id)
        assertNotEquals(first, second)
    }

    @Test
    fun `acknowledging an old message does not wipe a newer one`() {
        val notices = Notices()
        notices.raise(NoticeKind.SeriesChangedWhileReordering)
        val stale = notices.current.value!!
        notices.raise(NoticeKind.SeriesChangedWhileReordering)
        val current = notices.current.value!!

        notices.shown(stale.id)

        assertEquals(current, notices.current.value)
    }

    @Test
    fun `acknowledging nothing is not a way to clear the message`() {
        // A snackbar coroutine cancelled by the branch changing under it
        // must not take the message with it.
        val notices = Notices()
        notices.raise(NoticeKind.SeriesChangedWhileReordering)
        val pending = notices.current.value!!

        notices.shown(pending.id + 99)

        assertEquals(pending, notices.current.value)
    }
}
