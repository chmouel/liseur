package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book

/**
 * One card on the grouped shelf: a book on its own, or a series
 * standing for its volumes.
 *
 * The grouped view is one grid, not a series section over a book
 * section: a series files under its name between the standalones the
 * way it would on a wooden shelf, so the reader scans one alphabet, one
 * recency, not two.
 */
sealed interface ShelfEntry {
    /** Stable identity for the grid, distinct across the two kinds. */
    val gridKey: String

    data class Single(val book: Book) : ShelfEntry {
        override val gridKey: String get() = "book:${book.id}"
    }

    data class Pile(val shelf: SeriesShelf) : ShelfEntry {
        override val gridKey: String get() = "series:${shelf.key}"
    }
}

/**
 * Folds a library into the entries the grouped shelf draws: one pile
 * per series in [shelves], one single for every book that belongs to
 * none of them, the whole lot in the asked-for order.
 *
 * A book whose series is on the shelf disappears into its pile — the
 * pile is where it is now found — while a book naming a series too
 * small to show ([worthShowing]) stays out as a single, wearing its
 * series line but not a card of its own.
 *
 * [books] and [shelves] arrive already narrowed by search and filters,
 * each by its own rules: a series survives a search any volume matches,
 * a book survives on its own name. The fold here must not re-litigate
 * that, only merge and order.
 */
fun mixedShelf(
    books: List<Book>,
    shelves: List<SeriesShelf>,
    sort: LibrarySort,
    reversed: Boolean = false,
    readAt: Map<String, Long> = emptyMap(),
): List<ShelfEntry> {
    val shown = shelves.mapTo(mutableSetOf()) { it.key }
    val entries = shelves.map<SeriesShelf, ShelfEntry> { ShelfEntry.Pile(it) } +
        books.filter { seriesKey(it.seriesName) !in shown }.map { ShelfEntry.Single(it) }
    return entries.sortedWith(entryComparator(sort, reversed, readAt))
}

/** The string an entry files under: a book by its title, a pile by its name. */
private fun ShelfEntry.nameKey(): String = when (this) {
    is ShelfEntry.Single -> sortKey(book.title)
    is ShelfEntry.Pile -> sortKey(shelf.name)
}

private fun ShelfEntry.authorName(): String? = when (this) {
    is ShelfEntry.Single -> book.displayAuthor
    is ShelfEntry.Pile -> shelf.author
}

/**
 * A pile is as recent as its most recent volume in the best rank any
 * volume holds — the same rule [SeriesShelf.arrangedBy] uses, so
 * turning grouping on does not reshuffle what "recent" means.
 */
private fun ShelfEntry.recentRank(readAt: Map<String, Long>): Int = when (this) {
    is ShelfEntry.Single -> book.recentRank(readAt[book.url])
    is ShelfEntry.Pile ->
        shelf.volumes.minOfOrNull { it.book.recentRank(readAt[it.book.url]) } ?: 2
}

private fun ShelfEntry.recentAt(readAt: Map<String, Long>): Long = when (this) {
    is ShelfEntry.Single -> book.recentAt(readAt[book.url])
    is ShelfEntry.Pile -> {
        val rank = recentRank(readAt)
        shelf.volumes.asSequence()
            .filter { it.book.recentRank(readAt[it.book.url]) == rank }
            .maxOfOrNull { it.book.recentAt(readAt[it.book.url]) }
            ?: 0L
    }
}

/** A pile joined the library when its newest volume did. */
private fun ShelfEntry.addedAt(): Long = when (this) {
    is ShelfEntry.Single -> book.addedAt
    is ShelfEntry.Pile -> shelf.volumes.maxOfOrNull { it.book.addedAt } ?: 0L
}

/** The series an entry files under for [LibrarySort.SERIES], or "". */
private fun ShelfEntry.seriesSortKey(): String = when (this) {
    is ShelfEntry.Single -> seriesKey(book.seriesName)
    is ShelfEntry.Pile -> shelf.key
}

/**
 * Orders [ShelfEntry]s the way [arrangedBy] orders books and
 * [SeriesShelf.arrangedBy] orders shelves, so the mixed grid agrees
 * with both of the views it replaces. Every order finishes on the name
 * and then the key, so equal entries keep their places between
 * launches.
 */
private fun entryComparator(
    sort: LibrarySort,
    reversed: Boolean,
    readAt: Map<String, Long>,
): Comparator<ShelfEntry> {
    val byName = compareBy<ShelfEntry>({ it.nameKey() }, { it.gridKey })

    return when (sort) {
        LibrarySort.RECENT -> {
            val recent = compareBy<ShelfEntry> { it.recentRank(readAt) }
                .thenByDescending { it.recentAt(readAt) }
            (if (reversed) recent.reversed() else recent).then(byName)
        }

        LibrarySort.TITLE ->
            if (reversed) byName.reversed() else byName

        LibrarySort.AUTHOR -> {
            val named = compareBy<ShelfEntry> { sortKey(it.authorName()) }
            val ordered = if (reversed) named.reversed() else named
            compareBy<ShelfEntry> { if (it.authorName().isNullOrBlank()) 1 else 0 }
                .then(ordered)
                .then(byName)
        }

        LibrarySort.ADDED -> {
            val added = compareByDescending<ShelfEntry> { it.addedAt() }
            (if (reversed) added.reversed() else added).then(byName)
        }

        LibrarySort.SERIES -> {
            // A pile *is* its series; a single with a series name files
            // under it too, and a book with none files last, as the
            // plain shelf has always had it. Reversing reads the series
            // back to front but never a series' own volumes backwards.
            val bySeries = compareBy<ShelfEntry> { it.seriesSortKey() }
            val ordered = if (reversed) {
                compareByDescending<ShelfEntry> { it.seriesSortKey() }
            } else {
                bySeries
            }
            compareBy<ShelfEntry> { if (it.seriesSortKey().isEmpty()) 1 else 0 }
                .then(ordered)
                .thenBy { (it as? ShelfEntry.Single)?.book?.seriesIndex == null }
                .thenBy { (it as? ShelfEntry.Single)?.book?.seriesIndex ?: 0.0 }
                .then(byName)
        }
    }
}
