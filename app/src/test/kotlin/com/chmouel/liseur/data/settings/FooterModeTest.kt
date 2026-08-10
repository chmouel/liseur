package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What tapping the reading footer does.
 *
 * The cycle used to include [FooterMode.NONE], which draws nothing. The
 * tap that reached it removed the footer, and with it the only place
 * left to tap, so the round could be entered but never finished.
 */
class FooterModeTest {

    private val shown = FooterMode.entries.filter { it != FooterMode.NONE }

    @Test
    fun `tapping never hides the footer`() {
        FooterMode.entries.forEach { mode ->
            assertFalse("$mode led to NONE", mode.next() == FooterMode.NONE)
        }
    }

    @Test
    fun `tapping from the last mode comes back to the first`() {
        assertEquals(shown.first(), shown.last().next())
    }

    @Test
    fun `tapping enough times shows everything`() {
        var mode = shown.first()
        val seen = mutableSetOf(mode)
        repeat(shown.size) {
            mode = mode.next()
            seen += mode
        }
        assertEquals(shown.toSet(), seen)
        assertEquals("the round should close", shown.first(), mode)
    }

    @Test
    fun `a footer hidden from the sheet is not a dead end`() {
        assertTrue(FooterMode.NONE.next() in shown)
    }

    @Test
    fun `an unknown stored mode falls back to the default`() {
        assertEquals(FooterMode.Default, FooterMode.fromId(null))
        assertEquals(FooterMode.Default, FooterMode.fromId("something else"))
        assertEquals(FooterMode.TIME_LEFT_BOOK, FooterMode.fromId("time_book"))
    }

    @Test
    fun `the single-slot modes of old resolve to the default middle`() {
        // "page" and "percent" filled the one slot the footer used to
        // have; both figures are now permanent edges, so the stored
        // preference should land on the default middle, not vanish.
        assertEquals(FooterMode.Default, FooterMode.fromId("page"))
        assertEquals(FooterMode.Default, FooterMode.fromId("percent"))
    }

    @Test
    fun `the default is something a book can always show`() {
        // SMART degrades to the chapter title until a pace is measured,
        // so the middle is never a stock guess presented as knowledge.
        assertEquals(FooterMode.SMART, FooterMode.Default)
    }
}
