package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class HeldPlaceTest {

    private fun place(progression: Double) = requireNotNull(
        Locator.fromJSON(
            JSONObject()
                .put("href", "https://example.com/chapter.xhtml")
                .put("type", "application/xhtml+xml")
                .put("locations", JSONObject().put("progression", progression)),
        ),
    )

    @Test
    fun `a place measured with nothing in between is kept`() {
        val held = HeldPlace()
        val since = held.mark()

        assertTrue(held.hold(place(0.4), since))
        assertEquals(0.4, held.current()?.locations?.progression)
    }

    @Test
    fun `retiring forgets the place`() {
        val held = HeldPlace()
        held.hold(place(0.4), held.mark())

        held.retire()

        assertNull(held.current())
    }

    @Test
    fun `a measurement started before a retire is refused`() {
        val held = HeldPlace()
        val since = held.mark()

        held.retire()

        assertFalse(held.hold(place(0.4), since))
        assertNull(held.current())
    }

    @Test
    fun `a measurement started after a retire is kept`() {
        val held = HeldPlace()
        held.hold(place(0.4), held.mark())
        held.retire()

        val since = held.mark()

        assertTrue(held.hold(place(0.7), since))
        assertEquals(0.7, held.current()?.locations?.progression)
    }

    @Test
    fun `only the measurement in flight across a retire is refused`() {
        val held = HeldPlace()
        val stale = held.mark()
        held.retire()
        val fresh = held.mark()
        held.hold(place(0.7), fresh)

        assertFalse(held.hold(place(0.4), stale))
        assertEquals(0.7, held.current()?.locations?.progression)
    }

    @Test
    fun `invalidating keeps the place standing`() {
        val held = HeldPlace()
        held.hold(place(0.4), held.mark())

        held.invalidate()

        assertEquals(0.4, held.current()?.locations?.progression)
    }

    @Test
    fun `a measurement in flight across an invalidate is refused`() {
        val held = HeldPlace()
        held.hold(place(0.4), held.mark())
        val stale = held.mark()

        held.invalidate()

        assertFalse(held.hold(place(0.2), stale))
        assertEquals(0.4, held.current()?.locations?.progression)
    }

    @Test
    fun `the mark an invalidate answers with still holds`() {
        val held = HeldPlace()
        held.hold(place(0.4), held.mark())

        val since = held.invalidate()

        assertTrue(held.hold(place(0.7), since))
        assertEquals(0.7, held.current()?.locations?.progression)
    }

    @Test
    fun `a place taken against an invalidate survives a later measurement it raced`() {
        val held = HeldPlace()
        val racing = held.mark()
        val since = held.invalidate()
        held.hold(place(0.7), since)

        assertFalse(held.hold(place(0.4), racing))
        assertEquals(0.7, held.current()?.locations?.progression)
    }

    @Test
    fun `of two measurements in the same generation the first to land stands`() {
        val held = HeldPlace()
        val newer = held.mark()
        val older = held.mark()

        assertTrue(held.hold(place(0.7), newer))
        assertFalse(held.hold(place(0.4), older))
        assertEquals(0.7, held.current()?.locations?.progression)
    }

    @Test
    fun `a place taken after a hold still lands`() {
        val held = HeldPlace()
        held.hold(place(0.4), held.mark())

        val since = held.mark()

        assertTrue(held.hold(place(0.7), since))
        assertEquals(0.7, held.current()?.locations?.progression)
    }
}
