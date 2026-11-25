package com.chmouel.liseur.drive

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerCapabilities
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.ServerSetup
import com.chmouel.liseur.data.remote.SetupFailure
import com.chmouel.liseur.data.remote.SetupResult
import com.chmouel.liseur.domain.Identifier
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.workIdOf
import com.chmouel.liseur.sync.LibrarySyncSwitchCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DriveLibrarySyncTest {

    private lateinit var context: Application
    private lateinit var liseurDb: LiseurDatabase
    private lateinit var driveDb: DriveSyncDatabase
    private lateinit var remoteAccount: RemoteAccountRepository
    private lateinit var driveSync: DriveLibrarySync
    private lateinit var switchCoordinator: LibrarySyncSwitchCoordinator
    private val serverDisconnectHooks = AtomicInteger(0)
    private val driveWorkersStopped = AtomicInteger(0)

    @Volatile
    private var onBooksForgetting: suspend (List<String>) -> Unit = {}

    /** Runs inside the transfer manager between fingerprinting a book and writing its row. */
    @Volatile
    private var beforeQueueWrite: suspend () -> Unit = {}

    /** The metered-transfer allowance each EPUB upload worker was scheduled with. */
    private val uploadWorkScheduledWith = CopyOnWriteArrayList<Boolean>()

    @Before
    fun open() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        context = ApplicationProvider.getApplicationContext()
        liseurDb = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        driveDb = Room.inMemoryDatabaseBuilder(context, DriveSyncDatabase::class.java).build()
        switchCoordinator = LibrarySyncSwitchCoordinator()
        serverDisconnectHooks.set(0)
        driveWorkersStopped.set(0)
        uploadWorkScheduledWith.clear()

        val removal = BookRemoval(
            bookDao = liseurDb.bookDao(),
            sessionDao = liseurDb.readingSessionDao(),
            peerStateDao = liseurDb.syncPeerStateDao(),
            identityDao = liseurDb.workIdentityDao(),
            progressDao = liseurDb.readingProgressDao(),
            annotationDao = liseurDb.annotationDao(),
            annotationSyncDao = liseurDb.annotationSyncDao(),
            evaluationDao = liseurDb.bookEvaluationDao(),
            metadataDao = liseurDb.bookMetadataDao(),
            inTransaction = { work -> liseurDb.withTransaction { work() } },
        )
        lateinit var syncRef: DriveLibrarySync
        remoteAccount = RemoteAccountRepository(
            dao = liseurDb.remoteServerDao(),
            bookDao = liseurDb.bookDao(),
            progressDao = liseurDb.readingProgressDao(),
            bookRemoval = removal,
            seriesExtraDao = liseurDb.seriesExtraDao(),
            peerStateDao = liseurDb.syncPeerStateDao(),
            furthestDao = liseurDb.furthestPositionDao(),
            identityDao = liseurDb.workIdentityDao(),
            sessionDao = liseurDb.readingSessionDao(),
            annotationSyncDao = liseurDb.annotationSyncDao(),
            evaluationDao = liseurDb.bookEvaluationDao(),
            metadataOverrideDao = liseurDb.bookMetadataOverrideDao(),
            setups = mapOf(
                ServerKind.LISEUR_SYNC to FakeSetup(
                    SetupResult.Success(
                        ServerCapabilities(
                            baseUrl = "https://sync.example",
                            canDownload = true,
                            accountId = "dev-1",
                            liseurAccountId = "acct-1",
                            displayName = "reader",
                            liseurToken = "tok-1",
                        ),
                    ),
                ),
                ServerKind.KOMGA to FakeSetup(
                    SetupResult.Success(
                        ServerCapabilities(
                            baseUrl = "https://komga.example",
                            canDownload = true,
                            accountId = "komga-1",
                            displayName = "reader",
                        ),
                    ),
                ),
                ServerKind.CUSTOM to FakeSetup(
                    SetupResult.Failure(SetupFailure.Unreachable("offline", false)),
                ),
            ),
            inTransaction = { work -> removal.together(work) },
            beforeMainServerChange = { syncRef.deactivate() },
            switchCoordinator = switchCoordinator,
        )
        syncRef = DriveLibrarySync(
            context = context,
            database = liseurDb,
            remoteAccount = remoteAccount,
            switchCoordinator = switchCoordinator,
            beforeServerDisconnect = { serverDisconnectHooks.incrementAndGet() },
            stopWorkers = { driveWorkersStopped.incrementAndGet() },
            beforeQueueWrite = { beforeQueueWrite() },
            appendEpubUploadsWork = { allowMetered -> uploadWorkScheduledWith.add(allowMetered) },
            deviceIdGenerator = { "11111111-2222-4333-8444-555555555555" },
            openStore = { driveDb },
            bookRemoval = BookRemoval(
                bookDao = liseurDb.bookDao(),
                sessionDao = liseurDb.readingSessionDao(),
                peerStateDao = liseurDb.syncPeerStateDao(),
                identityDao = liseurDb.workIdentityDao(),
                progressDao = liseurDb.readingProgressDao(),
                annotationDao = liseurDb.annotationDao(),
                annotationSyncDao = liseurDb.annotationSyncDao(),
                evaluationDao = liseurDb.bookEvaluationDao(),
                metadataDao = liseurDb.bookMetadataDao(),
                inTransaction = { work -> liseurDb.withTransaction { work() } },
                onBooksForgetting = { onBooksForgetting(it) },
            ),
        )
        driveSync = syncRef
    }

    @After
    fun close() {
        driveDb.close()
        liseurDb.close()
        CredentialCipher.keyForTesting = null
    }

    @Test
    fun `server to drive switch disconnects main server, keeps browse catalogs and downloaded or history stubs, and captures portable Drive work_alias keys`() = runTest {
        assertTrue(remoteAccount.connectLiseurSync("https://sync.example", "reader", "pw") is SetupResult.Success)
        val server = requireNotNull(remoteAccount.current())

        // Browse-only server (id > 1) and its preview book.
        val browseId = liseurDb.remoteServerDao().nextBrowseId()
        liseurDb.remoteServerDao().upsert(
            RemoteServer(
                id = browseId,
                kind = ServerKind.CUSTOM,
                baseUrl = "https://browse.example",
                catalogUrl = "https://browse.example/opds",
                username = "",
                passwordCipher = null,
                apiKeyCipher = null,
                accountId = null,
                userId = null,
                koboTokenCipher = null,
                canDownload = true,
                addedAt = 1L,
                catalogSyncedAt = null,
                positionSyncedAt = null,
                syncToken = null,
            ),
        )
        liseurDb.bookDao().upsert(
            Book(
                url = "browse:$browseId:item",
                title = "Browse Item",
                author = null,
                coverPath = null,
                source = null,
                addedAt = 1L,
                lastOpenedAt = null,
                remoteUuid = "browse-uuid",
                browseServerId = browseId,
                downloadState = DownloadState.REMOTE,
            ),
        )

        // Untouched preview stub (should be removed).
        liseurDb.bookDao().upsert(stub("sync:untouched"))

        // Downloaded book (should stay downloaded, unlinked).
        liseurDb.bookDao().upsert(
            Book(
                url = "sync:downloaded",
                title = "Downloaded",
                author = "Author",
                coverPath = null,
                source = null,
                addedAt = 1L,
                lastOpenedAt = null,
                localUri = "file:///book.epub",
                remoteUuid = "remote-dl",
                downloadState = DownloadState.DOWNLOADED,
            ),
        )

        // Preserved stub 1: ISBN-10 in catalog metadata -> normalized isbn:<isbn13>.
        liseurDb.bookDao().upsert(stub("sync:stub-isbn").copy(archivedAt = 10L))
        liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(
                bookUrl = "sync:stub-isbn",
                source = MetadataSource.CATALOG,
                snapshot = MetadataSnapshot(identifiers = listOf(Identifier("isbn", "0-261-10221-4"))),
            ),
        )

        // Preserved stub 2: usable dc:identifier in books.work_id -> dc:<sha256>.
        liseurDb.bookDao().upsert(
            stub("sync:stub-dc").copy(workId = "urn:uuid:12345678-1234-1234-1234-1234567890ab", titleOverridden = true, userTitle = "Edited"),
        )

        // Preserved stub 3: retiring server work_alias.edition_sha -> sha:<edition_sha>.
        liseurDb.bookDao().upsert(stub("sync:stub-sha"))
        liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = "sync:stub-sha",
                startedAt = 100L,
                endedAt = 200L,
                lastCheckpointAt = 200L,
                durationMs = 100L,
                startProgression = 0.0,
                endProgression = 0.2,
            ),
        )
        liseurDb.workIdentityDao().upsert(
            WorkAlias(
                bookUrl = "sync:stub-sha",
                peerId = server.accountKey,
                workId = "srv-work-3",
                confidence = WorkAlias.HIGH,
                confirmed = true,
                editionSha = "ABCDEF012345",
                resolvedAt = 50L,
            ),
        )

        // Preserved stub 4: no ISBN, no usable DC, no edition_sha -> deterministic stub:<sha256>.
        liseurDb.bookDao().upsert(stub("sync:stub-fallback").copy(finishedAt = 200L))

        val account = driveSync.activateAccount(permissionId = "perm-1", email = "reader@example.com", now = 500L)

        // Mutual exclusion & server disconnect checks.
        assertEquals(1, serverDisconnectHooks.get())
        assertNull(remoteAccount.current())
        assertTrue(driveSync.isActive())
        assertTrue(driveSync.active.first())
        assertEquals(DriveAccount.STATE_ACTIVE, account.state)
        assertEquals("11111111-2222-4333-8444-555555555555", driveDb.installs().get()?.deviceId)

        // Browse-only server & books untouched.
        assertEquals(1, liseurDb.remoteServerDao().browseServers().size)
        assertNotNull(liseurDb.bookDao().getByUrl("browse:$browseId:item"))

        // Untouched preview removed; downloaded book kept.
        assertNull(liseurDb.bookDao().getByUrl("sync:untouched"))
        assertNotNull(liseurDb.bookDao().getByUrl("sync:downloaded"))

        // Retiring server work_alias was cleared by forgetSyncPeer, while Drive work_alias rows were captured first.
        assertNull(liseurDb.workIdentityDao().alias("sync:stub-sha", server.accountKey))

        val drivePeer = DriveLibrarySync.accountPeerId("perm-1")
        val isbnAlias = requireNotNull(liseurDb.workIdentityDao().alias("sync:stub-isbn", drivePeer))
        assertEquals("isbn:9780261102217", isbnAlias.workId)
        assertEquals(WorkAlias.HIGH, isbnAlias.confidence)
        assertTrue(isbnAlias.confirmed)
        assertFalse(isbnAlias.seeded)

        val dcAlias = requireNotNull(liseurDb.workIdentityDao().alias("sync:stub-dc", drivePeer))
        assertTrue(dcAlias.workId.startsWith("dc:"))

        val shaAlias = requireNotNull(liseurDb.workIdentityDao().alias("sync:stub-sha", drivePeer))
        assertEquals("sha:abcdef012345", shaAlias.workId)
        assertEquals("abcdef012345", shaAlias.editionSha)
        assertEquals(1, liseurDb.readingSessionDao().countForBook("sync:stub-sha"))

        val fallbackAlias = requireNotNull(liseurDb.workIdentityDao().alias("sync:stub-fallback", drivePeer))
        assertTrue(fallbackAlias.workId.startsWith("stub:"))
    }

    @Test
    fun `failed drive_sync db open leaves the main server connection and its books untouched`() = runTest {
        assertTrue(remoteAccount.connectLiseurSync("https://sync.example", "reader", "pw") is SetupResult.Success)
        val server = requireNotNull(remoteAccount.current())
        liseurDb.bookDao().upsert(stub("sync:untouched"))
        val disconnects = AtomicInteger(0)
        val failing = DriveLibrarySync(
            context = context,
            database = liseurDb,
            remoteAccount = remoteAccount,
            switchCoordinator = switchCoordinator,
            beforeServerDisconnect = { disconnects.incrementAndGet() },
            stopWorkers = {},
            deviceIdGenerator = { "11111111-2222-4333-8444-555555555555" },
            openStore = { error("disk full") },
        )

        val failure = runCatching { failing.activateAccount("perm-a", "a@example.com", now = 100L) }

        assertTrue(failure.isFailure)
        assertEquals(0, disconnects.get())
        assertEquals(server.id, remoteAccount.current()?.id)
        assertNotNull(liseurDb.bookDao().getByUrl("sync:untouched"))
    }

    @Test
    fun `drive to server switch pauses Drive while preserving Drive state, and failed probe leaves Drive active`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        driveDb.accounts().updateStartPageToken("perm-a", "page-tok-1")
        driveDb.cursors().upsert(DriveCursor("perm-a", "dev-remote", "annotation", appliedSeq = 7))
        assertTrue(driveSync.isActive())

        // Failed Custom probe must not deactivate Drive.
        val failedCustom = remoteAccount.connectCustom(
            catalogUrl = "https://unreachable.example/opds",
            username = "",
            password = "",
            kosyncUrl = "",
            kosyncUsername = "",
            kosyncPassword = "",
        )
        assertFalse(failedCustom.connected)
        assertTrue(driveSync.isActive())
        assertEquals(0, driveWorkersStopped.get())

        // Successful Komga connect deactivates Drive (PAUSED) and preserves its state.
        assertTrue(remoteAccount.connectKomga("https://komga.example", "key") is SetupResult.Success)
        assertEquals(1, driveWorkersStopped.get())
        assertFalse(driveSync.isActive())
        assertFalse(driveSync.active.first())
        assertNotNull(remoteAccount.current())

        val paused = requireNotNull(driveDb.accounts().get("perm-a"))
        assertEquals(DriveAccount.STATE_PAUSED, paused.state)
        assertEquals("page-tok-1", paused.startPageToken)
        assertEquals(7L, driveDb.cursors().get("perm-a", "dev-remote", "annotation")?.appliedSeq)
    }

    @Test
    fun `switching Google accounts pauses account A with its state intact and allows clean resume`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        driveDb.accounts().updateStartPageToken("perm-a", "tok-a")
        driveDb.accounts().updateFolderId("perm-a", "folder-a")
        driveDb.uploads().upsert(
            DriveUpload("perm-a", "sha-1", "file:///a.epub", 1000L, DriveUpload.STATE_PENDING, selectedAt = 100L, updatedAt = 100L),
        )

        driveSync.activateAccount("perm-b", "b@example.com", now = 200L)
        assertEquals("perm-b", driveDb.accounts().active()?.permissionId)
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-a")?.state)
        assertEquals("tok-a", driveDb.accounts().get("perm-a")?.startPageToken)
        assertEquals(1, driveDb.uploads().pendingForAccount("perm-a").size)
        assertTrue(driveDb.uploads().pendingForAccount("perm-b").isEmpty())

        // Switching back to Account A resumes with its saved cursor, folder, and upload queue intact.
        val resumed = driveSync.activateAccount("perm-a", "a-renamed@example.com", now = 300L)
        assertEquals(DriveAccount.STATE_ACTIVE, resumed.state)
        assertEquals("a-renamed@example.com", resumed.email)
        assertEquals("tok-a", resumed.startPageToken)
        assertEquals("folder-a", resumed.folderId)
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-b")?.state)
        assertEquals(1, driveDb.uploads().pendingForAccount("perm-a").size)
    }

    @Test
    fun `overlapping server publish and Drive activation serialize via shared coordinator and never leave both active`() = runTest {
        val removal = BookRemoval(
            bookDao = liseurDb.bookDao(),
            sessionDao = liseurDb.readingSessionDao(),
            peerStateDao = liseurDb.syncPeerStateDao(),
            identityDao = liseurDb.workIdentityDao(),
            progressDao = liseurDb.readingProgressDao(),
            annotationDao = liseurDb.annotationDao(),
            annotationSyncDao = liseurDb.annotationSyncDao(),
            evaluationDao = liseurDb.bookEvaluationDao(),
            metadataDao = liseurDb.bookMetadataDao(),
            inTransaction = { work -> liseurDb.withTransaction { work() } },
        )

        // Interleaving 1: Server publish finishes beforeMainServerChange() and pauses
        // before writing remote_server.id = 1 while Drive activation starts concurrently.
        val coordinator1 = LibrarySyncSwitchCoordinator()
        val serverDeactivatedDrive = CompletableDeferred<Unit>()
        val releaseServerPublish = CompletableDeferred<Unit>()
        val disconnectCount1 = AtomicInteger(0)
        lateinit var sync1: DriveLibrarySync
        val repo1 = RemoteAccountRepository(
            dao = liseurDb.remoteServerDao(),
            bookDao = liseurDb.bookDao(),
            progressDao = liseurDb.readingProgressDao(),
            bookRemoval = removal,
            seriesExtraDao = liseurDb.seriesExtraDao(),
            peerStateDao = liseurDb.syncPeerStateDao(),
            furthestDao = liseurDb.furthestPositionDao(),
            identityDao = liseurDb.workIdentityDao(),
            sessionDao = liseurDb.readingSessionDao(),
            annotationSyncDao = liseurDb.annotationSyncDao(),
            evaluationDao = liseurDb.bookEvaluationDao(),
            metadataOverrideDao = liseurDb.bookMetadataOverrideDao(),
            setups = mapOf(
                ServerKind.KOMGA to FakeSetup(
                    SetupResult.Success(
                        ServerCapabilities(
                            baseUrl = "https://komga.example",
                            canDownload = true,
                            accountId = "komga-1",
                            displayName = "reader",
                        ),
                    ),
                ),
                ServerKind.CUSTOM to FakeSetup(
                    SetupResult.Success(
                        ServerCapabilities(
                            baseUrl = "https://opds.example",
                            catalogUrl = "https://opds.example/feed",
                            canDownload = true,
                            accountId = null,
                            displayName = "",
                        ),
                    ),
                ),
            ),
            inTransaction = { work -> removal.together(work) },
            beforeMainServerChange = {
                sync1.deactivate()
                serverDeactivatedDrive.complete(Unit)
                releaseServerPublish.await()
            },
            switchCoordinator = coordinator1,
        )
        sync1 = DriveLibrarySync(
            context = context,
            database = liseurDb,
            remoteAccount = repo1,
            switchCoordinator = coordinator1,
            beforeServerDisconnect = { disconnectCount1.incrementAndGet() },
            openStore = { driveDb },
        )

        val serverJob1 = async(Dispatchers.Default) { repo1.connectKomga("https://komga.example", "key") }
        serverDeactivatedDrive.await()

        val driveJob1 = async(Dispatchers.Default) {
            sync1.activateAccount("perm-race-1", "race1@example.com", now = 1000L)
        }
        // Drive activation must stay blocked until Server finishes publishing under the shared lock.
        withContext(Dispatchers.Default) { delay(50) }
        assertFalse(driveJob1.isCompleted)
        assertFalse(sync1.isActive())

        releaseServerPublish.complete(Unit)
        assertTrue(serverJob1.await() is SetupResult.Success)
        assertEquals(DriveAccount.STATE_ACTIVE, driveJob1.await().state)

        // Drive acquired the lock after Server committed, disconnected Server, and became sole active backend.
        assertEquals(1, disconnectCount1.get())
        assertNull(repo1.current())
        assertTrue(sync1.isActive())

        // Interleaving 2: Drive activation holds the lock while disconnecting a main server
        // and pauses before committing ACTIVE Drive while Custom publish and backup restore race it.
        assertTrue(remoteAccount.connectKomga("https://komga.example", "key") is SetupResult.Success)
        val coordinator2 = LibrarySyncSwitchCoordinator()
        val driveMidDisconnect = CompletableDeferred<Unit>()
        val releaseDriveActivate = CompletableDeferred<Unit>()
        val serverDeactivateCalls2 = AtomicInteger(0)
        lateinit var sync2: DriveLibrarySync
        val repo2 = RemoteAccountRepository(
            dao = liseurDb.remoteServerDao(),
            bookDao = liseurDb.bookDao(),
            progressDao = liseurDb.readingProgressDao(),
            bookRemoval = removal,
            seriesExtraDao = liseurDb.seriesExtraDao(),
            peerStateDao = liseurDb.syncPeerStateDao(),
            furthestDao = liseurDb.furthestPositionDao(),
            identityDao = liseurDb.workIdentityDao(),
            sessionDao = liseurDb.readingSessionDao(),
            annotationSyncDao = liseurDb.annotationSyncDao(),
            evaluationDao = liseurDb.bookEvaluationDao(),
            metadataOverrideDao = liseurDb.bookMetadataOverrideDao(),
            setups = mapOf(
                ServerKind.CUSTOM to FakeSetup(
                    SetupResult.Success(
                        ServerCapabilities(
                            baseUrl = "https://opds.example",
                            catalogUrl = "https://opds.example/feed",
                            canDownload = true,
                            accountId = null,
                            displayName = "",
                        ),
                    ),
                ),
            ),
            inTransaction = { work -> removal.together(work) },
            beforeMainServerChange = {
                serverDeactivateCalls2.incrementAndGet()
                sync2.deactivate()
            },
            switchCoordinator = coordinator2,
        )
        sync2 = DriveLibrarySync(
            context = context,
            database = liseurDb,
            remoteAccount = repo2,
            switchCoordinator = coordinator2,
            beforeServerDisconnect = {
                driveMidDisconnect.complete(Unit)
                releaseDriveActivate.await()
            },
            openStore = { driveDb },
        )

        val driveJob2 = async(Dispatchers.Default) {
            sync2.activateAccount("perm-race-2", "race2@example.com", now = 2000L)
        }
        driveMidDisconnect.await()

        val customJob = async(Dispatchers.Default) {
            repo2.connectCustom(
                catalogUrl = "https://opds.example/feed",
                username = "",
                password = "",
                kosyncUrl = "",
                kosyncUsername = "",
                kosyncPassword = "",
            )
        }
        withContext(Dispatchers.Default) { delay(50) }
        assertFalse(customJob.isCompleted)
        assertEquals(0, serverDeactivateCalls2.get())

        releaseDriveActivate.complete(Unit)
        assertEquals(DriveAccount.STATE_ACTIVE, driveJob2.await().state)
        assertTrue(customJob.await().connected)

        // Custom connect ran after Drive committed, paused Drive, and became sole active backend.
        assertEquals(1, serverDeactivateCalls2.get())
        assertEquals(ServerKind.CUSTOM, repo2.current()?.kind)
        assertFalse(sync2.isActive())
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-race-2")?.state)

        // Interleaving 3: restoreBackup(nonNullServer) also serializes with concurrent Drive activation.
        val coordinator3 = LibrarySyncSwitchCoordinator()
        val restorePaused = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        val disconnectCount3 = AtomicInteger(0)
        lateinit var sync3: DriveLibrarySync
        val repo3 = RemoteAccountRepository(
            dao = liseurDb.remoteServerDao(),
            bookDao = liseurDb.bookDao(),
            progressDao = liseurDb.readingProgressDao(),
            bookRemoval = removal,
            seriesExtraDao = liseurDb.seriesExtraDao(),
            peerStateDao = liseurDb.syncPeerStateDao(),
            furthestDao = liseurDb.furthestPositionDao(),
            identityDao = liseurDb.workIdentityDao(),
            sessionDao = liseurDb.readingSessionDao(),
            annotationSyncDao = liseurDb.annotationSyncDao(),
            evaluationDao = liseurDb.bookEvaluationDao(),
            metadataOverrideDao = liseurDb.bookMetadataOverrideDao(),
            inTransaction = { work -> removal.together(work) },
            beforeMainServerChange = {
                sync3.deactivate()
                restorePaused.complete(Unit)
                releaseRestore.await()
            },
            switchCoordinator = coordinator3,
        )
        sync3 = DriveLibrarySync(
            context = context,
            database = liseurDb,
            remoteAccount = repo3,
            switchCoordinator = coordinator3,
            beforeServerDisconnect = { disconnectCount3.incrementAndGet() },
            openStore = { driveDb },
        )

        val backupServer = RemoteServer(
            kind = ServerKind.KOMGA,
            baseUrl = "https://komga.example",
            username = "reader",
            passwordCipher = null,
            apiKeyCipher = RemoteServer.seal("api-key"),
            accountId = "komga-1",
            userId = null,
            koboTokenCipher = null,
            canDownload = true,
            addedAt = 100L,
            catalogSyncedAt = null,
            positionSyncedAt = null,
            syncToken = null,
        )
        val restoreJob = async(Dispatchers.Default) { repo3.restoreBackup(backupServer) }
        restorePaused.await()

        val driveJob3 = async(Dispatchers.Default) {
            sync3.activateAccount("perm-race-3", "race3@example.com", now = 3000L)
        }
        withContext(Dispatchers.Default) { delay(50) }
        assertFalse(driveJob3.isCompleted)
        assertFalse(sync3.isActive())

        releaseRestore.complete(Unit)
        restoreJob.await()
        assertEquals(DriveAccount.STATE_ACTIVE, driveJob3.await().state)

        assertEquals(1, disconnectCount3.get())
        assertNull(repo3.current())
        assertTrue(sync3.isActive())
    }

    @Test
    fun `AppContainer initializes eager positionSync and lazy Play librarySync without creating drive_sync db on startup`() = runTest {
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(
                context,
                androidx.work.Configuration.Builder().setExecutor { it.run() }.build(),
            )
        }
        val dbFile = context.getDatabasePath(DriveSyncDatabase.NAME)
        dbFile.delete()

        val container = AppContainer(context)
        try {
            assertTrue(container.librarySync is DriveLibrarySync)
            assertEquals(DriveLibrarySync.PEER_ID, container.librarySync.positionPeer?.peerId)
            assertTrue(container.librarySync.supportsBookServerOption)
            assertFalse(container.librarySync.isActive())
            assertFalse(container.librarySync.active.first())
            container.librarySync.resume()

            // Never connecting Drive must not open or create drive_sync.db on disk.
            assertFalse(dbFile.exists())
        } finally {
            container.database.close()
        }
    }

    @Test
    fun `a catalog title does not turn a file title and author work id into a dc identity`() {
        val fileTitle = "The Hobbit"
        val author = "J. R. R. Tolkien"
        val book = Book(
            url = "file:///hobbit.epub",
            title = fileTitle,
            author = author,
            coverPath = null,
            source = null,
            addedAt = 1L,
            lastOpenedAt = null,
            fileTitle = fileTitle,
            fileAuthor = author,
            catalogTitle = "The Hobbit (Deluxe)",
            workId = workIdOf(null, fileTitle, author),
        )

        val key = DriveLibrarySync.preservedStubWorkKey(book, metadataRows = emptyList(), retiringEditionSha = null)

        assertTrue(key, key.startsWith("stub:"))
    }

    @Test
    fun `identifiers keep the scheme they were declared under and match their raw urn form`() {
        val base = Book(
            url = "file:///book.epub",
            title = "Book",
            author = "Author",
            coverPath = null,
            source = null,
            addedAt = 1L,
            lastOpenedAt = null,
        )
        fun keyFor(book: Book, vararg identifiers: Identifier): String {
            val metadata = identifiers.map {
                BookMetadata.of(
                    bookUrl = book.url,
                    source = MetadataSource.FILE,
                    snapshot = MetadataSnapshot(identifiers = listOf(it)),
                )
            }
            return DriveLibrarySync.preservedStubWorkKey(book, metadata, retiringEditionSha = null)
        }

        val asUuid = keyFor(base, Identifier("uuid", "12345678-1234-1234-1234-1234567890ab"))
        val asCalibre = keyFor(base, Identifier("calibre", "12345678-1234-1234-1234-1234567890ab"))

        assertTrue(asUuid, asUuid.startsWith("dc:"))
        assertTrue(asCalibre, asCalibre.startsWith("dc:"))
        assertNotEquals(asUuid, asCalibre)
        assertEquals(
            asUuid,
            keyFor(
                base.copy(workId = "urn:uuid:12345678-1234-1234-1234-1234567890ab"),
            ),
        )
    }

    @Test
    fun `scheme-qualified placeholder identifiers fall through to the stub key`() {
        val base = Book(
            url = "file:///book.epub",
            title = "Book",
            author = "Author",
            coverPath = null,
            source = null,
            addedAt = 1L,
            lastOpenedAt = null,
        )
        fun keyFor(vararg identifiers: Identifier) = DriveLibrarySync.preservedStubWorkKey(
            base,
            listOf(
                BookMetadata.of(
                    bookUrl = base.url,
                    source = MetadataSource.FILE,
                    snapshot = MetadataSnapshot(identifiers = identifiers.toList()),
                ),
            ),
            retiringEditionSha = null,
        )

        val placeholders = listOf("unknown", "calibre_id", " UNKNOWN ", "none")
        for (value in placeholders) {
            val key = keyFor(Identifier("isbn", value), Identifier("calibre", value))
            assertTrue("$value -> $key", key.startsWith("stub:"))
        }
        assertTrue(keyFor(Identifier("calibre", "calibre_id"), Identifier("uuid", "abc-1")).startsWith("dc:"))
    }

    @Test
    fun `blank identifier values in a stored metadata row fall through to the stub key`() {
        val base = Book(
            url = "file:///book.epub",
            title = "Book",
            author = "Author",
            coverPath = null,
            source = null,
            addedAt = 1L,
            lastOpenedAt = null,
        )
        val raw = BookMetadata(
            bookUrl = base.url,
            source = MetadataSource.FILE.key,
            identifiers = """[{"scheme":"calibre","value":""},{"scheme":"uuid","value":"  "}]""",
        )

        val key = DriveLibrarySync.preservedStubWorkKey(base, listOf(raw), retiringEditionSha = null)

        assertTrue(key, key.startsWith("stub:"))
    }

    @Test
    fun `equivalent uuid spellings derive one key while other schemes stay apart`() {
        val id = "12345678-1234-1234-1234-1234567890ab"
        val base = Book(
            url = "file:///book.epub",
            title = "Book",
            author = "Author",
            coverPath = null,
            source = null,
            addedAt = 1L,
            lastOpenedAt = null,
        )
        fun keyFor(scheme: String?, value: String) = DriveLibrarySync.preservedStubWorkKey(
            base,
            listOf(
                BookMetadata.of(
                    bookUrl = base.url,
                    source = MetadataSource.FILE,
                    snapshot = MetadataSnapshot(identifiers = listOf(Identifier(scheme.orEmpty(), value))),
                ),
            ),
            retiringEditionSha = null,
        )

        val canonical = keyFor("uuid", id)
        assertEquals(canonical, keyFor("uuid", "urn:uuid:$id"))
        assertEquals(canonical, keyFor("uuid", "uuid:$id"))
        assertEquals(canonical, keyFor("", "uuid:$id"))
        assertEquals(canonical, keyFor("", "URN:UUID:${id.uppercase()}"))
        assertNotEquals(canonical, keyFor("calibre", id))
        assertNotEquals(canonical, keyFor("calibre", "uuid:$id"))
    }

    @Test
    fun `deactivating under the switch lock cancels a delete holding the sync lock instead of deadlocking`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val book = stub("drive:isbn:111")
        liseurDb.bookDao().upsert(book)
        val outcome = CompletableDeferred<Result<Boolean>>()

        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                switchCoordinator.withSwitchLock {
                    launch { outcome.complete(runCatching { driveSync.deleteFromSyncedLibrary(book) }) }
                    // The delete now owns the sync lock and can only be waiting on the switch lock held here.
                    while (!driveSync.changeEngine.isSyncLockHeld) delay(1)
                    driveSync.deactivate()
                }
            }
        }

        assertTrue(outcome.await().exceptionOrNull() is CancellationException)
        assertNotNull(liseurDb.bookDao().getByUrl(book.url))
        assertTrue(driveDb.recordStates().forDomain("perm-a", DriveWireCodec.DOMAIN_CATALOG).isEmpty())
        assertFalse(driveSync.changeEngine.isSyncLockHeld)
    }

    @Test
    fun `cancelling a delete after the tombstone commit still removes the local copy`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val book = stub("drive:isbn:111")
        liseurDb.bookDao().upsert(book)
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        onBooksForgetting = {
            cleanupStarted.complete(Unit)
            releaseCleanup.await()
        }

        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                val delete = launch { runCatching { driveSync.deleteFromSyncedLibrary(book) } }
                cleanupStarted.await()
                assertTrue(
                    driveDb.recordStates().forDomain("perm-a", DriveWireCodec.DOMAIN_CATALOG)
                        .any { it.versionOp == DriveRecordState.OP_DEL },
                )
                val disconnect = launch { driveSync.deactivate() }
                delete.cancel()
                releaseCleanup.complete(Unit)
                disconnect.join()
                delete.join()
            }
        }

        assertNull(liseurDb.bookDao().getByUrl(book.url))
        assertTrue(
            driveDb.recordStates().forDomain("perm-a", DriveWireCodec.DOMAIN_CATALOG)
                .any { it.versionOp == DriveRecordState.OP_DEL },
        )
        assertFalse(driveSync.changeEngine.isSyncLockHeld)
    }

    @Test
    fun `deactivating under the switch lock cancels a conflict resolution holding the sync lock`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val conflict = DriveConflict(
            permissionId = "perm-a",
            conflictId = "c-1",
            domain = DriveWireCodec.DOMAIN_ANNOTATION,
            recordKey = "isbn:111",
            kind = DriveConflict.KIND_EDIT_EDIT,
            localFp = "l",
            remoteFp = "r",
            remoteDeviceId = "dev",
            remoteHlc = "h",
            state = DriveConflict.STATE_OPEN,
            openedAt = 1L,
        )
        val outcome = CompletableDeferred<Result<Unit>>()

        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                switchCoordinator.withSwitchLock {
                    launch {
                        outcome.complete(
                            runCatching {
                                driveSync.resolveConflicts(listOf(conflict), DriveConflictChoice.KEEP_LOCAL, syncAfter = false)
                            },
                        )
                    }
                    while (!driveSync.changeEngine.isSyncLockHeld) delay(1)
                    driveSync.deactivate()
                }
            }
        }

        assertTrue(outcome.await().exceptionOrNull() is CancellationException)
        assertFalse(driveSync.changeEngine.isSyncLockHeld)
    }

    @Test
    fun `a delete waiting on the switch lock does nothing when the account is paused meanwhile`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val book = stub("drive:isbn:111")
        liseurDb.bookDao().upsert(book)
        val outcome = CompletableDeferred<Boolean>()

        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                switchCoordinator.withSwitchLock {
                    launch { outcome.complete(driveSync.deleteFromSyncedLibrary(book)) }
                    while (!driveSync.changeEngine.isSyncLockHeld) delay(1)
                    driveDb.accounts().pauseAllActive()
                }
                assertFalse(outcome.await())
            }
        }

        assertNotNull(liseurDb.bookDao().getByUrl(book.url))
        assertTrue(driveDb.recordStates().forDomain("perm-a", DriveWireCodec.DOMAIN_CATALOG).isEmpty())
    }

    @Test
    fun `deleting a local edition keeps a stub, alias and history that another account still references`() = runTest {
        val peerA = DriveLibrarySync.accountPeerId("perm-a")
        val peerB = DriveLibrarySync.accountPeerId("perm-b")
        driveSync.activateAccount("perm-b", "b@example.com", now = 100L)
        val shared = stub("drive:isbn:111")
        liseurDb.bookDao().upsert(shared)
        liseurDb.workIdentityDao().upsert(
            WorkAlias(bookUrl = shared.url, peerId = peerB, workId = "isbn:111", confidence = WorkAlias.HIGH, confirmed = true, resolvedAt = 1L),
        )
        liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = shared.url,
                startedAt = 100L,
                endedAt = 200L,
                lastCheckpointAt = 200L,
                durationMs = 100L,
                startProgression = 0.0,
                endProgression = 0.2,
            ),
        )
        driveSync.activateAccount("perm-a", "a@example.com", now = 200L)
        val local = stub("local:edition").copy(localUri = "file:///edition.epub", downloadState = DownloadState.DOWNLOADED)
        liseurDb.bookDao().upsert(local)
        liseurDb.workIdentityDao().upsert(
            WorkAlias(bookUrl = local.url, peerId = peerA, workId = "isbn:111", confidence = WorkAlias.HIGH, confirmed = true, resolvedAt = 2L),
        )

        assertTrue(driveSync.deleteFromSyncedLibrary(local))

        assertNotNull(liseurDb.bookDao().getByUrl(local.url))
        assertEquals(WorkAlias.REJECTED, liseurDb.workIdentityDao().alias(local.url, peerA)?.confidence)
        assertNotNull(liseurDb.bookDao().getByUrl(shared.url))
        assertNotNull(liseurDb.workIdentityDao().alias(shared.url, peerB))
        assertEquals(1, liseurDb.readingSessionDao().countForBook(shared.url))
    }

    @Test
    fun `deleting from the synced library still removes a stub no other account references`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val stub = stub("drive:isbn:222")
        liseurDb.bookDao().upsert(stub)

        assertTrue(driveSync.deleteFromSyncedLibrary(stub))

        assertNull(liseurDb.bookDao().getByUrl(stub.url))
    }

    @Test
    fun `no upload target while inactive`() = runTest {
        assertNull(driveSync.uploadTarget.first())
    }

    @Test
    fun `a stale account key queues nothing`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val book = localBook("stale.epub")

        assertTrue(driveSync.enqueueUploads("drive:someone-else", listOf(book)).isEmpty())
        assertTrue(driveDb.uploads().forAccount("perm-a").isEmpty())
    }

    @Test
    fun `a valid account key queues the book and the target reflects it`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val book = localBook("valid.epub")

        assertEquals(
            setOf(book.url),
            driveSync.enqueueUploads(DriveLibrarySync.accountPeerId("perm-a"), listOf(book)),
        )
        assertEquals(DriveUpload.STATE_PENDING, driveDb.uploads().forAccount("perm-a").single().state)

        val target = requireNotNull(driveSync.uploadTarget.first { it != null })
        assertEquals(setOf(book.url), target.queued)
        assertEquals(100L, target.offerSince)
    }

    @Test
    fun `an unsettled selection exposes no offer until the picker is answered`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)

        val beforePicker = requireNotNull(driveSync.uploadTarget.first { it != null })
        assertFalse(beforePicker.selectionSettled)

        driveDb.accounts().setUploadSelectionAt("perm-a", 200L)

        val afterPicker = requireNotNull(driveSync.uploadTarget.first { it != null })
        assertTrue(afterPicker.selectionSettled)
        assertEquals(200L, afterPicker.offerSince)
    }

    @Test
    fun `upload target follows only the active account`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val queuedForA = localBook("account-a.epub")
        assertEquals(1, driveSync.epubTransfers.queueBooks("perm-a", listOf(queuedForA.url)).size)

        val targetA = requireNotNull(
            driveSync.uploadTarget.first { it?.accountKey == DriveLibrarySync.accountPeerId("perm-a") },
        )
        assertEquals(setOf(queuedForA.url), targetA.queued)

        driveSync.activateAccount("perm-b", "b@example.com", now = 200L)

        val targetB = requireNotNull(
            driveSync.uploadTarget.first { it?.accountKey == DriveLibrarySync.accountPeerId("perm-b") },
        )
        assertTrue(targetB.queued.isEmpty())
        assertEquals(setOf(queuedForA.url), driveDb.uploads().forAccount("perm-a").map { it.bookUrl }.toSet())
    }

    @Test
    fun `queueing one book leaves an earlier selection pending`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val a = localBook("picked.epub")
        val b = localBook("offered.epub")

        assertEquals(1, driveSync.epubTransfers.queueSelectedBooks("perm-a", setOf(a.url)))
        assertEquals(setOf(b.url), driveSync.epubTransfers.queueBooks("perm-a", listOf(b.url)))

        assertEquals(
            setOf(a.url, b.url),
            driveDb.uploads().forAccount("perm-a").map { it.bookUrl }.toSet(),
        )
    }

    @Test
    fun `an uploaded edition is not queued again`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val a = localBook("uploaded.epub")
        driveSync.epubTransfers.queueBooks("perm-a", listOf(a.url))
        val sha = driveDb.uploads().forAccount("perm-a").single().sha256
        driveDb.uploads().markUploaded("perm-a", sha, "file-1", 200L)

        assertTrue(driveSync.epubTransfers.queueBooks("perm-a", listOf(a.url)).isEmpty())
    }

    @Test
    fun `a picker answer landing mid-enqueue keeps the book out of the queue`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        driveDb.accounts().setUploadSelectionAt("perm-a", 200L)
        val offered = localBook("in-flight.epub")
        // The reader confirms an empty picker after the offer was computed but before it was written.
        beforeQueueWrite = { driveDb.accounts().setUploadSelectionAt("perm-a", 300L) }

        val queued = driveSync.enqueueUploads(
            DriveLibrarySync.accountPeerId("perm-a"),
            listOf(offered),
            expectedOfferSince = 200L,
        )

        assertEquals(emptySet<String>(), queued)
        assertTrue(driveDb.uploads().forAccount("perm-a").isEmpty())
    }

    @Test
    fun `an offer decided under the current answer still queues`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        driveDb.accounts().setUploadSelectionAt("perm-a", 200L)
        val offered = localBook("offered.epub")

        val queued = driveSync.enqueueUploads(
            DriveLibrarySync.accountPeerId("perm-a"),
            listOf(offered),
            expectedOfferSince = 200L,
        )

        assertEquals(setOf(offered.url), queued)
        assertEquals(DriveUpload.STATE_PENDING, driveDb.uploads().forAccount("perm-a").single().state)
    }

    /**
     * The answer and the rows it drops are one write: a worker that starts while the
     * selected books are still being queued must never find the new cutoff over a queue
     * that still holds a book the answer left out.
     */
    @Test
    fun `an answer drops the rows it leaves out even when queueing its selection fails`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val leftOut = localBook("left-out.epub")
        assertEquals(1, driveSync.epubTransfers.queueSelectedBooks("perm-a", setOf(leftOut.url)))
        // The queue survives a switch away and back; the answer does not.
        driveSync.activateAccount("perm-b", "b@example.com", now = 200L)
        driveSync.activateAccount("perm-a", "a@example.com", now = 300L)
        val kept = driveDb.uploads().forAccount("perm-a").single()
        driveDb.uploads().upsert(kept.copy(selectedAt = 1L, updatedAt = 1L))
        assertNull(driveDb.accounts().get("perm-a")?.uploadSelectionAt)

        val picked = localBook("picked.epub")
        beforeQueueWrite = { throw IllegalStateException("the queue write failed") }
        val failure = runCatching {
            driveSync.epubTransfers.queueSelectedBooks("perm-a", setOf(picked.url), 400L)
        }
        beforeQueueWrite = {}

        assertTrue(failure.isFailure)
        assertEquals(400L, driveDb.accounts().get("perm-a")?.uploadSelectionAt)
        assertEquals(emptyList<String>(), driveDb.uploads().pendingForAccount("perm-a").map { it.bookUrl })
    }

    @Test
    fun `an empty answer records the cutoff and empties the kept queue in one write`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val leftOut = localBook("left-out.epub")
        assertEquals(1, driveSync.epubTransfers.queueSelectedBooks("perm-a", setOf(leftOut.url)))
        driveSync.activateAccount("perm-b", "b@example.com", now = 200L)
        driveSync.activateAccount("perm-a", "a@example.com", now = 300L)
        val kept = driveDb.uploads().forAccount("perm-a").single()
        driveDb.uploads().upsert(kept.copy(selectedAt = 1L, updatedAt = 1L))

        assertEquals(0, driveSync.epubTransfers.queueSelectedBooks("perm-a", emptySet(), 400L))

        assertEquals(400L, driveDb.accounts().get("perm-a")?.uploadSelectionAt)
        assertTrue(driveDb.uploads().pendingForAccount("perm-a").isEmpty())
    }

    /**
     * The reader can reopen the picker and uncheck a book while the answer that checked it
     * is still fingerprinting. The later answer is the last word: the older one must not
     * write down the row it was working on.
     */
    @Test
    fun `an answer replaced while its book is fingerprinting queues nothing`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val unchecked = localBook("unchecked.epub")
        var answeredAgain = false
        beforeQueueWrite = {
            if (!answeredAgain) {
                answeredAgain = true
                driveSync.epubTransfers.queueSelectedBooks("perm-a", emptySet(), 300L)
            }
        }

        val queued = driveSync.epubTransfers.queueSelectedBooks("perm-a", setOf(unchecked.url), 200L)
        beforeQueueWrite = {}

        assertEquals(0, queued)
        assertEquals(300L, driveDb.accounts().get("perm-a")?.uploadSelectionAt)
        assertTrue(driveDb.uploads().forAccount("perm-a").isEmpty())
    }

    /** A file deleted in Drive stays deleted even where only another device's upload held the book. */
    @Test
    fun `deleting a file another device uploaded suppresses this device's edition`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        driveDb.accounts().setUploadSelectionAt("perm-a", 200L)
        val held = localBook("held-elsewhere.epub")
        val sha = requireNotNull(liseurDb.workIdentityDao().fingerprint(held.url)).sha256
        driveDb.files().upsert(
            DriveFile(
                permissionId = "perm-a",
                fileId = "file-b",
                name = "held-elsewhere.epub",
                kind = DriveFile.KIND_EPUB,
                sha256 = sha,
                trashed = false,
            ),
        )
        assertTrue(driveDb.uploads().forAccount("perm-a").isEmpty())
        advertiseEdition("perm-a", held, sha, "file-b")
        assertTrue(held.url in requireNotNull(driveSync.uploadTarget.first { it != null }).held)

        driveSync.catalogSync.handleDriveEpubRemoved("perm-a", "file-b") { "0000000000001-0000-dev" }

        val target = requireNotNull(driveSync.uploadTarget.first { it != null })
        assertTrue(held.url in target.withheld)
        assertFalse(held.url in target.held)
        assertEquals(DriveUpload.STATE_SUPPRESSED, driveDb.uploads().get("perm-a", sha)?.state)

        // Asking for this book by hand is still consent: the suppression is not a refusal.
        assertEquals(
            setOf(held.url),
            driveSync.enqueueUploads(DriveLibrarySync.accountPeerId("perm-a"), listOf(held)),
        )
        assertEquals(DriveUpload.STATE_PENDING, driveDb.uploads().get("perm-a", sha)?.state)
    }

    /**
     * A live file left behind by a cancelled upload is on no other device, so treating it as
     * held would keep the book out of the offer and hide the action that uploads it again.
     */
    @Test
    fun `only a live file an advertised catalog edition points at holds the book`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        driveDb.accounts().setUploadSelectionAt("perm-a", 200L)
        val book = localBook("orphan.epub")
        val sha = requireNotNull(liseurDb.workIdentityDao().fingerprint(book.url)).sha256
        driveDb.files().upsert(
            DriveFile(
                permissionId = "perm-a",
                fileId = "file-orphan",
                name = "orphan.epub",
                kind = DriveFile.KIND_EPUB,
                sha256 = sha,
                trashed = false,
            ),
        )

        assertFalse(book.url in requireNotNull(driveSync.uploadTarget.first { it != null }).held)

        advertiseEdition("perm-a", book, sha, "file-orphan")

        assertTrue(book.url in requireNotNull(driveSync.uploadTarget.first { it != null }).held)
    }

    /**
     * A corrected EPUB reindexed at the same path is a different set of bytes. The hash
     * cached for the file it replaced must not let an UPLOADED row hold the book and hide
     * the upload the current edition still needs.
     */
    @Test
    fun `reindexing a book discards the fingerprint of the file it replaced`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        driveDb.accounts().setUploadSelectionAt("perm-a", 200L)
        val book = localBook("corrected.epub")
        val sha = requireNotNull(liseurDb.workIdentityDao().fingerprint(book.url)).sha256
        liseurDb.bookDao().markDownloaded(book.url, book.url, downloadedAt = 10L, fileModifiedAt = 100L)
        liseurDb.workIdentityDao().upsert(BookFingerprintRow(book.url, sha, "md5", 1L, 100L, 1L))
        driveDb.uploads().upsert(
            DriveUpload(
                permissionId = "perm-a",
                sha256 = sha,
                bookUrl = book.url,
                sizeBytes = 1L,
                state = DriveUpload.STATE_UPLOADED,
                fileId = "file-corrected",
                selectedAt = 1L,
                updatedAt = 1L,
            ),
        )
        assertTrue(book.url in requireNotNull(driveSync.uploadTarget.first { it != null }).held)

        // The reader drops a corrected EPUB in at the same path.
        liseurDb.bookDao().markDownloaded(book.url, book.url, downloadedAt = 20L, fileModifiedAt = 200L)

        val target = requireNotNull(driveSync.uploadTarget.first { it != null })
        assertTrue(book.url !in target.held)
        assertTrue(book.url !in target.queued)
        assertTrue(book.url !in target.withheld)
    }

    /**
     * The reader can ask for a book while the deletion of its Drive copy is still on its way.
     * That request is theirs and outlives the deletion; only the automatic offer's own row is
     * cancelled, and what its upload started is still cleaned up.
     */
    @Test
    fun `a Drive deletion cancels the automatic offer's queued row but not a hand-picked one`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        driveDb.accounts().setUploadSelectionAt("perm-a", 200L)
        val peer = DriveLibrarySync.accountPeerId("perm-a")
        val automatic = localBook("automatic.epub")
        val byHand = localBook("by-hand.epub")
        val automaticSha = requireNotNull(liseurDb.workIdentityDao().fingerprint(automatic.url)).sha256
        val byHandSha = requireNotNull(liseurDb.workIdentityDao().fingerprint(byHand.url)).sha256
        assertEquals(setOf(automatic.url), driveSync.enqueueUploads(peer, listOf(automatic), expectedOfferSince = 200L))
        assertEquals(setOf(byHand.url), driveSync.enqueueUploads(peer, listOf(byHand)))
        // The automatic row's upload had already started when the old copy was deleted.
        driveDb.uploads().updateProgress("perm-a", automaticSha, DriveUpload.STATE_UPLOADING, "session-1", 10L, 210L)
        for ((sha, fileId) in listOf(automaticSha to "file-old-auto", byHandSha to "file-old-hand")) {
            driveDb.files().upsert(
                DriveFile(
                    permissionId = "perm-a",
                    fileId = fileId,
                    name = "deleted.epub",
                    kind = DriveFile.KIND_EPUB,
                    sha256 = sha,
                    trashed = false,
                ),
            )
        }

        driveSync.catalogSync.handleDriveEpubRemoved("perm-a", "file-old-auto") { "0000000000001-0000-dev" }
        driveSync.catalogSync.handleDriveEpubRemoved("perm-a", "file-old-hand") { "0000000000002-0000-dev" }

        val cancelled = requireNotNull(driveDb.uploads().get("perm-a", automaticSha))
        assertEquals(DriveUpload.STATE_SUPPRESSED, cancelled.state)
        // Its copy in Drive still has to be trashed, so the session stays for cleanup.
        assertEquals("session-1", cancelled.sessionUri)
        // The suppressed row's pending cleanup keeps the worker scheduled, as does the other book.
        assertEquals(2, driveDb.uploads().workCount("perm-a"))
        val kept = requireNotNull(driveDb.uploads().get("perm-a", byHandSha))
        assertEquals(DriveUpload.STATE_PENDING, kept.state)

        // Asking for the cancelled edition by hand keeps its session for cleanup, so the copy
        // that upload started is still trashed before a fresh one goes up.
        assertEquals(setOf(automatic.url), driveSync.enqueueUploads(peer, listOf(automatic)))
        val requeued = requireNotNull(driveDb.uploads().get("perm-a", automaticSha))
        assertEquals(DriveUpload.STATE_REQUEUE, requeued.state)
        assertEquals("session-1", requeued.sessionUri)
    }

    /** The offer's snapshot can predate the row, so the write itself has to refuse these states. */
    @Test
    fun `an automatic offer does not overwrite a suppressed or failed edition`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        driveDb.accounts().setUploadSelectionAt("perm-a", 200L)
        val deleted = localBook("deleted-in-drive.epub")
        val failed = localBook("failed.epub")
        for (book in listOf(deleted, failed)) {
            val sha = requireNotNull(liseurDb.workIdentityDao().fingerprint(book.url)).sha256
            driveSync.epubTransfers.queueBooks("perm-a", listOf(book.url))
            val state = if (book == deleted) DriveUpload.STATE_SUPPRESSED else DriveUpload.STATE_FAILED
            driveDb.uploads().transition("perm-a", sha, DriveUpload.STATE_PENDING, state, 300L)
        }

        val queued = driveSync.enqueueUploads(
            DriveLibrarySync.accountPeerId("perm-a"),
            listOf(deleted, failed),
            expectedOfferSince = 200L,
        )

        assertTrue(queued.isEmpty())
        assertEquals(
            mapOf(deleted.url to DriveUpload.STATE_SUPPRESSED, failed.url to DriveUpload.STATE_FAILED),
            driveDb.uploads().forAccount("perm-a").associate { it.bookUrl to it.state },
        )
    }

    /**
     * Queueing fingerprints the books first, and the reader can turn metered transfers off
     * while that runs. An empty queue reschedules nothing, so the worker this write
     * schedules has to carry the setting as it stands after the write.
     */
    @Test
    fun `the scheduled upload worker carries the metered setting read after the queue write`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        driveDb.accounts().setUploadSelectionAt("perm-a", 200L)
        driveDb.accounts().setMeteredTransfers("perm-a", true)
        val book = localBook("metered-while-queueing.epub")
        beforeQueueWrite = { driveDb.accounts().setMeteredTransfers("perm-a", false) }

        assertEquals(
            setOf(book.url),
            driveSync.enqueueUploads(DriveLibrarySync.accountPeerId("perm-a"), listOf(book), expectedOfferSince = 200L),
        )

        assertEquals(listOf(false), uploadWorkScheduledWith)
    }

    /**
     * A rejected Drive work match is the reader's answer about these bytes: an automatic
     * offer must neither promote it back to confirmed nor upload the book under it.
     */
    @Test
    fun `an automatic offer leaves a rejected work match rejected and queues nothing`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        driveDb.accounts().setUploadSelectionAt("perm-a", 200L)
        val book = localBook("refused-match.epub")
        val peer = DriveLibrarySync.accountPeerId("perm-a")
        liseurDb.workIdentityDao().upsert(
            WorkAlias(
                bookUrl = book.url,
                peerId = peer,
                workId = "isbn:999",
                confidence = WorkAlias.REJECTED,
                confirmed = false,
                resolvedAt = 2L,
            ),
        )

        assertTrue(driveSync.enqueueUploads(peer, listOf(book), expectedOfferSince = 200L).isEmpty())
        assertTrue(driveDb.uploads().forAccount("perm-a").isEmpty())
        assertEquals(WorkAlias.REJECTED, liseurDb.workIdentityDao().alias(book.url, peer)?.confidence)

        // Asking for this book by hand is still the reader's choice to make.
        assertEquals(setOf(book.url), driveSync.enqueueUploads(peer, listOf(book)))
        assertEquals(WorkAlias.HIGH, liseurDb.workIdentityDao().alias(book.url, peer)?.confidence)
    }

    @Test
    fun `a paused account writes no queued row`() = runTest {
        driveSync.activateAccount("perm-a", "a@example.com", now = 100L)
        val a = localBook("paused.epub")
        driveDb.accounts().setState("perm-a", DriveAccount.STATE_PAUSED)

        assertTrue(driveSync.epubTransfers.queueBooks("perm-a", listOf(a.url)).isEmpty())
        assertTrue(driveDb.uploads().forAccount("perm-a").isEmpty())
    }

    /** A local file book with a matching stored fingerprint, so no hashing is needed. */
    private suspend fun localBook(name: String): Book {
        val url = "file:///tmp/$name"
        val book = Book(
            url = url,
            title = name,
            author = null,
            coverPath = null,
            source = null,
            addedAt = 1_000L,
            lastOpenedAt = null,
            localUri = url,
            downloadState = DownloadState.DOWNLOADED,
        )
        liseurDb.bookDao().upsert(book)
        liseurDb.workIdentityDao().upsert(
            BookFingerprintRow(
                bookUrl = url,
                sha256 = name.hashCode().toString().padStart(64, '0'),
                partialMd5 = "md5",
                fileSize = 1,
                fileModifiedAt = null,
                computedAt = 1,
            ),
        )
        return book
    }

    /** Publishes the catalog edition a synced upload leaves behind for [book]. */
    private suspend fun advertiseEdition(permissionId: String, book: Book, sha: String, driveFileId: String) {
        val body = DriveCatalogBody(
            workKey = "sha256:$sha",
            title = book.title,
            author = book.author,
            identifiers = emptyList(),
            editions = listOf(
                DriveCatalogEdition(
                    sha256 = sha,
                    sizeBytes = 1L,
                    driveFileId = driveFileId,
                    fileName = "${book.title}.epub",
                    uploadedAt = 1L,
                    availabilityHlc = "0017450000000-0001-dev-a",
                ),
            ),
            fileSeriesName = null,
            fileSeriesIndex = null,
            addedAt = book.addedAt,
            archivedAt = null,
            updatedAt = 1L,
        )
        driveDb.recordStates().upsert(
            DriveRecordState(
                permissionId = permissionId,
                domain = DriveWireCodec.DOMAIN_CATALOG,
                recordKey = body.workKey,
                versionFp = "advertised-$driveFileId",
                versionOp = DriveRecordState.OP_PUT,
                lastHlc = "0017450000000-0001-dev-a",
                bodyJson = DriveCanonicalJson.encode(DriveWireCodec.encodeCatalogBody(body)),
            ),
        )
    }

    private fun stub(url: String) = Book(
        url = url,
        title = "Stub $url",
        author = "Author",
        coverPath = null,
        source = null,
        addedAt = 1L,
        lastOpenedAt = null,
        localUri = null,
        remoteUuid = "remote-$url",
        downloadState = DownloadState.REMOTE,
    )

    private class FakeSetup(private val result: SetupResult) : ServerSetup {
        override suspend fun connect(
            rawUrl: String,
            credentials: RemoteCredentials,
            allowHttp: Boolean,
        ): SetupResult = result
    }
}
