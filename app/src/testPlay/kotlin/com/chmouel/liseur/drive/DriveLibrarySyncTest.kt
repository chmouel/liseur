package com.chmouel.liseur.drive

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
            deviceIdGenerator = { "11111111-2222-4333-8444-555555555555" },
            openStore = { driveDb },
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
            // No Drive sync pipeline exists yet, so the production backend must not
            // advertise the book-server option that promises one.
            assertFalse(container.librarySync.supportsBookServerOption)
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
