package com.chmouel.liseur.reader.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the selection bar is drawn from.
 *
 * Worth pinning rather than reading off the screen: the ordering is
 * what keeps a reader's muscle memory when the set changes, and the
 * hidden-colour case is the one where getting it wrong loses a mark the
 * reader made.
 */
class HighlightPaletteTest {

    @Test
    fun `three colours by default`() {
        val palette = HighlightPalette()

        assertEquals(
            listOf(HighlightTint.YELLOW, HighlightTint.GREEN, HighlightTint.BLUE),
            palette.shown,
        )
    }

    @Test
    fun `the bar keeps the palette's own order, not the order of choosing`() {
        val palette = HighlightPalette(
            offered = setOf(HighlightTint.ORANGE, HighlightTint.GREEN, HighlightTint.YELLOW),
        )

        assertEquals(
            listOf(HighlightTint.YELLOW, HighlightTint.GREEN, HighlightTint.ORANGE),
            palette.shown,
        )
    }

    @Test
    fun `the default does not jump the queue, and need not be offered at all`() {
        val palette = HighlightPalette(
            offered = setOf(HighlightTint.YELLOW, HighlightTint.PINK),
            default = HighlightTint.PINK,
        )

        assertEquals(listOf(HighlightTint.YELLOW, HighlightTint.PINK), palette.shown)
        assertEquals(
            listOf(HighlightTint.YELLOW),
            palette.toggled(HighlightTint.PINK).shown,
        )
    }

    @Test
    fun `adding a colour leaves the others where they were`() {
        val three = HighlightPalette()
        val four = three.toggled(HighlightTint.PINK)

        assertEquals(three.shown, four.shown.take(3))
        assertEquals(4, four.shown.size)
    }

    @Test
    fun `toggling a colour twice gets back to where it started`() {
        val palette = HighlightPalette()

        assertEquals(palette, palette.toggled(HighlightTint.PURPLE).toggled(HighlightTint.PURPLE))
        assertEquals(palette, palette.toggled(HighlightTint.YELLOW).toggled(HighlightTint.YELLOW))
    }

    @Test
    fun `the whole palette can be offered`() {
        val palette = HighlightPalette(offered = HighlightTint.entries.toSet())

        assertEquals(HighlightPalette.MAX_COUNT, palette.shown.size)
        assertEquals(HighlightTint.entries.toList(), palette.shown)
    }

    @Test
    fun `no colours at all is a palette the bar has to answer for`() {
        val palette = HighlightPalette(offered = emptySet())

        assertEquals(emptyList<HighlightTint>(), palette.shown)
        assertTrue(palette.isEmpty)
        assertFalse(HighlightPalette().isEmpty)
    }

    @Test
    fun `a mark in a hidden colour keeps a chip of its own`() {
        val palette = HighlightPalette(
            offered = setOf(HighlightTint.YELLOW, HighlightTint.GREEN),
        )

        assertEquals(
            listOf(HighlightTint.YELLOW, HighlightTint.GREEN, HighlightTint.ORANGE),
            palette.chipsFor(HighlightTint.ORANGE),
        )
    }

    @Test
    fun `a mark in a colour already offered adds nothing`() {
        val palette = HighlightPalette(
            offered = setOf(HighlightTint.YELLOW, HighlightTint.GREEN),
        )

        assertEquals(palette.shown, palette.chipsFor(HighlightTint.GREEN))
        assertEquals(palette.shown, palette.chipsFor(null))
    }

    @Test
    fun `an unmarked passage at no colours still shows nothing`() {
        val none = HighlightPalette(offered = emptySet())

        assertEquals(emptyList<HighlightTint>(), none.chipsFor(null))
    }

    @Test
    fun `a mark selected at no colours still shows its own`() {
        val none = HighlightPalette(offered = emptySet())

        assertTrue(none.isEmpty)
        assertEquals(listOf(HighlightTint.ORANGE), none.chipsFor(HighlightTint.ORANGE))
    }

    @Test
    fun `having chosen none is not the same as never having chosen`() {
        assertEquals(HighlightPalette.DEFAULT_OFFERED, HighlightPalette.of(null, null).offered)
        assertEquals(emptySet<HighlightTint>(), HighlightPalette.of(emptySet(), null).offered)
    }

    @Test
    fun `a colour name this build does not know is dropped from the set`() {
        val palette = HighlightPalette.of(setOf("YELLOW", "CHARTREUSE"), null)

        assertEquals(setOf(HighlightTint.YELLOW), palette.offered)
    }

    @Test
    fun `a default this build does not know falls back`() {
        assertEquals(HighlightTint.DEFAULT, HighlightPalette.of(null, "CHARTREUSE").default)
        assertEquals(HighlightTint.DEFAULT, HighlightPalette.of(null, null).default)
        assertEquals(HighlightTint.PURPLE, HighlightPalette.of(null, "PURPLE").default)
    }
}
