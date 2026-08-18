package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.BookUploadWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which books the library offers to send up, and which it leaves alone. */
class AwaitingUploadTest {

    @Test
    fun `a book added on this device is offered`() {
        val books = listOf(local("file:///sd/a.epub"))
        assertEquals(listOf("file:///sd/a.epub"), books.awaitingUpload(true).map { it.url })
    }

    @Test
    fun `a book that came from a server is not sent back to it`() {
        val books = listOf(local("liseur-sync:42"), local("calibre:7"), local("komga:9"))
        assertTrue(books.awaitingUpload(true).isEmpty())
    }

    @Test
    fun `a book already linked to the server is left alone`() {
        val books = listOf(local("file:///sd/a.epub").copy(remoteUuid = "42"))
        assertTrue(books.awaitingUpload(true).isEmpty())
    }

    @Test
    fun `an archived book is not offered`() {
        val books = listOf(local("file:///sd/a.epub").copy(archivedAt = 1))
        assertTrue(books.awaitingUpload(true).isEmpty())
    }

    @Test
    fun `nothing is offered where uploading is not on the table`() {
        assertTrue(listOf(local("file:///sd/a.epub")).awaitingUpload(false).isEmpty())
    }

    /**
     * Switching accounts clears `remote_uuid` from downloaded books but
     * leaves them their old server's URL. Asking only whether the book is
     * linked would offer to upload another server's copy, so the per-book
     * action asks the same question the offer does.
     */
    @Test
    fun `a downloaded book that lost its link is still not ours to send`() {
        val orphan = local("komga:9").copy(remoteUuid = null, localUri = "file:///data/9.epub")
        assertFalse(orphan.livesOnlyOnThisDevice())
    }

    @Test
    fun `an archived book can still be sent when it is asked for by hand`() {
        assertTrue(local("file:///sd/a.epub").copy(archivedAt = 1).livesOnlyOnThisDevice())
    }

    @Test
    fun `a filename never carries a path separator into the request`() {
        assertEquals(
            "A_B - C_D.epub",
            BookUploadWorker.filenameFor("A/B", "C\\D"),
        )
    }

    @Test
    fun `a book with no title still gets a name`() {
        assertEquals("book.epub", BookUploadWorker.filenameFor("", null))
    }

    private fun local(url: String) = Book(
        url = url,
        title = "A book",
        author = "An author",
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
    )
}
