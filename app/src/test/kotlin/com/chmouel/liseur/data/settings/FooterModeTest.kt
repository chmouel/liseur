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
        assertEquals(FooterMode.PERCENT, FooterMode.fromId("percent"))
    }
}
