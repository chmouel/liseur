package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.bookorbit.BookOrbitCatalogClient
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.calibre.CalibreUrl
import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.BookOrbitBindingDao
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.liseursync.LiseurSyncCatalogClient
import com.chmouel.liseur.data.opds.GutenbergBrowse
import com.chmouel.liseur.data.opds.OpdsCatalogClient
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BrowseCategory(val id: String, val title: String)

data class BrowseListing(
    val categories: List<BrowseCategory> = emptyList(),
    val books: List<RemoteBook> = emptyList(),
    val complete: Boolean = true,
    /** Where the rest of this section continues, when only its first pages were read. */
    val nextPage: String? = null,
    /** How many requests reading this listing took. */
    val requests: Int = 0,
)

/** One full read of a non-OPDS catalog: its books and, for liseur-sync, its named folders. */
internal data class BrowseWalk(
    val books: List<RemoteBook>,
    val complete: Boolean,
    val folders: Map<String, BrowseCategory> = emptyMap(),
)

/**
 * The last catalog walked, so moving between its categories does not
 * read the whole catalog again. Keyed by the connection's identity: a
 * reconnected or re-keyed server walks afresh.
 */
internal class BrowseWalkCache {
    data class Key(val serverId: Long, val addedAt: Long, val accountKey: String)

    private val lock = Mutex()
    private var entry: Pair<Key, BrowseWalk>? = null
    private var generation = 0L
    private var requested: Key? = null

    suspend fun get(key: Key, fresh: Boolean, walk: suspend () -> BrowseWalk): BrowseWalk {
        val request = lock.withLock {
            entry?.takeIf { !fresh && it.first == key }?.second?.let { return it }
            requested = key
            ++generation
        }
        val result = walk()
        lock.withLock { if (generation == request) entry = key to result }
        return result
    }

    suspend fun forget(serverId: Long) = lock.withLock {
        if (requested?.serverId == serverId) {
            generation++
            requested = null
        }
        if (entry?.first?.serverId == serverId) entry = null
    }
}

/** Keep Coil's URL based cover cache separate for each saved connection. */
internal fun browseCoverUrl(url: String, server: RemoteServer): String {
    val separator = if ('#' in url) '&' else '#'
    return "$url${separator}liseur-browse=${browseCoverScope(server)}"
}

/** [url], retagged for [server] after a book moves to it from another saved connection. */
internal fun rescopedBrowseCoverUrl(url: String, server: RemoteServer): String =
    browseCoverUrl(url.replace(BROWSE_COVER_TAG, ""), server)

private val BROWSE_COVER_TAG = Regex("[#&]liseur-browse=[0-9a-f]*$")

internal fun browseCoverScope(server: RemoteServer): String {
    val identity = "${server.id}|${server.addedAt}|${server.accountKey}"
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return digest
}

internal fun BrowseCoverSource.matches(server: RemoteServer): Boolean =
    serverId == server.id && scope != null && scope == browseCoverScope(server)

data class BrowseConnectionResult(val id: Long? = null, val failure: SetupFailure? = null)

enum class BrowseOwnedState { QUEUED, DOWNLOADING, IN_LIBRARY, FAILED }

/** What the library already holds for one catalog book, and how far its download has got. */
data class BrowseOwned(val state: BrowseOwnedState, val fraction: Float? = null)

internal fun browseOwned(book: Book, progress: DownloadProgress?): BrowseOwned = when {
    progress?.queued == true -> BrowseOwned(BrowseOwnedState.QUEUED)
    progress != null -> BrowseOwned(BrowseOwnedState.DOWNLOADING, progress.fraction)
    book.localUri != null || book.downloadState == DownloadState.DOWNLOADED -> BrowseOwned(BrowseOwnedState.IN_LIBRARY)
    book.downloadState == DownloadState.QUEUED -> BrowseOwned(BrowseOwnedState.QUEUED)
    book.downloadState == DownloadState.DOWNLOADING -> BrowseOwned(BrowseOwnedState.DOWNLOADING)
    book.downloadState == DownloadState.FAILED -> BrowseOwned(BrowseOwnedState.FAILED)
    else -> BrowseOwned(BrowseOwnedState.IN_LIBRARY)
}

/** [browse] and every other saved catalog signed in to the same account. */
internal fun sameAccount(browse: RemoteServer, saved: List<RemoteServer>): Set<Long> =
    saved.filter { it.kind == browse.kind && it.accountKey == browse.accountKey }
        .mapTo(mutableSetOf(browse.id), RemoteServer::id)

/** Another saved catalog signed in to [removed]'s account, to keep its downloaded books. */
internal fun survivingSibling(removed: RemoteServer, saved: List<RemoteServer>): RemoteServer? =
    saved.firstOrNull { it.id != removed.id && it.kind == removed.kind && it.accountKey == removed.accountKey }

/**
 * Whether the library already holds this book from [browse]'s account: from [browse] itself,
 * from a same-account catalog in [siblings] (see [sameAccount]), or from the main connection.
 */
internal fun Book.belongsToBrowse(browse: RemoteServer, main: RemoteServer?, siblings: Set<Long>): Boolean =
    browseServerId in siblings ||
        (browseServerId == null && remoteUuid != null && main?.kind == browse.kind &&
            main.accountKey == browse.accountKey)

/** Saved catalog connections keep separate credentials and catalog walks. */
class BrowseCatalogRepository(
    private val servers: RemoteServerDao,
    private val books: BookDao,
    private val orbitBindings: BookOrbitBindingDao,
    private val removal: BookRemoval,
    private val downloads: BookDownloadRepository,
    private val router: RemoteRouter,
    private val setups: Map<ServerKind, ServerSetup>,
    private val orbitCatalog: (Long) -> BookOrbitCatalogClient,
    private val closeOrbit: suspend (RemoteServer) -> Unit = {},
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
) {
    private val changing = Mutex()
    private val walks = BrowseWalkCache()
    val saved: Flow<List<RemoteServer>> = servers.observeBrowseServers()

    suspend fun addedIds(serverId: Long): Set<String> {
        val browse = servers.get(serverId) ?: return emptySet()
        val main = servers.get()
        val siblings = sameAccount(browse, servers.browseServers())
        return books.allOnce().filter { it.belongsToBrowse(browse, main, siblings) }
            .mapNotNull { it.remoteUuid }.toSet()
    }

    /** Books from this catalog already in the library, keyed by remote id, with live download state. */
    fun observeOwned(serverId: Long): Flow<Map<String, BrowseOwned>> =
        combine(
            books.observeAll(),
            downloads.progress,
            saved,
            servers.observe(),
        ) { allBooks, progress, catalogs, main ->
            val browse = catalogs.firstOrNull { it.id == serverId }
            val siblings = browse?.let { sameAccount(it, catalogs) }.orEmpty()
            allBooks.filter { browse != null && it.belongsToBrowse(browse, main, siblings) }
                .mapNotNull { book ->
                    book.remoteUuid?.let { it to browseOwned(book, progress[book.url]) }
                }.toMap()
        }

    /**
     * Which of [remoteIds] the library already holds from [server]'s account: through the main
     * connection, this catalog, or another saved catalog signed in to the same account.
     */
    private suspend fun heldRemoteIds(server: RemoteServer, remoteIds: List<String>): Set<String> {
        val main = servers.get()
        val siblings = sameAccount(server, servers.browseServers())
        return remoteIds.distinct().chunked(HELD_LOOKUP_CHUNK).flatMap { chunk ->
            books.byRemoteUuidsWithin(chunk, siblings.toList())
        }.filter { it.belongsToBrowse(server, main, siblings) }
            .mapNotNull(Book::remoteUuid)
            .toSet()
    }

    suspend fun connect(
        kind: ServerKind,
        address: String,
        username: String,
        password: String,
        apiKey: String,
        deviceToken: String,
        allowHttp: Boolean,
    ): BrowseConnectionResult {
        val credential = when (kind) {
            ServerKind.CALIBRE, ServerKind.BOOKORBIT -> RemoteCredentials.Basic(username.trim(), password)
            ServerKind.KOMGA -> RemoteCredentials.ApiKey(apiKey.trim())
            ServerKind.LISEUR_SYNC -> if (deviceToken.isNotBlank()) {
                RemoteCredentials.Bearer(deviceToken.trim())
            } else {
                RemoteCredentials.Basic(username.trim(), password)
            }
            ServerKind.CUSTOM -> if (username.isBlank() && password.isBlank()) {
                RemoteCredentials.Anonymous
            } else {
                RemoteCredentials.Basic(username.trim(), password)
            }
        }
        val setup = setups[kind] ?: return BrowseConnectionResult(failure = SetupFailure.WrongServer)
        val result = setup.connect(address, credential, allowHttp)
        val capabilities = when (result) {
            is SetupResult.Failure -> return BrowseConnectionResult(failure = result.reason)
            is SetupResult.Success -> result.capabilities
        }
        if (capabilities.catalogUrl == null) {
            return BrowseConnectionResult(failure = SetupFailure.WrongServer)
        }
        return changing.withLock {
            var id = 0L
            inTransaction {
                id = servers.nextBrowseId()
                servers.upsert(
                    RemoteServer(
                        id = id,
                        kind = kind,
                        baseUrl = capabilities.baseUrl,
                        catalogUrl = capabilities.catalogUrl,
                        username = username.trim().ifBlank {
                            if (kind == ServerKind.CUSTOM) "" else capabilities.displayName
                        },
                        passwordCipher = (credential as? RemoteCredentials.Basic)
                            ?.takeIf { kind.signsWithStoredPassword }
                            ?.let { RemoteServer.seal(it.password) },
                        apiKeyCipher = (credential as? RemoteCredentials.ApiKey)
                            ?.let { RemoteServer.seal(it.key) },
                        accountId = capabilities.accountId,
                        userId = capabilities.calibreUserId,
                        koboTokenCipher = null,
                        canDownload = capabilities.canDownload,
                        addedAt = System.currentTimeMillis(),
                        catalogSyncedAt = null,
                        positionSyncedAt = null,
                        syncToken = null,
                        liseurTokenCipher = RemoteServer.seal(
                            capabilities.liseurToken ?: (credential as? RemoteCredentials.Bearer)?.token,
                        ),
                        liseurAccountId = capabilities.liseurAccountId,
                        orbitAccessCipher = RemoteServer.seal(capabilities.orbitAccessToken),
                        orbitRefreshCipher = RemoteServer.seal(capabilities.orbitRefreshToken),
                        orbitAccessExpires = capabilities.orbitAccessExpiresAt,
                        orbitSessionId = capabilities.orbitSessionId,
                        orbitEpoch = System.currentTimeMillis(),
                    ),
                )
            }
            BrowseConnectionResult(id = id)
        }
    }

    /**
     * One section of a catalog. OPDS sections are read a page at a time so the first books show
     * quickly; pass the previous listing's [BrowseListing.nextPage] as [page] to continue.
     * Other kinds are walked whole once and reused across categories until [fresh] is asked.
     */
    suspend fun listing(
        serverId: Long,
        categoryId: String? = null,
        page: String? = null,
        fresh: Boolean = false,
        requestBudget: Int = OpdsCatalogClient.MAX_REQUESTS,
    ): BrowseListing {
        val server = servers.get(serverId)?.takeIf { it.id != RemoteServer.SINGLE_ID }
            ?: return BrowseListing(complete = false)
        val credential = server.credentials ?: return BrowseListing(complete = false)
        val address = server.catalogUrl ?: return BrowseListing(complete = false)
        if (server.kind == ServerKind.CUSTOM || server.kind == ServerKind.CALIBRE) {
            val client = router.catalogFor(ServerKind.CUSTOM) as? OpdsCatalogClient
                ?: return BrowseListing(complete = false)
            val root = if (server.kind == ServerKind.CALIBRE) {
                CalibreUrl.resolve(address, "/opds")
            } else {
                address
            }
            val gutenberg = GutenbergBrowse.isRoot(server)
            if (gutenberg && categoryId != null) {
                GutenbergBrowse.listing(categoryId)?.let { return it }
                if (GutenbergBrowse.isVirtual(categoryId) && !GutenbergBrowse.isShelf(categoryId)) {
                    return BrowseListing(complete = false)
                }
            }
            val section = if (gutenberg && categoryId != null) {
                GutenbergBrowse.feedUrl(categoryId) ?: categoryId
            } else {
                categoryId ?: root
            }
            val listing = client.browseSection(
                root, credential, page ?: section, pageLimit = 1, requestBudget = requestBudget,
            )
            return if (gutenberg && categoryId == null && page == null) {
                listing.copy(categories = GutenbergBrowse.rootCategories() + listing.categories)
            } else {
                listing
            }
        }
        val client = if (server.kind == ServerKind.BOOKORBIT) {
            orbitCatalog(serverId)
        } else {
            router.catalogFor(server.kind)
        } ?: return BrowseListing(complete = false)
        val key = BrowseWalkCache.Key(serverId, server.addedAt, server.accountKey)
        val walk = walks.get(key, fresh) {
            val found = mutableListOf<RemoteBook>()
            val result = client.allBooks(address, credential) { found += it }
            val folders = if (server.kind == ServerKind.LISEUR_SYNC) {
                (client as? LiseurSyncCatalogClient)?.browseFolders(address, credential)
                    ?.associateBy(BrowseCategory::id).orEmpty()
            } else {
                emptyMap()
            }
            BrowseWalk(found.distinctBy(RemoteBook::remoteId), result.complete, folders)
        }
        val grouped = walk.books.groupBy { book -> categoryKey(server.kind, book) }
        val namedFolders = walk.folders
        val sections = grouped.keys.filterNotNull().map { key ->
            namedFolders[key] ?: BrowseCategory(
                key,
                grouped.getValue(key).first().seriesName ?: key.substringAfter(':'),
            )
        }.sortedBy { it.title.lowercase() }
        return BrowseListing(
            categories = if (categoryId == null) sections else emptyList(),
            books = if (categoryId == null) grouped[null].orEmpty() else grouped[categoryId].orEmpty(),
            complete = walk.complete,
        )
    }

    /** A complete recursive snapshot is required before offering a category-wide add. */
    suspend fun categoryBooks(serverId: Long, categoryId: String): BrowseListing {
        val server = servers.get(serverId) ?: return BrowseListing(complete = false)
        if (GutenbergBrowse.isRoot(server) && GutenbergBrowse.isVirtual(categoryId) &&
            !GutenbergBrowse.isShelf(categoryId)
        ) return BrowseListing(complete = false)
        if (server.kind != ServerKind.CUSTOM && server.kind != ServerKind.CALIBRE) {
            return listing(serverId, categoryId)
        }
        val queue = ArrayDeque(listOf(categoryId))
        val seen = mutableSetOf<String>()
        val found = linkedMapOf<String, RemoteBook>()
        var complete = true
        var spent = 0
        while (queue.isNotEmpty() && seen.size < MAX_CATEGORY_SECTIONS && spent < OpdsCatalogClient.MAX_REQUESTS) {
            val section = queue.removeFirst()
            if (!seen.add(section)) continue
            var cursor: String? = null
            var pages = 0
            do {
                val page = listing(serverId, section, cursor, requestBudget = OpdsCatalogClient.MAX_REQUESTS - spent)
                spent += page.requests
                complete = complete && page.complete
                page.books.forEach { found[it.remoteId] = it }
                page.categories.forEach { if (it.id !in seen) queue.addLast(it.id) }
                cursor = page.nextPage
            } while (cursor != null && ++pages < OpdsCatalogClient.MAX_REQUESTS && spent < OpdsCatalogClient.MAX_REQUESTS)
            if (cursor != null) complete = false
        }
        if (queue.isNotEmpty()) complete = false
        return BrowseListing(books = found.values.toList(), complete = complete)
    }

    suspend fun add(serverId: Long, selected: List<RemoteBook>): Int = changing.withLock {
        val server = servers.get(serverId)?.takeIf { it.id != RemoteServer.SINGLE_ID && it.canDownload }
            ?: return@withLock 0
        val address = server.catalogUrl ?: return@withLock 0
        val candidates = selected.filter { it.downloadHref != null }.distinctBy(RemoteBook::remoteId)
        val added = mutableListOf<com.chmouel.liseur.data.db.Book>()
        inTransaction {
            val current = servers.get(serverId)
            if (current?.addedAt != server.addedAt || current.accountKey != server.accountKey) return@inTransaction
            val held = heldRemoteIds(server, candidates.map(RemoteBook::remoteId))
            candidates.filter { it.remoteId !in held }.forEach { remote ->
                val url = bookUrl(serverId, server.kind, remote.remoteId)
                val existing = books.getByUrl(url)
                if (existing == null) {
                    val merged = mergeCatalogEntry(
                        remote, null, url, address, System.currentTimeMillis(), server.kind,
                    )
                    val book = merged.copy(
                        browseServerId = serverId,
                        coverUrl = merged.coverUrl?.let { browseCoverUrl(it, server) },
                    )
                    books.upsert(book)
                    added += book
                }
            }
        }
        added.forEach { downloads.enqueue(it, guardSpace = added.size > 1) }
        added.size
    }

    suspend fun remove(serverId: Long) = changing.withLock {
        if (serverId == RemoteServer.SINGLE_ID) return@withLock
        if (servers.get(serverId) == null) return@withLock
        walks.forget(serverId)
        val owned = books.fromBrowseServer(serverId)
        owned.forEach { downloads.stopWork(it) }
        var removedServer: RemoteServer? = null
        var adopted: List<Book> = emptyList()
        downloads.withBookLocks(owned.map { it.url }) {
            // A worker may have finished while cancellation was being requested.
            // Classify its row only after it has released the book lock.
            books.fromBrowseServer(serverId).filter { it.localUri == null }.forEach {
                downloads.removeDownload(it)
            }
            inTransaction {
                // Read again here: a refresh of the same account may have
                // adopted a row into the main library since [owned]. Its
                // binding is now the main connection's, and its download,
                // cancelled above all the same, has to start again.
                val stillOwned = books.fromBrowseServer(serverId)
                val removing = servers.get(serverId)
                // A second saved connection to the same account keeps the
                // downloaded books linked, so it does not offer them again.
                val heir = removing?.let { survivingSibling(it, servers.browseServers()) }
                val inherited = if (heir == null) {
                    emptySet()
                } else {
                    stillOwned.filter { it.localUri != null }.onEach {
                        books.moveToBrowseServer(it.url, heir.id, it.coverUrl?.let { url -> rescopedBrowseCoverUrl(url, heir) })
                    }.mapTo(mutableSetOf()) { it.url }
                }
                adopted = owned.map { it.url }.chunked(HELD_LOOKUP_CHUNK).flatMap { books.getByUrls(it) }
                    .filter {
                        it.browseServerId == null && it.localUri == null &&
                            it.downloadState in IN_FLIGHT
                    }
                removal.deleteByUrls(books.undownloadedFromBrowseServer(serverId).map { it.url })
                books.unlinkDownloadedFromBrowseServer(serverId)
                stillOwned.filter { it.url !in inherited }.forEach { orbitBindings.clearBook(it.url) }
                removedServer = removing
                servers.delete(serverId)
            }
        }
        adopted.forEach { downloads.enqueue(it) }
        removedServer?.takeIf { it.kind == ServerKind.BOOKORBIT }?.let {
            try {
                closeOrbit(it)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The local removal already committed; logout is best effort.
            }
        }
    }

    private fun categoryKey(kind: ServerKind, book: RemoteBook): String? = when (kind) {
        ServerKind.LISEUR_SYNC -> book.folderId?.let { "folder:$it" }
        ServerKind.KOMGA -> (book.seriesId ?: book.seriesName)?.let { "series:$it" }
        ServerKind.BOOKORBIT -> (book.seriesId ?: book.seriesName)?.let { "series:$it" }
        ServerKind.CALIBRE, ServerKind.CUSTOM -> book.seriesName?.let { "series:$it" }
    }

    companion object {
        const val MAX_CATEGORY_SECTIONS = 200
        private const val HELD_LOOKUP_CHUNK = 500
        private val IN_FLIGHT = setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING)
        fun bookUrl(serverId: Long, kind: ServerKind, remoteId: String): String =
            "browse:$serverId:${kind.urlPrefix}:$remoteId"
    }
}
