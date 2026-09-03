package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.db.AnnotationSyncDao
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotationDao
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.data.db.SyncPeerStateDao
import com.chmouel.liseur.data.db.WorkIdentityDao

/**
 * Removes library books and their reading statistics as one database change.
 *
 * A book row disappears through several routes: direct deletion, folder
 * removal, a rescan, catalog pruning, and account disconnect. Keeping the
 * rule here prevents any one of those routes from leaving orphaned totals.
 * Removing a download and archiving do not call this because both retain the
 * book in the library and deliberately retain its history.
 */
class BookRemoval(
    private val bookDao: BookDao,
    private val sessionDao: ReadingSessionDao,
    private val peerStateDao: SyncPeerStateDao,
    private val identityDao: WorkIdentityDao,
    private val progressDao: ReadingProgressDao,
    private val annotationDao: BookAnnotationDao,
    private val annotationSyncDao: AnnotationSyncDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    suspend fun deleteByUrls(bookUrls: List<String>) {
        if (bookUrls.isEmpty()) return
        inTransaction {
            forget(bookUrls)
        }
    }

    suspend fun deleteRemoteNotDownloaded() {
        inTransaction {
            val bookUrls = bookDao.remoteNotDownloadedUrls()
            if (bookUrls.isEmpty()) return@inTransaction
            forget(bookUrls)
        }
    }

    /**
     * Clears out what described the book that used to be at a path,
     * when a different one has taken it over.
     *
     * The library row itself stays — a book rewritten in place keeps its
     * place in the library — but everything that described the old
     * contents goes, because none of it describes what is there now.
     *
     * In one transaction, and that matters more than it looks. The marks
     * and the record of what a sync server was told about them have to
     * go together: an agreement left behind with nothing to agree about
     * is indistinguishable from a reader having deleted a highlight, and
     * the next sync would delete it everywhere.
     */
    suspend fun contentReplaced(bookUrl: String) {
        inTransaction {
            progressDao.forget(bookUrl)
            annotationDao.deleteForBook(bookUrl)
            annotationSyncDao.forgetBook(bookUrl)
            sessionDao.deleteForBook(bookUrl)
            // What a server was told this path held goes too. The
            // fingerprints describe a file that is not there any more,
            // and the alias is a name for the book that used to be. Left
            // standing, the next pass would take the new book for the
            // old one: its highlights would be pushed onto the old
            // work, and the old work's would arrive here and anchor
            // into text that never contained them.
            identityDao.forgetFingerprints(listOf(bookUrl))
            identityDao.forgetAliases(listOf(bookUrl))
            identityDao.forgetAmbiguities(listOf(bookUrl))
            peerStateDao.forgetBooks(listOf(bookUrl))
            bookDao.forgetReadingHistory(bookUrl)
            bookDao.clearSeriesForReplacedWork(bookUrl)
        }
    }

    /**
     * Drops entries that are a second name for a book already on the
     * shelf, and only those with nothing on them worth keeping.
     *
     * Earlier versions shelved one file twice whenever it was picked by
     * hand as well as found by a folder scan (issue #147), and those
     * duplicates are still there. A scan can now recognise them, but
     * recognising is not licence to delete: the reader may well have
     * been reading the duplicate — that is where a position opened
     * through `+ -> Add Book` was recorded — and quietly discarding
     * where someone had got to is worse than the duplicate itself. So a
     * row goes only when it holds no reading position, no marks, no
     * agreement with a server about marks, no reading time, and nothing
     * on the row itself either. Anything else is left for the reader to
     * remove by hand.
     *
     * Returns what was actually removed.
     */
    suspend fun dropUntouchedDuplicates(bookUrls: List<String>): List<String> {
        if (bookUrls.isEmpty()) return emptyList()
        val removable = mutableListOf<String>()
        inTransaction {
            for (url in bookUrls.distinct()) {
                val book = bookDao.getByUrl(url) ?: continue
                if (blank(book) && untouched(url)) removable += url
            }
            if (removable.isNotEmpty()) forget(removable)
        }
        return removable
    }

    /**
     * Whether the row itself says nothing that the other one will not.
     *
     * Reading history is not the only thing a duplicate can be carrying.
     * A row that has been uploaded holds the `remote_uuid` that is the
     * only reason the book is not sent a second time; one that was
     * opened, finished, archived or filed into a series by hand holds a
     * decision the reader made. None of that is on the entry this would
     * keep, so none of it may be dropped on the quiet.
     */
    private fun blank(book: Book): Boolean =
        book.remoteUuid == null &&
            book.remoteBookId == null &&
            book.lastOpenedAt == null &&
            book.finishedAt == null &&
            book.archivedAt == null &&
            book.seriesName == book.fileSeriesName &&
            book.seriesIndex == book.fileSeriesIndex

    private suspend fun untouched(bookUrl: String): Boolean =
        progressDao.get(bookUrl) == null &&
            annotationDao.count(bookUrl) == 0 &&
            annotationSyncDao.countForBook(bookUrl) == 0 &&
            sessionDao.countForBook(bookUrl) == 0 &&
            // What a sync partner has agreed about this entry is state
            // too, and `forget` below would take it with the row.
            peerStateDao.countForBook(bookUrl) == 0 &&
            // So is what a server was persuaded to call it. A rejected
            // low-confidence match is a decision the reader made, and
            // nothing here could work it out again.
            identityDao.namingCountForBook(bookUrl) == 0

    /**
     * Everything keyed by a book URL, in one place.
     *
     * The fingerprints go too. They describe files that are no longer
     * here, and a path that is reused later would otherwise be sent to a
     * server under the hash of whatever used to be at it.
     *
     * What the reader marked is deliberately *not* here, and neither is
     * the record of what a sync server was told about it. Removing a
     * book from this device says nothing about the highlights in it —
     * they are still on the server and still on the other phone — so
     * both are left as they are. Dropping only the agreements would be
     * worse than useless: the book coming back would push every mark
     * again as if it were new.
     */
    private suspend fun forget(bookUrls: List<String>) {
        sessionDao.deleteForBooks(bookUrls)
        peerStateDao.forgetBooks(bookUrls)
        identityDao.forgetFingerprints(bookUrls)
        identityDao.forgetAliases(bookUrls)
        identityDao.forgetAmbiguities(bookUrls)
        bookDao.deleteByUrls(bookUrls)
    }
}
