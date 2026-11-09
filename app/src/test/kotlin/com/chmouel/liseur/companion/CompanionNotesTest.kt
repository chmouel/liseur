package com.chmouel.liseur.companion

import com.chmouel.liseur.reader.annotations.NoteMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionNotesTest {
    private val sections = listOf(
        "Who they are" to listOf(NoteItem("Keeps the ledger. "), NoteItem("Lives by the river.")),
        "Backstory" to emptyList(),
        "Relationships" to listOf(NoteItem("her brother", name = "Joss")),
    )

    @Test
    fun `an answer copies as headings and items, empty sections left out`() {
        assertEquals(
            "Mira\n\nAliases\n- M\n\nWho they are\n- Keeps the ledger.\n- Lives by the river.\n\nRelationships\n- Joss: her brother",
            CompanionNotes.text("Mira", sections, "Aliases" to "M").plain,
        )
    }

    @Test
    fun `an answer saves as a note with headings, aliases under the title and a lone item as a paragraph`() {
        assertEquals(
            "## Mira\n*M, the clerk*\n\n### Who they are\n- Keeps the ledger.\n- Lives by the river.\n\n" +
                "### Relationships\n**Joss**: her brother",
            CompanionNotes.text("Mira", sections, "Aliases" to "M, the clerk").markdown,
        )
        assertEquals("## Mira", CompanionNotes.text("Mira", emptyList(), "Aliases" to " ").markdown)
    }

    @Test
    fun `a lone item that would read as a list or quote stays an item`() {
        val note = CompanionNotes.text("Mira", listOf("Facts" to listOf(NoteItem("1. first"))))
        assertEquals("## Mira\n\n### Facts\n- 1. first", note.markdown)
    }

    @Test
    fun `stars and underscores from the model stay as written in the note`() {
        val note = CompanionNotes.text(
            "M*r_a",
            listOf("Facts" to listOf(NoteItem("2 * 3 *is* six, see snake_case_name or _this_ \\o/", name = "*Joss*"))),
            "Aliases" to "*M*",
        )

        val rendered = NoteMarkdown.render(note.markdown)

        assertEquals("M*r_a\n*M*\n\nFacts\n*Joss*: 2 * 3 *is* six, see snake_case_name or _this_ \\o/", rendered.text)
        val styled = rendered.styles.map { it.style to rendered.text.substring(it.start, it.end) }.toSet()
        assertEquals(
            setOf(
                NoteMarkdown.Style.HEADING2 to "M*r_a",
                NoteMarkdown.Style.ITALIC to "*M*",
                NoteMarkdown.Style.HEADING3 to "Facts",
                NoteMarkdown.Style.BOLD to "*Joss*",
            ),
            styled,
        )
        assertTrue(note.plain.startsWith("M*r_a\n\nAliases\n- *M*"))
    }

    @Test
    fun `a saved answer follows the reader's own note and never replaces it`() {
        assertEquals("Answer", CompanionNotes.appended(null, "Answer"))
        assertEquals("Answer", CompanionNotes.appended("  ", "Answer"))
        assertEquals("My thought\n\nAnswer", CompanionNotes.appended("My thought\n", "Answer"))
    }
}
