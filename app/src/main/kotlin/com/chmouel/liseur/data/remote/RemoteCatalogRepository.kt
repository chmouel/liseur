package com.chmouel.liseur.data.remote

import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * Keeps a copy of the connected server's catalog in the library.
 *
 * Mirroring the catalog into the database rather than paging it live
 * means the whole library is there to browse, sort and search while
 * offline, and downloaded books sit next to ones that are still on the
 * server.
 *
 * Which server that is does not reach this far: the [router] hands over
 * whichever [CatalogSource] matches, and the books that come back are
 * already in the same shape whoever sent them.
 */
class RemoteCatalogRepository(
    private val router: RemoteRouter,
    private val serverDao: RemoteServerDao,
    private val bookDao: BookDao,
    /**
     * Whose account a book belongs to is only true until someone signs
     * out, so every write here checks and writes in one go. Anything
     * less and a disconnect landing mid-refresh leaves the new account
     * holding the old one's library.
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _status = MutableStateFlow<CatalogStatus>(CatalogStatus.Idle)
    val status: StateFlow<CatalogStatus> = _status.asStateFlow()

    private val refreshing = Mutex()

    /**
     * Starts a refresh that outlives the screen that asked for it.
     *
     * Connecting an account is a way of saying "show me these books",
     * and the settings screen is usually left the moment it turns green
     * — long before a catalog of any size has been read. Tying the fetch
     * to that screen's lifetime is how the library ends up empty until
     * the app is restarted.
     */
    fun refreshDetached() {
        scope.launch { refresh() }
    }

    /** Pulls the catalog and folds it into the library. Safe to call often. */
    suspend fun refresh(): Boolean {
        if (!refreshing.tryLock()) return false
        try {
            val server = serverDao.get() ?: run {
                _status.value = CatalogStatus.Idle
                return false
            }
            // Everything about the request comes from the one row that
            // was read, so a sign-in landing now cannot have this send
            // the new account's secret to the old account's server.
            val credentials = server.credentials ?: run {
                _status.value = CatalogStatus.CredentialsLost
                return false
            }
            val client = router.catalogFor(server.kind) ?: run {
                _status.value = CatalogStatus.Idle
                return false
            }

            _status.value = CatalogStatus.Refreshing
            return try {
                val seen = mutableSetOf<String>()
                client.allBooks(server.baseUrl, credentials) { page ->
                    page.forEach { seen += it.remoteId }
                    forAccount(server) { store(server.kind, server.baseUrl, page) }
                }
                // Someone may have disconnected or signed in elsewhere
                // while this was in flight. Writing now would delete the
                // new account's books, or bring the old account back from
                // the dead, so the whole answer is dropped instead.
                forAccount(server) {
                    dropVanished(seen)
                    serverDao.setCatalogSyncedAt(System.currentTimeMillis())
                }
                _status.value = CatalogStatus.Idle
                true
            } catch (e: AccountChanged) {
                Log.i(TAG, "The account changed while the catalog was being read; dropping it", e)
                _status.value = CatalogStatus.Idle
                false
            } catch (e: IOException) {
                Log.i(TAG, "Could not refresh the catalog", e)
                _status.value = CatalogStatus.Offline
                false
            }
        } finally {
            refreshing.unlock()
        }
    }

    suspend fun search(query: String): List<RemoteBook> {
        val server = serverDao.get() ?: return emptyList()
        val credentials = server.credentials ?: return emptyList()
        val client = router.catalogFor(server.kind) ?: return emptyList()
        return try {
            client.search(server.baseUrl, credentials, query)
        } catch (e: IOException) {
            Log.i(TAG, "Could not search the catalog", e)
            emptyList()
        }
    }

    private suspend fun store(kind: ServerKind, baseUrl: String, books: List<RemoteBook>) {
        val now = System.currentTimeMillis()
        books.forEach { remote ->
            val url = kind.remoteUrl(remote.remoteId)
            val existing = bookDao.getByRemoteUuid(remote.remoteId) ?: bookDao.getByUrl(url)
            bookDao.upsert(mergeCatalogEntry(remote, existing, url, baseUrl, now))
        }
    }

    /**
     * Forgets books that are no longer in the catalog, unless they are on
     * the device: a book the user has downloaded stays readable even if
     * it is removed from the server.
     */
    private suspend fun dropVanished(seenUuids: Set<String>) {
        val gone = bookDao.allRemote().filter { it.remoteUuid !in seenUuids }
        // Only a book with a file of its own is worth keeping. One that
        // was queued or failed has nothing to read, so it goes with the
        // rest rather than staying as a row that can never be opened.
        val (onDevice, noFile) = gone.partition { it.localUri != null }
        if (noFile.isNotEmpty()) bookDao.deleteByUrls(noFile.map { it.url })
        // A book that is here but no longer there keeps its file and loses
        // its link: syncing it would keep asking the server about an id it
        // has forgotten, and be told no every time.
        if (onDevice.isNotEmpty()) bookDao.unlinkFromRemote(onDevice.map { it.url })
    }

    /**
     * Runs [work] only if [server] is still the connected account, with
     * the check and the work in the same transaction so that nothing can
     * sign out in between.
     */
    private suspend fun forAccount(server: RemoteServer, work: suspend () -> Unit) {
        var changed = false
        inTransaction {
            if (serverDao.get()?.accountKey != server.accountKey) {
                changed = true
            } else {
                work()
            }
        }
        if (changed) throw AccountChanged()
    }

    /** Thrown to abandon a run whose account is no longer the current one. */
    private class AccountChanged : IOException("The connected account changed")

    private companion object {
        const val TAG = "RemoteCatalog"
    }
}

/**
 * Folds a catalog entry into what is already known about a book.
 *
 * Only the fields the catalog owns are taken from the feed. Everything
 * else — whether the book has been downloaded and when, how far it was
 * read, whether it was finished — belongs to this device, and a routine
 * refresh has no business resetting it.
 */
internal fun mergeCatalogEntry(
    remote: RemoteBook,
    existing: Book?,
    url: String,
    baseUrl: String,
    now: Long,
): Book {
    val book = existing ?: Book(
        url = url,
        title = remote.title,
        author = remote.author,
        // A downloaded book has a cover extracted from the file itself;
        // until then the catalog's cover URL is what gets shown.
        coverPath = null,
        source = null,
        addedAt = now,
        lastOpenedAt = null,
        downloadState = DownloadState.REMOTE,
    )
    return book.copy(
        url = url,
        title = remote.title,
        author = remote.author,
        remoteUuid = remote.remoteId,
        remoteBookId = remote.calibreBookId,
        coverUrl = remote.coverHref?.let { RemoteUrl.resolve(baseUrl, it) },
        downloadHref = remote.downloadHref,
        remoteUpdatedAt = remote.updatedAt,
        remotePageCount = remote.pageCount,
    )
}
