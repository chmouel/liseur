package com.chmouel.liseur.tts

import androidx.annotation.StringRes
import com.chmouel.liseur.R

/**
 * The Gemini voices offered for reading aloud, a curated few of the
 * prebuilt ones, each with the style Google describes it by.
 */
enum class GeminiVoice(val id: String, @StringRes val style: Int) {
    KORE("Kore", R.string.read_aloud_voice_firm),
    CHARON("Charon", R.string.read_aloud_voice_informative),
    PUCK("Puck", R.string.read_aloud_voice_upbeat),
    AOEDE("Aoede", R.string.read_aloud_voice_breezy),
    LEDA("Leda", R.string.read_aloud_voice_youthful),
    FENRIR("Fenrir", R.string.read_aloud_voice_excitable),
    SULAFAT("Sulafat", R.string.read_aloud_voice_warm),
    ACHERNAR("Achernar", R.string.read_aloud_voice_soft),
    IAPETUS("Iapetus", R.string.read_aloud_voice_clear),
    GACRUX("Gacrux", R.string.read_aloud_voice_mature),
    VINDEMIATRIX("Vindemiatrix", R.string.read_aloud_voice_gentle),
    SCHEDAR("Schedar", R.string.read_aloud_voice_even),
    ;

    companion object {
        val Default = KORE

        /** The stored choice, or the default for none or one this build no longer offers. */
        fun of(id: String?): GeminiVoice = entries.firstOrNull { it.id == id } ?: Default
    }
}
