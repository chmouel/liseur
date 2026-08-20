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
        ).forEach { event ->
            assertFalse(event.persists)
            assertFalse(event.teachesPace)
            assertFalse(event.recordsReadingTime)
        }
    }
}
