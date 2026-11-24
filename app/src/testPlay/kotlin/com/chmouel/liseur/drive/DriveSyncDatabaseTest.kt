package com.chmouel.liseur.drive

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DriveSyncDatabaseTest {

    private lateinit var db: DriveSyncDatabase

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, DriveSyncDatabase::class.java).build()
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `drive_install stores a single immutable install device id`() = runTest {
        assertNull(db.installs().get())

        val first = DriveInstall(deviceId = "550e8400-e29b-41d4-a716-446655440000", createdAt = 100L)
        assertTrue(db.installs().insertIfAbsent(first) > 0)

        // Second insert with IGNORE does not overwrite the minted device id.
        val second = DriveInstall(deviceId = "661f9511-f3ac-52e5-b827-557766551111", createdAt = 200L)
        assertEquals(-1L, db.installs().insertIfAbsent(second))

        assertEquals(first, db.installs().get())
    }

    @Test
    fun `drive_account partitions state by permissionId and supports transitions and field updates`() = runTest {
        val acctA = DriveAccount(
            permissionId = "perm-a",
            email = "a@example.com",
            state = DriveAccount.STATE_ACTIVE,
            connectedAt = 100L,
        )
        val acctB = DriveAccount(
            permissionId = "perm-b",
            email = "b@example.com",
            state = DriveAccount.STATE_PAUSED,
            connectedAt = 200L,
        )
        db.accounts().upsert(acctA)
        db.accounts().upsert(acctB)

        assertEquals("perm-a", db.accounts().active()?.permissionId)
        assertEquals("perm-a", db.accounts().observeActive().first()?.permissionId)

        db.accounts().updateFolderId("perm-a", "folder-123")
        db.accounts().updateStartPageToken("perm-a", "token-456")
        db.accounts().updateManifest("perm-a", "manifest-1", snapshotThroughSeq = 20L, deltasSinceSnapshot = 3)
        db.accounts().updateHlc("perm-a", wallMs = 1_700_000_000_000L, counter = 7)
        db.accounts().setNextSeq("perm-a", 24L)
        db.accounts().recordError("perm-a", "rate_limited")
        db.accounts().recordSuccess("perm-a", 999L)
        db.accounts().setMeteredTransfers("perm-a", true)

        val updatedA = requireNotNull(db.accounts().get("perm-a"))
        assertEquals("folder-123", updatedA.folderId)
        assertEquals("token-456", updatedA.startPageToken)
        assertEquals("manifest-1", updatedA.manifestFileId)
        assertEquals(20L, updatedA.snapshotThroughSeq)
        assertEquals(3, updatedA.deltasSinceSnapshot)
        assertEquals(1_700_000_000_000L, updatedA.hlcWallMs)
        assertEquals(7, updatedA.hlcCounter)
        assertEquals(24L, updatedA.nextSeq)
        assertEquals(999L, updatedA.lastSuccessAt)
        assertNull(updatedA.lastError)
        assertTrue(updatedA.meteredTransfers)

        db.accounts().setState("perm-b", DriveAccount.STATE_NEEDS_RECONNECT)
        db.accounts().pauseAllActive()
        assertNull(db.accounts().active())
        assertEquals(DriveAccount.STATE_PAUSED, db.accounts().get("perm-a")?.state)
        assertEquals(DriveAccount.STATE_PAUSED, db.accounts().get("perm-b")?.state)
    }

    @Test
    fun `devices, cursors, files, outbox, record states, resolutions, conflicts, session acks, and uploads partition cleanly by permissionId`() = runTest {
        // Devices & cursors
        db.devices().upsert(
            DriveDevice("perm-a", "dev-1", "Pixel 8", "mf-1", appVersionCode = 85, snapshotThroughSeq = 10, lastSeenAt = 500),
        )
        db.devices().upsert(
            DriveDevice("perm-b", "dev-1", "Pixel 8", "mf-b", appVersionCode = 85, snapshotThroughSeq = 2, lastSeenAt = 600),
        )
        assertEquals("mf-1", db.devices().get("perm-a", "dev-1")?.manifestFileId)
        assertEquals("mf-b", db.devices().get("perm-b", "dev-1")?.manifestFileId)

        db.cursors().upsert(DriveCursor("perm-a", "dev-1", "annotation", appliedSeq = 12))
        db.cursors().upsert(DriveCursor("perm-a", "dev-1", "catalog", appliedSeq = 12, pausedSchemaVersion = 2))
        db.cursors().upsert(DriveCursor("perm-b", "dev-1", "annotation", appliedSeq = 1))
        assertEquals(2, db.cursors().forDevice("perm-a", "dev-1").size)
        assertEquals(listOf("catalog"), db.cursors().pausedForAccount("perm-a").map { it.domain })
        assertEquals(1L, db.cursors().get("perm-b", "dev-1", "annotation")?.appliedSeq)

        // Files + (permission_id, kind, sha256) index query
        db.files().upsert(
            DriveFile(
                permissionId = "perm-a",
                fileId = "file-epub-1",
                name = "dune.epub",
                kind = DriveFile.KIND_EPUB,
                sha256 = "sha-dune",
                size = 1024L,
                md5 = "md5-1",
                createdTime = "2026-04-01T00:00:00Z",
            ),
        )
        db.files().upsert(
            DriveFile(
                permissionId = "perm-a",
                fileId = "file-epub-trashed",
                name = "dune-old.epub",
                kind = DriveFile.KIND_EPUB,
                sha256 = "sha-dune",
                trashed = true,
            ),
        )
        db.files().upsert(
            DriveFile(
                permissionId = "perm-b",
                fileId = "file-epub-b",
                name = "dune.epub",
                kind = DriveFile.KIND_EPUB,
                sha256 = "sha-dune",
            ),
        )
        assertEquals(
            listOf("file-epub-1"),
            db.files().byKindAndSha256("perm-a", DriveFile.KIND_EPUB, "sha-dune").map { it.fileId },
        )
        db.files().setTrashed("perm-a", "file-epub-1", true)
        assertTrue(db.files().byKindAndSha256("perm-a", DriveFile.KIND_EPUB, "sha-dune").isEmpty())

        // Outbox receipts
        db.outbox().upsert(
            DriveOutbox("perm-a", seq = 1L, batchId = "batch-1", payload = "{}", state = DriveOutbox.STATE_BUILT, createdAt = 10L),
        )
        assertEquals(1L, db.outbox().inFlight("perm-a")?.seq)
        db.outbox().markUploaded("perm-a", 1L, "drive-file-1")
        assertEquals(DriveOutbox.STATE_UPLOADED, db.outbox().inFlight("perm-a")?.state)
        db.outbox().markAcked("perm-a", 1L, "drive-file-1")
        assertNull(db.outbox().inFlight("perm-a"))
        assertEquals(DriveOutbox.STATE_ACKED, db.outbox().get("perm-a", 1L)?.state)

        // Record causal state (with version_op and res_refs_json)
        val rec = DriveRecordState(
            permissionId = "perm-a",
            domain = "annotation",
            recordKey = "ann-1",
            versionFp = "fp-res-1",
            versionOp = DriveRecordState.OP_RESOLVE,
            publishedFp = "fp-res-1",
            contentFp = "fp-content-1",
            lastSyncedRevision = 4L,
            originDeviceId = "dev-2",
            originSeq = 9L,
            ancestorsJson = """["fp-a","fp-b"]""",
            resRefsJson = """["fp-res-1"]""",
            lastHlc = "0017450000000-0001-dev-2",
            bodyJson = """{"note":"hello"}""",
        )
        db.recordStates().upsert(rec)
        assertEquals(rec, db.recordStates().get("perm-a", "annotation", "ann-1"))
        assertNull(db.recordStates().get("perm-b", "annotation", "ann-1"))

        // Durable resolutions
        val resolution = DriveResolution(
            permissionId = "perm-a",
            domain = "annotation",
            recordKey = "ann-1",
            resolutionFp = "fp-res-1",
            resolvedFpsJson = """["fp-a","fp-b"]""",
            outcome = DriveResolution.OUTCOME_PUT,
            hlc = "0017450000000-0001-dev-1",
            authorDeviceId = "dev-1",
            createdAt = 123L,
        )
        db.resolutions().upsert(resolution)
        assertEquals(listOf(resolution), db.resolutions().forRecord("perm-a", "annotation", "ann-1"))
        assertEquals(listOf(resolution), db.resolutions().authoredBy("perm-a", "dev-1"))
        assertTrue(db.resolutions().forRecord("perm-b", "annotation", "ann-1").isEmpty())

        // Conflicts lifecycle: OPEN -> RESOLVING -> RESOLVED
        val conflict = DriveConflict(
            permissionId = "perm-a",
            conflictId = "conf-1",
            domain = "annotation",
            recordKey = "ann-1",
            bookUrl = "file:///book.epub",
            kind = DriveConflict.KIND_DELETE_EDIT,
            localFp = "fp-a",
            remoteFp = "fp-b",
            remoteJson = """{"note":"edited"}""",
            remoteDeviceId = "dev-2",
            remoteHlc = "0017450000000-0002-dev-2",
            state = DriveConflict.STATE_OPEN,
            openedAt = 100L,
        )
        db.conflicts().upsert(conflict)
        assertEquals(1, db.conflicts().observeOpenCount("perm-a").first())
        assertEquals(0, db.conflicts().observeOpenCount("perm-b").first())

        db.conflicts().markResolving("perm-a", "conf-1", "keep_remote")
        assertEquals(1, db.conflicts().resolvingForAccount("perm-a").size)
        assertEquals(1, db.conflicts().unsettledForRecord("perm-a", "annotation", "ann-1").size)

        db.conflicts().markResolved("perm-a", "conf-1", "keep_remote", "fp-res-1", resolvedAt = 200L)
        assertEquals(0, db.conflicts().observeOpenCount("perm-a").first())
        assertTrue(db.conflicts().unsettledForRecord("perm-a", "annotation", "ann-1").isEmpty())
        val settled = requireNotNull(db.conflicts().get("perm-a", "conf-1"))
        assertEquals(DriveConflict.STATE_RESOLVED, settled.state)
        assertEquals("fp-res-1", settled.resolutionFp)

        // Per-account session delivery acks
        db.sessionAcks().insertAll(
            listOf(
                DriveSessionAck("perm-a", "sess-1", batchSeq = 1L, ackedAt = 100L),
                DriveSessionAck("perm-a", "sess-1", batchSeq = 2L, ackedAt = 200L), // ignored duplicate
            ),
        )
        assertTrue(db.sessionAcks().isAcked("perm-a", "sess-1"))
        assertFalse(db.sessionAcks().isAcked("perm-b", "sess-1"))
        assertEquals(1L, db.sessionAcks().forAccount("perm-a").single().batchSeq)

        // EPUB upload queue
        db.uploads().upsert(
            DriveUpload(
                permissionId = "perm-a",
                sha256 = "sha-dune",
                bookUrl = "file:///dune.epub",
                sizeBytes = 4096L,
                state = DriveUpload.STATE_PENDING,
                selectedAt = 10L,
                updatedAt = 10L,
            ),
        )
        assertEquals(1, db.uploads().pendingForAccount("perm-a").size)
        assertTrue(db.uploads().pendingForAccount("perm-b").isEmpty())

        db.uploads().updateProgress("perm-a", "sha-dune", DriveUpload.STATE_UPLOADING, "https://upload.example/1", 2048L, 20L)
        assertEquals(2048L, db.uploads().get("perm-a", "sha-dune")?.bytesSent)

        db.uploads().markUploaded("perm-a", "sha-dune", "file-epub-99", 30L)
        assertTrue(db.uploads().pendingForAccount("perm-a").isEmpty())
        val doneUpload = requireNotNull(db.uploads().get("perm-a", "sha-dune"))
        assertEquals(DriveUpload.STATE_UPLOADED, doneUpload.state)
        assertEquals("file-epub-99", doneUpload.fileId)
        assertEquals(4096L, doneUpload.bytesSent)
        assertNull(doneUpload.sessionUri)
    }

    @Test
    fun `upload row writes are conditional on the row still being selected or idle`() = runTest {
        val dao = db.uploads()
        fun row(sha: String, state: String, session: String? = null, file: String? = null) = DriveUpload(
            permissionId = "perm-a", sha256 = sha, bookUrl = "file:///$sha.epub", sizeBytes = 100L,
            state = state, sessionUri = session, fileId = file, selectedAt = 1L, updatedAt = 1L,
        )

        // The picker's delete never drops a row a worker has since given a session or moved on.
        dao.upsert(row("idle", DriveUpload.STATE_PENDING))
        dao.upsert(row("session", DriveUpload.STATE_PENDING, session = "session://1"))
        dao.upsert(row("moved", DriveUpload.STATE_UPLOADING))
        assertEquals(0, dao.deleteIfIdle("perm-a", "session", DriveUpload.STATE_PENDING))
        assertEquals(0, dao.deleteIfIdle("perm-a", "moved", DriveUpload.STATE_PENDING))
        assertEquals(1, dao.deleteIfIdle("perm-a", "idle", DriveUpload.STATE_PENDING))
        assertNull(dao.get("perm-a", "idle"))
        assertEquals("session://1", dao.get("perm-a", "session")?.sessionUri)
        assertEquals(DriveUpload.STATE_UPLOADING, dao.get("perm-a", "moved")?.state)

        // Progress and completion never revive a deselected edition.
        for (state in listOf(DriveUpload.STATE_CANCELLED, DriveUpload.STATE_REQUEUE)) {
            dao.upsert(row("dead-$state", state, session = "session://old"))
            assertEquals(0, dao.updateProgress("perm-a", "dead-$state", DriveUpload.STATE_UPLOADING, "session://new", 5L, 2L))
            assertEquals(0, dao.markUploadedIfSelected("perm-a", "dead-$state", "file-1", 2L))
            val kept = dao.get("perm-a", "dead-$state")!!
            assertEquals(state, kept.state)
            assertEquals("session://old", kept.sessionUri)
            assertNull(kept.fileId)
        }

        // A reconfirmed (PENDING) or failed row still completes.
        for (state in listOf(DriveUpload.STATE_PENDING, DriveUpload.STATE_UPLOADING, DriveUpload.STATE_FAILED)) {
            dao.upsert(row("live-$state", state, session = "session://1"))
            assertEquals(1, dao.markUploadedIfSelected("perm-a", "live-$state", "file-2", 3L))
            assertEquals(DriveUpload.STATE_UPLOADED, dao.get("perm-a", "live-$state")?.state)
        }
    }

    @Test
    fun `android backup and device transfer rules exclude drive_sync db`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for (xmlRes in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val includedDatabases = mutableListOf<String>()
            val parser = context.resources.getXml(xmlRes)
            try {
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "include") {
                        val domain = parser.getAttributeValue(null, "domain")
                        val path = parser.getAttributeValue(null, "path")
                        if (domain == "database" && path != null) includedDatabases += path
                    }
                    event = parser.next()
                }
            } finally {
                parser.close()
            }
            assertTrue("Expected database allowlist in xml", includedDatabases.isNotEmpty())
            assertFalse(DriveSyncDatabase.NAME in includedDatabases)
            assertFalse("." in includedDatabases)
        }
    }
}
