package com.chmouel.liseur.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Where decoded speech is heard. */
interface PcmOutput {
    /**
     * Plays [pcm] (16-bit mono at [SpeechAudio.SAMPLE_RATE]) and returns once
     * the last frame has been heard, not merely written. Cancellable.
     */
    suspend fun play(pcm: ByteArray)

    /** Silences at once and drops anything queued. Synchronous, idempotent, safe on the main thread. */
    fun halt()

    fun release()
}

class PcmOutputException(message: String) : Exception(message)

/**
 * One [AudioTrack] reused for every sentence.
 *
 * Writes are non-blocking and made under a lock that [halt] also takes, so
 * a halt can never be followed by a stale chunk from the sentence it
 * stopped. Completion is the playback head reaching the frames written.
 */
class AudioTrackPcmOutput(
    private val writer: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : PcmOutput {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var turn = 0L

    private fun trackLocked(): AudioTrack =
        track ?: run {
            val minimum = AudioTrack.getMinBufferSize(
                SpeechAudio.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) throw PcmOutputException("No PCM output")
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SpeechAudio.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minimum * 2)
                .build()
                .also {
                    if (it.state != AudioTrack.STATE_INITIALIZED) {
                        it.release()
                        throw PcmOutputException("PCM output did not initialise")
                    }
                    track = it
                }
        }

    override suspend fun play(pcm: ByteArray) = withContext(writer) {
        val (myTurn, track, base, padded) = synchronized(lock) {
            val track = trackLocked()
            // A stream track waits for a full buffer before it starts, so a
            // clip shorter than that is padded with silence to get heard.
            val bufferBytes = track.bufferSizeInFrames * 2
            val padded = if (pcm.size < bufferBytes) pcm.copyOf(bufferBytes) else pcm
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            Quad(turn, track, track.playbackHeadPosition.toLong() and 0xffffffffL, padded)
        }
        val frameMs = 1000.0 / SpeechAudio.SAMPLE_RATE
        // How long a track that stops moving is given: its whole buffer plus slack.
        val stallNanos = ((track.bufferSizeInFrames * frameMs).toLong() + STALL_SLACK_MS) * 1_000_000
        var offset = 0
        var progressAt = System.nanoTime()
        while (offset < padded.size) {
            coroutineContext.ensureActive()
            val written = synchronized(lock) {
                if (turn != myTurn) return@withContext
                track.write(padded, offset, padded.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            if (written < 0) throw PcmOutputException("PCM write failed: $written")
            offset += written
            if (written > 0) {
                progressAt = System.nanoTime()
            } else {
                if (System.nanoTime() - progressAt > stallNanos) throw PcmOutputException("PCM output stalled")
                delay(WRITE_WAIT_MS)
            }
        }
        val target = base + padded.size / 2
        val deadline = System.nanoTime() + stallNanos
        while (true) {
            coroutineContext.ensureActive()
            val head = synchronized(lock) {
                if (turn != myTurn) return@withContext
                track.playbackHeadPosition.toLong() and 0xffffffffL
            }
            if (head >= target) return@withContext
            // Not heard to the end: the sentence fails rather than being skipped.
            if (System.nanoTime() > deadline) throw PcmOutputException("PCM output stalled")
            delay(((target - head) * frameMs).toLong().coerceIn(1, POLL_MS))
        }
    }

    override fun halt() {
        synchronized(lock) {
            turn++
            track?.run {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) pause()
                flush()
            }
        }
    }

    override fun release() {
        synchronized(lock) {
            turn++
            track?.release()
            track = null
        }
    }

    private data class Quad(val turn: Long, val track: AudioTrack, val base: Long, val pcm: ByteArray)

    private companion object {
        const val WRITE_WAIT_MS = 10L
        const val POLL_MS = 20L
        const val STALL_SLACK_MS = 2_000L
    }
}
