package com.chmouel.liseur.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorPositionEventTest {

    @Test
    fun `only genuine reading teaches pace and records reading time`() {
        assertTrue(NavigatorPositionEvent.READER_MOVEMENT.persists)
        assertTrue(NavigatorPositionEvent.READER_MOVEMENT.teachesPace)
        assertTrue(NavigatorPositionEvent.READER_MOVEMENT.recordsReadingTime)

        assertTrue(NavigatorPositionEvent.LOCAL_JUMP.persists)
        assertFalse(NavigatorPositionEvent.LOCAL_JUMP.teachesPace)
        assertFalse(NavigatorPositionEvent.LOCAL_JUMP.recordsReadingTime)
    }

    @Test
    fun `remote and layout events only update the display`() {
        listOf(
            NavigatorPositionEvent.REMOTE_ADOPTION,
            NavigatorPositionEvent.PREFERENCE_REFLOW,
            NavigatorPositionEvent.FRAGMENT_RECREATION,
            NavigatorPositionEvent.LIFECYCLE_REPLAY,
            NavigatorPositionEvent.OPENING_RESTORATION,
        ).forEach { event ->
            assertFalse(event.persists)
            assertFalse(event.teachesPace)
            assertFalse(event.recordsReadingTime)
        }
    }

    @Test
    fun `a book being reopened is not reading anyone should be sent to`() {
        // The navigator reports where it is before it has finished being
        // told where to go. Persisting that pushed a position of zero to
        // the reader's other devices.
        assertFalse(NavigatorPositionEvent.OPENING_RESTORATION.persists)
    }
}
