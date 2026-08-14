package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesOrderTest {

    @Test
    fun `moving the first book down`() {
        assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").movedItem(0, 1))
    }

    @Test
    fun `moving the last book up`() {
        assertEquals(listOf("c", "a", "b"), listOf("a", "b", "c").movedItem(2, 0))
    }

    @Test
    fun `moving a book onto itself changes nothing`() {
        val order = listOf("a", "b", "c")
        assertEquals(order, order.movedItem(1, 1))
    }

    @Test
    fun `a move off the end is not a move`() {
        val order = listOf("a", "b", "c")
        assertEquals(order, order.movedItem(0, 3))
        assertEquals(order, order.movedItem(-1, 0))
        assertTrue(emptyList<String>().movedItem(0, 0).isEmpty())
    }

    @Test
    fun `a shelf nobody moved is not renumbered`() {
        val order = listOf("a", "b", "c")
        assertTrue(renumbered(order, order).isEmpty())
    }

    @Test
    fun `a moved shelf is numbered one to n`() {
        assertEquals(
            listOf("b" to 1.0, "a" to 2.0, "c" to 3.0),
            renumbered(listOf("b", "a", "c"), listOf("a", "b", "c")),
        )
    }

    @Test
    fun `every volume is written, not only the ones that changed`() {
        // "c" is third before and after, and is still written: left
        // alone it would keep no override, and the next catalog refresh
        // could move it back out of the order just set.
        val written = renumbered(listOf("b", "a", "c"), listOf("a", "b", "c"))
        assertEquals(listOf("b", "a", "c"), written.map { it.first })
    }

    @Test
    fun `a shelf already in order but unnumbered is left alone`() {
        // Nothing moved, so nothing is written. Opening the mode and
        // closing it again must not renumber a series.
        assertTrue(renumbered(listOf("a"), listOf("a")).isEmpty())
        assertTrue(renumbered(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `half numbers are flattened away`() {
        // The shelf ran 1, 1.5, 2 and comes out 1, 2, 3. The novella
        // keeps its place and loses its number, which is the stated
        // cost of storing the order as the index.
        assertEquals(
            listOf(1.0, 2.0, 3.0),
            renumbered(listOf("c", "a", "b"), listOf("a", "b", "c")).map { it.second },
        )
    }
}
