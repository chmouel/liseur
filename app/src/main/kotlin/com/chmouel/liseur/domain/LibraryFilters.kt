package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState

/**
 * The axis a filter narrows.
 *
 * Options in the same group answer the same question, so they are
 * combined with *or*: ticking every option on an axis says no more than
 * ticking none of them. Different groups are combined with *and*, which
 * is what lets the reader ask for a downloaded book they have not
 * finished — the one thing the old row of exclusive chips could not say.
 */
enum class FilterGroup {
    /** Whether the file is on the device. */
    AVAILABILITY,

    /** How far into the book the reader is. */
    READING,

    /**
     * Not a narrowing of the shelf but a different shelf. Held apart
     * because archived books are out of every other view: the point of
     * putting a book away is not to meet it again while looking for
     * something else.
     */
    PLACE,
}

/** One tickable way of narrowing the library. */
enum class LibraryFilterOption(val id: String, val group: FilterGroup) {
    DOWNLOADED("downloaded", FilterGroup.AVAILABILITY),
    NOT_DOWNLOADED("not_downloaded", FilterGroup.AVAILABILITY),

    /** Not opened far enough to leave a trace, and not marked read. */
    UNREAD("unread", FilterGroup.READING),

    /** Started and not finished. */
    IN_PROGRESS("in_progress", FilterGroup.READING),
    FINISHED("finished", FilterGroup.READING),

    /** The books put away. */
    ARCHIVED("archived", FilterGroup.PLACE),
    ;

    companion object {
        fun fromId(id: String?): LibraryFilterOption? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How much of a book has to be read before it counts as started.
 *
 * A locator is written the moment a book is opened, so *any* progress at
 * all would move a book out of Unread for having been glanced at. This
 * is roughly one page of a novel.
 */
private const val STARTED_PROGRESSION = 0.01

/**
 * Everything narrowing the library at once.
 *
 * [groupBySeries] is not one of the [options] because it is a view mode
 * rather than a narrowing: what it changes is not how many cards are on
 * the shelf but what one card stands for.
 */
data class LibraryFilters(
    val options: Set<LibraryFilterOption> = emptySet(),
    val groupBySeries: Boolean = false,
) {
    /** Whether the archive is being looked at rather than the shelf. */
    val archived: Boolean get() = LibraryFilterOption.ARCHIVED in options

    /** Whether anything at all is narrowing the shelf. */
    val isEmpty: Boolean get() = options.isEmpty() && !groupBySeries

    fun toggle(option: LibraryFilterOption): LibraryFilters = copy(
        options = if (option in options) options - option else options + option,
    )

    private fun group(group: FilterGroup): Set<LibraryFilterOption> =
        options.filterTo(mutableSetOf()) { it.group == group }

    /**
     * Whether a book belongs in this view.
     *
     * [progression] is how far through the book the reader is, which the
     * book row does not know: it lives in the progress table, and the
     * caller has it in hand.
     */
    fun accepts(book: Book, progression: Double? = null): Boolean {
        // Checked before anything else, and never as one condition among
        // the rest: an archived book is out of every view but its own,
        // however well it matches the others.
        if (book.archived != archived) return false

        val availability = group(FilterGroup.AVAILABILITY)
        if (availability.isNotEmpty()) {
            val onDevice = book.openableUrl != null ||
                book.downloadState == DownloadState.DOWNLOADED
            val wanted = if (onDevice) {
                LibraryFilterOption.DOWNLOADED
            } else {
                LibraryFilterOption.NOT_DOWNLOADED
            }
            if (wanted !in availability) return false
        }

        val reading = group(FilterGroup.READING)
        if (reading.isNotEmpty()) {
            val started = (progression ?: 0.0) >= STARTED_PROGRESSION
            val wanted = when {
                book.finished -> LibraryFilterOption.FINISHED
                started -> LibraryFilterOption.IN_PROGRESS
                else -> LibraryFilterOption.UNREAD
            }
            if (wanted !in reading) return false
        }

        return true
    }

    /**
     * The options as one string, for storing.
     *
     * Sorted by declaration order rather than by set order, so the same
     * selection always writes the same string and a rewrite of an
     * unchanged filter does not wake every reader of the settings flow.
     */
    fun serialise(): String = LibraryFilterOption.entries
        .filter { it in options }
        .joinToString(",") { it.id }

    companion object {
        val None = LibraryFilters()

        /**
         * Reads back what [serialise] wrote, dropping anything it does
         * not recognise. A filter written by a newer version has to
         * degrade into a wider shelf, never into a crash.
         */
        fun parse(stored: String?): Set<LibraryFilterOption> =
            stored?.split(",")
                ?.mapNotNullTo(mutableSetOf()) { LibraryFilterOption.fromId(it.trim()) }
                ?: emptySet()
    }
}
