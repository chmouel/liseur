package com.chmouel.liseur.tts

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

        // Kokoro's own letters for the languages it speaks.
        private val LANGUAGES = mapOf(
            'a' to "en-US",
            'b' to "en-GB",
            'e' to "es",
            'f' to "fr-FR",
            'h' to "hi",
            'i' to "it",
            'j' to "ja",
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

        /** [ids] grouped by language in the order first seen, voices with none last. */
        fun grouped(ids: List<String>): List<Pair<String?, List<VoiceLabel>>> {
            val groups = ids.map(::of).groupBy { it.language }
            return groups.filterKeys { it != null }.toList() + groups.filterKeys { it == null }.toList()
        }
    }
}
