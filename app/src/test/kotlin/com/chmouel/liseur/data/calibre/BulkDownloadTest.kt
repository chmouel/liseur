package com.chmouel.liseur.data.calibre

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "download everything" will and will not reach for, and what it is
 * willing to claim about the cost.
 *
 * Both halves are pure on purpose: the selection decides what gets
 * queued against a server, and the estimate is the only thing standing
 * between a tap and a full device.
 */
class BulkDownloadTest {

    private fun book(
        url: String,
        state: DownloadState = DownloadState.REMOTE,
        archivedAt: Long? = null,
        remoteUuid: String? = "uuid-$url",
        downloadHref: String? = "/download/$url",
        sizeBytes: Long? = null,
    ) = Book(
        url = url,
        title = url,
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0L,
        lastOpenedAt = null,
        remoteUuid = remoteUuid,
        downloadHref = downloadHref,
        downloadState = state,
        archivedAt = archivedAt,
        sizeBytes = sizeBytes,
    )

    @Test
    fun `takes books that are only on the server`() {
        val picked = booksToDownload(listOf(book("a"), book("b")))
        assertEquals(listOf("a", "b"), picked.map { it.url })
    }

    @Test
    fun `retries a book whose download failed`() {
        val picked = booksToDownload(listOf(book("a", state = DownloadState.FAILED)))
        assertEquals(listOf("a"), picked.map { it.url })
    }

    @Test
    fun `skips a book already on the device`() {
        assertTrue(booksToDownload(listOf(book("a", state = DownloadState.DOWNLOADED))).isEmpty())
    }

    @Test
    fun `skips a download already in flight`() {
        // Both would collide with the unique work name that is already
        // taken, and the collision is silent under KEEP.
        val inFlight = listOf(
            book("a", state = DownloadState.QUEUED),
            book("b", state = DownloadState.DOWNLOADING),
        )
        assertTrue(booksToDownload(inFlight).isEmpty())
    }

    @Test
    fun `skips an archived book`() {
        // Taking it off the shelf is a statement about wanting it out of
        // the way; fetching it back is the opposite of honouring that.
        assertTrue(booksToDownload(listOf(book("a", archivedAt = 1L))).isEmpty())
    }

    @Test
    fun `skips a book the server cannot serve`() {
        // liseur-sync nulls the href for books its watched folders no
        // longer hold. Queuing one schedules work that cannot succeed.
        assertTrue(booksToDownload(listOf(book("a", downloadHref = null))).isEmpty())
        assertTrue(booksToDownload(listOf(book("a", downloadHref = "  "))).isEmpty())
    }

    @Test
    fun `skips a book with nowhere to land`() {
        assertTrue(booksToDownload(listOf(book("a", remoteUuid = null))).isEmpty())
    }

    @Test
    fun `adds up the sizes it was given`() {
        val estimate = estimateBulkDownload(listOf(1_000L, 2_000L, 3_000L), freeBytes = GIB * 10)
        assertEquals(6_000L, estimate.bytes)
        assertEquals(SpaceVerdict.FITS, estimate.verdict)
        assertEquals(3, estimate.count)
    }

    @Test
    fun `charges the median for a book the server did not measure`() {
        // Median rather than mean: one outsized volume in a shelf of
        // novels should not decide what the unmeasured ones cost.
        val estimate = estimateBulkDownload(
            listOf(1_000L, 2_000L, 100_000_000L, null),
            freeBytes = GIB * 10,
        )
        assertEquals(1_000L + 2_000L + 100_000_000L + 2_000L, estimate.bytes)
    }

    @Test
    fun `says nothing rather than guessing when no book has a size`() {
        val estimate = estimateBulkDownload(listOf(null, null), freeBytes = GIB)
        assertNull(estimate.bytes)
        assertEquals(SpaceVerdict.UNKNOWN, estimate.verdict)
        assertEquals(2, estimate.count)
    }

    @Test
    fun `discards a size the server could not have meant`() {
        // Zero, negative and absurd all come off the wire. Believing any
        // of them either scares the reader off a download that fits or
        // poisons the median for every book that reported nothing.
        val estimate = estimateBulkDownload(
            listOf(4_000L, 0L, -1L, Long.MAX_VALUE),
            freeBytes = GIB * 10,
        )
        assertEquals(4_000L * 4, estimate.bytes)
    }

    @Test
    fun `does not overflow into looking like it fits`() {
        val huge = List(64) { 4L * GIB }
        val estimate = estimateBulkDownload(huge, freeBytes = GIB)
        assertTrue((estimate.bytes ?: 0L) > 0L)
        assertEquals(SpaceVerdict.WILL_NOT_FIT, estimate.verdict)
    }

    @Test
    fun `refuses to promise a fit that would eat the reserve`() {
        val estimate = estimateBulkDownload(
            listOf(GIB),
            freeBytes = GIB + BULK_DOWNLOAD_RESERVE_BYTES - 1,
        )
        assertEquals(SpaceVerdict.WILL_NOT_FIT, estimate.verdict)
    }

    @Test
    fun `warns when it fits with little to spare`() {
        val estimate = estimateBulkDownload(
            listOf(GIB),
            freeBytes = GIB + BULK_DOWNLOAD_RESERVE_BYTES + 1,
        )
        assertEquals(SpaceVerdict.TIGHT, estimate.verdict)
    }

    @Test
    fun `is content when there is room to spare`() {
        val estimate = estimateBulkDownload(listOf(GIB), freeBytes = GIB * 10)
        assertEquals(SpaceVerdict.FITS, estimate.verdict)
    }

    @Test
    fun `reads a full disk off the exception the filesystem threw`() {
        // There is no typed exception for ENOSPC, and telling it from a
        // dropped connection is what decides whether the batch retries
        // or stops.
        assertTrue(isOutOfSpace(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(isOutOfSpace(IOException("Not enough space")))
        assertTrue(isOutOfSpace(IOException(null, IOException("ENOSPC"))))
        assertFalse(isOutOfSpace(IOException("Connection reset by peer")))
        assertFalse(isOutOfSpace(IOException(null as String?)))
    }

    @Test
    fun `round-trips a stop reason through its stored id`() {
        BulkStopReason.entries.forEach { reason ->
            assertEquals(reason, BulkStopReason.fromId(reason.id))
        }
        assertNull(BulkStopReason.fromId(null))
        assertNull(BulkStopReason.fromId("something else"))
    }

    private companion object {
        const val GIB = 1024L * 1024 * 1024
    }
}
