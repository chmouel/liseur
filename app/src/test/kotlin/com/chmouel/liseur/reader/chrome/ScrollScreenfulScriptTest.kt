package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
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
        assertTrue(script.contains("""if (top >= max - 2) { return "at-end"; }"""))
        assertTrue(script.contains("top + page * 0.9"))
    }

    @Test
    fun `backward stops at the top and moves the other way`() {
        val script = scrollScreenfulScript(forward = false, smooth = false)
        assertTrue(script.contains("""if (top <= 2) { return "at-end"; }"""))
        assertTrue(script.contains("top + page * -0.9"))
    }

    @Test
    fun `the scroll never leaves the document`() {
        val script = scrollScreenfulScript(forward = true, smooth = false)
        assertTrue(script.contains("Math.max(0, Math.min(max, top + page * 0.9))"))
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
    fun `the end is reported with the token the caller looks for`() {
        assertEquals("at-end", AT_END)
    }
}
