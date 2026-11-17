package com.chmouel.liseur.domain

import kotlin.math.roundToInt

/**
 * What Hardcover holds for one book in the reader's library, as far as
 * a progress push needs to know.
 */
data class HardcoverShelfState(
    /** The library entry's id, or null when the book is not in the library. */
    val userBookId: Long?,
    /** Hardcover's status id; see [HardcoverStatus]. */
    val statusId: Int?,
    /** The read that is started and not finished, if there is one. */
    val openReadId: Long?,
    /** Pages logged on that read. */
    val openReadPages: Int?,
)

/** Hardcover's `user_books.status_id` values. */
object HardcoverStatus {
    const val WANT_TO_READ = 1
    const val CURRENTLY_READING = 2
    const val READ = 3
    const val PAUSED = 4
    const val DID_NOT_FINISH = 5
}

/** One write a push is made of, in the order they must be sent. */
sealed interface HardcoverStep {
    /** Add the book to the library as currently reading; the server starts a read. */
    data object AddAsReading : HardcoverStep

    /** Set the library entry to currently reading; the server starts a read. */
    data object MarkReading : HardcoverStep

    /** Log pages on the open read. */
    data class LogPages(val pages: Int) : HardcoverStep

    /** Set the library entry to read; the server finishes the read. */
    data object MarkRead : HardcoverStep
}

/**
 * What to tell Hardcover about a book, decided from stored state alone.
 *
 * Hardcover keeps a library entry and its reads in step by itself:
 * setting an entry to currently reading starts a read, setting it to
 * read finishes one, and each of those writes a journal entry. So a push
 * sends the fewest statuses that say what happened and never dates a
 * read by hand, or the reader's journal says everything twice.
 *
 * Only reading and finishing are reported. A book that is unread,
 * wanted, paused or put down says nothing here: Hardcover has statuses
 * for some of those, and pushing them is a later decision, not a side
 * effect of this one.
 *
 * Three more things this refuses to do, each because the write would
 * cost the reader something they did on purpose:
 *
 * - A book already marked read stays read. Setting it to currently
 *   reading starts a second read, and opening a finished book to look
 *   something up is not a re-read.
 * - A book marked did-not-finish stays so, for the same reason.
 * - Progress never goes backwards. Flicking back to check a map is not
 *   un-reading the chapters in between, and Hardcover shows the logged
 *   page as where the reader is.
 */
object HardcoverProgress {

    /**
     * The page a share of the book lands on in an edition of [editionPages].
     *
     * Hardcover counts pages, and Liseur knows how far through the text
     * the reader is, so the edition's page count is the bridge. Null
     * without a usable count: an edition Hardcover has no length for
     * cannot be given a page.
     *
     * A book that is not finished never reports its last page: progress
     * that reaches the end of the edition finishes the read on the
     * server, and rounding 99.6% up would mark a book read with a
     * chapter to go.
     */
    fun pagesFor(progression: Double, editionPages: Int?, finished: Boolean): Int? {
        if (editionPages == null || editionPages <= 0) return null
        if (finished) return editionPages
        val share = progression.coerceIn(0.0, 1.0)
        return (share * editionPages).roundToInt().coerceIn(0, (editionPages - 1).coerceAtLeast(0))
    }

    fun plan(
        remote: HardcoverShelfState,
        local: ReadingStatus,
        progression: Double?,
        editionPages: Int?,
    ): List<HardcoverStep> = buildList {
        val finished = local == ReadingStatus.FINISHED
        if (!finished && local != ReadingStatus.READING) return@buildList
        val status = remote.statusId
        if (status == HardcoverStatus.READ || status == HardcoverStatus.DID_NOT_FINISH) return@buildList

        val reading = remote.userBookId != null && status == HardcoverStatus.CURRENTLY_READING
        if (remote.userBookId == null) add(HardcoverStep.AddAsReading)
        else if (!reading) add(HardcoverStep.MarkReading)

        if (finished) {
            add(HardcoverStep.MarkRead)
            return@buildList
        }

        val pages = pagesFor(progression ?: 0.0, editionPages, finished = false)
            ?: return@buildList
        val logged = if (reading) remote.openReadPages ?: 0 else 0
        if (pages > logged) add(HardcoverStep.LogPages(pages))
    }
}
