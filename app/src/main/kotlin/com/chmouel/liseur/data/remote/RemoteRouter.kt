package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.db.RemoteServerDao

/**
 * Sends a request to whichever implementation matches the connected
 * server.
 *
 * One server is connected at a time, so its [ServerKind] is the whole
 * decision. Keeping it in one place means the rest of the app — the
 * library, the download worker, the sync coordinator — never learns
 * that there is more than one kind of server.
 */
class RemoteRouter(
    private val serverDao: RemoteServerDao,
    private val catalogs: Map<ServerKind, CatalogSource>,
    private val files: Map<ServerKind, FileSource>,
    private val positions: Map<ServerKind, PositionSync>,
    private val seriesClaims: Map<ServerKind, SeriesClaimSync> = emptyMap(),
    /**
     * Which kinds can delete a book on the server at all. Absent from
     * the map means the action is never offered for that kind.
     */
    private val deleters: Map<ServerKind, BookDeleter> = emptyMap(),
    /**
     * Which kinds can be sent a book at all. Absent means the action is
     * never offered for that kind, the same rule [deleters] follows.
     */
    private val uploaders: Map<ServerKind, BookUploader> = emptyMap(),
) {
    private suspend fun kind(): ServerKind? = serverDao.get()?.kind

    suspend fun catalog(): CatalogSource? = kind()?.let(catalogs::get)

    /**
     * The catalog for a server already in hand.
     *
     * Reading the connected server again would be a second look at
     * something that may have changed in between, which is how the new
     * account's secret ends up being sent to the old account's host.
     */
    fun catalogFor(kind: ServerKind): CatalogSource? = catalogs[kind]

    suspend fun files(): FileSource? = kind()?.let(files::get)

    /** The downloader for a server already in hand. See [catalogFor]. */
    fun filesFor(kind: ServerKind): FileSource? = files[kind]

    suspend fun positionSync(): PositionSync? = kind()?.let(positions::get)

    /** The deleter for a server already in hand, when the kind has one. */
    fun deleterFor(kind: ServerKind): BookDeleter? = deleters[kind]

    /** The uploader for a server already in hand, when the kind has one. */
    fun uploaderFor(kind: ServerKind): BookUploader? = uploaders[kind]

    /** The series-claim writer for a server already in hand, when it has one. */
    fun seriesClaimsFor(kind: ServerKind): SeriesClaimSync? = seriesClaims[kind]
}

/**
 * A [PositionSync] that is whichever one the connected server needs.
 *
 * This is what `PositionSyncCoordinator` wraps, so its ordering rules —
 * one run at a time, and never claiming a run that started too early —
 * hold across both kinds of server without being written twice.
 *
 * With nothing connected, or a server whose kind cannot sync, every call
 * answers "not applicable" rather than failing: there is nothing wrong,
 * there is simply nothing to do, and a retry would not change that.
 *
 * As a peer this is one partner among possibly several, and the one
 * whose reading positions the connected server shows in its own
 * interface.
 */
class RoutedPositionSync(private val router: RemoteRouter) : PeerPositionSync {

    override val peerId: String get() = PeerPositionSync.CATALOG

    override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome =
        router.positionSync()?.syncAll(snapshot) ?: SyncOutcome.NotApplicable

    override suspend fun syncBook(bookUrl: String): SyncOutcome =
        router.positionSync()?.syncBook(bookUrl) ?: SyncOutcome.NotApplicable

    override suspend fun canSync(bookUrl: String): Boolean =
        router.positionSync()?.canSync(bookUrl) ?: false

    override suspend fun previewBook(bookUrl: String): PreviewOutcome =
        router.positionSync()?.previewBook(bookUrl) ?: PreviewOutcome.NotSynced

    override suspend fun preservedConflict(bookUrl: String): SyncPreview? =
        router.positionSync()?.preservedConflict(bookUrl)

    override suspend fun takeRemotePosition(bookUrl: String, atRevision: Long): ResolveOutcome =
        router.positionSync()?.takeRemotePosition(bookUrl, atRevision) ?: ResolveOutcome.Done

    override suspend fun keepLocalPosition(bookUrl: String): ResolveOutcome =
        router.positionSync()?.keepLocalPosition(bookUrl) ?: ResolveOutcome.Done

    override suspend fun refreshUnresolved() {
        router.positionSync()?.refreshUnresolved()
    }

    override suspend fun identity(): SyncIdentity? = router.positionSync()?.identity()
}
