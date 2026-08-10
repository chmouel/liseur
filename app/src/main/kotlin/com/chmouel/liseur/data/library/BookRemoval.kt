package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.db.BookDao
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
     * Everything keyed by a book URL, in one place.
     *
     * The fingerprints go too. They describe files that are no longer
     * here, and a path that is reused later would otherwise be sent to a
     * server under the hash of whatever used to be at it.
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
