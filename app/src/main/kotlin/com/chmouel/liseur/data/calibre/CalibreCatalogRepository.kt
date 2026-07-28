package com.chmouel.liseur.data.calibre

import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.CalibreServerDao
import com.chmouel.liseur.data.db.DownloadState
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a catalog refresh is going, for the library to show. */
sealed interface CatalogStatus {
    data object Idle : CatalogStatus
    data object Refreshing : CatalogStatus
    data object Offline : CatalogStatus

    /** The stored password could not be read, so the account must be set up again. */
    data object CredentialsLost : CatalogStatus
}

/**
 * Keeps a copy of the calibre-web catalog in the library.
 *
 * Mirroring the feed into the database rather than paging it live means
 * the whole library is there to browse, sort and search while offline,
 * and downloaded books sit next to ones that are still on the server.
 */
class CalibreCatalogRepository(
    private val account: CalibreAccountRepository,
    private val serverDao: CalibreServerDao,
    private val bookDao: BookDao,
    private val client: CalibreCatalogClient = CalibreCatalogClient(),
) {
    private val _status = MutableStateFlow<CatalogStatus>(CatalogStatus.Idle)
    val status: StateFlow<CatalogStatus> = _status.asStateFlow()

    private val refreshing = Mutex()

    /** Pulls the catalog and folds it into the library. Safe to call often. */
    suspend fun refresh(): Boolean {
        if (!refreshing.tryLock()) return false
        try {
            val server = serverDao.get() ?: run {
                _status.value = CatalogStatus.Idle
                return false
            }
            val credentials = account.credentials() ?: run {
                _status.value = CatalogStatus.CredentialsLost
                return false
            }

            _status.value = CatalogStatus.Refreshing
            return try {
                val seen = mutableSetOf<String>()
                client.allBooks(server.baseUrl, credentials) { page ->
                    page.forEach { seen += it.uuid }
                    store(server.baseUrl, page)
                }
                dropVanished(seen)
                serverDao.upsert(server.copy(catalogSyncedAt = System.currentTimeMillis()))
                _status.value = CatalogStatus.Idle
                true
            } catch (e: IOException) {
                Log.i(TAG, "Could not refresh the catalog", e)
                _status.value = CatalogStatus.Offline
                false
            }
        } finally {
            refreshing.unlock()
        }
    }

    suspend fun search(query: String): List<OpdsBook> {
        val server = serverDao.get() ?: return emptyList()
        val credentials = account.credentials() ?: return emptyList()
        return try {
            client.search(server.baseUrl, credentials, query)
        } catch (e: IOException) {
            Log.i(TAG, "Could not search the catalog", e)
            emptyList()
        }
    }

    private suspend fun store(baseUrl: String, books: List<OpdsBook>) {
        val now = System.currentTimeMillis()
        books.forEach { remote ->
            val url = remoteUrl(remote.uuid)
            val existing = bookDao.getByRemoteUuid(remote.uuid) ?: bookDao.getByUrl(url)
            bookDao.upsert(
                Book(
                    id = existing?.id ?: 0,
                    url = url,
                    title = remote.title,
                    author = remote.author,
                    // A downloaded book has a real cover extracted from the
                    // file; keep it rather than going back to the network.
                    coverPath = existing?.coverPath,
                    source = null,
                    addedAt = existing?.addedAt ?: now,
                    lastOpenedAt = existing?.lastOpenedAt,
                    localUri = existing?.localUri,
                    remoteUuid = remote.uuid,
                    remoteBookId = remote.bookId,
                    coverUrl = remote.coverHref?.let { CalibreUrl.resolve(baseUrl, it) },
                    downloadState = existing?.downloadState ?: DownloadState.REMOTE,
                    remoteUpdatedAt = remote.updatedAt,
                ),
            )
        }
    }

    /**
     * Forgets books that are no longer in the catalog, unless they are on
     * the device: a book the user has downloaded stays readable even if
     * it is removed from the server.
     */
    private suspend fun dropVanished(seenUuids: Set<String>) {
        val gone = bookDao.allRemote()
            .filter { it.remoteUuid !in seenUuids && it.downloadState == DownloadState.REMOTE }
            .map { it.url }
        if (gone.isNotEmpty()) bookDao.deleteByUrls(gone)
    }

    companion object {
        private const val TAG = "CalibreCatalog"

        /**
         * A remote book's permanent identity. It stays the same whether or
         * not the file is on the device, so reading positions survive a
         * download being removed.
         */
        fun remoteUrl(uuid: String) = "calibre:$uuid"
    }
}
