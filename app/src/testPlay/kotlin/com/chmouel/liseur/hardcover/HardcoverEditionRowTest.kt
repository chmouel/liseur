package com.chmouel.liseur.hardcover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardcoverEditionRowTest {

    private fun edition(
        id: Long = 5,
        title: String = "The Hobbit",
        bookTitle: String? = "Book Title",
        format: String? = "ebook",
        pages: Int? = 300,
        isbn13: String? = "9780547928227",
        releaseYear: Int? = 2012,
        publisher: String? = "Mariner",
        usersCount: Int = 48,
        coverUrl: String? = "https://assets.hardcover.app/c.jpg",
        authors: List<String> = listOf("J.R.R. Tolkien"),
    ) = HardcoverEdition(
        id = id,
        bookId = 9,
        title = title,
        bookTitle = bookTitle,
        format = format,
        pages = pages,
        isbn13 = isbn13,
        isbn10 = null,
        releaseYear = releaseYear,
        publisher = publisher,
        usersCount = usersCount,
        coverUrl = coverUrl,
        authors = authors,
    )

    @Test
    fun `an ebook edition shows format, year, pages and readers, the author, the cover and a check when matched`() {
        val row = editionRow(edition(), currentEditionId = 5)

        assertEquals(EditionIcon.BOOK, row.icon)
        assertEquals("The Hobbit", row.headline)
        assertEquals("J.R.R. Tolkien", row.authors)
        assertEquals("https://assets.hardcover.app/c.jpg", row.coverUrl)
        assertEquals(
            listOf(
                EditionChip.Label("ebook"),
                EditionChip.Label("2012"),
                EditionChip.Pages(300),
                EditionChip.Readers(48),
            ),
            row.chips,
        )
        assertTrue(row.current)
        assertFalse(row.selected)
    }

    @Test
    fun `current and selected are independent`() {
        val row = editionRow(edition(), currentEditionId = 99, selectedEditionId = 5)

        assertFalse(row.current)
        assertTrue(row.selected)
        assertEquals(5L, row.editionId)
    }

    @Test
    fun `an audiobook edition uses the headphones icon and keeps the format label`() {
        val row = editionRow(edition(format = "Audiobook"), currentEditionId = 99)

        assertEquals(EditionIcon.AUDIOBOOK, row.icon)
        assertEquals("Audiobook", row.formatLabel)
    }

    @Test
    fun `paperback, hardcover and unknown formats use the book icon`() {
        assertEquals(EditionIcon.BOOK, editionRow(edition(format = "paperback"), null).icon)
        assertEquals(EditionIcon.BOOK, editionRow(edition(format = "hardcover"), null).icon)
        val unknown = editionRow(edition(format = null), null)
        assertEquals(EditionIcon.BOOK, unknown.icon)
        assertNull(unknown.formatLabel)
    }

    @Test
    fun `missing fields and zero counts are omitted, and a blank title falls back to the book title`() {
        val row = editionRow(
            edition(
                title = "  ",
                bookTitle = "The Hobbit",
                format = null,
                pages = null,
                releaseYear = null,
                usersCount = 0,
                coverUrl = null,
                authors = emptyList(),
            ),
            currentEditionId = null,
        )

        assertEquals("The Hobbit", row.headline)
        assertNull(row.authors)
        assertNull(row.coverUrl)
        assertEquals(emptyList<EditionChip>(), row.chips)
        assertFalse(row.current)
    }

    @Test
    fun `more than two authors are cut to two and a count`() {
        assertEquals("A, B +2", editionRow(edition(authors = listOf("A", "B", "C", "D")), null).authors)
        assertEquals("A, B", editionRow(edition(authors = listOf("A", " ", "B")), null).authors)
    }

    @Test
    fun `a cover off the image host is not passed on`() {
        assertNull(editionRow(edition(coverUrl = "https://evil.example/c.jpg"), null).coverUrl)
    }
}
