package com.chmouel.liseur.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeDeckTest {
    private val width = 1000f
    private val fling = 2000f

    @Test
    fun `a drag past the line rates left as Again and right as Good`() {
        assertEquals(Recall.AGAIN, swipeRating(-360f, false, 0f, width, fling, ReviewSwipe))
        assertEquals(Recall.GOOD, swipeRating(360f, false, 0f, width, fling, ReviewSwipe))
        assertNull(swipeRating(340f, false, 0f, width, fling, ReviewSwipe))
    }

    @Test
    fun `a fling rates early on review but never on Home`() {
        assertEquals(Recall.GOOD, swipeRating(260f, false, 3000f, width, fling, ReviewSwipe))
        assertNull(swipeRating(260f, false, -3000f, width, fling, ReviewSwipe))
        assertNull(swipeRating(200f, false, 3000f, width, fling, ReviewSwipe))
        assertNull(swipeRating(390f, false, 9000f, width, fling, HomeSwipe))
        assertEquals(Recall.AGAIN, swipeRating(-400f, false, 0f, width, fling, HomeSwipe))
    }

    @Test
    fun `the card stays armed a little short of the line once there`() {
        assertFalse(swipeArmed(false, 330f, width, ReviewSwipe))
        assertTrue(swipeArmed(false, 350f, width, ReviewSwipe))
        assertTrue(swipeArmed(true, 330f, width, ReviewSwipe))
        assertFalse(swipeArmed(true, 290f, width, ReviewSwipe))
    }

    @Test
    fun `an armed card rates when let go just short of the line`() {
        assertEquals(Recall.GOOD, swipeRating(330f, true, 0f, width, fling, ReviewSwipe))
        assertEquals(Recall.AGAIN, swipeRating(-380f, true, 0f, width, fling, HomeSwipe))
    }

    @Test
    fun `only a mostly sideways drag belongs to the card`() {
        assertTrue(swipeClaims(30f, 10f))
        assertFalse(swipeClaims(20f, 20f))
        assertFalse(swipeClaims(5f, -30f))
    }
}
