package com.chmouel.liseur.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionNotesTest {
    @Test
    fun `an answer reads as headings and items, empty sections left out`() {
        val text = CompanionNotes.text(
            "Mira",
            listOf("Aliases" to listOf(""), "Who they are" to listOf("Keeps the ledger. ", "Lives by the river."), "Backstory" to emptyList()),
        )

        assertEquals("Mira\n\nWho they are\n- Keeps the ledger.\n- Lives by the river.", text)
    }

    @Test
    fun `a saved answer follows the reader's own note and never replaces it`() {
        assertEquals("Answer", CompanionNotes.appended(null, "Answer"))
        assertEquals("Answer", CompanionNotes.appended("  ", "Answer"))
        assertEquals("My thought\n\nAnswer", CompanionNotes.appended("My thought\n", "Answer"))
    }
}
