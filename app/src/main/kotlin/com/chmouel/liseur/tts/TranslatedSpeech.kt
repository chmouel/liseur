package com.chmouel.liseur.tts

import com.chmouel.liseur.translate.SentenceTranslations
import com.chmouel.liseur.translate.TranslationError
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit

/**
 * A book read aloud in translation: the translations it shares with the
 * page, from [source] (null when the service works it out) into [target].
 * Whoever hands one to read aloud has [SentenceTranslations.acquire]d a
 * hold for it; the session lets go of it when it ends.
 */
class SpeechTranslation(val translations: SentenceTranslations, val source: String?, val target: String) {
    suspend fun identity(): String = translations.translator.answering()
}

/**
 * What one request says when the book is read translated: the book's
 * sentences in it, each translated where it is, so the page and the
 * voice share the words, then the translation cut into pieces a speech
 * request takes.
 *
 * [sentences] cuts the request's source text the way the page cuts it,
 * one sentence each, for a request that groups several; [pieces] cuts
 * the translation, which may run longer than the source, to the bounds a
 * request takes. A failed translation fails the whole request: nothing is
 * said in the book's own words in its place.
 */
class TranslatedSpeech(
    private val translations: SentenceTranslations,
    private val sentences: (String) -> List<String>,
    private val pieces: (String) -> List<String>,
) {
    suspend fun prepare(key: SpeechKey, previous: String?): List<String> {
        val place = key.place ?: throw TranslationError.Failed("the sentence has no place in the book")
        val parts = sentences(key.text).ifEmpty { listOf(key.text) }
        var context = previous?.let { sentences(it).lastOrNull() ?: it }
        var before = place.before
        val said = mutableListOf<String>()
        for (part in parts) {
            val translated = translations.resolve(place.copy(text = part, before = before), context)
            if (key.translationIdentity != null && translated.by != key.translationIdentity) {
                throw TranslationError.Changed()
            }
            said += translated.text.trim()
            context = part
            before = before.orEmpty() + part
        }
        val text = said.filter { it.isNotEmpty() }.joinToString(" ")
        if (text.isBlank()) throw TranslationError.Empty()
        return pieces(text)
    }

    @OptIn(ExperimentalReadiumApi::class)
    companion object {
        /** [text] in [language] cut into sentences, one each, as the page cuts them. */
        fun sentencesOf(text: String, language: Language?): List<String> =
            BoundedSentenceTokenizer.factory(1)(language).tokenize(text).map { text.substring(it) }

        /**
         * [text] in pieces of at most [max] characters, whole sentences
         * where they fit, so a translation longer than its sentence still
         * goes in requests the service takes.
         */
        fun piecesOf(text: String, language: Language?, max: Int = BoundedSentenceTokenizer.MAX_LENGTH): List<String> {
            if (text.length <= max) return listOf(text)
            val ranges = BoundedSentenceTokenizer(
                DefaultTextContentTokenizer(TextUnit.Sentence, language),
                maxLength = max,
            ).tokenize(text)
            val pieces = mutableListOf<String>()
            var first = -1
            var last = -1
            for (range in ranges) {
                if (first >= 0 && range.last - first + 1 > max) {
                    pieces += text.substring(first, last + 1)
                    first = -1
                }
                if (first < 0) first = range.first
                last = range.last
            }
            if (first >= 0) pieces += text.substring(first, last + 1)
            return pieces.ifEmpty { listOf(text) }
        }
    }
}
