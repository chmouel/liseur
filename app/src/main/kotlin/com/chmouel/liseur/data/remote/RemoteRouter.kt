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
) {
    private suspend fun kind(): ServerKind? = serverDao.get()?.kind

    suspend fun catalog(): CatalogSource? = kind()?.let(catalogs::get)

    suspend fun files(): FileSource? = kind()?.let(files::get)

    suspend fun positionSync(): PositionSync? = kind()?.let(positions::get)
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
 */
class RoutedPositionSync(private val router: RemoteRouter) : PositionSync {

    override suspend fun syncAll(): SyncOutcome =
        router.positionSync()?.syncAll() ?: SyncOutcome.NotApplicable

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
