package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FootnoteCardPlacementTest {

    @Test
    fun `a marker high on the page puts the note under it`() {
        assertEquals(248f, placeCard(anchorY = 200f, cardHeight = 400f, viewportHeight = 2400f))
    }

    @Test
    fun `a marker near the foot puts the note above it`() {
        val top = placeCard(anchorY = 2200f, cardHeight = 400f, viewportHeight = 2400f)
        assertEquals(1752f, top)
        assertTrue("must clear the marker", top + 400f <= 2200f)
    }

    @Test
    fun `a note too tall for either side is centred`() {
        val top = placeCard(anchorY = 1200f, cardHeight = 2000f, viewportHeight = 2400f)
        assertEquals(200f, top)
    }

    @Test
    fun `a note taller than the page still starts on it`() {
        val top = placeCard(anchorY = 1200f, cardHeight = 3000f, viewportHeight = 2400f)
        assertEquals(0f, top)
    }

    @Test
    fun `a marker at the very top is still cleared`() {
        val top = placeCard(anchorY = 0f, cardHeight = 300f, viewportHeight = 2400f)
        assertTrue("must not sit over the marker", top > 0f)
    }
}
