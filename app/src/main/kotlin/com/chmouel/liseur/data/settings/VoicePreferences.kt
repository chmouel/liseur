package com.chmouel.liseur.data.settings

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The voice the reader chose for one language with one speech service:
 * [provider] is the service's id, [context] what its voices belong to (the
 * device's speech engine package, a server's API root, or nothing for
 * Gemini), [model] the speech model or nothing, and [language] a primary
 * language subtag such as `en`.
 */
data class VoicePreference(
    val provider: String,
    val context: String,
    val model: String,
    val language: String,
    val voice: String,
) {
    /** Whether [other] is for the same service, context, model and language, so one replaces the other. */
    fun sameSlot(other: VoicePreference): Boolean =
        provider == other.provider && context == other.context && model == other.model && language == other.language
}

/** The stored form of the voices remembered per language, a JSON array; backed up, never synced. */
internal object VoicePreferences {
    /** Enough for every language of a few services; the oldest go first. */
    const val MAX_ENTRIES = 200

    /** What [json] holds, skipping what it cannot read; nothing for none or for malformed JSON. */
    fun decode(json: String?): List<VoicePreference> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(json)
        } catch (_: JSONException) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            val entry = array.optJSONObject(i) ?: return@mapNotNull null
            val voice = entry.optString("voice").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val provider = entry.optString("provider").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val language = entry.optString("language").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VoicePreference(provider, entry.optString("context"), entry.optString("model"), language, voice)
        }
    }

    fun encode(preferences: List<VoicePreference>): String = JSONArray().apply {
        preferences.forEach {
            put(
                JSONObject()
                    .put("provider", it.provider)
                    .put("context", it.context)
                    .put("model", it.model)
                    .put("language", it.language)
                    .put("voice", it.voice),
            )
        }
    }.toString()

    /** [preferences] with [preference] in place of the one for its slot, latest last. */
    fun with(preferences: List<VoicePreference>, preference: VoicePreference): List<VoicePreference> =
        (preferences.filterNot { it.sameSlot(preference) } + preference).takeLast(MAX_ENTRIES)
}
