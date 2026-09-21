package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the footer is worth the band of paper the page keeps for it.
 *
 * The reservation is made before the page lays itself out, so this
 * answer and the drawing have to agree or the book gets a strip of
 * blank paper with nothing on it.
 */
class FooterVisibilityTest {

    @Test
    fun `the usual footer is drawn`() {
        assertTrue(
            footerHasAnythingToSay(
                FooterMode.SMART,
                FooterField.PERCENT_READ,
                FooterField.PAGE_OF_BOOK,
            ),
        )
    }

    @Test
    fun `hiding the footer hides it whatever the edges say`() {
        assertFalse(
            footerHasAnythingToSay(
                FooterMode.NONE,
                FooterField.PERCENT_READ,
                FooterField.PAGE_OF_BOOK,
            ),
        )
    }

    @Test
    fun `a footer emptied slot by slot takes its band back`() {
        assertFalse(
            footerHasAnythingToSay(
                FooterMode.EMPTY,
                FooterField.EMPTY,
                FooterField.EMPTY,
            ),
        )
    }

    @Test
    fun `one slot still speaking keeps the footer`() {
        assertTrue(
            footerHasAnythingToSay(FooterMode.EMPTY, FooterField.CLOCK, FooterField.EMPTY),
        )
        assertTrue(
            footerHasAnythingToSay(FooterMode.EMPTY, FooterField.EMPTY, FooterField.CLOCK),
        )
        assertTrue(
            footerHasAnythingToSay(FooterMode.SMART, FooterField.EMPTY, FooterField.EMPTY),
        )
    }

    @Test
    fun `an edge tap steps to the next field`() {
        val next = nextFooterField(
            FooterSlot.LEFT,
            FooterMode.SMART,
            FooterField.PERCENT_READ,
            FooterField.PAGE_OF_BOOK,
        )
        assertEquals(FooterField.PERCENT_READ.next(), next)
    }

    @Test
    fun `the last slot drawing cannot be emptied by a tap`() {
        // The left edge is one step from EMPTY and is all that is left.
        val before = FooterField.entries[FooterField.EMPTY.ordinal - 1]
        val next = nextFooterField(
            FooterSlot.LEFT,
            FooterMode.EMPTY,
            before,
            FooterField.EMPTY,
        )
        assertNotEquals(FooterField.EMPTY, next)
        assertTrue(footerHasAnythingToSay(FooterMode.EMPTY, next, FooterField.EMPTY))
    }

    @Test
    fun `the same holds for the right edge and the middle`() {
        val beforeEmpty = FooterField.entries[FooterField.EMPTY.ordinal - 1]
        val right = nextFooterField(
            FooterSlot.RIGHT,
            FooterMode.EMPTY,
            FooterField.EMPTY,
            beforeEmpty,
        )
        assertTrue(footerHasAnythingToSay(FooterMode.EMPTY, FooterField.EMPTY, right))

        var mode = FooterMode.SMART
        // Walk the middle round with both edges empty; it must never
        // land anywhere that takes the footer off the page.
        repeat(FooterMode.entries.size * 2) {
            mode = nextFooterMode(mode, FooterField.EMPTY, FooterField.EMPTY)
            assertTrue(
                "middle landed on $mode with both edges empty",
                footerHasAnythingToSay(mode, FooterField.EMPTY, FooterField.EMPTY),
            )
        }
    }

    @Test
    fun `an edge emptied by a tap is still allowed while another slot speaks`() {
        val before = FooterField.entries[FooterField.EMPTY.ordinal - 1]
        val next = nextFooterField(
            FooterSlot.LEFT,
            FooterMode.SMART,
            before,
            FooterField.EMPTY,
        )
        assertEquals(FooterField.EMPTY, next)
    }
}
