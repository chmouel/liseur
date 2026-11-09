package com.chmouel.liseur.reader.annotations

import com.chmouel.liseur.reader.annotations.NoteFormatting.Edit
import com.chmouel.liseur.reader.annotations.NoteFormatting.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFormattingTest {

    private fun toggle(edit: Edit, format: Format) = NoteFormatting.toggle(edit, format)

    @Test
    fun `bold wraps the selected words and keeps them selected`() {
        assertEquals(Edit("say **hello** now", 6, 11), toggle(Edit("say hello now", 4, 9), Format.BOLD))
    }

    @Test
    fun `bold leaves the spaces at the edges of a selection outside`() {
        assertEquals("say **hello** now", toggle(Edit("say hello now", 3, 10), Format.BOLD).text)
    }

    @Test
    fun `bold comes off words already bold`() {
        assertEquals(Edit("say hello now", 4, 9), toggle(Edit("say **hello** now", 6, 11), Format.BOLD))
        assertEquals("say hello now", toggle(Edit("say **hello** now", 4, 13), Format.BOLD).text)
        assertEquals(Edit("x word y", 3), toggle(Edit("x **word** y", 5), Format.BOLD))
    }

    @Test
    fun `bold over words partly bold makes one run`() {
        assertEquals(Edit("**one two three**", 2, 15), toggle(Edit("one **two** three", 0, 17), Format.BOLD))
        assertEquals("**one two three**", toggle(Edit("**one two** three", 4, 17), Format.BOLD).text)
    }

    @Test
    fun `with nothing selected italic opens a pair and a second press takes it back`() {
        val opened = toggle(Edit("ab", 1), Format.ITALIC)
        assertEquals(Edit("a**b", 2), opened)
        assertEquals(Edit("ab", 1), toggle(opened, Format.ITALIC))
    }

    @Test
    fun `pressing bold again after typing a word steps out of it`() {
        var edit = toggle(Edit("for ", 4), Format.BOLD)
        edit = Edit(edit.text.substring(0, edit.start) + "rum" + edit.text.substring(edit.start), edit.start + 3)
        assertEquals(Edit("for **rum**", 11), toggle(edit, Format.BOLD))
        assertEquals(Edit("*a* b", 3), toggle(Edit("*a* b", 2), Format.ITALIC))
    }

    @Test
    fun `a selection over two lines is wrapped line by line`() {
        assertEquals("**one**\n**two**", toggle(Edit("one\ntwo", 0, 7), Format.BOLD).text)
        assertEquals("one\ntwo", toggle(Edit("**one**\n**two**", 0, 15), Format.BOLD).text)
    }

    @Test
    fun `bullets go on every selected line and come off again`() {
        val listed = toggle(Edit("one\ntwo", 0, 7), Format.BULLET)
        assertEquals(Edit("- one\n- two", 2, 11), listed)
        assertEquals(Edit("one\ntwo", 0, 7), toggle(listed, Format.BULLET))
    }

    @Test
    fun `numbering replaces bullets and counts from one`() {
        assertEquals("1. a\n2. b", toggle(Edit("- a\n- b", 0, 7), Format.NUMBERED).text)
    }

    @Test
    fun `a list starts on an empty line`() {
        assertEquals(Edit("a\n> ", 4), toggle(Edit("a\n", 2), Format.QUOTE))
    }

    @Test
    fun `a selection ending at the start of a line leaves that line alone`() {
        assertEquals("- one\ntwo", toggle(Edit("one\ntwo", 0, 4), Format.BULLET).text)
    }

    @Test
    fun `enter at the end of an item starts the next one`() {
        assertEquals(
            Edit("- milk\n- ", 9),
            NoteFormatting.continueList(Edit("- milk", 6), Edit("- milk\n", 7)),
        )
        assertEquals(
            Edit("  9) a\n  10) ", 13),
            NoteFormatting.continueList(Edit("  9) a", 6), Edit("  9) a\n", 7)),
        )
    }

    @Test
    fun `enter on an empty item ends the list`() {
        assertEquals(
            Edit("- a\n", 4),
            NoteFormatting.continueList(Edit("- a\n- ", 6), Edit("- a\n- \n", 7)),
        )
    }

    @Test
    fun `enter anywhere else is left to the field`() {
        assertNull(NoteFormatting.continueList(Edit("plain", 5), Edit("plain\n", 6)))
        assertNull(NoteFormatting.continueList(Edit("- a", 3), Edit("- ab", 4)))
    }

    @Test
    fun `the toolbar shows what is in force at the cursor`() {
        assertTrue(Format.BOLD in NoteFormatting.active(Edit("x **word** y", 5)))
        assertEquals(setOf(Format.BULLET, Format.ITALIC), NoteFormatting.active(Edit("- an *idea*", 7)))
    }
}
