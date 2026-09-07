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

    // Swipes: what the drag is when there is no curl to be had.

    @Test
    fun `a sideways swipe past the threshold turns one page`() {
        val h = Harness()
        assertTrue(h.drag.offer(pointers = 1, dx = -30f, dy = 2f))
        h.curl.ready(false)
        assertTrue(h.drag.offer(pointers = 1, dx = -200f, dy = 6f))
        assertTrue(h.drag.release())
        assertEquals(listOf(true), h.turns)
        assertEquals(1, h.curl.log.size)
    }

    @Test
    fun `a gesture that set off down the page is never claimed later`() {
        val h = Harness()
        assertFalse(h.drag.offer(pointers = 1, dx = 2f, dy = -40f))
        // The finger curves until it has gone further across than down.
        assertFalse(h.drag.offer(pointers = 1, dx = -200f, dy = -60f))
        assertFalse(h.drag.release())
        assertTrue(h.turns.isEmpty())
    }

    @Test
    fun `a second finger hands a swipe back to the web view`() {
        val h = Harness()
        assertTrue(h.drag.offer(pointers = 1, dx = -200f, dy = 4f))
        h.curl.ready(false)
        assertFalse(h.drag.offer(pointers = 2, dx = -200f, dy = 4f))
        // One finger leaves, the other keeps moving across the page.
        assertFalse(h.drag.offer(pointers = 1, dx = -300f, dy = 4f))
        assertFalse(h.drag.release())
        assertTrue(h.turns.isEmpty())
    }

    @Test
    fun `a fresh touch starts over`() {
        val h = Harness()
        assertFalse(h.drag.offer(pointers = 2, dx = -200f, dy = 4f))
        h.drag.reset()
        assertTrue(h.drag.offer(pointers = 1, dx = -200f, dy = 4f))
        h.curl.ready(false)
        assertTrue(h.drag.release())
        assertEquals(listOf(true), h.turns)
    }

    @Test
    fun `the slide is left to the navigator`() {
        val h = Harness(style = PageTurnStyle.SLIDE)
        assertFalse(h.drag.offer(pointers = 1, dx = -200f, dy = 4f))
        assertFalse(h.drag.release())
        assertTrue(h.turns.isEmpty())
        assertTrue(h.curl.log.isEmpty())
    }

    @Test
    fun `a page that cannot be turned is left alone`() {
        val h = Harness(canTurn = false)
        assertFalse(h.drag.offer(pointers = 1, dx = -200f, dy = 4f))
        assertFalse(h.drag.release())
        assertTrue(h.turns.isEmpty())
        assertTrue(h.curl.log.isEmpty())
    }

    @Test
    fun `electronic paper is left to the navigator`() {
        val h = Harness(interactive = false)
        assertFalse(h.drag.offer(pointers = 1, dx = -200f, dy = 4f))
        assertFalse(h.drag.release())
        assertTrue(h.turns.isEmpty())
        assertTrue(h.curl.log.isEmpty())
    }

    // Curls.

    @Test
    fun `a claimed drag begins a curl in its direction and follows the finger once it is in hand`() {
        val h = Harness()
        assertTrue(h.drag.offer(pointers = 1, dx = -30f, dy = 2f, downY = 900f))
        assertEquals(listOf("begin forward grab=900.0"), h.curl.log)
        // Travel while the page is being photographed is held, not lost.
        assertTrue(h.drag.offer(pointers = 1, dx = -80f, dy = 10f))
        assertEquals(1, h.curl.log.size)
        h.curl.ready(true)
        assertEquals("follow 80.0 dy=10.0", h.curl.log.last())
        assertTrue(h.drag.offer(pointers = 1, dx = -120f, dy = 12f))
        assertEquals("follow 120.0 dy=12.0", h.curl.log.last())
    }

    @Test
    fun `dragging right curls the page back, and in a right-to-left book forward`() {
        val ltr = Harness()
        ltr.drag.offer(pointers = 1, dx = 40f, dy = 0f)
        assertEquals("begin back grab=0.0", ltr.curl.log.single())

        val rtl = Harness(rtl = true)
        rtl.drag.offer(pointers = 1, dx = 40f, dy = 0f)
        assertEquals("begin forward grab=0.0", rtl.curl.log.single())
    }

    @Test
    fun `a finger back past where it started is a flat page, not the other turn`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -30f, dy = 0f)
        h.curl.ready(true)
        h.drag.offer(pointers = 1, dx = 50f, dy = 0f)
        assertEquals("follow 0.0 dy=0.0", h.curl.log.last())
    }

    @Test
    fun `letting go past the middle finishes the turn and the page is not turned twice`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -30f, dy = 0f)
        h.curl.ready(true)
        h.drag.offer(pointers = 1, dx = -500f, dy = 0f)
        assertTrue(h.drag.release(velocityX = 0f))
        assertEquals("finish v=-0.0", h.curl.log.last())
        assertTrue(h.turns.isEmpty())
    }

    @Test
    fun `letting go short of the middle puts the page back`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -30f, dy = 0f)
        h.curl.ready(true)
        h.drag.offer(pointers = 1, dx = -200f, dy = 0f)
        assertTrue(h.drag.release(velocityX = 0f))
        assertEquals("restore v=-0.0", h.curl.log.last())
    }

    @Test
    fun `a flick finishes a short pull, along the turn`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -30f, dy = 0f)
        h.curl.ready(true)
        h.drag.offer(pointers = 1, dx = -120f, dy = 0f)
        assertTrue(h.drag.release(velocityX = -2000f))
        // The screen velocity is leftwards; along the turn it is positive.
        assertEquals("finish v=2000.0", h.curl.log.last())
    }

    @Test
    fun `a flick back puts the page back however far it came`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -30f, dy = 0f)
        h.curl.ready(true)
        h.drag.offer(pointers = 1, dx = -800f, dy = 0f)
        assertTrue(h.drag.release(velocityX = 2000f))
        assertEquals("restore v=-2000.0", h.curl.log.last())
    }

    @Test
    fun `a release before the page is in hand is decided when it is`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -30f, dy = 0f)
        h.drag.offer(pointers = 1, dx = -600f, dy = 0f)
        assertTrue(h.drag.release())
        assertEquals(1, h.curl.log.size)
        h.curl.ready(true)
        assertEquals("finish v=-0.0", h.curl.log.last())
    }

    @Test
    fun `a curl refused after release becomes the swipe it would have been`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -200f, dy = 0f)
        assertTrue(h.drag.release())
        assertTrue(h.turns.isEmpty())
        h.curl.ready(false)
        assertEquals(listOf(true), h.turns)
    }

    @Test
    fun `a curl refused while the finger is still down goes on as a swipe`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -30f, dy = 0f)
        h.curl.ready(false)
        assertTrue(h.drag.offer(pointers = 1, dx = -200f, dy = 0f))
        assertEquals(1, h.curl.log.size)
        assertTrue(h.drag.release())
        assertEquals(listOf(true), h.turns)
    }

    @Test
    fun `a second finger puts a curled page down and keeps the gesture to the end`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -200f, dy = 0f)
        h.curl.ready(true)
        assertTrue(h.drag.offer(pointers = 2, dx = -200f, dy = 0f))
        assertEquals("restore v=0.0", h.curl.log.last())
        // The gesture stays ours until every finger has left.
        assertTrue(h.drag.offer(pointers = 1, dx = -400f, dy = 0f))
        assertTrue(h.drag.release(velocityX = -3000f))
        assertEquals("restore v=0.0", h.curl.log.last())
        assertTrue(h.turns.isEmpty())
    }

    @Test
    fun `a second finger before the page is in hand puts it down when it arrives`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -200f, dy = 0f)
        assertTrue(h.drag.offer(pointers = 2, dx = -200f, dy = 0f))
        assertTrue(h.drag.release())
        assertEquals(1, h.curl.log.size)
        h.curl.ready(true)
        assertEquals("restore v=0.0", h.curl.log.last())
    }

    @Test
    fun `a fresh touch over a curl in the air puts it down`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -200f, dy = 0f)
        h.drag.reset()
        h.curl.ready(true)
        assertEquals("restore v=0.0", h.curl.log.last())
    }

    @Test
    fun `giving up a held curl puts the book back without animating it`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -200f, dy = 0f)
        h.curl.ready(true)
        h.drag.abandon()
        assertEquals("abandon", h.curl.log.last())
        // Nothing is left of the gesture: the lift is not also a turn.
        assertFalse(h.drag.release(velocityX = -3000f))
        assertTrue(h.turns.isEmpty())
    }

    @Test
    fun `giving up before the page is in hand turns nothing when it arrives`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -200f, dy = 0f)
        h.drag.abandon()
        assertEquals("abandon", h.curl.log.last())
        // The photograph was still being taken; its answer finds the
        // gesture gone and must not start a swipe out of it.
        assertTrue(h.curl.awaiting)
        h.curl.ready(false)
        assertTrue(h.turns.isEmpty())
    }

    @Test
    fun `giving up when nothing is happening is harmless`() {
        val h = Harness()
        h.drag.abandon()
        assertEquals(listOf("abandon"), h.curl.log)
        // The next touch is an ordinary one.
        assertTrue(h.drag.offer(pointers = 1, dx = -200f, dy = 0f))
        assertEquals("begin forward grab=0.0", h.curl.log[1])
    }

    @Test
    fun `a curl given up after the fingers left is still put back`() {
        val h = Harness()
        h.drag.offer(pointers = 1, dx = -200f, dy = 0f)
        h.curl.ready(true)
        // Let go short of the commit: the page is settling back.
        assertTrue(h.drag.release())
        assertTrue(h.curl.log.last().startsWith("restore"))
        h.drag.abandon()
        assertEquals("abandon", h.curl.log.last())
    }

    private class FakeCurl : PageTurnDrag.Curl {
        val log = mutableListOf<String>()
        private var pending: ((Boolean) -> Unit)? = null

        override fun begin(forward: Boolean, grabY: Float, onReady: (Boolean) -> Unit) {
            log += "begin ${if (forward) "forward" else "back"} grab=$grabY"
            pending = onReady
        }

        override fun follow(travel: Float, dy: Float) {
            log += "follow $travel dy=$dy"
        }

        override fun finish(velocity: Float) {
            log += "finish v=$velocity"
        }

        override fun restore(velocity: Float) {
            log += "restore v=$velocity"
        }

        override fun abandon() {
            log += "abandon"
        }

        fun ready(inHand: Boolean) {
            val answer = pending ?: error("no curl was begun")
            pending = null
            answer(inHand)
        }

        /** Whether a photograph asked for has yet to be answered. */
        val awaiting: Boolean get() = pending != null
    }

    private class Harness(
        style: PageTurnStyle = PageTurnStyle.LIFT,
        canTurn: Boolean = true,
        interactive: Boolean = true,
        rtl: Boolean = false,
    ) {
        val turns = mutableListOf<Boolean>()
        val curl = FakeCurl()
        val drag = PageTurnDrag(
            style = { style },
            canTurn = { canTurn },
            interactive = { interactive },
            isRtl = { rtl },
            density = { 1f },
            width = { 1000f },
            onTurnPage = { turns += it },
            curl = curl,
        )
    }
}
