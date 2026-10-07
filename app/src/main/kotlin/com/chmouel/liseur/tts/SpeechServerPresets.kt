package com.chmouel.liseur.tts

import androidx.annotation.StringRes
import com.chmouel.liseur.R

/**
 * A speech service offered by name on the server address field: its
 * OpenAI-compatible address, or for a self-hosted server ([example]) an
 * address whose host the reader replaces with their own.
 */
internal class SpeechServerPreset(@StringRes val name: Int, val url: String, val example: Boolean = false) {
    /** The server the address is on, as its key is kept, see [ServerKeys.origin]. */
    val origin: String = ServerKeys.origin(requireNotNull(OpenAiTts.baseUrl(url)) { url })

    /** Where the example's host is in [url], for the reader to type over. */
    val host: IntRange
        get() {
            val start = url.indexOf("://") + 3
            val end = url.indexOf(':', start).takeIf { it > 0 } ?: url.indexOf('/', start).takeIf { it > 0 } ?: url.length
            return start until end
        }
}

internal object SpeechServerPresets {
    val all = listOf(
        SpeechServerPreset(R.string.speech_preset_openai, "https://api.openai.com/v1"),
        SpeechServerPreset(R.string.speech_preset_deepinfra, "https://api.deepinfra.com/v1/openai"),
        SpeechServerPreset(R.string.speech_preset_openrouter, "https://openrouter.ai/api/v1"),
        SpeechServerPreset(R.string.speech_preset_mistral, "https://api.mistral.ai/v1"),
        SpeechServerPreset(R.string.speech_preset_groq, "https://api.groq.com/openai/v1"),
        SpeechServerPreset(R.string.speech_preset_kokoro, "http://192.168.1.10:8880/v1", example = true),
    )

    /** The hosted service whose server [url] is on, if any. */
    fun matching(url: String): SpeechServerPreset? {
        val origin = OpenAiTts.baseUrl(url)?.let(ServerKeys::origin) ?: return null
        return all.firstOrNull { !it.example && it.origin == origin }
    }
}
