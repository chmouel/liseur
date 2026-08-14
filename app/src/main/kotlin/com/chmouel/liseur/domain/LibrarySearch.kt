package com.chmouel.liseur.domain

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
): Boolean {
    val words = query.foldForSearch().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = listOf(
        title,
        author.orEmpty(),
        series.orEmpty(),
    ).joinToString(" ") { it.foldForSearch() }
    val volume = seriesIndexLabel(seriesIndex)
    return words.all { haystack.contains(it) || it == volume }
}

/**
 * Whether a book stays on the shelf, given the state of the search bar.
 *
 * Closing search keeps the query so it can be offered back next time,
 * which means a query outlives the bar that showed it. A shelf narrowed
 * by a query nobody can see has simply lost books, so the query counts
 * only while the bar is up.
 */
fun survivesLibrarySearch(
    query: String,
    searchActive: Boolean,
    title: String,
    author: String?,
    series: String? = null,
    seriesIndex: Double? = null,
): Boolean = !searchActive || matchesLibrarySearch(query, title, author, series, seriesIndex)

/**
 * Reduces text to something two spellings of the same word agree on.
 *
 * Decomposing first and then dropping the combining marks turns é into
 * e, which is what lets an accented title be found from a keyboard that
 * cannot easily produce the accent.
 */
private fun String.foldForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()

private val COMBINING_MARKS = Regex("\\p{Mn}+")
