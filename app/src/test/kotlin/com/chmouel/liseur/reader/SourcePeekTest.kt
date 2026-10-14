package com.chmouel.liseur.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SourcePeekTest {
    private val origin = Locator(href = Url("chapter5.xhtml")!!, mediaType = MediaType.XHTML)

    @Test
    fun `arriving at a source, reflowing and showing the chrome end nothing`() {
        val peek = SourcePeek(origin)
        NavigatorPositionEvent.entries.forEach { event ->
            assertFalse(event.name, peek.endedBy(event, moved = true))
        }
    }

    @Test
    fun `a page turn of the reader's own that moves ends the peek`() {
        val turned = SourcePeek(origin).turned()
        assertTrue(turned.endedBy(NavigatorPositionEvent.READER_MOVEMENT, moved = true))
        // The turn that hit the end of a chapter moved nowhere.
        assertFalse(turned.endedBy(NavigatorPositionEvent.READER_MOVEMENT, moved = false))
        assertFalse(turned.endedBy(NavigatorPositionEvent.PREFERENCE_REFLOW, moved = true))
    }

    @Test
    fun `nothing during a return ends it, and only its own answer counts`() {
        val returning = SourcePeek(origin).turned().returningNow()
        assertFalse(returning.userTurned)
        assertFalse(returning.turned().endedBy(NavigatorPositionEvent.READER_MOVEMENT, moved = true))
        assertTrue(returning.returnedBy(returning.returning))

        val again = returning.returnFailed(returning.returning).returningNow()
        assertFalse(again.returnedBy(returning.returning))
        // A late failure for the first Return leaves the second running.
        assertEquals(again, again.returnFailed(returning.returning))
        assertTrue(again.returnedBy(again.returning))
    }

    @Test
    fun `a failed return is a peek again, still recoverable`() {
        val returning = SourcePeek(origin).returningNow()
        val failed = returning.returnFailed(returning.returning)
        assertEquals(SourcePeek.Phase.PEEKING, failed.phase)
        assertEquals(origin, failed.origin)
        assertTrue(failed.turned().endedBy(NavigatorPositionEvent.READER_MOVEMENT, moved = true))
    }
}
