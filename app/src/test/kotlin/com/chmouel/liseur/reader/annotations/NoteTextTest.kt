package com.chmouel.liseur.reader.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTextTest {
    private val createdMs = 1_700_000_000_000L

    @Test
    fun `a note saved in the same sitting is not edited`() {
        val updatedMicros = (createdMs + 3_000L) * 1000
        assertFalse(NoteText.edited(createdMs, updatedMicros))
    }

    @Test
    fun `a note reworked later is edited`() {
        val updatedMicros = (createdMs + 2 * 60 * 60 * 1000L) * 1000
        assertTrue(NoteText.edited(createdMs, updatedMicros))
    }

    @Test
    fun `an unstamped row is not edited`() {
        assertFalse(NoteText.edited(createdMs, 0L))
    }

    @Test
    fun `microseconds are not mistaken for milliseconds`() {
        // The same instant in both units must read as the same sitting.
        assertFalse(NoteText.edited(createdMs, createdMs * 1000))
    }

    @Test
    fun `share quotes the passage and puts the note under it`() {
        assertEquals(
            "\u201Cthe artist\u201D\n\nA good word for it.",
            NoteText.share("  the artist ", "A good word for it.\n"),
        )
    }

    @Test
    fun `share leaves out what is missing`() {
        assertEquals("\u201Cthe artist\u201D", NoteText.share("the artist", "  "))
        assertEquals("only the note", NoteText.share(null, "only the note"))
        assertEquals("", NoteText.share(null, null))
    }
}
