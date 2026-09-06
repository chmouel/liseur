package com.chmouel.liseur.reader.chrome

import com.chmouel.liseur.data.settings.PageTurnStyle
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

    @Test
    fun `a sideways swipe past the threshold turns one page`() {
        val turns = mutableListOf<Boolean>()
        val drag = drag(turns)
        assertTrue(drag.offer(pointers = 1, dx = -30f, dy = 2f))
        assertTrue(drag.offer(pointers = 1, dx = -200f, dy = 6f))
        assertTrue(drag.release())
        assertEquals(listOf(true), turns)
    }

    @Test
    fun `a gesture that set off down the page is never claimed later`() {
        val turns = mutableListOf<Boolean>()
        val drag = drag(turns)
        assertFalse(drag.offer(pointers = 1, dx = 2f, dy = -40f))
        // The finger curves until it has gone further across than down.
        assertFalse(drag.offer(pointers = 1, dx = -200f, dy = -60f))
        assertFalse(drag.release())
        assertTrue(turns.isEmpty())
    }

    @Test
    fun `a second finger settles the gesture for good`() {
        val turns = mutableListOf<Boolean>()
        val drag = drag(turns)
        assertTrue(drag.offer(pointers = 1, dx = -200f, dy = 4f))
        assertFalse(drag.offer(pointers = 2, dx = -200f, dy = 4f))
        // One finger leaves, the other keeps moving across the page.
        assertFalse(drag.offer(pointers = 1, dx = -300f, dy = 4f))
        assertFalse(drag.release())
        assertTrue(turns.isEmpty())
    }

    @Test
    fun `a fresh touch starts over`() {
        val turns = mutableListOf<Boolean>()
        val drag = drag(turns)
        assertFalse(drag.offer(pointers = 2, dx = -200f, dy = 4f))
        drag.reset()
        assertTrue(drag.offer(pointers = 1, dx = -200f, dy = 4f))
        assertTrue(drag.release())
        assertEquals(listOf(true), turns)
    }

    @Test
    fun `the slide is left to the navigator`() {
        val turns = mutableListOf<Boolean>()
        val drag = drag(turns, style = PageTurnStyle.SLIDE)
        assertFalse(drag.offer(pointers = 1, dx = -200f, dy = 4f))
        assertFalse(drag.release())
        assertTrue(turns.isEmpty())
    }

    @Test
    fun `a page that cannot be turned is left alone`() {
        val turns = mutableListOf<Boolean>()
        val drag = drag(turns, canTurn = false)
        assertFalse(drag.offer(pointers = 1, dx = -200f, dy = 4f))
        assertFalse(drag.release())
        assertTrue(turns.isEmpty())
    }

    private fun drag(
        turns: MutableList<Boolean>,
        style: PageTurnStyle = PageTurnStyle.LIFT,
        canTurn: Boolean = true,
    ) = PageTurnDrag(
        style = { style },
        canTurn = { canTurn },
        isRtl = { false },
        density = { 1f },
        onTurnPage = { turns += it },
    )
}
