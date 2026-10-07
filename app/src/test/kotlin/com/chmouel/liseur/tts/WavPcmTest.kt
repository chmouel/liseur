package com.chmouel.liseur.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WavPcmTest {

    companion object {
        private val PCM_GUID = byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)

        private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
            ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

        fun chunk(id: String, body: ByteArray, size: Long = body.size.toLong()): ByteArray =
            id.toByteArray(Charsets.US_ASCII) + le(4) { putInt(size.toInt()) } + body +
                if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf()

        fun fmt(rate: Int, channels: Int, tag: Int = 1, bits: Int = 16): ByteArray = le(16) {
            putShort(tag.toShort())
            putShort(channels.toShort())
            putInt(rate)
            putInt(rate * channels * bits / 8)
            putShort((channels * bits / 8).toShort())
            putShort(bits.toShort())
        }

        fun riff(vararg chunks: ByteArray): ByteArray {
            val body = "WAVE".toByteArray(Charsets.US_ASCII) + chunks.fold(byteArrayOf()) { a, b -> a + b }
            return "RIFF".toByteArray(Charsets.US_ASCII) + le(4) { putInt(body.size) } + body
        }

        fun wav(rate: Int, channels: Int, data: ByteArray, dataSize: Long = data.size.toLong()): ByteArray =
            riff(chunk("fmt ", fmt(rate, channels)), chunk("data", data, dataSize))
    }

    private fun invalid(bytes: ByteArray) {
        try {
            WavPcm.toSpeechPcm(bytes)
            fail("expected an invalid response")
        } catch (_: SpeechError.InvalidResponse) {
        }
    }

    @Test
    fun `24 kHz mono passes through untouched`() {
        val data = byteArrayOf(1, 0, 2, 0, 3, 0)
        assertTrue(WavPcm.isWav(wav(24_000, 1, data)))
        assertArrayEquals(data, WavPcm.toSpeechPcm(wav(24_000, 1, data)))
    }

    @Test
    fun `44,1 kHz stereo is mixed down and resampled to 24 kHz`() {
        val out = WavPcm.toSpeechPcm(wav(44_100, 2, ByteArray(441 * 4)))
        assertEquals(240 * 2, out.size)
    }

    @Test
    fun `a streamed reply of unknown length is read to the end`() {
        val data = byteArrayOf(5, 0, 6, 0)
        assertArrayEquals(data, WavPcm.toSpeechPcm(wav(24_000, 1, data, dataSize = 0xFFFFFFFFL)))
        assertArrayEquals(data, WavPcm.toSpeechPcm(wav(24_000, 1, data, dataSize = 0)))
    }

    @Test
    fun `other chunks are skipped, odd ones with their padding byte`() {
        val data = byteArrayOf(7, 0)
        val bytes = riff(chunk("LIST", byteArrayOf(1, 2, 3)), chunk("fmt ", fmt(24_000, 1)), chunk("data", data))
        assertArrayEquals(data, WavPcm.toSpeechPcm(bytes))
    }

    @Test
    fun `extensible PCM is read, extensible float is not`() {
        fun extensible(subformat: ByteArray) = fmt(24_000, 1, tag = 0xFFFE) + le(24) {
            putShort(22)
            putShort(16)
            putInt(4)
            put(subformat)
        }
        val data = byteArrayOf(9, 0)
        assertArrayEquals(data, WavPcm.toSpeechPcm(riff(chunk("fmt ", extensible(PCM_GUID)), chunk("data", data))))

        val float = PCM_GUID.copyOf().also { it[0] = 3 }
        invalid(riff(chunk("fmt ", extensible(float)), chunk("data", data)))
    }

    @Test
    fun `headers that do not add up are errors, not noise`() {
        val data = byteArrayOf(1, 0, 2, 0)
        invalid(riff(chunk("fmt ", fmt(24_000, 1, bits = 8)), chunk("data", data)))
        invalid(riff(chunk("fmt ", fmt(24_000, 1, tag = 3)), chunk("data", data)))
        invalid(riff(chunk("data", data)))
        invalid(riff(chunk("fmt ", fmt(24_000, 1).copyOf(12)), chunk("data", data)))
        invalid(riff(chunk("fmt ", fmt(24_000, 1), size = 64)))
        invalid(riff(chunk("fmt ", fmt(24_000, 1))))
        invalid(wav(24_000, 2, byteArrayOf(1, 0, 2, 0, 3, 0)))
        invalid(wav(24_000, 1, ByteArray(0)))
        invalid(wav(4_000, 1, data))
    }

    @Test
    fun `a low rate cannot turn a reply into too much audio`() {
        // 3 MiB at 8 kHz would be 9 MiB at 24 kHz, over the cap.
        invalid(wav(8_000, 1, ByteArray(3 * 1024 * 1024)))
    }

    @Test
    fun `raw PCM is not taken for a WAV, and a cut RIFF header is not taken for PCM`() {
        assertFalse(WavPcm.isWav(byteArrayOf(1, 0, 2, 0)))
        val cut = "RIFF\u0000\u0000\u0000\u0000WA".toByteArray(Charsets.ISO_8859_1)
        val avi = "RIFF\u0000\u0000\u0000\u0000AVI ".toByteArray(Charsets.ISO_8859_1)
        assertTrue(WavPcm.isWav(cut))
        invalid(cut)
        invalid(avi)
    }
}
