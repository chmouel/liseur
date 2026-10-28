package com.chmouel.liseur.reader.annotations

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionActionLayoutTest {
    @Test fun `round trip preserves order styles and labels with delimiters and Unicode`() {
        val layout = SelectionActionLayout.default.move(SelectionAction.SHARE, 0)
            .withStyle(SelectionAction.NOTE, SelectionActionStyle.BOTH)
            .withLabel(SelectionAction.SHARE, "Partager, citer : 100% + 中文 📖")
        assertEquals(layout, SelectionActionLayout.parse(layout.serialize()))
    }

    @Test fun `changing styles and order preserves labels and blank labels restore defaults`() {
        val layout = SelectionActionLayout.parse("share:label,note:icon")
            .withLabel(SelectionAction.NOTE, "  My note  ")
            .withStyle(SelectionAction.NOTE, SelectionActionStyle.BOTH)
            .move(SelectionAction.NOTE, 0)
        assertEquals("My note", layout.entries.first().customLabel)
        listOf(null, "", "  ").forEach { label ->
            val reset = layout.withLabel(SelectionAction.NOTE, label)
            assertEquals(null, reset.entries.first().customLabel)
            assertEquals(reset, SelectionActionLayout.parse(reset.serialize()))
        }
    }

    @Test fun `malformed labels do not discard saved order or styles`() {
        val layout = SelectionActionLayout.parse("share:both:%ZZ,note:label:%20%20")
        assertEquals(listOf(SelectionAction.SHARE, SelectionAction.NOTE), layout.entries.map { it.action }
            .filter { it == SelectionAction.SHARE || it == SelectionAction.NOTE })
        val share = layout.entries.first { it.action == SelectionAction.SHARE }
        assertEquals(SelectionActionStyle.BOTH, share.style)
        assertEquals(null, share.customLabel)
        assertEquals(null, layout.entries.first { it.action == SelectionAction.NOTE }.customLabel)
    }

    @Test fun `unknown duplicates and invalid styles are sanitized`() {
        val layout = SelectionActionLayout.parse("share:label,unknown:icon,share:both,note:invalid,delete:hidden")
        assertEquals(SelectionActionStyle.LABEL, layout.entries.first { it.action == SelectionAction.SHARE }.style)
        assertEquals(SelectionActionStyle.ICON, layout.entries.first { it.action == SelectionAction.NOTE }.style)
        assertEquals(SelectionActionStyle.ICON, layout.entries.first { it.action == SelectionAction.DELETE }.style)
        assertEquals(SelectionAction.entries.size, layout.entries.size)
        assertEquals(SelectionActionStyle.ICON, layout.withStyle(SelectionAction.DELETE, SelectionActionStyle.HIDDEN)
            .entries.first { it.action == SelectionAction.DELETE }.style)
    }

    @Test fun `missing actions go before their first default successor without reordering saved actions`() {
        val layout = SelectionActionLayout.parse("share:icon,search:label,note:icon")
        val actions = layout.entries.map { it.action }
        assertEquals(listOf(SelectionAction.SHARE, SelectionAction.SEARCH, SelectionAction.NOTE),
            actions.filter { it in setOf(SelectionAction.SHARE, SelectionAction.SEARCH, SelectionAction.NOTE) })
        assertEquals(actions.indexOf(SelectionAction.SEARCH) - 1, actions.indexOf(SelectionAction.COMPANION))
        assertEquals(actions.indexOf(SelectionAction.COMPANION) - 1, actions.indexOf(SelectionAction.LOOK_UP))
    }

    @Test fun `citation omits missing fields`() {
        assertEquals("passage", citationText("passage", null, emptyList()))
        assertEquals("passage\n\nTitle", citationText("passage", "Title", listOf("")))
        assertEquals("passage\n\nA, B", citationText("passage", " ", listOf("A", " B ")))
        assertEquals("passage\n\nTitle — A, B", citationText("passage", "Title", listOf("A", "B")))
    }
}
