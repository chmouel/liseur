package com.chmouel.liseur.domain

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Hashing a book file.
 *
 * The SHA-256 is checked against the plain thing it claims to be. The
 * partial MD5 is checked against a deliberately naive implementation
 * that seeks and reads, which is how KOReader does it — the point being
 * that reading the file once and picking the samples out of the stream
 * must come to exactly the same answer, because a fingerprint that only
 * agrees with itself is worth nothing.
 */
class BookFingerprintTest {

    @Test
    fun `the sha256 is the sha256 of the file`() {
        val bytes = bytes(300_000)

        val fingerprint = BookFingerprints.of(ByteArrayInputStream(bytes))

        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(bytes)), fingerprint.sha256)
        assertEquals(300_000L, fingerprint.size)
    }

    @Test
    fun `streaming the samples matches seeking to them`() {
        // Larger than the read buffer, so samples land mid-chunk and
        // across chunk boundaries rather than conveniently at the start.
        for (size in listOf(0, 100, 1024, 2048, 5000, 70_000, 200_000)) {
            val bytes = bytes(size)

            assertEquals(
                "at $size bytes",
                seeking(bytes),
                BookFingerprints.of(ByteArrayInputStream(bytes)).partialMd5,
            )
        }
    }

    @Test
    fun `the first sample is the head of the file`() {
        // KOReader's first seek is a left shift by -2, which LuaJIT
        // takes modulo 32 and overflows to zero. That accident is the
        // fingerprint every KOReader device has already stored, so it is
        // the behaviour to keep.
        assertEquals(0L, BookFingerprints.SAMPLE_OFFSETS.first())
        assertEquals(listOf(0L, 1024L, 4096L, 16_384L), BookFingerprints.SAMPLE_OFFSETS.take(4))
        assertEquals(1024L shl 20, BookFingerprints.SAMPLE_OFFSETS.last())
    }

    @Test
    fun `only the sampled parts of a large file are looked at`() {
        // A byte in a gap between samples changes the whole-file hash
        // and not the sampled one. This is what makes the partial hash
        // cheap, and also why it is never trusted on its own.
        val bytes = bytes(200_000)
        val changed = bytes.copyOf().also { it[100_000] = (it[100_000] + 1).toByte() }

        val first = BookFingerprints.of(ByteArrayInputStream(bytes))
        val second = BookFingerprints.of(ByteArrayInputStream(changed))

        assertEquals(first.partialMd5, second.partialMd5)
        assertNotEquals(first.sha256, second.sha256)
    }

    @Test
    fun `a file shorter than the samples is still hashed`() {
        // Truncated files, and the empty file, must produce an answer
        // rather than throwing: they are books as far as the library is
        // concerned right up until somebody tries to open them.
        val short = BookFingerprints.of(ByteArrayInputStream(bytes(10)))

        assertEquals(seeking(bytes(10)), short.partialMd5)
        assertEquals(10L, short.size)
    }

    /**
     * KOReader's own approach: seek to each offset, read up to 1024
     * bytes, stop at the first read that comes back with nothing.
     */
    private fun seeking(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        for (offset in BookFingerprints.SAMPLE_OFFSETS) {
            if (offset >= bytes.size) break
            val end = minOf(offset + 1024, bytes.size.toLong()).toInt()
            digest.update(bytes, offset.toInt(), end - offset.toInt())
        }
        return hex(digest.digest())
    }

    private fun bytes(size: Int) = Random(size.toLong()).nextBytes(size)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
