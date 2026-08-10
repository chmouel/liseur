package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.data.db.SyncPeerStateDao

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
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    suspend fun deleteByUrls(bookUrls: List<String>) {
        if (bookUrls.isEmpty()) return
        inTransaction {
            sessionDao.deleteForBooks(bookUrls)
            peerStateDao.forgetBooks(bookUrls)
            bookDao.deleteByUrls(bookUrls)
        }
    }

    suspend fun deleteRemoteNotDownloaded() {
        inTransaction {
            val bookUrls = bookDao.remoteNotDownloadedUrls()
            if (bookUrls.isEmpty()) return@inTransaction
            sessionDao.deleteForBooks(bookUrls)
            peerStateDao.forgetBooks(bookUrls)
            bookDao.deleteByUrls(bookUrls)
        }
    }
}
