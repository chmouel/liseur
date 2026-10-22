package com.chmouel.liseur.translate

import com.chmouel.liseur.data.db.GlossaryEntry

internal data class TranslationTerm(val original: String, val preferred: String)

internal data class BookTranslationPreferences(
    val direction: String = "",
    val terms: List<TranslationTerm> = emptyList(),
) {
    val identity: String get() = if (direction.isEmpty() && terms.isEmpty()) "" else translationKey(
        buildString {
            append(direction.length).append(':').append(direction)
            terms.forEach { append('|').append(it.original.length).append(':').append(it.original).append(it.preferred.length).append(':').append(it.preferred) }
        },
    )

    fun instructions(passages: List<String>): String? = buildList {
        direction.takeIf { it.isNotBlank() }?.let { add("Translation direction for this book: $it") }
        val matching = terms.filter { term -> passages.any { containsTerm(it, term.original) } }
        if (matching.isNotEmpty()) add("Names and terms: " + matching.joinToString("; ") { "${it.original} → ${it.preferred}" })
    }.joinToString("\n").takeIf { it.isNotEmpty() }

    companion object {
        fun from(direction: String, entries: List<GlossaryEntry>, target: String) = BookTranslationPreferences(
            direction,
            entries.filter { it.enabled && it.target == target && it.term.isNotBlank() && it.translation.isNotBlank() }
                .map { TranslationTerm(it.term, it.translation) }.sortedBy { it.original },
        )
    }
}

internal fun containsTerm(text: String, term: String): Boolean {
    if (term.isEmpty()) return false
    val unseparated = term.codePoints().anyMatch {
        Character.UnicodeScript.of(it) in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR)
    }
    if (unseparated) return text.contains(term)
    return Regex("(?<![\\p{L}\\p{M}\\p{N}_])" + Regex.escape(term) + "(?![\\p{L}\\p{M}\\p{N}_])").containsMatchIn(text)
}
