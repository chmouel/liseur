package com.chmouel.liseur.tts

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit

/**
 * Sentences sized for one speech request each.
 *
 * Every request costs a round trip, so very short sentences ride along with
 * the next one; a very long one would make the listener wait for the whole
 * of it, so it is cut at a clause, else at a space. Readium tokenizes one
 * text segment at a time, so ranges never leave the segment they came from
 * and the locators Readium builds from them stay right.
 */
@OptIn(ExperimentalReadiumApi::class)
class BoundedSentenceTokenizer(
    private val sentences: TextTokenizer,
    private val minLength: Int = MIN_LENGTH,
    private val maxLength: Int = MAX_LENGTH,
) : TextTokenizer {

    override fun tokenize(data: String): List<IntRange> {
        val merged = mutableListOf<IntRange>()
        var pending: IntRange? = null
        for (range in sentences.tokenize(data)) {
            val joined = pending?.let { it.first..range.last } ?: range
            pending = if (joined.count() < minLength) joined else {
                merged += joined
                null
            }
        }
        pending?.let { short ->
            // A short tail joins the sentence before it rather than going alone.
            val last = merged.removeLastOrNull()
            merged += if (last != null && (short.last - last.first + 1) <= maxLength) last.first..short.last else {
                last?.let(merged::add)
                short
            }
        }
        return merged.flatMap { split(data, it) }
    }

    private fun split(data: String, range: IntRange): List<IntRange> {
        val pieces = mutableListOf<IntRange>()
        var start = range.first
        while (range.last - start + 1 > maxLength) {
            val window = (start + minLength) until (start + maxLength)
            val cut = window.reversed().firstOrNull { data[it] in CLAUSE_BREAKS }?.plus(1)
                ?: window.reversed().firstOrNull { data[it].isWhitespace() }
                ?: (start + maxLength).let { if (data[it - 1].isHighSurrogate()) it - 1 else it }
            trimmed(data, start until cut)?.let(pieces::add)
            start = cut
        }
        trimmed(data, start..range.last)?.let(pieces::add)
        return pieces
    }

    private fun trimmed(data: String, range: IntRange): IntRange? {
        var first = range.first
        var last = range.last
        while (first <= last && data[first].isWhitespace()) first++
        while (last >= first && data[last].isWhitespace()) last--
        return if (first <= last) first..last else null
    }

    companion object {
        const val MIN_LENGTH = 40
        const val MAX_LENGTH = 600
        private val CLAUSE_BREAKS = setOf(',', ';', ':', '—', '–', '…', ')')

        /** The one factory both the navigator and the prefetcher are given, so they cut text alike. */
        val factory: (Language?) -> TextTokenizer = { language ->
            BoundedSentenceTokenizer(DefaultTextContentTokenizer(TextUnit.Sentence, language))
        }
    }
}
