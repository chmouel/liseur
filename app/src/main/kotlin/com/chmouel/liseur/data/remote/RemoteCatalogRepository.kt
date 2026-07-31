package com.chmouel.liseur.data.remote

import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import java.io.IOException
import java.net.SocketTimeoutException
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

    /**
     * The last refresh did not finish, and why.
     *
     * Carrying the reason rather than a bare "offline" is the difference
     * between telling someone their wifi is down and telling them their
     * password was refused. Both used to come out as the former, because
     * the failure a rejected request throws is an [java.io.IOException]
     * like any other.
     */
    data class Failed(val reason: SyncFailure) : CatalogStatus

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
    suspend fun refresh(): CatalogRefresh {
        if (!refreshing.tryLock()) return CatalogRefresh.None
        try {
            val server = serverDao.get() ?: run {
                _status.value = CatalogStatus.Idle
                return CatalogRefresh.None
            }
            // Everything about the request comes from the one row that
            // was read, so a sign-in landing now cannot have this send
            // the new account's secret to the old account's server.
            val credentials = server.credentials ?: run {
                _status.value = CatalogStatus.CredentialsLost
                return CatalogRefresh.None
            }
            val client = router.catalogFor(server.kind) ?: run {
                _status.value = CatalogStatus.Idle
                return CatalogRefresh.None
            }

            _status.value = CatalogStatus.Refreshing
            return try {
                val seen = mutableSetOf<String>()
                // What is already known, read once. Doing it per book was
                // a query each against an unindexed column, so the cost of
                // folding a catalog in grew with the square of the shelf.
                val known = KnownBooks(bookDao.allOnce())
                val walk = client.allBooks(server.baseUrl, credentials) { page ->
                    page.forEach { seen += it.remoteId }
                    forAccount(server) {
                        store(known, server.kind, server.baseUrl, page)
                    }
                }
                if (!walk.complete) {
                    // A walk that stopped short has not seen the whole
                    // library, so nothing may be removed for being absent
                    // from it and nothing may be told this is current.
                    Log.i(TAG, "The catalog walk did not finish; keeping what is known")
                    _status.value = CatalogStatus.Idle
                    return CatalogRefresh.None
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
                CatalogRefresh(
                    completed = true,
                    accountKey = server.accountKey,
                    snapshot = walk.snapshot,
                )
            } catch (e: AccountChanged) {
                Log.i(TAG, "The account changed while the catalog was being read; dropping it", e)
                _status.value = CatalogStatus.Idle
                CatalogRefresh.None
            } catch (e: RemoteHttpFailure) {
                // Already carries its meaning: a refused sign-in, a
                // server in trouble, an answer that was not a catalog.
                Log.i(TAG, "The server would not give up its catalog")
                _status.value = CatalogStatus.Failed(e.reason)
                CatalogRefresh.None
            } catch (e: SocketTimeoutException) {
                // Before the catch below, because this is one too. A
                // server that is answering slowly is not a server that
                // cannot be reached, and waiting is the right advice
                // where checking the address is not.
                Log.i(TAG, "The catalog took too long to arrive")
                _status.value = CatalogStatus.Failed(SyncFailure.Timeout)
                CatalogRefresh.None
            } catch (e: IOException) {
                Log.i(TAG, "Could not refresh the catalog", e)
                _status.value = CatalogStatus.Failed(SyncFailure.Offline)
                CatalogRefresh.None
            }
        } finally {
            // Cancellation and anything unforeseen both arrive here
            // still marked as refreshing. A spinner that never stops is
            // worse than any wrong message, so nothing leaves without a
            // settled answer. Idle rather than a failure: by far the
            // usual way to land here is the screen going away, which is
            // nobody's fault and should not accuse the server of
            // anything. A genuine surprise still travels on unswallowed.
            if (_status.value is CatalogStatus.Refreshing) {
                _status.value = CatalogStatus.Idle
            }
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

    private suspend fun store(
        known: KnownBooks,
        kind: ServerKind,
        baseUrl: String,
        books: List<RemoteBook>,
    ) {
        val now = System.currentTimeMillis()
        val changed = mutableListOf<Book>()
        books.forEach { remote ->
            val url = kind.remoteUrl(remote.remoteId)
            // A book first seen earlier in this same walk was written
            // without its generated id coming back, so ask for it rather
            // than upsert a row that would insert a second time and be
            // refused by the unique URL.
            val existing = known.find(remote.remoteId, url)
                ?.let { if (it.id == 0L) bookDao.getByUrl(url) else it }
            val merged = mergeCatalogEntry(remote, existing, url, baseUrl, now)
            known.remember(merged)
            // Nothing the catalog owns has moved, so writing the row back
            // would only tell the library to sort itself again.
            if (merged != existing) changed += merged
        }
        if (changed.isNotEmpty()) bookDao.upsertAll(changed)
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
 * What a catalog refresh left behind.
 *
 * [completed] is what everything else hangs on: only a walk that reached
 * the end of the catalog has seen the whole library, and only such a
 * walk may be reused in place of asking the server again.
 */
data class CatalogRefresh(
    val completed: Boolean,
    val accountKey: String? = null,
    val snapshot: CatalogSnapshot? = null,
) {
    /**
     * The walk offered to a position sync, or null when there is nothing
     * worth offering. Refusing one from another account is the point:
     * reading progress belongs to whoever was signed in when it was read.
     */
    fun forSync(): SyncSnapshot? {
        if (!completed) return null
        val account = accountKey ?: return null
        return snapshot?.let { SyncSnapshot(account, it) }
    }

    companion object {
        /** Nothing was read, so nothing may be concluded from it. */
        val None = CatalogRefresh(completed = false)
    }
}

/**
 * The library as it stood when a refresh began, by both of the names a
 * catalog entry can be recognised by.
 *
 * Every book is here, not only the ones linked to a server: a downloaded
 * book that was unlinked when it vanished from the catalog has no UUID
 * left, and if it comes back it must attach to the row it already has
 * rather than collide with it over the URL.
 */
private class KnownBooks(books: List<Book>) {
    private val byUuid = books.mapNotNull { book -> book.remoteUuid?.let { it to book } }.toMap()
        .toMutableMap()
    private val byUrl = books.associateBy { it.url }.toMutableMap()

    fun find(remoteId: String, url: String): Book? = byUuid[remoteId] ?: byUrl[url]

    fun remember(book: Book) {
        book.remoteUuid?.let { byUuid[it] = book }
        byUrl[book.url] = book
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
