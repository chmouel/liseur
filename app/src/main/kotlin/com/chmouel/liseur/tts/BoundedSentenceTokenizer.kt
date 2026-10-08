package com.chmouel.liseur.tts

import com.chmouel.liseur.data.settings.AppSettings
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
 * of it, so it is cut at a clause, else at a space. [sentencesPerRequest]
 * sentences go together when they fit in [maxLength], for fewer round
 * trips. Readium tokenizes one text segment at a time, so ranges never
 * leave the segment they came from and the locators Readium builds from
 * them stay right.
 */
@OptIn(ExperimentalReadiumApi::class)
class BoundedSentenceTokenizer(
    private val sentences: TextTokenizer,
    private val minLength: Int = MIN_LENGTH,
    private val maxLength: Int = MAX_LENGTH,
    sentencesPerRequest: Int = 1,
) : TextTokenizer {
    private val perRequest = sentencesPerRequest.coerceIn(AppSettings.SENTENCES_PER_REQUEST)

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
        return grouped(merged).flatMap { split(data, it) }
    }

    /** [units] joined [perRequest] at a time, a group closing early rather than outgrow [maxLength]. */
    private fun grouped(units: List<IntRange>): List<IntRange> {
        if (perRequest == 1) return units
        val groups = mutableListOf<IntRange>()
        var group: IntRange? = null
        var count = 0
        for (unit in units) {
            group = if (group != null && count < perRequest && unit.last - group.first + 1 <= maxLength) {
                count++
                group.first..unit.last
            } else {
                group?.let(groups::add)
                count = 1
                unit
            }
        }
        group?.let(groups::add)
        return groups
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
        fun factory(sentencesPerRequest: Int = 1): (Language?) -> TextTokenizer = { language ->
            BoundedSentenceTokenizer(
                DefaultTextContentTokenizer(TextUnit.Sentence, language),
                sentencesPerRequest = sentencesPerRequest,
            )
        }
    }
}
