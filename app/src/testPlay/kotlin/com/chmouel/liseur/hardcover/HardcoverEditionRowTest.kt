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
    )

    @Test
    fun `an ebook edition shows format, year, publisher, isbn, pages and readers, and a check when matched`() {
        val row = editionRow(edition(), currentEditionId = 5)

        assertEquals(EditionIcon.BOOK, row.icon)
        assertEquals("The Hobbit", row.headline)
        assertEquals("ebook · 2012 · Mariner · 9780547928227", row.supporting)
        assertEquals(300, row.pages)
        assertEquals(48, row.readers)
        assertTrue(row.current)
    }

    @Test
    fun `an audiobook edition uses the headphones icon and keeps the format label`() {
        val row = editionRow(edition(format = "Audiobook"), currentEditionId = 99)

        assertEquals(EditionIcon.AUDIOBOOK, row.icon)
        assertEquals("Audiobook", row.formatLabel)
        assertFalse(row.current)
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
                isbn13 = null,
                releaseYear = null,
                publisher = null,
                usersCount = 0,
            ),
            currentEditionId = null,
        )

        assertEquals("The Hobbit", row.headline)
        assertEquals("", row.supporting)
        assertNull(row.pages)
        assertNull(row.readers)
        assertFalse(row.current)
    }
}
