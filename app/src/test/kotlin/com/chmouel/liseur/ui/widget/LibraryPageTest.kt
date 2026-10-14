package com.chmouel.liseur.ui.widget

import com.chmouel.liseur.data.db.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPageTest {
    @Test
    fun `pages cover the library once including a partial final page`() {
        val books = (0 until 19).toList()
        val seen = (0..2).flatMap { page -> books.drop(libraryPageStart(page * 7, books.size, 7)).take(7) }
        assertEquals(books, seen)
        assertEquals(14, libraryPageStart(21, books.size, 7))
        assertEquals(0, libraryPageStart(-7, books.size, 7))
    }

    @Test
    fun `resizing retains the anchor and removals clamp to the last available page`() {
        assertEquals(5, libraryPageStart(7, 19, 5))
        assertEquals(4, libraryPageStart(16, 5, 2))
        assertEquals(0, libraryPageStart(16, 0, 7))
        val tall = libraryGrid(320f, 300f)
        assertEquals(LibraryGrid(4, 2), tall)
        assertTrue(tall.featuredLeft)
        assertEquals(2, tall.featuredSpan)
        assertEquals(5, tall.capacity)
        assertEquals(3, libraryGrid(250f, 300f).capacity)
        assertEquals(3, libraryGrid(180f, 300f).capacity)
        val singleRow = libraryGrid(180f, 180f)
        assertEquals(LibraryGrid(2, 1), singleRow)
        assertEquals(2, singleRow.capacity)
    }

    @Test
    fun `library hides archived and hidden books and sorts recent books before unread ones`() {
        val books = listOf(
            book("z", "Z"), book("b", "B", 200), book("a", "A", 100), book("c", "C"),
            book("hidden", "Hidden", 300).copy(hiddenAt = 1),
            book("archive", "Archived", 400).copy(archivedAt = 1),
        )
        assertEquals(listOf("b", "a", "c", "z"), libraryBooks(books, current = null).map { it.url })
    }

    @Test
    fun `the current book leads the shelf once`() {
        val books = listOf(book("z", "Z"), book("b", "B", 200), book("a", "A", 100))
        assertEquals(listOf("z", "b", "a"), libraryBooks(books, current = book("z", "Z")).map { it.url })
        assertEquals(listOf("b", "a", "z"), libraryBooks(books, current = book("gone", "Gone")).map { it.url })
        val hidden = book("h", "H", 300).copy(hiddenAt = 1)
        assertEquals(listOf("b", "a", "z"), libraryBooks(books + hidden, current = hidden).map { it.url })
    }

    @Test
    fun `the window holds every page any widget size can show`() {
        for (count in listOf(0, 1, 5, 19, 40)) {
            for (anchor in listOf(0, 3, 7, 14, 21, 100)) {
                val window = libraryWindow(anchor, count)
                for (capacity in listOf(2, 3, 4, 5)) {
                    val start = libraryPageStart(anchor, count, capacity)
                    assertTrue(window.containsAll((start until minOf(start + capacity, count)).toList()))
                }
                assertTrue(window.all { it in 0 until count })
            }
        }
        assertTrue(libraryWindow(7, 19).size <= 16)
    }

    private fun book(url: String, title: String, opened: Long? = null) = Book(
        url = url, title = title, author = null, coverPath = null, source = null,
        addedAt = 1L, lastOpenedAt = opened,
    )
}
