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
     * Plays [pcm] (16-bit mono at [SpeechAudio.SAMPLE_RATE]) from frame
     * [fromFrame] and returns once the last frame has been heard, not merely
     * written. Cancellable.
     */
    suspend fun play(pcm: ByteArray, fromFrame: Int = 0)

    /**
     * Silences at once and drops anything queued. Synchronous, idempotent,
     * safe on the main thread. Returns the frame of the clip being played
     * that had been heard, or null when none was playing.
     */
    fun halt(): Int?

    fun release()
}

class PcmOutputException(message: String) : Exception(message)

/**
 * One [AudioTrack] reused for every sentence.
 *
 * Writes are non-blocking and made under a lock that [halt] also takes, so
 * a halt can never be followed by a stale chunk from the sentence it
 * stopped. Completion is the playback head reaching the frames written.
 *
 * [speed] is read as it plays, so a new speed is heard mid-sentence; the
 * track stretches time and keeps the pitch.
 */
class AudioTrackPcmOutput(
    private val speed: () -> Float = { 1f },
    private val writer: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : PcmOutput {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var turn = 0L
    private var applied = 1f

    /** The clip of the current turn: where its frames start on the track's head, and its bounds. Under [lock]. */
    private var clip: Clip? = null

    /** The speed the track plays at, set to [speed] first. Under [lock]. */
    private fun speedLocked(track: AudioTrack): Float {
        val wanted = speed()
        if (wanted != applied) {
            // A speed the device refuses leaves it as it was rather than failing the sentence.
            runCatching { track.playbackParams = track.playbackParams.setSpeed(wanted) }
                .onSuccess { applied = wanted }
        }
        return applied
    }

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
                    applied = 1f
                }
        }

    override suspend fun play(pcm: ByteArray, fromFrame: Int) = withContext(writer) {
        val (myTurn, track, playing, padded) = synchronized(lock) {
            val track = trackLocked()
            val frames = pcm.size / 2
            val from = fromFrame.coerceIn(0, frames)
            val rest = if (from == 0) pcm else pcm.copyOfRange(from * 2, pcm.size)
            // A stream track waits for a full buffer before it starts, so a
            // clip shorter than that is padded with silence to get heard.
            val bufferBytes = track.bufferSizeInFrames * 2
            val padded = if (rest.size < bufferBytes) rest.copyOf(bufferBytes) else rest
            speedLocked(track)
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            val base = track.playbackHeadPosition.toLong() and 0xffffffffL
            val playing = Clip(turn, base, from, frames)
            clip = playing
            Quad(turn, track, playing, padded)
        }
        val frameMs = 1000.0 / SpeechAudio.SAMPLE_RATE
        // How long a track that stops moving is given: its whole buffer, at
        // the slowest speed, plus slack.
        val stallNanos =
            ((track.bufferSizeInFrames * frameMs / ReadAloudSpeed.STEPS.first()).toLong() + STALL_SLACK_MS) * 1_000_000
        var offset = 0
        var progressAt = System.nanoTime()
        while (offset < padded.size) {
            coroutineContext.ensureActive()
            val written = synchronized(lock) {
                if (turn != myTurn) return@withContext
                speedLocked(track)
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
        val target = playing.base + padded.size / 2
        val deadline = System.nanoTime() + stallNanos
        while (true) {
            coroutineContext.ensureActive()
            val (head, rate) = synchronized(lock) {
                if (turn != myTurn) return@withContext
                val head = track.playbackHeadPosition.toLong() and 0xffffffffL
                if (head >= target && clip === playing) clip = null
                head to speedLocked(track)
            }
            if (head >= target) return@withContext
            // Not heard to the end: the sentence fails rather than being skipped.
            if (System.nanoTime() > deadline) throw PcmOutputException("PCM output stalled")
            delay(((target - head) * frameMs / rate).toLong().coerceIn(1, POLL_MS))
        }
    }

    override fun halt(): Int? = synchronized(lock) {
        val playing = clip?.takeIf { it.turn == turn }
        clip = null
        turn++
        track?.run {
            // Read before the flush, which may move the head.
            val heard = playing?.let { c ->
                val head = playbackHeadPosition.toLong() and 0xffffffffL
                (c.from + (head - c.base).coerceAtLeast(0)).coerceAtMost(c.frames.toLong()).toInt()
            }
            if (playState == AudioTrack.PLAYSTATE_PLAYING) pause()
            flush()
            heard
        }
    }

    override fun release() {
        synchronized(lock) {
            turn++
            clip = null
            track?.release()
            track = null
        }
    }

    private class Clip(val turn: Long, val base: Long, val from: Int, val frames: Int)

    private data class Quad(val turn: Long, val track: AudioTrack, val clip: Clip, val pcm: ByteArray)

    private companion object {
        const val WRITE_WAIT_MS = 10L
        const val POLL_MS = 20L
        const val STALL_SLACK_MS = 2_000L
    }
}
