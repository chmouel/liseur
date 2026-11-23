package com.chmouel.liseur.drive

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.room.withTransaction
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.remote.PeerPositionSync
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.SyncIdentity
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.data.remote.SyncSnapshot
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.USELESS_IDENTIFIERS
import com.chmouel.liseur.domain.WorkIdentifiers
import com.chmouel.liseur.hardcover.Isbn
import com.chmouel.liseur.sync.LibrarySyncBackend
import com.chmouel.liseur.sync.LibrarySyncSwitchCoordinator
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    private val switchCoordinator: LibrarySyncSwitchCoordinator = LibrarySyncSwitchCoordinator(),
    private val beforeServerDisconnect: suspend () -> Unit = {},
    private val stopWorkers: () -> Unit = {},
    private val onResumeActive: () -> Unit = {},
    private val deviceIdGenerator: () -> String = { UUID.randomUUID().toString().lowercase(Locale.ROOT) },
    private val openStore: (() -> DriveSyncDatabase)? = null,
    authClient: DriveAuthClient? = null,
    driveApi: DriveRemoteApi? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : LibrarySyncBackend {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    override suspend fun deactivate() {
        stopWorkers()
        if (!hasStore()) return
        store.accounts().pauseAllActive()
    }

    override fun resume() {
        if (!hasStore()) return
        scope.launch {
            runCatching {
                if (store.accounts().active() != null) onResumeActive()
            }
        }
    }

    override val positionPeer: PeerPositionSync = DrivePositionPeer()

    @Composable
    override fun SettingsEntry(onOpen: () -> Unit) {
        DriveSettingsEntry(controller = accountController, onOpen = onOpen)
    }

    @Composable
    override fun SettingsSection() {
        DriveSettingsSection(controller = accountController)
    }

    /** Stops background workers and transitions [permissionId] to `NEEDS_RECONNECT` while preserving all state. */
    internal suspend fun markNeedsReconnect(permissionId: String, reason: String) {
        stopWorkers()
        if (!hasStore()) return
        store.withTransaction {
            store.accounts().setState(permissionId, DriveAccount.STATE_NEEDS_RECONNECT)
            store.accounts().recordError(permissionId, reason)
        }
    }

    /**
     * Restores a previously saved account state after cancelling a pending switch,
     * while upholding the mutual exclusion invariant with any connected main server.
     */
    internal suspend fun restoreAccountState(permissionId: String, state: String) {
        if (!hasStore()) return
        val restoredActive = switchCoordinator.withSwitchLock {
            if (state == DriveAccount.STATE_ACTIVE && remoteAccount.current() != null) {
                return@withSwitchLock false
            }
            store.withTransaction {
                if (state == DriveAccount.STATE_ACTIVE) {
                    store.accounts().pauseAllActive()
                }
                store.accounts().setState(permissionId, state)
            }
            state == DriveAccount.STATE_ACTIVE
        }
        if (restoredActive) onResumeActive()
    }

    internal fun notifyActivated() {
        onResumeActive()
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
     * transitioned to `PAUSED` with all of its state preserved intact.
     */
    suspend fun activateAccount(
        permissionId: String,
        email: String,
        now: Long = System.currentTimeMillis(),
    ): DriveAccount {
        require(permissionId.isNotBlank()) { "Drive permissionId must not be blank" }
        return switchCoordinator.withSwitchLock {
            if (remoteAccount.current() != null) {
                beforeServerDisconnect()
                remoteAccount.disconnect(preserveHistoryStubs = true) { retiringServer, preservedStubs ->
                    capturePreservedStubAliases(permissionId, retiringServer, preservedStubs, now)
                }
            }
            val db = store
            db.withTransaction {
                ensureInstall(db, now)
                db.accounts().pauseAllActive()
                val existing = db.accounts().get(permissionId)
                val updated = existing?.copy(
                    email = email,
                    state = DriveAccount.STATE_ACTIVE,
                    connectedAt = now,
                    lastError = null,
                ) ?: DriveAccount(
                    permissionId = permissionId,
                    email = email,
                    state = DriveAccount.STATE_ACTIVE,
                    connectedAt = now,
                )
                db.accounts().upsert(updated)
                updated
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

    /**
     * Play-only position-sync peer attached after kosync in
     * `CompositePositionSync`. Answers `NotApplicable` / `NotSynced` while
     * inactive (and until Phase 3 wires Drive position reconciliation).
     */
    private class DrivePositionPeer : PeerPositionSync {
        override val peerId: String get() = PEER_ID

        override suspend fun dialledAddress(): String? = null

        override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome = SyncOutcome.NotApplicable

        override suspend fun syncBook(bookUrl: String): SyncOutcome = SyncOutcome.NotApplicable

        override suspend fun canSync(bookUrl: String): Boolean = false

        override suspend fun previewBook(bookUrl: String): PreviewOutcome = PreviewOutcome.NotSynced

        override suspend fun preservedConflict(bookUrl: String, peerId: String?): SyncPreview? = null

        override suspend fun takeRemotePosition(
            bookUrl: String,
            atRevision: Long,
            peerId: String?,
            expectedAccountKey: String?,
        ): ResolveOutcome = if (expectedAccountKey != null) ResolveOutcome.Superseded else ResolveOutcome.Done

        override suspend fun keepLocalPosition(bookUrl: String, peerId: String?): ResolveOutcome = ResolveOutcome.Done

        override suspend fun refreshUnresolved() = Unit

        override suspend fun identity(): SyncIdentity? = null
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
                    title = book.stableTitle,
                    author = book.identityAuthor ?: book.stableAuthor,
                )?.let(::add)
                val bySource = metadataRows.associateBy { it.source }
                listOf(MetadataSource.FILE.key, MetadataSource.CATALOG.key).forEach { source ->
                    bySource[source]?.toSnapshot()?.identifiers?.forEach { identifier ->
                        if (identifier.value.isNotBlank()) add(identifier.value)
                    }
                }
            }
            for (candidate in candidates) {
                Isbn.from(candidate)?.let { return "isbn:${it.isbn13}" }
            }
            for (candidate in candidates) {
                val normalized = candidate.trim().lowercase(Locale.ROOT)
                if (normalized.isNotEmpty() && normalized !in USELESS_IDENTIFIERS) {
                    return "dc:${sha256Hex(normalized)}"
                }
            }
            retiringEditionSha?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let {
                return "sha:$it"
            }
            return "stub:${sha256Hex("liseur-stub|v1|${book.url}")}"
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

        private const val HEX_CHARS = "0123456789abcdef"
    }
}
