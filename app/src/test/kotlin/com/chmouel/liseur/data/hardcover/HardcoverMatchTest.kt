package com.chmouel.liseur.data.hardcover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When a search hit is certain enough to report reading against. */
class HardcoverMatchTest {

    private fun hit(id: Long, title: String, vararg authors: String) =
        HardcoverBook(id, title, authors.toList(), releaseYear = null)

    private fun edition(id: Long, pages: Int?) = HardcoverEdition(
        id = id, bookId = 1, title = "t", bookTitle = null, format = null, pages = pages,
        isbn13 = null, isbn10 = null, releaseYear = null, publisher = null,
    )

    @Test
    fun `the same title and author is a match`() {
        val hits = listOf(hit(9, "The Hobbit", "J.R.R. Tolkien"))
        assertEquals(9L, HardcoverMatch.confident("The Hobbit", "J.R.R. Tolkien", hits)?.id)
    }

    @Test
    fun `case, accents, punctuation and name order do not matter`() {
        val hits = listOf(hit(4, "Les Misérables", "Victor Hugo"))
        assertEquals(4L, HardcoverMatch.confident("LES MISERABLES", "Hugo, Victor", hits)?.id)
    }

    @Test
    fun `a subtitle on either side does not stop a match`() {
        val hits = listOf(hit(3, "Perfect Victims: And The Politics Of Appeal", "Mohammed El-Kurd"))
        assertEquals(3L, HardcoverMatch.confident("Perfect Victims", "Mohammed El-Kurd", hits)?.id)
        val plain = listOf(hit(3, "Perfect Victims", "Mohammed El-Kurd"))
        assertEquals(
            3L,
            HardcoverMatch.confident("Perfect Victims (Illustrated)", "Mohammed El-Kurd", plain)?.id,
        )
    }

    @Test
    fun `a subtitle after a semicolon does not stop a match`() {
        val hits = listOf(hit(11, "Frankenstein", "Mary Shelley"))
        assertEquals(
            11L,
            HardcoverMatch.confident(
                "Frankenstein; Or, The Modern Prometheus",
                "Shelley, Mary Wollstonecraft",
                hits,
            )?.id,
        )
    }

    @Test
    fun `the first agreeing hit wins over better-ranked ones that do not agree`() {
        val hits = listOf(
            hit(1, "The Hobbit Companion", "David Day"),
            hit(2, "The Hobbit", "J.R.R. Tolkien"),
        )
        assertEquals(2L, HardcoverMatch.confident("The Hobbit", "Tolkien", hits)?.id)
    }

    @Test
    fun `the right title by someone else is not a match`() {
        val hits = listOf(hit(5, "Poems", "Emily Dickinson"))
        assertNull(HardcoverMatch.confident("Poems", "Walt Whitman", hits))
    }

    @Test
    fun `the right author on another book is not a match`() {
        val hits = listOf(hit(6, "The Silmarillion", "J.R.R. Tolkien"))
        assertNull(HardcoverMatch.confident("The Hobbit", "J.R.R. Tolkien", hits))
    }

    @Test
    fun `shared initials are not a shared author`() {
        val hits = listOf(hit(7, "Selected Essays", "J. K. Smith"))
        assertNull(HardcoverMatch.confident("Selected Essays", "J. K. Brown", hits))
    }

    @Test
    fun `a book with no author or no title is left to the reader`() {
        val hits = listOf(hit(8, "Poems", "Anonymous"))
        assertNull(HardcoverMatch.confident("Poems", null, hits))
        assertNull(HardcoverMatch.confident(null, "Anonymous", hits))
        assertNull(HardcoverMatch.confident("Poems", "Anonymous", emptyList()))
    }

    @Test
    fun `an ebook edition with pages is preferred`() {
        val defaults = HardcoverDefaultEditions(
            ebook = edition(1, 300), physical = edition(2, 320), mostRead = edition(3, 310),
        )
        assertEquals(1L, HardcoverMatch.preferredEdition(defaults)?.id)
    }

    @Test
    fun `an edition with a page count beats an ebook without one`() {
        val defaults = HardcoverDefaultEditions(
            ebook = edition(1, null), physical = edition(2, 320), mostRead = null,
        )
        assertEquals(2L, HardcoverMatch.preferredEdition(defaults)?.id)
    }

    @Test
    fun `with no page counts anywhere the ebook is still taken`() {
        val defaults = HardcoverDefaultEditions(ebook = edition(1, null), physical = null, mostRead = edition(3, 0))
        assertEquals(1L, HardcoverMatch.preferredEdition(defaults)?.id)
        assertNull(HardcoverMatch.preferredEdition(HardcoverDefaultEditions(null, null, null)))
    }
}
