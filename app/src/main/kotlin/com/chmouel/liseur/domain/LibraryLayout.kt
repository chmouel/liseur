package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import kotlin.math.sqrt

/**
 * How the library screen lays its books out.
 *
 * Every style shows the same books with the same actions, badges and
 * search; only the arrangement differs (ADR-0070).
 */
enum class LibraryStyle(val id: String) {
    /** The cover grid. */
    CLASSIC("classic"),

    /** The current book large, then strips of recent books and series, then the grid. */
    HOME("home"),

    /** Rows of covers standing on shelves, one shelf per group. */
    SHELVES("shelves"),

    /** Rows with the details and progress spelled out. */
    LIST("list"),
    ;

    companion object {
        val Default = CLASSIC

        fun fromId(id: String?): LibraryStyle = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** What puts two books on the same shelf in [LibraryStyle.SHELVES]. */
enum class ShelfGrouping(val id: String) {
    SERIES("series"),
    AUTHOR("author"),
    STATUS("status"),
    ;

    companion object {
        val Default = SERIES

        fun fromId(id: String?): ShelfGrouping = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** The wall drawn behind the shelves in [LibraryStyle.SHELVES]. */
enum class ShelfBackdrop(val id: String) {
    PLAIN("plain"),
    WOOD("wood"),
    PLASTER("plaster"),
    BRICKS("bricks"),
    CUSTOM("custom"),
    ;

    companion object {
        val Default = BRICKS

        fun fromId(id: String?): ShelfBackdrop = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** The material and accent tint of the shelves and frames in [LibraryStyle.SHELVES]. */
enum class ShelfTone(val id: String) {
    THEME("theme"),
    STONE("stone"),
    OAK("oak"),
    WALNUT("walnut"),
    BRASS("brass"),
    ;

    companion object {
        val Default = THEME

        fun fromId(id: String?): ShelfTone = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** The chosen style and the options each of the styles that has any carries. */
data class LibraryLayout(
    val style: LibraryStyle = LibraryStyle.Default,
    val shelfGrouping: ShelfGrouping = ShelfGrouping.Default,
    val shelfBackdrop: ShelfBackdrop = ShelfBackdrop.Default,
    val shelfBackdropUri: String? = null,
    val shelfTone: ShelfTone = ShelfTone.Default,
    val compactList: Boolean = false,
)

/** What a shelf in [LibraryStyle.SHELVES] is labelled with. */
sealed interface ShelfLabel {
    data class Series(val name: String, val key: String) : ShelfLabel

    data class Author(val name: String) : ShelfLabel

    data class Status(val state: LibraryFilterOption) : ShelfLabel

    /** Books that share their group with nobody else on the shelf. */
    data object Other : ShelfLabel
}

data class Shelf(val label: ShelfLabel, val books: List<Book>) {
    val key: String
        get() = when (label) {
            is ShelfLabel.Series -> "series:${label.key}"
            is ShelfLabel.Author -> "author:${label.name.lowercase()}"
            is ShelfLabel.Status -> "status:${label.state.name}"
            ShelfLabel.Other -> "other"
        }
}

private val statusOrder = listOf(
    LibraryFilterOption.IN_PROGRESS,
    LibraryFilterOption.UNREAD,
    LibraryFilterOption.FINISHED,
)

/**
 * [books], already filtered and sorted, cut into shelves by [grouping].
 *
 * Shelves come in the order their first book does, so the library sort
 * still decides what is on top. A series or an author with a single
 * book here is not a shelf: those books go on [ShelfLabel.Other], last,
 * rather than each standing alone on a plank of its own.
 *
 * [shownSeries] are the series keys the library treats as series at all,
 * and [stateOf] is the book's reading state as the shelf filter sees it.
 */
fun shelvesOf(
    books: List<Book>,
    grouping: ShelfGrouping,
    shownSeries: Set<String>,
    stateOf: (Book) -> LibraryFilterOption,
): List<Shelf> = when (grouping) {
    ShelfGrouping.STATUS -> {
        val byState = books.groupBy(stateOf)
        statusOrder.mapNotNull { state ->
            byState[state]?.let { Shelf(ShelfLabel.Status(state), it) }
        }
    }

    ShelfGrouping.SERIES -> grouped(
        books = books,
        minGroupSize = 2,
        keyOf = { book -> seriesKey(book.seriesName).takeIf { it in shownSeries } },
        labelOf = { key, first -> ShelfLabel.Series(first.seriesName.orEmpty().trim(), key) },
        arrange = { group -> group.sortedWith(compareBy(nullsLast()) { it.seriesIndex }) },
    )

    ShelfGrouping.AUTHOR -> grouped(
        books = books,
        minGroupSize = 1,
        keyOf = { book -> book.displayAuthor?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } },
        labelOf = { _, first -> ShelfLabel.Author(first.displayAuthor.orEmpty().trim()) },
        arrange = { it },
    )
}

private fun grouped(
    books: List<Book>,
    minGroupSize: Int,
    keyOf: (Book) -> String?,
    labelOf: (key: String, first: Book) -> ShelfLabel,
    arrange: (List<Book>) -> List<Book>,
): List<Shelf> {
    val groups = LinkedHashMap<String, MutableList<Book>>()
    val loose = mutableListOf<Book>()
    for (book in books) {
        val key = keyOf(book)
        if (key == null) loose += book else groups.getOrPut(key) { mutableListOf() } += book
    }
    val shelves = mutableListOf<Shelf>()
    for ((key, group) in groups) {
        if (group.size < minGroupSize) {
            loose += group
        } else {
            shelves += Shelf(labelOf(key, group.first()), arrange(group))
        }
    }
    if (loose.isNotEmpty()) {
        // Back in library order: a book pushed here because it turned
        // out to be alone keeps its place relative to the other loose ones.
        val order = books.withIndex().associate { (index, book) -> book.id to index }
        shelves += Shelf(ShelfLabel.Other, loose.sortedBy { order[it.id] })
    }
    return shelves
}

/**
 * One labeled span of books standing together on a [PlankRow].
 *
 * [label] is null when the whole bookcase is a single ungrouped pile,
 * or on the second and later planks of a shelf that wrapped onto
 * another board.
 */
data class PlankSegment(
    val label: ShelfLabel?,
    val books: List<Book>,
    val totalInShelf: Int,
)

/** One physical board in [LibraryStyle.SHELVES], holding up to `columns` books. */
data class PlankRow(
    val key: String,
    val segments: List<PlankSegment>,
) {
    val totalBooks: Int get() = segments.sumOf { it.books.size }
}

/**
 * Breaks [shelves] into physical boards of at most [columns] books each
 * so a large group wraps down the bookcase instead of scrolling off to
 * the side.
 *
 * When the only shelf is [ShelfLabel.Other] — a library with no series
 * on it yet — its heading is left off: "Other books" has nothing to be
 * other than. With [packSmallShelves], consecutive small shelves share
 * a board whenever they fit together.
 */
fun planksOf(
    shelves: List<Shelf>,
    columns: Int,
    packSmallShelves: Boolean = false,
): List<PlankRow> {
    val width = columns.coerceAtLeast(1)
    if (shelves.isEmpty()) return emptyList()
    if (shelves.size == 1 && shelves.single().label is ShelfLabel.Other) {
        val only = shelves.single()
        return only.books.chunked(width).mapIndexed { index, chunk ->
            PlankRow(
                key = "${only.key}:$index",
                segments = listOf(
                    PlankSegment(
                        label = null,
                        books = chunk,
                        totalInShelf = only.books.size,
                    ),
                ),
            )
        }
    }

    val rows = mutableListOf<PlankRow>()
    val pending = mutableListOf<Pair<String, PlankSegment>>()
    var pendingSlots = 0

    fun flushPending() {
        if (pending.isEmpty()) return
        rows += PlankRow(
            key = pending.joinToString("|") { it.first },
            segments = pending.map { it.second },
        )
        pending.clear()
        pendingSlots = 0
    }

    for (shelf in shelves) {
        if (packSmallShelves && shelf.books.size < width && shelf.label !is ShelfLabel.Other) {
            if (pendingSlots + shelf.books.size > width) {
                flushPending()
            }
            pending += shelf.key to PlankSegment(
                label = shelf.label,
                books = shelf.books,
                totalInShelf = shelf.books.size,
            )
            pendingSlots += shelf.books.size
            if (pendingSlots == width) {
                flushPending()
            }
        } else {
            flushPending()
            shelf.books.chunked(width).forEachIndexed { index, chunk ->
                rows += PlankRow(
                    key = "${shelf.key}:$index",
                    segments = listOf(
                        PlankSegment(
                            label = if (index == 0) shelf.label else null,
                            books = chunk,
                            totalInShelf = shelf.books.size,
                        ),
                    ),
                )
            }
        }
    }
    flushPending()
    return rows
}

/** The strips [LibraryStyle.HOME] puts above the grid. */
data class HomeSections(
    val recentlyOpened: List<Book> = emptyList(),
    val newBooks: List<Book> = emptyList(),
    val seriesUnderway: List<SeriesShelf> = emptyList(),
) {
    val isEmpty: Boolean get() = recentlyOpened.isEmpty() && newBooks.isEmpty() && seriesUnderway.isEmpty()
}

private const val HOME_STRIP_LIMIT = 12
private const val HOME_SERIES_LIMIT = 3

/**
 * The home strips, from every book on the shelf rather than what the
 * filters left: the strips are about what was read lately, not what the
 * grid below happens to be narrowed to.
 *
 * [current] is the book the hero card already shows, kept out of the
 * strips so it does not appear twice at the top of the screen.
 */
fun homeSectionsOf(
    books: List<Book>,
    series: List<SeriesShelf>,
    current: String?,
    stateOf: (Book) -> LibraryFilterOption,
): HomeSections {
    val shelf = books.filter { !it.archived && !it.hidden }
    val states = shelf.associate { it.url to stateOf(it) }
    // A book read on another device has progress but was never opened
    // here; it is still underway, not new.
    val begun = { book: Book ->
        book.lastOpenedAt != null || states[book.url] == LibraryFilterOption.IN_PROGRESS
    }
    return HomeSections(
        recentlyOpened = shelf
            .filter { begun(it) && states[it.url] != LibraryFilterOption.FINISHED && it.url != current }
            .sortedWith(compareByDescending(nullsFirst()) { it.lastOpenedAt })
            .take(HOME_STRIP_LIMIT),
        newBooks = shelf
            .filter { !begun(it) && states[it.url] == LibraryFilterOption.UNREAD }
            .sortedByDescending { it.addedAt }
            .take(HOME_STRIP_LIMIT),
        seriesUnderway = series
            .filter { it.nextUp != null && (it.inProgressCount > 0 || it.finishedCount > 0) }
            .sortedByDescending { shelf -> shelf.volumes.maxOf { it.book.lastOpenedAt ?: 0L } }
            .take(HOME_SERIES_LIMIT),
    )
}

/** Shortest a progress track gets in [LibraryStyle.LIST], as a share of the longest. */
const val SHORTEST_TRACK = 0.35f

/**
 * How long each book's progress track is drawn, from 0 to 1, so a long
 * book reads as long before it is opened.
 *
 * Page counts are the better measure and are used whenever any book has
 * one; otherwise the file size the server reported. Units are never
 * mixed: a book without the measure in use is left out and drawn at full
 * length. Square-rooted, so a doorstop does not shrink everything else to
 * a stub.
 */
fun trackLengths(books: List<Book>): Map<String, Float> {
    val pages = books.any { (it.remotePageCount ?: 0) > 0 }
    val measure: (Book) -> Double? = if (pages) {
        { book -> book.remotePageCount?.takeIf { it > 0 }?.toDouble() }
    } else {
        { book -> book.sizeBytes?.takeIf { it > 0 }?.toDouble() }
    }
    val longest = books.mapNotNull(measure).maxOrNull() ?: return emptyMap()
    return books.mapNotNull { book ->
        measure(book)?.let { size ->
            book.url to (SHORTEST_TRACK + (1f - SHORTEST_TRACK) * sqrt(size / longest).toFloat())
        }
    }.toMap()
}
