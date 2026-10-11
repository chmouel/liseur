package com.chmouel.liseur.translate

import java.text.Normalizer
import java.util.Locale

/**
 * Liseur's language tags against ML Kit's codes. ML Kit has one Chinese
 * model, which writes Simplified characters, and one Portuguese model,
 * whose output follows no particular region. A book in either Portuguese
 * can be translated from; neither is offered as a target, nor is
 * Traditional Chinese.
 */
internal object MlKitLanguages {
    /** The ML Kit code for [tag], or null when ML Kit cannot read or write it as asked. */
    fun code(tag: String, codes: Set<String>, asTarget: Boolean): String? {
        val kept = TranslationLanguages.of(tag) ?: return null
        val code = when {
            kept == "zh-Hans" -> "zh"
            kept == "pt" || kept == "pt-BR" || kept == "pt-PT" -> if (asTarget) return null else "pt"
            '-' in kept -> return null
            else -> ALIASES[kept] ?: kept
        }
        return code.takeIf { it in codes }
    }

    /** [code] as Liseur keeps it. */
    fun tag(code: String): String = TranslationLanguages.of(code) ?: code

    /** The languages a passage can be in. */
    fun sources(codes: Set<String>): Set<String> =
        codes.mapTo(mutableSetOf(), ::tag).apply { if ("pt" in codes) addAll(listOf("pt-BR", "pt-PT")) }

    /**
     * What [source] goes into: ready once both languages are on the phone.
     * Any source, when it is not known, needs only the target's model.
     */
    fun targets(codes: Set<String>, downloaded: Set<String>, source: String?): Map<String, PairState> {
        val from = source?.let { code(it, codes, asTarget = false) ?: return emptyMap() }
        // Only what a saved target maps back to, so Portuguese is never offered.
        return codes.filter { it != from && code(tag(it), codes, asTarget = true) == it }.associate { to ->
            val ready = to in downloaded && (from == null || from in downloaded)
            tag(to) to if (ready) PairState.Ready else PairState.NeedsDownload
        }
    }

    /** The two ML Kit codes for [source] into [target]; null when ML Kit has no such pair. */
    fun pair(codes: Set<String>, source: String?, target: String): Pair<String, String>? {
        val from = source?.let { code(it, codes, asTarget = false) } ?: return null
        val to = code(target, codes, asTarget = true) ?: return null
        return if (from == to) null else from to to
    }

    /** One language on the languages page: [sourceOnly] when books in it can be read but nothing is written in it. */
    data class Entry(val code: String, val name: String, val onPhone: Boolean, val sourceOnly: Boolean)

    /** Every language ML Kit has, named by [name], those matching [query] only, in [order] of their names. */
    fun entries(codes: Set<String>, downloaded: Set<String>, name: (String) -> String, query: String, order: Comparator<in String>): List<Entry> {
        val wanted = folded(query.trim())
        return codes.map { Entry(it, name(it), it in downloaded, code(tag(it), codes, asTarget = true) != it) }
            .filter { wanted.isEmpty() || wanted in folded(it.name) || it.code.equals(wanted, ignoreCase = true) }
            .sortedWith(compareBy(order) { it.name })
    }

    // Without case or accents, so "fran" and "espanol" find what they mean.
    private fun folded(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)

    private val MARKS = Regex("\\p{Mn}+")

    // Tags a book or the system may use for a language ML Kit codes another way.
    private val ALIASES = mapOf("nb" to "no", "fil" to "tl", "iw" to "he", "in" to "id")
}
