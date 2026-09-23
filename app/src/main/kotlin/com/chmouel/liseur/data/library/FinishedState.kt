package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.readingStatusFor

/**
 * The one thing that decides whether a book counts as read.
 *
 * Whether a book is finished used to be settled in three places at once —
 * the reader watching the last page go by, the library's own flag, and
 * whatever calibre-web happened to say — with two different thresholds
 * between them. A book could be read on the shelf and unread in sync, and
 * putting one back on the pile by hand was undone the moment it was
 * reopened.
 *
 * Now every one of those routes comes through here, and the library flag
 * is always derived from the same answer rather than kept in parallel.
 */
class FinishedState(
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Someone said so outright, from the library or the reader.
     *
     * This counts as reading the server has not been told about, so it
     * travels to the other devices like a page turn does.
     */
    suspend fun setFinished(bookUrl: String, finished: Boolean) {
        val override = if (finished) FinishedOverride.FINISHED else FinishedOverride.UNREAD
        setStatus(bookUrl, override, null)
    }

    /**
     * Applies a status from a partner as a new local state without moving the passage,
     * unless the row now belongs to another account.
     */
    suspend fun adoptRemoteStatus(
        bookUrl: String,
        expectedStatusRevision: Long,
        account: String,
        override: FinishedOverride,
        status: ReadingStatus,
    ): Boolean {
        val at = now()
        var applied = false
        inTransaction {
            val progression = progressDao.get(bookUrl)?.totalProgression
            applied = progressDao.adoptFinishedOverride(
                bookUrl = bookUrl,
                expectedStatusRevision = expectedStatusRevision,
                account = account,
                override = override.ordinal,
                status = status.wireName,
                progression = progression,
                now = at,
            )
            if (applied) applyFlag(bookUrl, status == ReadingStatus.FINISHED, at)
        }
        return applied
    }

    private suspend fun setStatus(
        bookUrl: String,
        override: FinishedOverride,
        requestedStatus: ReadingStatus?,
    ) {
        val at = now()
        inTransaction {
            val progression = progressDao.get(bookUrl)?.totalProgression
            val status = requestedStatus ?: readingStatusFor(progression, override)
            progressDao.setFinishedOverride(
                bookUrl = bookUrl,
                override = override.ordinal,
                status = status.wireName,
                progression = progression,
                now = at,
            )
            applyFlag(bookUrl, status == ReadingStatus.FINISHED, at)
        }
    }

    /**
     * The position moved, so whatever it now implies stands — unless
     * someone has said otherwise, in which case they still have.
     */
    suspend fun refreshFromProgress(bookUrl: String) {
        val at = now()
        inTransaction {
            val row = progressDao.get(bookUrl) ?: return@inTransaction
            val finished =
                readingStatusFor(row.totalProgression, row.override) == ReadingStatus.FINISHED
            applyFlag(bookUrl, finished, at)
        }
    }

    /** Keeps the library flag where it was, if it is already right. */
    private suspend fun applyFlag(bookUrl: String, finished: Boolean, at: Long) {
        val book = bookDao.getByUrl(bookUrl) ?: return
        when {
            finished && book.finishedAt == null -> bookDao.setFinishedAt(bookUrl, at)
            !finished && book.finishedAt != null -> bookDao.setFinishedAt(bookUrl, null)
        }
    }
}
