package com.chmouel.liseur.ui.widget

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.domain.displayTitle

internal data class LibraryGrid(val columns: Int, val rows: Int) {
    /**
     * When the grid has two rows, the first book spans both rows on the left
     * and the remaining columns stack two covers each. It spans two columns
     * when there is room, so it keeps a cover's proportions instead of being
     * cropped to a strip.
     */
    val featuredLeft: Boolean get() = rows > 1
    val featuredSpan: Int get() = if (featuredLeft && columns >= 3) 2 else 1
    val sideColumns: Int get() = columns - featuredSpan
    val capacity: Int get() = if (featuredLeft) 1 + sideColumns * rows else columns * rows
}

internal fun libraryGrid(width: Float, height: Float): LibraryGrid = LibraryGrid(
    columns = when { width >= 300 -> 4; width >= 240 -> 3; else -> 2 },
    rows = if (height >= 270) 2 else 1,
)

/** Every page size [libraryGrid] can produce. */
private val LIBRARY_CAPACITIES = listOf(2, 3, 4, 5)

/** Page sizes whose first cover is drawn large, two rows tall. */
internal val LIBRARY_FEATURED_CAPACITIES = listOf(3, 5)

/**
 * The shelf in widget order: the book the cover widget shows first, then
 * books opened on this device, newest first, then the rest by title.
 */
internal fun libraryBooks(books: List<Book>, current: Book?): List<Book> {
    val shelf = books
        .filterNot { it.hidden || it.archived }
        .sortedWith(compareByDescending<Book> { it.lastOpenedAt ?: 0L }.thenBy { it.displayTitle.lowercase() }.thenBy { it.url })
    val first = shelf.firstOrNull { it.url == current?.url } ?: return shelf
    return listOf(first) + shelf.filterNot { it === first }
}

/** Align an anchor to the resized page, and clamp after books are removed. */
internal fun libraryPageStart(anchor: Int, count: Int, capacity: Int): Int {
    require(capacity > 0)
    val lastPage = ((count - 1).coerceAtLeast(0) / capacity) * capacity
    return ((anchor.coerceAtLeast(0) / capacity) * capacity).coerceAtMost(lastPage)
}

/**
 * The indices any widget size can show for [anchor]. A placed widget is
 * drawn at several sizes at once (portrait, landscape), so its covers are
 * all decoded before the first frame instead of after it.
 */
internal fun libraryWindow(anchor: Int, count: Int): Set<Int> =
    LIBRARY_CAPACITIES.flatMapTo(sortedSetOf()) { capacity ->
        val start = libraryPageStart(anchor, count, capacity)
        start until minOf(start + capacity, count)
    }
