package com.chmouel.liseur.tts

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A speech server's MP3 reply as [SpeechAudio] wants it, decoded by the
 * platform. OpenRouter answers only MP3 or a PCM whose rate it does not
 * say; MP3 says its own. Anything the decoder cannot read, or that
 * decodes to something other than 16-bit PCM, is an
 * [SpeechError.InvalidResponse].
 */
internal object Mp3Pcm {
    /** Throws [SpeechError.InvalidResponse]; cancelling the caller stops decoding. */
    suspend fun toSpeechPcm(mp3: ByteArray): ByteArray {
        if (mp3.isEmpty()) throw SpeechError.InvalidResponse("empty audio")
        val source = Bytes(mp3)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            if (extractor.trackCount != 1) throw SpeechError.InvalidResponse("unsupported mp3")
            val input = extractor.getTrackFormat(0)
            if (input.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_MPEG) {
                throw SpeechError.InvalidResponse("unsupported mp3")
            }
            extractor.selectTrack(0)
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
            codec.configure(input, null, null, 0)
            codec.start()
            return decode(extractor, codec)
        } catch (e: SpeechError) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // The platform's words are not shown: they say nothing useful.
            throw SpeechError.InvalidResponse("undecodable audio")
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
            source.close()
        }
    }

    private class Pcm(val channels: Int, val rate: Int)

    private suspend fun decode(extractor: MediaExtractor, codec: MediaCodec): ByteArray {
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + DEADLINE_NANOS
        var format: Pcm? = null
        var inputDone = false
        while (true) {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() > deadline) throw SpeechError.InvalidResponse("decoding took too long")
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: throw SpeechError.InvalidResponse("undecodable audio")
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val changed = pcm(codec.outputFormat)
                // Samples at two rates cannot be played as one.
                if (out.size() > 0 && format != null && (changed.rate != format.rate || changed.channels != format.channels)) {
                    throw SpeechError.InvalidResponse("unsupported mp3")
                }
                format = changed
            }
            if (index < 0) continue
            val pcm = format ?: pcm(codec.outputFormat).also { format = it }
            if (info.size > 0) {
                if (info.size % (pcm.channels * 2) != 0) throw SpeechError.InvalidResponse("partial mp3 frame")
                val total = out.size().toLong() + info.size
                // Sized before keeping it: the native rate and channels make it up to 64 times what is played.
                if (total > MAX_NATIVE_BYTES || total / (pcm.channels * 2) * SpeechAudio.SAMPLE_RATE / pcm.rate * 2 > SpeechAudio.MAX_PCM_BYTES) {
                    throw SpeechError.InvalidResponse("audio too long")
                }
                val buffer = codec.getOutputBuffer(index) ?: throw SpeechError.InvalidResponse("undecodable audio")
                val chunk = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(chunk)
                out.write(chunk)
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        val bytes = out.toByteArray()
        val pcm = format
        if (bytes.isEmpty() || pcm == null) throw SpeechError.InvalidResponse("empty audio")
        if (bytes.size.toLong() / (pcm.channels * 2) * SpeechAudio.SAMPLE_RATE / pcm.rate == 0L) {
            throw SpeechError.InvalidResponse("empty audio")
        }
        return DeviceVoices.toSpeechPcm(bytes, pcm.rate, AudioFormat.ENCODING_PCM_16BIT, pcm.channels)
            ?: throw SpeechError.InvalidResponse("unsupported mp3")
    }

    private fun pcm(format: MediaFormat): Pcm {
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (encoding != AudioFormat.ENCODING_PCM_16BIT || channels !in 1..8 || rate !in 8_000..192_000) {
            throw SpeechError.InvalidResponse("unsupported mp3")
        }
        return Pcm(channels, rate)
    }

    private class Bytes(private val bytes: ByteArray) : MediaDataSource() {
        override fun getSize(): Long = bytes.size.toLong()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun close() = Unit
    }

    private const val TIMEOUT_US = 10_000L
    private const val DEADLINE_NANOS = 30_000_000_000L

    /** 16-bit PCM as decoded, before it is made 24 kHz mono. */
    private const val MAX_NATIVE_BYTES = 24 * 1024 * 1024L
}
