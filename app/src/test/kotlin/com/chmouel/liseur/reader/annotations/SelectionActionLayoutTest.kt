package com.chmouel.liseur.reader.annotations

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionActionLayoutTest {
    private val firstSite = "custom_6ed9c95b-9a2f-4f26-a573-d7356b0cd68e"
    private val secondSite = "custom_4bdbd75e-b8a0-4826-80ca-51c50660464d"

    @Test fun `reference actions are opt in when upgrading a customized layout`() {
        val layout = SelectionActionLayout.parse("share:both:My+share:heart,note:label")
        assertEquals("My share", layout.entries.first { it.action == SelectionAction.SHARE }.customLabel)
        for (action in listOf(SelectionAction.WIKIPEDIA, SelectionAction.OPEN_LIBRARY)) {
            assertEquals(SelectionActionStyle.HIDDEN, layout.entries.first { it.action == action }.style)
        }
    }

    @Test fun `multiple websites retain identity customization and ordering through persistence`() {
        val layout = SelectionActionLayout.default
            .withWebsite(firstSite, "Référence, 中文 : +", "https://example.org/search?q={text}&kind=book,author", SelectionActionIcon.STAR)
            .withWebsite(secondSite, "Other", "https://other.example/wiki/{text}", null)
            .move(firstSite, 0)
            .withStyle(firstSite, SelectionActionStyle.HIDDEN)
            .withWebsite(firstSite, "Renamed", "https://example.org/find/{text}?mode=a:b", SelectionActionIcon.HEART)
        assertEquals(firstSite, layout.entries.first().id)
        assertEquals(SelectionActionStyle.HIDDEN, layout.entries.first().style)
        assertEquals(layout, SelectionActionLayout.parse(layout.serialize()))
        assertEquals("Renamed", layout.withLabel(firstSite, " ").entries.first().customLabel)
        val removed = layout.removeWebsite(firstSite)
        assertEquals(listOf(secondSite), removed.entries.filter { it.action == null }.map { it.id })
        assertEquals(removed, removed.removeWebsite(SelectionAction.NOTE.id))
    }

    @Test fun `reset restores built ins and retains website definitions with default appearance`() {
        val layout = SelectionActionLayout.default.withStyle(SelectionAction.WIKIPEDIA.id, SelectionActionStyle.BOTH)
            .withWebsite(firstSite, "Mine", "https://example.org/?q={text}", SelectionActionIcon.HEART)
            .withStyle(firstSite, SelectionActionStyle.HIDDEN).move(firstSite, 0)
        val reset = layout.reset()
        assertEquals(SelectionActionLayout.default.entries, reset.entries.dropLast(1))
        assertEquals(firstSite, reset.entries.last().id)
        assertEquals("Mine", reset.entries.last().customLabel)
        assertEquals(SelectionActionStyle.BOTH, reset.entries.last().style)
        assertEquals(null, reset.entries.last().customIcon)
        assertEquals(reset, SelectionActionLayout.parse(reset.serialize()))
    }

    @Test fun `invalid website rows and duplicate ids do not lose valid saved entries`() {
        val good = SelectionActionLayout.default.withWebsite(firstSite, "Mine", "https://example.org/?q={text}", null)
        val bad = "$secondSite:both:Invalid::https%3A%2F%2F%7Btext%7D.example.org%2F"
        val layout = SelectionActionLayout.parse("$bad,${good.serialize()},$firstSite:label:Duplicate::https%3A%2F%2Fexample.org%2F%7Btext%7D")
        assertEquals(good, layout)
        assertEquals(good, good.withWebsite(firstSite, "Changed", "javascript:{text}", null))
        assertEquals(good, good.withWebsite(secondSite, " ", "https://example.org/{text}", null))
    }

    @Test fun `round trip preserves order styles labels with delimiters and custom icons`() {
        val layout = SelectionActionLayout.default.move(SelectionAction.SHARE.id, 0)
            .withStyle(SelectionAction.NOTE.id, SelectionActionStyle.BOTH)
            .withLabel(SelectionAction.SHARE.id, "Partager, citer : 100% + 中文 📖")
            .withIcon(SelectionAction.SHARE.id, SelectionActionIcon.HEART)
            .withIcon(SelectionAction.SEARCH.id, SelectionActionIcon.STAR)
        assertEquals(layout, SelectionActionLayout.parse(layout.serialize()))
    }

    @Test fun `icons survive other edits and reset independently`() {
        val layout = SelectionActionLayout.default
            .withIcon(SelectionAction.COPY.id, SelectionActionIcon.STAR)
            .withLabel(SelectionAction.COPY.id, "Keep this label")
            .withStyle(SelectionAction.COPY.id, SelectionActionStyle.BOTH)
            .move(SelectionAction.COPY.id, 0)
        assertEquals(SelectionActionIcon.STAR, layout.entries.first().customIcon)
        listOf(null, SelectionAction.COPY.defaultIcon).forEach { icon ->
            val reset = layout.withIcon(SelectionAction.COPY.id, icon)
            assertEquals(null, reset.entries.first().customIcon)
            assertEquals("Keep this label", reset.entries.first().customLabel)
            assertEquals(SelectionActionStyle.BOTH, reset.entries.first().style)
            assertEquals(reset, SelectionActionLayout.parse(reset.serialize()))
        }
    }

    @Test fun `legacy layouts and unknown icons keep labels and use default icons`() {
        listOf("share:label", "share:label:My%3A+label", "share:label:My%3A+label:future_icon").forEach { value ->
            val layout = SelectionActionLayout.parse(value)
            val share = layout.entries.first { it.action == SelectionAction.SHARE }
            assertEquals(null, share.customIcon)
            assertEquals(SelectionActionStyle.LABEL, share.style)
            assertEquals(if (value == "share:label") null else "My: label", share.customLabel)
            assertEquals(layout, SelectionActionLayout.parse(layout.serialize()))
        }
    }

    @Test fun `changing styles and order preserves labels and blank labels restore defaults`() {
        val layout = SelectionActionLayout.parse("share:label,note:icon")
            .withLabel(SelectionAction.NOTE.id, "  My note  ")
            .withStyle(SelectionAction.NOTE.id, SelectionActionStyle.BOTH)
            .move(SelectionAction.NOTE.id, 0)
        assertEquals("My note", layout.entries.first().customLabel)
        listOf(null, "", "  ").forEach { label ->
            val reset = layout.withLabel(SelectionAction.NOTE.id, label)
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
        assertEquals(SelectionActionStyle.ICON, layout.withStyle(SelectionAction.DELETE.id, SelectionActionStyle.HIDDEN)
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
