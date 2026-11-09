package com.chmouel.liseur.reader.annotations

import com.chmouel.liseur.reader.annotations.NoteMarkdown.Style
import com.chmouel.liseur.reader.annotations.NoteMarkdown.Styled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMarkdownTest {

    private fun shown(note: String) = NoteMarkdown.render(note)

    @Test
    fun `bold and italic lose their markers and keep their style`() {
        val rendered = shown("a **b** and *c* or _d_")
        assertEquals("a b and c or d", rendered.text)
        assertEquals(
            setOf(Styled(2, 3, Style.BOLD), Styled(8, 9, Style.ITALIC), Styled(13, 14, Style.ITALIC)),
            rendered.styles.toSet(),
        )
    }

    @Test
    fun `three stars are bold and italic at once`() {
        val rendered = shown("***both***")
        assertEquals("both", rendered.text)
        assertEquals(setOf(Styled(0, 4, Style.BOLD), Styled(0, 4, Style.ITALIC)), rendered.styles.toSet())
    }

    @Test
    fun `stars and underscores that are not emphasis stay as typed`() {
        listOf("2 * 3 * 4", "snake_case_name", "**never closed", "a ** b **", "\\*literal\\*").forEach { note ->
            val rendered = shown(note)
            assertTrue(note, rendered.styles.isEmpty())
        }
        assertEquals("2 * 3 * 4", shown("2 * 3 * 4").text)
        assertEquals("snake_case_name", shown("snake_case_name").text)
        assertEquals("*literal*", shown("\\*literal\\*").text)
    }

    @Test
    fun `lists and quotes are drawn and line breaks kept`() {
        val rendered = shown("- one\n* two\n3. three\n> said\nplain")
        assertEquals("•\u2002one\n•\u2002two\n3.\u2002three\n\u2003said\nplain", rendered.text)
        val quote = rendered.text.indexOf("said")
        assertTrue(Styled(quote, quote + 4, Style.ITALIC) in rendered.styles)
    }

    @Test
    fun `emphasis inside a list item is shown inside it`() {
        val rendered = shown("- a **big** deal")
        assertEquals("•\u2002a big deal", rendered.text)
        assertTrue(Styled(4, 7, Style.BOLD) in rendered.styles)
    }

    @Test
    fun `the editor keeps every character and dims the markers`() {
        assertEquals(
            setOf(Styled(0, 2, Style.MARKER), Styled(2, 3, Style.BOLD), Styled(3, 5, Style.MARKER)),
            NoteMarkdown.editorStyles("**b**").toSet(),
        )
        assertEquals(setOf(Styled(0, 2, Style.MARKER)), NoteMarkdown.editorStyles("- item").toSet())
    }
}
