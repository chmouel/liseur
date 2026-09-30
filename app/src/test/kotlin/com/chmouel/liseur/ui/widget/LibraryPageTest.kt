package com.chmouel.liseur.ui.widget

import com.chmouel.liseur.data.db.Book
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPageTest {
    @Test
    fun `pages cover the library once including a partial final page`() {
        val books = (0 until 19).toList()
        val seen = (0..2).flatMap { page -> books.drop(libraryPageStart(page * 8, books.size, 8)).take(8) }
        assertEquals(books, seen)
        assertEquals(16, libraryPageStart(24, books.size, 8))
        assertEquals(0, libraryPageStart(-8, books.size, 8))
    }

    @Test
    fun `resizing retains the anchor and removals clamp to the last available page`() {
        assertEquals(6, libraryPageStart(8, 19, 6))
        assertEquals(4, libraryPageStart(16, 5, 2))
        assertEquals(0, libraryPageStart(16, 0, 8))
        assertEquals(LibraryGrid(4, 2), libraryGrid(320f, 300f))
        assertEquals(LibraryGrid(2, 1), libraryGrid(180f, 180f))
    }

    @Test
    fun `library hides archived and hidden books and sorts recent books before unread ones`() {
        fun book(url: String, title: String, opened: Long? = null) = Book(
            url = url, title = title, author = null, coverPath = null, source = null,
            addedAt = 1L, lastOpenedAt = opened,
        )
        val books = listOf(
            book("z", "Z"), book("b", "B", 200), book("a", "A", 100), book("c", "C"),
            book("hidden", "Hidden", 300).copy(hiddenAt = 1),
            book("archive", "Archived", 400).copy(archivedAt = 1),
        )
        assertEquals(listOf("b", "a", "c", "z"), libraryBooks(books).map { it.url })
    }
}
