package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog the footer's two edges are chosen from.
 *
 * The ids are on the wire and in a file on the device, so they are
 * pinned here: renaming an entry in Kotlin must not silently move
 * every reader who chose it onto the default.
 */
class FooterFieldTest {

    @Test
    fun `the edges start where they have always been`() {
        assertEquals(FooterField.PERCENT_READ, FooterField.defaultFor(FooterSlot.LEFT))
        assertEquals(FooterField.PAGE_OF_BOOK, FooterField.defaultFor(FooterSlot.RIGHT))
    }

    @Test
    fun `an unknown id falls back to the slot it was asked for`() {
        assertEquals(
            FooterField.PERCENT_READ,
            FooterField.fromId("no such field", FooterSlot.LEFT),
        )
        assertEquals(
            FooterField.PAGE_OF_BOOK,
            FooterField.fromId(null, FooterSlot.RIGHT),
        )
    }

    @Test
    fun `an id means the same thing in either edge`() {
        for (field in FooterField.entries) {
            assertEquals(field, FooterField.fromId(field.id, FooterSlot.LEFT))
            assertEquals(field, FooterField.fromId(field.id, FooterSlot.RIGHT))
        }
    }

    @Test
    fun `no two fields share an id`() {
        val ids = FooterField.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    /**
     * Two things are frozen here, and both of them leave the device.
     * The ids are what DataStore keeps and what liseur-sync carries
     * between a reader's phone and their tablet, so renaming one
     * silently resets that edge everywhere. The order is the tap
     * cycle, so moving an entry changes what the next tap lands on
     * for every reader at once. Either is a deliberate act; this test
     * is here so it cannot be an accidental one.
     */
    @Test
    fun `the catalog keeps its order and its stored names`() {
        assertEquals(
            listOf(
                "percent",
                "percent_left",
                "page",
                "pages_left_book",
                "time_book",
                "location",
                "pages_chapter",
                "page_chapter",
                "percent_chapter",
                "percent_left_chapter",
                "time_chapter",
                "clock",
                "battery",
                "empty",
            ),
            FooterField.entries.map { it.id },
        )
    }

    @Test
    fun `tapping an edge enough times comes back round`() {
        var field = FooterField.PERCENT_READ
        val seen = mutableSetOf<FooterField>()
        repeat(FooterField.entries.size) {
            assertTrue("$field was reached twice", seen.add(field))
            field = field.next()
        }
        assertEquals(FooterField.PERCENT_READ, field)
    }

    @Test
    fun `an emptied edge can be filled again by tapping it`() {
        // Nothing a single tap does may need a settings screen to undo.
        assertNotEquals(FooterField.EMPTY, FooterField.EMPTY.next())
    }
}
