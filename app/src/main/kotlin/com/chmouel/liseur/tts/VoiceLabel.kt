package com.chmouel.liseur.tts

import java.util.Locale

/**
 * How a voice id from a speech service is shown. Kokoro names its voices
 * `<language><gender>_<name>` (`af_bella` is an American English woman),
 * which is read into a name, a language and a gender; any other id is
 * shown as it is, capitalised.
 */
internal data class VoiceLabel(
    val id: String,
    val name: String,
    /** A BCP 47 tag such as `en-US`, or null when the id does not say. */
    val language: String?,
    val gender: Gender?,
) {
    enum class Gender { FEMALE, MALE }

    companion object {
        private val KOKORO = Regex("^([a-z])([fm])_([a-z0-9]+)$")

        // Kokoro's own letters for the languages it speaks, each with the
        // country its voices come from, which gives the flag shown.
        private val LANGUAGES = mapOf(
            'a' to "en-US",
            'b' to "en-GB",
            'e' to "es-ES",
            'f' to "fr-FR",
            'h' to "hi-IN",
            'i' to "it-IT",
            'j' to "ja-JP",
            'p' to "pt-BR",
            'z' to "zh-CN",
        )

        fun of(id: String): VoiceLabel {
            val match = KOKORO.matchEntire(id)
            val language = match?.let { LANGUAGES[it.groupValues[1][0]] }
                ?: return VoiceLabel(id, id.replaceFirstChar { it.uppercaseChar() }, null, null)
            return VoiceLabel(
                id = id,
                name = match.groupValues[3].replaceFirstChar { it.uppercaseChar() },
                language = language,
                gender = if (match.groupValues[2] == "f") Gender.FEMALE else Gender.MALE,
            )
        }

        /**
         * The [listed] voices to offer: those [chosen], keeping the
         * [stored] one so the voice in use is always seen; all of them
         * when nothing listed was chosen.
         */
        fun offered(listed: List<String>, chosen: Set<String>, stored: String): List<String> {
            if (listed.none { it in chosen }) return listed
            return listed.filter { it in chosen || it == stored }
        }

        /** The flag of the country [language] names, as an emoji, or null when it names none. */
        fun flag(language: String): String? {
            val region = Locale.forLanguageTag(language).country
            if (region.length != 2 || region.any { it !in 'A'..'Z' }) return null
            return buildString { region.forEach { appendCodePoint(REGIONAL_INDICATOR_A + (it - 'A')) } }
        }

        /** [language]'s name in [locale], after its country's flag when it has one. */
        fun languageLabel(language: String, locale: Locale): String =
            listOfNotNull(flag(language), Locale.forLanguageTag(language).getDisplayName(locale)).joinToString(" ")

        private const val REGIONAL_INDICATOR_A = 0x1F1E6

        /** [ids] grouped by language in the order first seen, voices with none last. */
        fun grouped(ids: List<String>): List<Pair<String?, List<VoiceLabel>>> {
            val groups = ids.map(::of).groupBy { it.language }
            return groups.filterKeys { it != null }.toList() + groups.filterKeys { it == null }.toList()
        }
    }
}
