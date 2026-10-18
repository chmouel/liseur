package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import java.text.Normalizer

/**
 * Whether a book answers to what someone typed into the library search.
 *
 * Matching ignores case and accents, so `eloge` finds *Éloge*, and looks
 * for every word separately rather than as one phrase: a shelf is
 * searched from half-remembered fragments, and "brown morning" should
 * find *Morning Star* by Pierce Brown even though those words never
 * appear together in that order.
 *
 * The series counts as much as the title, so a half-remembered series
 * name finds every volume of it -- which is a thing people look for far
 * more often than they look for a title they cannot quite recall -- and
 * "expanse 3" finds the one volume of it.
 *
 * That last part is why the volume number is matched whole rather than
 * as a substring: a 3 found inside 13 would answer "expanse 3" with
 * volumes 3, 13, 23 and 30 through 39, which is the opposite of what
 * naming a volume is for.
 */
fun matchesLibrarySearch(
    query: String,
    title: String,
    author: String?,
    series: String? = null,
    seriesIndex: Double? = null,
    otherSeries: List<String> = emptyList(),
): Boolean {
    val words = query.foldForSearch().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = (listOf(title, author.orEmpty(), series.orEmpty()) + otherSeries)
        .joinToString(" ") { it.foldForSearch() }
    val volume = seriesIndexLabel(seriesIndex)
    return words.all { haystack.contains(it) || it == volume }
}

/**
 * Every book that answers to [query], best match first, or nothing for
 * a blank query.
 *
 * A search is a lookup, not a view of the shelf, so the shelf's filters
 * do not apply: a finished book and an archived one are found like any
 * other. A hidden book is the exception, because hiding it was asking
 * for it to be gone from everywhere but Settings.
 *
 * Results are books, never series. A query that names a series lists
 * its volumes, kept together and in reading order by the tie-breaks.
 */
fun searchLibrary(query: String, books: List<Book>): List<Book> {
    val words = query.foldForSearch().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val phrase = words.joinToString(" ")
    return books
        .filterNot { it.hidden }
        .mapNotNull { book -> searchTier(book, phrase, words)?.let { SearchHit(book, it) } }
        .sortedWith(hitOrder)
        .map { it.book }
}

/**
 * Where in [text] the words of [query] are, as ranges into [text] as
 * written, for emboldening what a search matched.
 */
fun searchMatchRanges(text: String, query: String): List<IntRange> {
    val words = query.foldForSearch().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty() || text.isEmpty()) return emptyList()
    return foldTracking(text).rangesOf(words)
}

/**
 * The other names a book's series goes by: the file's and the catalog's,
 * when they differ from the one it is shelved under.
 *
 * Not when the reader filed the book by hand. Taking a book out of a
 * series is a decision, and the old name finding it again would undo it.
 */
private fun Book.otherSeriesNames(): List<String> {
    if (seriesOverridden) return emptyList()
    val shown = seriesKey(seriesName)
    return listOfNotNull(fileSeriesName, catalogSeriesName)
        .filter { it.isNotBlank() && seriesKey(it) != shown }
        .distinctBy { seriesKey(it) }
}

private data class SearchHit(val book: Book, val tier: Int)

/**
 * How well [book] answers the query, lower is better, or null when it
 * does not answer it at all.
 *
 * The title first, because that is what a reader usually types; a
 * series name next, then the author. A query whose words land in
 * different fields still matches, last.
 */
private fun searchTier(book: Book, phrase: String, words: List<String>): Int? {
    val otherSeries = book.otherSeriesNames()
    if (!matchesLibrarySearch(
            phrase,
            book.displayTitle,
            book.displayAuthor,
            book.seriesName,
            book.seriesIndex,
            otherSeries,
        )
    ) {
        return null
    }
    val title = book.displayTitle.foldForSearch()
    val series = (listOfNotNull(book.seriesName) + otherSeries).map { it.foldForSearch() }
    val volume = seriesIndexLabel(book.seriesIndex)
    val author = book.displayAuthor?.foldForSearch()
    return when {
        title.startsWith(phrase) -> SEARCH_TIER_TITLE_PREFIX
        startsAWord(title, phrase) || words.all { title.contains(it) } -> SEARCH_TIER_TITLE
        series.isNotEmpty() &&
            words.all { word -> word == volume || series.any { it.contains(word) } } ->
            SEARCH_TIER_SERIES
        author != null && words.all { author.contains(it) } -> SEARCH_TIER_AUTHOR
        else -> SEARCH_TIER_SPREAD
    }
}

/**
 * Within a tier, books file the way the series sort files them: a
 * series by its name with its volumes in order, a book in none by its
 * title. The id last, so equal books keep their places as the list
 * updates under the reader's typing.
 */
private val hitOrder: Comparator<SearchHit> =
    compareBy<SearchHit> { it.tier }
        .thenBy { sortKey((it.book.seriesName ?: it.book.displayTitle).foldForSearch()) }
        .thenBy(nullsLast<Double>()) { it.book.seriesIndex }
        .thenBy { sortKey(it.book.displayTitle.foldForSearch()) }
        .thenBy { it.book.id }

private const val SEARCH_TIER_TITLE_PREFIX = 0
private const val SEARCH_TIER_TITLE = 1
private const val SEARCH_TIER_SERIES = 2
private const val SEARCH_TIER_AUTHOR = 3
private const val SEARCH_TIER_SPREAD = 4

/**
 * Reduces text to something two spellings of the same word agree on.
 *
 * Decomposing first and then dropping the combining marks turns é into
 * e, which is what lets an accented title be found from a keyboard that
 * cannot easily produce the accent.
 *
 * Greek's two lower-case sigmas are collapsed onto one. Which of ς or σ
 * a word ends in is decided by the keyboard that typed it, not by the
 * word — and lowercasing is itself context-sensitive (a whole string
 * ends a word in ς, a character at a time never does), so without this
 * the same name folds two ways depending on who is doing the folding.
 */
internal fun String.foldForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .replace(FINAL_SIGMA, SMALL_SIGMA)

internal val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Greek small letter final sigma, folded onto the ordinary one. */
private const val FINAL_SIGMA = 'ς'
private const val SMALL_SIGMA = 'σ'

/** Whether [foldedQuery] starts one of the words of [foldedText]. */
internal fun startsAWord(foldedText: String, foldedQuery: String): Boolean {
    var at = foldedText.indexOf(foldedQuery)
    while (at > 0) {
        if (!foldedText[at - 1].isLetterOrDigit()) return true
        at = foldedText.indexOf(foldedQuery, at + 1)
    }
    return false
}

/**
 * A folded text that still knows where each of its characters came from.
 *
 * Folding a whole string at once and then using the offsets it hands
 * back is a bug waiting for its first accent: dropping a combining mark
 * shortens the text, so *Éloge* folded says the match is at 0..4 and the
 * name says that is *Éloge* minus its last letter. Folding a character
 * at a time and keeping the source index of each one costs nothing at
 * this size and cannot drift.
 */
internal class FoldedText(val text: String, private val source: IntArray, private val length: Int) {
    /**
     * Where each query word landed, in the *original* text's indices.
     *
     * Overlapping ranges are merged: "ex expanse" finds *Ex* and
     * *Expanse* at the same place, and drawing them one after the other
     * would embolden the shorter and skip the rest of the longer.
     */
    fun rangesOf(words: List<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        words.forEach { word ->
            var at = text.indexOf(word)
            while (at >= 0) {
                val start = source[at]
                val endExclusive = if (at + word.length < source.size) {
                    source[at + word.length]
                } else {
                    length
                }
                if (endExclusive > start) ranges += start until endExclusive
                at = text.indexOf(word, at + word.length)
            }
        }
        val merged = mutableListOf<IntRange>()
        ranges.sortedBy { it.first }.forEach { range ->
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }
}

/** Folds a text the way the library search does, keeping the offsets. */
internal fun foldTracking(name: String): FoldedText {
    val folded = StringBuilder(name.length)
    val source = mutableListOf<Int>()
    name.forEachIndexed { index, char ->
        val piece = char.toString().foldForSearch()
        folded.append(piece)
        repeat(piece.length) { source += index }
    }
    return FoldedText(folded.toString(), source.toIntArray(), name.length)
}
