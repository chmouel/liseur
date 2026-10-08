package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrubberDragTest {

    @Test
    fun `the finger wins while dragging`() {
        val drag = ScrubberDrag()
        drag.drag(0.8f)
        drag.progressed(0.25f)
        assertEquals(0.8f, drag.value(0.25f))
    }

    @Test
    fun `a dropped scrubber stays put until the jump lands, then follows the reader`() {
        val drag = ScrubberDrag()
        drag.drag(0.8f)
        assertEquals(0.8f, drag.release())
        drag.hold(from = 0.2f, to = 0.8f)
        assertEquals(0.8f, drag.value(0.2f))

        drag.progressed(0.79f)
        assertEquals(0.79f, drag.value(0.79f))
    }

    @Test
    fun `going back to where the drag started shows that place, not the drop`() {
        val drag = ScrubberDrag()
        drag.drag(0.8f)
        drag.release()
        drag.hold(from = 0.2f, to = 0.8f)
        drag.progressed(0.79f)

        drag.progressed(0.2f)
        assertEquals(0.2f, drag.value(0.2f))
    }

    @Test
    fun `a tap that changes nothing does not seek`() {
        val drag = ScrubberDrag()
        assertNull(drag.release())

        drag.drag(0.6f)
        drag.release()
        assertNull("an earlier drag must not be sought again", drag.release())
    }

    @Test
    fun `a new drop that holds nothing does not bring back the old one`() {
        val drag = ScrubberDrag()
        drag.drag(0.8f)
        drag.release()
        drag.hold(from = 0.2f, to = 0.8f)

        // Dropped back on the page shown, or refused: no new hold.
        drag.drag(0.2f)
        drag.release()
        assertNull(drag.held)
        assertEquals(0.2f, drag.value(0.2f))
    }

    @Test
    fun `a tap that changes nothing keeps the drop it follows`() {
        val drag = ScrubberDrag()
        drag.drag(0.8f)
        drag.release()
        drag.hold(from = 0.2f, to = 0.8f)

        drag.release()
        assertEquals(0.8f, drag.value(0.2f))
    }

    @Test
    fun `a hold that never hears back runs out`() {
        val drag = ScrubberDrag()
        drag.hold(from = 0.2f, to = 0.8f)
        val first = drag.held!!
        drag.expire(first)
        assertEquals(0.2f, drag.value(0.2f))
    }

    @Test
    fun `an older hold running out leaves a newer drop in place`() {
        val drag = ScrubberDrag()
        drag.hold(from = 0.2f, to = 0.8f)
        val first = drag.held!!
        drag.hold(from = 0.2f, to = 0.8f)
        drag.expire(first)
        assertEquals(0.8f, drag.value(0.2f))
    }
}
