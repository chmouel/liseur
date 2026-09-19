package com.chmouel.liseur.data.remote

import android.util.Log
import com.chmouel.liseur.data.NetworkAvailability
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.StarterCatalogProgress
import com.chmouel.liseur.data.db.StarterCatalogProgressDao
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    /**
     * The refresh worked, but the walk did not reach the end of the
     * catalog.
     *
     * Usually because the catalog is bigger than one pass of it: a walk
     * stops at its request budget or its depth limit. It can also be a
     * feed that points back at itself, paging a client cannot make
     * sense of, entries it cannot read, or a link out of the catalog it
     * will not follow. Either way what it did read is real: the books
     * are there and the shelf is usable.
     *
     * What is not true is that this is the whole library, and until now
     * that was reported by falling through to [Idle] — a reader pointed
     * at a catalog of tens of thousands of books got a few hundred of
     * them and no explanation (#219). The notice says only that, and
     * names no cause, because every cause looks the same from here and
     * the advice is the same for all of them.
     *
     * Not a [Failed]: nothing went wrong and there is nothing to retry.
     * The same walk from the same root reads the same pages every time,
     * so the advice is to point Liseur at a narrower shelf rather than
     * to try again.
     */
    data object Partial : CatalogStatus

    /** The stored password could not be read, so the account must be set up again. */
    data object CredentialsLost : CatalogStatus
}

/** Whether the connected starter catalog can discover more books. */
data class StarterCatalogMoreState(
    val available: Boolean = false,
    val exhausted: Boolean = false,
)

/** What the explicit starter-catalog expansion did. */
sealed interface StarterCatalogMoreResult {
    data class Added(val count: Int, val exhausted: Boolean) : StarterCatalogMoreResult
    data class Failed(val reason: SyncFailure) : StarterCatalogMoreResult
    data object NotAvailable : StarterCatalogMoreResult
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
     * How far a starter shelf has been walked. Null where there is no
     * such shelf to walk — in tests, and in any build that does not
     * offer one — and the offer of more books is withheld rather than
     * made and then unable to do anything.
     */
    private val starterProgressDao: StarterCatalogProgressDao? = null,
    /**
     * Whose account a book belongs to is only true until someone signs
     * out, so every write here checks and writes in one go. Anything
     * less and a disconnect landing mid-refresh leaves the new account
     * holding the old one's library.
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val networkAvailability: NetworkAvailability = NetworkAvailability { true },
    private val localNetwork: LocalNetworkAccess = LocalNetworkAccess.Unrestricted,
) {
    private val _status = MutableStateFlow<CatalogStatus>(CatalogStatus.Idle)
    val status: StateFlow<CatalogStatus> = _status.asStateFlow()
    private val _starterMoreLoading = MutableStateFlow(false)
    val starterMoreLoading: StateFlow<Boolean> = _starterMoreLoading.asStateFlow()

    private val _starterMoreResult = MutableStateFlow<StarterCatalogMoreResult?>(null)

    /**
     * What the last finished load-more came to, held until the reader
     * has seen it.
     *
     * A plain [SharedFlow] loses a value nobody was collecting at the
     * moment it was emitted, and the walk runs in this repository's own
     * scope precisely so it can outlive a rotation or a moment spent
     * off the library screen. Held state survives that the way the
     * event would not; [starterMoreResultShown] is how the reader marks
     * it read.
     */
    val starterMoreResult: StateFlow<StarterCatalogMoreResult?> = _starterMoreResult.asStateFlow()

    fun starterMoreResultShown() {
        _starterMoreResult.value = null
    }

    val starterMore: kotlinx.coroutines.flow.Flow<StarterCatalogMoreState> =
        serverDao.observe().flatMapLatest { server ->
            val progressDao = starterProgressDao
            if (progressDao != null &&
                server?.kind == ServerKind.CUSTOM &&
                server.shelfLimit != null &&
                server.catalogUrl != null
            ) {
                val catalogUrl = RemoteUrl.withoutTrailingSlash(server.catalogUrl)
                progressDao.observe(server.accountKey, catalogUrl).map { progress ->
                    StarterCatalogMoreState(
                        available = true,
                        exhausted = progress?.exhausted == true,
                    )
                }
            } else {
                flowOf(StarterCatalogMoreState())
            }
        }

    private val refreshing = Mutex()

    /**
     * Starts a refresh that outlives the screen that asked for it.
     *
     * Connecting an account is a way of saying "show me these books",
     * and the settings screen is usually left the moment it turns green
     * — long before a catalog of any size has been read. Tying the fetch
     * to that screen's lifetime is how the library ends up empty until
     * the app is restarted.
     *
     * Waits for a walk already in flight rather than standing down, and
     * this is the one caller that may. An account that has just changed
     * is precisely when the old account's walk is still going, and it
     * will throw its own result away on noticing — so dropping this one
     * too leaves the new account's shelf empty until somebody pulls it
     * down by hand. Every other caller has a screen behind it that can
     * see a refresh is already running.
     */
    fun refreshDetached(andThen: suspend (CatalogRefresh) -> Unit = {}) {
        scope.launch {
            // Nothing on this errand has a screen behind it to catch a
            // surprise, and an uncaught one in a detached scope takes
            // the whole app down. IO troubles are already handled inside;
            // this is for the failure nobody predicted.
            val refreshed = try {
                refreshing.withLock { refreshLocked() }
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
        return try {
            refreshLocked()
        } finally {
            refreshing.unlock()
        }
    }

    /**
     * Asks for more books in the app's own scope.
     *
     * Books are committed page by page, so a walk cut short halfway
     * through can leave the shelf ahead of the saved checkpoint and the
     * same books are read again next time. Leaving the library is not a
     * reason for that to happen, so the walk does not belong to the
     * screen that started it.
     */
    fun loadMoreDetached() {
        scope.launch {
            val result = try {
                loadMoreStarterCatalog()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Loading more starter books failed unexpectedly", e)
                StarterCatalogMoreResult.NotAvailable
            }
            _starterMoreResult.value = result
        }
    }

    /**
     * Adds another bounded slice from a starter OPDS shelf.
     *
     * Uses the refresh lock because both paths fold catalog entries into
     * the same rows. This action discovers; refreshes only keep known books
     * current.
     */
    suspend fun loadMoreStarterCatalog(limit: Int = STARTER_MORE_BATCH): StarterCatalogMoreResult {
        if (!refreshing.tryLock()) return StarterCatalogMoreResult.NotAvailable
        _starterMoreLoading.value = true
        return try {
            val server = serverDao.get() ?: return StarterCatalogMoreResult.NotAvailable
            if (server.kind != ServerKind.CUSTOM || server.shelfLimit == null) {
                return StarterCatalogMoreResult.NotAvailable
            }
            val catalogUrl = server.catalogUrl ?: return StarterCatalogMoreResult.NotAvailable
            val progressDao = starterProgressDao ?: return StarterCatalogMoreResult.NotAvailable
            val credentials = server.credentials
                ?: return StarterCatalogMoreResult.Failed(SyncFailure.Unauthorised)
            if (!networkAvailability.isAvailable()) {
                return StarterCatalogMoreResult.Failed(SyncFailure.Offline)
            }
            if (localNetwork.blocks(server.baseUrl)) {
                return StarterCatalogMoreResult.Failed(SyncFailure.LocalNetworkBlocked)
            }
            val client = router.resumableCatalogFor(server.kind)
                ?: return StarterCatalogMoreResult.NotAvailable
            val progressKey = RemoteUrl.withoutTrailingSlash(catalogUrl)
            val progress = progressDao.get(server.accountKey, progressKey)
            if (progress?.exhausted == true) {
                return StarterCatalogMoreResult.Added(0, exhausted = true)
            }
            val known = KnownBooks(bookDao.allOnce())
            val knownRemoteIds = known.remoteIds()
            val more = try {
                client.loadMore(
                    baseUrl = catalogUrl,
                    credentials = credentials,
                    state = progress?.continuation,
                    knownRemoteIds = knownRemoteIds,
                    limit = limit,
                ) { page ->
                    forAccount(server) {
                        store(
                            known = known,
                            kind = server.kind,
                            baseUrl = catalogUrl,
                            books = page,
                        )
                    }
                }
            } catch (e: RemoteHttpFailure) {
                Log.i(TAG, "The starter catalog would not load more books")
                return StarterCatalogMoreResult.Failed(e.reason)
            } catch (e: SocketTimeoutException) {
                Log.i(TAG, "The starter catalog took too long to load more books")
                return StarterCatalogMoreResult.Failed(SyncFailure.Timeout)
            } catch (e: AccountChanged) {
                // Before the general `IOException`, which it is one of.
                // An account that went away mid-walk is not an offline
                // phone, and telling the reader it was would be a lie
                // about the account they have only just connected.
                Log.i(TAG, "The account changed while loading more books")
                return StarterCatalogMoreResult.NotAvailable
            } catch (e: IOException) {
                Log.i(TAG, "Could not load more starter catalog books", e)
                return StarterCatalogMoreResult.Failed(SyncFailure.Offline)
            }
            try {
                forAccount(server) {
                    progressDao.upsert(
                        StarterCatalogProgress.of(
                            accountKey = server.accountKey,
                            catalogUrl = progressKey,
                            continuation = more.state,
                            exhausted = more.exhausted,
                        ),
                    )
                }
            } catch (e: AccountChanged) {
                // The books that were stored were stored against the
                // account that was connected at the time; the checkpoint
                // belongs to an account nobody is talking to any more.
                Log.i(TAG, "The account changed before the walk could be saved")
                return StarterCatalogMoreResult.NotAvailable
            }
            StarterCatalogMoreResult.Added(more.added, more.exhausted)
        } finally {
            _starterMoreLoading.value = false
            refreshing.unlock()
        }
    }

    /** The refresh itself, with the turn already taken. */
    private suspend fun refreshLocked(): CatalogRefresh {
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
            // A connection with no catalog is not a broken one. A
            // Custom server may be nothing but a KOReader sync address,
            // and there is simply nothing here to pull; saying so
            // quietly is the difference between that and a failure the
            // reader is asked to do something about.
            val catalogUrl = server.catalogUrl ?: run {
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
            // Same reasoning, one rung further in: a server on a network
            // the phone is blocking swallows the connection rather than
            // refusing it, so a pull-to-refresh that is not stopped here
            // is fifteen seconds of spinner and a puzzling timeout.
            if (localNetwork.blocks(server.baseUrl)) {
                _status.value = CatalogStatus.Failed(SyncFailure.LocalNetworkBlocked)
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
                val deferredLegacy = mutableListOf<RemoteBook>()
                val walk = client.allBooks(catalogUrl, credentials) { page ->
                    page.forEach { seen += it.remoteId }
                    forAccount(server) {
                        store(
                            known = known,
                            kind = server.kind,
                            baseUrl = catalogUrl,
                            books = page,
                            deferLegacy = server.kind == ServerKind.CUSTOM,
                            deferredLegacy = deferredLegacy,
                        )
                    }
                }
                if (walk.complete && deferredLegacy.isNotEmpty()) {
                    val distinctDeferredLegacy = deferredLegacy.distinctBy { it.remoteId }
                    val uniqueLegacyKeys = distinctDeferredLegacy
                        .groupingBy { it.title to it.author }
                        .eachCount()
                        .filterValues { it == 1 }
                        .keys
                    forAccount(server) {
                        store(
                            known = known,
                            kind = server.kind,
                            baseUrl = catalogUrl,
                            books = distinctDeferredLegacy,
                            legacyKeys = uniqueLegacyKeys,
                        )
                    }
                }
                if (!walk.complete) {
                    // A walk that stopped short has not seen the whole
                    // library, so nothing may be removed for being absent
                    // from it and nothing may be told this is current.
                    //
                    // There used to be an exception for a walk that
                    // stopped only because this app's own shelf size
                    // was reached: the shelf had been seen whole, so a
                    // book that had dropped off it could go. A starter
                    // shelf can now be asked for more books, which
                    // makes it something the reader grows rather than a
                    // rolling top N, and letting go of a book because
                    // the popularity order moved would quietly undo
                    // that. Nothing is reconciled from a short walk.
                    //
                    // Through the account guard like every other write
                    // here, because the status is about a server: one
                    // signed out of while this was in flight must not
                    // leave its notice over the next account's shelf.
                    Log.i(TAG, "The catalog walk did not finish; keeping what is known")
                    forAccount(server) {
                        _status.value = CatalogStatus.Partial
                    }
                    return CatalogRefresh.None
                }
                // Someone may have disconnected or signed in elsewhere
                // while this was in flight. Writing now would delete the
                // new account's books, or bring the old account back from
                // the dead, so the whole answer is dropped instead.
                forAccount(server) {
                    if (server.shelfLimit == null) reconcileVanished(seen)
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
        }
    }

    suspend fun search(query: String): List<RemoteBook> {
        if (!networkAvailability.isAvailable()) return emptyList()
        val server = serverDao.get() ?: return emptyList()
        val credentials = server.credentials ?: return emptyList()
        val client = router.catalogFor(server.kind) ?: return emptyList()
        // Nothing to search when there is no catalog. See `refresh`.
        val catalogUrl = server.catalogUrl ?: return emptyList()
        return try {
            client.search(catalogUrl, credentials, query)
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
        deferLegacy: Boolean = false,
        deferredLegacy: MutableList<RemoteBook>? = null,
        legacyKeys: Set<Pair<String, String?>> = emptySet(),
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
            .filter { known.findExact(it, kind.remoteUrl(it)) == null }
        if (unknown.isNotEmpty()) bookDao.byRemoteUuids(unknown).forEach(known::remember)
        // Keyed by URL so a feed that names the same book twice on one
        // page folds into one insert, rather than two rows racing for
        // the same unique URL and being refused.
        val inserts = LinkedHashMap<String, Book>()
        val updates = mutableListOf<CatalogUpdate>()
        books.forEach { remote ->
            val url = kind.remoteUrl(remote.remoteId)
            if (
                deferLegacy &&
                    deferredLegacy != null &&
                    known.hasLegacyCandidate(remote.remoteId, url, remote.title, remote.author)
            ) {
                deferredLegacy += remote
                return@forEach
            }
            // A book first seen earlier in this walk was written without
            // its generated id coming back, so ask the database for it.
            // One seen earlier on this same page has not landed yet, and
            // the pending row itself is the answer.
            val existing = known.find(
                remote.remoteId,
                url,
                remote.title,
                remote.author,
                (remote.title to remote.author) in legacyKeys,
            )
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
     * Decides what a finished walk means for the books it did not name.
     *
     * Not by removing them: a book absent from one walk is only
     * suspected of having gone. Every catalog here is read a page at a
     * time by offset, and a catalog edited between two of those requests
     * shifts under the offset, so a book still on the server is never
     * sent — while every check on the envelope passes, because a
     * deletion and an addition together leave the declared count exactly
     * where it was. Believing a single absence is how the reader loses a
     * book, and with it their place, their sittings and their history.
     *
     * So absence must be seen by two finished walks running. The first
     * marks; the second acts, and does what this always did — a book
     * with no file goes, one on the device keeps its file and loses its
     * link. Being named again at any point clears the mark, including by
     * a walk that never finished, which is done as the page is stored.
     *
     * The cost is that a book genuinely deleted on the server lingers
     * until the refresh after next. That is the right way round: an
     * extra refresh showing a book that has gone is a moment's
     * confusion, and the alternative is unrecoverable.
     */
    private suspend fun reconcileVanished(seenUuids: Set<String>) {
        // Only rows the catalog itself introduced are the catalog's to
        // forget. A book of the reader's own that was uploaded and
        // linked is not one: its file is its URL rather than a
        // download, so the test below would read it as having nothing
        // to open, and a walk that began before the upload cannot have
        // seen its id. Between them they would delete the book and
        // every page ever read of it.
        val remote = bookDao.allRemote()
        val gone = remote
            .filter { it.remoteUuid !in seenUuids && ServerKind.isRemoteUrl(it.url) }
        val (confirmed, suspected) = gone.partition { it.catalogMissingSince != null }
        // Only a book with a file of its own is worth keeping. One that
        // was queued or failed has nothing to read, so it goes with the
        // rest rather than staying as a row that can never be opened.
        val (onDevice, noFile) = confirmed.partition { it.localUri != null }
        noFile.map { it.url }.chunkedForSql { bookRemoval.deleteByUrls(it) }
        // A book that is here but no longer there keeps its file and loses
        // its link: syncing it would keep asking the server about an id it
        // has forgotten, and be told no every time.
        onDevice.map { it.url }.chunkedForSql { bookDao.unlinkFromRemote(it) }
        val now = System.currentTimeMillis()
        suspected.map { it.url }.chunkedForSql { bookDao.markCatalogMissing(it, now) }
        // Storing a page already clears the mark on everything it names,
        // so this ought to find nothing. It stays because the invariant
        // it keeps -- named means unmarked -- is what stops a single
        // absence being fatal, and it should not depend on one write in
        // another file continuing to be unconditional.
        remote
            .filter { it.remoteUuid in seenUuids && it.catalogMissingSince != null }
            .map { it.url }
            .chunkedForSql { bookDao.clearCatalogMissing(it) }
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
        const val STARTER_MORE_BATCH = 50
    }
}

/**
 * How many URLs to name in one statement.
 *
 * `IN (:urls)` binds a variable per book and SQLite refuses more than
 * 999 of them before API 31. A whole library can vanish from a catalog
 * at once — a misconfigured server, a library unshared — and the throw
 * would land in the middle of the reconciliation.
 */
private const val URL_CHUNK = 500

/** Runs [write] over the URLs in batches SQLite will accept, and not at all for none. */
private suspend fun List<String>.chunkedForSql(write: suspend (List<String>) -> Unit) {
    if (isEmpty()) return
    chunked(URL_CHUNK).forEach { write(it) }
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
    private val legacyGrimmory = books
        .filter {
            it.url.startsWith("grimmory:") &&
                it.remoteUuid != null &&
                it.openableUrl != null
        }
        .groupBy { it.title to it.author }
        .filterValues { it.size == 1 }
        .mapValuesTo(mutableMapOf()) { it.value.single() }

    fun findExact(remoteId: String, url: String): Book? = byUuid[remoteId] ?: byUrl[url]

    /** Which remote books are already here, so a walk can skip them. */
    fun remoteIds(): Set<String> = byUuid.keys.toSet()

    fun hasLegacyCandidate(remoteId: String, url: String, title: String, author: String?): Boolean =
        findExact(remoteId, url) == null &&
            url.startsWith("custom:") &&
            legacyGrimmory.containsKey(title to author)

    fun find(
        remoteId: String,
        url: String,
        title: String,
        author: String?,
        allowLegacy: Boolean,
    ): Book? =
        findExact(remoteId, url)
            ?: allowLegacy.takeIf { it }
                ?.let {
                    url.takeIf { it.startsWith("custom:") }
                }
                ?.let { legacyGrimmory.remove(title to author) }

    fun remember(book: Book) {
        book.remoteUuid?.let { byUuid[it] = book }
        byUrl[book.url] = book
        legacyGrimmory.remove(book.title to book.author)
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
        // A link out of a catalog Liseur has a client for is rebuilt
        // onto the address that answered, because a self-hosted server
        // behind a proxy advertises a host the phone cannot reach. A
        // plain OPDS catalog gets the opposite treatment: its links
        // were made absolute against the document they came out of, and
        // re-rooting one would point a cover on another host at this
        // one. See `ServerKind.linksAreAbsolute`.
        coverUrl = remote.coverHref?.let {
            if (kind.linksAreAbsolute) it else RemoteUrl.resolve(baseUrl, it)
        },
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
        // The catalog is naming this book, so whatever an earlier walk
        // failed to find, it was not this. Clearing it here rather than
        // only at the end of a walk is deliberate: an unfinished walk
        // still stores its pages, and a book it saw must not be left one
        // absence away from deletion. It also makes a row that differs
        // by nothing else fail the "unchanged, skip it" test in `store`,
        // which is what carries the clear as far as the database.
        catalogMissingSince = null,
    )
}
