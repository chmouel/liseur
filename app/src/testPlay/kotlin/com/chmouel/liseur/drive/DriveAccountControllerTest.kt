package com.chmouel.liseur.drive

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.R
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.vocabulary.VocabularyWord
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerCapabilities
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.ServerSetup
import com.chmouel.liseur.data.remote.SetupResult
import com.chmouel.liseur.sync.LibrarySyncSwitchCoordinator
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DriveAccountControllerTest {

    private lateinit var context: Application
    private lateinit var liseurDb: LiseurDatabase
    private lateinit var driveDb: DriveSyncDatabase
    private lateinit var remoteAccount: RemoteAccountRepository
    private lateinit var driveSync: DriveLibrarySync
    private lateinit var fakeAuth: FakeDriveAuthClient
    private lateinit var fakeApi: FakeDriveRemoteApi
    private lateinit var controller: DriveAccountController
    private lateinit var switchCoordinator: LibrarySyncSwitchCoordinator

    private val workersStopped = AtomicInteger(0)
    private val resumedActive = AtomicInteger(0)
    private val uploadsScheduled = AtomicInteger(0)
    private val uploadsCancelled = AtomicInteger(0)
    private var nowMs = 1_000L

    /** Runs inside the transfer manager between fingerprinting a book and writing its row. */
    @Volatile
    private var beforeQueueWrite: suspend () -> Unit = {}

    @Before
    fun setUp() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        context = ApplicationProvider.getApplicationContext()
        liseurDb = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        driveDb = Room.inMemoryDatabaseBuilder(context, DriveSyncDatabase::class.java).build()
        workersStopped.set(0)
        resumedActive.set(0)
        uploadsScheduled.set(0)
        uploadsCancelled.set(0)
        nowMs = 1_000L
        beforeQueueWrite = {}

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
        val switchCoordinator = LibrarySyncSwitchCoordinator()
        this.switchCoordinator = switchCoordinator
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
                ServerKind.LISEUR_SYNC to FakeServerSetup(
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
            ),
            inTransaction = { work -> removal.together(work) },
            beforeMainServerChange = { syncRef.deactivate() },
            switchCoordinator = switchCoordinator,
        )
        fakeAuth = FakeDriveAuthClient()
        fakeApi = FakeDriveRemoteApi()
        syncRef = DriveLibrarySync(
            context = context,
            database = liseurDb,
            remoteAccount = remoteAccount,
            switchCoordinator = switchCoordinator,
            stopWorkers = { workersStopped.incrementAndGet() },
            onResumeActive = { resumedActive.incrementAndGet() },
            scheduleEpubUploadsWork = { _ -> uploadsScheduled.incrementAndGet() },
            cancelEpubUploadsWork = { uploadsCancelled.incrementAndGet() },
            beforeQueueWrite = { beforeQueueWrite() },
            deviceIdGenerator = { "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee" },
            openStore = { driveDb },
            authClient = fakeAuth,
            driveApi = fakeApi,
            clock = { nowMs },
        )
        driveSync = syncRef
        controller = driveSync.accountController
    }

    @After
    fun tearDown() {
        driveDb.close()
        liseurDb.close()
        CredentialCipher.keyForTesting = null
    }

    @Test
    fun `first connect on empty library activates immediately and keys by permissionId`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")

        controller.beginConnect()

        val state = controller.uiState.first()
        assertFalse(state.busy)
        assertNull(state.pendingConfirmation)
        assertEquals("perm-A", state.account?.permissionId)
        assertEquals("alice@example.com", state.account?.email)
        assertEquals(DriveAccount.STATE_ACTIVE, state.account?.state)
        assertEquals(0, fakeApi.writeCalls)
        assertEquals(1, resumedActive.get())
    }

    @Test
    fun `first connect asks for the upload selection before anything goes up`() = runTest {
        seedLocalLibrary(books = 1, notes = 0, sessions = 0)
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()

        controller.confirmPendingSwitch()

        assertNull(driveDb.accounts().get("perm-A")?.uploadSelectionAt)
        assertTrue(controller.uiState.first().uploadPickerVisible)
    }

    @Test
    fun `first connect with local library data requires explicit confirmation naming target account and exact counts`() = runTest {
        seedLocalLibrary(books = 2, notes = 3, sessions = 4)
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")

        controller.beginConnect()

        // Before confirmation: zero drive_account rows written and zero Drive API writes.
        assertTrue(driveDb.accounts().all().isEmpty())
        assertEquals(0, fakeApi.writeCalls)

        val pendingState = controller.uiState.first()
        val confirmation = requireNotNull(pendingState.pendingConfirmation)
        assertNull(confirmation.previousAccount)
        assertEquals("perm-A", confirmation.targetPermissionId)
        assertEquals("alice@example.com", confirmation.targetEmail)
        assertEquals(LocalLibrarySummary(booksCount = 2, notesCount = 3, readingSessionsCount = 4), confirmation.localSummary)
        assertFalse(confirmation.mainServerConnected)

        // Cancelling keeps device completely disconnected with zero writes.
        controller.cancelPendingSwitch()
        assertTrue(driveDb.accounts().all().isEmpty())
        assertNull(controller.uiState.first().pendingConfirmation)

        // Retrying and confirming activates Account A.
        controller.beginConnect()
        assertNotNull(controller.uiState.first().pendingConfirmation)
        controller.confirmPendingSwitch()

        val active = requireNotNull(driveDb.accounts().active())
        assertEquals("perm-A", active.permissionId)
        assertEquals("alice@example.com", active.email)
        assertNull(controller.uiState.first().pendingConfirmation)
    }

    @Test
    fun `a main server connected while the reader was confirming is asked about again instead of disconnected`() = runTest {
        seedLocalLibrary(books = 1, notes = 0, sessions = 0)
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")

        controller.beginConnect()
        assertFalse(requireNotNull(controller.uiState.first().pendingConfirmation).mainServerConnected)

        // The reader connects a sync server while the confirmation prompt is still open.
        assertTrue(remoteAccount.connectLiseurSync("https://sync.example", "reader", "pw") is SetupResult.Success)

        controller.confirmPendingSwitch()

        assertNotNull(remoteAccount.current())
        assertTrue(driveDb.accounts().all().isEmpty())
        val confirmation = requireNotNull(controller.uiState.first().pendingConfirmation)
        assertTrue(confirmation.mainServerConnected)
        assertEquals("perm-A", confirmation.targetPermissionId)

        // Confirming again, now that the server is part of what the reader was shown, disconnects it.
        controller.confirmPendingSwitch()
        assertNull(remoteAccount.current())
        assertEquals(DriveAccount.STATE_ACTIVE, driveDb.accounts().get("perm-A")?.state)
    }

    @Test
    fun `account switch A to B pauses A without wiping cursors or queues, blocks writes until confirmed, and restores A on cancel`() = runTest {
        // Connect Account A and populate A's state (cursors, outbox, uploads, folder, conflicts).
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()

        driveDb.accounts().updateStartPageToken("perm-A", "page-tok-A")
        driveDb.accounts().updateFolderId("perm-A", "folder-A")
        driveDb.cursors().upsert(DriveCursor("perm-A", "dev-remote", "annotation", appliedSeq = 12))
        driveDb.outbox().upsert(DriveOutbox("perm-A", seq = 1, batchId = "b-1", payload = "{}", state = DriveOutbox.STATE_BUILT, createdAt = 10L))
        driveDb.uploads().upsert(DriveUpload("perm-A", sha256 = "sha-a", bookUrl = "file:///a.epub", sizeBytes = 100L, state = DriveUpload.STATE_PENDING, selectedAt = 10L, updatedAt = 10L))
        driveDb.conflicts().upsert(
            DriveConflict(
                permissionId = "perm-A",
                conflictId = "c-1",
                domain = "annotation",
                recordKey = "note-1",
                bookUrl = "file:///book-0.epub",
                kind = DriveConflict.KIND_EDIT_EDIT,
                localFp = "fp1",
                remoteFp = "fp2",
                remoteDeviceId = "dev-remote",
                remoteHlc = "0001",
                state = DriveConflict.STATE_OPEN,
                openedAt = 10L,
            ),
        )
        seedLocalLibrary(books = 1, notes = 2, sessions = 1)

        // Verify UI state surfaces Account A's pending uploads and unresolved conflicts counts.
        val beforeSwitch = controller.uiState.first()
        assertEquals(1, beforeSwitch.pendingUploadsCount)
        assertEquals(1, beforeSwitch.unresolvedConflictsCount)

        // Start switching to Account B.
        nowMs = 2_000L
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-b")
        fakeApi.aboutByToken["tok-b"] = DriveUserAbout("perm-B", "bob@example.com")
        controller.beginConnect(switchAccount = true)

        assertTrue(fakeAuth.lastSelectAccount)
        // Account A is PAUSED with all state intact; Account B does NOT exist in drive_account yet.
        val pausedA = requireNotNull(driveDb.accounts().get("perm-A"))
        assertEquals(DriveAccount.STATE_PAUSED, pausedA.state)
        assertEquals("page-tok-A", pausedA.startPageToken)
        assertEquals("folder-A", pausedA.folderId)
        assertNull(driveDb.accounts().get("perm-B"))
        assertEquals(0, fakeApi.writeCalls)

        val pending = requireNotNull(controller.uiState.first().pendingConfirmation)
        assertTrue(pending.isAccountSwitch)
        assertEquals("perm-A", pending.previousAccount?.permissionId)
        assertEquals(DriveAccount.STATE_ACTIVE, pending.previousState)
        assertEquals("perm-B", pending.targetPermissionId)
        assertEquals("bob@example.com", pending.targetEmail)
        assertEquals(LocalLibrarySummary(booksCount = 1, notesCount = 2, readingSessionsCount = 1), pending.localSummary)

        // Cancelling restores Account A to ACTIVE and leaves zero rows for Account B.
        controller.cancelPendingSwitch()
        assertEquals(DriveAccount.STATE_ACTIVE, driveDb.accounts().get("perm-A")?.state)
        assertNull(driveDb.accounts().get("perm-B"))
        assertEquals(0, fakeApi.writeCalls)

        // Now switch again and CONFIRM.
        controller.beginConnect(switchAccount = true)
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)
        controller.confirmPendingSwitch()

        // Account A stays PAUSED with ALL cursors, outbox, uploads, folder, and conflicts preserved!
        val keptA = requireNotNull(driveDb.accounts().get("perm-A"))
        assertEquals(DriveAccount.STATE_PAUSED, keptA.state)
        assertEquals("page-tok-A", keptA.startPageToken)
        assertEquals("folder-A", keptA.folderId)
        assertEquals(12L, driveDb.cursors().get("perm-A", "dev-remote", "annotation")?.appliedSeq)
        assertEquals(1, driveDb.outbox().forAccount("perm-A").size)
        assertEquals(1, driveDb.uploads().forAccount("perm-A").size)
        assertEquals(1, driveDb.conflicts().openForAccount("perm-A").size)

        // Account B is now ACTIVE with its own clean, empty queues.
        val activeB = requireNotNull(driveDb.accounts().active())
        assertEquals("perm-B", activeB.permissionId)
        assertEquals("bob@example.com", activeB.email)
        assertTrue(driveDb.uploads().forAccount("perm-B").isEmpty())
        assertTrue(driveDb.cursors().forAccount("perm-B").isEmpty())

        val stateB = controller.uiState.first()
        assertEquals("bob@example.com", stateB.account?.email)
        assertEquals(0, stateB.pendingUploadsCount)
        assertEquals(0, stateB.unresolvedConflictsCount)
    }

    /** Account B has its own selection, so A's answer cannot cover it. */
    @Test
    fun `an account switch asks for the new account's upload selection`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        nowMs = 1_000L
        controller.confirmUploadSelection(emptySet())
        controller.closeUploadPicker()
        assertFalse(controller.uiState.first().uploadPickerVisible)

        nowMs = 2_000L
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-b")
        fakeApi.aboutByToken["tok-b"] = DriveUserAbout("perm-B", "bob@example.com")
        controller.beginConnect(switchAccount = true)
        controller.confirmPendingSwitch()

        assertTrue(controller.uiState.first().uploadPickerVisible)
        assertNull(driveDb.accounts().get("perm-B")?.uploadSelectionAt)
        assertEquals(1_000L, driveDb.accounts().get("perm-A")?.uploadSelectionAt)
    }

    /** ADR-0078: resuming an account used before is still a switch, so it asks for its selection again. */
    @Test
    fun `switching back to an account asks for its upload selection again`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        nowMs = 1_000L
        controller.confirmUploadSelection(emptySet())
        controller.closeUploadPicker()

        nowMs = 2_000L
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-b")
        fakeApi.aboutByToken["tok-b"] = DriveUserAbout("perm-B", "bob@example.com")
        controller.beginConnect(switchAccount = true)
        controller.confirmPendingSwitch()
        nowMs = 3_000L
        controller.confirmUploadSelection(emptySet())
        controller.closeUploadPicker()
        assertFalse(controller.uiState.first().uploadPickerVisible)

        nowMs = 4_000L
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a-again")
        fakeApi.aboutByToken["tok-a-again"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect(switchAccount = true)
        controller.confirmPendingSwitch()

        assertTrue(controller.uiState.first().uploadPickerVisible)
        assertNull(driveDb.accounts().get("perm-A")?.uploadSelectionAt)
        assertEquals(3_000L, driveDb.accounts().get("perm-B")?.uploadSelectionAt)
        // Until it is answered, no policy (ALWAYS included) offers anything for this account.
        val target = requireNotNull(
            driveSync.uploadTarget.first { it?.accountKey == DriveLibrarySync.accountPeerId("perm-A") },
        )
        assertFalse(target.selectionSettled)
    }

    @Test
    fun `silent background reauth marks NEEDS_RECONNECT and pauses workers on hasResolution or revoked access`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        driveDb.cursors().upsert(DriveCursor("perm-A", "dev-remote", "catalog", appliedSeq = 9))

        // 1) Silent acquisition succeeds while grant is healthy and about.get matches perm-A.
        fakeAuth.nextSilent = DriveAuthResult.Authorized("tok-fresh")
        fakeApi.aboutByToken["tok-fresh"] = DriveUserAbout("perm-A", "alice@example.com")
        val ok = controller.acquireSilentAccessToken()
        assertEquals(SilentTokenResult.Token("tok-fresh", "perm-A"), ok)
        assertEquals("alice@example.com", fakeAuth.lastAccountEmail)

        // 2) Transient non-auth failure leaves account ACTIVE.
        fakeAuth.nextSilent = DriveAuthResult.Failed("Network unavailable", requiresReconnect = false)
        val transient = controller.acquireSilentAccessToken()
        assertTrue(transient is SilentTokenResult.TransientFailure)
        assertEquals(DriveAccount.STATE_ACTIVE, driveDb.accounts().get("perm-A")?.state)

        // 3) hasResolution() -> transitions account to NEEDS_RECONNECT, stops workers, keeps cursors.
        fakeAuth.nextSilent = DriveAuthResult.NeedsResolution(pendingIntent = null)
        val needs = controller.acquireSilentAccessToken()
        assertTrue(needs is SilentTokenResult.NeedsReconnect)
        assertEquals(1, workersStopped.get())
        assertEquals(DriveAccount.STATE_NEEDS_RECONNECT, driveDb.accounts().get("perm-A")?.state)
        assertEquals(9L, driveDb.cursors().get("perm-A", "dev-remote", "catalog")?.appliedSeq)

        // 4) Interactive reconnect for same permissionId (even with updated email!) returns to ACTIVE
        //    without confirmation dialog or data loss.
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-reconnected")
        fakeApi.aboutByToken["tok-reconnected"] = DriveUserAbout("perm-A", "alice.new@example.com")
        controller.beginConnect(switchAccount = false)

        val reconnected = requireNotNull(driveDb.accounts().active())
        assertEquals("perm-A", reconnected.permissionId)
        assertEquals("alice.new@example.com", reconnected.email)
        assertNull(reconnected.lastError)
        assertEquals(9L, driveDb.cursors().get("perm-A", "dev-remote", "catalog")?.appliedSeq)
    }

    @Test
    fun `stale silent refresh never marks or returns a token for an account that was disconnected, switched or reconnected`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        fakeApi.aboutByToken["tok-fresh"] = DriveUserAbout("perm-A", "alice@example.com")

        suspend fun gatedRefresh(
            outcome: DriveAuthResult,
            interleave: suspend () -> Unit,
        ): SilentTokenResult {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fakeAuth.silentHandler = {
                entered.complete(Unit)
                release.await()
                outcome
            }
            val refresh = async(Dispatchers.Default) { controller.acquireSilentAccessToken() }
            entered.await()
            interleave()
            release.complete(Unit)
            return refresh.await().also { fakeAuth.silentHandler = null }
        }

        // 1) Disconnect while a refresh that will need consent is in flight.
        val stoppedBefore = workersStopped.get()
        val afterDisconnect = gatedRefresh(DriveAuthResult.NeedsResolution(pendingIntent = null)) {
            controller.disconnect()
        }
        assertTrue(afterDisconnect is SilentTokenResult.TransientFailure)
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)
        assertEquals(stoppedBefore + 1, workersStopped.get())

        // 2) Same-account reconnect while a refresh is in flight: the stale result is dropped.
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a2")
        fakeApi.aboutByToken["tok-a2"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        val afterReconnect = gatedRefresh(DriveAuthResult.NeedsResolution(pendingIntent = null)) {
            controller.disconnect()
            controller.beginConnect()
        }
        assertTrue(afterReconnect is SilentTokenResult.TransientFailure)
        assertEquals(DriveAccount.STATE_ACTIVE, driveDb.accounts().get("perm-A")?.state)

        // 3) A successful refresh that completes after disconnect does not hand out a token.
        val tokenAfterDisconnect = gatedRefresh(DriveAuthResult.Authorized("tok-fresh")) {
            controller.disconnect()
        }
        assertTrue(tokenAfterDisconnect is SilentTokenResult.TransientFailure)

        // 4) Account B activated outside the controller: A's stale result leaves B alone.
        controller.beginConnect()
        val stoppedBeforeSwitch = workersStopped.get()
        val afterSwitch = gatedRefresh(DriveAuthResult.NeedsResolution(pendingIntent = null)) {
            driveSync.activateAccount("perm-B", "bob@example.com", now = 2_000L)
        }
        assertTrue(afterSwitch is SilentTokenResult.TransientFailure)
        assertEquals(DriveAccount.STATE_ACTIVE, driveDb.accounts().get("perm-B")?.state)
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)
        assertEquals(stoppedBeforeSwitch + 1, workersStopped.get())
    }

    @Test
    fun `silent refresh started during an interactive reconnect waits for it to finish`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()

        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-reconnect")
        fakeApi.aboutByToken["tok-reconnect"] = DriveUserAbout("perm-A", "alice@example.com")
        val gate = CompletableDeferred<Unit>()
        fakeApi.aboutGate = gate
        val reconnect = async(Dispatchers.Default) { controller.beginConnect() }
        fakeApi.aboutEntered.await()

        var silentRanDuringTransition = false
        fakeAuth.silentHandler = {
            silentRanDuringTransition = !gate.isCompleted
            DriveAuthResult.NeedsResolution(pendingIntent = null)
        }
        val refresh = async(Dispatchers.Default) { controller.acquireSilentAccessToken() }
        kotlinx.coroutines.withContext(Dispatchers.Default) { kotlinx.coroutines.delay(100) }
        assertFalse(refresh.isCompleted)

        fakeApi.aboutGate = null
        gate.complete(Unit)
        reconnect.await()
        refresh.await()
        assertFalse(silentRanDuringTransition)
    }

    @Test
    fun `handleAuthExpired clears stale token and marks NEEDS_RECONNECT when consent was revoked`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()

        fakeAuth.nextSilent = DriveAuthResult.Failed("Revoked", requiresReconnect = true)
        val result = controller.handleAuthExpired("stale-tok-401")

        assertTrue(result is SilentTokenResult.NeedsReconnect)
        assertEquals(listOf("stale-tok-401"), fakeAuth.clearedTokens)
        assertEquals(DriveAccount.STATE_NEEDS_RECONNECT, driveDb.accounts().get("perm-A")?.state)
        assertEquals(1, workersStopped.get())
    }

    @Test
    fun `interactive resolution launches PendingIntent and completes via activity result`() = runTest {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent("com.chmouel.liseur.TEST_DRIVE_AUTH"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fakeAuth.nextAuthorize = DriveAuthResult.NeedsResolution(pendingIntent)
        controller.beginConnect()

        val waiting = controller.uiState.first()
        assertTrue(waiting.busy)
        assertEquals(pendingIntent, waiting.pendingAuthIntent)
        controller.consumePendingAuthIntent()
        assertNull(controller.uiState.first().pendingAuthIntent)

        fakeAuth.nextParsedResult = DriveAuthResult.Authorized("tok-from-intent")
        fakeApi.aboutByToken["tok-from-intent"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.onAuthorizationActivityResult(Activity.RESULT_OK, Intent())

        val connected = controller.uiState.first()
        assertFalse(connected.busy)
        assertEquals("perm-A", connected.account?.permissionId)
        assertEquals(DriveAccount.STATE_ACTIVE, connected.account?.state)
    }

    @Test
    fun `cancelled authorization with result data is inspected without connecting`() = runTest {
        val pendingIntent = PendingIntent.getActivity(
            context, 0, Intent("com.chmouel.liseur.TEST_DRIVE_AUTH"), PendingIntent.FLAG_IMMUTABLE,
        )
        fakeAuth.nextAuthorize = DriveAuthResult.NeedsResolution(pendingIntent)
        controller.beginConnect()
        fakeAuth.nextParsedResult = DriveAuthResult.Authorized("token")

        controller.onAuthorizationActivityResult(Activity.RESULT_CANCELED, Intent())

        assertEquals(1, fakeAuth.parsedResults)
        assertFalse(controller.uiState.first().busy)
        assertNull(controller.uiState.first().account)
    }

    @Test
    fun `authorization error in cancelled activity result is shown while user cancellation stays quiet`() = runTest {
        val pendingIntent = PendingIntent.getActivity(
            context, 0, Intent("com.chmouel.liseur.TEST_DRIVE_AUTH"), PendingIntent.FLAG_IMMUTABLE,
        )
        fakeAuth.nextAuthorize = DriveAuthResult.NeedsResolution(pendingIntent)
        controller.beginConnect()
        fakeAuth.nextParsedResult = DriveAuthResult.Failed(
            "Google authorization error", cause = ApiException(Status(CommonStatusCodes.INTERNAL_ERROR)),
        )

        controller.onAuthorizationActivityResult(Activity.RESULT_CANCELED, Intent())

        assertEquals(R.string.drive_sync_error_auth_failed, controller.uiState.first().errorMessageRes)
        assertFalse(controller.uiState.first().busy)

        controller.beginConnect()
        fakeAuth.nextParsedResult = DriveAuthResult.Failed(
            "Authorization cancelled", cause = ApiException(Status(CommonStatusCodes.CANCELED)),
        )
        controller.onAuthorizationActivityResult(Activity.RESULT_CANCELED, Intent())

        assertNull(controller.uiState.first().errorMessageRes)
        assertFalse(controller.uiState.first().busy)
    }

    @Test
    fun `consent to disconnect one server does not cover a server that replaced it`() = runTest {
        assertTrue(remoteAccount.connectLiseurSync("https://sync.example", "reader", "pw") is SetupResult.Success)
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        val shown = requireNotNull(controller.uiState.first().pendingConfirmation)
        assertTrue(shown.mainServerConnected)

        val serverA = requireNotNull(remoteAccount.current())
        liseurDb.remoteServerDao().upsert(serverA.copy(baseUrl = "https://replacement.example"))

        controller.confirmPendingSwitch()

        assertEquals("https://replacement.example", remoteAccount.current()?.baseUrl)
        assertTrue(driveDb.accounts().all().isEmpty())
        val reprompt = requireNotNull(controller.uiState.first().pendingConfirmation)
        assertTrue(reprompt.mainServerConnected)
        assertNotEquals(shown.confirmedServer, reprompt.confirmedServer)

        controller.confirmPendingSwitch()
        assertNull(remoteAccount.current())
        assertEquals(DriveAccount.STATE_ACTIVE, driveDb.accounts().get("perm-A")?.state)
    }

    @Test
    fun `saved vocabulary alone requires local data confirmation and counts only unowned words`() = runTest {
        fun word(text: String, owner: String) = VocabularyWord(
            word = text,
            normalized = text,
            language = "en",
            definition = null,
            translationLanguage = null,
            addedAt = 1L,
            dueAt = 1L,
            ownerAccount = owner,
        )
        liseurDb.vocabularyDao().insertWord(word("alpha", ""))
        liseurDb.vocabularyDao().insertWord(word("beta", "other-account"))
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")

        controller.beginConnect()

        val confirmation = requireNotNull(controller.uiState.first().pendingConfirmation)
        assertEquals(
            LocalLibrarySummary(booksCount = 0, notesCount = 0, readingSessionsCount = 0, vocabularyWordsCount = 1),
            confirmation.localSummary,
        )
        assertTrue(driveDb.accounts().all().isEmpty())
    }

    @Test
    fun `confirmation count leaves out undownloaded previews that disconnecting the server removes`() = runTest {
        assertTrue(remoteAccount.connectLiseurSync("https://sync.example", "reader", "pw") is SetupResult.Success)
        seedLocalLibrary(books = 1, notes = 0, sessions = 0)
        liseurDb.bookDao().upsert(
            Book(
                url = "remote://untouched",
                title = "Preview",
                author = "A",
                coverPath = null,
                source = null,
                addedAt = 5L,
                lastOpenedAt = null,
                remoteUuid = "uuid-1",
                downloadState = DownloadState.REMOTE,
            ),
        )
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")

        controller.beginConnect()

        assertEquals(1, requireNotNull(controller.uiState.first().pendingConfirmation).localSummary.booksCount)
    }

    @Test
    fun `abandoned authorization frees the controller and ignores a late result`() = runTest {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent("com.chmouel.liseur.TEST_DRIVE_AUTH"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fakeAuth.nextAuthorize = DriveAuthResult.NeedsResolution(pendingIntent)
        controller.beginConnect()
        assertTrue(controller.uiState.first().busy)

        controller.abandonAuthorization()
        assertFalse(controller.uiState.first().busy)

        fakeAuth.nextParsedResult = DriveAuthResult.Authorized("tok-late")
        fakeApi.aboutByToken["tok-late"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.onAuthorizationActivityResult(Activity.RESULT_OK, Intent())
        assertNull(controller.uiState.first().account)
        assertTrue(driveDb.accounts().all().isEmpty())

        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        assertEquals("perm-A", controller.uiState.first().account?.permissionId)
    }

    @Test
    fun `disconnect pauses account while preserving all state and main server switch confirms disconnect`() = runTest {
        assertTrue(remoteAccount.connectLiseurSync("https://sync.example", "reader", "pw") is SetupResult.Success)
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")

        controller.beginConnect()
        val pending = requireNotNull(controller.uiState.first().pendingConfirmation)
        assertTrue(pending.mainServerConnected)
        assertNotNull(remoteAccount.current())

        controller.confirmPendingSwitch()
        assertNull(remoteAccount.current())
        assertEquals(DriveAccount.STATE_ACTIVE, driveDb.accounts().get("perm-A")?.state)

        driveDb.accounts().updateStartPageToken("perm-A", "tok-keep")
        val stoppedBeforeDisconnect = workersStopped.get()
        controller.disconnect()
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)
        assertEquals("tok-keep", driveDb.accounts().get("perm-A")?.startPageToken)
        assertEquals(stoppedBeforeDisconnect + 1, workersStopped.get())
    }

    @Test
    fun `switching from PAUSED account A to B keeps A paused on cancel and preserves A when switching back later`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        driveDb.cursors().upsert(DriveCursor("perm-A", "dev-1", "annotation", appliedSeq = 42))
        controller.disconnect()
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)

        // Start switch to B from PAUSED A, then cancel -> A must stay PAUSED (not become ACTIVE).
        nowMs = 2_000L
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-b")
        fakeApi.aboutByToken["tok-b"] = DriveUserAbout("perm-B", "bob@example.com")
        controller.beginConnect(switchAccount = true)
        assertNotNull(controller.uiState.first().pendingConfirmation)
        controller.cancelPendingSwitch()
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)
        assertNull(driveDb.accounts().get("perm-B"))

        // Switch to B and confirm.
        controller.beginConnect(switchAccount = true)
        controller.confirmPendingSwitch()
        assertEquals(DriveAccount.STATE_ACTIVE, driveDb.accounts().get("perm-B")?.state)
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)

        // Switch back to A later -> requires confirmation naming A, and resumes A's preserved cursors.
        nowMs = 3_000L
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        controller.beginConnect(switchAccount = true)
        val backPending = requireNotNull(controller.uiState.first().pendingConfirmation)
        assertEquals("perm-A", backPending.targetPermissionId)
        assertEquals("alice@example.com", backPending.targetEmail)
        controller.confirmPendingSwitch()

        assertEquals(DriveAccount.STATE_ACTIVE, driveDb.accounts().get("perm-A")?.state)
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-B")?.state)
        assertEquals(42L, driveDb.cursors().get("perm-A", "dev-1", "annotation")?.appliedSeq)

        // Disconnect after A -> B -> A: A must remain the most-recently-active account for UI and reconnect.
        controller.disconnect()
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-B")?.state)

        val pausedUi = controller.uiState.first()
        assertEquals("perm-A", pausedUi.account?.permissionId)
        assertEquals("alice@example.com", pausedUi.account?.email)
        assertEquals(DriveAccount.STATE_PAUSED, pausedUi.account?.state)

        // Reconnect without switchAccount targets A directly without an account-switch dialog.
        nowMs = 4_000L
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        controller.beginConnect(switchAccount = false)

        assertEquals("alice@example.com", fakeAuth.lastAccountEmail)
        assertFalse(fakeAuth.lastSelectAccount)
        assertNull(controller.uiState.first().pendingConfirmation)
        assertEquals(DriveAccount.STATE_ACTIVE, driveDb.accounts().get("perm-A")?.state)
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-B")?.state)
        assertEquals(42L, driveDb.cursors().get("perm-A", "dev-1", "annotation")?.appliedSeq)
    }

    @Test
    fun `cancelling beginConnect while authorize suspends clears busy state and allows a subsequent connect`() = runTest {
        val authorizeStarted = CompletableDeferred<Unit>()
        val authorizeGate = CompletableDeferred<DriveAuthResult>()
        fakeAuth.authorizeHandler = { _, _ ->
            authorizeStarted.complete(Unit)
            authorizeGate.await()
        }

        val job = launch { controller.beginConnect() }
        authorizeStarted.await()
        assertTrue(controller.uiState.first().busy)

        job.cancelAndJoin()
        val afterCancel = controller.uiState.first()
        assertFalse(afterCancel.busy)
        assertNull(afterCancel.pendingAuthIntent)
        assertNull(afterCancel.errorMessageRes)

        // Subsequent beginConnect succeeds and activates the account.
        fakeAuth.authorizeHandler = null
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()

        val connected = controller.uiState.first()
        assertFalse(connected.busy)
        assertEquals("perm-A", connected.account?.permissionId)
        assertEquals(DriveAccount.STATE_ACTIVE, connected.account?.state)
    }

    @Test
    fun `silent background reauth rejects token when about permissionId belongs to a different account`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        driveDb.cursors().upsert(DriveCursor("perm-A", "dev-remote", "annotation", appliedSeq = 7))

        // Silent auth returns a token whose about.get identifies Account B instead of active Account A.
        fakeAuth.nextSilent = DriveAuthResult.Authorized("tok-wrong-account-b")
        fakeApi.aboutByToken["tok-wrong-account-b"] = DriveUserAbout("perm-B", "bob@example.com")

        val result = controller.acquireSilentAccessToken()

        assertTrue(result is SilentTokenResult.NeedsReconnect)
        val needs = result as SilentTokenResult.NeedsReconnect
        assertEquals("perm-A", needs.permissionId)
        assertEquals(DriveAccountController.ACCOUNT_MISMATCH_REASON, needs.reason)
        assertEquals(listOf("tok-wrong-account-b"), fakeAuth.clearedTokens)
        assertEquals(1, workersStopped.get())
        assertEquals(DriveAccount.STATE_NEEDS_RECONNECT, driveDb.accounts().get("perm-A")?.state)
        assertEquals(7L, driveDb.cursors().get("perm-A", "dev-remote", "annotation")?.appliedSeq)
        assertNull(driveDb.accounts().get("perm-B"))
        assertEquals(0, fakeApi.writeCalls)
    }

    @Test
    fun `a cancel interrupted while restoring account A leaves the confirmation retryable`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()

        nowMs = 2_000L
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-b")
        fakeApi.aboutByToken["tok-b"] = DriveUserAbout("perm-B", "bob@example.com")
        controller.beginConnect(switchAccount = true)
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)

        // Leaving settings cancels the scope running the restore halfway through it.
        val switching = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            switchCoordinator.withSwitchLock {
                switching.complete(Unit)
                release.await()
            }
        }
        switching.await()
        val cancel = launch(Dispatchers.Default) { controller.cancelPendingSwitch() }
        kotlinx.coroutines.withContext(Dispatchers.Default) { kotlinx.coroutines.delay(100) }
        assertFalse(cancel.isCompleted)
        cancel.cancelAndJoin()
        release.complete(Unit)
        holder.join()

        // Account A is still paused, but the confirmation survived so the reader can retry.
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)
        assertNotNull(controller.uiState.first().pendingConfirmation)

        controller.cancelPendingSwitch()
        assertEquals(DriveAccount.STATE_ACTIVE, driveDb.accounts().get("perm-A")?.state)
        assertNull(controller.uiState.first().pendingConfirmation)
    }

    @Test
    fun `a switch abandoned to process death is recovered on the next start`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()

        nowMs = 2_000L
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-b")
        fakeApi.aboutByToken["tok-b"] = DriveUserAbout("perm-B", "bob@example.com")
        controller.beginConnect(switchAccount = true)

        val abandoned = requireNotNull(driveDb.accounts().get("perm-A"))
        assertEquals(DriveAccount.STATE_PAUSED, abandoned.state)
        assertEquals(DriveAccount.STATE_ACTIVE, abandoned.pendingSwitchPreviousState)
        assertNull(driveDb.accounts().get("perm-B"))

        val resumed = AtomicInteger(0)
        val restarted = restartSync(resumed)
        restarted.resume()
        awaitState("perm-A", DriveAccount.STATE_ACTIVE)

        assertNull(driveDb.accounts().get("perm-A")?.pendingSwitchPreviousState)
        assertEquals(1, resumed.get())
    }

    @Test
    fun `an intentional disconnect is not undone by the next start`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        controller.disconnect()

        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)
        assertNull(driveDb.accounts().get("perm-A")?.pendingSwitchPreviousState)

        val resumed = AtomicInteger(0)
        restartSync(resumed).resume()
        kotlinx.coroutines.withContext(Dispatchers.Default) { kotlinx.coroutines.delay(200) }

        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)
        assertEquals(0, resumed.get())
    }

    @Test
    fun `a stale switch marker does not undo a later disconnect`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()

        nowMs = 2_000L
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-b")
        fakeApi.aboutByToken["tok-b"] = DriveUserAbout("perm-B", "bob@example.com")
        controller.beginConnect(switchAccount = true)
        assertEquals(
            DriveAccount.STATE_PAUSED,
            driveDb.accounts().get("perm-A")?.state,
        )

        // A transition that runs while the restore is queued clears the marker:
        // the reader disconnects, which is why startup must not bring A back.
        controller.disconnect()
        assertNull(driveDb.accounts().get("perm-A")?.pendingSwitchPreviousState)

        val resumed = AtomicInteger(0)
        val restarted = restartSync(resumed)
        val restored = restarted.restoreAccountState(
            "perm-A",
            DriveAccount.STATE_ACTIVE,
        )

        assertFalse(restored)
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)
        assertEquals(0, resumed.get())
    }

    @Test
    fun `an account Drive reports without an email keeps a blank hint and is named by permission id`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "")
        controller.beginConnect()

        val active = requireNotNull(driveDb.accounts().active())
        assertEquals("", active.email)
        assertEquals("perm-A", active.displayName)

        // Silent refresh must not name a Google account that does not exist.
        fakeAuth.nextSilent = DriveAuthResult.Authorized("tok-fresh")
        fakeApi.aboutByToken["tok-fresh"] = DriveUserAbout("perm-A", "")
        assertEquals(SilentTokenResult.Token("tok-fresh", "perm-A"), controller.acquireSilentAccessToken())
        assertNull(fakeAuth.lastAccountEmail)
    }

    @Test
    fun `an unconfirmed switch names the target by permission id when Drive omits its email`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()

        nowMs = 2_000L
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-b")
        fakeApi.aboutByToken["tok-b"] = DriveUserAbout("perm-B", "")
        controller.beginConnect(switchAccount = true)

        val pending = requireNotNull(controller.uiState.first().pendingConfirmation)
        assertEquals("", pending.targetEmail)
        assertEquals("perm-B", pending.targetDisplayName)

        controller.confirmPendingSwitch()
        val activeB = requireNotNull(driveDb.accounts().active())
        assertEquals("perm-B", activeB.permissionId)
        assertEquals("", activeB.email)
        assertEquals("perm-B", activeB.displayName)
    }

    @Test
    fun `confirmation counts only the sessions Drive will upload`() = runTest {
        seedLocalLibrary(books = 1, notes = 0, sessions = 1)
        // Imported sittings stay local, and one that cannot say where in the book
        // it happened is never uploaded.
        liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = "file:///book-0.epub",
                startedAt = 5_000L,
                endedAt = 5_100L,
                lastCheckpointAt = 5_100L,
                durationMs = 100L,
                startProgression = 0.3,
                endProgression = 0.4,
                importedAt = 9_000L,
            ),
        )
        liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = "file:///book-0.epub",
                startedAt = 6_000L,
                endedAt = 6_100L,
                lastCheckpointAt = 6_100L,
                durationMs = 100L,
            ),
        )
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")

        controller.beginConnect()

        assertEquals(1, requireNotNull(controller.uiState.first().pendingConfirmation).localSummary.readingSessionsCount)
    }

    @Test
    fun `disposing interactive flow with a pending confirmation cancels the switch and restores account A`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()

        nowMs = 2_000L
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-b")
        fakeApi.aboutByToken["tok-b"] = DriveUserAbout("perm-B", "bob@example.com")
        controller.beginConnect(switchAccount = true)
        assertEquals(DriveAccount.STATE_PAUSED, driveDb.accounts().get("perm-A")?.state)
        assertNotNull(controller.uiState.first().pendingConfirmation)

        controller.abandonInteractiveFlow(this)
        awaitState("perm-A", DriveAccount.STATE_ACTIVE)

        assertEquals(DriveAccount.STATE_ACTIVE, driveDb.accounts().get("perm-A")?.state)
        assertNull(driveDb.accounts().get("perm-A")?.pendingSwitchPreviousState)
        assertNull(controller.uiState.first().pendingConfirmation)
        assertNull(driveDb.accounts().get("perm-B"))
    }

    @Test
    fun `controller errors emit translatable string resource ids`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.NeedsResolution(pendingIntent = null)
        controller.beginConnect()
        assertEquals(R.string.drive_sync_error_sign_in_prompt, controller.uiState.first().errorMessageRes)

        fakeAuth.nextAuthorize = DriveAuthResult.Failed("boom")
        controller.beginConnect()
        assertEquals(R.string.drive_sync_error_auth_failed, controller.uiState.first().errorMessageRes)

        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-expired")
        controller.beginConnect()
        assertEquals(R.string.drive_sync_error_token_rejected, controller.uiState.first().errorMessageRes)

        controller.clearError()
        assertNull(controller.uiState.first().errorMessageRes)
    }

    private suspend fun awaitState(permissionId: String, state: String) {
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) {
                while (driveDb.accounts().get(permissionId)?.state != state) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
    }

    /** A second backend over the same `drive_sync.db`, as a process restart would open. */
    private fun restartSync(resumed: AtomicInteger) = DriveLibrarySync(
        context = context,
        database = liseurDb,
        remoteAccount = remoteAccount,
        switchCoordinator = switchCoordinator,
        onResumeActive = { resumed.incrementAndGet() },
        openStore = { driveDb },
        authClient = fakeAuth,
        driveApi = fakeApi,
        clock = { nowMs },
    )

    private suspend fun seedLocalLibrary(books: Int, notes: Int, sessions: Int) {
        repeat(books) { i ->
            liseurDb.bookDao().upsert(
                Book(
                    url = "file:///book-$i.epub",
                    title = "Book $i",
                    author = "Author $i",
                    coverPath = null,
                    source = null,
                    addedAt = 10L + i,
                    lastOpenedAt = null,
                    localUri = "file:///book-$i.epub",
                    downloadState = DownloadState.DOWNLOADED,
                ),
            )
        }
        repeat(notes) { i ->
            liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-$i",
                    bookId = "file:///book-0.epub",
                    kind = "NOTE",
                    locatorJson = "{}",
                    note = "Thought $i",
                    createdAt = 100L + i,
                ),
            )
        }
        repeat(sessions) { i ->
            liseurDb.readingSessionDao().insert(
                ReadingSession(
                    bookUrl = "file:///book-0.epub",
                    startedAt = 1_000L + i * 100L,
                    endedAt = 1_050L + i * 100L,
                    lastCheckpointAt = 1_050L + i * 100L,
                    durationMs = 50L,
                    startProgression = 0.1,
                    endProgression = 0.2,
                ),
            )
        }
    }

    private class FakeDriveAuthClient : DriveAuthClient {
        var nextAuthorize: DriveAuthResult = DriveAuthResult.Failed("Unconfigured")
        var authorizeHandler: (suspend (accountEmail: String?, selectAccount: Boolean) -> DriveAuthResult)? = null
        var nextSilent: DriveAuthResult = DriveAuthResult.Failed("Unconfigured")
        var nextParsedResult: DriveAuthResult = DriveAuthResult.Failed("Unconfigured")
        var parsedResults = 0
        var lastAccountEmail: String? = null
        var lastSelectAccount: Boolean = false
        val clearedTokens = mutableListOf<String>()

        override suspend fun authorize(accountEmail: String?, selectAccount: Boolean): DriveAuthResult {
            lastAccountEmail = accountEmail
            lastSelectAccount = selectAccount
            return authorizeHandler?.invoke(accountEmail, selectAccount) ?: nextAuthorize
        }

        var silentHandler: (suspend (accountEmail: String?) -> DriveAuthResult)? = null

        override suspend fun authorizeSilently(accountEmail: String?): DriveAuthResult {
            lastAccountEmail = accountEmail
            return silentHandler?.invoke(accountEmail) ?: nextSilent
        }

        override fun parseAuthorizationResult(data: Intent?): DriveAuthResult {
            parsedResults++
            return nextParsedResult
        }

        override suspend fun clearCachedToken(accessToken: String) {
            clearedTokens += accessToken
        }
    }

    @Test
    fun `confirmUploadSelection cancels bulk upload work when no pending uploads remain`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()

        val book1 = Book(
            url = "file:///book-1.epub",
            title = "Book One",
            author = "Author",
            coverPath = null,
            source = null,
            addedAt = 100L,
            lastOpenedAt = null,
            localUri = "file:///book-1.epub",
            downloadState = DownloadState.DOWNLOADED,
        )
        val book2 = book1.copy(url = "file:///book-2.epub", title = "Book Two", localUri = "file:///book-2.epub")
        liseurDb.bookDao().upsert(book1)
        liseurDb.bookDao().upsert(book2)
        val sha1 = "1".repeat(64)
        val sha2 = "2".repeat(64)
        liseurDb.workIdentityDao().upsert(
            com.chmouel.liseur.data.db.BookFingerprintRow(book1.url, sha1, "md5-1", 1000L, null, 1L),
        )
        liseurDb.workIdentityDao().upsert(
            com.chmouel.liseur.data.db.BookFingerprintRow(book2.url, sha2, "md5-2", 1000L, null, 1L),
        )

        // Selecting books schedules bulk upload work.
        controller.confirmUploadSelection(setOf(book1.url, book2.url))
        assertEquals(1, uploadsScheduled.get())
        assertEquals(0, uploadsCancelled.get())

        // Clearing selection cancels the running/scheduled bulk upload worker.
        controller.confirmUploadSelection(emptySet())
        assertEquals(1, uploadsScheduled.get())
        assertEquals(1, uploadsCancelled.get())
        assertTrue(driveDb.uploads().pendingForAccount("perm-A").isEmpty())

        // Leaving only an already-uploaded book selected also cancels bulk upload work.
        controller.confirmUploadSelection(setOf(book1.url, book2.url))
        assertEquals(2, uploadsScheduled.get())
        driveDb.uploads().markUploaded("perm-A", sha1, "drive-file-1", 2_000L)
        controller.confirmUploadSelection(setOf(book1.url))
        assertEquals(2, uploadsScheduled.get())
        assertEquals(2, uploadsCancelled.get())
        assertTrue(driveDb.uploads().pendingForAccount("perm-A").isEmpty())
    }

    @Test
    fun `confirming the picker records when the reader answered`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()

        nowMs = 5_000L
        controller.confirmUploadSelection(emptySet())

        assertEquals(5_000L, driveDb.accounts().get("perm-A")?.uploadSelectionAt)
    }

    /**
     * Leaving Settings cancels the screen's scope. An answer the reader already gave must
     * still write its queue and schedule the worker that drains it.
     */
    @Test
    fun `an accepted upload selection outlives the screen that asked for it`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        val book = Book(
            url = "file:///kept.epub",
            title = "Kept",
            author = "Author",
            coverPath = null,
            source = null,
            addedAt = 100L,
            lastOpenedAt = null,
            localUri = "file:///kept.epub",
            downloadState = DownloadState.DOWNLOADED,
        )
        liseurDb.bookDao().upsert(book)
        liseurDb.workIdentityDao().upsert(
            com.chmouel.liseur.data.db.BookFingerprintRow(book.url, "3".repeat(64), "md5-3", 1_000L, null, 1L),
        )
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeQueueWrite = {
            started.complete(Unit)
            release.await()
        }

        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                val screen = launch { controller.submitUploadSelection(setOf(book.url)) }
                started.await()
                // The reader leaves Settings while the selected book is still being queued.
                screen.cancelAndJoin()
                release.complete(Unit)
                while (driveDb.uploads().pendingForAccount("perm-A").isEmpty()) delay(5)
                while (uploadsScheduled.get() == 0) delay(5)
            }
        }
        beforeQueueWrite = {}

        assertEquals(1_000L, driveDb.accounts().get("perm-A")?.uploadSelectionAt)
        assertEquals(listOf(book.url), driveDb.uploads().pendingForAccount("perm-A").map { it.bookUrl })
        assertEquals(1, uploadsScheduled.get())
    }

    @Test
    fun `an upload selection finishing after an account switch leaves the new account alone`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        val book = Book(
            url = "file:///slow.epub",
            title = "Slow",
            author = "Author",
            coverPath = null,
            source = null,
            addedAt = 100L,
            lastOpenedAt = null,
            localUri = "file:///slow.epub",
            downloadState = DownloadState.DOWNLOADED,
        )
        liseurDb.bookDao().upsert(book)
        liseurDb.workIdentityDao().upsert(
            com.chmouel.liseur.data.db.BookFingerprintRow(book.url, "4".repeat(64), "md5-4", 1_000L, null, 1L),
        )
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeQueueWrite = {
            started.complete(Unit)
            release.await()
        }

        var scheduledAfterSwitch = 0
        var cancelledAfterSwitch = 0
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                controller.submitUploadSelection(setOf(book.url))
                started.await()
                beforeQueueWrite = {}
                // The reader switches to B while A's answer is still queueing its book.
                fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-b")
                fakeApi.aboutByToken["tok-b"] = DriveUserAbout("perm-B", "bob@example.com")
                controller.beginConnect(switchAccount = true)
                controller.confirmPendingSwitch()
                scheduledAfterSwitch = uploadsScheduled.get()
                cancelledAfterSwitch = uploadsCancelled.get()
                release.complete(Unit)
                while (driveDb.uploads().pendingForAccount("perm-A").isEmpty()) delay(5)
                delay(50)
            }
        }

        assertEquals(listOf(book.url), driveDb.uploads().pendingForAccount("perm-A").map { it.bookUrl })
        assertTrue(controller.uiState.first().uploadPickerVisible)
        assertEquals(scheduledAfterSwitch, uploadsScheduled.get())
        assertEquals(cancelledAfterSwitch, uploadsCancelled.get())
    }

    private class FakeDriveRemoteApi : DriveRemoteApi {
        val aboutByToken = mutableMapOf<String, DriveUserAbout>()
        var writeCalls = 0

        var aboutGate: CompletableDeferred<Unit>? = null
        val aboutEntered = CompletableDeferred<Unit>()

        override suspend fun getAbout(accessToken: String): DriveUserAbout {
            aboutGate?.let {
                aboutEntered.complete(Unit)
                it.await()
            }
            return aboutByToken[accessToken] ?: throw DriveAuthExpiredException("Unknown token")
        }

        override suspend fun listLiseurFolders(accessToken: String): List<DriveRemoteFile> = emptyList()
        override suspend fun ensureLiseurFolder(accessToken: String): LiseurFolderResolution {
            writeCalls++
            error("Unexpected write call in account-switch test")
        }
        override suspend fun getStartPageToken(accessToken: String): String = "1"
        override suspend fun listChanges(accessToken: String, pageToken: String, pageSize: Int): DriveChangesPage =
            DriveChangesPage(emptyList(), null, "1")
        override suspend fun listAppDataFiles(accessToken: String, query: String?, pageSize: Int): List<DriveRemoteFile> =
            emptyList()
        override suspend fun findAppDataFileByBatchId(accessToken: String, batchId: String): DriveRemoteFile? = null
        override suspend fun downloadMedia(accessToken: String, fileId: String): ByteArray = ByteArray(0)
        override suspend fun uploadAppDataJsonBatch(
            accessToken: String,
            name: String,
            jsonPayload: ByteArray,
            appProperties: Map<String, String>,
        ): DriveRemoteFile {
            writeCalls++
            error("Unexpected write call in account-switch test")
        }
        override suspend fun createAppDataFile(
            accessToken: String,
            name: String,
            mimeType: String,
            content: ByteArray,
            appProperties: Map<String, String>,
        ): DriveRemoteFile {
            writeCalls++
            error("Unexpected write call in account-switch test")
        }
        override suspend fun updateFileMedia(
            accessToken: String,
            fileId: String,
            mimeType: String,
            content: ByteArray,
            appProperties: Map<String, String>?,
        ): DriveRemoteFile {
            writeCalls++
            error("Unexpected write call in account-switch test")
        }
        override suspend fun deleteFile(accessToken: String, fileId: String) {
            writeCalls++
            error("Unexpected write call in account-switch test")
        }
        override suspend fun trashFile(accessToken: String, fileId: String): DriveRemoteFile {
            writeCalls++
            error("Unexpected write call in account-switch test")
        }
        override suspend fun initiateResumableUpload(
            accessToken: String,
            name: String,
            mimeType: String,
            totalBytes: Long,
            parents: List<String>,
            appProperties: Map<String, String>,
        ): String {
            writeCalls++
            error("Unexpected write call in account-switch test")
        }
        override suspend fun queryResumableUploadOffset(
            accessToken: String,
            sessionUri: String,
            totalBytes: Long,
        ): ResumableUploadStatus = ResumableUploadStatus.Expired
        override suspend fun uploadResumableChunk(
            accessToken: String,
            sessionUri: String,
            chunk: ByteArray,
            offset: Long,
            totalBytes: Long,
            mimeType: String,
        ): ResumableUploadStatus {
            writeCalls++
            error("Unexpected write call in account-switch test")
        }
        override suspend fun uploadResumableFile(
            accessToken: String,
            name: String,
            mimeType: String,
            totalBytes: Long,
            parents: List<String>,
            appProperties: Map<String, String>,
            existingSessionUri: String?,
            chunkSizeBytes: Int,
            openStream: () -> InputStream,
            onSessionUri: suspend (String) -> Unit,
            onProgress: suspend (Long, String) -> Unit,
        ): DriveRemoteFile {
            writeCalls++
            error("Unexpected write call in account-switch test")
        }
        override suspend fun downloadResumable(
            accessToken: String,
            fileId: String,
            destination: OutputStream,
            startOffset: Long,
            onRestartFromZero: () -> Unit,
            onProgress: (Long) -> Unit,
        ): DriveDownloadResult = DriveDownloadResult(0L, 0L, false)
    }

    private class FakeServerSetup(private val result: SetupResult) : ServerSetup {
        override suspend fun connect(
            rawUrl: String,
            credentials: RemoteCredentials,
            allowHttp: Boolean,
        ): SetupResult = result
    }

    /** Nothing in the library is still an answer: it is what lets Ask and Always work later. */
    @Test
    fun `confirming an empty picker records the answer and queues nothing`() = runTest {
        fakeAuth.nextAuthorize = DriveAuthResult.Authorized("tok-a")
        fakeApi.aboutByToken["tok-a"] = DriveUserAbout("perm-A", "alice@example.com")
        controller.beginConnect()
        val picker = controller.uiState.first()
        assertTrue(picker.uploadPickerVisible)
        assertTrue(picker.uploadCandidates.isEmpty())

        nowMs = 7_000L
        controller.confirmUploadSelection(emptySet())

        assertEquals(7_000L, driveDb.accounts().get("perm-A")?.uploadSelectionAt)
        assertTrue(driveDb.uploads().forAccount("perm-A").isEmpty())
        val target = requireNotNull(
            driveSync.uploadTarget.first { it?.accountKey == DriveLibrarySync.accountPeerId("perm-A") },
        )
        assertTrue(target.selectionSettled)
        assertEquals(7_000L, target.offerSince)
    }
}
