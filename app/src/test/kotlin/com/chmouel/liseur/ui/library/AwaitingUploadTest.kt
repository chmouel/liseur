package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.RefusedBytes
import com.chmouel.liseur.data.db.UploadRefusal
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
     * The bug this whole thing exists for: a book the server had already
     * refused was offered again on every single launch, because nothing
     * was written down when the upload failed for good.
     */
    @Test
    fun `a book this server has already refused is not offered again`() {
        val books = listOf(local("file:///sd/a.epub"), local("file:///sd/b.epub"))
        assertEquals(
            listOf("file:///sd/b.epub"),
            books.awaitingUpload(true, refused = setOf("file:///sd/a.epub")).map { it.url },
        )
    }

    /** Asking by hand is a different question, and always reaches the book. */
    @Test
    fun `a refused book can still be sent when it is asked for by hand`() {
        assertTrue(local("file:///sd/a.epub").livesOnlyOnThisDevice())
    }

    /**
     * A refusal is one server's opinion about a set of bytes, so it
     * applies only while the file still hashes to what was refused. The
     * comparison is the invalidation: nothing has to remember to clear a
     * row when a reader replaces a copy.
     */
    @Test
    fun `a refusal stops applying when the bytes change`() {
        val refused = refusal(refusedSha256 = "aa", currentSha256 = "aa")
        assertTrue(refused.stillApplies)
        assertFalse(refused.copy(currentSha256 = "bb").stillApplies)
    }

    /**
     * Re-offering a book is a cheap mistake; hiding one the reader could
     * have sent is not. So an unknown digest on either side is not a
     * match, and a refusal with no bytes behind it — a file this device
     * could not read — suppresses nothing.
     */
    @Test
    fun `a refusal with nothing to compare suppresses nothing`() {
        assertFalse(refusal(refusedSha256 = null, currentSha256 = "aa").stillApplies)
        assertFalse(refusal(refusedSha256 = "aa", currentSha256 = null).stillApplies)
        assertFalse(refusal(refusedSha256 = null, currentSha256 = null).stillApplies)
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

    /**
     * Suppressing the offer and explaining it are different questions.
     * A file this device could not read never got as far as bytes, so it
     * silences nothing — but it is the only account the reader will ever
     * get of a *Send* that appeared to do nothing, so the sheet still
     * says it. A refusal whose bytes have moved on is the other way
     * round: spent, and no longer true.
     */
    @Test
    fun `an unreadable file explains itself without hiding the book`() {
        val unreadable = refusal(refusedSha256 = null, currentSha256 = "aa")
            .copy(kind = UploadRefusal.FILE_UNREADABLE)
        assertFalse(unreadable.stillApplies)
        assertTrue(unreadable.worthSaying)

        assertFalse(refusal(refusedSha256 = "aa", currentSha256 = "bb").worthSaying)
        assertTrue(refusal(refusedSha256 = "aa", currentSha256 = "aa").worthSaying)
    }

    private fun refusal(refusedSha256: String?, currentSha256: String?) = RefusedBytes(
        bookUrl = "file:///sd/a.epub",
        kind = UploadRefusal.SERVER_REFUSED,
        reason = "not an epub",
        refusedSha256 = refusedSha256,
        currentSha256 = currentSha256,
    )

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
