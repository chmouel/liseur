package com.chmouel.liseur.data.library

import com.chmouel.liseur.data.db.AnnotationSyncDao
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotationDao
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LibraryFolderDao
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
    private val bookOrbitBindings: com.chmouel.liseur.data.db.BookOrbitBindingDao? = null,
) {
    suspend fun deleteByUrls(bookUrls: List<String>) {
        if (bookUrls.isEmpty()) return
        inTransaction {
            forget(bookUrls)
        }
    }

    /** Removes a watched folder and the books that only came from it. */
    suspend fun deleteFolder(
        folderDao: LibraryFolderDao,
        folderUrl: String,
        rehomedSources: Map<String, String?> = emptyMap(),
    ) {
        inTransaction {
            val books = bookDao.booksForSource(folderUrl)
            val bookUrls = books.map { it.url }
            // The source lookup can involve SAF and therefore happens before
            // this call, but applying its answer here keeps the source change
            // and the deletion in one database transaction.
            val rehomed = rehomedSources.filterKeys { it in bookUrls }
                .also { sources ->
                    sources.forEach { (bookUrl, source) -> bookDao.setSource(bookUrl, source) }
                }
            // A null source is a book nothing could be shown to hold while
            // some tree would not be read. It keeps its row and belongs to
            // no folder, so no scan prunes it and the first one that finds
            // the file takes it back.
            val homed = rehomed.filterValues { it != null }.keys
            books
                .filter { (it.remoteUuid != null || it.localUri != null) && it.url !in homed }
                .forEach { book ->
                    // An uploaded local book keeps its stable URL and server
                    // identity, and one with its own openable copy keeps
                    // it, but the released folder no longer makes
                    // the folder's file available.
                    bookDao.setSource(book.url, null)
                    // A copy the app can open on its own is not the
                    // folder's to take away: only a book that was readable
                    // *through* the folder loses its file along with it.
                    if (book.remoteUuid != null && book.localUri == null) {
                        bookDao.setDownloadState(book.url, DownloadState.REMOTE, null)
                    }
                }
            folderDao.delete(folderUrl)
            forget(
                books
                    // Nothing but the folder's file stood behind these.
                    // A row with a private copy has been kept above,
                    // whether or not it is still linked to a server:
                    // disconnecting an account clears `remote_uuid` and
                    // deliberately leaves the download in place.
                    .filter {
                        it.remoteUuid == null &&
                            it.localUri == null &&
                            it.url !in rehomed.keys
                    }
                    .map { it.url },
            )
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
     * Cuts uploaded books loose from a server that no longer has them.
     * The entries and their reading stay; the link goes, and so does the
     * BookOrbit binding, whose recorded digest would otherwise tie the
     * book back to the vanished id on the next sign-in.
     */
    suspend fun unlinkVanishedUploads(bookUrls: List<String>, accountKey: String) {
        if (bookUrls.isEmpty()) return
        bookDao.unlinkFromRemote(bookUrls)
        // Only the account whose catalog lost the book. Another account's
        // binding, kept across a disconnect, is still true of its server.
        bookOrbitBindings?.let { bindings -> bookUrls.forEach { bindings.delete(accountKey, it) } }
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
            // The remote link named the old contents too. Kept, position
            // sync and catalog lookups would address the server's copy of
            // the book that used to be here as if it were the new one.
            bookDao.unlinkFromRemote(listOf(bookUrl))
            bookOrbitBindings?.clearBook(bookUrl)
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
     * Drops catalog entries that an upload's adoption would make a second
     * name for [remoteUuid], but only if every one of them is untouched.
     *
     * A catalog pass that ran while a book was on its way up has already
     * shelved the server's copy under its own URL. That row is normally
     * minutes old and empty, and the uploaded entry is the one holding the
     * reading. "Normally" is not "always": the reader can have opened it,
     * downloaded it or marked it in the meantime. So this is all or
     * nothing: if any of them holds anything [dropUntouchedDuplicates]
     * would protect, or a file of its own, none is removed and the caller
     * must not link.
     *
     * [forgetExtra] runs for the removed URLs in the same transaction, for
     * provider state keyed by them. Returns whether the way is clear.
     */
    suspend fun dropUntouchedCatalogDuplicates(
        bookUrls: List<String>,
        remoteUuid: String,
        forgetExtra: suspend (List<String>) -> Unit = {},
    ): Boolean {
        if (bookUrls.isEmpty()) return true
        var clear = false
        inTransaction {
            val rows = bookUrls.distinct().mapNotNull { bookDao.getByUrl(it) }
            val removable = rows.all { row ->
                row.remoteUuid == remoteUuid &&
                    row.localUri == null &&
                    row.downloadState == DownloadState.REMOTE &&
                    blank(row.copy(remoteUuid = null, remoteBookId = null)) &&
                    untouched(row.url)
            }
            if (!removable) return@inTransaction
            val urls = rows.map { it.url }
            if (urls.isNotEmpty()) {
                forgetExtra(urls)
                forget(urls)
            }
            clear = true
        }
        return clear
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
            // A book the reader took off the shelf is a decision of its
            // own, and the one entry they can still act on.
            book.hiddenAt == null &&
            // The flags, not only what they currently produce. Filing a
            // book by hand under the series its file already names looks
            // like nothing was said, and a claim still waiting on a
            // server is a decision even when it has changed nothing yet.
            !book.seriesOverridden &&
            !book.indexOverridden &&
            !book.seriesClaimPending &&
            !book.seriesClaimReset &&
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
        bookUrls.distinct().chunked(SQLITE_URL_BATCH_SIZE).forEach { batch ->
            sessionDao.deleteForBooks(batch)
            peerStateDao.forgetBooks(batch)
            identityDao.forgetFingerprints(batch)
            identityDao.forgetAliases(batch)
            identityDao.forgetAmbiguities(batch)
            bookDao.deleteByUrls(batch)
        }
    }

    private companion object {
        // Leave headroom below SQLite's 999-variable limit for Android
        // versions that still use the default limit.
        const val SQLITE_URL_BATCH_SIZE = 900
    }
}
