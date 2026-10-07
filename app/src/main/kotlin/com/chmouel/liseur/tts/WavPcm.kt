package com.chmouel.liseur.tts

import android.media.AudioFormat

/**
 * A speech server's WAV reply as [SpeechAudio] wants it. Servers answer
 * at their own rate (DeepInfra's Audio8 at 44.1 kHz), so the header is
 * read and the samples converted, rather than trusting a raw PCM reply
 * to be 24 kHz. Only 16-bit integer PCM is taken; anything else, or a
 * header that does not add up, is an [SpeechError.InvalidResponse].
 */
internal object WavPcm {
    /**
     * Whether [bytes] start like a WAV file, so they must be read as one:
     * a cut or broken header is an error, never played as samples.
     */
    fun isWav(bytes: ByteArray): Boolean = bytes.size >= 4 && ascii(bytes, 0) == "RIFF"

    /** Throws [SpeechError.InvalidResponse]. */
    fun toSpeechPcm(bytes: ByteArray): ByteArray {
        if (bytes.size < 12 || ascii(bytes, 8) != "WAVE") throw SpeechError.InvalidResponse("not a wav")
        var format: Format? = null
        var at = 12L
        while (at + 8 <= bytes.size) {
            val id = ascii(bytes, at.toInt())
            val size = u32(bytes, at.toInt() + 4)
            val body = at + 8
            if (id == "data") {
                val fmt = format ?: throw SpeechError.InvalidResponse("wav data before format")
                // A streamed reply cannot know its length: its size is 0, all ones, or a guess past the end.
                val available = bytes.size - body
                val length = if (size == 0L || size > available) available else size
                return convert(fmt, bytes.copyOfRange(body.toInt(), (body + length).toInt()))
            }
            if (body + size > bytes.size) throw SpeechError.InvalidResponse("truncated wav")
            if (id == "fmt ") format = format(bytes, body.toInt(), size)
            at = body + size + (size and 1)
        }
        throw SpeechError.InvalidResponse("wav without data")
    }

    private class Format(val channels: Int, val rate: Int)

    private fun format(bytes: ByteArray, at: Int, size: Long): Format {
        if (size < 16) throw SpeechError.InvalidResponse("short wav format")
        val tag = u16(bytes, at)
        val channels = u16(bytes, at + 2)
        val rate = u32(bytes, at + 4)
        val blockAlign = u16(bytes, at + 12)
        val bits = u16(bytes, at + 14)
        val pcm = when (tag) {
            PCM -> true
            EXTENSIBLE -> size >= 40 && u16(bytes, at + 16) >= 22 && u16(bytes, at + 18) == 16 &&
                (0 until 16).all { bytes[at + 24 + it] == PCM_SUBFORMAT[it] }
            else -> false
        }
        if (!pcm || bits != 16) throw SpeechError.InvalidResponse("unsupported wav format")
        if (channels !in 1..8 || rate !in MIN_RATE..MAX_RATE || blockAlign != channels * 2) {
            throw SpeechError.InvalidResponse("unsupported wav format")
        }
        return Format(channels, rate.toInt())
    }

    private fun convert(format: Format, data: ByteArray): ByteArray {
        val frameBytes = format.channels * 2
        if (data.isEmpty()) throw SpeechError.InvalidResponse("empty audio")
        if (data.size % frameBytes != 0) throw SpeechError.InvalidResponse("partial wav frame")
        val frames = (data.size / frameBytes).toLong()
        // Sized before converting: a tiny declared rate would otherwise ask for a huge buffer.
        val outBytes = frames * SpeechAudio.SAMPLE_RATE / format.rate * 2
        if (outBytes == 0L) throw SpeechError.InvalidResponse("empty audio")
        if (outBytes > SpeechAudio.MAX_PCM_BYTES) throw SpeechError.InvalidResponse("audio too long")
        return DeviceVoices.toSpeechPcm(data, format.rate, AudioFormat.ENCODING_PCM_16BIT, format.channels)
            ?: throw SpeechError.InvalidResponse("unsupported wav format")
    }

    private fun ascii(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)

    private const val PCM = 1
    private const val EXTENSIBLE = 0xFFFE
    private const val MIN_RATE = 8_000L
    private const val MAX_RATE = 192_000L

    /** KSDATAFORMAT_SUBTYPE_PCM. */
    private val PCM_SUBFORMAT = byteArrayOf(
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
    )
}
