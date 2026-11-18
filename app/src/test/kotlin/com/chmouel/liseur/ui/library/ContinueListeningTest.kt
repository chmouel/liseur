package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.readaloud.PausedReadAloud
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the shelf offers to carry on listening with.
 *
 * The row is the only thing on the shelf that says a listening exists,
 * so it has to be both offered where it can be taken up and absent
 * wherever it would lie: about a book no longer on the shelf, over a
 * search, or about a book already being read aloud.
 */
class ContinueListeningTest {

    private fun book(
        url: String = "book",
        title: String = "A book",
        archived: Boolean = false,
        hidden: Boolean = false,
    ) = Book(
        url = url,
        title = title,
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
        archivedAt = if (archived) 1L else null,
        hiddenAt = if (hidden) 1L else null,
    )

    private fun record(bookId: String = "book") = PausedReadAloud(
        bookId = bookId,
        heardJson = "{}",
        voice = "voice",
        language = "en",
        revision = 1,
        positionJson = "{}",
    )

    private fun row(
        books: List<Book> = listOf(book()),
        record: PausedReadAloud? = record(),
        available: Boolean = true,
        configured: Boolean = true,
        playingBookId: String? = null,
        onShelf: Boolean = true,
    ) = continueListeningFor(
        books = books,
        record = record,
        available = available,
        configured = configured,
        playingBookId = playingBookId,
        onShelf = onShelf,
    )

    @Test
    fun `the paused book is offered by name`() {
        val offered = row(books = listOf(book(title = "Middlemarch")))
        assertNotNull(offered)
        assertEquals("Middlemarch", offered?.title)
        assertEquals("book", offered?.bookId)
    }

    @Test
    fun `nothing is kept, nothing is offered`() {
        assertNull(row(record = null))
    }

    /** A build or a device without read aloud has nothing to carry on with. */
    @Test
    fun `nothing is offered where read aloud is unavailable`() {
        assertNull(row(available = false))
    }

    /** Without a chosen voice the book would open and say nothing. */
    @Test
    fun `nothing is offered before a voice is chosen`() {
        assertNull(row(configured = false))
    }

    @Test
    fun `the same book's listening is already playing`() {
        assertNull(row(playingBookId = "book"))
    }

    @Test
    fun `another book's listening leaves this one offered`() {
        assertNotNull(row(playingBookId = "other"))
    }

    @Test
    fun `a book gone from the shelf is not offered`() {
        assertNull(row(books = listOf(book(url = "elsewhere"))))
    }

    @Test
    fun `an archived book is not offered`() {
        assertNull(row(books = listOf(book(archived = true))))
    }

    @Test
    fun `a hidden book is not offered`() {
        assertNull(row(books = listOf(book(hidden = true))))
    }

    /** A search and the archive show something else; the row is about the shelf. */
    @Test
    fun `nothing is offered off the shelf`() {
        assertNull(row(onShelf = false))
    }
}
