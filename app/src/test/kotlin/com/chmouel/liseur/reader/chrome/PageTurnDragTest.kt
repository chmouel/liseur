package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnDragTest {

    @Test
    fun `a drag that sets off across the page is claimed`() {
        assertTrue(PageTurnDrag.sideways(dx = -20f, dy = 4f))
        assertTrue(PageTurnDrag.sideways(dx = 20f, dy = -4f))
    }

    @Test
    fun `a drag that sets off up or down the page is left alone`() {
        assertFalse(PageTurnDrag.sideways(dx = 4f, dy = -20f))
        assertFalse(PageTurnDrag.sideways(dx = 0f, dy = 20f))
        // A perfect diagonal is nobody's page turn.
        assertFalse(PageTurnDrag.sideways(dx = 10f, dy = 10f))
    }

    @Test
    fun `a short swipe turns nothing`() {
        assertNull(PageTurnDrag.forward(travel = -47f, threshold = 48f, rtl = false))
        assertNull(PageTurnDrag.forward(travel = 47f, threshold = 48f, rtl = true))
    }

    @Test
    fun `dragging left goes forwards in a book read left to right`() {
        assertEquals(true, PageTurnDrag.forward(travel = -60f, threshold = 48f, rtl = false))
        assertEquals(false, PageTurnDrag.forward(travel = 60f, threshold = 48f, rtl = false))
    }

    @Test
    fun `dragging left goes back in a book read right to left`() {
        assertEquals(false, PageTurnDrag.forward(travel = -60f, threshold = 48f, rtl = true))
        assertEquals(true, PageTurnDrag.forward(travel = 60f, threshold = 48f, rtl = true))
    }
}
