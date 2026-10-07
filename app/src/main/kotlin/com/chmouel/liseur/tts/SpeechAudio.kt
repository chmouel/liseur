package com.chmouel.liseur.tts

import java.io.IOException

/** Mono 16-bit little-endian PCM at [SAMPLE_RATE], what every speech service here answers with. */
class SpeechAudio(val pcm: ByteArray) {
    val frames: Int get() = pcm.size / 2

    companion object {
        const val SAMPLE_RATE = 24_000

        /** About three minutes of speech, far beyond any one bounded sentence. */
        const val MAX_PCM_BYTES = 8 * 1024 * 1024
    }
}

/** Why a sentence could not be turned into speech. Messages never carry the key or the text. */
sealed class SpeechError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** [code] is null when the key could not even be sent. */
    class InvalidKey(code: Int? = null) : SpeechError(
        code?.let { "The speech service rejected the API key (HTTP $it)" } ?: "The API key cannot be sent",
    )
    class InvalidVoice(code: Int) : SpeechError("The speech service has no such voice (HTTP $code)")
    class RateLimited(code: Int) : SpeechError("Speech quota or rate limit reached (HTTP $code)")
    class Network(cause: IOException) :
        SpeechError("The speech service could not be reached (${cause.javaClass.simpleName})", cause)
    class Service(val code: Int) : SpeechError("The speech service answered HTTP $code")
    class InvalidResponse(reason: String) : SpeechError("The speech service sent no usable audio: $reason")
}

/**
 * Turns one sentence into audio, with the key and voice of the session
 * already bound. An interface so the engine can be tested without a network.
 */
fun interface SpeechSynthesizer {
    /** Throws [SpeechError]; cancelling the caller cancels the request. */
    suspend fun synthesize(text: String): SpeechAudio
}
