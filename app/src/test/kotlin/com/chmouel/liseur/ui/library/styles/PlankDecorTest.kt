package com.chmouel.liseur.ui.library.styles

import com.chmouel.liseur.data.db.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlankDecorTest {

    private val finished = (1..3).map { book("file:///sd/$it.epub") }

    @Test
    fun `with nothing finished every empty place gets a plant`() {
        val decor = plankDecor("series:dune", emptySet(), emptyList(), empty = 3)
        assertEquals(3, decor.slots.size)
        assertTrue(decor.slots.all { it is SlotDecor.Plant })
    }

    @Test
    fun `no cover is hung twice or next to its own book`() {
        repeat(40) { n ->
            val decor = plankDecor("plank:$n", setOf("file:///sd/1.epub"), finished, empty = 5)
            val hung = decor.slots.filterIsInstance<SlotDecor.Frame>().map { it.book.url }
            assertEquals(hung.distinct(), hung)
            assertTrue("file:///sd/1.epub" !in hung)
        }
    }

    @Test
    fun `a pot and a hanging plant never share an end`() {
        val decors = (0 until 60).map { plankDecor("plank:$it", emptySet(), finished, empty = 0) }
        decors.filter { it.pot != null && it.hanging != null }
            .forEach { assertNotEquals(it.pot, it.hanging) }
        assertTrue(decors.any { it.pot != null })
        assertTrue(decors.any { it.hanging != null })
        assertTrue(decors.any { it.pot == null && it.hanging == null })
    }

    private fun book(url: String) = Book(
        url = url,
        title = "A Book",
        author = null,
        coverPath = "/covers/x.jpg",
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
    )
}
