package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgePullTest {

    private val threshold = 100f

    /**
     * [forward] is travel towards later text, so the signs read the same
     * whichever way the book is set; [down]/[up] are the room the page
     * still has ahead of and behind the reader.
     */
    private fun EdgePull.move(forward: Float, down: Boolean = true, up: Boolean = true) =
        onMove(
            forwardTravel = forward,
            atForwardEdge = !down,
            atBackwardEdge = !up,
            threshold = threshold,
        )

    @Test
    fun `scrolling within a chapter turns nothing`() {
        val pull = EdgePull()
        assertEquals(EdgePull.Step.NONE, pull.move(50f))
        assertEquals(EdgePull.Step.NONE, pull.move(400f))
    }

    @Test
    fun `arriving at the end is not enough on its own`() {
        val pull = EdgePull()
        assertEquals(EdgePull.Step.NONE, pull.move(300f))
        assertEquals(EdgePull.Step.NONE, pull.move(380f, down = false))
    }

    @Test
    fun `dragging on past the end opens the next chapter`() {
        val pull = EdgePull()
        pull.move(300f)
        pull.move(320f, down = false)
        assertEquals(EdgePull.Step.NONE, pull.move(400f, down = false))
        assertEquals(EdgePull.Step.FORWARD, pull.move(421f, down = false))
    }

    @Test
    fun `the pull is measured from the end, not from where the drag began`() {
        val pull = EdgePull()
        // A drag that scrolls a long way and only just reaches the end
        // has travelled further than the threshold, and must not count.
        assertEquals(EdgePull.Step.NONE, pull.move(500f))
        assertEquals(EdgePull.Step.NONE, pull.move(560f, down = false))
    }

    @Test
    fun `dragging on past the top opens the chapter before`() {
        val pull = EdgePull()
        pull.move(-40f, up = false)
        assertEquals(EdgePull.Step.NONE, pull.move(-130f, up = false))
        assertEquals(EdgePull.Step.BACKWARD, pull.move(-141f, up = false))
    }

    @Test
    fun `one drag turns one chapter, however far it goes`() {
        val pull = EdgePull()
        pull.move(0f, down = false)
        assertEquals(EdgePull.Step.FORWARD, pull.move(100f, down = false))
        assertEquals(EdgePull.Step.NONE, pull.move(800f, down = false))
    }

    @Test
    fun `a new drag can turn again`() {
        val pull = EdgePull()
        pull.move(0f, down = false)
        assertEquals(EdgePull.Step.FORWARD, pull.move(100f, down = false))
        pull.reset()
        pull.move(0f, down = false)
        assertEquals(EdgePull.Step.FORWARD, pull.move(100f, down = false))
    }

    @Test
    fun `scrolling back off the end starts the measurement over`() {
        val pull = EdgePull()
        pull.move(200f, down = false)
        pull.move(260f)
        assertEquals(EdgePull.Step.NONE, pull.move(320f, down = false))
        assertEquals(EdgePull.Step.FORWARD, pull.move(421f, down = false))
    }

    @Test
    fun `a chapter shorter than the screen is left in either direction`() {
        val forward = EdgePull()
        forward.move(0f, down = false, up = false)
        assertEquals(EdgePull.Step.FORWARD, forward.move(100f, down = false, up = false))

        val backward = EdgePull()
        backward.move(0f, down = false, up = false)
        assertEquals(EdgePull.Step.BACKWARD, backward.move(-100f, down = false, up = false))
    }
}
