package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.reader.progress.SectionScreenProgress.Geometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionScreenProgressTest {

    private fun screens(width: Double, span: Double, offset: Double) =
        SectionScreenProgress.screens(Geometry(width, span, offset))

    @Test
    fun `reads the three numbers the document reports`() {
        val geometry = SectionScreenProgress.parse("\"411.43 4937.16 822.86\"")!!
        assertEquals(411.43, geometry.screenWidth, 0.0001)
        assertEquals(4937.16, geometry.span, 0.0001)
        assertEquals(822.86, geometry.offset, 0.0001)
    }

    @Test
    fun `declines every answer that is not three numbers`() {
        assertNull(SectionScreenProgress.parse(null))
        assertNull(SectionScreenProgress.parse(""))
        assertNull(SectionScreenProgress.parse("   "))
        assertNull(SectionScreenProgress.parse("null"))
        assertNull(SectionScreenProgress.parse("\"null\""))
        assertNull(SectionScreenProgress.parse("\"411 4937\""))
        assertNull(SectionScreenProgress.parse("\"411 4937 822 6\""))
        assertNull(SectionScreenProgress.parse("\"411 wide 822\""))
    }

    @Test
    fun `counts one screen per turn through a resource`() {
        val width = 400.0
        val span = width * 6
        val counted = (0 until 6).map { screens(width, span, width * it)!!.screen }

        assertEquals(listOf(1, 2, 3, 4, 5, 6), counted)
        assertEquals(6, screens(width, span, 0.0)!!.screens)
    }

    /**
     * The whole point of the number: at a size where a Readium position
     * spans several screens, every turn still moves it by one.
     */
    @Test
    fun `a turn always moves the number`() {
        val width = 411.0
        val span = width * 12
        var last = 0
        for (turn in 0 until 12) {
            val here = screens(width, span, width * turn)!!.screen
            assertEquals(last + 1, here)
            last = here
        }
    }

    /**
     * `clientWidth` is an integer and the width Readium turns by is not.
     * Counting the offset against the reported width alone drifts by a
     * whole screen deep into a long resource; recovering the real width
     * from the span does not.
     */
    @Test
    fun `an integer viewport does not drift over a long resource`() {
        val real = 411.4285714285714
        val reported = 411.0
        val total = 300
        val span = real * total

        assertEquals(total, screens(reported, span, 0.0)!!.screens)
        for (turn in listOf(0, 1, 57, 150, 298, 299)) {
            assertEquals(turn + 1, screens(reported, span, real * turn)!!.screen)
        }
    }

    @Test
    fun `a resource shorter than the screen is one screen`() {
        val only = screens(400.0, 180.0, 0.0)!!
        assertEquals(1, only.screen)
        assertEquals(1, only.screens)
    }

    /**
     * A two-column spread is one turn, because Readium splits one
     * viewport width into two columns and scrolls by the whole of it.
     * Nothing here has to know that: the width it measures is the
     * viewport's, not the column's.
     */
    @Test
    fun `a two column spread counts as the one turn it is`() {
        val viewport = 800.0
        val span = viewport * 5
        assertEquals(5, screens(viewport, span, 0.0)!!.screens)
        assertEquals(3, screens(viewport, span, viewport * 2)!!.screen)
    }

    /**
     * Right to left scrolls to negative offsets. The script reports the
     * distance rather than the direction, so the first screen is still
     * the first one.
     */
    @Test
    fun `a right to left book counts from its own first screen`() {
        val width = 400.0
        val span = width * 4
        assertEquals(1, screens(width, span, 0.0)!!.screen)
        assertEquals(4, screens(width, span, width * 3)!!.screen)
    }

    @Test
    fun `a last column short of a full screen still lands on the last screen`() {
        // Readium pads a spread with a virtual column, but a single
        // column resource can end wherever the text ends.
        val width = 400.0
        val span = width * 9 + 150
        val measured = screens(width, span, width * 8)!!
        assertEquals(9, measured.screens)
        assertEquals(9, measured.screen)
    }

    @Test
    fun `an offset past the end never leaves the resource`() {
        val measured = screens(400.0, 1600.0, 99_999.0)!!
        assertEquals(4, measured.screen)
        assertEquals(4, measured.screens)
    }

    @Test
    fun `refuses geometry no page could have`() {
        assertNull(screens(0.0, 1600.0, 0.0))
        assertNull(screens(-400.0, 1600.0, 0.0))
        assertNull(screens(400.0, 0.0, 0.0))
        assertNull(screens(400.0, -1600.0, 0.0))
        assertNull(screens(400.0, 1600.0, -1.0))
        assertNull(screens(Double.NaN, 1600.0, 0.0))
        assertNull(screens(400.0, Double.POSITIVE_INFINITY, 0.0))
        assertNull(screens(400.0, 1600.0, Double.NaN))
    }

    /** A view being measured is not a view being read. */
    @Test
    fun `refuses a viewport too narrow to be a screen`() {
        assertNull(screens(4.0, 1600.0, 0.0))
    }

    @Test
    fun `refuses a count that can only be a mismeasurement`() {
        assertNull(screens(20.0, 20.0 * 20_001, 0.0))
        assertEquals(20_000, screens(20.0, 20.0 * 20_000, 0.0)!!.screens)
    }

    @Test
    fun `asks the document only what it can answer without being moved`() {
        val script = SectionScreenProgress.SCRIPT
        assertTrue(script.contains("e.clientWidth"))
        assertTrue(script.contains("e.scrollWidth"))
        assertTrue(script.contains("window.scrollX"))
        // Declines the modes it cannot count, rather than guessing.
        assertTrue(script.contains("readium-scroll-on"))
        assertTrue(script.contains("writing.indexOf(\"vertical\")"))
        // Nothing in the page is written to.
        assertTrue(!script.contains("scrollTo"))
        assertTrue(!script.contains("setAttribute"))
        assertTrue(!script.contains("appendChild"))
    }
}
