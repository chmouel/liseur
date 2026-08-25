package com.chmouel.liseur.reader.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollProgressionTest {

    @Test
    fun `reads the fraction the document gives back`() {
        assertEquals(0.25, ScrollProgression.parse("0.25")!!, 0.0)
        assertEquals(0.25, ScrollProgression.parse("\"0.25\"")!!, 0.0)
        assertEquals(0.0, ScrollProgression.parse(" 0 ")!!, 0.0)
    }

    @Test
    fun `clamps an offset that overshoots its own length`() {
        assertEquals(1.0, ScrollProgression.parse("1.4")!!, 0.0)
        assertEquals(0.0, ScrollProgression.parse("-0.2")!!, 0.0)
    }

    /**
     * Every one of these is a page that cannot say where it is, and the
     * caller must save nothing rather than file the reader at the top of
     * the chapter.
     */
    @Test
    fun `declines every answer that is not a fraction`() {
        assertNull(ScrollProgression.parse(null))
        assertNull(ScrollProgression.parse(""))
        assertNull(ScrollProgression.parse("   "))
        assertNull(ScrollProgression.parse("null"))
        assertNull(ScrollProgression.parse("\"null\""))
        assertNull(ScrollProgression.parse("undefined"))
        assertNull(ScrollProgression.parse("NaN"))
        assertNull(ScrollProgression.parse("Infinity"))
    }

    @Test
    fun `measures the axis the book is scrolled along`() {
        val across = ScrollProgression.script(vertical = false)
        assertTrue(across.contains("e.scrollTop"))
        assertTrue(across.contains("e.scrollHeight"))

        val down = ScrollProgression.script(vertical = true)
        assertTrue(down.contains("Math.abs(e.scrollLeft)"))
        assertTrue(down.contains("e.scrollWidth"))
    }

    @Test
    fun `refuses to divide by a page that has no length yet`() {
        assertTrue(ScrollProgression.script(vertical = false).contains("if (!(span > 0))"))
    }
}
