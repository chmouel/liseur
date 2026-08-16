package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book

/**
 * One series as the picker needs to show it.
 *
 * A name alone is not enough to choose by: *The Expanse* the eight-book
 * shelf and *Expanse* the one-off spelling a feed once supplied sort
 * together, and what tells the row apart from a neighbour is what sits
 * around the name — the cover, who wrote it, how many books are on the
 * shelf. All of it is already known to [SeriesShelf]; this is that,
 * narrowed to what a row draws and what the ranking sorts on.
 */
data class SeriesPickOption(
    val key: String,
    /** The series name as its books spell it, for showing. */
    val name: String,
    val author: String?,
    val volumeCount: Int,
    /** The cover to draw beside the name. */
    val cover: Book,
    /** When a volume of it was last read, for putting the likely ones first. */
    val lastReadAt: Long?,
    /** The highest volume number on the shelf, for suggesting the next one. */
    val maxIndex: Double?,
)

/**
 * A series the query found, and where in its name it was found.
 *
 * The ranges are into [SeriesPickOption.name] as written, so a row can
 * embolden what matched without folding the name a second time and
 * hoping the offsets survived.
 */
data class RankedSeries(
    val option: SeriesPickOption,
    val matches: List<IntRange>,
)

/**
 * The shelf as the picker sees it.
 *
 * [readAt] is when each book was last read, by URL — the same map the
 * library already sorts by, so "recently read series" costs one lookup
 * per volume rather than a query of its own.
 */
fun SeriesShelf.asPickOption(readAt: Map<String, Long>): SeriesPickOption = SeriesPickOption(
    key = key,
    name = name,
    author = author,
    volumeCount = volumes.size,
    cover = cover,
    lastReadAt = volumes.mapNotNull { readAt[it.book.url] }.maxOrNull(),
    maxIndex = volumes.mapNotNull { it.index }.maxOrNull(),
)

/**
 * The series worth offering for what has been typed, best first.
 *
 * The old picker sorted into two piles — starts-with, and everything
 * else — which on a shelf of two hundred is barely a sort at all. What
 * a reader types is nearly always the start of a name or the start of a
 * word inside one, so those are separate tiers, and an author match is
 * a tier below both because typing *Corey* to find *The Expanse* is a
 * fallback rather than an intention.
 *
 * Within a tier the series read most recently comes first. Somebody
 * filing volume nine is nearly always filing it next to volume eight,
 * which they were reading last week — and when nothing has been read,
 * the bigger shelf wins, because that is the one that has proved it
 * gathers books.
 *
 * An empty query is not a search. It returns the whole shelf in
 * alphabetical order, for the sections and the letter rail to make
 * navigable.
 */
fun rankSeriesOptions(
    typed: String,
    options: List<SeriesPickOption>,
): List<RankedSeries> {
    val query = typed.trim()
    if (query.isEmpty()) {
        return options.sortedWith(byName).map { RankedSeries(it, emptyList()) }
    }

    val foldedQuery = query.foldForSearch()
    val words = foldedQuery.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) {
        return options.sortedWith(byName).map { RankedSeries(it, emptyList()) }
    }

    return options
        .mapNotNull { option ->
            val folded = foldTracking(option.name)
            val tier = tierOf(folded.text, foldedQuery, words, option.author)
                ?: return@mapNotNull null
            Scored(
                ranked = RankedSeries(option, folded.rangesOf(words)),
                tier = tier,
            )
        }
        .sortedWith(byScore)
        .map { it.ranked }
}

/**
 * The series to offer before anything is typed.
 *
 * A book being filed is usually joining a shelf it has an obvious
 * claim on: the rest of its author's work, or the series its own title
 * announces — *The Expanse* for *The Expanse: Nemesis Games*. Guessing
 * those saves the reader from typing at all, which is the only way a
 * picker gets faster as a library gets bigger.
 *
 * A guess that is wrong costs a glance. So they are offered, never
 * chosen: nothing here writes anything.
 */
fun suggestedSeries(
    title: String,
    author: String?,
    options: List<SeriesPickOption>,
    limit: Int = 3,
): List<SeriesPickOption> {
    val foldedTitle = title.foldForSearch()
    val foldedAuthor = author?.foldForSearch()?.takeIf { it.isNotBlank() }
    return options
        .mapNotNull { option ->
            val name = option.name.foldForSearch()
            val rank = when {
                name.isNotEmpty() && foldedTitle.startsWith(name) -> 0
                foldedAuthor != null &&
                    option.author?.foldForSearch() == foldedAuthor -> 1

                else -> null
            }
            rank?.let { it to option }
        }
        .sortedWith(
            compareBy<Pair<Int, SeriesPickOption>> { it.first }
                .thenByDescending { it.second.lastReadAt ?: Long.MIN_VALUE }
                .thenByDescending { it.second.volumeCount }
                .thenBy { sortKey(it.second.name) },
        )
        .map { it.second }
        .take(limit)
}

/**
 * The series a volume was read from most recently.
 *
 * Offered above the alphabet because the shelf someone was reading
 * yesterday is the one they are most likely filing into today, and
 * because a list that always opens on the letter A makes the same
 * reader scroll to the same place every time.
 */
fun recentSeries(options: List<SeriesPickOption>, limit: Int = 5): List<SeriesPickOption> =
    options
        .filter { it.lastReadAt != null }
        .sortedByDescending { it.lastReadAt }
        .take(limit)

/**
 * The letter a series files under in the picker's alphabet.
 *
 * Built off [sortKey], so *The Expanse* files under E exactly as it
 * sorts, and a name starting with a digit or a symbol lands in one
 * bucket at the end rather than in a scattering of one-row sections.
 */
fun seriesInitial(name: String): String {
    val first = sortKey(name.foldForSearch()).firstOrNull() ?: return OTHER_INITIAL
    return if (first.isLetter()) first.uppercase() else OTHER_INITIAL
}

/** Where names that do not start with a letter gather. */
const val OTHER_INITIAL = "#"

/**
 * The volume number to offer for a book joining this series.
 *
 * The next one along, because a series is filled in order far more
 * often than it is filled in the middle. Null when the shelf has no
 * numbers at all — a reader who has never numbered these books is not
 * asking to start now.
 */
fun suggestedVolume(option: SeriesPickOption): Double? {
    val max = option.maxIndex ?: return null
    if (!max.isFinite()) return null
    return Math.floor(max) + 1.0
}

/** Which tier a series lands in, or null when the query does not find it. */
private fun tierOf(
    foldedName: String,
    foldedQuery: String,
    words: List<String>,
    author: String?,
): Int? = when {
    foldedName == foldedQuery -> TIER_EXACT
    foldedName.startsWith(foldedQuery) -> TIER_PREFIX
    startsAWord(foldedName, foldedQuery) -> TIER_WORD_PREFIX
    words.all { foldedName.contains(it) } -> TIER_CONTAINS
    words.all { (foldedName + ' ' + author.orEmpty().foldForSearch()).contains(it) } ->
        TIER_AUTHOR

    else -> null
}

/** Whether the query starts one of the words of the name. */
private fun startsAWord(foldedName: String, foldedQuery: String): Boolean {
    var at = foldedName.indexOf(foldedQuery)
    while (at > 0) {
        if (!foldedName[at - 1].isLetterOrDigit()) return true
        at = foldedName.indexOf(foldedQuery, at + 1)
    }
    return false
}

private data class Scored(val ranked: RankedSeries, val tier: Int)

/**
 * A folded name that still knows where each of its characters came from.
 *
 * Folding a whole string at once and then using the offsets it hands
 * back is a bug waiting for its first accent: dropping a combining mark
 * shortens the text, so *Éloge* folded says the match is at 0..4 and the
 * name says that is *Éloge* minus its last letter. Folding a character
 * at a time and keeping the source index of each one costs nothing at
 * this size and cannot drift.
 */
private class FoldedText(val text: String, private val source: IntArray, private val length: Int) {
    /** Where each query word landed, in the *original* name's indices. */
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
        return ranges.sortedBy { it.first }
    }
}

/** Folds a name the way the library search does, keeping the offsets. */
private fun foldTracking(name: String): FoldedText {
    val folded = StringBuilder(name.length)
    val source = mutableListOf<Int>()
    name.forEachIndexed { index, char ->
        val piece = char.toString().foldForSearch()
        folded.append(piece)
        repeat(piece.length) { source += index }
    }
    return FoldedText(folded.toString(), source.toIntArray(), name.length)
}

private val byName: Comparator<SeriesPickOption> = compareBy { sortKey(it.name) }

private val byScore: Comparator<Scored> =
    compareBy<Scored> { it.tier }
        .thenByDescending { it.ranked.option.lastReadAt ?: Long.MIN_VALUE }
        .thenByDescending { it.ranked.option.volumeCount }
        .thenBy { sortKey(it.ranked.option.name) }

private const val TIER_EXACT = 0
private const val TIER_PREFIX = 1
private const val TIER_WORD_PREFIX = 2
private const val TIER_CONTAINS = 3
private const val TIER_AUTHOR = 4
