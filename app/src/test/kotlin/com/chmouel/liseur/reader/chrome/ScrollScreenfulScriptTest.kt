package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollScreenfulScriptTest {

    @Test
    fun `measures the visible part of the document, not the web view`() {
        val script = scrollScreenfulScript(forward = true, smooth = false)
        assertTrue(script.contains("e.clientHeight || window.innerHeight"))
        assertTrue(script.contains("e.scrollHeight - page"))
    }

    @Test
    fun `forward stops at the bottom and asks for the next chapter`() {
        val script = scrollScreenfulScript(forward = true, smooth = false)
        assertTrue(script.contains("""if (at >= max - 2) { return "at-end"; }"""))
        assertTrue(script.contains("at + page * 0.9"))
    }

    @Test
    fun `backward stops at the top and moves the other way`() {
        val script = scrollScreenfulScript(forward = false, smooth = false)
        assertTrue(script.contains("""if (at <= 2) { return "at-end"; }"""))
        assertTrue(script.contains("at + page * -0.9"))
    }

    @Test
    fun `the scroll never leaves the document`() {
        val script = scrollScreenfulScript(forward = true, smooth = false)
        assertTrue(script.contains("Math.max(0, Math.min(max, at + page * 0.9))"))
    }

    @Test
    fun `gliding follows the page turn animation`() {
        assertTrue(
            scrollScreenfulScript(forward = true, smooth = true).contains("""behavior: "smooth""""),
        )
        assertTrue(
            scrollScreenfulScript(forward = true, smooth = false).contains("""behavior: "auto""""),
        )
    }

    @Test
    fun `a book set in vertical lines is scrolled sideways`() {
        val script = scrollScreenfulScript(forward = true, smooth = false, vertical = true)
        assertTrue(script.contains("e.clientWidth || window.innerWidth"))
        assertTrue(script.contains("e.scrollWidth - page"))
        assertTrue(script.contains("Math.abs(e.scrollLeft)"))
        // The text runs right to left, so further in is further negative.
        assertTrue(script.contains("left: -to"))
        assertFalse(script.contains("scrollTop"))
        assertFalse(script.contains("scrollHeight"))
    }

    @Test
    fun `vertical lines end where their own axis runs out`() {
        val script = scrollScreenfulScript(forward = true, smooth = false, vertical = true)
        assertTrue(script.contains("""if (at >= max - 2) { return "at-end"; }"""))
        assertTrue(
            scrollScreenfulScript(forward = false, smooth = false, vertical = true)
                .contains("""if (at <= 2) { return "at-end"; }"""),
        )
    }

    @Test
    fun `the end is reported with the token the caller looks for`() {
        assertEquals("at-end", AT_END)
    }
}
