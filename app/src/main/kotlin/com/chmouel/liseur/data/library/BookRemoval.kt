package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.db.AnnotationSyncDao
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
