package com.chmouel.liseur.data.remote

import android.util.Log
import com.chmouel.liseur.data.NetworkAvailability
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.domain.SeriesMetadata
import com.chmouel.liseur.domain.SeriesOverride
import com.chmouel.liseur.domain.effectiveSeries
import com.chmouel.liseur.domain.mergeSeries
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
    private val bookRemoval: BookRemoval,
    /**
     * Whose account a book belongs to is only true until someone signs
     * out, so every write here checks and writes in one go. Anything
     * less and a disconnect landing mid-refresh leaves the new account
     * holding the old one's library.
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val networkAvailability: NetworkAvailability = NetworkAvailability { true },
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
    fun refreshDetached(andThen: suspend (CatalogRefresh) -> Unit = {}) {
        scope.launch {
            // Nothing on this errand has a screen behind it to catch a
            // surprise, and an uncaught one in a detached scope takes
            // the whole app down. IO troubles are already handled inside;
            // this is for the failure nobody predicted.
            val refreshed = try {
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "The catalog refresh failed unexpectedly", e)
                CatalogRefresh.None
            }
            // Whatever follows a refresh -- reconciling where the new
            // account's books were read -- belongs to the same errand and
            // must outlive the screen just as the refresh itself does.
            runCatching { andThen(refreshed) }
        }
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
            // Said rather than attempted: the pull would end here anyway,
            // several stalled connections later, and a refresh that
            // reports nothing looks like a gesture that did not register.
            if (!networkAvailability.isAvailable()) {
                _status.value = CatalogStatus.Failed(SyncFailure.Offline)
                return CatalogRefresh.None
            }

            _status.value = CatalogStatus.Refreshing
            return try {
                // Before the catalog is read, not after: a claim made offline has to
                // reach the server first, or the pull it races would merge against a
                // personal layer the server has never been told about.
                retryPendingSeriesClaims(server, credentials)
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
        if (!networkAvailability.isAvailable()) return emptyList()
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

    /** Tries each durable local claim once; failures stay on the row for the next refresh. */
    suspend fun retryPendingSeriesClaims() {
        val server = serverDao.get()
        if (server == null) {
            // Nobody left to answer a claim raised before signing out.
            bookDao.discardPendingSeriesClaims()
            return
        }
        if (!networkAvailability.isAvailable()) return
        val credentials = server.credentials ?: return
        retryPendingSeriesClaims(server, credentials)
    }

    private suspend fun retryPendingSeriesClaims(server: RemoteServer, credentials: RemoteCredentials) {
        if (server.kind != ServerKind.LISEUR_SYNC || !server.canManageLibrary) {
            // Komga and calibre-web never speak this protocol, so a claim raised
            // against either would sit pending forever otherwise, freezing the
            // row's series fields at whatever they were when it was made.
            bookDao.discardPendingSeriesClaims()
            return
        }
        val claims = router.seriesClaimsFor(server.kind) ?: return
        // Snapshot first. A response may update the row and must not change this pass's work list.
        bookDao.pendingSeriesClaims().forEach { book ->
            val timestamp = book.userSeriesUpdatedAt ?: return@forEach
            try {
                val layers = if (book.seriesClaimReset) {
                    claims.resetPersonalSeries(server.baseUrl, credentials, book)
                } else {
                    // A book-level claim writes the same personal layer the shelf-order
                    // route writes, so it must carry the position too. Sending none would
                    // renumber a shelf that was dragged into order moments earlier.
                    val name = if (book.seriesOverridden) book.userSeriesName else book.seriesName
                    // Nothing to claim, and no name to hang a claim on. Pushing here would
                    // turn "this book has no series yet" into "this reader says it has none".
                    if (name == null && !book.seriesOverridden) return@forEach
                    val index = if (book.indexOverridden) book.userSeriesIndex else null
                    claims.setPersonalSeries(server.baseUrl, credentials, book, name, index)
                } ?: return@forEach
                // The response answers this account's request; a sign-out or
                // account switch that landed during it must not let it write.
                forAccount(server) {
                    when (layers.outcome) {
                        "stale" -> bookDao.updatePendingSeriesClaimRevision(
                            book.url, timestamp, layers.personalUpdatedAt,
                        )
                        "applied", "duplicate", null -> bookDao.acknowledgeSeriesClaim(
                            url = book.url,
                            expectedUserSeriesUpdatedAt = timestamp,
                            seriesId = layers.personal?.firstOrNull()?.id,
                            personalSeriesUpdatedAt = layers.personalUpdatedAt,
                        )
                    }
                }
            } catch (e: AccountChanged) {
                Log.i(TAG, "The account changed while a series claim was in flight; stopping the retry pass", e)
                return
            } catch (e: IOException) {
                Log.i(TAG, "Could not push the series claim", e)
            }
        }
    }

    private suspend fun store(
        known: KnownBooks,
        kind: ServerKind,
        baseUrl: String,
        books: List<RemoteBook>,
    ) {
        val now = System.currentTimeMillis()
        // What is known was read once before the walk began, so a book
        // this device uploaded and linked while the walk was in flight
        // is missing from it — and the catalog would introduce it all
        // over again as a second row holding none of the reading. One
        // query a page, on the ids this page actually names, is what
        // keeps an upload that raced the catalog from doubling. Books
        // the snapshot already covers are left alone: their snapshot is
        // what the series write below checks itself against.
        val unknown = books.map { it.remoteId }
            .filter { known.find(it, kind.remoteUrl(it)) == null }
        if (unknown.isNotEmpty()) bookDao.byRemoteUuids(unknown).forEach(known::remember)
        // Keyed by URL so a feed that names the same book twice on one
        // page folds into one insert, rather than two rows racing for
        // the same unique URL and being refused.
        val inserts = LinkedHashMap<String, Book>()
        val updates = mutableListOf<CatalogUpdate>()
        books.forEach { remote ->
            val url = kind.remoteUrl(remote.remoteId)
            // A book first seen earlier in this walk was written without
            // its generated id coming back, so ask the database for it.
            // One seen earlier on this same page has not landed yet, and
            // the pending row itself is the answer.
            val existing = known.find(remote.remoteId, url)
                ?.let { pending ->
                    if (pending.id == 0L) bookDao.getByUrl(url) ?: pending else pending
                }
            // A row matched on its remote id alone — a local book that
            // was uploaded and linked — keeps its own URL, which is what
            // reading positions hang off; the catalog's spelling of the
            // identity belongs to rows the catalog itself introduced.
            val rowUrl = existing?.takeIf { it.id != 0L }?.url ?: url
            val merged = mergeCatalogEntry(remote, existing, rowUrl, baseUrl, now, kind)
            known.remember(merged)
            // Nothing the catalog owns has moved, so writing the row back
            // would only tell the library to sort itself again.
            if (merged == existing) return@forEach
            if (merged.id == 0L) {
                inserts[rowUrl] = merged
            } else {
                updates += CatalogUpdate(
                    book = merged,
                    remote = remote,
                    snapshotUserSeriesUpdatedAt = existing?.userSeriesUpdatedAt,
                )
            }
        }
        // Rows the library already has get a narrow update: catalog
        // columns always, and liseur-sync's effective personal-series
        // fields only while the snapshot is still current. What is known
        // was read once before the walk, so a full-row write would put
        // back any local change that happened while the network waited.
        updates.forEach { update ->
            val book = update.book
            val remote = update.remote
            bookDao.updateCatalogFields(
                url = book.url,
                title = book.title,
                author = book.author,
                remoteUuid = book.remoteUuid,
                remoteBookId = book.remoteBookId,
                coverUrl = book.coverUrl,
                downloadHref = book.downloadHref,
                remoteUpdatedAt = book.remoteUpdatedAt,
                remotePageCount = book.remotePageCount,
                catalogSeriesName = remote.seriesName,
                catalogSeriesIndex = remote.seriesIndex,
                catalogFolderId = remote.folderId,
                catalogSeriesSource = remote.seriesSource,
                userSeriesName = book.userSeriesName,
                userSeriesIndex = book.userSeriesIndex,
                seriesOverridden = book.seriesOverridden,
                indexOverridden = book.indexOverridden,
                userSeriesUpdatedAt = book.userSeriesUpdatedAt,
                expectedUserSeriesUpdatedAt = update.snapshotUserSeriesUpdatedAt,
                seriesId = book.seriesId,
                personalSeriesUpdatedAt = book.personalSeriesUpdatedAt,
                sizeBytes = book.sizeBytes,
            )
        }
        if (inserts.isNotEmpty()) bookDao.upsertAll(inserts.values.toList())
    }

    /**
     * Forgets books that are no longer in the catalog, unless they are on
     * the device: a book the user has downloaded stays readable even if
     * it is removed from the server.
     */
    private suspend fun dropVanished(seenUuids: Set<String>) {
        // Only rows the catalog itself introduced are the catalog's to
        // forget. A book of the reader's own that was uploaded and
        // linked is not one: its file is its URL rather than a
        // download, so the test below would read it as having nothing
        // to open, and a walk that began before the upload cannot have
        // seen its id. Between them they would delete the book and
        // every page ever read of it.
        val gone = bookDao.allRemote()
            .filter { it.remoteUuid !in seenUuids && ServerKind.isRemoteUrl(it.url) }
        // Only a book with a file of its own is worth keeping. One that
        // was queued or failed has nothing to read, so it goes with the
        // rest rather than staying as a row that can never be opened.
        val (onDevice, noFile) = gone.partition { it.localUri != null }
        if (noFile.isNotEmpty()) bookRemoval.deleteByUrls(noFile.map { it.url })
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
 * A catalog row ready to write, tied to the local series revision from
 * which it was calculated. Keeping the snapshot value named prevents a
 * future cleanup from accidentally comparing the database with the new
 * value and turning the optimistic-lock check into a tautology.
 */
private data class CatalogUpdate(
    val book: Book,
    val remote: RemoteBook,
    val snapshotUserSeriesUpdatedAt: Long?,
)

/**
 * Folds a catalog entry into what is already known about a book.
 *
 * The catalog's fields and liseur-sync's effective personal-series
 * claim are taken from the feed. Everything else — whether the book has
 * been downloaded and when, how far it was read, whether it was
 * finished — belongs to this device, and a routine refresh has no
 * business resetting it. The DAO still checks that the personal-series
 * snapshot is current before writing that part.
 */
internal fun mergeCatalogEntry(
    remote: RemoteBook,
    existing: Book?,
    url: String,
    baseUrl: String,
    now: Long,
    kind: ServerKind = ServerKind.KOMGA,
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
    // What the server says wins, and what it leaves out is filled in by
    // whatever the file itself said when it was indexed. A feed that
    // names a series without numbering it should not throw away a number
    // the EPUB was carrying all along.
    val series = mergeSeries(
        catalog = SeriesMetadata(remote.seriesName, remote.seriesIndex, remote.seriesId),
        file = SeriesMetadata(book.fileSeriesName, book.fileSeriesIndex),
    )
    // The catalog book timestamp is unrelated to the personal claim and may
    // come from another clock. Only its own revision decides what may be adopted.
    //
    // A book this device has already had acknowledged counts too, even when the
    // catalog now reports no claim at all: that is a withdrawal made on another
    // device, and reading it as "no news" would leave this shelf alone forever.
    val serverPersonalWins = kind == ServerKind.LISEUR_SYNC && !book.seriesClaimPending &&
        (remote.seriesClaimUpdatedAt != null || book.personalSeriesUpdatedAt != null)
    val personalMembership = remote.series.firstOrNull { it.source == "personal" }
    val userSeriesName = when {
        serverPersonalWins && remote.seriesSource == "personal" -> personalMembership?.name
        serverPersonalWins -> null
        else -> book.userSeriesName
    }
    val userSeriesIndex = when {
        serverPersonalWins && remote.seriesSource == "personal" -> personalMembership?.position
        serverPersonalWins -> null
        else -> book.userSeriesIndex
    }
    val seriesOverridden = when {
        serverPersonalWins -> remote.seriesSource == "personal"
        else -> book.seriesOverridden
    }
    val indexOverridden = when {
        serverPersonalWins -> remote.seriesSource == "personal"
        else -> book.indexOverridden
    }
    val userSeriesUpdatedAt = when {
        serverPersonalWins && remote.seriesSource == "personal" -> remote.seriesClaimUpdatedAt
        serverPersonalWins -> null
        else -> book.userSeriesUpdatedAt
    }
    // Work out the effective shelf from the refresh snapshot. The DAO
    // applies it only if that snapshot's local-series timestamp still
    // matches; otherwise the concurrent local edit remains effective.
    val filed = effectiveSeries(
        name = if (seriesOverridden) {
            SeriesOverride(userSeriesName, userSeriesIndex)
        } else {
            null
        },
        index = userSeriesIndex,
        indexOverridden = indexOverridden,
        source = series,
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
        // A catalog page that leaves the size out should not erase a
        // figure an earlier one gave: only calibre-web's paged feed
        // carries it consistently, and losing it silently would make
        // the bulk-download estimate worse over time, not better.
        sizeBytes = remote.sizeBytes ?: book.sizeBytes,
        seriesName = filed.name,
        seriesIndex = filed.index,
        // A shelf id is what the reorder route speaks through, so losing one
        // costs the reader the ability to drag that shelf. Keep the id already
        // stored when this refresh carries no membership to replace it with.
        seriesId = if (seriesOverridden) {
            personalMembership?.id ?: book.seriesId
        } else {
            series.id
        },
        catalogSeriesName = remote.seriesName,
        catalogSeriesIndex = remote.seriesIndex,
        catalogFolderId = remote.folderId,
        catalogSeriesSource = remote.seriesSource,
        userSeriesName = userSeriesName,
        userSeriesIndex = userSeriesIndex,
        seriesOverridden = seriesOverridden,
        indexOverridden = indexOverridden,
        userSeriesUpdatedAt = userSeriesUpdatedAt,
        // Verbatim when the server had the say, so that a withdrawal on another
        // device clears the revision instead of leaving one that keeps claiming
        // the personal layer still exists. A pending edit keeps its own.
        personalSeriesUpdatedAt = if (serverPersonalWins) {
            remote.seriesClaimUpdatedAt
        } else {
            book.personalSeriesUpdatedAt
        },
    )
}
