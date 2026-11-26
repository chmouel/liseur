package com.chmouel.liseur.drive

import android.app.Application
import android.content.Intent
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.AnnotationSync
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.BookEvaluation
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.EvaluationSync
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.data.db.MetadataOverrideRevision
import com.chmouel.liseur.data.db.MetadataOverrideSync
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.library.BookMetadataOverrideRepository
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.library.FieldEdit
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.BookFingerprints
import com.chmouel.liseur.domain.Identifier
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.PersonalEvaluation
import com.chmouel.liseur.domain.seriesKey
import com.chmouel.liseur.sync.LibrarySyncSwitchCoordinator
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic two-device end-to-end verification suite for Phase 4 Google Drive sync:
 * - Golden fingerprint invariants (excluding `editionSha` and `position` for annotations)
 * - Annotations: highlights, notes, book notes, bookmarks, simultaneous-upload `EDIT_EDIT`,
 *   stale delete vs `DELETE_EDIT` (**Keep edited note** / **Delete note**), delete-after-keep,
 *   and `BookRemoval` preserving annotations with zero tombstones
 * - Evaluations: rating & review sync, `EDIT_EDIT`/`DELETE_EDIT`, revision-to-fp crash recovery
 *   (both unedited and followed by immediate local edit)
 * - Metadata overrides & custom covers: title/author/description + `cover-<sha256>.webp` syncing
 *   to `DownloadState.REMOTE` stubs, hash deduplication, WebP quality ladder & 512 KiB refusal,
 *   revision-to-fp crash recovery
 * - Series: 5-book shelf reordering via `SeriesOrderDao.renumber`, explicit "no series",
 *   concurrent 5-book reorder grouped into ONE shelf conflict by `seriesKey`, clear order
 * - Library deletion lifecycle: **Remove download** local-only, **Delete from synced library**
 *   durable tombstone + Drive EPUB trash + receiver removes `drive:` stub keeping annotations
 *   and unlinks local-file copy without resurrecting; Drive-side EPUB deletion keeps history
 * - Phase 5 domains (`vocabWord`, `vocabOccurrence`, `vocabReview`, `session`) stay paused/unsupported
 */
@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DrivePhase4EndToEndTest {

    private lateinit var context: Application
    private lateinit var driveServer: Phase4FakeDriveServer
    private lateinit var deviceA: Phase4Device
    private lateinit var deviceB: Phase4Device
    private lateinit var tempDir: File
    private val clockHooks = mutableMapOf<String, () -> Unit>()

    @Before
    fun setUp() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "drive-p4-test-${System.nanoTime()}").apply { mkdirs() }
        driveServer = Phase4FakeDriveServer()
        deviceA = createDevice("dev-a", File(tempDir, "a"))
        deviceB = createDevice("dev-b", File(tempDir, "b"))
    }

    @After
    fun tearDown() {
        deviceA.close()
        deviceB.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `golden fingerprints are deterministic and exclude annotation editionSha and rendered position`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "hobbit.epub", "9780547928227", "The Hobbit", "J.R.R. Tolkien")
        val ann = BookAnnotation(
            id = "ann-golden-1",
            bookId = book.url,
            kind = AnnotationKind.HIGHLIGHT.name,
            locatorJson = """{"type":"application/xhtml+xml","href":"ch01.xhtml","locations":{"totalProgression":0.125}}""",
            text = "In a hole in the ground",
            note = "Classic opening",
            tint = "YELLOW",
            chapter = "Chapter 1",
            position = 14,
            totalProgression = 0.125,
            createdAt = 1_745_000_000_000L,
            noteCreatedAt = 1_745_000_001_000L,
            noteUpdatedAt = 1_745_000_002_000_000L,
            updatedAt = 1_745_000_002_000_000L,
        )
        deviceA.liseurDb.annotationDao().upsert(ann)
        val roundTripped = deviceA.liseurDb.annotationDao().byId(ann.id)!!

        val fpEditionA = DriveWireCodec.annotationContentFp(
            deviceA.driveSync.annotationSync.toWireBody(roundTripped, "isbn:9780547928227", "a".repeat(64)),
        )
        val fpEditionB = DriveWireCodec.annotationContentFp(
            deviceA.driveSync.annotationSync.toWireBody(roundTripped.copy(position = 99), "isbn:9780547928227", "b".repeat(64)),
        )
        val fpNoEdition = DriveWireCodec.annotationContentFp(
            deviceA.driveSync.annotationSync.toWireBody(roundTripped.copy(position = null), "isbn:9780547928227", null),
        )
        assertEquals(fpEditionA, fpEditionB)
        assertEquals(fpEditionA, fpNoEdition)
        assertEquals(64, fpEditionA.length)
    }

    @Test
    fun `annotations sync all kinds, resolve simultaneous EDIT_EDIT, stale delete vs DELETE_EDIT, and BookRemoval emits zero tombstones`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "hobbit.epub", "9780547928227", "The Hobbit", "J.R.R. Tolkien")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubUrlB = "drive:isbn:9780547928227"
        assertNotNull(deviceB.liseurDb.bookDao().getByUrl(stubUrlB))

        // 1. Happy path: create highlight, passage note, anchorless book note, and bookmark on A -> B.
        val highlight = BookAnnotation(
            id = "ann-hl", bookId = bookA.url, kind = AnnotationKind.HIGHLIGHT.name,
            locatorJson = """{"href":"ch01.xhtml","locations":{"totalProgression":0.05}}""",
            text = "In a hole", note = "Original note", tint = "YELLOW", chapter = "Ch 1",
            position = 3, totalProgression = 0.05,
            createdAt = 1_000L, noteCreatedAt = 1_100L, noteUpdatedAt = 1_100_000L, updatedAt = 1_100_000L,
        )
        val bookNote = BookAnnotation(
            id = "ann-bn", bookId = bookA.url, kind = AnnotationKind.BOOK_NOTE.name,
            locatorJson = "", text = null, note = "Whole-book thought", tint = null, chapter = null,
            position = null, totalProgression = null,
            createdAt = 2_000L, noteCreatedAt = 2_000L, noteUpdatedAt = 2_000_000L, updatedAt = 2_000_000L,
        )
        val bookmark = BookAnnotation(
            id = "ann-bm", bookId = bookA.url, kind = AnnotationKind.BOOKMARK.name,
            locatorJson = """{"href":"ch02.xhtml","locations":{"totalProgression":0.2}}""",
            text = null, note = null, tint = null, chapter = "Ch 2",
            position = 12, totalProgression = 0.2,
            createdAt = 3_000L, updatedAt = 3_000_000L,
        )
        deviceA.liseurDb.annotationDao().upsert(highlight)
        deviceA.liseurDb.annotationDao().upsert(bookNote)
        deviceA.liseurDb.annotationDao().upsert(bookmark)

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        val pulledHl = deviceB.liseurDb.annotationDao().byId("ann-hl")!!
        assertEquals("Original note", pulledHl.note)
        assertEquals("YELLOW", pulledHl.tint)
        assertEquals(1_100_000L, pulledHl.updatedAt)
        assertNull(pulledHl.position) // rendered page stays local
        assertEquals("", deviceB.liseurDb.annotationDao().byId("ann-bn")!!.locatorJson)
        assertNotNull(deviceB.liseurDb.annotationDao().byId("ann-bm"))

        // 2. Simultaneous offline uploads -> EDIT_EDIT on BOTH devices (never silent fast-forward!).
        deviceA.liseurDb.annotationDao().upsert(highlight.copy(note = "Edit from phone", updatedAt = 4_000_000L))
        deviceB.liseurDb.annotationDao().upsert(pulledHl.copy(note = "Edit from tablet", updatedAt = 4_500_000L))
        // Both upload before pulling each other!
        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()

        // Both pull each other's delta -> BOTH open EDIT_EDIT preserving both versions!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val openOnA = deviceA.driveDb.conflicts().openForAccount(PERM_ID)
        val openOnB = deviceB.driveDb.conflicts().openForAccount(PERM_ID)
        assertEquals(1, openOnA.size)
        assertEquals(1, openOnB.size)
        assertEquals(DriveConflict.KIND_EDIT_EDIT, openOnA.first().kind)
        assertEquals("Edit from phone", deviceA.liseurDb.annotationDao().byId("ann-hl")!!.note)
        assertEquals("Edit from tablet", deviceB.liseurDb.annotationDao().byId("ann-hl")!!.note)

        // Resolve via UI controller on Device A choosing KEEP_REMOTE ("Edit from tablet").
        val uiStateA = deviceA.driveSync.accountController.uiState.first()
        assertEquals(1, uiStateA.unresolvedConflictsCount)
        deviceA.driveSync.accountController.resolveConflictGroup(
            uiStateA.unresolvedConflictGroups.first(),
            DriveConflictChoice.KEEP_REMOTE,
        )
        assertEquals("Edit from tablet", deviceA.liseurDb.annotationDao().byId("ann-hl")!!.note)
        assertTrue(deviceA.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())

        // Device B pulls A's `op: "resolve"` -> closes conflict & converges cleanly!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        assertEquals("Edit from tablet", deviceB.liseurDb.annotationDao().byId("ann-hl")!!.note)
        assertEquals(DriveRecordState.OP_RESOLVE, deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-hl")!!.versionOp)

        // 3. Stale delete on untouched copy (bookmark) -> applies silently with zero conflict!
        deviceA.liseurDb.annotationDao().deleteById("ann-bm")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertNull(deviceB.liseurDb.annotationDao().byId("ann-bm"))
        assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())

        // 4. Delete-vs-edit (`DELETE_EDIT`) on bookNote: A deletes while B edits offline.
        deviceA.liseurDb.annotationDao().deleteById("ann-bn")
        val bnOnB = deviceB.liseurDb.annotationDao().byId("ann-bn")!!
        deviceB.liseurDb.annotationDao().upsert(bnOnB.copy(note = "Edited before delete arrived", updatedAt = 6_000_000L))
        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val delEditB = deviceB.driveDb.conflicts().openForAccount(PERM_ID).single()
        assertEquals(DriveConflict.KIND_DELETE_EDIT, delEditB.kind)

        // A stale dialog holding another account's copy of the conflict changes nothing.
        deviceB.driveSync.resolveConflicts(listOf(delEditB.copy(permissionId = "perm-stale")), DriveConflictChoice.DELETE, syncAfter = false)
        assertNotNull(deviceB.liseurDb.annotationDao().byId("ann-bn"))
        assertEquals(1, deviceB.driveDb.conflicts().openForAccount(PERM_ID).size)

        // Reader chooses "Delete note" (`DriveConflictChoice.DELETE`) -> emits `op: "resolve", outcome: "delete", body: null`!
        deviceB.driveSync.resolveConflicts(listOf(delEditB), DriveConflictChoice.DELETE, syncAfter = true)
        assertNull(deviceB.liseurDb.annotationDao().byId("ann-bn"))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertNull(deviceA.liseurDb.annotationDao().byId("ann-bn"))
        assertTrue(deviceA.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        val resOnA = deviceA.driveDb.resolutions().forRecord(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-bn")
        assertTrue(resOnA.any { it.outcome == DriveResolution.OUTCOME_DELETE })

        // 4b. Delete-vs-edit (`DELETE_EDIT`) resolved with "Keep edited note" (`KEEP_EDITED` -> `outcome: "put"`),
        //     followed by a subsequent delete-after-keep (unique `delFp(d, k, hlc)` must NOT collide with prior delete!).
        val passageNote = BookAnnotation(
            id = "ann-note-keep", bookId = bookA.url, kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch03.xhtml","locations":{"totalProgression":0.35}}""",
            text = "Riddles in the Dark", note = "Initial passage note", tint = "BLUE", chapter = "Ch 3",
            position = 21, totalProgression = 0.35,
            createdAt = 7_000L, noteCreatedAt = 7_000L, noteUpdatedAt = 7_000_000L, updatedAt = 7_000_000L,
        )
        deviceA.liseurDb.annotationDao().upsert(passageNote)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val noteOnB = deviceB.liseurDb.annotationDao().byId("ann-note-keep")!!

        // A deletes offline while B edits offline -> DELETE_EDIT on both devices.
        deviceA.liseurDb.annotationDao().deleteById("ann-note-keep")
        deviceB.liseurDb.annotationDao().upsert(noteOnB.copy(note = "Keep this edited note", updatedAt = 8_000_000L))
        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val keepConflictB = deviceB.driveDb.conflicts().openForAccount(PERM_ID).single { it.recordKey == "ann-note-keep" }
        assertEquals(DriveConflict.KIND_DELETE_EDIT, keepConflictB.kind)

        // Reader chooses "Keep edited note" (`DriveConflictChoice.KEEP_EDITED`) -> emits `op: "resolve", outcome: "put"`.
        deviceB.driveSync.resolveConflicts(listOf(keepConflictB), DriveConflictChoice.KEEP_EDITED, syncAfter = true)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals("Keep this edited note", deviceA.liseurDb.annotationDao().byId("ann-note-keep")!!.note)
        assertTrue(deviceA.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())

        // Subsequent delete-after-keep on A -> applies silently on B (fresh `delFp` with newer HLC!).
        deviceA.liseurDb.annotationDao().deleteById("ann-note-keep")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertNull(deviceB.liseurDb.annotationDao().byId("ann-note-keep"))
        assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())

        // 5. BookRemoval.deleteByUrls on Device B preserves annotations and emits ZERO annotation tombstones!
        val removalB = BookRemoval(
            bookDao = deviceB.liseurDb.bookDao(),
            sessionDao = deviceB.liseurDb.readingSessionDao(),
            peerStateDao = deviceB.liseurDb.syncPeerStateDao(),
            identityDao = deviceB.liseurDb.workIdentityDao(),
            progressDao = deviceB.liseurDb.readingProgressDao(),
            annotationDao = deviceB.liseurDb.annotationDao(),
            annotationSyncDao = deviceB.liseurDb.annotationSyncDao(),
            inTransaction = { work -> deviceB.liseurDb.withTransaction { work() } },
        )
        removalB.deleteByUrls(listOf(stubUrlB))
        assertNotNull(deviceB.liseurDb.annotationDao().byId("ann-hl"))
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertNotNull(deviceA.liseurDb.annotationDao().byId("ann-hl"))
    }

    @Test
    fun `crash between liseur_db and drive_sync_db on incoming op=resolve settles OPEN conflict on Agreement and Stale replay`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "crash-res.epub", "9780547928227", "The Hobbit", "J.R.R. Tolkien")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780547928227"

        // Seed 3 annotations to test:
        // (1) incoming `op: "resolve", outcome: "put"` Agreement replay after Tx1-Tx2 crash,
        // (2) incoming `op: "resolve", outcome: "delete"` Agreement replay after Tx1-Tx2 crash,
        // (3) incoming `op: "resolve", outcome: "put"` Stale replay after Tx1-Tx2 crash + immediate local edit.
        val anns = listOf("ann-crash-put", "ann-crash-del", "ann-crash-stale").mapIndexed { idx, id ->
            BookAnnotation(
                id = id, bookId = bookA.url, kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch0${idx + 1}.xhtml"}""", text = "Passage $idx", note = "v0-$idx",
                createdAt = 1_000L + idx, updatedAt = 1_000_000L + idx,
            )
        }
        anns.forEach { deviceA.liseurDb.annotationDao().upsert(it) }
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // Create concurrent offline conflicts on all 3 annotations.
        deviceA.liseurDb.annotationDao().upsert(anns[0].copy(note = "A-put", updatedAt = 2_000_000L))
        deviceB.liseurDb.annotationDao().upsert(deviceB.liseurDb.annotationDao().byId("ann-crash-put")!!.copy(note = "B-put", updatedAt = 2_100_000L))

        deviceA.liseurDb.annotationDao().deleteById("ann-crash-del")
        deviceB.liseurDb.annotationDao().upsert(deviceB.liseurDb.annotationDao().byId("ann-crash-del")!!.copy(note = "B-del-edit", updatedAt = 2_200_000L))

        deviceA.liseurDb.annotationDao().upsert(anns[2].copy(note = "A-stale", updatedAt = 2_300_000L))
        deviceB.liseurDb.annotationDao().upsert(deviceB.liseurDb.annotationDao().byId("ann-crash-stale")!!.copy(note = "B-stale", updatedAt = 2_400_000L))

        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(3, deviceB.driveDb.conflicts().openForAccount(PERM_ID).size)

        // Snapshot Device B's drive_sync.db state BEFORE pulling Device A's `op: "resolve"` batch.
        val preResolveConflictsB = deviceB.driveDb.conflicts().openForAccount(PERM_ID)
        val preResolveStatesB = listOf("ann-crash-put", "ann-crash-del", "ann-crash-stale")
            .associateWith { deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, it)!! }
        val preResolveCursorSeq = deviceB.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_ANNOTATION)!!.appliedSeq
        val preResolveStartPageToken = deviceB.driveDb.accounts().get(PERM_ID)!!.startPageToken

        // Device A resolves all 3 conflicts:
        // - ann-crash-put: KEEP_LOCAL ("A-put", outcome: "put")
        // - ann-crash-del: DELETE (outcome: "delete")
        // - ann-crash-stale: KEEP_LOCAL ("A-stale", outcome: "put")
        val openA = deviceA.driveDb.conflicts().openForAccount(PERM_ID).associateBy { it.recordKey }
        deviceA.driveSync.resolveConflicts(listOf(openA.getValue("ann-crash-put")), DriveConflictChoice.KEEP_LOCAL, syncAfter = false)
        deviceA.driveSync.resolveConflicts(listOf(openA.getValue("ann-crash-del")), DriveConflictChoice.DELETE, syncAfter = false)
        deviceA.driveSync.resolveConflicts(listOf(openA.getValue("ann-crash-stale")), DriveConflictChoice.KEEP_LOCAL, syncAfter = true)

        // Device B pulls A's `op: "resolve"` records (committing Tx 1 to liseur.db AND Tx 2 to drive_sync.db).
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals("A-put", deviceB.liseurDb.annotationDao().byId("ann-crash-put")!!.note)
        assertNull(deviceB.liseurDb.annotationDao().byId("ann-crash-del"))
        assertEquals("A-stale", deviceB.liseurDb.annotationDao().byId("ann-crash-stale")!!.note)

        // Now roll back ONLY drive_sync.db to simulate a crash RIGHT AFTER Tx 1 committed liseur.db
        // and BEFORE Tx 2 committed drive_resolution / drive_conflict / drive_record_state / drive_cursor / startPageToken!
        deviceB.driveDb.withTransaction {
            for ((key, priorState) in preResolveStatesB) {
                deviceB.driveDb.recordStates().upsert(priorState)
                deviceB.driveDb.openHelper.writableDatabase.execSQL(
                    "DELETE FROM drive_resolution WHERE permission_id = ? AND domain = ? AND record_key = ?",
                    arrayOf<Any>(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, key),
                )
            }
            for (priorConflict in preResolveConflictsB) {
                deviceB.driveDb.conflicts().upsert(priorConflict)
            }
            deviceB.driveDb.cursors().upsert(DriveCursor(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_ANNOTATION, preResolveCursorSeq))
            preResolveStartPageToken?.let { deviceB.driveDb.accounts().updateStartPageToken(PERM_ID, it) }
        }
        assertEquals(3, deviceB.driveDb.conflicts().openForAccount(PERM_ID).size)

        // Before replay runs, user edits `ann-crash-stale` locally in liseur.db (post-crash local edit -> Stale replay path)!
        val staleRowB = deviceB.liseurDb.annotationDao().byId("ann-crash-stale")!!
        deviceB.liseurDb.annotationDao().upsert(staleRowB.copy(note = "B-edited-after-crash", updatedAt = 3_000_000L))

        // Replay on Device B:
        // - `ann-crash-put` hits Agreement + Step 0 closes OPEN conflict & persists resolution.
        // - `ann-crash-del` hits Agreement + Step 0 closes OPEN conflict, persists resolution, clears stale bodyJson/contentFp.
        // - `ann-crash-stale` hits POST_CRASH_LOCAL_EDIT_PRESERVED + Step 0 closes OPEN conflict, preserves edit, and pushes it!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        assertEquals("A-put", deviceB.liseurDb.annotationDao().byId("ann-crash-put")!!.note)
        assertNull(deviceB.liseurDb.annotationDao().byId("ann-crash-del"))
        assertEquals("B-edited-after-crash", deviceB.liseurDb.annotationDao().byId("ann-crash-stale")!!.note)

        val delStateB = deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-crash-del")!!
        assertEquals(DriveRecordState.OP_RESOLVE, delStateB.versionOp)
        assertNull(delStateB.contentFp)
        assertNull(delStateB.bodyJson)
        assertTrue(deviceB.driveDb.resolutions().forRecord(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-crash-put").isNotEmpty())
        assertTrue(deviceB.driveDb.resolutions().forRecord(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-crash-del").isNotEmpty())
        assertTrue(deviceB.driveDb.resolutions().forRecord(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-crash-stale").isNotEmpty())

        // Device A pulls B's post-crash edit and converges cleanly without conflict!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals("B-edited-after-crash", deviceA.liseurDb.annotationDao().byId("ann-crash-stale")!!.note)
        assertTrue(deviceA.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        assertNotNull(deviceB.liseurDb.bookDao().getByUrl(stubB))
    }

    @Test
    fun `a successor that outruns its resolution on a third device converges on the successor, not the stale resolution`() = runTest {
        val deviceC = createDevice("dev-c", File(tempDir, "c"))
        try {
            deviceA.connect()
            deviceB.connect()
            deviceC.connect()
            val bookA = seedBook(deviceA, "outrun.epub", "9780547928227", "The Hobbit", "J.R.R. Tolkien")
            val id = "ann-outrun"
            deviceA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = id, bookId = bookA.url, kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch01.xhtml"}""", text = "Passage", note = "v0",
                    createdAt = 1_000L, updatedAt = 1_000_000L,
                ),
            )
            for (device in listOf(deviceA, deviceB, deviceC, deviceA, deviceB)) {
                assertEquals(SyncOutcome.Success, device.changeEngine.sync().outcome)
            }
            suspend fun note(device: Phase4Device) = device.liseurDb.annotationDao().byId(id)?.note

            // A and B edit concurrently; C sees both and settles the conflict with an op=resolve.
            driveServer.hideAuthorsFor("dev-a", "dev-b", "dev-c")
            deviceA.liseurDb.annotationDao().upsert(deviceA.liseurDb.annotationDao().byId(id)!!.copy(note = "edit-A", updatedAt = 2_000_000L))
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            driveServer.hideAuthorsFor("dev-b", "dev-a", "dev-c")
            deviceB.liseurDb.annotationDao().upsert(deviceB.liseurDb.annotationDao().byId(id)!!.copy(note = "edit-B", updatedAt = 2_100_000L))
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
            driveServer.clearAuthorHides()
            assertEquals(SyncOutcome.Success, deviceC.changeEngine.sync().outcome)
            val conflictC = deviceC.driveDb.conflicts().openForAccount(PERM_ID).single { it.recordKey == id }
            deviceC.driveSync.resolveConflicts(listOf(conflictC), DriveConflictChoice.KEEP_LOCAL, syncAfter = true)
            val resolution = driveServer.deltaRecords("dev-c").single { it.op == DriveRecordState.OP_RESOLVE && it.k == id }

            // B adopts the resolution and then edits again: its successor descends from the resolution.
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
            assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).none { it.recordKey == id })
            deviceB.liseurDb.annotationDao().upsert(deviceB.liseurDb.annotationDao().byId(id)!!.copy(note = "after-resolution", updatedAt = 3_000_000L))
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
            val successor = driveServer.deltaRecords("dev-b").last { it.k == id }
            assertTrue(resolution.fp in successor.resRefs)

            // A receives B's deltas but not C's: the successor arrives before the resolution it refers to.
            driveServer.hideAuthorsFor("dev-a", "dev-c")
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            assertEquals("edit-A", note(deviceA))
            val parked = deviceA.driveDb.conflicts().unsettledForRecord(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, id)
                .single { it.remoteFp == successor.fp }
            assertEquals(DriveRecordState.OP_PUT, parked.remoteOp)
            assertNotNull(parked.remoteSeq)
            assertTrue(resolution.fp in DriveConflictEngine.decodeFps(parked.remoteResRefsJson))
            assertTrue(DriveConflictEngine.decodeFps(parked.remoteAncJson).isNotEmpty())

            driveServer.clearAuthorHides()
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            assertEquals("after-resolution", note(deviceA))
            assertTrue(deviceA.driveDb.conflicts().unsettledForRecord(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, id).isEmpty())
            val stateA = deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, id)!!
            assertEquals(successor.fp, stateA.versionFp)
            assertEquals(
                DriveWireCodec.annotationContentFp(DriveWireCodec.decodeAnnotationBody(successor.body!!)!!),
                stateA.contentFp,
            )
            assertEquals(
                stateA.contentFp,
                deviceA.liseurDb.annotationSyncDao().get(DriveLibrarySync.accountPeerId(PERM_ID), id)!!.ackedFingerprint,
            )

            // Replaying every remote record, and a further run, keeps the successor.
            for (author in listOf("dev-b", "dev-c")) {
                deviceA.driveDb.cursors().upsert(DriveCursor(PERM_ID, author, DriveWireCodec.DOMAIN_ANNOTATION, 0L))
            }
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            assertEquals("after-resolution", note(deviceA))

            for (device in listOf(deviceC, deviceB, deviceA, deviceC)) {
                assertEquals(SyncOutcome.Success, device.changeEngine.sync().outcome)
                assertEquals("after-resolution", note(device))
                assertTrue(device.driveDb.conflicts().openForAccount(PERM_ID).none { it.recordKey == id })
            }
        } finally {
            deviceC.close()
        }
    }

    @Test
    fun `evaluations sync rating and review and recover cleanly from Tx1-Tx2 crash both unedited and post-crash edited`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "dune.epub", "9780441172719", "Dune", "Frank Herbert")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780441172719"

        // Happy path: A rates 4.5 stars ("9") and writes a review.
        deviceA.liseurDb.bookEvaluationDao().edit(bookA.url, PersonalEvaluation.RATING, "9", "Dune", "Frank Herbert", 1_000L)
        deviceA.liseurDb.bookEvaluationDao().edit(bookA.url, PersonalEvaluation.REVIEW, "Masterpiece", "Dune", "Frank Herbert", 1_100L)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        assertEquals("9", deviceB.liseurDb.bookEvaluationDao().get(stubB, PersonalEvaluation.RATING)!!.value)
        assertEquals("Masterpiece", deviceB.liseurDb.bookEvaluationDao().get(stubB, PersonalEvaluation.REVIEW)!!.value)

        // Crash simulation A (unedited): delete drive_record_state + reset cursor on B after Tx 1 already committed evaluation_sync!
        val ratingKey = DriveEvaluationSync.recordKey("isbn:9780441172719", PersonalEvaluation.RATING)
        val evalSyncRowBefore = deviceB.liseurDb.bookEvaluationDao().state(DriveLibrarySync.accountPeerId(PERM_ID), stubB, PersonalEvaluation.RATING)!!
        assertNotNull(evalSyncRowBefore.sentJson)
        deviceB.driveDb.recordStates().delete(PERM_ID, DriveWireCodec.DOMAIN_EVALUATION, ratingKey)
        deviceB.driveDb.cursors().upsert(DriveCursor(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_EVALUATION, appliedSeq = 0L))

        // Replay: revision-to-fp adapter recomputes contentFp from committed book_evaluation, repairs state, hits Agreement!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val repairedState = deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_EVALUATION, ratingKey)!!
        assertEquals(evalSyncRowBefore.ackedRevision, repairedState.lastSyncedRevision)
        assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())

        // Crash simulation B (post-crash local edit before replay):
        // Simulate Tx 1 having committed "Masterpiece" (rev R1), Tx 2 crashed (recordState missing & cursor unadvanced),
        // AND the reader immediately edited the review locally to "Masterpiece v2" (rev R2 > R1) before replay!
        val reviewKey = DriveEvaluationSync.recordKey("isbn:9780441172719", PersonalEvaluation.REVIEW)
        deviceB.driveDb.recordStates().delete(PERM_ID, DriveWireCodec.DOMAIN_EVALUATION, reviewKey)
        deviceB.driveDb.cursors().upsert(DriveCursor(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_EVALUATION, appliedSeq = 0L))
        deviceB.liseurDb.bookEvaluationDao().edit(stubB, PersonalEvaluation.REVIEW, "Masterpiece v2", "Dune", "Frank Herbert", 2_000L)

        // Replay must preserve "Masterpiece v2" (Stale replay via sent_json.vfp in anc) and publish it cleanly to A!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals("Masterpiece v2", deviceB.liseurDb.bookEvaluationDao().get(stubB, PersonalEvaluation.REVIEW)!!.value)
        assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals("Masterpiece v2", deviceA.liseurDb.bookEvaluationDao().get(bookA.url, PersonalEvaluation.REVIEW)!!.value)
    }

    @Test
    fun `metadata overrides and content-addressed custom covers sync to REMOTE stubs, deduplicate by hash, and enforce 512 KiB budget`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val book1A = seedBook(deviceA, "book1.epub", "9780547928227", "Hobbit", "Tolkien")
        val book2A = seedBook(deviceA, "book2.epub", "9780618391110", "Silmarillion", "Tolkien")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        val stub1B = "drive:isbn:9780547928227"
        val stub2B = "drive:isbn:9780618391110"
        assertEquals(DownloadState.REMOTE, deviceB.liseurDb.bookDao().getByUrl(stub1B)!!.downloadState)

        // Apply title, author, description, and custom cover overrides on A (using real BookMetadataOverrideRepository!).
        val repoA = BookMetadataOverrideRepository(
            bookDao = deviceA.liseurDb.bookDao(),
            overrideDao = deviceA.liseurDb.bookMetadataOverrideDao(),
            coversDir = deviceA.coversDir,
            inTransaction = { work -> deviceA.liseurDb.withTransaction { work() } },
            encodeCoverJpeg = { it },
        )
        assertTrue(
            repoA.applyTextEdits(
                bookUrl = book1A.url,
                title = FieldEdit.Set("The Hobbit (Illustrated)"),
                author = FieldEdit.Set("J.R.R. Tolkien"),
                description = FieldEdit.Set("An unexpected journey."),
            ),
        )
        val sharedCoverBytes = "FAKE-SOURCE-COVER-IMAGE-BYTES-12345".toByteArray()
        assertNotNull(repoA.coverFromBytes(book1A.url, sharedCoverBytes))
        // Set the EXACT same cover image on book2 to prove content-addressed deduplication!
        assertNotNull(repoA.coverFromBytes(book2A.url, sharedCoverBytes))

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        // Only ONE cover-<sha256>.webp blob uploaded to appDataFolder for both books!
        val uploadedCovers = driveServer.appDataFilesSnapshot().filter { it.appProperties["kind"] == DriveFile.KIND_COVER }
        assertEquals(1, uploadedCovers.size)
        assertTrue(uploadedCovers.single().name.startsWith("cover-"))
        assertTrue(uploadedCovers.single().name.endsWith(".webp"))

        // Device B pulls while BOTH books are DownloadState.REMOTE (`localUri == null`)!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val remoteBook1B = deviceB.liseurDb.bookDao().getByUrl(stub1B)!!
        val remoteBook2B = deviceB.liseurDb.bookDao().getByUrl(stub2B)!!
        assertEquals(DownloadState.REMOTE, remoteBook1B.downloadState)
        assertNull(remoteBook1B.localUri)
        assertEquals("The Hobbit (Illustrated)", remoteBook1B.title)
        assertEquals("J.R.R. Tolkien", remoteBook1B.author)
        assertEquals("An unexpected journey.", remoteBook1B.userDescription)
        assertTrue(remoteBook1B.coverOverridden)
        assertNotNull(remoteBook1B.userCoverPath)
        assertTrue(File(remoteBook1B.userCoverPath!!).isFile)
        assertEquals(remoteBook1B.userCoverSha256, remoteBook2B.userCoverSha256)
        // Each book owns its cover file, so resetting one never deletes the other's image.
        assertNotEquals(remoteBook1B.userCoverPath, remoteBook2B.userCoverPath)
        BookMetadataOverrideRepository(
            bookDao = deviceB.liseurDb.bookDao(),
            overrideDao = deviceB.liseurDb.bookMetadataOverrideDao(),
            coversDir = deviceB.coversDir,
            inTransaction = { work -> deviceB.liseurDb.withTransaction { work() } },
            encodeCoverJpeg = { it },
        ).resetCover(stub1B)
        assertTrue(File(deviceB.liseurDb.bookDao().getByUrl(stub2B)!!.userCoverPath!!).isFile)

        // Verify WebP quality ladder (85 -> 75 -> 65 -> 55 -> 45) & refusal when > 512 KiB.
        val attemptedQualities = mutableListOf<Int>()
        val ladderCodec = DriveCoverCodec(
            coversDir = File(tempDir, "ladder"),
            store = deviceA.driveDb,
            driveApi = driveServer,
            encoder = { _, _, quality ->
                attemptedQualities.add(quality)
                val size = if (quality > 55) 600 * 1024 else 200 * 1024
                EncodedImageFrame(ByteArray(size) { quality.toByte() }, width = 800, height = 1200)
            },
        )
        val ladderResult = ladderCodec.encodeToBudget(byteArrayOf(1, 2, 3))
        assertTrue(ladderResult is DriveCoverEncodeResult.Encoded)
        assertEquals(listOf(85, 75, 65, 55), attemptedQualities)

        val tooLargeCodec = DriveCoverCodec(
            coversDir = File(tempDir, "toolarge"),
            store = deviceA.driveDb,
            driveApi = driveServer,
            encoder = { _, _, _ -> EncodedImageFrame(ByteArray(700 * 1024), width = 1200, height = 1200) },
        )
        assertTrue(tooLargeCodec.encodeToBudget(byteArrayOf(1, 2, 3)) is DriveCoverEncodeResult.TooLarge)

        // Crash recovery on metadata_override_sync (post-crash local edit before replay):
        val titleKey = DriveMetadataSync.recordKey("isbn:9780547928227", MetadataOverrideFields.TITLE)
        deviceB.driveDb.recordStates().delete(PERM_ID, DriveWireCodec.DOMAIN_METADATA, titleKey)
        deviceB.driveDb.cursors().upsert(DriveCursor(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_METADATA, appliedSeq = 0L))
        val repoB = BookMetadataOverrideRepository(
            bookDao = deviceB.liseurDb.bookDao(),
            overrideDao = deviceB.liseurDb.bookMetadataOverrideDao(),
            coversDir = deviceB.coversDir,
            inTransaction = { work -> deviceB.liseurDb.withTransaction { work() } },
        )
        repoB.applyTextEdits(stub1B, title = FieldEdit.Set("The Hobbit (Reader's Cut)"))
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals("The Hobbit (Reader's Cut)", deviceB.liseurDb.bookDao().getByUrl(stub1B)!!.title)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals("The Hobbit (Reader's Cut)", deviceA.liseurDb.bookDao().getByUrl(book1A.url)!!.title)
    }

    @Test
    fun `series syncs 5-book shelf reorder, explicit no-series, and groups concurrent shelf conflict into one choice`() = runTest {
        deviceA.connect()
        deviceB.connect()

        // Seed 5 books in the same series "Middle-earth" (valid ISBN-13 check digits!).
        val isbns = listOf("9780000000019", "9780000000026", "9780000000033", "9780000000040", "9780000000057")
        val booksA = isbns.mapIndexed { idx, isbn ->
            val i = idx + 1
            seedBook(
                device = deviceA,
                fileName = "me-$i.epub",
                isbn13 = isbn,
                title = "Volume $i",
                author = "Tolkien",
                fileSeriesName = "Middle-earth",
                fileSeriesIndex = i.toDouble(),
            )
        }
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val urlsB = isbns.map { isbn -> "drive:isbn:$isbn" }

        // 1. Device A reorders the 5-book shelf via SeriesOrderDao.renumber -> B converges identically!
        val orderA = listOf(booksA[4].url, booksA[3].url, booksA[2].url, booksA[1].url, booksA[0].url)
        assertTrue(deviceA.liseurDb.seriesOrderDao().renumber(seriesKey("Middle-earth"), orderA))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(5.0, deviceB.liseurDb.bookDao().getByUrl(urlsB[0])!!.seriesIndex!!, 0.001)
        assertEquals(1.0, deviceB.liseurDb.bookDao().getByUrl(urlsB[4])!!.seriesIndex!!, 0.001)

        // 2. Concurrent 5-book shelf reorder offline on A and B -> grouped into ONE UI conflict by seriesKey!
        //    Both orders are derangements of step 1 (`[4, 3, 2, 1, 0]`) and disagree on all 5 books.
        val orderA2 = listOf(booksA[0].url, booksA[1].url, booksA[3].url, booksA[4].url, booksA[2].url)
        val orderB2 = listOf(urlsB[2], urlsB[4], urlsB[0], urlsB[3], urlsB[1])
        assertTrue(deviceA.liseurDb.seriesOrderDao().renumber(seriesKey("Middle-earth"), orderA2))
        assertTrue(deviceB.liseurDb.seriesOrderDao().renumber(seriesKey("Middle-earth"), orderB2))

        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // UI groups all 5 conflicting books on the Middle-earth shelf into ONE single shelf choice!
        val uiStateB = deviceB.driveSync.accountController.uiState.first()
        assertEquals(1, uiStateB.unresolvedConflictsCount)
        val shelfGroup = uiStateB.unresolvedConflictGroups.single()
        assertEquals(DriveWireCodec.DOMAIN_SERIES, shelfGroup.domain)
        assertEquals(5, shelfGroup.conflicts.size)

        // Resolve the single shelf group choosing KEEP_LOCAL (B's order) -> emits per-book `op: "resolve"`!
        deviceB.driveSync.accountController.resolveConflictGroup(shelfGroup, DriveConflictChoice.KEEP_LOCAL)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertTrue(deviceA.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        // Book 3 (index 2) is #1 on both devices!
        assertEquals(1.0, deviceA.liseurDb.bookDao().getByUrl(booksA[2].url)!!.seriesIndex!!, 0.001)
        assertEquals(1.0, deviceB.liseurDb.bookDao().getByUrl(urlsB[2])!!.seriesIndex!!, 0.001)

        // 3. Clear shelf order via SeriesOrderDao.clearOrder -> restores source fileSeriesIndex on B!
        assertTrue(deviceA.liseurDb.seriesOrderDao().clearOrder(seriesKey("Middle-earth"), booksA.map { it.url }))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertFalse(deviceB.liseurDb.bookDao().getByUrl(urlsB[2])!!.indexOverridden)
        assertEquals(3.0, deviceB.liseurDb.bookDao().getByUrl(urlsB[2])!!.seriesIndex!!, 0.001)

        // 4. Explicit "no series" (`seriesOverridden = true, userSeriesName = null`) stays un-shelved on B.
        deviceA.liseurDb.bookDao().setSeriesOverride(booksA[0].url, null, null, 9_000L)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val unshelvedB = deviceB.liseurDb.bookDao().getByUrl(urlsB[0])!!
        assertTrue(unshelvedB.seriesOverridden)
        assertNull(unshelvedB.seriesName)
    }

    private fun overrideRepo(device: Phase4Device) = BookMetadataOverrideRepository(
        bookDao = device.liseurDb.bookDao(),
        overrideDao = device.liseurDb.bookMetadataOverrideDao(),
        coversDir = device.coversDir,
        inTransaction = { work -> device.liseurDb.withTransaction { work() } },
        encodeCoverJpeg = { it },
    )

    @Test
    fun `choosing a remote cover that cannot be fetched and verified keeps the conflict open and the local cover`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "cover-conflict.epub", "9780547928234", "Cover", "Author")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780547928234"
        assertNotNull(overrideRepo(deviceA).coverFromBytes(bookA.url, "COVER-FROM-A".toByteArray()))
        assertNotNull(overrideRepo(deviceB).coverFromBytes(stubB, "COVER-FROM-B".toByteArray()))
        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val conflict = deviceB.driveDb.conflicts().openForAccount(PERM_ID).single { it.domain == DriveWireCodec.DOMAIN_METADATA }
        val localSha = deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userCoverSha256

        driveServer.failNextCoverDownloads(10)
        deviceB.driveSync.resolveConflicts(listOf(conflict), DriveConflictChoice.KEEP_REMOTE, syncAfter = false)
        assertEquals(localSha, deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userCoverSha256)
        assertEquals(DriveConflict.STATE_OPEN, deviceB.driveDb.conflicts().get(PERM_ID, conflict.conflictId)!!.state)

        driveServer.failNextCoverDownloads(0)
        driveServer.corruptNextCoverDownloads(10)
        deviceB.driveSync.resolveConflicts(listOf(conflict), DriveConflictChoice.KEEP_REMOTE, syncAfter = false)
        assertEquals(localSha, deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userCoverSha256)
        assertEquals(DriveConflict.STATE_OPEN, deviceB.driveDb.conflicts().get(PERM_ID, conflict.conflictId)!!.state)

        driveServer.corruptNextCoverDownloads(0)
        deviceB.driveSync.resolveConflicts(listOf(conflict), DriveConflictChoice.KEEP_REMOTE, syncAfter = false)
        val resolved = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
        assertTrue(resolved.userCoverSha256 != localSha)
        assertTrue(File(resolved.userCoverPath!!).isFile)
    }

    @Test
    fun `keeping a cover edited while its conflict was open resolves and reaches the other device`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "cover-keep.epub", "9780547928265", "Keep", "Author")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780547928265"
        assertNotNull(overrideRepo(deviceA).coverFromBytes(bookA.url, "COVER-KEEP-A".toByteArray()))
        assertNotNull(overrideRepo(deviceB).coverFromBytes(stubB, "COVER-KEEP-B".toByteArray()))
        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val conflict = deviceB.driveDb.conflicts().openForAccount(PERM_ID).single { it.domain == DriveWireCodec.DOMAIN_METADATA }

        val previousSha = deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userCoverSha256
        // A new cover while the conflict is open is never collected, so only the reader's image holds it.
        assertNotNull(overrideRepo(deviceB).coverFromBytes(stubB, "COVER-KEEP-B-AGAIN".toByteArray()))
        deviceB.driveSync.resolveConflicts(listOf(conflict), DriveConflictChoice.KEEP_LOCAL, syncAfter = true)

        assertEquals(DriveConflict.STATE_RESOLVED, deviceB.driveDb.conflicts().get(PERM_ID, conflict.conflictId)!!.state)
        val keptOnB = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
        val keptSha = keptOnB.userCoverSha256
        assertNotEquals(previousSha, keptSha)
        assertTrue(String(File(keptOnB.userCoverPath!!).readBytes()).contains("COVER-KEEP-B-AGAIN"))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val coverOnA = deviceA.liseurDb.bookDao().getByUrl(bookA.url)!!
        assertEquals(keptSha, coverOnA.userCoverSha256)
        assertTrue(File(coverOnA.userCoverPath!!).isFile)
        assertEquals(keptSha, deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userCoverSha256)
    }

    @Test
    fun `keeping the edited remote cover after a local reset fetches it and resolves`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "cover-reset.epub", "9780547928272", "Reset", "Author")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertNotNull(overrideRepo(deviceA).coverFromBytes(bookA.url, "COVER-RESET-SHARED".toByteArray()))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780547928272"
        assertTrue(deviceB.liseurDb.bookDao().getByUrl(stubB)!!.coverOverridden)

        // B resets its cover while A sets a new one; B never sees A's new blob before choosing.
        overrideRepo(deviceB).resetCover(stubB)
        assertNotNull(overrideRepo(deviceA).coverFromBytes(bookA.url, "COVER-RESET-A-NEW".toByteArray()))
        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val conflict = deviceB.driveDb.conflicts().openForAccount(PERM_ID).single { it.domain == DriveWireCodec.DOMAIN_METADATA }
        assertEquals(DriveConflict.KIND_DELETE_EDIT, conflict.kind)

        deviceB.driveSync.resolveConflicts(listOf(conflict), DriveConflictChoice.KEEP_EDITED, syncAfter = true)

        assertEquals(DriveConflict.STATE_RESOLVED, deviceB.driveDb.conflicts().get(PERM_ID, conflict.conflictId)!!.state)
        val kept = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
        assertTrue(kept.coverOverridden)
        assertTrue(File(kept.userCoverPath!!).isFile)
    }

    @Test
    fun `switching accounts while a remote cover resolution downloads commits nothing`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "cover-switch.epub", "9780547928258", "Switch", "Author")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780547928258"
        assertNotNull(overrideRepo(deviceA).coverFromBytes(bookA.url, "COVER-SWITCH-A".toByteArray()))
        assertNotNull(overrideRepo(deviceB).coverFromBytes(stubB, "COVER-SWITCH-B".toByteArray()))
        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val conflict = deviceB.driveDb.conflicts().openForAccount(PERM_ID).single { it.domain == DriveWireCodec.DOMAIN_METADATA }
        val localSha = deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userCoverSha256

        driveServer.duringNextCoverDownload = {
            deviceB.driveSync.activateAccount("perm-other", "other@example.com", now = 9_000L)
        }
        deviceB.driveSync.resolveConflicts(listOf(conflict), DriveConflictChoice.KEEP_REMOTE, syncAfter = false)

        assertEquals(localSha, deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userCoverSha256)
        assertEquals(DriveConflict.STATE_OPEN, deviceB.driveDb.conflicts().get(PERM_ID, conflict.conflictId)!!.state)
        assertTrue(deviceB.driveDb.resolutions().forRecord(PERM_ID, DriveWireCodec.DOMAIN_METADATA, conflict.recordKey).isEmpty())
    }

    @Test
    fun `a cover edit made while a remote cover downloads is not overwritten`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "cover-race.epub", "9780547928241", "Race", "Author")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780547928241"
        assertNotNull(overrideRepo(deviceA).coverFromBytes(bookA.url, "COVER-REMOTE".toByteArray()))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        var editedSha: String? = null
        driveServer.duringNextCoverDownload = {
            assertNotNull(overrideRepo(deviceB).coverFromBytes(stubB, "COVER-LOCAL-DURING-DOWNLOAD".toByteArray()))
            editedSha = deviceB.liseurDb.bookDao().getByUrl(stubB)?.userCoverSha256
        }
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertNotNull(editedSha)
        assertEquals(editedSha, deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userCoverSha256)

        // The deferred remote cover now meets the reader's newer edit as a conflict, not an overwrite.
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(editedSha, deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userCoverSha256)

        // Keeping that local cover resolves, uploads its blob and reaches the other device.
        val coverConflict = deviceB.driveDb.conflicts().openForAccount(PERM_ID).single { it.domain == DriveWireCodec.DOMAIN_METADATA }
        deviceB.driveSync.resolveConflicts(listOf(coverConflict), DriveConflictChoice.KEEP_LOCAL, syncAfter = true)
        assertEquals(
            DriveConflict.STATE_RESOLVED,
            deviceB.driveDb.conflicts().get(PERM_ID, coverConflict.conflictId)!!.state,
        )
        val keptSha = deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userCoverSha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val coverOnA = deviceA.liseurDb.bookDao().getByUrl(bookA.url)!!
        assertEquals(keptSha, coverOnA.userCoverSha256)
        assertTrue(File(coverOnA.userCoverPath!!).isFile)
        assertTrue(deviceA.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
    }

    @Test
    fun `an annotation that arrives before its catalog entry waits and applies once the catalog lands`() = runTest {
        // Remote streams apply in device-id order: dev-a (annotation) is read before dev-z (catalog).
        val owner = createDevice("dev-z", File(tempDir, "z"))
        val fresh = createDevice("dev-c", File(tempDir, "c-fresh"))
        try {
            owner.connect()
            deviceA.connect()
            fresh.connect()
            seedBook(owner, "early.epub", "9780618640171", "Early", "Author")
            assertEquals(SyncOutcome.Success, owner.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            val stub = "drive:isbn:9780618640171"
            assertNotNull(deviceA.liseurDb.bookDao().getByUrl(stub))
            deviceA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-early", bookId = stub, kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch01.xhtml"}""", text = "Early", note = "Arrives first",
                    createdAt = 1_000L, updatedAt = 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

            assertEquals(SyncOutcome.Success, fresh.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, fresh.changeEngine.sync().outcome)
            assertNotNull(fresh.liseurDb.bookDao().getByUrl(stub))
            assertEquals("Arrives first", fresh.liseurDb.annotationDao().byId("ann-early")?.note)
        } finally {
            owner.close()
            fresh.close()
        }
    }

    @Test
    fun `deleting a downloaded Drive book from the synced library keeps its local file and row`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "keep.epub", "9780618640164", "Keep", "Author", customBytes = "EPUB-KEEP-LOCAL-COPY".toByteArray())
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(bookA.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780618640164"
        assertTrue(deviceB.epubTransfers.downloadBook(stubB) is DriveEpubDownloadOutcome.Downloaded)
        val downloaded = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
        val localFile = File(java.net.URI(downloaded.localUri!!))
        assertTrue(localFile.isFile)

        assertTrue(deviceB.driveSync.deleteFromSyncedLibrary(downloaded))

        assertTrue(localFile.isFile)
        assertNotNull(deviceB.liseurDb.bookDao().getByUrl(stubB))
        // The retained copy stays unlinked: later syncs neither relink it nor publish its edits.
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(com.chmouel.liseur.data.db.WorkAlias.REJECTED, deviceB.liseurDb.workIdentityDao().alias(stubB, DriveLibrarySync.accountPeerId(PERM_ID))?.confidence)
        deviceB.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-after-delete", bookId = stubB, kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch01.xhtml"}""", text = "Kept", note = "Local only",
                createdAt = 2_000L, updatedAt = 2_000L,
            ),
        )
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(com.chmouel.liseur.data.db.WorkAlias.REJECTED, deviceB.liseurDb.workIdentityDao().alias(stubB, DriveLibrarySync.accountPeerId(PERM_ID))?.confidence)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertNull(deviceA.liseurDb.annotationDao().byId("ann-after-delete"))
    }

    /**
     * A deletion that lands after the automatic offer's final PUT must not erase the
     * suppression: the file it created is trashed, but the row stays SUPPRESSED so no later
     * automatic offer sends the deleted edition up again.
     */
    @Test
    fun `a Drive deletion racing the final upload PUT keeps the automatic row suppressed`() = runTest {
        deviceA.connect()
        deviceA.driveDb.accounts().setUploadSelectionAt(PERM_ID, 1_100L)
        val epubBytes = "EPUB-AUTO-DELETED-DURING-FINAL-PUT".toByteArray()
        val book = seedBook(deviceA, "auto-delete-race.epub", "9780000000001", "Race", "Author", customBytes = epubBytes)
        val sha = BookFingerprints.of(ByteArrayInputStream(epubBytes)).sha256
        val peer = DriveLibrarySync.accountPeerId(PERM_ID)
        // An older Drive copy of these bytes exists when the automatic offer starts uploading.
        deviceA.driveDb.files().upsert(
            DriveFile(
                permissionId = PERM_ID,
                fileId = "file-old-auto",
                name = "race.epub",
                kind = DriveFile.KIND_EPUB,
                sha256 = sha,
                trashed = false,
            ),
        )
        assertEquals(
            setOf(book.url),
            deviceA.driveSync.enqueueUploads(peer, listOf(book), expectedOfferSince = 1_100L),
        )

        var removed = false
        driveServer.onUploadResumableFinalPut = {
            if (!removed) {
                removed = true
                // The reader's deletion of the old copy is processed while the PUT returns.
                deviceA.driveSync.catalogSync.handleDriveEpubRemoved(PERM_ID, "file-old-auto") {
                    "0000000000001-0000-dev"
                }
            }
        }
        val summary = try {
            deviceA.epubTransfers.uploadPending()
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }

        assertEquals(0, summary.uploadedCount)
        // Cleanup trashed the file this upload created...
        assertTrue(driveServer.epubFilesSnapshot().single().trashed)
        // ...but the durable suppression survives it.
        val row = requireNotNull(deviceA.driveDb.uploads().get(PERM_ID, sha))
        assertEquals(DriveUpload.STATE_SUPPRESSED, row.state)
        assertTrue(row.autoSelected)
        assertNull(row.fileId)
        assertNull(row.sessionUri)

        // Another automatic offer is still refused, so no selection is needed to keep it away.
        assertTrue(deviceA.driveSync.enqueueUploads(peer, listOf(book), expectedOfferSince = 1_100L).isEmpty())
        assertEquals(DriveUpload.STATE_SUPPRESSED, deviceA.driveDb.uploads().get(PERM_ID, sha)!!.state)
    }

    @Test
    fun `library deletion lifecycle separates Remove download, Delete from synced library, and Drive-side EPUB trash`() = runTest {
        val deviceC = createDevice("dev-c", File(tempDir, "c"))
        try {
            deviceA.connect()
            deviceB.connect()
            deviceC.connect()

            val epubBytes = "EPUB-DELETE-LIFECYCLE-CONTENT".toByteArray()
            val bookA = seedBook(deviceA, "lotr.epub", "9780618640157", "LOTR", "Tolkien", customBytes = epubBytes)
            // Device C also owns a local sideloaded copy of the same edition!
            val bookC = seedBook(deviceC, "lotr-c.epub", "9780618640157", "LOTR", "Tolkien", customBytes = epubBytes)

            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            // Upload EPUB from A.
            assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(bookA.url)))
            assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)

            // Device B discovers catalog stub, downloads EPUB, and creates an annotation.
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, deviceC.changeEngine.sync().outcome)
            val stubB = "drive:isbn:9780618640157"
            val dl = deviceB.epubTransfers.downloadBook(stubB)
            assertTrue(dl is DriveEpubDownloadOutcome.Downloaded)
            deviceB.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-keep-me", bookId = stubB, kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch01.xhtml"}""", text = "One Ring", note = "Keep this note",
                    createdAt = 1_000L, updatedAt = 1_000_000L,
                ),
            )

            // 1. "Remove download" on B frees local storage ONLY (no catalog tombstone, Drive EPUB stays!).
            val downloadedB = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
            val localDownloadedFile = File(java.net.URI(downloadedB.localUri!!))
            assertTrue(localDownloadedFile.isFile)
            localDownloadedFile.delete()
            BookRemoval(
                bookDao = deviceB.liseurDb.bookDao(),
                sessionDao = deviceB.liseurDb.readingSessionDao(),
                peerStateDao = deviceB.liseurDb.syncPeerStateDao(),
                identityDao = deviceB.liseurDb.workIdentityDao(),
                progressDao = deviceB.liseurDb.readingProgressDao(),
                annotationDao = deviceB.liseurDb.annotationDao(),
                annotationSyncDao = deviceB.liseurDb.annotationSyncDao(),
                inTransaction = { work -> deviceB.liseurDb.withTransaction { work() } },
            ).downloadRemoved(stubB)
            assertFalse(localDownloadedFile.isFile)
            assertEquals(DownloadState.REMOTE, deviceB.liseurDb.bookDao().getByUrl(stubB)!!.downloadState)
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            assertNotNull(deviceA.liseurDb.bookDao().getByUrl(bookA.url))
            assertFalse(driveServer.epubFilesSnapshot().single().trashed)
            deviceB.liseurDb.readingSessionDao().insert(
                ReadingSession(bookUrl = stubB, startedAt = 1_000L, endedAt = 61_000L,
                    lastCheckpointAt = 61_000L, durationMs = 60_000L),
            )

            // 2. "Delete from synced library" on A -> durable catalog tombstone + Drive EPUB trashed!
            assertTrue(deviceA.driveSync.deleteFromSyncedLibrary(bookA))
            // Drive EPUB is now trashed!
            assertTrue(driveServer.epubFilesSnapshot().single().trashed)
            // Device A keeps its local file and unlinks from Drive.
            assertNotNull(deviceA.liseurDb.bookDao().getByUrl(bookA.url))
            assertEquals(com.chmouel.liseur.data.db.WorkAlias.REJECTED,
                deviceA.liseurDb.workIdentityDao().alias(bookA.url, DriveLibrarySync.accountPeerId(PERM_ID))?.confidence)

            // Device B pulls catalog `del` -> removes `drive:` stub while KEEPING its annotation!
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
            assertNull(deviceB.liseurDb.bookDao().getByUrl(stubB))
            assertNotNull(deviceB.liseurDb.annotationDao().byId("ann-keep-me"))
            assertEquals(1, deviceB.liseurDb.readingSessionDao().countForBook(stubB))

            // Device C (has local file) pulls catalog `del` -> keeps local file & book row, unlinks from Drive,
            // and subsequent sync does NOT resurrect the deleted book!
            assertEquals(SyncOutcome.Success, deviceC.changeEngine.sync().outcome)
            assertNotNull(deviceC.liseurDb.bookDao().getByUrl(bookC.url))
            assertEquals(com.chmouel.liseur.data.db.WorkAlias.REJECTED,
                deviceC.liseurDb.workIdentityDao().alias(bookC.url, DriveLibrarySync.accountPeerId(PERM_ID))?.confidence)
            assertEquals(SyncOutcome.Success, deviceC.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
            assertNull(deviceB.liseurDb.bookDao().getByUrl(stubB)) // Never resurrected!

            // 3. Drive-side EPUB deletion (user trashes EPUB directly in Google Drive, NOT via Liseur):
            //    marks media unavailable while retaining catalog entry, reading history, and annotations!
            val book2Bytes = "EPUB-DRIVE-SIDE-DELETE-CONTENT".toByteArray()
            val book2A = seedBook(deviceA, "silmarillion.epub", "9780618391110", "The Silmarillion", "Tolkien", customBytes = book2Bytes)
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book2A.url)))
            assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

            val stub2B = "drive:isbn:9780618391110"
            assertNotNull(deviceB.liseurDb.bookDao().getByUrl(stub2B)!!.downloadHref)
            deviceB.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-drive-side-del", bookId = stub2B, kind = AnnotationKind.HIGHLIGHT.name,
                    locatorJson = """{"href":"ch01.xhtml"}""", text = "Ainulindale", note = "Retained after Drive trash",
                    createdAt = 5_000L, updatedAt = 5_000_000L,
                ),
            )
            deviceB.liseurDb.readingSessionDao().insert(
                ReadingSession(
                    bookUrl = stub2B, startedAt = 10_000L, endedAt = 70_000L,
                    lastCheckpointAt = 70_000L, durationMs = 60_000L,
                    startProgression = 0.0, endProgression = 0.1,
                ),
            )

            // User trashes the EPUB directly in Google Drive!
            val uploadedSilmarillion = driveServer.epubFilesSnapshot().single { !it.trashed }
            driveServer.trashFile("token-user", uploadedSilmarillion.id)

            // Sync on A & B propagates unavailable media (`downloadHref == null`) without deleting catalog/history/notes!
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

            val unavailableStubB = deviceB.liseurDb.bookDao().getByUrl(stub2B)
            assertNotNull(unavailableStubB)
            assertNull(unavailableStubB!!.downloadHref)
            assertTrue(deviceB.epubTransfers.downloadBook(stub2B) is DriveEpubDownloadOutcome.PermanentFailure)
            // Catalog entry, reading session history, and annotations are all retained!
            assertNotNull(deviceB.liseurDb.annotationDao().byId("ann-drive-side-del"))
            assertEquals(1, deviceB.liseurDb.readingSessionDao().countForBook(stub2B))
        } finally {
            deviceC.close()
        }
    }

    @Test
    fun `delete from synced library unlinks every local edition of the work and keeps local files`() = runTest {
        deviceA.connect()
        val first = seedBook(deviceA, "ed1.epub", "9780345391803", "Hitchhiker", "Douglas Adams", customBytes = "ED-ONE".toByteArray())
        val second = seedBook(deviceA, "ed2.epub", "9780345391803", "Hitchhiker", "Douglas Adams", customBytes = "ED-TWO".toByteArray())
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val peer = DriveLibrarySync.accountPeerId(PERM_ID)
        assertNotNull(deviceA.liseurDb.workIdentityDao().alias(second.url, peer))

        assertTrue(deviceA.driveSync.deleteFromSyncedLibrary(first))

        assertEquals(com.chmouel.liseur.data.db.WorkAlias.REJECTED, deviceA.liseurDb.workIdentityDao().alias(second.url, peer)?.confidence)
        assertNotNull(deviceA.liseurDb.bookDao().getByUrl(second.url))
        assertNotNull(deviceA.liseurDb.bookDao().getByUrl(first.url))
    }

    @Test
    fun `work-wide delete cancels a queued staged unpublished second edition without uploading it`() = runTest {
        deviceA.connect()
        val first = seedBook(deviceA, "pub-a.epub", "9780345391803", "Hitchhiker", "Douglas Adams", customBytes = "ED-A".toByteArray())
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(first.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        val second = seedBook(deviceA, "unpub-b.epub", "9780345391803", "Hitchhiker", "Douglas Adams", customBytes = "ED-B".toByteArray())
        // Identity resolves without a catalog publish, so the stored catalog body lacks edition B.
        deviceA.driveSync.workIdentity.resolveBook(PERM_ID, second, computeFingerprintIfMissing = true)
        val peer = DriveLibrarySync.accountPeerId(PERM_ID)
        assertNotNull(deviceA.liseurDb.workIdentityDao().alias(second.url, peer))
        val secondSha = deviceA.liseurDb.workIdentityDao().fingerprint(second.url)!!.sha256
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(first.url, second.url)))
        val staged = DriveEpubTransferManager.stagedFile(deviceA.stagingDir, PERM_ID, secondSha).apply {
            parentFile?.mkdirs()
            writeBytes("ED-B".toByteArray())
        }

        assertTrue(deviceA.driveSync.deleteFromSyncedLibrary(first))

        assertNull(deviceA.driveDb.uploads().get(PERM_ID, secondSha))
        assertFalse(staged.exists())
        deviceA.epubTransfers.uploadPending()
        assertTrue(driveServer.epubFilesSnapshot().none { it.name.contains("unpub-b") || it.name.contains("ED-B") })
        assertEquals(1, driveServer.epubFilesSnapshot().size)
        assertNotNull(deviceA.liseurDb.bookDao().getByUrl(second.url))
        assertEquals(com.chmouel.liseur.data.db.WorkAlias.REJECTED, deviceA.liseurDb.workIdentityDao().alias(second.url, peer)?.confidence)
    }

    @Test
    fun `work-wide delete trashes an unpublished edition whose upload completes before cleanup`() = runTest {
        deviceA.connect()
        val first = seedBook(deviceA, "race-a.epub", "9780345391803", "Hitchhiker", "Douglas Adams", customBytes = "RACE-A".toByteArray())
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(first.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        val second = seedBook(deviceA, "race-b.epub", "9780345391803", "Hitchhiker", "Douglas Adams", customBytes = "RACE-B".toByteArray())
        deviceA.driveSync.workIdentity.resolveBook(PERM_ID, second, computeFingerprintIfMissing = true)
        val secondSha = deviceA.liseurDb.workIdentityDao().fingerprint(second.url)!!.sha256
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(first.url, second.url)))

        // The IDs were collected before nextHlc(); B's upload finishes in that window.
        var completedInWindow = 0
        assertTrue(
            deviceA.driveSync.catalogSync.deleteFromSyncedLibrary(
                permissionId = PERM_ID,
                book = first,
                nextHlc = {
                    completedInWindow = deviceA.epubTransfers.uploadPending().uploadedCount
                    deviceA.changeEngine.nextHlc(PERM_ID)
                },
                driveApi = driveServer.clientFor("dev-a"),
                accessToken = "token-dev-a",
            ),
        )

        assertEquals(1, completedInWindow)
        assertTrue(driveServer.epubFilesSnapshot().all { it.trashed })
        assertEquals(2, driveServer.epubFilesSnapshot().size)
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, secondSha))
    }

    @Test
    fun `a worker paused before starting cannot revive a deleted upload with a completed session`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "revive.epub", "9780547928500", "Revive Race", "Author", customBytes = "EPUB-REVIVE".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))

        // The first worker dies right after the final PUT completed the resumable session.
        driveServer.onUploadResumableFinalPut = { name ->
            if (name == "Revive Race.epub") throw kotlinx.coroutines.CancellationException("worker replaced")
        }
        try {
            assertTrue(runCatching { deviceA.epubTransfers.uploadPending() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }
        val interrupted = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_UPLOADING, interrupted.state)
        assertNotNull(interrupted.sessionUri)

        var deleted = false
        var rowAtPause: DriveUpload? = null
        val manager = DriveEpubTransferManager(
            context = context,
            database = deviceA.liseurDb,
            store = deviceA.driveDb,
            driveApi = driveServer.clientFor("dev-a"),
            workIdentity = deviceA.driveSync.workIdentity,
            catalogSync = deviceA.driveSync.catalogSync,
            fingerprintStore = deviceA.driveSync.fingerprintStore,
            nextHlc = { perm -> deviceA.changeEngine.nextHlc(perm) },
            acquireToken = { SilentTokenResult.Token(accessToken = "token-dev-a", permissionId = PERM_ID) },
            stagingDirProvider = { deviceA.stagingDir },
            // All selected/staging checks have passed; the deletion lands before the state write.
            beforeUploadStart = {
                if (!deleted) {
                    deleted = true
                    assertTrue(deviceA.driveSync.deleteFromSyncedLibrary(book))
                    rowAtPause = deviceA.driveDb.uploads().get(PERM_ID, sha)
                }
            },
        )

        val summary = manager.uploadPending()

        assertTrue(deleted)
        assertEquals(DriveUpload.STATE_CANCELLED, rowAtPause!!.state)
        assertEquals(0, summary.uploadedCount)
        assertEquals(0, summary.failedCount)
        assertTrue(driveServer.epubFilesSnapshot().single().trashed)
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, sha))
    }

    @Test
    fun `deleting a downloaded Drive book keeps its file and history and the copy is no longer redownloadable`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "kept.epub", "9780547928227", "Kept", "Tolkien", customBytes = "EPUB-KEPT".toByteArray())
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(bookA.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        val stubB = "drive:isbn:9780547928227"
        assertTrue(deviceB.epubTransfers.downloadBook(stubB) is DriveEpubDownloadOutcome.Downloaded)
        deviceB.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = stubB, startedAt = 10_000L, endedAt = 70_000L,
                lastCheckpointAt = 70_000L, durationMs = 60_000L,
                startProgression = 0.0, endProgression = 0.1,
            ),
        )
        val downloaded = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
        assertTrue(deviceB.driveSync.canDownloadAgain(downloaded))

        assertTrue(deviceB.driveSync.deleteFromSyncedLibrary(downloaded))

        val kept = deviceB.liseurDb.bookDao().getByUrl(stubB)
        assertNotNull(kept)
        assertTrue(File(java.net.URI(kept!!.localUri!!)).isFile)
        assertNull(kept.downloadHref)
        assertEquals(1, deviceB.liseurDb.readingSessionDao().countForBook(stubB))
        assertFalse(deviceB.driveSync.canDownloadAgain(kept))

        // Neither canDownloadAgain nor a subsequent sync may resurrect the Drive alias or publish records for the deleted work.
        val peerId = DriveLibrarySync.accountPeerId(PERM_ID)
        assertEquals(com.chmouel.liseur.data.db.WorkAlias.REJECTED, deviceB.liseurDb.workIdentityDao().alias(stubB, peerId)?.confidence)
        val appDataBefore = driveServer.appDataFilesSnapshot().map { it.id }.toSet()
        deviceB.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-after-delete",
                bookId = stubB,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch01.xhtml"}""",
                text = "Still reading locally",
                note = "Local only note",
                createdAt = 20_000L,
                updatedAt = 20_000L,
            ),
        )
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(com.chmouel.liseur.data.db.WorkAlias.REJECTED, deviceB.liseurDb.workIdentityDao().alias(stubB, peerId)?.confidence)
        assertEquals(appDataBefore, driveServer.appDataFilesSnapshot().map { it.id }.toSet())
    }

    @Test
    fun `removing an installed second edition and downloading again fetches the same edition`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val isbn = "9780547928500"
        // Catalog editions are sorted by SHA-256, so the edition installed first sorts last once
        // the other one is published: a lookup that ignores the installed SHA would pick the wrong file.
        val byBytes = listOf("EDITION-A", "EDITION-B").map { it.toByteArray() }
            .sortedByDescending { BookFingerprints.of(ByteArrayInputStream(it)).sha256 }
        val installedBytes = byBytes.first()
        val installedSha = BookFingerprints.of(ByteArrayInputStream(installedBytes)).sha256.lowercase()
        val installedBook = seedBook(deviceA, "ed-installed.epub", isbn, "Editions", "Author", customBytes = installedBytes)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(installedBook.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:$isbn"
        assertTrue(deviceB.epubTransfers.downloadBook(stubB) is DriveEpubDownloadOutcome.Downloaded)

        val other = seedBook(deviceA, "ed-other.epub", isbn, "Editions", "Author", customBytes = byBytes.last())
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(installedBook.url, other.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        val installed = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
        assertTrue(deviceB.driveSync.canDownloadAgain(installed))
        File(java.net.URI(installed.localUri!!)).delete()
        BookRemoval(
            bookDao = deviceB.liseurDb.bookDao(),
            sessionDao = deviceB.liseurDb.readingSessionDao(),
            peerStateDao = deviceB.liseurDb.syncPeerStateDao(),
            identityDao = deviceB.liseurDb.workIdentityDao(),
            progressDao = deviceB.liseurDb.readingProgressDao(),
            annotationDao = deviceB.liseurDb.annotationDao(),
            annotationSyncDao = deviceB.liseurDb.annotationSyncDao(),
            inTransaction = { work -> deviceB.liseurDb.withTransaction { work() } },
        ).downloadRemoved(stubB)
        assertNull(deviceB.liseurDb.workIdentityDao().fingerprint(stubB))

        assertTrue(deviceB.epubTransfers.downloadBook(stubB) is DriveEpubDownloadOutcome.Downloaded)
        val redownloaded = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
        assertEquals(
            installedSha,
            BookFingerprints.of(File(java.net.URI(redownloaded.localUri!!)).inputStream()).sha256.lowercase(),
        )
    }

    @Test
    fun `an incoming catalog deletion keeps the session of an interrupted upload for cleanup`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "remote-del.epub", "9780547928500", "Remote Del", "Author", customBytes = "EPUB-REMOTE-DEL".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        driveServer.onUploadResumableFinalPut = { name ->
            if (name == "Remote Del.epub") throw kotlinx.coroutines.CancellationException("response lost")
        }
        try {
            assertTrue(runCatching { deviceA.epubTransfers.uploadPending() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }
        assertNotNull(deviceA.driveDb.uploads().get(PERM_ID, sha)!!.sessionUri)

        val key = deviceA.driveDb.recordStates().forDomain(PERM_ID, DriveWireCodec.DOMAIN_CATALOG).single().recordKey
        val hlc = "0099999999999-0001-dev-b"
        deviceA.driveSync.catalogSync.applyRemoteRecord(
            permissionId = PERM_ID,
            record = DriveWireRecord(
                d = DriveWireCodec.DOMAIN_CATALOG,
                k = key,
                op = DriveRecordState.OP_DEL,
                hlc = hlc,
                fp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_CATALOG, key, hlc),
            ),
            remoteDeviceId = "dev-b",
            remoteSeq = 1L,
        ) { deviceA.changeEngine.nextHlc(PERM_ID) }

        assertEquals(DriveUpload.STATE_CANCELLED, deviceA.driveDb.uploads().get(PERM_ID, sha)!!.state)
        deviceA.epubTransfers.uploadPending()
        assertTrue(driveServer.epubFilesSnapshot().single().trashed)
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, sha))
    }

    @Test
    fun `a picker write racing a completed upload keeps the uploaded row`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "picker-race.epub", "9780547928500", "Picker Race", "Author", customBytes = "EPUB-PICKER-RACE".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        val manager = DriveEpubTransferManager(
            context = context,
            database = deviceA.liseurDb,
            store = deviceA.driveDb,
            driveApi = driveServer.clientFor("dev-a"),
            workIdentity = deviceA.driveSync.workIdentity,
            catalogSync = deviceA.driveSync.catalogSync,
            fingerprintStore = deviceA.driveSync.fingerprintStore,
            nextHlc = { perm -> deviceA.changeEngine.nextHlc(perm) },
            acquireToken = { SilentTokenResult.Token(accessToken = "token-dev-a", permissionId = PERM_ID) },
            stagingDirProvider = { deviceA.stagingDir },
            // The upload finishes after the picker decided to write but before it does.
            beforeQueueWrite = { deviceA.driveDb.uploads().markUploaded(PERM_ID, sha, "done-file", 5L) },
        )

        assertEquals(0, manager.queueSelectedBooks(PERM_ID, setOf(book.url)))

        val row = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_UPLOADED, row.state)
        assertEquals("done-file", row.fileId)
        assertNull(row.sessionUri)
    }

    @Test
    fun `cleanup clears a cancelled row that never got a session or file`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "no-session.epub", "9780547928500", "No Session", "Author", customBytes = "EPUB-NO-SESSION".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        deviceA.driveDb.uploads().upsert(
            DriveUpload(
                permissionId = PERM_ID, sha256 = sha, bookUrl = book.url, sizeBytes = 1L,
                state = DriveUpload.STATE_CANCELLED, selectedAt = 1L, updatedAt = 1L,
            ),
        )

        deviceA.epubTransfers.uploadPending()

        assertNull(deviceA.driveDb.uploads().get(PERM_ID, sha))
    }

    @Test
    fun `confirming the upload picker drops queued books the reader unchecked`() = runTest {
        deviceA.connect()
        val first = seedBook(deviceA, "first.epub", "9780547928227", "First", "Author", customBytes = "EPUB-FIRST".toByteArray())
        val second = seedBook(deviceA, "second.epub", "9780618640157", "Second", "Author", customBytes = "EPUB-SECOND".toByteArray())
        assertEquals(2, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(first.url, second.url)))

        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(first.url)))

        assertEquals(listOf(first.url), deviceA.driveDb.uploads().pendingForAccount(PERM_ID).map { it.bookUrl })
    }

    @Test
    fun `deselecting a queued book while an upload batch is running skips it without uploading`() = runTest {
        deviceA.connect()
        val first = seedBook(deviceA, "first-batch.epub", "9780547928227", "First", "Author", customBytes = "EPUB-BATCH-1".toByteArray())
        val second = seedBook(deviceA, "second-batch.epub", "9780618640157", "Second", "Author", customBytes = "EPUB-BATCH-2".toByteArray())
        assertEquals(2, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(first.url, second.url)))
        val secondSha = deviceA.liseurDb.workIdentityDao().fingerprint(second.url)!!.sha256

        // Deselect `second` while `first` is actively uploading inside the same batch.
        driveServer.onUploadResumableStart = { name ->
            if (name == "First.epub") {
                deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(first.url))
            }
        }
        try {
            val summary = deviceA.epubTransfers.uploadPending()
            assertEquals(1, summary.uploadedCount)
            assertEquals(0, summary.failedCount)
        } finally {
            driveServer.onUploadResumableStart = null
        }

        assertEquals(listOf("First.epub"), driveServer.epubFilesSnapshot().map { it.name })
        val remainingUploads = deviceA.driveDb.uploads().forAccount(PERM_ID)
        assertEquals(1, remainingUploads.size)
        assertEquals(first.url, remainingUploads.single().bookUrl)
        assertEquals(DriveUpload.STATE_UPLOADED, remainingUploads.single().state)
        assertFalse(DriveEpubTransferManager.stagedFile(deviceA.stagingDir, PERM_ID, secondSha).exists())
    }

    @Test
    fun `cross-device pull re-keys conflicting local ISBN alias instead of combining unrelated editions`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val isbn = "9780261102217"
        val bookA = seedBook(deviceA, "hobbit-a.epub", isbn, "The Hobbit", "J.R.R. Tolkien", customBytes = "EPUB-HOBBIT-A".toByteArray())
        val shaA = deviceA.liseurDb.workIdentityDao().fingerprint(bookA.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        val bookB = seedBook(deviceB, "cooking-b.epub", isbn, "Cooking Basics", "Someone Else", customBytes = "EPUB-COOKING-B".toByteArray())
        val shaB = deviceB.liseurDb.workIdentityDao().fingerprint(bookB.url)!!.sha256
        val peerId = DriveLibrarySync.accountPeerId(PERM_ID)

        // Device B resolves its local aliases BEFORE pulling Device A's catalog.
        deviceB.driveSync.workIdentity.resolveAllLocalBooks(PERM_ID)
        assertEquals("isbn:$isbn", deviceB.liseurDb.workIdentityDao().alias(bookB.url, peerId)?.workId)

        // Pull A's catalog and run follow-up sync/publish on both devices.
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // Device B keeps distinct aliases and distinct catalog works without combining editions.
        val stubB = DriveWorkIdentity.driveBookUrl("isbn:$isbn")
        assertEquals("isbn:$isbn", deviceB.liseurDb.workIdentityDao().alias(stubB, peerId)?.workId)
        assertEquals("sha:$shaB", deviceB.liseurDb.workIdentityDao().alias(bookB.url, peerId)?.workId)

        val isbnStateB = deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "isbn:$isbn")
        val isbnBodyB = DriveWireCodec.decodeCatalogBody(org.json.JSONObject(isbnStateB!!.bodyJson!!))!!
        assertEquals(listOf(shaA), isbnBodyB.editions.map { it.sha256 })

        val shaStateB = deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "sha:$shaB")
        val shaBodyB = DriveWireCodec.decodeCatalogBody(org.json.JSONObject(shaStateB!!.bodyJson!!))!!
        assertEquals("Cooking Basics", shaBodyB.title)
        assertEquals(listOf(shaB), shaBodyB.editions.map { it.sha256 })

        // Device A also receives Cooking Basics as its own separate work.
        val cookingStubA = deviceA.liseurDb.bookDao().getByUrl(DriveWorkIdentity.driveBookUrl("sha:$shaB"))
        assertNotNull(cookingStubA)
        assertEquals("Cooking Basics", cookingStubA!!.title)
        val isbnStateA = deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "isbn:$isbn")
        val isbnBodyA = DriveWireCodec.decodeCatalogBody(org.json.JSONObject(isbnStateA!!.bodyJson!!))!!
        assertEquals(listOf(shaA), isbnBodyA.editions.map { it.sha256 })
    }

    @Test
    fun `catalog deletion cleans up staged EPUB files left behind by interrupted uploads`() = runTest {
        deviceA.connect()
        deviceB.connect()

        // 1. Local catalog deletion removes staged file & .part file for an interrupted upload.
        val localBook = seedBook(deviceA, "staged-local.epub", "9780547928227", "Local Staged", "Author", customBytes = "EPUB-STAGED-LOCAL".toByteArray())
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(localBook.url)))
        val localSha = deviceA.liseurDb.workIdentityDao().fingerprint(localBook.url)!!.sha256
        val localStaged = DriveEpubTransferManager.stagedFile(deviceA.stagingDir, PERM_ID, localSha).apply {
            parentFile?.mkdirs()
            writeBytes("EPUB-STAGED-LOCAL".toByteArray())
        }
        val localPart = File(localStaged.path + ".part").apply { writeBytes("PART".toByteArray()) }
        assertTrue(localStaged.isFile)
        assertTrue(localPart.isFile)

        assertTrue(deviceA.driveSync.deleteFromSyncedLibrary(localBook))
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, localSha))
        assertFalse(localStaged.exists())
        assertFalse(localPart.exists())

        // 2. Remote catalog tombstone removes staged file on the receiving device.
        val sharedBytes = "EPUB-STAGED-REMOTE".toByteArray()
        val remoteA = seedBook(deviceA, "staged-remote-a.epub", "9780618640157", "Remote Staged", "Author", customBytes = sharedBytes)
        val remoteB = seedBook(deviceB, "staged-remote-b.epub", "9780618640157", "Remote Staged", "Author", customBytes = sharedBytes)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(1, deviceB.epubTransfers.queueSelectedBooks(PERM_ID, setOf(remoteB.url)))
        val remoteSha = deviceB.liseurDb.workIdentityDao().fingerprint(remoteB.url)!!.sha256
        val remoteStaged = DriveEpubTransferManager.stagedFile(deviceB.stagingDir, PERM_ID, remoteSha).apply {
            parentFile?.mkdirs()
            writeBytes(sharedBytes)
        }
        assertTrue(remoteStaged.isFile)

        assertTrue(deviceA.driveSync.deleteFromSyncedLibrary(remoteA))
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertNull(deviceB.driveDb.uploads().get(PERM_ID, remoteSha))
        assertFalse(remoteStaged.exists())
    }

    @Test
    fun `cross-device ISBN collision separates works even when both devices already stored catalog state`() = runTest {
        deviceA.connect()
        deviceB.connect()
        // Establish baseline sync cursors on both devices before they diverge offline.
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val isbn = "9780261102217"
        val peerId = DriveLibrarySync.accountPeerId(PERM_ID)
        val bookA = seedBook(deviceA, "hobbit-both.epub", isbn, "The Hobbit", "J.R.R. Tolkien", customBytes = "EPUB-HOBBIT-BOTH".toByteArray())
        val shaA = deviceA.liseurDb.workIdentityDao().fingerprint(bookA.url)!!.sha256
        val bookB = seedBook(deviceB, "cooking-both.epub", isbn, "Cooking Basics", "Someone Else", customBytes = "EPUB-COOKING-BOTH".toByteArray())
        val shaB = deviceB.liseurDb.workIdentityDao().fingerprint(bookB.url)!!.sha256

        // Both Device A and Device B independently sync and store catalog state under the same ISBN before exchanging records.
        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()

        // Confirm BOTH devices already have catalog state for isbn:<isbn> before exchanging records.
        assertNotNull(deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "isbn:$isbn"))
        assertNotNull(deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "isbn:$isbn"))
        assertEquals("isbn:$isbn", deviceA.liseurDb.workIdentityDao().alias(bookA.url, peerId)?.workId)
        assertEquals("isbn:$isbn", deviceB.liseurDb.workIdentityDao().alias(bookB.url, peerId)?.workId)

        // Exchange records and sync again on both devices.
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // Device B re-keyed Cooking Basics to sha:<shaB> and holds The Hobbit under isbn:<isbn> with no combined editions.
        val stubB = DriveWorkIdentity.driveBookUrl("isbn:$isbn")
        assertEquals("isbn:$isbn", deviceB.liseurDb.workIdentityDao().alias(stubB, peerId)?.workId)
        assertEquals("sha:$shaB", deviceB.liseurDb.workIdentityDao().alias(bookB.url, peerId)?.workId)
        assertEquals("The Hobbit", deviceB.liseurDb.bookDao().getByUrl(stubB)?.title)

        val isbnBodyB = DriveWireCodec.decodeCatalogBody(
            org.json.JSONObject(
                deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "isbn:$isbn")!!.bodyJson!!,
            ),
        )!!
        assertEquals("The Hobbit", isbnBodyB.title)
        assertEquals(listOf(shaA), isbnBodyB.editions.map { it.sha256 })

        val shaBodyB = DriveWireCodec.decodeCatalogBody(
            org.json.JSONObject(
                deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "sha:$shaB")!!.bodyJson!!,
            ),
        )!!
        assertEquals("Cooking Basics", shaBodyB.title)
        assertEquals(listOf(shaB), shaBodyB.editions.map { it.sha256 })

        // Device A keeps The Hobbit under isbn:<isbn> and receives Cooking Basics under sha:<shaB> with no combined editions.
        val cookingStubA = deviceA.liseurDb.bookDao().getByUrl(DriveWorkIdentity.driveBookUrl("sha:$shaB"))
        assertNotNull(cookingStubA)
        assertEquals("Cooking Basics", cookingStubA!!.title)
        assertEquals("isbn:$isbn", deviceA.liseurDb.workIdentityDao().alias(bookA.url, peerId)?.workId)
        assertEquals("sha:$shaB", deviceA.liseurDb.workIdentityDao().alias(cookingStubA.url, peerId)?.workId)

        val isbnBodyA = DriveWireCodec.decodeCatalogBody(
            org.json.JSONObject(
                deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "isbn:$isbn")!!.bodyJson!!,
            ),
        )!!
        assertEquals("The Hobbit", isbnBodyA.title)
        assertEquals(listOf(shaA), isbnBodyA.editions.map { it.sha256 })

        val shaBodyA = DriveWireCodec.decodeCatalogBody(
            org.json.JSONObject(
                deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, "sha:$shaB")!!.bodyJson!!,
            ),
        )!!
        assertEquals("Cooking Basics", shaBodyA.title)
        assertEquals(listOf(shaB), shaBodyA.editions.map { it.sha256 })
    }

    @Test
    fun `deselecting a book during the final upload PUT trashes the created remote EPUB and does not mark it uploaded`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "final-put-race.epub", "9780547928227", "Race Book", "Author", customBytes = "EPUB-FINAL-PUT-RACE".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))

        // Deselect the book after the final PUT creates the remote file on Drive and before onProgress(total, sessionUri) fires.
        driveServer.onUploadResumableFinalPut = { name ->
            if (name == "Race Book.epub") {
                deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
            }
        }
        try {
            val summary = deviceA.epubTransfers.uploadPending()
            assertEquals(0, summary.uploadedCount)
            assertEquals(0, summary.failedCount)
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }

        // The remote EPUB created by the final PUT must be trashed in Drive, not left active, and not recorded as uploaded.
        val remoteEpubs = driveServer.epubFilesSnapshot()
        assertEquals(1, remoteEpubs.size)
        assertTrue("Expected remote EPUB to be trashed after deselection race", remoteEpubs.single().trashed)
        assertTrue(driveServer.listDriveEpubFiles("token-dev-a").isEmpty())
        assertTrue(deviceA.driveDb.uploads().forAccount(PERM_ID).isEmpty())
        assertFalse(DriveEpubTransferManager.stagedFile(deviceA.stagingDir, PERM_ID, sha).exists())

        // A subsequent changes scan must not resurrect EPUB availability.
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val activeEpubsInDb = deviceA.driveDb.files().byKindAndSha256(PERM_ID, DriveFile.KIND_EPUB, sha)
        assertTrue(activeEpubsInDb.isEmpty())
    }

    @Test
    fun `reconfirming the picker after the final progress write still completes the selected upload`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "reconfirm.epub", "9780547928401", "Reconfirm", "Author", customBytes = "EPUB-RECONFIRM".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))

        // The picker is confirmed again after the final progress write, resetting UPLOADING to PENDING.
        driveServer.afterUploadFinalProgress = { name ->
            if (name == "Reconfirm.epub") deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url))
        }
        val summary = try {
            deviceA.epubTransfers.uploadPending()
        } finally {
            driveServer.afterUploadFinalProgress = null
        }

        assertEquals(1, summary.uploadedCount)
        assertFalse(driveServer.epubFilesSnapshot().single().trashed)
        val row = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_UPLOADED, row.state)
        assertEquals(driveServer.epubFilesSnapshot().single().id, row.fileId)
    }

    @Test
    fun `a resumed session deselected before its final chunk is not revived and its file is trashed`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "resumed-deselect.epub", "9780547928418", "Resumed Deselect", "Author", customBytes = "EPUB-RESUMED-DESELECT".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))

        // First worker is cancelled after its session was recorded; the cancellation must surface.
        driveServer.beforeUploadCommit = { name ->
            if (name == "Resumed Deselect.epub") throw kotlinx.coroutines.CancellationException("worker replaced")
        }
        try {
            assertTrue(runCatching { deviceA.epubTransfers.uploadPending() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        } finally {
            driveServer.beforeUploadCommit = null
        }
        val interrupted = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_UPLOADING, interrupted.state)
        assertNotNull(interrupted.sessionUri)

        // The resumed worker's session is attached, then the reader deselects before the last chunk.
        driveServer.beforeUploadCommit = { name ->
            if (name == "Resumed Deselect.epub") deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
        }
        val summary = try {
            deviceA.epubTransfers.uploadPending()
        } finally {
            driveServer.beforeUploadCommit = null
        }

        assertEquals(0, summary.uploadedCount)
        assertTrue(driveServer.epubFilesSnapshot().single().trashed)
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, sha))
        assertTrue(driveServer.listDriveEpubFiles("token-dev-a").isEmpty())
    }

    @Test
    fun `re-selecting during the final PUT keeps the new selection and uploads a fresh copy`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "final-put-reselect.epub", "9780547928425", "Final Reselect", "Author", customBytes = "EPUB-FINAL-RESELECT".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))

        // Deselect then select again while the final PUT is committing: CANCELLED becomes REQUEUE.
        driveServer.onUploadResumableFinalPut = { name ->
            if (name == "Final Reselect.epub") {
                deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
                deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url))
            }
        }
        try {
            assertEquals(0, deviceA.epubTransfers.uploadPending().uploadedCount)
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }
        val first = driveServer.epubFilesSnapshot().single()
        assertTrue(first.trashed)
        val requeued = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_PENDING, requeued.state)
        assertNull(requeued.fileId)

        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        val active = driveServer.epubFilesSnapshot().filterNot { it.trashed }
        assertEquals(1, active.size)
        assertEquals(active.single().id, deviceA.driveDb.uploads().get(PERM_ID, sha)!!.fileId)
    }

    @Test
    fun `cleanup racing a completed upload cannot requeue the selection under its trashed file`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "completion-cleanup-race.epub", "9780547928432", "Completion Race", "Author", customBytes = "EPUB-COMPLETION-RACE".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))

        // The final PUT lands while the reader deselects and re-selects: the row is REQUEUE.
        driveServer.onUploadResumableFinalPut = { name ->
            if (name == "Completion Race.epub") {
                deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
                deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url))
            }
        }
        lateinit var manager: DriveEpubTransferManager
        var rowDuringCleanup: DriveUpload? = null
        var racingCleanupRan = false
        manager = DriveEpubTransferManager(
            context = context,
            database = deviceA.liseurDb,
            store = deviceA.driveDb,
            driveApi = driveServer.clientFor("dev-a"),
            workIdentity = deviceA.driveSync.workIdentity,
            catalogSync = deviceA.driveSync.catalogSync,
            fingerprintStore = deviceA.driveSync.fingerprintStore,
            nextHlc = { perm -> deviceA.changeEngine.nextHlc(perm) },
            acquireToken = { SilentTokenResult.Token(accessToken = "token-dev-a", permissionId = PERM_ID) },
            stagingDirProvider = { deviceA.stagingDir },
            // The upload request has returned, local finalization has not started: run cleanup now.
            afterUploadRequest = {
                if (!racingCleanupRan) {
                    racingCleanupRan = true
                    manager.uploadPending()
                    rowDuringCleanup = deviceA.driveDb.uploads().get(PERM_ID, sha)
                }
            },
        )
        try {
            assertEquals(0, manager.uploadPending().uploadedCount)
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }

        assertTrue(racingCleanupRan)
        // Cleanup could not resolve the still-running upload's session, so the row was left alone.
        assertEquals(DriveUpload.STATE_REQUEUE, rowDuringCleanup!!.state)
        assertTrue(driveServer.epubFilesSnapshot().single().trashed)
        val trashedId = driveServer.epubFilesSnapshot().single().id
        assertTrue(deviceA.driveDb.files().get(PERM_ID, trashedId)?.trashed != false)
        val requeued = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_PENDING, requeued.state)
        assertNull(requeued.fileId)

        assertEquals(1, manager.uploadPending().uploadedCount)
        val active = driveServer.epubFilesSnapshot().filterNot { it.trashed }.single()
        assertEquals(active.id, deviceA.driveDb.uploads().get(PERM_ID, sha)!!.fileId)
    }

    @Test
    fun `a deselected upload whose trash fails is retried and never advertised`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val book = seedBook(deviceA, "final-put-offline.epub", "9780547928289", "Offline Race", "Author", customBytes = "EPUB-FINAL-PUT-OFFLINE".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))

        driveServer.onUploadResumableFinalPut = { name ->
            if (name == "Offline Race.epub") deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
        }
        // Both the immediate cleanup and the end-of-run retry fail.
        driveServer.failNextTrashes = 2
        try {
            val summary = deviceA.epubTransfers.uploadPending()
            assertEquals(0, summary.uploadedCount)
            // Cleanup-only work still asks the worker to retry.
            assertTrue(summary.retryableFailure)
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }
        assertFalse(driveServer.epubFilesSnapshot().single().trashed)
        assertEquals(DriveUpload.STATE_CANCELLED, deviceA.driveDb.uploads().get(PERM_ID, sha)!!.state)
        // Counted as outstanding work, so resume and settings changes reschedule the worker.
        assertEquals(1, deviceA.driveDb.uploads().workCount(PERM_ID))

        // A changes scan indexes the still-live file, but the catalog never advertises it.
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertNull(deviceB.liseurDb.bookDao().getByUrl("drive:isbn:9780547928289")!!.downloadHref)

        // The next upload run trashes it and forgets the row.
        assertFalse(deviceA.epubTransfers.uploadPending().retryableFailure)
        assertTrue(driveServer.epubFilesSnapshot().single().trashed)
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, sha))
        assertEquals(0, deviceA.driveDb.uploads().workCount(PERM_ID))
    }

    @Test
    fun `a worker cancelled after the final PUT commits leaves a session that cleanup resolves`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val book = seedBook(deviceA, "final-put-cancel.epub", "9780547928296", "Cancelled Race", "Author", customBytes = "EPUB-FINAL-PUT-CANCEL".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))

        // Drive commits the PUT, the reader deselects, and the worker is cancelled before the response.
        driveServer.onUploadResumableFinalPut = { name ->
            if (name == "Cancelled Race.epub") {
                deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
                throw kotlinx.coroutines.CancellationException("worker replaced")
            }
        }
        try {
            assertTrue(runCatching { deviceA.epubTransfers.uploadPending() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }
        val cancelled = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_CANCELLED, cancelled.state)
        assertNotNull(cancelled.sessionUri)

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertNull(deviceB.liseurDb.bookDao().getByUrl("drive:isbn:9780547928296")!!.downloadHref)

        // The replacement worker asks the session, finds the created file and trashes it.
        assertFalse(deviceA.epubTransfers.uploadPending().retryableFailure)
        assertTrue(driveServer.epubFilesSnapshot().single().trashed)
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, sha))
    }

    @Test
    fun `disconnect waits for an in-flight upload to unwind`() = runTest {
        assertUploadDrained { deviceA.driveSync.deactivate() }
    }

    @Test
    fun `a pending switch waits for an in-flight upload to unwind`() = runTest {
        assertUploadDrained {
            val previous = deviceA.driveDb.accounts().active()!!
            deviceA.driveSync.pauseForPendingSwitch(previous)
        }
        assertEquals(DriveAccount.STATE_ACTIVE, deviceA.driveDb.accounts().get(PERM_ID)?.pendingSwitchPreviousState)
        assertTrue(deviceA.driveSync.restoreAccountState(PERM_ID, DriveAccount.STATE_ACTIVE))
        assertEquals(DriveUpload.STATE_UPLOADING, deviceA.driveDb.uploads().forAccount(PERM_ID).single().state)
        val remoteId = driveServer.epubFilesSnapshot().single().id
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertEquals(remoteId, deviceA.driveDb.uploads().forAccount(PERM_ID).single().fileId)
        assertEquals(1, driveServer.epubFilesSnapshot().size)
    }

    @Test
    fun `cancelling a pending switch drains the upload before restoring the account`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "cancel-switch.epub", "9780547928319", "Cancel Switch", "Author", customBytes = "SWITCH".toByteArray())
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        driveServer.beforeUploadCommit = {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
            }
        }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    val upload = async { runCatching { deviceA.epubTransfers.uploadPending() } }
                    try {
                        started.await()
                        val previous = deviceA.driveDb.accounts().active()!!
                        val switching = async { deviceA.driveSync.pauseForPendingSwitch(previous) }
                        while (deviceA.driveDb.accounts().active() != null) delay(1)
                        switching.cancel()
                        assertFalse(switching.isCompleted)
                        release.complete(Unit)
                        switching.join()
                        upload.await()
                        assertEquals(PERM_ID, deviceA.driveDb.accounts().active()?.permissionId)
                        assertNull(deviceA.driveDb.accounts().get(PERM_ID)?.pendingSwitchPreviousState)
                    } finally {
                        release.complete(Unit)
                    }
                }
            }
        } finally {
            release.complete(Unit)
            driveServer.beforeUploadCommit = null
        }
    }

    @Test
    fun `direct switch asks for upload selection even if the clock moved backwards`() = runTest {
        deviceA.driveSync.activateAccount("perm-other", "other@example.com", now = 2_000L)
        val other = deviceA.driveDb.accounts().active()!!
        deviceA.driveDb.accounts().upsert(other.copy(uploadSelectionAt = 2_001L))
        deviceA.driveSync.activateAccount(PERM_ID, "reader@example.com", now = 1_000L)
        deviceA.driveSync.activateAccount("perm-other", "other@example.com", now = 3_000L)
        assertNull(deviceA.driveDb.accounts().get("perm-other")?.uploadSelectionAt)
    }

    @Test
    fun `switching back after clock rollback holds old uploads until selected again`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "clock.epub", "9780547928319", "Clock", "Author", customBytes = "CLOCK".toByteArray())
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        deviceA.driveSync.activateAccount("perm-other", "other@example.com", now = 1_100L)
        deviceA.driveSync.pauseForPendingSwitch(deviceA.driveDb.accounts().active()!!)
        deviceA.driveSync.activateAccount(PERM_ID, "reader@example.com", now = 1_200L)
        assertNull(deviceA.driveDb.accounts().get(PERM_ID)?.uploadSelectionAt)
        assertEquals(0, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertTrue(driveServer.epubFilesSnapshot().isEmpty())
        assertEquals(setOf(book.url), deviceA.epubTransfers.queueBooks(PERM_ID, listOf(book.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
    }

    @Test
    fun `confirmed switch asks for upload selection even if the clock moved backwards`() = runTest {
        deviceA.driveSync.activateAccount("perm-other", "other@example.com", now = 2_000L)
        val other = deviceA.driveDb.accounts().active()!!
        deviceA.driveDb.accounts().upsert(other.copy(uploadSelectionAt = 2_001L))
        deviceA.driveSync.activateAccount(PERM_ID, "reader@example.com", now = 1_000L)
        deviceA.driveSync.pauseForPendingSwitch(deviceA.driveDb.accounts().active()!!)
        deviceA.driveSync.activateAccount("perm-other", "other@example.com", now = 3_000L)
        assertNull(deviceA.driveDb.accounts().get("perm-other")?.uploadSelectionAt)
    }

    @Test
    fun `direct account replacement waits for an in-flight upload to unwind`() = runTest {
        assertUploadDrained { deviceA.driveSync.activateAccount("perm-other", "other@example.com") }
        assertEquals("perm-other", deviceA.driveDb.accounts().active()?.permissionId)
    }

    @Test
    fun `disconnect drains an upload after its final remote progress`() = runTest {
        assertUploadDrained(afterCommit = true) { deviceA.driveSync.deactivate() }
    }

    private suspend fun assertUploadDrained(afterCommit: Boolean = false, transition: suspend () -> Unit) {
        deviceA.connect()
        val book = seedBook(deviceA, "drain.epub", "9780547928319", "Drain", "Author", customBytes = "EPUB-DRAIN".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val heldUpload = CompletableDeferred<Job>()
        val hold: suspend (String) -> Unit = {
            heldUpload.complete(currentCoroutineContext()[Job]!!)
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
            }
        }
        if (afterCommit) driveServer.afterUploadFinalProgress = hold else driveServer.beforeUploadCommit = hold
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    val upload = async { runCatching { deviceA.epubTransfers.uploadPending() } }
                    try {
                        started.await()
                        val switching = async { transition() }
                        while (deviceA.driveDb.accounts().active()?.permissionId == PERM_ID) delay(1)
                        // The account flips before the transition cancels transfers; releasing in
                        // between lets the upload finish instead of unwinding.
                        val held = heldUpload.await()
                        while (!held.isCancelled) delay(1)
                        assertFalse(switching.isCompleted)
                        release.complete(Unit)
                        switching.await()
                        assertTrue(upload.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
                    } finally {
                        release.complete(Unit)
                    }
                }
            }
        } finally {
            release.complete(Unit)
            driveServer.beforeUploadCommit = null
            driveServer.afterUploadFinalProgress = null
        }
        val remote = driveServer.epubFilesSnapshot().single()
        val row = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_UPLOADING, row.state)
        assertNotNull(row.sessionUri)
        assertNull(row.fileId)
        assertNull(deviceA.driveDb.files().get(PERM_ID, remote.id))
    }

    @Test
    fun `drain waits for upload finalization after the remote request`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "finalize.epub", "9780547928319", "Finalize", "Author", customBytes = "FINALIZE".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val uploadJob = CompletableDeferred<Job>()
        val manager = DriveEpubTransferManager(
            context = context,
            database = deviceA.liseurDb,
            store = deviceA.driveDb,
            driveApi = driveServer.clientFor("dev-a"),
            workIdentity = deviceA.driveSync.workIdentity,
            catalogSync = deviceA.driveSync.catalogSync,
            fingerprintStore = deviceA.driveSync.fingerprintStore,
            nextHlc = { perm -> deviceA.changeEngine.nextHlc(perm) },
            acquireToken = { SilentTokenResult.Token("token-dev-a", PERM_ID) },
            stagingDirProvider = { deviceA.stagingDir },
            afterUploadRequest = {
                uploadJob.complete(currentCoroutineContext()[Job]!!)
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                }
            },
        )
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                val upload = async { runCatching { manager.uploadPending() } }
                try {
                    started.await()
                    val drain = async { manager.cancelAndDrain() }
                    while (uploadJob.await().isActive) delay(1)
                    assertFalse(drain.isCompleted)
                    release.complete(Unit)
                    drain.await()
                    assertTrue(upload.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
                } finally {
                    release.complete(Unit)
                }
            }
        }
        val remote = driveServer.epubFilesSnapshot().single()
        assertNull(deviceA.driveDb.files().get(PERM_ID, remote.id))
        assertEquals(DriveUpload.STATE_UPLOADING, deviceA.driveDb.uploads().get(PERM_ID, sha)?.state)
    }

    @Test
    fun `cleanup keeps a cancelled session whose request is still running`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "still-running.epub", "9780547928319", "Still Running", "Author", customBytes = "EPUB-STILL-RUNNING".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        driveServer.beforeUploadCommit = { name ->
            if (name == "Still Running.epub") {
                deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
                started.complete(Unit)
                release.await()
            }
        }
        // The replaced worker's request commits the file, but its response is discarded.
        driveServer.onUploadResumableFinalPut = { name ->
            if (name == "Still Running.epub") throw kotlinx.coroutines.CancellationException("worker replaced")
        }
        try {
            val oldWorker = async(Dispatchers.Default) { runCatching { deviceA.epubTransfers.uploadPending() } }
            started.await()

            // The replacement worker probes the session before the old request has committed.
            assertTrue(deviceA.epubTransfers.uploadPending().retryableFailure)
            assertEquals(DriveUpload.STATE_CANCELLED, deviceA.driveDb.uploads().get(PERM_ID, sha)!!.state)

            release.complete(Unit)
            oldWorker.await()
        } finally {
            driveServer.beforeUploadCommit = null
            driveServer.onUploadResumableFinalPut = null
        }
        assertFalse(driveServer.epubFilesSnapshot().single().trashed)

        // Once the request has returned, the next run finds the completed session and trashes it.
        assertFalse(deviceA.epubTransfers.uploadPending().retryableFailure)
        assertTrue(driveServer.epubFilesSnapshot().single().trashed)
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, sha))
    }

    @Test
    fun `re-selecting identical bytes through another book keeps the new source for the fresh upload`() = runTest {
        deviceA.connect()
        val bytes = "EPUB-SAME-BYTES".toByteArray()
        val bookA = seedBook(deviceA, "source-a.epub", "9780547928401", "Source A", "Author", customBytes = bytes)
        val bookB = seedBook(deviceA, "source-b.epub", "9780547928418", "Source B", "Author", customBytes = bytes)
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(bookA.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(bookA.url)))
        driveServer.onUploadResumableFinalPut = { name ->
            if (name == "Source A.epub") deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
        }
        driveServer.failNextTrashes = 2
        try {
            deviceA.epubTransfers.uploadPending()
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }
        val oldFileId = deviceA.driveDb.uploads().get(PERM_ID, sha)!!.fileId!!

        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(bookB.url)))
        val requeued = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_REQUEUE, requeued.state)
        assertEquals(bookB.url, requeued.bookUrl)
        assertEquals(oldFileId, requeued.fileId)

        File(java.net.URI(bookA.url)).delete()
        val summary = deviceA.epubTransfers.uploadPending()

        assertEquals(1, summary.uploadedCount)
        val uploaded = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_UPLOADED, uploaded.state)
        assertEquals(bookB.url, uploaded.bookUrl)
        assertTrue(driveServer.epubFilesSnapshot().single { it.id == oldFileId }.trashed)
    }

    @Test
    fun `an in-flight upload session stays protected until every worker holding it has released`() {
        val session = "https://upload.example/session-${System.nanoTime()}"
        DriveEpubTransferManager.holdSession(session)
        DriveEpubTransferManager.holdSession(session)
        DriveEpubTransferManager.releaseSession(session)
        assertTrue(DriveEpubTransferManager.isSessionInFlight(session))
        DriveEpubTransferManager.releaseSession(session)
        assertFalse(DriveEpubTransferManager.isSessionInFlight(session))
        DriveEpubTransferManager.releaseSession(session)
        assertFalse(DriveEpubTransferManager.isSessionInFlight(session))
    }

    @Test
    fun `deselecting again while cleanup runs is not undone by that cleanup`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "deselect-again.epub", "9780547928326", "Deselect Again", "Author", customBytes = "EPUB-DESELECT-AGAIN".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        driveServer.onUploadResumableFinalPut = { name ->
            if (name == "Deselect Again.epub") deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
        }
        driveServer.failNextTrashes = 2
        try {
            deviceA.epubTransfers.uploadPending()
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }
        // Re-selected (REQUEUE), then deselected again while cleanup is trashing the old copy.
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        assertEquals(DriveUpload.STATE_REQUEUE, deviceA.driveDb.uploads().get(PERM_ID, sha)!!.state)
        driveServer.duringNextTrash = {
            deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
        }

        val summary = deviceA.epubTransfers.uploadPending()

        assertEquals(0, summary.uploadedCount)
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, sha))
        assertTrue(driveServer.epubFilesSnapshot().all { it.trashed })
    }

    @Test
    fun `re-selecting an edition while its cancelled copy is being trashed uploads a fresh copy`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "reselect.epub", "9780547928302", "Reselect", "Author", customBytes = "EPUB-RESELECT".toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        driveServer.onUploadResumableFinalPut = { name ->
            if (name == "Reselect.epub") deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
        }
        driveServer.failNextTrashes = 2
        try {
            deviceA.epubTransfers.uploadPending()
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }
        val oldFileId = deviceA.driveDb.uploads().get(PERM_ID, sha)!!.fileId!!

        // The reader picks the book again while cleanup is trashing the old copy.
        driveServer.duringNextTrash = {
            deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url))
        }
        val summary = deviceA.epubTransfers.uploadPending()

        assertEquals(1, summary.uploadedCount)
        val uploaded = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_UPLOADED, uploaded.state)
        assertTrue(uploaded.fileId != oldFileId)
        val files = driveServer.epubFilesSnapshot().associateBy { it.id }
        assertTrue(files.getValue(oldFileId).trashed)
        assertFalse(files.getValue(uploaded.fileId!!).trashed)
    }

    @Test
    fun `a cancelled upload whose trash failed is never advertised, resolved or reused by a peer`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bytes = "EPUB-UNCONFIRMED".toByteArray()
        val isbn = "9780547928319"
        val book = seedBook(deviceA, "unconfirmed.epub", isbn, "Unconfirmed", "Author", customBytes = bytes)
        val bookB = seedBook(deviceB, "unconfirmed-b.epub", isbn, "Unconfirmed", "Author", customBytes = bytes)
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        driveServer.onUploadResumableFinalPut = { name ->
            if (name == "Unconfirmed.epub") deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
        }
        driveServer.failNextTrashes = 2
        try {
            deviceA.epubTransfers.uploadPending()
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }
        val cancelled = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_CANCELLED, cancelled.state)
        val orphanId = cancelled.fileId!!
        assertFalse(driveServer.epubFilesSnapshot().single { it.id == orphanId }.trashed)

        // Both devices discover the live file, but no catalog edition confirms it.
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(orphanId, deviceB.driveDb.files().byKindAndSha256(PERM_ID, DriveFile.KIND_EPUB, sha).single().fileId)
        for (device in listOf(deviceA, deviceB)) {
            assertTrue(device.driveSync.catalogSync.advertisedEpubFileIds(PERM_ID).isEmpty())
            val editions = device.driveDb.recordStates().forDomain(PERM_ID, DriveWireCodec.DOMAIN_CATALOG)
                .mapNotNull { it.bodyJson }
                .flatMap { DriveWireCodec.decodeCatalogBody(org.json.JSONObject(it))!!.editions }
            assertTrue(editions.isNotEmpty())
            assertTrue(editions.all { it.driveFileId == null })
        }
        assertNull(deviceB.driveSync.catalogSync.resolveDownloadSource(PERM_ID, bookB, sha))
        assertNull(deviceB.driveSync.catalogSync.resolveDownloadSource(PERM_ID, bookB))

        // The peer does not reuse the unconfirmed file; it uploads its own copy.
        assertEquals(1, deviceB.epubTransfers.queueSelectedBooks(PERM_ID, setOf(bookB.url)))
        assertEquals(1, deviceB.epubTransfers.uploadPending().uploadedCount)
        val reused = deviceB.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_UPLOADED, reused.state)
        assertNotEquals(orphanId, reused.fileId)

        // A confirmed upload syncs normally and is advertised and resolvable on the other device.
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(setOf(reused.fileId!!), deviceA.driveSync.catalogSync.advertisedEpubFileIds(PERM_ID)[sha])
        assertEquals(setOf(reused.fileId!!), deviceB.driveSync.catalogSync.advertisedEpubFileIds(PERM_ID)[sha])
        assertEquals(
            reused.fileId to sha,
            deviceA.driveSync.catalogSync.resolveDownloadSource(PERM_ID, book, sha),
        )
        assertFalse(orphanId in deviceA.driveSync.catalogSync.advertisedEpubFileIds(PERM_ID)[sha].orEmpty())
    }

    private suspend fun cancelledUploadWithFile(name: String, isbn: String, bytes: String): Triple<Book, String, DriveUpload> {
        val book = seedBook(deviceA, "$name.epub", isbn, name, "Author", customBytes = bytes.toByteArray())
        val sha = deviceA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book.url)))
        driveServer.onUploadResumableFinalPut = { n ->
            if (n == "$name.epub") deviceA.epubTransfers.queueSelectedBooks(PERM_ID, emptySet())
        }
        driveServer.failNextTrashes = 2
        try {
            deviceA.epubTransfers.uploadPending()
        } finally {
            driveServer.onUploadResumableFinalPut = null
        }
        return Triple(book, sha, deviceA.driveDb.uploads().get(PERM_ID, sha)!!)
    }

    @Test
    fun `a file recovered from a completed session stays on its cleanup row until trashed`() = runTest {
        deviceA.connect()
        val (_, sha, cancelled) = cancelledUploadWithFile("Recovered", "9780547928500", "EPUB-RECOVERED")
        val fileId = cancelled.fileId!!
        // The completion response was lost: only the session identifies the file.
        deviceA.driveDb.uploads().upsert(cancelled.copy(fileId = null, sessionUri = driveServer.sessionOf(fileId)))
        driveServer.failNextTrashes = 2

        assertTrue(deviceA.epubTransfers.uploadPending().retryableFailure)

        val kept = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(DriveUpload.STATE_CANCELLED, kept.state)
        assertEquals(fileId, kept.fileId)
        assertFalse(driveServer.epubFilesSnapshot().single().trashed)

        driveServer.expireSessions()
        assertFalse(deviceA.epubTransfers.uploadPending().retryableFailure)

        assertTrue(driveServer.epubFilesSnapshot().single().trashed)
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, sha))
    }

    private suspend fun assertStaleCleanupKeepsNewer(newerState: String, isbn: String) {
        deviceA.connect()
        val (_, sha, old) = cancelledUploadWithFile("Stale", isbn, "EPUB-STALE-$newerState")
        driveServer.failNextTrashes = 0
        val newer = old.copy(state = newerState, sessionUri = "session://newer", fileId = null)
        // Another pass finishes the old file and a newer upload is deselected while this pass trashes.
        driveServer.duringNextTrash = { deviceA.driveDb.uploads().upsert(newer) }
        // The newer upload's request is still running, so later passes leave its row alone.
        DriveEpubTransferManager.holdSession("session://newer")
        try {
            deviceA.epubTransfers.uploadPending()
        } finally {
            DriveEpubTransferManager.releaseSession("session://newer")
        }

        val row = deviceA.driveDb.uploads().get(PERM_ID, sha)!!
        assertEquals(newerState, row.state)
        assertEquals("session://newer", row.sessionUri)
    }

    @Test
    fun `a stale cleanup pass cannot delete a newer cancelled upload`() = runTest {
        assertStaleCleanupKeepsNewer(DriveUpload.STATE_CANCELLED, "9780547928517")
    }

    @Test
    fun `a stale cleanup pass cannot reset a newer requeued upload`() = runTest {
        assertStaleCleanupKeepsNewer(DriveUpload.STATE_REQUEUE, "9780547928524")
    }

    @Test
    fun `a never-downloaded stub follows the available edition and a removed download keeps its own`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val isbn = "9780547928531"
        val metadataOnly = seedBook(deviceA, "stub-a.epub", isbn, "Stub Edition", "Author", customBytes = "EPUB-STUB-A".toByteArray())
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubUrl = "drive:isbn:$isbn"
        assertNull(deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!.downloadHref)

        val second = seedBook(deviceA, "stub-b.epub", isbn, "Stub Edition", "Author", customBytes = "EPUB-STUB-B".toByteArray())
        val secondSha = deviceA.liseurDb.workIdentityDao().fingerprint(second.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(second.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        val first = deviceB.epubTransfers.downloadBook(stubUrl)
        assertTrue(first is DriveEpubDownloadOutcome.Downloaded)
        assertEquals(secondSha, (first as DriveEpubDownloadOutcome.Downloaded).sha256)

        // Another edition becomes available; the removed copy's edition is still the one fetched.
        val third = seedBook(deviceA, "stub-c.epub", isbn, "Stub Edition", "Author", customBytes = "EPUB-STUB-C".toByteArray())
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(1, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(second.url, third.url)))
        assertEquals(1, deviceA.epubTransfers.uploadPending().uploadedCount)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val downloaded = deviceB.liseurDb.bookDao().getByUrl(stubUrl)!!
        File(java.net.URI(downloaded.localUri!!)).delete()
        BookRemoval(
            bookDao = deviceB.liseurDb.bookDao(),
            sessionDao = deviceB.liseurDb.readingSessionDao(),
            peerStateDao = deviceB.liseurDb.syncPeerStateDao(),
            identityDao = deviceB.liseurDb.workIdentityDao(),
            progressDao = deviceB.liseurDb.readingProgressDao(),
            annotationDao = deviceB.liseurDb.annotationDao(),
            annotationSyncDao = deviceB.liseurDb.annotationSyncDao(),
            inTransaction = { work -> deviceB.liseurDb.withTransaction { work() } },
        ).downloadRemoved(stubUrl)

        val again = deviceB.epubTransfers.downloadBook(stubUrl)
        assertEquals(secondSha, (again as DriveEpubDownloadOutcome.Downloaded).sha256)
        assertNotNull(metadataOnly)
    }

    @Test
    fun `concurrent deletion of staging part file skips deleted item and still uploads remaining batch items`() = runTest {
        deviceA.connect()
        val first = seedBook(deviceA, "stage-race-1.epub", "9780547928227", "First Staged", "Author", customBytes = "EPUB-STAGE-RACE-1".toByteArray())
        val second = seedBook(deviceA, "stage-race-2.epub", "9780618640157", "Second Staged", "Author", customBytes = "EPUB-STAGE-RACE-2".toByteArray())
        val firstSha = deviceA.liseurDb.workIdentityDao().fingerprint(first.url)!!.sha256
        val secondSha = deviceA.liseurDb.workIdentityDao().fingerprint(second.url)!!.sha256
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(2, deviceA.epubTransfers.queueSelectedBooks(PERM_ID, setOf(first.url, second.url)))

        // Simulate catalog deletion removing `first`'s `.part` file right after copy finishes and before SHA hashing reads `.part`.
        var triggeredDeletion = false
        val transferManager = DriveEpubTransferManager(
            context = context,
            database = deviceA.liseurDb,
            store = deviceA.driveDb,
            driveApi = driveServer.clientFor("dev-a"),
            workIdentity = deviceA.driveSync.workIdentity,
            catalogSync = deviceA.driveSync.catalogSync,
            fingerprintStore = deviceA.driveSync.fingerprintStore,
            nextHlc = { perm -> deviceA.changeEngine.nextHlc(perm) },
            acquireToken = { SilentTokenResult.Token(accessToken = "token-dev-a", permissionId = PERM_ID) },
            onCatalogAvailabilityChanged = { deviceA.changeEngine.sync() },
            openBookStream = { book ->
                val raw = File(java.net.URI(book.localUri!!)).inputStream()
                if (book.url == first.url) {
                    object : FilterInputStream(raw) {
                        override fun close() {
                            super.close()
                            if (!triggeredDeletion) {
                                triggeredDeletion = true
                                runBlocking { deviceA.driveSync.deleteFromSyncedLibrary(first) }
                            }
                        }
                    }
                } else {
                    raw
                }
            },
            stagingDirProvider = { deviceA.stagingDir },
        )

        val summary = transferManager.uploadPending()
        assertTrue(triggeredDeletion)
        assertEquals(1, summary.uploadedCount)
        assertEquals(0, summary.failedCount)
        assertFalse(summary.retryableFailure)

        assertEquals(listOf("Second Staged.epub"), driveServer.listDriveEpubFiles("token-dev-a").map { it.name })
        assertNull(deviceA.driveDb.uploads().get(PERM_ID, firstSha))
        assertEquals(DriveUpload.STATE_UPLOADED, deviceA.driveDb.uploads().get(PERM_ID, secondSha)?.state)
        val firstStaged = DriveEpubTransferManager.stagedFile(deviceA.stagingDir, PERM_ID, firstSha)
        assertFalse(firstStaged.exists())
        assertFalse(File(firstStaged.path + ".part").exists())
    }

    @Test
    fun `all v1 domains including Phase 5 are supported in DriveWireCodec while future versions pause`() {
        val phase5Domains = listOf(
            DriveWireCodec.DOMAIN_VOCAB_WORD,
            DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE,
            DriveWireCodec.DOMAIN_VOCAB_REVIEW,
            DriveWireCodec.DOMAIN_SESSION,
        )
        for (domain in phase5Domains) {
            assertTrue("$domain must be supported in Phase 5", domain in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS)
            assertEquals(DriveSchemaCheck.Supported, DriveWireCodec.checkSchema(1, mapOf(domain to 1)))
            val futureCheck = DriveWireCodec.checkSchema(1, mapOf(domain to 2))
            assertTrue(futureCheck is DriveSchemaCheck.UnsupportedDomains)
        }
    }

    @Test
    fun `fresh device bootstrap rejects older catalog put from another device after durable deletion`() = runTest {
        val deviceC = createDevice("dev-c", File(tempDir, "c-bootstrap"))
        try {
            deviceA.connect()
            deviceB.connect()
            val epubBytes = "EPUB-BOOTSTRAP-DELETE-TEST".toByteArray()
            val bookA = seedBook(deviceA, "old-book-a.epub", "9780345391803", "Hitchhiker", "Douglas Adams", customBytes = epubBytes)
            val bookB = seedBook(deviceB, "old-book-b.epub", "9780345391803", "Hitchhiker", "Douglas Adams", customBytes = epubBytes)

            // Both A and B publish catalog puts before A deletes the book from the synced library.
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            deviceB.liseurDb.bookDao().setArchived(bookB.url, 5_000L)
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

            // Device A explicitly deletes from synced library -> durable OP_DEL tombstone on dev-a.
            assertTrue(deviceA.driveSync.deleteFromSyncedLibrary(bookA))
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

            // Fresh Device C bootstraps: remote devices are processed in sorted order (dev-a then dev-b),
            // so dev-a's durable deletion is applied BEFORE dev-b's older catalog put!
            deviceC.connect()
            assertEquals(SyncOutcome.Success, deviceC.changeEngine.sync().outcome)

            val workKey = "isbn:9780345391803"
            val stubUrlC = DriveWorkIdentity.driveBookUrl(workKey)
            assertNull("Deleted book must NOT be resurrected by older catalog put", deviceC.liseurDb.bookDao().getByUrl(stubUrlC))
            val stateC = deviceC.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, workKey)
            assertNotNull(stateC)
            assertEquals(DriveRecordState.OP_DEL, stateC!!.versionOp)
        } finally {
            deviceC.close()
        }
    }

    @Test
    fun `KEEP_REMOTE replay of retained old put keeps OP_RESOLVE and competing resolution arbitrates cleanly`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(
            deviceA,
            "replay-keep-remote.epub",
            "9780547928227",
            "The Hobbit",
            "J.R.R. Tolkien",
            fileSeriesName = "Middle-earth",
            fileSeriesIndex = 1.0,
        )
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780547928227"

        // Seed both an annotation and a series override so we test both content-matched adapters.
        val ann = BookAnnotation(
            id = "ann-replay-kr",
            bookId = bookA.url,
            kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""",
            text = "Far over",
            note = "v0",
            createdAt = 1_000L,
            updatedAt = 1_000_000L,
        )
        deviceA.liseurDb.annotationDao().upsert(ann)
        deviceA.liseurDb.bookDao().setSeriesOverride(bookA.url, "Tolkien Saga", 1.0, 1_000L)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // Create concurrent offline EDIT_EDIT conflicts on both annotation and series.
        val seqBeforeBConflict = deviceA.driveDb.cursors().get(PERM_ID, "dev-b", DriveWireCodec.DOMAIN_ANNOTATION)?.appliedSeq ?: 0L
        deviceA.liseurDb.annotationDao().upsert(ann.copy(note = "Edit A", updatedAt = 2_000_000L))
        deviceB.liseurDb.annotationDao().upsert(deviceB.liseurDb.annotationDao().byId("ann-replay-kr")!!.copy(note = "Edit B", updatedAt = 2_100_000L))
        deviceA.liseurDb.bookDao().setSeriesOverride(bookA.url, "Shelf A", 2.0, 2_000L)
        deviceB.liseurDb.bookDao().setSeriesOverride(stubB, "Shelf B", 3.0, 2_100L)

        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(2, deviceA.driveDb.conflicts().openForAccount(PERM_ID).size)
        assertEquals(2, deviceB.driveDb.conflicts().openForAccount(PERM_ID).size)

        // Device A resolves locally with KEEP_REMOTE (retaining B's content) WITHOUT seeing B's competing resolution yet.
        val openA = deviceA.driveDb.conflicts().openForAccount(PERM_ID)
        driveServer.hideOtherDeviceDeltasFor("dev-a")
        deviceA.driveSync.resolveConflicts(openA, DriveConflictChoice.KEEP_REMOTE, syncAfter = true)
        driveServer.unhideAll()

        val annResStateBefore = deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-replay-kr")!!
        val seriesResStateBefore = deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_SERIES, "isbn:9780547928227")!!
        assertEquals(DriveRecordState.OP_RESOLVE, annResStateBefore.versionOp)
        assertEquals(DriveRecordState.OP_RESOLVE, seriesResStateBefore.versionOp)

        // Replay Device B's pre-resolution old puts (`Edit B` / `Shelf B`) on Device A!
        deviceA.driveDb.cursors().upsert(DriveCursor(PERM_ID, "dev-b", DriveWireCodec.DOMAIN_ANNOTATION, appliedSeq = seqBeforeBConflict))
        deviceA.driveDb.cursors().upsert(DriveCursor(PERM_ID, "dev-b", DriveWireCodec.DOMAIN_SERIES, appliedSeq = seqBeforeBConflict))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // Both MUST remain OP_RESOLVE with identical resolutionFp (never demoted to OP_PUT!).
        val annStateAfterReplay = deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-replay-kr")!!
        val seriesStateAfterReplay = deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_SERIES, "isbn:9780547928227")!!
        assertEquals(DriveRecordState.OP_RESOLVE, annStateAfterReplay.versionOp)
        assertEquals(annResStateBefore.versionFp, annStateAfterReplay.versionFp)
        assertEquals(DriveRecordState.OP_RESOLVE, seriesStateAfterReplay.versionOp)
        assertEquals(seriesResStateBefore.versionFp, seriesStateAfterReplay.versionFp)

        // Device B resolves offline with competing DELETE (lower HLC than A's resolution) and pushes.
        val openB = deviceB.driveDb.conflicts().openForAccount(PERM_ID)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        deviceB.driveSync.resolveConflicts(openB, DriveConflictChoice.DELETE, syncAfter = true)
        driveServer.unhideAll()

        val annResB = deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-replay-kr")!!
        assertTrue(
            "Expected A's KEEP_REMOTE resolution HLC to outrank B's competing DELETE",
            annResStateBefore.lastHlc > annResB.lastHlc,
        )

        // Device A pulls B's competing resolution -> arbitrates silently by (hlc, fp):
        // because A stayed OP_RESOLVE (not demoted to OP_PUT), A's higher-HLC resA wins cleanly
        // without applying the losing DELETE or reopening a conflict!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertTrue(deviceA.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        assertEquals("Edit B", deviceA.liseurDb.annotationDao().byId("ann-replay-kr")!!.note)
        assertEquals("Shelf B", deviceA.liseurDb.bookDao().getByUrl(bookA.url)!!.userSeriesName)
        assertEquals(
            DriveRecordState.OP_RESOLVE,
            deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-replay-kr")!!.versionOp,
        )
        assertEquals(
            annResStateBefore.versionFp,
            deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-replay-kr")!!.versionFp,
        )
        assertEquals(
            DriveRecordState.OP_RESOLVE,
            deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_SERIES, "isbn:9780547928227")!!.versionOp,
        )
        assertEquals(
            seriesResStateBefore.versionFp,
            deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_SERIES, "isbn:9780547928227")!!.versionFp,
        )

        // Device B pulls A's winning resolution -> both devices converge identically with zero conflicts!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        assertEquals("Edit B", deviceB.liseurDb.annotationDao().byId("ann-replay-kr")!!.note)
        assertEquals("Shelf B", deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userSeriesName)
        assertEquals(
            annResStateBefore.versionFp,
            deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-replay-kr")!!.versionFp,
        )
        assertEquals(
            seriesResStateBefore.versionFp,
            deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_SERIES, "isbn:9780547928227")!!.versionFp,
        )
    }

    @Test
    fun `local resolution Tx1-Tx2 crash recovery preserves post-Tx1 edit after KEEP_REMOTE and recreate after DELETE`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(
            deviceA,
            "resolving-crash.epub",
            "9780441172719",
            "Dune",
            "Frank Herbert",
            fileSeriesName = "Dune Chronicles",
            fileSeriesIndex = 1.0,
        )
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780441172719"

        // Seed 2 annotations (one for KEEP_REMOTE + post-Tx1 edit, one for DELETE + post-Tx1 recreate),
        // plus evaluation review (KEEP_REMOTE + post-Tx1 edit), metadata title (DELETE + post-Tx1 recreate),
        // and series override (KEEP_REMOTE + post-Tx1 edit).
        val annKr = BookAnnotation(
            id = "ann-tx1-kr", bookId = bookA.url, kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""", text = "Spice", note = "v0-kr",
            createdAt = 1_000L, updatedAt = 1_000_000L,
        )
        val annDel = BookAnnotation(
            id = "ann-tx1-del", bookId = bookA.url, kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch02.xhtml"}""", text = "Arrakis", note = "v0-del",
            createdAt = 1_100L, updatedAt = 1_100_000L,
        )
        deviceA.liseurDb.annotationDao().upsert(annKr)
        deviceA.liseurDb.annotationDao().upsert(annDel)
        deviceA.liseurDb.bookEvaluationDao().edit(bookA.url, PersonalEvaluation.REVIEW, "Review v0", "Dune", "Frank Herbert", 1_000L)
        val repoA = BookMetadataOverrideRepository(
            bookDao = deviceA.liseurDb.bookDao(),
            overrideDao = deviceA.liseurDb.bookMetadataOverrideDao(),
            coversDir = deviceA.coversDir,
            inTransaction = { work -> deviceA.liseurDb.withTransaction { work() } },
        )
        val repoB = BookMetadataOverrideRepository(
            bookDao = deviceB.liseurDb.bookDao(),
            overrideDao = deviceB.liseurDb.bookMetadataOverrideDao(),
            coversDir = deviceB.coversDir,
            inTransaction = { work -> deviceB.liseurDb.withTransaction { work() } },
        )
        repoA.applyTextEdits(bookA.url, title = FieldEdit.Set("Dune v0"))
        deviceA.liseurDb.bookDao().setSeriesOverride(bookA.url, "Dune v0", 1.0, 1_000L)

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // Create concurrent offline conflicts across all 5 records!
        deviceA.liseurDb.annotationDao().upsert(annKr.copy(note = "A-kr", updatedAt = 2_000_000L))
        deviceB.liseurDb.annotationDao().upsert(deviceB.liseurDb.annotationDao().byId("ann-tx1-kr")!!.copy(note = "B-kr", updatedAt = 2_100_000L))

        deviceA.liseurDb.annotationDao().upsert(annDel.copy(note = "A-del", updatedAt = 2_200_000L))
        deviceB.liseurDb.annotationDao().upsert(deviceB.liseurDb.annotationDao().byId("ann-tx1-del")!!.copy(note = "B-del", updatedAt = 2_300_000L))

        deviceA.liseurDb.bookEvaluationDao().edit(bookA.url, PersonalEvaluation.REVIEW, "Review A", "Dune", "Frank Herbert", 2_000L)
        deviceB.liseurDb.bookEvaluationDao().edit(stubB, PersonalEvaluation.REVIEW, "Review B", "Dune", "Frank Herbert", 2_100L)

        repoA.applyTextEdits(bookA.url, title = FieldEdit.Set("Dune A"))
        repoB.applyTextEdits(stubB, title = FieldEdit.Set("Dune B"))

        deviceA.liseurDb.bookDao().setSeriesOverride(bookA.url, "Series A", 2.0, 2_000L)
        deviceB.liseurDb.bookDao().setSeriesOverride(stubB, "Series B", 3.0, 2_100L)

        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        val openA = deviceA.driveDb.conflicts().openForAccount(PERM_ID)
        assertEquals(5, openA.size)
        val openByKey = openA.associateBy { it.domain to it.recordKey }
        val preStates = openA.associate { (it.domain to it.recordKey) to deviceA.driveDb.recordStates().get(PERM_ID, it.domain, it.recordKey)!! }

        val workKey = "isbn:9780441172719"
        val reviewKey = DriveEvaluationSync.recordKey(workKey, PersonalEvaluation.REVIEW)
        val titleKey = DriveMetadataSync.recordKey(workKey, MetadataOverrideFields.TITLE)

        val choicesByPair = mapOf(
            (DriveWireCodec.DOMAIN_ANNOTATION to "ann-tx1-kr") to DriveConflictChoice.KEEP_REMOTE,
            (DriveWireCodec.DOMAIN_ANNOTATION to "ann-tx1-del") to DriveConflictChoice.DELETE,
            (DriveWireCodec.DOMAIN_EVALUATION to reviewKey) to DriveConflictChoice.KEEP_REMOTE,
            (DriveWireCodec.DOMAIN_METADATA to titleKey) to DriveConflictChoice.DELETE,
            (DriveWireCodec.DOMAIN_SERIES to workKey) to DriveConflictChoice.KEEP_REMOTE,
        )

        // Apply each local choice (commits Tx 1 to liseur.db + Tx 2 to drive_sync.db without syncing)...
        for ((pair, choice) in choicesByPair) {
            deviceA.driveSync.resolveConflicts(listOf(openByKey.getValue(pair)), choice, syncAfter = false)
        }

        // ...then roll back ONLY drive_sync.db to simulate a crash RIGHT AFTER Tx 1 and BEFORE Tx 2!
        deviceA.driveDb.withTransaction {
            for ((pair, choice) in choicesByPair) {
                val (domain, recordKey) = pair
                deviceA.driveDb.recordStates().upsert(preStates.getValue(pair))
                deviceA.driveDb.openHelper.writableDatabase.execSQL(
                    "DELETE FROM drive_resolution WHERE permission_id = ? AND domain = ? AND record_key = ?",
                    arrayOf<Any>(PERM_ID, domain, recordKey),
                )
                val conflict = openByKey.getValue(pair)
                deviceA.driveDb.conflicts().upsert(
                    conflict.copy(
                        state = DriveConflict.STATE_RESOLVING,
                        choice = choice.name,
                        resolutionFp = null,
                        resolvedAt = null,
                    ),
                )
            }
        }
        assertEquals(5, deviceA.driveDb.conflicts().resolvingForAccount(PERM_ID).size)

        // Before restart/recovery runs, the reader makes a post-Tx1 edit (after KEEP_REMOTE)
        // or recreates the item (after DELETE)!
        deviceA.liseurDb.annotationDao().upsert(
            deviceA.liseurDb.annotationDao().byId("ann-tx1-kr")!!.copy(note = "Post-Tx1 edited note", updatedAt = 5_000_000L),
        )
        deviceA.liseurDb.annotationDao().upsert(
            annDel.copy(note = "Post-Tx1 recreated note", updatedAt = 5_100_000L),
        )
        deviceA.liseurDb.bookEvaluationDao().edit(bookA.url, PersonalEvaluation.REVIEW, "Post-Tx1 edited review", "Dune", "Frank Herbert", 5_000L)
        repoA.applyTextEdits(bookA.url, title = FieldEdit.Set("Post-Tx1 recreated title"))
        deviceA.liseurDb.bookDao().setSeriesOverride(bookA.url, "Post-Tx1 Series", 9.0, 5_000L)

        // Restart & sync on Device A: recovery MUST NOT overwrite any post-Tx1 edit/recreation!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        assertTrue(deviceA.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        assertTrue(deviceA.driveDb.conflicts().resolvingForAccount(PERM_ID).isEmpty())
        assertEquals("Post-Tx1 edited note", deviceA.liseurDb.annotationDao().byId("ann-tx1-kr")!!.note)
        assertEquals("Post-Tx1 recreated note", deviceA.liseurDb.annotationDao().byId("ann-tx1-del")!!.note)
        assertEquals("Post-Tx1 edited review", deviceA.liseurDb.bookEvaluationDao().get(bookA.url, PersonalEvaluation.REVIEW)!!.value)
        assertEquals("Post-Tx1 recreated title", deviceA.liseurDb.bookDao().getByUrl(bookA.url)!!.title)
        assertEquals("Post-Tx1 Series", deviceA.liseurDb.bookDao().getByUrl(bookA.url)!!.userSeriesName)
        assertEquals(9.0, deviceA.liseurDb.bookDao().getByUrl(bookA.url)!!.userSeriesIndex!!, 0.001)

        // Original resolutions were finalized and recorded in drive_resolution + resRefs.
        for ((domain, recordKey) in choicesByPair.keys) {
            val resList = deviceA.driveDb.resolutions().forRecord(PERM_ID, domain, recordKey)
            assertTrue("Expected finalized drive_resolution for $domain/$recordKey", resList.isNotEmpty())
            val state = deviceA.driveDb.recordStates().get(PERM_ID, domain, recordKey)!!
            val resRefs = DriveWorkIdentity.decodeStringArray(state.resRefsJson)
            assertTrue("Expected resRefs to carry recovered resolutionFp for $domain/$recordKey", resList.any { it.resolutionFp in resRefs })
        }

        // Device B pulls A's recovered op="resolve" + post-Tx1 successors -> settles conflicts & converges cleanly!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        assertEquals("Post-Tx1 edited note", deviceB.liseurDb.annotationDao().byId("ann-tx1-kr")!!.note)
        assertEquals("Post-Tx1 recreated note", deviceB.liseurDb.annotationDao().byId("ann-tx1-del")!!.note)
        assertEquals("Post-Tx1 edited review", deviceB.liseurDb.bookEvaluationDao().get(stubB, PersonalEvaluation.REVIEW)!!.value)
        assertEquals("Post-Tx1 recreated title", deviceB.liseurDb.bookDao().getByUrl(stubB)!!.title)
        assertEquals("Post-Tx1 Series", deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userSeriesName)
        assertEquals(9.0, deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userSeriesIndex!!, 0.001)
    }

    @Test
    fun `series DELETE_EDIT KEEP_REMOTE Tx1 crash followed by newer reset preserves reset and converges`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "series-del-reset.epub", "9780441172719", "Dune", "Frank Herbert")
        deviceA.liseurDb.bookDao().setSeriesOverride(bookA.url, "Initial Series", 1.0, 1_000L)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780441172719"

        // Concurrent divergence: A clears its series override (OP_DEL at t=2_000), B edits series to "B Series" #3.
        deviceA.liseurDb.bookDao().clearSeriesOverride(bookA.url, 2_000L)
        deviceB.liseurDb.bookDao().setSeriesOverride(stubB, "B Series", 3.0, 2_100L)

        // Both push concurrently before seeing each other; then A pulls -> DELETE_EDIT conflict on A (with OP_DEL).
        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        val conflict = deviceA.driveDb.conflicts().openForAccount(PERM_ID)
            .single { it.domain == DriveWireCodec.DOMAIN_SERIES }
        assertEquals(DriveConflict.KIND_DELETE_EDIT, conflict.kind)

        // Simulate KEEP_REMOTE: pre-Tx1 markResolving records the OP_DEL baseline (t=2_000) on DriveRecordState,
        // Tx1 applies B's series override, then crash BEFORE Tx2!
        val baselineJson = org.json.JSONObject()
            .put("deleted", true)
            .put("userSeriesUpdatedAt", 2_000L)
            .toString()
        val stateBefore = deviceA.driveDb.recordStates()
            .get(PERM_ID, DriveWireCodec.DOMAIN_SERIES, conflict.recordKey)!!
        deviceA.driveDb.recordStates().upsert(stateBefore.copy(bodyJson = baselineJson))
        deviceA.driveDb.conflicts().markResolving(PERM_ID, conflict.conflictId, DriveConflictChoice.KEEP_REMOTE.name)
        deviceA.liseurDb.bookDao().setSeriesOverride(bookA.url, "B Series", 3.0, 2_100L)

        // Before restart/recovery, reader resets the series again at a newer timestamp (t=5_000).
        deviceA.liseurDb.bookDao().clearSeriesOverride(bookA.url, 5_000L)

        // Restart & sync on Device A: recovery MUST NOT overwrite the newer reset with "B Series"!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        assertTrue(deviceA.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        assertTrue(deviceA.driveDb.conflicts().resolvingForAccount(PERM_ID).isEmpty())
        val afterA = deviceA.liseurDb.bookDao().getByUrl(bookA.url)!!
        assertFalse("Newer reader reset must be preserved on Device A", afterA.seriesOverridden)
        assertFalse("Newer index reset must be preserved on Device A", afterA.indexOverridden)
        assertEquals(5_000L, afterA.userSeriesUpdatedAt)

        // Device B pulls A's recovered KEEP_REMOTE resolution + causal successor reset -> converges to reset!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        val afterB = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
        assertFalse("Device B must converge to the newer reader reset", afterB.seriesOverridden)
        assertFalse("Device B index must converge to reset", afterB.indexOverridden)
    }

    @Test
    fun `transient cover download failure does not advance metadata cursor and applies cover on retry`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "cover-retry.epub", "9780547928227", "The Hobbit", "J.R.R. Tolkien")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780547928227"
        val cursorBefore = deviceB.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_METADATA)!!.appliedSeq

        // Device A uploads a custom cover override.
        val repoA = BookMetadataOverrideRepository(
            bookDao = deviceA.liseurDb.bookDao(),
            overrideDao = deviceA.liseurDb.bookMetadataOverrideDao(),
            coversDir = deviceA.coversDir,
            inTransaction = { work -> deviceA.liseurDb.withTransaction { work() } },
            encodeCoverJpeg = { it },
        )
        assertNotNull(repoA.coverFromBytes(bookA.url, "COVER-RETRY-IMAGE-BYTES".toByteArray()))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // Inject a one-shot transient network failure when Device B downloads the cover blob!
        driveServer.failNextCoverDownloads(1)
        val firstRun = deviceB.changeEngine.sync()
        assertTrue("Expected transient sync failure on cover download error", firstRun.outcome is SyncOutcome.Failure)
        assertEquals(
            cursorBefore,
            deviceB.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_METADATA)!!.appliedSeq,
        )
        assertFalse(deviceB.liseurDb.bookDao().getByUrl(stubB)!!.coverOverridden)

        // Retry succeeds -> cover is downloaded, verified, applied, and metadata cursor advances!
        val retryRun = deviceB.changeEngine.sync()
        assertEquals(SyncOutcome.Success, retryRun.outcome)
        val updatedB = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
        assertTrue(updatedB.coverOverridden)
        assertNotNull(updatedB.userCoverPath)
        assertTrue(File(updatedB.userCoverPath!!).isFile)
        val cursorAfterFirstCover = deviceB.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_METADATA)!!.appliedSeq
        assertTrue(cursorAfterFirstCover > cursorBefore)

        // Also verify explicit deferred/not-applied path (corrupt SHA-256 -> downloadVerified returns null):
        // metadata cursor must stay unadvanced and the next incremental sync (with zero new Drive changes)
        // must replay and apply the cover cleanly!
        assertNotNull(repoA.coverFromBytes(bookA.url, "COVER-RETRY-IMAGE-BYTES-V2".toByteArray()))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        driveServer.corruptNextCoverDownloads(1)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(
            cursorAfterFirstCover,
            deviceB.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_METADATA)!!.appliedSeq,
        )
        val shaAfterFirstCover = updatedB.userCoverSha256
        assertEquals(shaAfterFirstCover, deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userCoverSha256)

        // Retry with zero new Drive changes replays the deferred metadata seq and applies the new cover!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val updatedV2 = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
        assertNotEquals(shaAfterFirstCover, updatedV2.userCoverSha256)
        assertTrue(File(updatedV2.userCoverPath!!).isFile)
        assertTrue(
            deviceB.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_METADATA)!!.appliedSeq > cursorAfterFirstCover,
        )
    }

    @Test
    fun `cover install failure defers the record without advancing the cursor and applies on retry`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "cover-install.epub", "9780547928227", "The Hobbit", "J.R.R. Tolkien")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780547928227"
        val cursorBefore = deviceB.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_METADATA)!!.appliedSeq

        val repoA = BookMetadataOverrideRepository(
            bookDao = deviceA.liseurDb.bookDao(),
            overrideDao = deviceA.liseurDb.bookMetadataOverrideDao(),
            coversDir = deviceA.coversDir,
            inTransaction = { work -> deviceA.liseurDb.withTransaction { work() } },
            encodeCoverJpeg = { it },
        )
        assertNotNull(repoA.coverFromBytes(bookA.url, "COVER-INSTALL-IMAGE-BYTES".toByteArray()))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        // The published cover is the encoded WebP, so its hash comes from the cache file, not the reader's source image.
        val sha = deviceA.coversDir.listFiles { f -> f.name.startsWith("drive-cover-") }!!.single()
            .name.removePrefix("drive-cover-").removeSuffix(".webp")

        // A non-empty directory at the install's temp path makes the copy fail, like a full or read-only disk.
        val bookHash = MessageDigest.getInstance("SHA-1").digest(stubB.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        val blocker = File(deviceB.coversDir, "drive-book-$bookHash-${sha.take(12)}.webp.tmp")
        assertTrue(File(blocker, "keep").apply { parentFile!!.mkdirs() }.createNewFile())

        deviceB.changeEngine.sync()
        assertEquals(
            cursorBefore,
            deviceB.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_METADATA)!!.appliedSeq,
        )
        assertFalse(deviceB.liseurDb.bookDao().getByUrl(stubB)!!.coverOverridden)
        assertTrue(deviceB.liseurDb.bookMetadataOverrideDao().revisions(stubB).isEmpty())

        blocker.deleteRecursively()
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val applied = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
        assertTrue(applied.coverOverridden)
        assertEquals(sha, applied.userCoverSha256)
        assertTrue(File(applied.userCoverPath!!).isFile)
        assertTrue(
            deviceB.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_METADATA)!!.appliedSeq > cursorBefore,
        )
    }

    @Test
    fun `persisted Phase 4 domain pauses from older build clear after upgrade and replay records from appliedSeq`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(
            deviceA,
            "upgrade-unpause.epub",
            "9780441172719",
            "Dune",
            "Frank Herbert",
            fileSeriesName = "Dune",
            fileSeriesIndex = 1.0,
        )
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780441172719"

        // Device A publishes records in all 4 Phase 4 domains (annotation, evaluation, metadata, series).
        deviceA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-upgrade-1", bookId = bookA.url, kind = AnnotationKind.HIGHLIGHT.name,
                locatorJson = """{"href":"ch01.xhtml"}""", text = "Fear is the mind-killer", note = "Classic",
                tint = "YELLOW", createdAt = 1_000L, updatedAt = 1_000_000L,
            ),
        )
        deviceA.liseurDb.bookEvaluationDao().edit(bookA.url, PersonalEvaluation.RATING, "10", "Dune", "Frank Herbert", 1_000L)
        val repoA = BookMetadataOverrideRepository(
            bookDao = deviceA.liseurDb.bookDao(),
            overrideDao = deviceA.liseurDb.bookMetadataOverrideDao(),
            coversDir = deviceA.coversDir,
            inTransaction = { work -> deviceA.liseurDb.withTransaction { work() } },
        )
        repoA.applyTextEdits(bookA.url, title = FieldEdit.Set("Dune (Upgraded)"))
        deviceA.liseurDb.bookDao().setSeriesOverride(bookA.url, "Dune Upgraded Series", 1.0, 1_500L)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // Simulate an older (Phase 3) build on Device B that indexed dev-a's new delta and advanced
        // Phase 3 cursors & startPageToken, while persisting pausedSchemaVersion = 1 on Phase 4 + Phase 5 domains!
        val latestCatalogSeq = deviceA.driveDb.accounts().get(PERM_ID)!!.nextSeq - 1L
        val allRemoteFiles = driveServer.appDataFilesSnapshot()
        deviceB.driveDb.withTransaction {
            for (remote in allRemoteFiles) {
                val props = remote.appProperties
                val kind = props["kind"] ?: continue
                deviceB.driveDb.files().upsert(
                    DriveFile(
                        permissionId = PERM_ID,
                        fileId = remote.id,
                        name = remote.name,
                        kind = kind,
                        deviceId = props["deviceId"],
                        seq = props["seq"]?.toLongOrNull(),
                        trashed = remote.trashed,
                        createdTime = remote.createdTime,
                    ),
                )
            }
            for (domain in DriveWireCodec.PHASE3_DOMAINS.keys) {
                deviceB.driveDb.cursors().upsert(DriveCursor(PERM_ID, "dev-a", domain, appliedSeq = latestCatalogSeq, pausedSchemaVersion = null))
            }
            val phase4Only = listOf(
                DriveWireCodec.DOMAIN_ANNOTATION,
                DriveWireCodec.DOMAIN_METADATA,
                DriveWireCodec.DOMAIN_SERIES,
                DriveWireCodec.DOMAIN_EVALUATION,
            )
            for (domain in phase4Only) {
                deviceB.driveDb.cursors().upsert(DriveCursor(PERM_ID, "dev-a", domain, appliedSeq = 0L, pausedSchemaVersion = 1))
            }
            // Phase 5 domain v1 pause should ALSO unpause cleanly now that Phase 5 is supported!
            deviceB.driveDb.cursors().upsert(
                DriveCursor(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_VOCAB_WORD, appliedSeq = 0L, pausedSchemaVersion = 1),
            )
            // Unsupported future schema version (v2) and unknown future domain MUST stay paused!
            deviceB.driveDb.cursors().upsert(
                DriveCursor(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_SESSION, appliedSeq = 0L, pausedSchemaVersion = 2),
            )
            deviceB.driveDb.cursors().upsert(
                DriveCursor(PERM_ID, "dev-a", "futureDomain", appliedSeq = 0L, pausedSchemaVersion = 1),
            )
            // Advance startPageToken to the head so incremental sync sees ZERO new Drive changes!
            deviceB.driveDb.accounts().updateStartPageToken(PERM_ID, driveServer.getStartPageToken("token-dev-b"))
        }

        // Verify none of the Phase 4 records exist on B before the upgraded sync runs.
        assertNull(deviceB.liseurDb.annotationDao().byId("ann-upgrade-1"))
        assertNull(deviceB.liseurDb.bookEvaluationDao().get(stubB, PersonalEvaluation.RATING))
        assertEquals("Dune", deviceB.liseurDb.bookDao().getByUrl(stubB)!!.title)
        assertFalse(deviceB.liseurDb.bookDao().getByUrl(stubB)!!.seriesOverridden)

        // Upgraded build runs incremental sync (no new remote changes on Drive)!
        val summary = deviceB.changeEngine.sync()
        assertEquals(SyncOutcome.Success, summary.outcome)

        // All Phase 4 + Phase 5 v1 domain pauses are cleared and records are imported from appliedSeq!
        for (domain in listOf(
            DriveWireCodec.DOMAIN_ANNOTATION,
            DriveWireCodec.DOMAIN_METADATA,
            DriveWireCodec.DOMAIN_SERIES,
            DriveWireCodec.DOMAIN_EVALUATION,
            DriveWireCodec.DOMAIN_VOCAB_WORD,
        )) {
            val cursor = deviceB.driveDb.cursors().get(PERM_ID, "dev-a", domain)!!
            assertNull("Expected pause cleared for $domain", cursor.pausedSchemaVersion)
            assertEquals(latestCatalogSeq, cursor.appliedSeq)
        }
        assertEquals("Classic", deviceB.liseurDb.annotationDao().byId("ann-upgrade-1")!!.note)
        assertEquals("10", deviceB.liseurDb.bookEvaluationDao().get(stubB, PersonalEvaluation.RATING)!!.value)
        assertEquals("Dune (Upgraded)", deviceB.liseurDb.bookDao().getByUrl(stubB)!!.title)
        assertEquals("Dune Upgraded Series", deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userSeriesName)

        // Unsupported future version (v2) and unknown future domain cursors remain paused!
        val sessionV2Cursor = deviceB.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_SESSION)!!
        assertEquals(2, sessionV2Cursor.pausedSchemaVersion)
        assertEquals(0L, sessionV2Cursor.appliedSeq)
        val futureDomainCursor = deviceB.driveDb.cursors().get(PERM_ID, "dev-a", "futureDomain")!!
        assertEquals(1, futureDomainCursor.pausedSchemaVersion)
        assertEquals(0L, futureDomainCursor.appliedSeq)
    }

    // -- Harness --------------------------------------------------------------

    /** Leaves device A holding one open review conflict against device B's concurrent edit. */
    private suspend fun openReviewConflict(): Pair<Book, DriveConflict> {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "recovery.epub", "9780441172719", "Dune", "Frank Herbert")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780441172719"
        deviceA.liseurDb.bookEvaluationDao().edit(bookA.url, PersonalEvaluation.REVIEW, "Review v0", "Dune", "Frank Herbert", 1_000L)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        deviceA.liseurDb.bookEvaluationDao().edit(bookA.url, PersonalEvaluation.REVIEW, "Review A", "Dune", "Frank Herbert", 2_000L)
        deviceB.liseurDb.bookEvaluationDao().edit(stubB, PersonalEvaluation.REVIEW, "Review B", "Dune", "Frank Herbert", 2_100L)
        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        return bookA to deviceA.driveDb.conflicts().openForAccount(PERM_ID).single()
    }

    private fun crashBeforeResolutionCommit(device: Phase4Device) {
        device.driveDb.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER crash_tx2 BEFORE INSERT ON drive_resolution BEGIN SELECT RAISE(ABORT, 'injected crash'); END",
        )
    }

    private fun restoreAfterCrash(device: Phase4Device) {
        device.driveDb.openHelper.writableDatabase.execSQL("DROP TRIGGER crash_tx2")
    }

    @Test
    fun `a resolving conflict is not settled by an older published resolution of the same record`() = runTest {
        val (bookA, conflict) = openReviewConflict()
        val reviewKey = DriveEvaluationSync.recordKey("isbn:9780441172719", PersonalEvaluation.REVIEW)
        val state = deviceA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_EVALUATION, reviewKey)!!
        deviceA.driveDb.resolutions().upsert(
            DriveResolution(
                PERM_ID, DriveWireCodec.DOMAIN_EVALUATION, reviewKey, "old-resolution", """["old-fp"]""",
                DriveResolution.OUTCOME_PUT, "0017450000000-0001-dev-a", "dev-a", 1L,
            ),
        )
        deviceA.driveDb.recordStates().upsert(
            state.copy(versionFp = "old-resolution", versionOp = DriveRecordState.OP_RESOLVE, publishedFp = "old-resolution"),
        )
        // Crash window: the new choice is recorded but its resolution never committed.
        deviceA.driveDb.conflicts().upsert(
            conflict.copy(state = DriveConflict.STATE_RESOLVING, choice = DriveConflictChoice.KEEP_REMOTE.name, resolutionFp = null),
        )

        deviceA.changeEngine.sync()

        val settled = deviceA.driveDb.conflicts().get(PERM_ID, conflict.conflictId)!!
        assertEquals(DriveConflict.STATE_RESOLVED, settled.state)
        assertNotEquals("old-resolution", settled.resolutionFp)
        assertEquals("Review B", deviceA.liseurDb.bookEvaluationDao().get(bookA.url, PersonalEvaluation.REVIEW)!!.value)
    }

    @Test
    fun `evaluation crash recovery republishes the exact chosen body after a later edit`() = runTest {
        val (bookA, conflict) = openReviewConflict()
        val evaluations = deviceA.liseurDb.bookEvaluationDao()
        evaluations.edit(bookA.url, PersonalEvaluation.REVIEW, "A edited", "Dune", "Frank Herbert", 3_000L)
        crashBeforeResolutionCommit(deviceA)
        val failure = runCatching {
            deviceA.driveSync.resolveConflicts(listOf(conflict), DriveConflictChoice.KEEP_EDITED, syncAfter = false)
        }
        assertTrue(failure.isFailure)
        restoreAfterCrash(deviceA)
        assertEquals(1, deviceA.driveDb.conflicts().resolvingForAccount(PERM_ID).size)
        val peerId = DriveLibrarySync.accountPeerId(PERM_ID)
        val receipt = DriveWireCodec.decodeRevisionAckJson(evaluations.state(peerId, bookA.url, PersonalEvaluation.REVIEW)!!.sentJson)!!
        assertEquals(DriveRecordState.OP_RESOLVE, receipt.op)

        evaluations.edit(bookA.url, PersonalEvaluation.REVIEW, "A edited again", "Dune", "Frank Herbert", 4_000L)
        deviceA.changeEngine.sync()

        val published = driveServer.deltaRecords("dev-a").single {
            it.d == DriveWireCodec.DOMAIN_EVALUATION && it.op == DriveRecordState.OP_RESOLVE && it.fp == receipt.vfp
        }
        val body = DriveWireCodec.decodeEvaluationBody(published.body!!)!!
        assertEquals("A edited", body.value)
        assertEquals(receipt.cfp, DriveCanonicalJson.contentFp(DriveWireCodec.encodeEvaluationBody(body)))
        assertEquals("A edited again", evaluations.get(bookA.url, PersonalEvaluation.REVIEW)!!.value)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals("A edited again", deviceB.liseurDb.bookEvaluationDao().get("drive:isbn:9780441172719", PersonalEvaluation.REVIEW)!!.value)
        assertTrue(deviceB.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
    }

    @Test
    fun `evaluation crash recovery without a provable chosen body stays resolving and publishes nothing`() = runTest {
        val (bookA, conflict) = openReviewConflict()
        val evaluations = deviceA.liseurDb.bookEvaluationDao()
        evaluations.edit(bookA.url, PersonalEvaluation.REVIEW, "A edited", "Dune", "Frank Herbert", 3_000L)
        crashBeforeResolutionCommit(deviceA)
        runCatching {
            deviceA.driveSync.resolveConflicts(listOf(conflict), DriveConflictChoice.KEEP_EDITED, syncAfter = false)
        }
        restoreAfterCrash(deviceA)
        val peerId = DriveLibrarySync.accountPeerId(PERM_ID)
        val sync = evaluations.state(peerId, bookA.url, PersonalEvaluation.REVIEW)!!
        val receipt = DriveWireCodec.decodeRevisionAckJson(sync.sentJson)!!
        evaluations.upsertState(sync.copy(sentJson = DriveWireCodec.encodeRevisionAckJson(receipt.copy(bodyJson = null))))
        evaluations.edit(bookA.url, PersonalEvaluation.REVIEW, "A edited again", "Dune", "Frank Herbert", 4_000L)

        deviceA.changeEngine.sync()

        assertEquals(1, deviceA.driveDb.conflicts().resolvingForAccount(PERM_ID).size)
        assertTrue(driveServer.deltaRecords("dev-a").none { it.op == DriveRecordState.OP_RESOLVE && it.fp == receipt.vfp })
        assertEquals("A edited again", evaluations.get(bookA.url, PersonalEvaluation.REVIEW)!!.value)
    }

    @Test
    fun `remote apply defers when the reader edits between reconciliation and commit`() = runTest {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "race.epub", "9780441172719", "Dune", "Frank Herbert")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780441172719"
        val annotation = BookAnnotation(
            id = "ann-race", bookId = bookA.url, kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""", text = "Spice", note = "v0",
            createdAt = 1_000L, updatedAt = 1_000_000L,
        )
        deviceA.liseurDb.annotationDao().upsert(annotation)
        deviceA.liseurDb.bookEvaluationDao().edit(bookA.url, PersonalEvaluation.REVIEW, "v0", "Dune", "Frank Herbert", 1_000L)
        deviceA.liseurDb.bookDao().setSeriesOverride(bookA.url, "Series v0", 1.0, 1_000L)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        deviceA.liseurDb.annotationDao().upsert(annotation.copy(note = "A", updatedAt = 2_000_000L))
        deviceA.liseurDb.bookEvaluationDao().edit(bookA.url, PersonalEvaluation.REVIEW, "A", "Dune", "Frank Herbert", 2_000L)
        deviceA.liseurDb.bookDao().setSeriesOverride(bookA.url, "Series A", 2.0, 2_000L)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // The reader's edit lands once per domain, after the record is reconciled and before it commits.
        val fired = mutableSetOf<String>()
        clockHooks["dev-b"] = {
            val inside = Throwable().stackTrace.map { it.className }
            fun entering(name: String) = inside.any { it.startsWith("com.chmouel.liseur.drive.$name") } && fired.add(name)
            runBlocking {
                when {
                    entering("DriveAnnotationSync") -> deviceB.liseurDb.annotationDao().upsert(
                        deviceB.liseurDb.annotationDao().byId("ann-race")!!.copy(note = "B newer", updatedAt = 9_000_000L),
                    )
                    entering("DriveEvaluationSync") ->
                        deviceB.liseurDb.bookEvaluationDao().edit(stubB, PersonalEvaluation.REVIEW, "B newer", "Dune", "Frank Herbert", 9_000L)
                    entering("DriveSeriesSync") -> deviceB.liseurDb.bookDao().setSeriesOverride(stubB, "Series B newer", 3.0, 9_000L)
                }
            }
        }
        deviceB.changeEngine.sync()
        clockHooks.remove("dev-b")

        assertEquals(setOf("DriveAnnotationSync", "DriveEvaluationSync", "DriveSeriesSync"), fired)
        assertEquals("B newer", deviceB.liseurDb.annotationDao().byId("ann-race")!!.note)
        assertEquals("B newer", deviceB.liseurDb.bookEvaluationDao().get(stubB, PersonalEvaluation.REVIEW)!!.value)
        assertEquals("Series B newer", deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userSeriesName)

        // The deferred records replay and reconcile against the newer edits instead of erasing them.
        deviceB.changeEngine.sync()
        assertEquals("B newer", deviceB.liseurDb.annotationDao().byId("ann-race")!!.note)
        assertEquals("B newer", deviceB.liseurDb.bookEvaluationDao().get(stubB, PersonalEvaluation.REVIEW)!!.value)
        assertEquals("Series B newer", deviceB.liseurDb.bookDao().getByUrl(stubB)!!.userSeriesName)
    }

    @Test
    fun `snapshot resolutions are keyed by canonical work after an alias merge and annotation ids stay`() = runTest {
        deviceA.connect()
        val hlc = "0017450000000-0002-dev-c"
        val merge = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_WORK_ALIAS,
            key = "merge:sha:ccc",
            hlc = hlc,
            bodyMap = DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge("sha:ccc", "isbn:bbb", 100L)),
        )
        val batch = DriveDeltaBatch(
            batchId = "snapshot-part",
            deviceId = "dev-c",
            seq = 1L,
            createdHlc = hlc,
            domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
            records = listOf(merge),
        )
        val resolutions = JSONArray()
        for ((domain, key) in listOf(
            DriveWireCodec.DOMAIN_SERIES to "sha:ccc",
            DriveWireCodec.DOMAIN_EVALUATION to "sha:ccc/review",
            DriveWireCodec.DOMAIN_METADATA to "sha:ccc/title",
            DriveWireCodec.DOMAIN_ANNOTATION to "ann-snapshot",
        )) {
            resolutions.put(
                JSONObject()
                    .put("d", domain).put("k", key).put("resolutionFp", "res-$domain")
                    .put("resolvedFps", JSONArray(listOf("fp-1", "fp-2")))
                    .put("outcome", DriveResolution.OUTCOME_DELETE).put("hlc", hlc),
            )
        }
        val bytes = JSONObject(DriveWireCodec.encodeDeltaBatch(batch))
            .put("throughSeq", 5L).put("partIndex", 0).put("partCount", 1).put("resolutions", resolutions)
            .toString().toByteArray()
        val file = driveServer.clientFor("dev-c").createAppDataFile("token", "snapshot-part.json", "application/json", bytes)

        deviceA.changeEngine.applySnapshot(
            PERM_ID, "dev-a", "dev-c",
            DriveSnapshotRef(5L, listOf(DriveSnapshotPartRef("snapshot-part.json", file.id, DriveCanonicalJson.sha256Hex(bytes), 1, 4))),
            "token",
        )

        val store = deviceA.driveDb.resolutions()
        for ((domain, key) in listOf(
            DriveWireCodec.DOMAIN_SERIES to "isbn:bbb",
            DriveWireCodec.DOMAIN_EVALUATION to "isbn:bbb/review",
            DriveWireCodec.DOMAIN_METADATA to "isbn:bbb/title",
            DriveWireCodec.DOMAIN_ANNOTATION to "ann-snapshot",
        )) {
            assertEquals("canonical $domain/$key", listOf("res-$domain"), store.forRecord(PERM_ID, domain, key).map { it.resolutionFp })
        }
        assertTrue(store.forRecord(PERM_ID, DriveWireCodec.DOMAIN_SERIES, "sha:ccc").isEmpty())
        assertTrue(store.forRecord(PERM_ID, DriveWireCodec.DOMAIN_EVALUATION, "sha:ccc/review").isEmpty())
        assertTrue(store.forRecord(PERM_ID, DriveWireCodec.DOMAIN_METADATA, "sha:ccc/title").isEmpty())
    }

    @Test
    fun `catalog tombstone without local cleanup is finished on the next sync and keeps annotations`() = runTest {
        deviceA.connect()
        deviceB.connect()
        seedBook(deviceA, "crash-cleanup.epub", "9780441013593", "Dune Messiah", "Frank Herbert")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780441013593"
        val stub = deviceB.liseurDb.bookDao().getByUrl(stubB)!!
        deviceB.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-keep", bookId = stubB, kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch01.xhtml"}""", text = "Kept", note = "survives",
                createdAt = 1_000L, updatedAt = 1_000L,
            ),
        )
        val peer = DriveLibrarySync.accountPeerId(PERM_ID)
        assertNotNull(deviceB.liseurDb.workIdentityDao().alias(stubB, peer))

        // The process dies after the tombstone commits but before the book rows are removed.
        val sqlite = deviceB.liseurDb.openHelper.writableDatabase
        sqlite.execSQL("CREATE TRIGGER crash_on_book_delete BEFORE DELETE ON books BEGIN SELECT RAISE(ABORT, 'crash'); END")
        assertTrue(runCatching { deviceB.driveSync.deleteFromSyncedLibrary(stub) }.isFailure)
        sqlite.execSQL("DROP TRIGGER crash_on_book_delete")

        val workKey = "isbn:9780441013593"
        val tombstone = deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, workKey)!!
        assertEquals(DriveRecordState.OP_DEL, tombstone.versionOp)
        assertNotNull(deviceB.liseurDb.bookDao().getByUrl(stubB))

        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        assertNull(deviceB.liseurDb.bookDao().getByUrl(stubB))
        assertNull(deviceB.liseurDb.workIdentityDao().alias(stubB, peer))
        assertEquals("survives", deviceB.liseurDb.annotationDao().byId("ann-keep")!!.note)
        assertFalse(deviceB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, workKey)!!.bodyJson.orEmpty().contains("cleanupPending"))
        // A second run changes nothing and does not re-create the book.
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertNull(deviceB.liseurDb.bookDao().getByUrl(stubB))
    }

    @Test
    fun `evaluation and series resets wait for the catalog on a fresh device and are not undone by an older put`() = runTest {
        // Remote streams apply in device-id order: dev-a (resets) is read before dev-z (catalog and puts).
        val owner = createDevice("dev-z", File(tempDir, "z-reset"))
        val fresh = createDevice("dev-c", File(tempDir, "c-reset"))
        try {
            owner.connect()
            deviceA.connect()
            fresh.connect()
            val book = seedBook(owner, "reset.epub", "9780441172719", "Dune", "Frank Herbert")
            owner.liseurDb.bookEvaluationDao().edit(book.url, PersonalEvaluation.REVIEW, "Original review", "Dune", "Frank Herbert", 1_000L)
            owner.liseurDb.bookDao().setSeriesOverride(book.url, "Original series", 1.0, 1_000L)
            assertEquals(SyncOutcome.Success, owner.changeEngine.sync().outcome)

            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            val stub = "drive:isbn:9780441172719"
            assertEquals("Original review", deviceA.liseurDb.bookEvaluationDao().get(stub, PersonalEvaluation.REVIEW)!!.value)
            deviceA.liseurDb.bookEvaluationDao().edit(stub, PersonalEvaluation.REVIEW, null, "Dune", "Frank Herbert", 2_000L)
            deviceA.liseurDb.bookDao().clearSeriesOverride(stub, 2_000L)
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

            assertEquals(SyncOutcome.Success, fresh.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, fresh.changeEngine.sync().outcome)

            assertNotNull(fresh.liseurDb.bookDao().getByUrl(stub))
            assertNull(fresh.liseurDb.bookEvaluationDao().get(stub, PersonalEvaluation.REVIEW)?.value)
            assertNull(fresh.liseurDb.bookDao().getByUrl(stub)!!.userSeriesName)
        } finally {
            owner.close()
            fresh.close()
        }
    }

    @Test
    fun `evaluation crash repair of an adopted incoming version drops the previous author's origin`() = runTest {
        deviceA.connect()
        val book = seedBook(deviceA, "origin.epub", "9780441172719", "Dune", "Frank Herbert")
        val peer = DriveLibrarySync.accountPeerId(PERM_ID)
        val workKey = "isbn:9780441172719"
        val key = DriveEvaluationSync.recordKey(workKey, PersonalEvaluation.REVIEW)
        deviceA.liseurDb.bookEvaluationDao().edit(book.url, PersonalEvaluation.REVIEW, "Incoming", "Dune", "Frank Herbert", 3_000L)
        val row = deviceA.liseurDb.bookEvaluationDao().get(book.url, PersonalEvaluation.REVIEW)!!
        val adoptedFp = "adopted-fp"
        deviceA.liseurDb.bookEvaluationDao().upsertState(
            EvaluationSync(
                peerId = peer, bookUrl = book.url, field = PersonalEvaluation.REVIEW, workId = workKey,
                ackedRevision = row.revision,
                sentJson = DriveWireCodec.encodeRevisionAckJson(
                    DriveRevisionAckPayload(vfp = adoptedFp, cfp = null, rev = row.revision, op = DriveRecordState.OP_PUT),
                ),
                sentRevision = row.revision, polledAt = 1L,
            ),
        )
        val previous = DriveRecordState(
            permissionId = PERM_ID, domain = DriveWireCodec.DOMAIN_EVALUATION, recordKey = key,
            versionFp = "previous-fp", versionOp = DriveRecordState.OP_PUT, publishedFp = "previous-fp",
            originDeviceId = "dev-prev", originSeq = 7L, lastHlc = "h1",
        )
        deviceA.driveDb.recordStates().upsert(previous)

        val repaired = deviceA.driveSync.evaluationSync.recoverFromRevisionSync(
            permissionId = PERM_ID, workKey = workKey, field = PersonalEvaluation.REVIEW, recordKey = key, row = row,
            sync = deviceA.liseurDb.bookEvaluationDao().state(peer, book.url, PersonalEvaluation.REVIEW),
            nextHlc = { "h2" },
        )!!
        assertEquals(adoptedFp, repaired.versionFp)
        assertNull(repaired.originDeviceId)
        assertNull(repaired.originSeq)

        // An unchanged fingerprint keeps its provenance.
        deviceA.driveDb.recordStates().upsert(previous.copy(versionFp = adoptedFp, publishedFp = adoptedFp))
        val kept = deviceA.driveSync.evaluationSync.recoverFromRevisionSync(
            permissionId = PERM_ID, workKey = workKey, field = PersonalEvaluation.REVIEW, recordKey = key, row = row,
            sync = deviceA.liseurDb.bookEvaluationDao().state(peer, book.url, PersonalEvaluation.REVIEW),
            nextHlc = { "h3" },
        )!!
        assertEquals("dev-prev", kept.originDeviceId)
    }

    @Test
    fun `annotation KEEP_EDITED crash before it reached liseur_db recovers the persisted choice and never undoes a later deletion`() = runTest {
        val id = "ann-keep-edited"
        val conflict = openAnnotationEditConflict(id)
        deviceA.liseurDb.annotationDao().upsert(
            deviceA.liseurDb.annotationDao().byId(id)!!.copy(note = "Edited while the conflict was open", updatedAt = 7_000_000L),
        )

        // Crash after the choice is durable in drive_sync.db but before liseur.db commits it.
        val sqlite = deviceA.liseurDb.openHelper.writableDatabase
        sqlite.execSQL("CREATE TRIGGER crash_ins BEFORE INSERT ON annotation_sync BEGIN SELECT RAISE(ABORT, 'crash'); END")
        sqlite.execSQL("CREATE TRIGGER crash_upd BEFORE UPDATE ON annotation_sync BEGIN SELECT RAISE(ABORT, 'crash'); END")
        assertTrue(runCatching { deviceA.driveSync.resolveConflicts(listOf(conflict), DriveConflictChoice.KEEP_EDITED, syncAfter = false) }.isFailure)
        sqlite.execSQL("DROP TRIGGER crash_ins")
        sqlite.execSQL("DROP TRIGGER crash_upd")
        val resolving = deviceA.driveDb.conflicts().resolvingForAccount(PERM_ID).single()
        assertNotNull(resolving.resolutionFp)

        // The reader deletes the note before recovery runs.
        deviceA.liseurDb.annotationDao().deleteById(id)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        assertNull("recovery must not restore a note the reader deleted", deviceA.liseurDb.annotationDao().byId(id))
        assertEquals(1, deviceA.driveDb.conflicts().resolvingForAccount(PERM_ID).size)
    }

    @Test
    fun `annotation KEEP_EDITED crash before liseur_db completes from the persisted body when the note is untouched`() = runTest {
        val id = "ann-keep-edited-ok"
        val conflict = openAnnotationEditConflict(id)
        deviceA.liseurDb.annotationDao().upsert(
            deviceA.liseurDb.annotationDao().byId(id)!!.copy(note = "Edited while the conflict was open", updatedAt = 7_000_000L),
        )
        val sqlite = deviceA.liseurDb.openHelper.writableDatabase
        sqlite.execSQL("CREATE TRIGGER crash_ins BEFORE INSERT ON annotation_sync BEGIN SELECT RAISE(ABORT, 'crash'); END")
        sqlite.execSQL("CREATE TRIGGER crash_upd BEFORE UPDATE ON annotation_sync BEGIN SELECT RAISE(ABORT, 'crash'); END")
        assertTrue(runCatching { deviceA.driveSync.resolveConflicts(listOf(conflict), DriveConflictChoice.KEEP_EDITED, syncAfter = false) }.isFailure)
        sqlite.execSQL("DROP TRIGGER crash_ins")
        sqlite.execSQL("DROP TRIGGER crash_upd")

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        assertEquals("Edited while the conflict was open", deviceA.liseurDb.annotationDao().byId(id)!!.note)
        assertTrue(deviceA.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        assertTrue(deviceA.driveDb.conflicts().resolvingForAccount(PERM_ID).isEmpty())
        assertEquals("Edited while the conflict was open", deviceB.liseurDb.annotationDao().byId(id)!!.note)
    }

    @Test
    fun `annotation KEEP_EDITED committed to liseur_db is not undone by a later deletion before the choice publishes`() = runTest {
        val id = "ann-keep-edited-del"
        val conflict = openAnnotationEditConflict(id)
        deviceA.liseurDb.annotationDao().upsert(
            deviceA.liseurDb.annotationDao().byId(id)!!.copy(note = "Edited while the conflict was open", updatedAt = 7_000_000L),
        )
        deviceA.driveSync.resolveConflicts(listOf(conflict), DriveConflictChoice.KEEP_EDITED, syncAfter = false)
        deviceA.liseurDb.annotationDao().deleteById(id)

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        assertNull(deviceA.liseurDb.annotationDao().byId(id))
        assertTrue(deviceA.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
    }

    private suspend fun openAnnotationEditConflict(id: String): DriveConflict {
        deviceA.connect()
        deviceB.connect()
        val bookA = seedBook(deviceA, "$id.epub", "9780547928227", "The Hobbit", "J.R.R. Tolkien")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val base = BookAnnotation(
            id = id, bookId = bookA.url, kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""", text = "Hole", note = "base",
            createdAt = 1_000L, updatedAt = 1_000_000L,
        )
        deviceA.liseurDb.annotationDao().upsert(base)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        deviceA.liseurDb.annotationDao().upsert(base.copy(note = "from A", updatedAt = 4_000_000L))
        deviceB.liseurDb.annotationDao().upsert(deviceB.liseurDb.annotationDao().byId(id)!!.copy(note = "from B", updatedAt = 4_500_000L))
        driveServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        driveServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        driveServer.unhideAll()
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        return deviceA.driveDb.conflicts().openForAccount(PERM_ID).single { it.recordKey == id }
    }

    private suspend fun seedBook(
        device: Phase4Device,
        fileName: String,
        isbn13: String,
        title: String,
        author: String,
        fileSeriesName: String? = null,
        fileSeriesIndex: Double? = null,
        customBytes: ByteArray = "EPUB-$isbn13-$fileName".toByteArray(),
    ): Book {
        val file = File(device.booksDir, fileName).apply {
            parentFile?.mkdirs()
            writeBytes(customBytes)
        }
        val fp = BookFingerprints.of(ByteArrayInputStream(customBytes))
        val book = Book(
            url = file.toURI().toString(),
            title = title,
            author = author,
            coverPath = null,
            source = null,
            addedAt = 1_000L,
            lastOpenedAt = null,
            localUri = file.toURI().toString(),
            downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size,
            fileTitle = title,
            fileAuthor = author,
            fileSeriesName = fileSeriesName,
            fileSeriesIndex = fileSeriesIndex,
            seriesName = fileSeriesName,
            seriesIndex = fileSeriesIndex,
            fileMetadataChecked = true,
            fileModifiedAt = file.lastModified(),
        )
        device.liseurDb.bookDao().upsert(book)
        device.liseurDb.workIdentityDao().upsert(
            BookFingerprintRow(book.url, fp.sha256, fp.partialMd5, fp.size, file.lastModified(), 1L),
        )
        device.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(book.url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn13)))),
        )
        return book
    }

    private fun createDevice(deviceId: String, rootDir: File): Phase4Device {
        val booksDir = File(rootDir, "books").apply { mkdirs() }
        val coversDir = File(rootDir, "covers").apply { mkdirs() }
        val stagingDir = File(rootDir, "staging").apply { mkdirs() }
        val liseurDb = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val driveDb = Room.inMemoryDatabaseBuilder(context, DriveSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val removal = BookRemoval(
            bookDao = liseurDb.bookDao(),
            sessionDao = liseurDb.readingSessionDao(),
            peerStateDao = liseurDb.syncPeerStateDao(),
            identityDao = liseurDb.workIdentityDao(),
            progressDao = liseurDb.readingProgressDao(),
            annotationDao = liseurDb.annotationDao(),
            annotationSyncDao = liseurDb.annotationSyncDao(),
            inTransaction = { work -> liseurDb.withTransaction { work() } },
            metadataDao = liseurDb.bookMetadataDao(),
            evaluationDao = liseurDb.bookEvaluationDao(),
        )
        val remoteAccount = RemoteAccountRepository(
            dao = liseurDb.remoteServerDao(),
            bookDao = liseurDb.bookDao(),
            progressDao = liseurDb.readingProgressDao(),
            seriesExtraDao = liseurDb.seriesExtraDao(),
            bookRemoval = removal,
            inTransaction = { work -> liseurDb.withTransaction { work() } },
        )
        val clockVal = AtomicLong(1_745_000_000_000L)
        val clock: () -> Long = {
            clockHooks[deviceId]?.invoke()
            clockVal.addAndGet(100L)
        }
        val deterministicEncoder = DriveWebpEncoder { sourceBytes, _, quality ->
            val payload = "WEBP-Q$quality:".toByteArray() + sourceBytes
            EncodedImageFrame(bytes = payload, width = 800, height = 1200)
        }
        val driveSync = DriveLibrarySync(
            context = context,
            database = liseurDb,
            remoteAccount = remoteAccount,
            bookRemoval = removal,
            switchCoordinator = LibrarySyncSwitchCoordinator(),
            deviceIdGenerator = { deviceId },
            openStore = { driveDb },
            authClient = object : DriveAuthClient {
                override suspend fun authorize(accountEmail: String?, selectAccount: Boolean) =
                    DriveAuthResult.Authorized("token-$deviceId")
                override suspend fun authorizeSilently(accountEmail: String?) =
                    DriveAuthResult.Authorized("token-$deviceId")
                override fun parseAuthorizationResult(data: Intent?) =
                    DriveAuthResult.Authorized("token-$deviceId")
                override suspend fun clearCachedToken(accessToken: String) = Unit
            },
            driveApi = driveServer.clientFor(deviceId),
            coversDirProvider = { coversDir },
            stagingDirProvider = { stagingDir },
            coverEncoder = deterministicEncoder,
            clock = clock,
        )
        return Phase4Device(
            deviceId = deviceId,
            booksDir = booksDir,
            coversDir = coversDir,
            stagingDir = stagingDir,
            liseurDb = liseurDb,
            driveDb = driveDb,
            driveSync = driveSync,
        )
    }

    private class Phase4Device(
        val deviceId: String,
        val booksDir: File,
        val coversDir: File,
        val stagingDir: File,
        val liseurDb: LiseurDatabase,
        val driveDb: DriveSyncDatabase,
        val driveSync: DriveLibrarySync,
    ) {
        val changeEngine: DriveChangeEngine get() = driveSync.changeEngine
        val epubTransfers: DriveEpubTransferManager get() = driveSync.epubTransfers

        suspend fun connect() {
            driveSync.activateAccount(PERM_ID, "reader@example.com", now = 1_000L)
        }

        fun close() {
            driveDb.close()
            liseurDb.close()
        }
    }

    /** Minimal deterministic in-memory Google Drive v3 fake with per-device delta visibility control. */
    private class Phase4FakeDriveServer : DriveRemoteApi {
        private data class StoredFile(
            var meta: DriveRemoteFile,
            var bytes: ByteArray,
            val space: String,
        )

        private val idGen = AtomicInteger(1)
        private val files = linkedMapOf<String, StoredFile>()
        private val changeLog = mutableListOf<DriveChange>()
        private var hiddenForDevice: String? = null
        private val coverDownloadFailuresRemaining = AtomicInteger(0)
        private val coverDownloadCorruptionsRemaining = AtomicInteger(0)
        var onUploadResumableStart: (suspend (name: String) -> Unit)? = null
        var onUploadResumableFinalPut: (suspend (name: String) -> Unit)? = null

        /** Runs once the bytes were read, before Drive commits the file (its session still in progress). */
        var beforeUploadCommit: (suspend (name: String) -> Unit)? = null
        var afterUploadFinalProgress: (suspend (name: String) -> Unit)? = null

        fun failNextCoverDownloads(count: Int) {
            coverDownloadFailuresRemaining.set(count)
        }

        /** Runs once, inside the next cover download, before its bytes are returned. */
        var duringNextCoverDownload: (suspend () -> Unit)? = null

        fun corruptNextCoverDownloads(count: Int) {
            coverDownloadCorruptionsRemaining.set(count)
        }

        fun hideOtherDeviceDeltasFor(deviceId: String) {
            hiddenForDevice = deviceId
        }

        fun unhideAll() {
            hiddenForDevice = null
        }

        private val hiddenAuthors = mutableMapOf<String, Set<String>>()

        /** Hides [authors]' delta files from [viewer] only; their changes are re-delivered once cleared. */
        fun hideAuthorsFor(viewer: String, vararg authors: String) {
            hiddenAuthors[viewer] = authors.toSet()
        }

        fun clearAuthorHides() {
            hiddenAuthors.clear()
        }

        fun appDataFilesSnapshot(): List<DriveRemoteFile> =
            files.values.filter { it.space == "appDataFolder" }.map { it.meta }

        fun deltaRecords(deviceId: String): List<DriveWireRecord> =
            files.values
                .filter { it.meta.name.startsWith("device-$deviceId-seq-") }
                .flatMap { DriveWireCodec.decodeDeltaBatch(it.bytes.toString(Charsets.UTF_8)).records }

        fun epubFilesSnapshot(): List<DriveRemoteFile> =
            files.values.filter { it.meta.appProperties["liseurKind"] == DriveFile.KIND_EPUB }.map { it.meta }

        fun clientFor(deviceId: String): DriveRemoteApi = object : DriveRemoteApi by this@Phase4FakeDriveServer {
            override suspend fun listAppDataFiles(accessToken: String, query: String?, pageSize: Int): List<DriveRemoteFile> =
                this@Phase4FakeDriveServer.listAppDataFiles(accessToken, query, pageSize).filter { file ->
                    if (file.appProperties["deviceId"] in (hiddenAuthors[deviceId] ?: emptySet())) {
                        false
                    } else if (hiddenForDevice == deviceId) {
                        val owner = file.appProperties["deviceId"]
                        owner == null || owner == deviceId
                    } else {
                        true
                    }
                }

            override suspend fun listChanges(accessToken: String, pageToken: String, pageSize: Int): DriveChangesPage {
                if (hiddenForDevice == deviceId) {
                    return DriveChangesPage(emptyList(), null, pageToken)
                }
                val page = this@Phase4FakeDriveServer.listChanges(accessToken, pageToken, pageSize)
                val hidden = hiddenAuthors[deviceId] ?: return page
                val isHidden = { change: DriveChange -> change.file?.appProperties?.get("deviceId") in hidden }
                val firstHidden = page.changes.indexOfFirst(isHidden)
                if (firstHidden < 0) return page
                val resumeToken = ((pageToken.toIntOrNull() ?: 1) + firstHidden).toString()
                return DriveChangesPage(page.changes.filterNot(isHidden), null, resumeToken)
            }
        }

        private fun nextId(prefix: String): String = "$prefix-${idGen.getAndIncrement()}"

        private fun recordChange(file: DriveRemoteFile, removed: Boolean = false) {
            changeLog.add(DriveChange(fileId = file.id, removed = removed, file = if (removed) null else file))
        }

        override suspend fun getAbout(accessToken: String) = DriveUserAbout(PERM_ID, "reader@example.com")

        override suspend fun listLiseurFolders(accessToken: String): List<DriveRemoteFile> =
            files.values
                .filter { it.space == "drive" && !it.meta.trashed && it.meta.mimeType == DriveRestClient.FOLDER_MIME_TYPE }
                .map { it.meta }
                .sortedWith(compareBy({ it.createdTime ?: "" }, { it.id }))

        override suspend fun ensureLiseurFolder(accessToken: String): LiseurFolderResolution {
            val existing = listLiseurFolders(accessToken)
            if (existing.isNotEmpty()) return LiseurFolderResolution(existing.first(), existing)
            val id = nextId("folder")
            val meta = DriveRemoteFile(
                id = id, name = DriveRestClient.LISEUR_FOLDER_NAME, mimeType = DriveRestClient.FOLDER_MIME_TYPE,
                createdTime = "2026-04-01T00:00:00Z",
                appProperties = mapOf("liseur" to "1", "kind" to DriveFile.KIND_FOLDER, "schemaVersion" to "1"),
            )
            files[id] = StoredFile(meta, ByteArray(0), "drive")
            recordChange(meta)
            val all = listLiseurFolders(accessToken)
            return LiseurFolderResolution(all.first(), all)
        }

        override suspend fun listDriveEpubFiles(accessToken: String): List<DriveRemoteFile> =
            files.values
                .filter { it.space == "drive" && !it.meta.trashed && it.meta.appProperties["liseurKind"] == DriveFile.KIND_EPUB }
                .map { it.meta }

        override suspend fun getStartPageToken(accessToken: String): String = (changeLog.size + 1).toString()

        override suspend fun listChanges(accessToken: String, pageToken: String, pageSize: Int): DriveChangesPage {
            val idx = (pageToken.toIntOrNull() ?: 1) - 1
            val slice = if (idx < changeLog.size) changeLog.subList(idx, changeLog.size).toList() else emptyList()
            return DriveChangesPage(slice, null, (changeLog.size + 1).toString())
        }

        override suspend fun listAppDataFiles(accessToken: String, query: String?, pageSize: Int): List<DriveRemoteFile> {
            val all = files.values.filter { it.space == "appDataFolder" && !it.meta.trashed }.map { it.meta }
            if (query.isNullOrBlank()) return all
            val nameMatch = Regex("name\\s*=\\s*'([^']+)'").find(query)?.groupValues?.get(1)
            val batchMatch = Regex("key='batchId'\\s+and\\s+value='([^']+)'").find(query)?.groupValues?.get(1)
            val devMatch = Regex("key='deviceId'\\s+and\\s+value='([^']+)'").find(query)?.groupValues?.get(1)
            return all.filter { f ->
                (nameMatch == null || f.name == nameMatch) &&
                    (batchMatch == null || f.appProperties["batchId"] == batchMatch) &&
                    (devMatch == null || f.appProperties["deviceId"] == devMatch)
            }
        }

        override suspend fun findAppDataFileByBatchId(accessToken: String, batchId: String): DriveRemoteFile? =
            files.values.firstOrNull {
                it.space == "appDataFolder" && !it.meta.trashed && it.meta.appProperties["batchId"] == batchId
            }?.meta

        override suspend fun downloadMedia(accessToken: String, fileId: String): ByteArray {
            val stored = files[fileId] ?: throw DriveNotFoundException("Missing $fileId", fileId)
            if (stored.meta.appProperties["kind"] == DriveFile.KIND_COVER) {
                duringNextCoverDownload?.let { hook ->
                    duringNextCoverDownload = null
                    hook()
                }
                if (coverDownloadFailuresRemaining.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                    throw DriveHttpException("Simulated transient cover download failure", httpCode = 503)
                }
                if (coverDownloadCorruptionsRemaining.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                    return "CORRUPT-COVER-BYTES".toByteArray()
                }
            }
            return stored.bytes.copyOf()
        }

        override suspend fun uploadAppDataJsonBatch(
            accessToken: String,
            name: String,
            jsonPayload: ByteArray,
            appProperties: Map<String, String>,
        ): DriveRemoteFile {
            appProperties["batchId"]?.let { bid -> findAppDataFileByBatchId(accessToken, bid)?.let { return it } }
            return createAppDataFile(accessToken, name, "application/json", jsonPayload, appProperties)
        }

        override suspend fun createAppDataFile(
            accessToken: String,
            name: String,
            mimeType: String,
            content: ByteArray,
            appProperties: Map<String, String>,
        ): DriveRemoteFile {
            val id = nextId("app")
            val meta = DriveRemoteFile(
                id = id, name = name, mimeType = mimeType, parents = listOf("appDataFolder"),
                trashed = false, appProperties = appProperties, size = content.size.toLong(),
                createdTime = "2026-04-01T00:00:00Z",
            )
            files[id] = StoredFile(meta, content.copyOf(), "appDataFolder")
            recordChange(meta)
            return meta
        }

        override suspend fun updateFileMedia(
            accessToken: String,
            fileId: String,
            mimeType: String,
            content: ByteArray,
            appProperties: Map<String, String>?,
        ): DriveRemoteFile {
            val stored = files[fileId] ?: throw DriveNotFoundException("Missing $fileId", fileId)
            stored.bytes = content.copyOf()
            stored.meta = stored.meta.copy(
                size = content.size.toLong(),
                appProperties = appProperties ?: stored.meta.appProperties,
            )
            recordChange(stored.meta)
            return stored.meta
        }

        override suspend fun deleteFile(accessToken: String, fileId: String) {
            val removed = files.remove(fileId) ?: throw DriveNotFoundException("Missing $fileId", fileId)
            recordChange(removed.meta, removed = true)
        }

        /** Number of upcoming trashFile calls that fail as if offline. */
        var failNextTrashes = 0

        /** Runs once, inside the next trashFile call, before the file is trashed. */
        var duringNextTrash: (suspend () -> Unit)? = null

        override suspend fun trashFile(accessToken: String, fileId: String): DriveRemoteFile {
            duringNextTrash?.let { hook ->
                duringNextTrash = null
                hook()
            }
            if (failNextTrashes > 0) {
                failNextTrashes--
                throw DriveHttpException("Simulated offline trash", httpCode = 503)
            }
            val stored = files[fileId] ?: throw DriveNotFoundException("Missing $fileId", fileId)
            stored.meta = stored.meta.copy(trashed = true)
            recordChange(stored.meta)
            return stored.meta
        }

        override suspend fun initiateResumableUpload(
            accessToken: String, name: String, mimeType: String, totalBytes: Long,
            parents: List<String>, appProperties: Map<String, String>,
        ): String = "session://${nextId("up")}"

        private val completedSessions = mutableMapOf<String, String>()

        /** Drops every completed session, as Drive does once they expire. */
        fun expireSessions() = completedSessions.clear()

        fun sessionOf(fileId: String): String = completedSessions.entries.first { it.value == fileId }.key

        override suspend fun queryResumableUploadOffset(
            accessToken: String, sessionUri: String, totalBytes: Long,
        ): ResumableUploadStatus = completedSessions[sessionUri]?.let { files[it]?.meta }
            ?.let { ResumableUploadStatus.Completed(it) }
            ?: ResumableUploadStatus.InProgress(0L)

        override suspend fun uploadResumableChunk(
            accessToken: String, sessionUri: String, chunk: ByteArray,
            offset: Long, totalBytes: Long, mimeType: String,
        ): ResumableUploadStatus = throw UnsupportedOperationException()

        override suspend fun uploadResumableFile(
            accessToken: String, name: String, mimeType: String, totalBytes: Long,
            parents: List<String>, appProperties: Map<String, String>, existingSessionUri: String?,
            chunkSizeBytes: Int, openStream: () -> InputStream,
            onSessionUri: suspend (String) -> Unit, onProgress: suspend (Long, String) -> Unit,
        ): DriveRemoteFile {
            existingSessionUri?.let { session ->
                completedSessions[session]?.let { id -> return files.getValue(id).meta }
            }
            val sessionUri = existingSessionUri ?: "session://${nextId("up")}"
            onSessionUri(sessionUri)
            onUploadResumableStart?.invoke(name)
            val bytes = openStream().use { it.readBytes() }
            beforeUploadCommit?.invoke(name)
            val id = nextId("epub")
            val meta = DriveRemoteFile(
                id = id, name = name, mimeType = mimeType, parents = parents,
                trashed = false, appProperties = appProperties, size = bytes.size.toLong(),
                createdTime = "2026-04-01T00:00:00Z",
            )
            files[id] = StoredFile(meta, bytes, "drive")
            completedSessions[sessionUri] = id
            recordChange(meta)
            onUploadResumableFinalPut?.invoke(name)
            onProgress(bytes.size.toLong(), sessionUri)
            afterUploadFinalProgress?.invoke(name)
            return meta
        }

        override suspend fun downloadResumable(
            accessToken: String, fileId: String, destination: OutputStream,
            startOffset: Long, onRestartFromZero: () -> Unit, onProgress: (Long) -> Unit,
        ): DriveDownloadResult {
            val stored = files[fileId] ?: throw DriveNotFoundException("Missing $fileId", fileId)
            val slice = stored.bytes.copyOfRange(startOffset.toInt(), stored.bytes.size)
            destination.write(slice)
            onProgress(stored.bytes.size.toLong())
            return DriveDownloadResult(slice.size.toLong(), stored.bytes.size.toLong(), false)
        }
    }

    companion object {
        private const val PERM_ID = "perm-phase4"
    }
}
