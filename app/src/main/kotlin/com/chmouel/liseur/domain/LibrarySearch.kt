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
 */
fun matchesLibrarySearch(query: String, title: String, author: String?): Boolean {
    val words = query.foldForSearch().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = "${title.foldForSearch()} ${author.orEmpty().foldForSearch()}"
    return words.all { haystack.contains(it) }
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
): Boolean = !searchActive || matchesLibrarySearch(query, title, author)

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
