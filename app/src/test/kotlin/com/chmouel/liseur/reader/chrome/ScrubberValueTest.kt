package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrubberValueTest {

    @Test
    fun `the finger wins while dragging`() {
        assertEquals(0.8f, scrubberValue(dragged = 0.8f, held = null, progression = 0.2f))
        assertEquals(0.8f, scrubberValue(0.8f, ScrubberHold(from = 0.2f, to = 0.5f), 0.2f))
    }

    @Test
    fun `a released scrubber stays where it was dropped until the seek lands`() {
        assertEquals(0.8f, scrubberValue(dragged = null, held = ScrubberHold(0.2f, 0.8f), progression = 0.2f))
    }

    @Test
    fun `the reader's new place takes over once it is reported`() {
        assertEquals(0.79f, scrubberValue(dragged = null, held = ScrubberHold(0.2f, 0.8f), progression = 0.79f))
    }

    @Test
    fun `without a hold the scrubber follows the reader`() {
        assertEquals(0.35f, scrubberValue(dragged = null, held = null, progression = 0.35f))
    }
}
