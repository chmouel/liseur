package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.settings.UploadPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which books go up without being asked about.
 *
 * The library watches the shelf for these and the reader applies the
 * same rule to a book just handed over by another app, so the two must
 * never disagree — which is why they read one function.
 */
class BooksToSendUpTest {

    @Test
    fun `under always a book of ours is sent`() {
        assertEquals(
            listOf("file:///sd/a.epub"),
            booksToSendUp(listOf(local("file:///sd/a.epub")), UploadPolicy.ALWAYS, true)
                .map { it.url },
        )
    }

    @Test
    fun `under ask nothing goes up on its own`() {
        assertTrue(
            booksToSendUp(listOf(local("file:///sd/a.epub")), UploadPolicy.ASK, true).isEmpty(),
        )
    }

    @Test
    fun `under never nothing goes up`() {
        assertTrue(
            booksToSendUp(listOf(local("file:///sd/a.epub")), UploadPolicy.NEVER, true).isEmpty(),
        )
    }

    @Test
    fun `always cannot send where uploading is not possible`() {
        assertTrue(
            booksToSendUp(listOf(local("file:///sd/a.epub")), UploadPolicy.ALWAYS, false).isEmpty(),
        )
    }

    /**
     * A book imported from another app is only ever a book of ours, so
     * the shelf's existing rules still decide: one that came from a
     * server, or is already linked to it, is not sent back.
     */
    @Test
    fun `always still respects where a book came from`() {
        val books = listOf(
            local("liseur-sync:42"),
            local("file:///sd/b.epub").copy(remoteUuid = "7"),
        )
        assertTrue(booksToSendUp(books, UploadPolicy.ALWAYS, true).isEmpty())
    }

    private fun local(url: String) = Book(
        url = url,
        title = "A Book",
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
    )
}
