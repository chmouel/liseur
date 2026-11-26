package com.chmouel.liseur.drive

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.room.withTransaction
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.PeerPositionSync
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.domain.FOREGROUND_SYNC_FRESH_FOR_MS
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.USELESS_IDENTIFIERS
import com.chmouel.liseur.domain.WorkIdentifiers
import com.chmouel.liseur.domain.identifierOf
import com.chmouel.liseur.hardcover.Isbn
import com.chmouel.liseur.providers.PlaySettingsEntries
import com.chmouel.liseur.sync.LibrarySyncBackend
import com.chmouel.liseur.sync.LibrarySyncSwitchCoordinator
import com.chmouel.liseur.sync.LibraryUploadTarget
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Play-only Google Drive [LibrarySyncBackend] implementation (ADR-0078).
 *
 * Opens `drive_sync.db` lazily so installs that never connect Google Drive
 * create no database file on disk. Enforces mutual exclusion with the main
 * `RemoteServer` (`id = 1`) while leaving browse-only catalogs (`id > 1`)
 * and kosync pairings untouched.
 */
internal class DriveLibrarySync(
    context: Context,
    private val database: LiseurDatabase,
    private val remoteAccount: RemoteAccountRepository,
    private val bookRemoval: BookRemoval? = null,
    private val switchCoordinator: LibrarySyncSwitchCoordinator = LibrarySyncSwitchCoordinator(),
    private val beforeServerDisconnect: suspend () -> Unit = {},
    private val stopWorkers: () -> Unit = {},
    private val onResumeActive: () -> Unit = {},
    private val scheduleEpubUploadsWork: (allowMetered: Boolean) -> Unit = {},
    private val appendEpubUploadsWork: (allowMetered: Boolean) -> Unit = scheduleEpubUploadsWork,
    private val cancelEpubUploadsWork: () -> Unit = {},
    private val enqueueEpubDownloadWork: suspend (bookUrl: String, allowMetered: Boolean) -> UUID? = { _, _ -> null },
    private val cancelEpubDownloadWork: suspend (bookUrl: String) -> Unit = {},
    private val beforeQueueWrite: suspend () -> Unit = {},
    private val deviceIdGenerator: () -> String = { UUID.randomUUID().toString().lowercase(Locale.ROOT) },
    private val openStore: (() -> DriveSyncDatabase)? = null,
    authClient: DriveAuthClient? = null,
    driveApi: DriveRemoteApi? = null,
    private val coversDirProvider: () -> File = { File(context.applicationContext.filesDir, "covers") },
    private val stagingDirProvider: () -> File = { DriveEpubTransferManager.defaultStagingDir(context.applicationContext) },
    private val coverEncoder: DriveWebpEncoder = AndroidBitmapWebpEncoder,
    private val compactionDeltaThreshold: Int = DriveCompactor.DEFAULT_DELTA_COUNT_THRESHOLD,
    private val compactionBytesThreshold: Long = DriveCompactor.DEFAULT_DELTA_BYTES_THRESHOLD,
    private val compactionMaxPartBytes: Int = DriveWireCodec.MAX_SNAPSHOT_PART_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : LibrarySyncBackend {

    private val appContext = context.applicationContext
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFile = appContext.getDatabasePath(DriveSyncDatabase.NAME)
    private val storeOpened = MutableStateFlow(openStore != null)

    private val storeLazy = lazy {
        (openStore?.invoke() ?: DriveSyncDatabase.open(appContext)).also {
            storeOpened.value = true
        }
    }

    internal val store: DriveSyncDatabase
        get() = storeLazy.value

    internal val authClient: DriveAuthClient by lazy {
        authClient ?: PlayServicesDriveAuthClient(appContext)
    }

    internal val driveApi: DriveRemoteApi by lazy {
        driveApi ?: DriveRestClient()
    }

    internal val accountController: DriveAccountController by lazy {
        DriveAccountController(
            database = database,
            remoteAccount = remoteAccount,
            driveSync = this,
            authClient = this.authClient,
            driveApi = this.driveApi,
            clock = clock,
        )
    }

    internal val fingerprintStore: BookFingerprintStore by lazy {
        BookFingerprintStore(appContext, database.workIdentityDao(), clock)
    }

    private val finishedState: FinishedState by lazy {
        FinishedState(
            bookDao = database.bookDao(),
            progressDao = database.readingProgressDao(),
            inTransaction = { work -> database.withTransaction { work() } },
            now = clock,
        )
    }

    internal val workIdentity: DriveWorkIdentity by lazy {
        DriveWorkIdentity(
            database = database,
            store = store,
            fingerprintStore = fingerprintStore,
            clock = clock,
        )
    }

    internal val catalogSync: DriveCatalogSync by lazy {
        DriveCatalogSync(
            database = database,
            store = store,
            workIdentity = workIdentity,
            bookRemoval = bookRemoval,
            onUploadDiscarded = { permissionId, sha ->
                DriveEpubTransferManager.discardStaged(stagingDirProvider(), permissionId, sha)
            },
            clock = clock,
        )
    }

    internal val coverCodec: DriveCoverCodec by lazy {
        DriveCoverCodec(
            coversDir = coversDirProvider(),
            store = store,
            driveApi = this.driveApi,
            encoder = coverEncoder,
        )
    }

    internal val annotationSync: DriveAnnotationSync by lazy {
        DriveAnnotationSync(
            database = database,
            store = store,
            workIdentity = workIdentity,
            clock = clock,
        )
    }

    internal val evaluationSync: DriveEvaluationSync by lazy {
        DriveEvaluationSync(
            database = database,
            store = store,
            workIdentity = workIdentity,
            clock = clock,
        )
    }

    internal val metadataSync: DriveMetadataSync by lazy {
        DriveMetadataSync(
            database = database,
            store = store,
            workIdentity = workIdentity,
            coverCodec = coverCodec,
            clock = clock,
        )
    }

    internal val seriesSync: DriveSeriesSync by lazy {
        DriveSeriesSync(
            database = database,
            store = store,
            workIdentity = workIdentity,
            clock = clock,
        )
    }

    internal val vocabularySync: DriveVocabularySync by lazy {
        DriveVocabularySync(
            database = database,
            store = store,
            workIdentity = workIdentity,
            clock = clock,
        )
    }

    internal val sessionSync: DriveSessionSync by lazy {
        DriveSessionSync(
            database = database,
            store = store,
            workIdentity = workIdentity,
            clock = clock,
        )
    }

    internal val positionSync: DrivePositionSync by lazy {
        DrivePositionSync(
            database = database,
            storeProvider = { store },
            workIdentityProvider = { workIdentity },
            finishedState = finishedState,
            runSync = { bookUrl -> changeEngine.sync(bookUrlFilter = bookUrl).outcome },
            isActive = ::isActive,
            activeAccount = { if (hasStore()) store.accounts().active() else null },
            localDeviceId = { ensureInstall().deviceId },
            nextHlc = { permissionId -> changeEngine.nextHlc(permissionId) },
            clock = clock,
        )
    }

    private val changeEngineLazy = lazy {
        DriveChangeEngine(
            store = store,
            driveApi = this.driveApi,
            workIdentity = workIdentity,
            catalogSync = catalogSync,
            positionSync = positionSync,
            acquireToken = { stale -> accountController.acquireSilentAccessToken(stale) },
            ensureDeviceId = { ensureInstall().deviceId },
            onNeedsReconnect = { permissionId, reason -> markNeedsReconnectIfActive(permissionId, reason) },
            annotationSync = annotationSync,
            evaluationSync = evaluationSync,
            metadataSync = metadataSync,
            seriesSync = seriesSync,
            vocabularySync = vocabularySync,
            sessionSync = sessionSync,
            compactionDeltaThreshold = compactionDeltaThreshold,
            compactionBytesThreshold = compactionBytesThreshold,
            compactionMaxPartBytes = compactionMaxPartBytes,
            clock = clock,
        )
    }

    internal val changeEngine: DriveChangeEngine by changeEngineLazy

    private val epubTransfersLazy = lazy {
        DriveEpubTransferManager(
            context = appContext,
            database = database,
            store = store,
            driveApi = this.driveApi,
            workIdentity = workIdentity,
            catalogSync = catalogSync,
            fingerprintStore = fingerprintStore,
            acquireToken = { stale -> accountController.acquireSilentAccessToken(stale) },
            nextHlc = { permissionId -> changeEngine.nextHlc(permissionId) },
            onCatalogAvailabilityChanged = {
                changeEngine.sync()
            },
            onNeedsReconnect = { permissionId, reason -> markNeedsReconnectIfActive(permissionId, reason) },
            stagingDirProvider = stagingDirProvider,
            beforeQueueWrite = beforeQueueWrite,
            clock = clock,
        )
    }

    internal val epubTransfers: DriveEpubTransferManager by epubTransfersLazy

    internal fun hasStore(): Boolean = storeLazy.isInitialized() || openStore != null || storeFile.exists()

    internal val storeAvailable: Flow<Boolean> = storeOpened
        .map { opened -> opened || storeFile.exists() }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val active: Flow<Boolean> = storeAvailable
        .flatMapLatest { exists ->
            if (exists) store.accounts().observeActive().map { it != null } else flowOf(false)
        }
        .distinctUntilChanged()

    override suspend fun isActive(): Boolean {
        if (!hasStore()) return false
        return store.accounts().active() != null
    }

    /**
     * The books the main-side upload offer must leave alone, kept in step with
     * the queue and with the Drive files a catalog edition advertises.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val uploadTarget: Flow<LibraryUploadTarget?> = storeAvailable
        .flatMapLatest { exists ->
            if (!exists) {
                flowOf(null)
            } else {
                store.accounts().observeActive()
                    .map { account ->
                        account?.let { Triple(it.permissionId, it.connectedAt, it.uploadSelectionAt) }
                    }.distinctUntilChanged()
                    .flatMapLatest { identity ->
                        if (identity == null) {
                            flowOf(null)
                        } else {
                            val (permissionId, connectedAt, selectionAt) = identity
                            val held = combine(
                                store.files().observeLiveEpubFiles(permissionId),
                                store.recordStates().observeDomain(permissionId, DriveWireCodec.DOMAIN_CATALOG),
                                ::heldEpubSha256s,
                            ).distinctUntilChanged().flowOn(Dispatchers.Default)
                            combine(
                                store.uploads().observeForAccount(permissionId),
                                held,
                                database.workIdentityDao().observeFingerprints(),
                            ) { uploads, remoteEpubShas, fingerprints ->
                                driveUploadTarget(
                                    permissionId = permissionId,
                                    offerSince = maxOf(connectedAt, selectionAt ?: 0L),
                                    uploads = uploads,
                                    remoteEpubShas = remoteEpubShas,
                                    fingerprints = fingerprints,
                                    selectionSettled = selectionAt != null,
                                )
                            }.flowOn(Dispatchers.Default)
                        }
                    }
            }
        }.distinctUntilChanged()

    /** Queues [books] for the active account, and only when it is still the one the caller saw. */
    override suspend fun enqueueUploads(accountKey: String, books: List<Book>, expectedOfferSince: Long?): Set<String> {
        if (!hasStore() || books.isEmpty()) return emptySet()
        val active = store.accounts().active() ?: return emptySet()
        if (accountPeerId(active.permissionId) != accountKey) return emptySet()
        val queued = epubTransfers.queueBooks(active.permissionId, books.map { it.url }, expectedOfferSince)
        if (queued.isEmpty()) return queued
        // Fingerprinting the books takes a while, and the reader may have turned metered
        // transfers off meanwhile. An empty queue reschedules nothing, so the allowance
        // read above would schedule this work for mobile data.
        val current = store.accounts().active() ?: return queued
        if (accountPeerId(current.permissionId) != accountKey) return queued
        appendEpubUploadsWork(current.meteredTransfers)
        return queued
    }

    override suspend fun deactivate() {
        if (hasStore()) {
            store.withTransaction {
                store.accounts().pauseAllActive()
                // An intentional disconnect must not look like an abandoned switch at startup.
                store.accounts().clearPendingSwitches()
            }
        }
        stopWorkers()
        if (changeEngineLazy.isInitialized()) {
            changeEngine.cancelAndDrain()
        }
        if (epubTransfersLazy.isInitialized()) {
            epubTransfers.cancelAndDrain()
        }
    }

    override fun resume() {
        if (!hasStore()) return
        scope.launch {
            runCatching {
                if (!recoverAbandonedSwitches()) {
                    if (store.accounts().active() == null) return@runCatching
                    resumeActiveWork()
                }
            }
        }
    }

    /**
     * Restores accounts left `PAUSED` by an account switch that never finished,
     * because Android killed the process while the confirmation was open.
     *
     * Returns true when it resumed an account, which has already been handed to
     * [onResumeActive] by [restoreAccountState].
     */
    private suspend fun recoverAbandonedSwitches(): Boolean {
        var restored = false
        for (account in store.accounts().withPendingSwitch()) {
            val previous = account.pendingSwitchPreviousState ?: continue
            if (restoreAccountState(account.permissionId, previous)) restored = true
        }
        return restored
    }

    override suspend fun shouldSyncOnForeground(now: Long): Boolean {
        if (!hasStore()) return false
        val active = store.accounts().active() ?: return false
        val last = active.lastSuccessAt ?: return true
        if (last > now) return true
        return now - last >= FOREGROUND_SYNC_FRESH_FOR_MS
    }

    override fun ownsBook(book: Book): Boolean =
        book.url.startsWith(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX) ||
            DriveCatalogSync.fileIdFromHref(book.downloadHref) != null

    override suspend fun canDownloadAgain(book: Book): Boolean {
        if (!ownsBook(book) || !hasStore()) return false
        val active = store.accounts().active() ?: return false
        val installedSha = if (book.localUri != null) {
            (fingerprintStore.of(book)?.sha256 ?: database.workIdentityDao().fingerprint(book.url)?.sha256)
                ?.lowercase(java.util.Locale.ROOT)
                ?: return false
        } else {
            null
        }
        return catalogSync.resolveDownloadSource(
            permissionId = active.permissionId,
            book = book,
            requiredSha256 = installedSha,
        ) != null
    }

    override suspend fun enqueueDownload(book: Book): UUID? {
        if (!ownsBook(book)) return null
        if (!hasStore()) return null
        val active = store.accounts().active() ?: return null
        database.bookDao().setDownloadState(book.url, DownloadState.QUEUED, null)
        return enqueueEpubDownloadWork(book.url, active.meteredTransfers)
    }

    override suspend fun cancelDownload(book: Book) {
        if (!ownsBook(book)) return
        cancelEpubDownloadWork(book.url)
    }

    override suspend fun canDeleteFromSyncedLibrary(book: Book): Boolean {
        if (book.browseServerId != null) return false
        return isActive()
    }

    override suspend fun deleteFromSyncedLibrary(book: Book): Boolean {
        if (book.browseServerId != null || !hasStore()) return false
        val active = store.accounts().active() ?: return false
        // Same lock order as resolveConflicts. Remote trashing is left to the sync below,
        // so no network call runs while the locks are held.
        val deleted = changeEngine.withSyncLock {
            switchCoordinator.withSwitchLock {
                if (store.accounts().active()?.permissionId != active.permissionId) return@withSwitchLock false
                catalogSync.deleteFromSyncedLibrary(
                    permissionId = active.permissionId,
                    book = book,
                    nextHlc = { changeEngine.nextHlc(active.permissionId) },
                )
            }
        }
        if (deleted) {
            runCatching { changeEngine.sync() }
        }
        return deleted
    }

    /** Resolves one or more conflicts (e.g. a shelf-grouped series conflict) and triggers sync. */
    internal suspend fun resolveConflicts(
        conflictsToResolve: List<DriveConflict>,
        choice: DriveConflictChoice,
        syncAfter: Boolean = true,
    ) {
        if (!hasStore() || conflictsToResolve.isEmpty()) return
        val active = store.accounts().active() ?: return
        val localDeviceId = ensureInstall().deviceId
        val tokenProvider: suspend () -> String? = {
            when (val res = accountController.acquireSilentAccessToken()) {
                // A token for another account must never fetch or publish this account's data.
                is SilentTokenResult.Token -> res.accessToken.takeIf { res.permissionId == active.permissionId }
                else -> null
            }
        }
        // A dialog opened before an account switch may still hold another account's conflict.
        val ownConflicts = conflictsToResolve.filter { it.permissionId == active.permissionId }
        // Network first and outside the switch lock: a remote cover is fetched before anything commits.
        // Any choice but DELETE / KEEP_LOCAL may keep the remote cover (KEEP_EDITED after a local reset).
        if (choice != DriveConflictChoice.DELETE && choice != DriveConflictChoice.KEEP_LOCAL) {
            for (conflict in ownConflicts.filter { it.domain == DriveWireCodec.DOMAIN_METADATA }) {
                metadataSync.prefetchConflictCover(active.permissionId, conflict, tokenProvider)
            }
        }
        // Lock order is sync mutex -> switch lock, as in DriveChangeEngine.sync, so the account
        // cannot change between this check and the commits.
        changeEngine.withSyncLock {
            switchCoordinator.withSwitchLock {
                if (store.accounts().active()?.permissionId != active.permissionId) return@withSwitchLock
                resolveOwnConflictsLocked(active.permissionId, ownConflicts, choice, localDeviceId)
            }
        }
        if (syncAfter) {
            runCatching { changeEngine.sync() }
        }
    }

    private suspend fun resolveOwnConflictsLocked(
        permissionId: String,
        ownConflicts: List<DriveConflict>,
        choice: DriveConflictChoice,
        localDeviceId: String,
    ) {
        for (requested in ownConflicts) {
            // Only conflicts still open (not settled by a sync meanwhile) are resolved.
            val conflict = store.conflicts().get(permissionId, requested.conflictId)
                ?.takeIf { it.state == DriveConflict.STATE_OPEN }
                ?: continue
            when (conflict.domain) {
                DriveWireCodec.DOMAIN_ANNOTATION ->
                    annotationSync.resolveLocalConflict(
                        permissionId = permissionId,
                        conflict = conflict,
                        choice = choice,
                        localDeviceId = localDeviceId,
                        nextHlc = { changeEngine.nextHlc(permissionId) },
                    )
                DriveWireCodec.DOMAIN_EVALUATION ->
                    evaluationSync.resolveLocalConflict(
                        permissionId = permissionId,
                        conflict = conflict,
                        choice = choice,
                        localDeviceId = localDeviceId,
                        nextHlc = { changeEngine.nextHlc(permissionId) },
                    )
                DriveWireCodec.DOMAIN_METADATA ->
                    metadataSync.resolveLocalConflict(
                        permissionId = permissionId,
                        conflict = conflict,
                        choice = choice,
                        localDeviceId = localDeviceId,
                        nextHlc = { changeEngine.nextHlc(permissionId) },
                        // The cover was prefetched; resolving never touches the network.
                        accessTokenProvider = null,
                    )
                DriveWireCodec.DOMAIN_SERIES ->
                    seriesSync.resolveLocalConflict(
                        permissionId = permissionId,
                        conflict = conflict,
                        choice = choice,
                        localDeviceId = localDeviceId,
                        nextHlc = { changeEngine.nextHlc(permissionId) },
                    )
            }
        }
    }

    internal fun triggerEpubUploads(allowMetered: Boolean) {
        scheduleEpubUploadsWork(allowMetered)
    }

    internal fun cancelEpubUploads() {
        cancelEpubUploadsWork()
    }

    override val positionPeer: PeerPositionSync
        get() = positionSync

    override val supportsBookServerOption: Boolean = true
    override val settingsFocusKey: String = PlaySettingsEntries.GoogleDrive.key

    @Composable
    override fun connectedSummary(): String? = driveConnectedSummary(controller = accountController)

    @Composable
    override fun SettingsEntry(onOpen: () -> Unit) {
        DriveSettingsEntry(controller = accountController, onOpen = onOpen)
    }

    @Composable
    override fun SettingsSection() {
        DriveSettingsSection(controller = accountController)
    }

    @Composable
    override fun BookServerSelectedRow(enabled: Boolean, onClick: () -> Unit) {
        DriveBookServerSelectedRow(enabled = enabled, onClick = onClick)
    }

    @Composable
    override fun BookServerPickerRow(selected: Boolean, onPick: () -> Unit) {
        DriveBookServerPickerRow(selected = selected, onPick = onPick)
    }

    @Composable
    override fun BookServerSwitchAction(onClick: () -> Unit) {
        DriveBookServerSwitchAction(onClick)
    }

    @Composable
    override fun welcomeConnectHint(): String =
        stringResource(R.string.drive_welcome_connect_library_hint)

    @Composable
    override fun WelcomeConnectIcon(modifier: Modifier) {
        GoogleDriveLogo(modifier)
    }

    @Composable
    override fun BookManageAction(book: Book, onDone: () -> Unit) {
        DriveBookManageAction(controller = accountController, book = book, onDone = onDone)
    }

    /** Stops background workers and transitions [permissionId] to `NEEDS_RECONNECT` while preserving all state. */
    internal suspend fun markNeedsReconnect(permissionId: String, reason: String) {
        // Persist before stopping workers: a worker calling this would otherwise cancel
        // itself first and leave the account looking healthy, retrying the same 401 forever.
        withContext(NonCancellable) {
            if (hasStore()) {
                store.withTransaction {
                    store.accounts().setState(permissionId, DriveAccount.STATE_NEEDS_RECONNECT)
                    store.accounts().recordError(permissionId, reason)
                }
            }
        }
        stopWorkers()
    }

    /**
     * [markNeedsReconnect] for an account that must still be the active one, checked
     * under the switch lock so a concurrent server connect or account switch wins.
     */
    internal suspend fun markNeedsReconnectIfActive(permissionId: String, reason: String): Boolean {
        if (!hasStore()) return false
        return switchCoordinator.withSwitchLock {
            if (store.accounts().active()?.permissionId != permissionId) return@withSwitchLock false
            markNeedsReconnect(permissionId, reason)
            true
        }
    }

    /**
     * Restores a previously saved account state after cancelling a pending switch,
     * while upholding the mutual exclusion invariant with any connected main server.
     *
     * Returns true when the account is `ACTIVE` again and its workers were resumed.
     */
    internal suspend fun restoreAccountState(permissionId: String, state: String): Boolean {
        if (!hasStore()) return false
        val restoredActive = switchCoordinator.withSwitchLock {
            if (state == DriveAccount.STATE_ACTIVE && remoteAccount.current() != null) {
                return@withSwitchLock false
            }
            val applied = store.withTransaction {
                // The marker can be gone or changed by a transition that ran while
                // this restore was queued: a disconnect clears it, and a newer
                // switch writes its own. Restoring a stale marker would undo that
                // transition, so only a still-matching marker is acted on.
                val current = store.accounts().get(permissionId)
                if (current?.pendingSwitchPreviousState != state) {
                    return@withTransaction false
                }
                if (state == DriveAccount.STATE_ACTIVE) {
                    store.accounts().pauseAllActive()
                }
                store.accounts().setState(permissionId, state)
                store.accounts().clearPendingSwitches()
                true
            }
            applied && state == DriveAccount.STATE_ACTIVE
        }
        if (restoredActive) resumeActiveWork()
        return restoredActive
    }

    /**
     * Pauses the active account for a switch awaiting the reader's confirmation,
     * recording in the same transaction that the pause is part of a switch.
     *
     * A process death during the confirmation then leaves a marker that startup
     * restores, instead of an account silently stopped and indistinguishable from
     * an intentional disconnect.
     */
    internal suspend fun pauseForPendingSwitch(previous: DriveAccount) {
        if (!hasStore()) return
        try {
            store.withTransaction {
                store.accounts().pauseAllActive()
                store.accounts().markPendingSwitch(previous.permissionId, previous.state)
            }
            stopWorkers()
            if (epubTransfersLazy.isInitialized()) {
                epubTransfers.cancelAndDrain()
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (epubTransfersLazy.isInitialized()) epubTransfers.cancelAndDrain()
                restoreAccountState(previous.permissionId, previous.state)
            }
            throw e
        }
    }

    internal fun notifyActivated() {
        onResumeActive()
        scope.launch { runCatching { resumeQueuedUploads() } }
    }

    /** Reschedules reading-state sync and any EPUB uploads queued for the active account. */
    private suspend fun resumeActiveWork() {
        onResumeActive()
        resumeQueuedUploads()
    }

    private suspend fun resumeQueuedUploads() {
        if (!hasStore()) return
        val active = store.accounts().active() ?: return
        if (store.uploads().workCount(active.permissionId) > 0) {
            scheduleEpubUploadsWork(active.meteredTransfers)
        }
    }

    /**
     * Activates Google Drive for [permissionId] while upholding the mutual
     * exclusion invariant (never both `remote_server.id = 1` and an `ACTIVE`
     * `drive_account`).
     *
     * If a main server is currently connected, disconnects it through the
     * history-preserving path so downloaded books, browse-only servers, and
     * fileless stubs carrying reading history or reader-authored overrides /
     * series / shelf state are kept and seeded with Drive `work_alias` rows
     * before server peer cleanup. Any previously active Drive account is
     * transitioned to `PAUSED` with all of its state preserved intact, except
     * for the EPUB upload selection, which a switch resets so the new account
     * is asked before anything goes up (ADR-0078).
     *
     * @param confirmedDisconnectServer `false` when the reader has not been asked
     *   about a main server: activation then fails with
     *   [MainServerConnectedException] rather than disconnecting one.
     * @param confirmedServer the connection the reader agreed to disconnect; a
     *   different one fails the same way, so consent never carries over to a replacement.
     */
    suspend fun activateAccount(
        permissionId: String,
        email: String,
        now: Long = System.currentTimeMillis(),
        confirmedDisconnectServer: Boolean = true,
        confirmedServer: ServerConnectionStamp? = null,
    ): DriveAccount {
        require(permissionId.isNotBlank()) { "Drive permissionId must not be blank" }
        return switchCoordinator.withSwitchLock {
            // Rechecked under the switch lock: the caller's own consent check happened
            // outside it, so a server connected while the reader was confirming must not
            // be disconnected without asking again.
            val connected = remoteAccount.current()
            if (connected != null) {
                val stamp = ServerConnectionStamp(connected)
                if (!confirmedDisconnectServer || (confirmedServer != null && confirmedServer != stamp)) {
                    throw MainServerConnectedException(stamp)
                }
            }
            // Open and initialise drive_sync.db before retiring the server, so a
            // storage failure here leaves the existing connection untouched.
            val db = store
            db.withTransaction { ensureInstall(db, now) }
            val previous = db.accounts().active()?.takeIf { it.permissionId != permissionId }
            try {
                if (previous != null) {
                    db.withTransaction {
                        db.accounts().pauseAllActive()
                        db.accounts().markPendingSwitch(previous.permissionId, previous.state)
                    }
                    stopWorkers()
                    if (epubTransfersLazy.isInitialized()) {
                        epubTransfers.cancelAndDrain()
                    }
                }
                if (remoteAccount.current() != null) {
                    beforeServerDisconnect()
                    remoteAccount.disconnect(preserveHistoryStubs = true) { retiringServer, preservedStubs ->
                        capturePreservedStubAliases(permissionId, retiringServer, preservedStubs, now)
                    }
                }
                db.withTransaction {
                    val previousPrimary = previous ?: db.accounts().all().firstOrNull { it.pendingSwitchPreviousState != null }
                        ?: db.accounts().primary()
                    db.accounts().pauseAllActive()
                    db.accounts().clearPendingSwitches()
                    val existing = db.accounts().get(permissionId)
                    // ADR-0078: every switch, including back to an account used before, asks for a
                    // fresh selection. Reconnecting the account that was already primary keeps the
                    // reader's last answer.
                    val keepsSelection = previousPrimary == null || previousPrimary.permissionId == permissionId
                    if (!keepsSelection) {
                        db.uploads().holdSelectionsBefore(permissionId, now, now - 1)
                    }
                    val updated = existing?.copy(
                        email = email,
                        state = DriveAccount.STATE_ACTIVE,
                        connectedAt = now,
                        lastError = null,
                        uploadSelectionAt = existing.uploadSelectionAt.takeIf { keepsSelection },
                    ) ?: DriveAccount(
                        permissionId = permissionId,
                        email = email,
                        state = DriveAccount.STATE_ACTIVE,
                        connectedAt = now,
                    )
                    db.accounts().upsert(updated)
                    updated
                }
            } catch (e: Throwable) {
                if (previous != null) {
                    withContext(NonCancellable) {
                        if (epubTransfersLazy.isInitialized()) epubTransfers.cancelAndDrain()
                        val restored = db.withTransaction {
                            if (db.accounts().get(previous.permissionId)?.pendingSwitchPreviousState != previous.state) {
                                false
                            } else {
                                db.accounts().setState(previous.permissionId, previous.state)
                                db.accounts().clearPendingSwitches()
                                true
                            }
                        }
                        if (restored) resumeActiveWork()
                    }
                }
                throw e
            }
        }
    }

    /** Returns the per-install Drive device id, minting it once if absent. */
    internal suspend fun ensureInstall(
        db: DriveSyncDatabase = store,
        now: Long = System.currentTimeMillis(),
    ): DriveInstall {
        db.installs().get()?.let { return it }
        val fresh = DriveInstall(
            id = DriveInstall.SINGLE_ID,
            deviceId = deviceIdGenerator(),
            createdAt = now,
        )
        db.installs().insertIfAbsent(fresh)
        return requireNotNull(db.installs().get())
    }

    /**
     * Captures portable Drive `work_alias` entries for preserved fileless
     * main-server stubs before `forgetSyncPeer` wipes the retiring server's
     * aliases and before `unlinkDownloadedFromRemote` drops catalog metadata.
     */
    internal suspend fun capturePreservedStubAliases(
        permissionId: String,
        retiringServer: RemoteServer,
        preservedStubs: List<Book>,
        now: Long,
    ) {
        if (preservedStubs.isEmpty()) return
        val drivePeerId = accountPeerId(permissionId)
        val identities = database.workIdentityDao()
        val metadataDao = database.bookMetadataDao()
        for (book in preservedStubs) {
            // Once a book has a frozen Drive key for this account, never rekey it.
            if (identities.alias(book.url, drivePeerId) != null) continue
            val retiringAlias = identities.alias(book.url, retiringServer.accountKey)
            val retiringSha = retiringAlias?.editionSha?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            val metadataRows = metadataDao.forBook(book.url)
            val workKey = preservedStubWorkKey(book, metadataRows, retiringSha)
            identities.upsert(
                WorkAlias(
                    bookUrl = book.url,
                    peerId = drivePeerId,
                    workId = workKey,
                    confidence = WorkAlias.HIGH,
                    confirmed = true,
                    seeded = false,
                    editionSha = retiringSha,
                    resolvedAt = now,
                ),
            )
        }
    }

    companion object {
        /** Play-only peer id for [com.chmouel.liseur.data.remote.CompositePositionSync]. */
        const val PEER_ID = "drive"

        /** Account-scoped peer key used in `liseur.db` reconciliation tables. */
        fun accountPeerId(permissionId: String): String = "$PEER_ID:$permissionId"

        /**
         * Derives the portable `workKey` for a preserved fileless stub on
         * Server → Drive switch per ADR-0078:
         * 1. `isbn:<isbn13>` from `books.work_id` or `book_metadata(file|catalog).identifiers`
         * 2. `dc:<sha256hex(trim.lowercase(id))>` from usable `dc:identifier`
         * 3. `sha:<edition_sha>` from the retiring server's `work_alias.edition_sha`
         * 4. `stub:<sha256hex("liseur-stub|v1|" + book.url)>` fallback
         */
        internal fun preservedStubWorkKey(
            book: Book,
            metadataRows: List<com.chmouel.liseur.data.db.BookMetadata>,
            retiringEditionSha: String?,
        ): String {
            val candidates = buildList {
                WorkIdentifiers.dcFrom(
                    storedWorkId = book.workId,
                    title = book.fileTitle?.takeIf { it.isNotBlank() } ?: book.title,
                    author = book.identityAuthor ?: book.stableAuthor,
                )?.let(::add)
                val bySource = metadataRows.associateBy { it.source }
                listOf(MetadataSource.FILE.key, MetadataSource.CATALOG.key).forEach { source ->
                    bySource[source]?.toSnapshot()?.identifiers?.forEach { identifier ->
                        if (identifier.value.isNotBlank() &&
                            identifier.value.trim().lowercase(Locale.ROOT) !in USELESS_IDENTIFIERS
                        ) {
                            add(schemeQualified(identifier.scheme, identifier.value))
                        }
                    }
                }
            }.map { candidate -> schemeQualified(null, candidate).lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() && it !in USELESS_IDENTIFIERS }
            for (candidate in candidates) {
                Isbn.from(candidate)?.let { return "isbn:${it.isbn13}" }
            }
            candidates.firstOrNull()?.let { return "dc:${sha256Hex(it)}" }
            retiringEditionSha?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let {
                return "sha:$it"
            }
            return "stub:${sha256Hex("liseur-stub|v1|${book.url}")}"
        }

        /**
         * An identifier with the scheme it was declared under kept in front
         * (`urn:uuid:<value>`), so two schemes sharing one value cannot collapse
         * into the same `dc:` key and a value read from `book_metadata`
         * hashes the same as the raw `dc:identifier` in `books.work_id`.
         *
         * Shared with the work-identity resolver so a downloaded book and
         * a preserved stub derive one identity for one identifier.
         */
        internal fun schemeQualified(scheme: String?, value: String): String {
            val qualified = qualify(scheme, value)
            return if (qualified.startsWith(BARE_UUID_PREFIX, ignoreCase = true)) {
                "urn:uuid:" + qualified.substring(BARE_UUID_PREFIX.length)
            } else {
                qualified
            }
        }

        private fun qualify(scheme: String?, value: String): String {
            val trimmed = value.trim()
            val declared = scheme?.trim()?.lowercase(Locale.ROOT)?.removePrefix("urn:").orEmpty()
            if (declared.isEmpty()) {
                val split = identifierOf(trimmed) ?: return trimmed
                return if (split.scheme.isEmpty()) trimmed else qualify(split.scheme, split.value)
            }
            val lowered = trimmed.lowercase(Locale.ROOT)
            if (lowered.startsWith("$declared:") || lowered.startsWith("urn:$declared:")) return trimmed
            return "urn:$declared:$trimmed"
        }

        private fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return buildString(digest.size * 2) {
                for (byte in digest) {
                    val unsigned = byte.toInt() and 0xff
                    append(HEX_CHARS[unsigned ushr 4])
                    append(HEX_CHARS[unsigned and 0x0f])
                }
            }
        }

        private const val BARE_UUID_PREFIX = "uuid:"
        private const val HEX_CHARS = "0123456789abcdef"
    }
}

/** Raised when Drive activation would disconnect a sync server the reader was not asked about. */
internal class MainServerConnectedException(val server: ServerConnectionStamp) :
    IllegalStateException("A sync server connected while Google Drive activation was pending")

/** Identifies one main-server connection, so consent to disconnect it cannot cover a replacement. */
data class ServerConnectionStamp(
    val kind: ServerKind,
    val baseUrl: String,
    val accountId: String?,
    val addedAt: Long,
    val orbitEpoch: Long,
) {
    constructor(server: RemoteServer) :
        this(server.kind, server.baseUrl, server.accountId, server.addedAt, server.orbitEpoch)
}
