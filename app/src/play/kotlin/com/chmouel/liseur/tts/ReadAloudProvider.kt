package com.chmouel.liseur.tts

import androidx.annotation.StringRes
import com.chmouel.liseur.R

/** The speech services the Play build can read aloud with. */
enum class ReadAloudProvider(
    val id: String,
    @StringRes val label: Int,
    /** Sentences fetched at once: a small self-hosted server only slows down with more. */
    val maxConcurrent: Int,
) {
    GEMINI("gemini", R.string.read_aloud_provider_gemini, SpeechCache.MAX_CONCURRENT),
    OPENAI("openai", R.string.read_aloud_provider_openai, 1),
    ;

    companion object {
        val Default = GEMINI

        /** The stored choice, or the default for none or one this build no longer offers. */
        fun of(id: String?): ReadAloudProvider = entries.firstOrNull { it.id == id } ?: Default
    }
}
