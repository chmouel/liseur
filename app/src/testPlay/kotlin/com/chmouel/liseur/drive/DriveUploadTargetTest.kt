package com.chmouel.liseur.drive

import com.chmouel.liseur.data.db.BookFingerprintRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the main-side upload offer learns from the `drive_upload` queue.
 *
 * Rows are keyed by edition (sha256) but the offer works in book URLs, so the
 * mapping has to agree with the picker's own matching: sha first, book URL
 * second, and a cancelled row treated as absent.
 */
class DriveUploadTargetTest {

    @Test
    fun `a row is matched by sha`() {
        val target = target(
            uploads = listOf(upload(sha = "aa", bookUrl = "file:///sd/other.epub", state = DriveUpload.STATE_PENDING)),
            fingerprints = listOf(fingerprint("file:///sd/a.epub", "AA")),
        )
        assertEquals(setOf("file:///sd/a.epub"), target.queued)
        assertTrue(target.held.isEmpty())
    }

    @Test
    fun `a row falls back to the book URL when the sha differs`() {
        val target = target(
            uploads = listOf(upload(sha = "aa", bookUrl = "file:///sd/a.epub", state = DriveUpload.STATE_PENDING)),
            fingerprints = listOf(fingerprint("file:///sd/a.epub", "bb")),
        )
        assertEquals(setOf("file:///sd/a.epub"), target.queued)
    }

    @Test
    fun `every queued state is queued`() {
        val states = listOf(
            DriveUpload.STATE_PENDING,
            DriveUpload.STATE_UPLOADING,
            DriveUpload.STATE_REQUEUE,
        )
        for (state in states) {
            val target = target(
                uploads = listOf(upload(sha = "aa", bookUrl = "file:///sd/a.epub", state = state)),
                fingerprints = listOf(fingerprint("file:///sd/a.epub", "aa")),
            )
            assertEquals(state, setOf("file:///sd/a.epub"), target.queued)
            assertTrue(state, target.held.isEmpty())
        }
    }

    /**
     * A row found by book URL alone speaks only for the bytes it was written for:
     * a file replaced at the same location was never deleted in Drive.
     */
    @Test
    fun `a suppressed row for another edition withholds nothing`() {
        val target = target(
            uploads = listOf(upload(sha = "aa", bookUrl = "file:///sd/a.epub", state = DriveUpload.STATE_SUPPRESSED)),
            fingerprints = listOf(fingerprint("file:///sd/a.epub", "bb")),
        )
        assertTrue(target.withheld.isEmpty())
        assertTrue(target.queued.isEmpty())
        assertTrue(target.held.isEmpty())
    }

    /** A failure speaks only for the bytes that failed, not a replacement at the same location. */
    @Test
    fun `a failed row for another edition withholds nothing`() {
        val target = target(
            uploads = listOf(upload(sha = "aa", bookUrl = "file:///sd/a.epub", state = DriveUpload.STATE_FAILED)),
            fingerprints = listOf(fingerprint("file:///sd/a.epub", "bb")),
        )
        assertTrue(target.withheld.isEmpty())
        assertTrue(target.queued.isEmpty())
        assertTrue(target.held.isEmpty())
    }

    /** A failed row is not uploading, so the shelf may offer a fresh attempt by hand. */
    @Test
    fun `a failed row is withheld rather than queued`() {
        val target = target(
            uploads = listOf(upload(sha = "aa", bookUrl = "file:///sd/a.epub", state = DriveUpload.STATE_FAILED)),
            fingerprints = listOf(fingerprint("file:///sd/a.epub", "aa")),
        )
        assertEquals(setOf("file:///sd/a.epub"), target.withheld)
        assertTrue(target.queued.isEmpty())
        assertTrue(target.held.isEmpty())
    }

    /** An edition deleted in Drive stays deleted until the reader asks for it again. */
    @Test
    fun `a suppressed row is withheld`() {
        val target = target(
            uploads = listOf(upload(sha = "aa", bookUrl = "file:///sd/a.epub", state = DriveUpload.STATE_SUPPRESSED)),
            fingerprints = listOf(fingerprint("file:///sd/a.epub", "aa")),
        )
        assertEquals(setOf("file:///sd/a.epub"), target.withheld)
        assertTrue(target.queued.isEmpty())
        assertTrue(target.held.isEmpty())
    }

    /** Uploading old bytes does not make the file the book now holds live in Drive. */
    @Test
    fun `an uploaded row for another edition holds nothing`() {
        val target = target(
            uploads = listOf(upload(sha = "aa", bookUrl = "file:///sd/a.epub", state = DriveUpload.STATE_UPLOADED)),
            fingerprints = listOf(fingerprint("file:///sd/a.epub", "bb")),
        )
        assertTrue(target.held.isEmpty())
        assertTrue(target.queued.isEmpty())
        assertTrue(target.withheld.isEmpty())
    }

    @Test
    fun `an uploaded edition is held rather than queued`() {
        val target = target(
            uploads = listOf(upload(sha = "aa", bookUrl = "file:///sd/a.epub", state = DriveUpload.STATE_UPLOADED)),
            fingerprints = listOf(fingerprint("file:///sd/a.epub", "aa")),
        )
        assertEquals(setOf("file:///sd/a.epub"), target.held)
        assertTrue(target.queued.isEmpty())
    }

    /** A deselected edition can be offered again by hand, so it is no row. */
    @Test
    fun `a cancelled row is absent`() {
        val target = target(
            uploads = listOf(upload(sha = "aa", bookUrl = "file:///sd/a.epub", state = DriveUpload.STATE_CANCELLED)),
            fingerprints = listOf(fingerprint("file:///sd/a.epub", "aa")),
        )
        assertTrue(target.queued.isEmpty())
        assertTrue(target.held.isEmpty())
    }

    @Test
    fun `a file already in Drive is held`() {
        val target = target(
            remoteEpubShas = listOf("AA"),
            fingerprints = listOf(fingerprint("file:///sd/a.epub", "aa")),
        )
        assertEquals(setOf("file:///sd/a.epub"), target.held)
    }

    @Test
    fun `no fingerprint means nothing is reported`() {
        val target = target(
            uploads = listOf(upload(sha = "aa", bookUrl = "file:///sd/a.epub", state = DriveUpload.STATE_PENDING)),
            fingerprints = emptyList(),
        )
        assertTrue(target.queued.isEmpty())
        assertTrue(target.held.isEmpty())
    }

    private fun target(
        uploads: List<DriveUpload> = emptyList(),
        remoteEpubShas: List<String> = emptyList(),
        fingerprints: List<BookFingerprintRow> = emptyList(),
        selectionSettled: Boolean = true,
    ) = driveUploadTarget(
        permissionId = "perm-a",
        offerSince = 5,
        uploads = uploads,
        remoteEpubShas = remoteEpubShas,
        fingerprints = fingerprints,
        selectionSettled = selectionSettled,
    )

    private fun upload(sha: String, bookUrl: String, state: String) = DriveUpload(
        permissionId = "perm-a",
        sha256 = sha,
        bookUrl = bookUrl,
        sizeBytes = 10,
        state = state,
        selectedAt = 1,
        updatedAt = 1,
    )

    private fun fingerprint(bookUrl: String, sha: String) = BookFingerprintRow(
        bookUrl = bookUrl,
        sha256 = sha,
        partialMd5 = "md5",
        fileSize = 10,
        fileModifiedAt = null,
        computedAt = 1,
    )
}
