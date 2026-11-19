package com.chmouel.liseur.vocabulary

import java.security.MessageDigest
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

internal const val MAX_VOCABULARY_WORD_LENGTH = 120

internal fun vocabularyLanguage(tag: String?): String =
    Locale.forLanguageTag(tag.orEmpty()).stripExtensions().toLanguageTag()

internal fun normalizeVocabularyWord(text: String, language: String): String? {
    val trimmed = text.trim()
    if (trimmed.length > MAX_VOCABULARY_WORD_LENGTH ||
        trimmed.codePoints().noneMatch { Character.isLetterOrDigit(it) }
    ) return null
    val first = trimmed.indexOfFirst { !it.isVocabularyPunctuation() }
    val last = trimmed.indexOfLast { !it.isVocabularyPunctuation() }
    val normalized = trimmed.substring(first, last + 1)
        .replace('’', '\'')
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.forLanguageTag(language))
    return Normalizer.normalize(normalized, Normalizer.Form.NFC)
}

private fun Char.isVocabularyPunctuation(): Boolean = isWhitespace() || when (Character.getType(this)) {
    Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt() -> true
    else -> false
}

internal fun vocabularySentence(before: String?, word: String, after: String?, language: String): String {
    val prefix = before.orEmpty().takeLast(1000)
    val text = prefix + word + after.orEmpty().take(1000)
    val sentences = BreakIterator.getSentenceInstance(Locale.forLanguageTag(language))
    sentences.setText(text)
    val start = sentences.preceding(prefix.length + 1).coerceAtLeast(0)
    val end = sentences.following(prefix.length + word.length - 1).takeIf { it != BreakIterator.DONE }
        ?: text.length
    return text.substring(start, end).trim()
}


internal fun vocabularyLocationKey(locator: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(locator.toByteArray(Charsets.UTF_8)),
    )

internal fun normalizeVocabularySearch(text: String): String =
    Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFC)

internal fun highlightWord(sentence: String, word: String): IntRange? {
    val marks = Regex("\\p{M}+")
    fun fold(text: String) = Normalizer.normalize(normalizeVocabularySearch(text), Normalizer.Form.NFD)
        .replace(marks, "").uppercase(Locale.ROOT)
    val target = fold(word.trim()).takeIf { it.isNotEmpty() } ?: return null
    val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(sentence) }
    val folded = StringBuilder()
    val starts = mutableListOf<Int>()
    val ends = mutableListOf<Int>()
    var start = characters.first()
    var end = characters.next()
    while (end != BreakIterator.DONE) {
        val part = fold(sentence.substring(start, end))
        folded.append(part)
        repeat(part.length) { starts += start; ends += end }
        start = end
        end = characters.next()
    }
    val match = folded.indexOf(target)
    return if (match < 0) null else starts[match] until ends[match + target.length - 1]
}
