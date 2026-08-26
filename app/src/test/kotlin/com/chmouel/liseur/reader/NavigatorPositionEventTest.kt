package com.chmouel.liseur.reader

import org.junit.Assert.assertEquals
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

    private fun classify(
        requested: NavigatorPositionEvent? = null,
        suppressed: Boolean = false,
        landed: Boolean = false,
        reflowActive: Boolean = false,
    ) = classifyNavigatorEmission(requested, suppressed, landed, reflowActive)

    @Test
    fun `an unmarked emission is the reader moving unless the page is being rebuilt`() {
        assertEquals(NavigatorPositionEvent.READER_MOVEMENT, classify().event)
        assertEquals(
            NavigatorPositionEvent.PREFERENCE_REFLOW,
            classify(reflowActive = true).event,
        )
    }

    @Test
    fun `a marker outranks a reflow but not the opening gate`() {
        assertEquals(
            NavigatorPositionEvent.LOCAL_JUMP,
            classify(requested = NavigatorPositionEvent.LOCAL_JUMP, reflowActive = true).event,
        )
        assertEquals(
            NavigatorPositionEvent.OPENING_RESTORATION,
            classify(requested = NavigatorPositionEvent.LOCAL_JUMP, suppressed = true).event,
        )
    }

    @Test
    fun `opening noise does not spend the marker owed to the arrival`() {
        // go() is asynchronous, so the position being left behind can be
        // reported first. If that emission takes the marker, the real
        // arrival is classified as reading: a jump would teach the pace
        // estimator a distance nobody read, and an adopted remote
        // position would be published straight back as local movement.
        val noise = classify(
            requested = NavigatorPositionEvent.REMOTE_ADOPTION,
            suppressed = true,
            landed = false,
        )
        assertEquals(NavigatorPositionEvent.OPENING_RESTORATION, noise.event)
        assertTrue(noise.markerSurvives)

        // Whether the arrival is suppressed or not, it is the navigation
        // completing, so it settles the marker either way.
        assertFalse(classify(NavigatorPositionEvent.REMOTE_ADOPTION, true, landed = true).markerSurvives)
        assertFalse(classify(NavigatorPositionEvent.REMOTE_ADOPTION).markerSurvives)
    }

    @Test
    fun `there is no marker to keep when none was set`() {
        assertFalse(classify(suppressed = true).markerSurvives)
        assertFalse(classify().markerSurvives)
    }
}
