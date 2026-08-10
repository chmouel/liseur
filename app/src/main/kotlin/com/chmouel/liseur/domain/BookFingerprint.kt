package com.chmouel.liseur.domain

import java.io.InputStream
import java.security.MessageDigest

/**
 * What a book's file is, said in the two ways a sync server understands.
 *
 * [sha256] names these exact bytes and nothing else. [partialMd5] is
 * KOReader's fingerprint, which samples the file rather than reading all
 * of it; it is here only so that a book already read on a KOReader
 * device is recognised as the same book rather than starting over.
 *
 * [size] is kept because it costs nothing and makes a mismatch
 * intelligible: two files that disagree on length were never going to be
 * the same bytes.
 */
data class BookFingerprint(
    val sha256: String,
    val partialMd5: String,
    val size: Long,
)

/**
 * Fingerprinting a book file.
 *
 * Both hashes are taken in one pass. KOReader's samples sit at
 * increasing offsets, so nothing has to seek backwards, and a book on a
 * memory card or behind a document provider is expensive enough to read
 * once without reading it twice.
 */
object BookFingerprints {

    /**
     * Where KOReader takes its 1024-byte samples.
     *
     * `util.partialMD5` seeks to `lshift(1024, 2 * i)` for `i` from -1
     * to 10. The first of those is a left shift by -2, which LuaJIT
     * takes modulo 32 and turns into a shift by 30 — the result
     * overflows to zero, so the first sample is the head of the file.
     * That is an accident of the original implementation, but it is the
     * fingerprint every KOReader device has already computed, so it is
     * the one to reproduce.
     */
    val SAMPLE_OFFSETS: List<Long> = buildList {
        add(0L)
        for (i in 0..10) add(SAMPLE_SIZE shl (2 * i))
    }

    /**
     * Reads [stream] once and hashes it.
     *
     * The stream is not closed here: whoever opened it knows what it
     * came from and what closing it means.
     */
    fun of(stream: InputStream): BookFingerprint {
        val whole = MessageDigest.getInstance("SHA-256")
        val sampled = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER)

        var position = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            whole.update(buffer, 0, read)
            sample(sampled, buffer, position, read)
            position += read
        }

        return BookFingerprint(
            sha256 = hex(whole.digest()),
            partialMd5 = hex(sampled.digest()),
            size = position,
        )
    }

    /**
     * Feeds whatever part of this chunk falls inside a sample window.
     *
     * A window that starts past the end of the file contributes nothing,
     * which is what KOReader does when its seek lands beyond the end and
     * the read comes back empty. A window the file stops in the middle
     * of contributes what there is, for the same reason.
     */
    private fun sample(digest: MessageDigest, chunk: ByteArray, at: Long, length: Int) {
        val end = at + length
        for (offset in SAMPLE_OFFSETS) {
            if (offset >= end) break
            val until = offset + SAMPLE_SIZE
            if (until <= at) continue
            val from = maxOf(offset, at)
            val to = minOf(until, end)
            digest.update(chunk, (from - at).toInt(), (to - from).toInt())
        }
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    private const val SAMPLE_SIZE = 1024L
    private const val BUFFER = 64 * 1024
    private const val HEX = "0123456789abcdef"
}
