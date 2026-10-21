package com.chmouel.liseur.tts

import androidx.annotation.StringRes
import com.chmouel.liseur.R

/**
 * A speech service offered by name on the server address field: its
 * OpenAI-compatible address, or for a self-hosted server ([example]) an
 * address whose host the reader replaces with their own. [keyUrl] opens
 * the hosted service's API keys page when one exists.
 */
internal class SpeechServerPreset(
    @StringRes val name: Int,
    val url: String,
    val keyUrl: String? = null,
    val example: Boolean = false,
) {
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
    // Keys are made in Studio's Organization page, which has no address of its own.
    private const val GRADIUM_KEYS = "https://studio.gradium.ai/"

    val all = listOf(
        SpeechServerPreset(R.string.speech_preset_openai, "https://api.openai.com/v1", keyUrl = "https://platform.openai.com/api-keys"),
        SpeechServerPreset(R.string.speech_preset_deepinfra, "https://api.deepinfra.com/v1/openai", keyUrl = "https://deepinfra.com/dash/api_keys"),
        SpeechServerPreset(R.string.speech_preset_openrouter, "https://openrouter.ai/api/v1", keyUrl = "https://openrouter.ai/settings/keys"),
        SpeechServerPreset(R.string.speech_preset_mistral, "https://api.mistral.ai/v1", keyUrl = "https://console.mistral.ai/api-keys"),
        SpeechServerPreset(R.string.speech_preset_groq, "https://api.groq.com/openai/v1", keyUrl = "https://console.groq.com/keys"),
        SpeechServerPreset(R.string.speech_preset_together, "https://api.together.ai/v1", keyUrl = "https://api.together.xyz/settings/api-keys"),
        SpeechServerPreset(R.string.speech_preset_gradium, "https://api.gradium.ai/api", keyUrl = GRADIUM_KEYS),
        SpeechServerPreset(R.string.speech_preset_gradium_eu, "https://eu.api.gradium.ai/api", keyUrl = GRADIUM_KEYS),
        SpeechServerPreset(R.string.speech_preset_gradium_us, "https://us.api.gradium.ai/api", keyUrl = GRADIUM_KEYS),
        SpeechServerPreset(R.string.speech_preset_kokoro, "http://192.168.1.10:8880", example = true),
        SpeechServerPreset(R.string.speech_preset_lm_studio, "http://192.168.1.10:1234", example = true),
        SpeechServerPreset(R.string.speech_preset_ollama, "http://192.168.1.10:11434", example = true),
    )

    /** The hosted service whose server [url] is on, if any. */
    fun matching(url: String): SpeechServerPreset? {
        val origin = OpenAiTts.baseUrl(url)?.let(ServerKeys::origin) ?: return null
        return all.firstOrNull { !it.example && it.origin == origin }
    }
}
