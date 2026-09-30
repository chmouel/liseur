package com.chmouel.liseur.ui.widget

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.domain.displayTitle

internal data class LibraryGrid(val columns: Int, val rows: Int) {
    val capacity: Int get() = columns * rows
}

internal fun libraryGrid(width: Float, height: Float): LibraryGrid = LibraryGrid(
    columns = when { width >= 300 -> 4; width >= 240 -> 3; else -> 2 },
    rows = if (height >= 270) 2 else 1,
)

internal fun libraryBooks(books: List<Book>): List<Book> = books
    .filterNot { it.hidden || it.archived }
    .sortedWith(compareByDescending<Book> { it.lastOpenedAt ?: 0L }.thenBy { it.displayTitle.lowercase() }.thenBy { it.url })

/** Align an anchor to the resized page, and clamp after books are removed. */
internal fun libraryPageStart(anchor: Int, count: Int, capacity: Int): Int {
    require(capacity > 0)
    val lastPage = ((count - 1).coerceAtLeast(0) / capacity) * capacity
    return ((anchor.coerceAtLeast(0) / capacity) * capacity).coerceAtMost(lastPage)
}
