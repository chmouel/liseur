package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.ReadingSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

    /**
     * The reading desk: the current book, shelf tiles, series under way and
     * what to pick up next. With the bottom bar it is a tab rather than a
     * style of the library.
     */
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
    val compactGrid: Boolean = false,
    val bottomBar: Boolean = true,
    /** With the bottom bar, whether the app opens on the Home tab or the Library tab. */
    val startOnHome: Boolean = true,
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

/** The four tiles under "Your shelves" on the Home tab. */
enum class HomeShelfKind { READING, TO_READ, SERIES, FINISHED }

/**
 * One tile under "Your shelves": how many books it holds, up to three
 * covers to fan out, and the one figure worth a second look ([note];
 * zero leaves it out).
 */
data class HomeShelfTile(
    val kind: HomeShelfKind,
    val count: Int,
    val covers: List<Book>,
    val note: Int,
)

/** A series under way, drawn as one wide card with a bar per volume. */
data class HomeSeriesSet(
    val shelf: SeriesShelf,
    val read: Int,
    val next: SeriesVolume?,
    /** How far through each volume, in series order, from 0 to 1. */
    val volumeProgress: List<Float>,
)

/** Why a book is in "Pick up next", which decides its label. */
enum class PickReason { IN_PROGRESS, NEXT_IN_SERIES, UNREAD }

data class HomePick(val book: Book, val reason: PickReason, val progression: Double?)

/**
 * The Home tab: a finite desk about what is being read and what comes
 * next, rather than another way of looking at the whole library.
 *
 * [starter] is offered in the hero's place when nothing is under way,
 * so a freshly stocked library still opens on something to read.
 */
data class HomeDesk(
    val tiles: List<HomeShelfTile> = emptyList(),
    val seriesSets: List<HomeSeriesSet> = emptyList(),
    val pickUpNext: List<HomePick> = emptyList(),
    val recentlyAdded: List<Book> = emptyList(),
    val starter: Book? = null,
    val shelfSize: Int = 0,
)

private const val HOME_STRIP_LIMIT = 12
private const val HOME_SERIES_LIMIT = 4
private const val HOME_STARTER_PICKS = 6
private const val ALMOST_DONE = 0.85
private const val ADDED_LATELY_MS = 14L * 24 * 60 * 60 * 1000

/**
 * The Home tab, from every book on the shelf rather than what the
 * library's filters left: Home is about what is being read, not about
 * what the Library tab happens to be narrowed to.
 *
 * [current] is the book the hero already shows, kept out of the strips
 * so it does not appear twice. [thisMonthFrom] is the first instant of
 * the current month, for the Finished tile's note.
 */
fun homeDeskOf(
    books: List<Book>,
    series: List<SeriesShelf>,
    current: String?,
    progressions: Map<String, Double>,
    now: Long,
    thisMonthFrom: Long,
    stateOf: (Book) -> LibraryFilterOption,
): HomeDesk {
    val shelf = books.filter { !it.archived && !it.hidden }
    val states = shelf.associate { it.url to stateOf(it) }
    val reading = shelf
        .filter { states[it.url] == LibraryFilterOption.IN_PROGRESS }
        .sortedWith(compareByDescending(nullsFirst()) { it.lastOpenedAt })
    val unread = shelf
        .filter { states[it.url] == LibraryFilterOption.UNREAD }
        .sortedByDescending { it.addedAt }
    val finished = shelf
        .filter { states[it.url] == LibraryFilterOption.FINISHED }
        .sortedByDescending { it.finishedAt }
    val underway = series
        .filter { it.nextUp != null && (it.inProgressCount > 0 || it.finishedCount > 0) }
        .sortedByDescending { set -> set.volumes.maxOf { it.book.lastOpenedAt ?: 0L } }

    val tiles = listOf(
        HomeShelfTile(
            kind = HomeShelfKind.READING,
            count = reading.size,
            covers = reading.take(3),
            note = reading.count { (progressions[it.url] ?: 0.0) >= ALMOST_DONE },
        ),
        HomeShelfTile(
            kind = HomeShelfKind.TO_READ,
            count = unread.size,
            covers = unread.take(3),
            note = unread.count { it.addedAt >= now - ADDED_LATELY_MS },
        ),
        HomeShelfTile(
            kind = HomeShelfKind.SERIES,
            count = series.size,
            covers = series.take(3).map { it.cover },
            note = underway.size,
        ),
        HomeShelfTile(
            kind = HomeShelfKind.FINISHED,
            count = finished.size,
            covers = finished.take(3),
            note = finished.count { (it.finishedAt ?: 0L) >= thisMonthFrom },
        ),
    )

    val picks = LinkedHashMap<String, HomePick>()
    reading.filter { it.url != current }.forEach {
        picks[it.url] = HomePick(it, PickReason.IN_PROGRESS, progressions[it.url])
    }
    underway.mapNotNull { it.nextUp }.forEach { volume ->
        val url = volume.book.url
        if (url != current && url !in picks) {
            picks[url] = HomePick(volume.book, PickReason.NEXT_IN_SERIES, volume.progression.takeIf { volume.inProgress })
        }
    }
    // A library nobody has started yet still gets something to pick up.
    if (picks.isEmpty()) {
        unread.filter { it.url != current }.take(HOME_STARTER_PICKS).forEach {
            picks[it.url] = HomePick(it, PickReason.UNREAD, null)
        }
    }
    val pickUpNext = picks.values.take(HOME_STRIP_LIMIT)
    val picked = pickUpNext.mapTo(mutableSetOf()) { it.book.url }

    return HomeDesk(
        tiles = tiles,
        seriesSets = underway.take(HOME_SERIES_LIMIT).map { set ->
            HomeSeriesSet(
                shelf = set,
                read = set.finishedCount,
                next = set.nextUp,
                volumeProgress = set.volumes.map { volume ->
                    when {
                        volume.finished -> 1f
                        volume.inProgress -> (volume.progression ?: 0.0).toFloat().coerceIn(0f, 1f)
                        else -> 0f
                    }
                },
            )
        },
        pickUpNext = pickUpNext,
        recentlyAdded = unread
            .filter { it.url != current && it.url !in picked }
            // Every book on the shelf being new would only repeat the Library.
            .takeIf { unread.size < shelf.size }
            .orEmpty()
            .take(HOME_STRIP_LIMIT),
        starter = unread.firstOrNull().takeIf { current == null },
        shelfSize = shelf.size,
    )
}

/** Today's reading and the run of days with some, for the Home header. */
data class ReadingPulse(val minutesToday: Int, val streakDays: Int)

/**
 * The Home header's figure, from the sessions this device recorded.
 * Null when there is nothing to say, so the header does not boast about
 * a nought.
 */
fun readingPulseOf(
    sessions: List<ReadingSession>,
    today: LocalDate,
    zone: ZoneId,
): ReadingPulse? {
    val byDay = sessions
        .filter { it.durationMs > 0 }
        // Same rule as the statistics: a sitting that runs past midnight counts on the day it ended.
        .groupBy { Instant.ofEpochMilli(it.endedAt ?: it.lastCheckpointAt).atZone(zone).toLocalDate() }
        .mapValues { (_, day) -> day.sumOf { it.durationMs } }
    val minutes = ((byDay[today] ?: 0L) / 60_000L).toInt()
    val streak = activeDayStreak(byDay.keys, today)
    return ReadingPulse(minutes, streak).takeIf { minutes > 0 || streak > 0 }
}

private const val MIN_PACE_EVIDENCE_MS = 5L * 60 * 1000
private const val MIN_PACE_COVERED = 0.02

/**
 * How long the rest of a book should take at the pace its own sessions
 * show, or null when they show too little to say: a guess on a page or
 * two of evidence would be a number the reader cannot trust.
 */
fun timeLeftMs(
    sessions: List<ReadingSession>,
    progression: Double?,
): Long? {
    val at = progression?.takeIf { it in 0.0..<1.0 } ?: return null
    var spent = 0L
    var covered = 0.0
    sessions.forEach { session ->
        val from = session.startProgression ?: return@forEach
        val to = session.endProgression ?: return@forEach
        if (to > from && session.durationMs > 0) {
            spent += session.durationMs
            covered += to - from
        }
    }
    if (spent < MIN_PACE_EVIDENCE_MS || covered < MIN_PACE_COVERED) return null
    return ((1.0 - at) * spent / covered).toLong()
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
