package com.chmouel.liseur.reader.annotations

import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a row with no note edit stamp is not edited`() {
        assertFalse(NoteText.edited(createdMs, null))
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

    private val noted = BookAnnotation(
        id = "a",
        bookId = "b",
        kind = AnnotationKind.NOTE.name,
        locatorJson = "{}",
        note = "first thought",
        tint = HighlightTint.GREEN.name,
        createdAt = createdMs,
        noteCreatedAt = createdMs,
        noteUpdatedAt = 42L,
    )

    @Test
    fun `clearing a note leaves the highlight in the colour picked`() {
        val cleared = NoteText.cleared(noted, HighlightTint.BLUE)
        assertEquals(AnnotationKind.HIGHLIGHT.name, cleared.kind)
        assertNull(cleared.note)
        assertNull(cleared.noteCreatedAt)
        assertNull(cleared.noteUpdatedAt)
        assertEquals(HighlightTint.BLUE.name, cleared.tint)
        assertEquals(noted.id, cleared.id)
    }

    @Test
    fun `saving the same words in a new colour is not an edit`() {
        val stamps = NoteText.stamps(noted, "first thought", createdMs + 3_600_000L)
        assertEquals(createdMs, stamps.createdAt)
        assertEquals(42L, stamps.updatedAt)
    }

    @Test
    fun `rewording a note stamps it in microseconds`() {
        val now = createdMs + 3_600_000L
        assertEquals(now * 1000, NoteText.stamps(noted, "second thought", now).updatedAt)
    }

    @Test
    fun `a first note on a highlight is written now`() {
        val highlight = noted.copy(kind = AnnotationKind.HIGHLIGHT.name, note = null, noteCreatedAt = null, noteUpdatedAt = null)
        val now = createdMs + 5_000L
        val stamps = NoteText.stamps(highlight, "new", now)
        assertEquals(now, stamps.createdAt)
        assertNull(stamps.updatedAt)
    }
}
