package com.chmouel.liseur.drive

import android.app.Application
import android.content.Intent
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.library.BookMetadataOverrideRepository
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.library.FieldEdit
import com.chmouel.liseur.data.remote.FurthestDestination
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.ResumeConfidence
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.BookFingerprints
import com.chmouel.liseur.domain.Identifier
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.PersonalEvaluation
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.StatsBook
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.domain.readingStats
import com.chmouel.liseur.sync.LibrarySyncSwitchCoordinator
import com.chmouel.liseur.vocabulary.Recall
import com.chmouel.liseur.vocabulary.VocabularyLookup
import com.chmouel.liseur.vocabulary.VocabularyOccurrence
import com.chmouel.liseur.vocabulary.VocabularyRepository
import com.chmouel.liseur.vocabulary.VocabularyWord
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.first
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
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 6 deterministic compaction, pruning, and multi-device resilience test suite
 * (`docs/DRIVE_SYNC.md` §2.4 & §5.2, ADR-0078):
 * - Multi-part snapshot compaction & one-generation-lag pruning of superseded deltas and snapshots,
 *   bounding `drive.appdata` file count, preserving tombstones (`OP_DEL`), `resRefs`, and
 *   vocabulary delete `observed` vectors, plus 404 fallback on pruned deltas.
 * - Returning offline device after >64 successor edits and >=3 compactions (`res1.fp in f70.resRefs`
 *   bridged settlement when `fA`/`fB`/`res1` have aged out of `f70.anc`).
 * - Independent-branch / skewed-clock repro (`fB -> fD` with higher HLC and without `res1.fp in fD.resRefs`
 *   MUST NOT bridge or close C's open conflict).
 * - Compacted-successor crash-between-transactions repro (both unedited Agreement and post-crash
 *   local edit Stale/preserved variants: Step 0 `resRefs`-bridge settlement closes `{fA, fB}` and
 *   advances cursor only after Tx 2).
 * - Clean reinstall from compacted snapshots alone across all 12 domains + EPUB availability.
 * - Duplicate/reordered delta batches, clock skew, intentional backward reading moves,
 *   furthest-position reset, competing resolution vs subsequent edit, and unsupported
 *   `schemaVersion` / domain version pause & upgrade notice.
 */
@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DrivePhase6CompactionAndResilienceTest {

    private lateinit var context: Application
    private lateinit var server: Phase6FakeDriveServer
    private lateinit var tempDir: File
    private val openDevices = mutableListOf<Phase6Device>()

    @Before
    fun setUp() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "drive-p6-test-${System.nanoTime()}").apply { mkdirs() }
        server = Phase6FakeDriveServer()
    }

    @After
    fun tearDown() {
        openDevices.forEach { it.close() }
        openDevices.clear()
        tempDir.deleteRecursively()
    }

    @Test
    fun `multi-part snapshot compaction prunes superseded deltas and old snapshots, bounds appData file count, and preserves tombstones and vocab delete observed vectors`() = runTest {
        val maxPartBytes = 2_400
        val devA = createDevice(
            deviceId = "dev-a",
            compactionDeltaThreshold = 5,
            compactionMaxPartBytes = maxPartBytes,
        )
        val devB = createDevice(deviceId = "dev-b")
        devA.connect()
        devB.connect()

        val bookA = seedBook(devA, "compact.epub", "9780547928227", "The Hobbit", "J.R.R. Tolkien")
        val vocabRepoA = VocabularyRepository(devA.liseurDb)
        val vocabRepoB = VocabularyRepository(devB.liseurDb)
        val baseTime = 1_745_000_000_000L

        // Seed an annotation (that we'll delete later for tombstone preservation), a live annotation,
        // and a vocabulary word (that A will delete while B stays untouched vs. a second word B reviews).
        val annLive = BookAnnotation(
            id = "ann-live",
            bookId = bookA.url,
            kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""",
            text = "In a hole in the ground",
            note = "Initial note",
            createdAt = baseTime,
            updatedAt = baseTime * 1_000L,
        )
        val annToDelete = BookAnnotation(
            id = "ann-tombstone",
            bookId = bookA.url,
            kind = AnnotationKind.HIGHLIGHT.name,
            locatorJson = """{"href":"ch02.xhtml"}""",
            text = "To be deleted",
            note = null,
            tint = "YELLOW",
            createdAt = baseTime + 100L,
            updatedAt = (baseTime + 100L) * 1_000L,
        )
        devA.liseurDb.annotationDao().upsert(annLive)
        devA.liseurDb.annotationDao().upsert(annToDelete)
        val wordDeletedId = vocabRepoA.collect(
            lookup = VocabularyLookup("éphémère", "fr", null),
            definition = "short-lived",
            translationLanguage = "en",
            now = baseTime,
        )!!
        val wordConcurrentReviewId = vocabRepoA.collect(
            lookup = VocabularyLookup("lumière", "fr", null),
            definition = "light",
            translationLanguage = "en",
            now = baseTime + 10L,
        )!!

        // Initial sync A -> B so B has both annotations and both vocab words.
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)

        // Now B goes offline and reviews `lumière` (creating a concurrent review unknown to A's observed vector),
        // while leaving `éphémère` and `ann-tombstone` untouched.
        val wordBReview = devB.liseurDb.vocabularyDao().findWord("lumière", "fr")!!
        vocabRepoB.review(
            card = wordBReview,
            rating = Recall.GOOD,
            newLimit = 0,
            reviewLimit = 0,
            now = baseTime + 5_000L,
        )

        // A deletes `ann-tombstone`, deletes both vocab words, and then performs 18 edits on `ann-live`
        // (1 delta per sync -> 20+ deltas total -> triggers >= 4 compactions with threshold = 5).
        devA.liseurDb.annotationDao().deleteById("ann-tombstone")
        devA.liseurDb.vocabularyDao().deleteWord(wordDeletedId)
        devA.liseurDb.vocabularyDao().deleteWord(wordConcurrentReviewId)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        var compactionCount = 0
        var totalPrunedDeltas = 0
        var totalPrunedSnapshotParts = 0
        val fileCountsAfterCompaction = mutableListOf<Int>()

        for (i in 1..20) {
            devA.liseurDb.annotationDao().upsert(
                annLive.copy(
                    note = "Compaction edit #$i — padding text to ensure multi-part snapshot splitting works reliably",
                    updatedAt = (baseTime + i * 1_000L) * 1_000L,
                ),
            )
            val run = devA.changeEngine.sync()
            assertEquals(SyncOutcome.Success, run.outcome)
            if (run.compacted) {
                compactionCount++
                totalPrunedDeltas += run.prunedDeltaCount
                totalPrunedSnapshotParts += run.prunedSnapshotPartCount
                fileCountsAfterCompaction.add(server.liveAppDataFiles().size)
            }
        }

        // Verify >= 4 compactions ran, deltas and old snapshot parts were ACTUALLY pruned from Drive,
        // and total appData file count stays strictly bounded!
        assertTrue("Expected >= 4 compactions, got $compactionCount", compactionCount >= 4)
        assertTrue("Expected pruned deltas > 0, got $totalPrunedDeltas", totalPrunedDeltas >= 10)
        assertTrue("Expected pruned snapshot parts > 0, got $totalPrunedSnapshotParts", totalPrunedSnapshotParts >= 2)

        val manifestA = server.decodeManifestFor("dev-a")!!
        val latestSnap = manifestA.snapshot
        val prevSnap = manifestA.previousSnapshot
        assertNotNull("Latest snapshot must be set in manifest", latestSnap)
        assertNotNull("Previous snapshot must be preserved (1-gen lag)", prevSnap)
        assertTrue("Multi-part snapshot should have > 1 part with $maxPartBytes-byte limit", latestSnap!!.parts.size > 1)
        assertTrue(latestSnap.throughSeq > prevSnap!!.throughSeq)

        // Verify surviving deltas on Drive: NO delta with seq <= prevSnap.throughSeq survives!
        val survivingDeltas = server.liveAppDataFiles()
            .filter { it.appProperties["kind"] == DriveFile.KIND_DELTA && it.appProperties["deviceId"] == "dev-a" }
        assertTrue(
            "Expected all deltas <= ${prevSnap.throughSeq} pruned, found ${survivingDeltas.map { it.appProperties["seq"] }}",
            survivingDeltas.all { (it.appProperties["seq"]?.toLongOrNull() ?: 0L) > prevSnap.throughSeq },
        )
        // Verify surviving snapshot files on Drive: ONLY latestSnap + prevSnap parts exist!
        val expectedSnapshotIds = (latestSnap.parts + prevSnap.parts).map { it.fileId }.toSet()
        val liveSnapshotIds = server.liveAppDataFiles()
            .filter { it.appProperties["kind"] == DriveFile.KIND_SNAPSHOT && it.appProperties["deviceId"] == "dev-a" }
            .map { it.id }
            .toSet()
        assertEquals(expectedSnapshotIds, liveSnapshotIds)

        // Verify EVERY emitted snapshot part's actual UTF-8 byte size <= maxPartBytes and hashes match.
        for (ref in prevSnap.parts) {
            val rawBytes = server.rawBytes(ref.fileId)!!
            assertTrue(
                "Previous snapshot part ${ref.name} is ${rawBytes.size} bytes, exceeding maxPartBytes=$maxPartBytes",
                rawBytes.size <= maxPartBytes,
            )
            assertEquals(ref.sha256, DriveCanonicalJson.sha256Hex(rawBytes))
        }
        val decodedParts = latestSnap.parts.map { ref ->
            val rawBytes = server.rawBytes(ref.fileId)!!
            assertTrue(
                "Snapshot part ${ref.name} is ${rawBytes.size} bytes, exceeding maxPartBytes=$maxPartBytes",
                rawBytes.size <= maxPartBytes,
            )
            assertEquals(ref.sha256, DriveCanonicalJson.sha256Hex(rawBytes))
            DriveWireCodec.decodeSnapshotPart(rawBytes.toString(Charsets.UTF_8))
        }
        val allSnapRecords = decodedParts.flatMap { it.records }
        val annTombstoneRec = allSnapRecords.firstOrNull { it.d == DriveWireCodec.DOMAIN_ANNOTATION && it.k == "ann-tombstone" }
        assertNotNull("Annotation OP_DEL tombstone must survive compaction", annTombstoneRec)
        assertEquals(DriveRecordState.OP_DEL, annTombstoneRec!!.op)
        assertNull(annTombstoneRec.body)

        val vocabDelRec = allSnapRecords.firstOrNull {
            it.d == DriveWireCodec.DOMAIN_VOCAB_WORD && it.k == DriveVocabularySync.wordKey("fr", "lumière")
        }
        assertNotNull("VocabWord OP_DEL must survive compaction", vocabDelRec)
        assertEquals(DriveRecordState.OP_DEL, vocabDelRec!!.op)
        assertNotNull("VocabWord OP_DEL must preserve observed vector across compactions", vocabDelRec.observed)

        // Publish one more delta (seq = throughSeq + 1) AFTER compaction and verify manifest preserves snapshot pointers!
        devA.liseurDb.annotationDao().upsert(
            annLive.copy(
                note = "Post-compaction tail delta #21",
                updatedAt = (baseTime + 99_000L) * 1_000L,
            ),
        )
        val tailRun = devA.changeEngine.sync()
        assertEquals(SyncOutcome.Success, tailRun.outcome)
        assertFalse(tailRun.compacted)
        val manifestAfterTail = server.decodeManifestFor("dev-a")!!
        assertEquals(latestSnap, manifestAfterTail.snapshot)
        assertEquals(prevSnap, manifestAfterTail.previousSnapshot)

        // Now B reconnects: ingests compacted multi-part snapshot + subsequent tail delta #21!
        // Also verify B handles a 404 if a stale indexed delta ID was pruned before B pulled.
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)

        // B converged to tail delta #21, silently deleted `ann-tombstone` and `éphémère`,
        // AND preserved `lumière` (add-wins because B had an unseen concurrent review)!
        assertEquals("Post-compaction tail delta #21", devB.liseurDb.annotationDao().byId("ann-live")!!.note)
        assertNull(devB.liseurDb.annotationDao().byId("ann-tombstone"))
        assertNull(devB.liseurDb.vocabularyDao().findWord("ephemere", "fr"))
        assertNotNull(devB.liseurDb.vocabularyDao().findWord("lumière", "fr"))

        // And bounded file count check: last compaction file count didn't grow linearly with edits.
        assertTrue(
            "AppData file count should remain bounded (${fileCountsAfterCompaction.last()})",
            fileCountsAfterCompaction.last() <= 20,
        )
    }

    @Test
    fun `returning offline device after 70 successor edits and 3+ compactions closes open conflict via resRefs bridge`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 20)
        val devB = createDevice(deviceId = "dev-b")
        val devC = createDevice(deviceId = "dev-c")
        devA.connect()
        devB.connect()
        devC.connect()

        // 1. A, B, and C converge on book + annotation f0.
        val bookA = seedBook(devA, "bridge.epub", "9780441172719", "Dune", "Frank Herbert")
        val baseTime = 1_745_200_000_000L
        val ann0 = BookAnnotation(
            id = "ann-bridge",
            bookId = bookA.url,
            kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""",
            text = "The sleeper must awaken",
            note = "f0",
            createdAt = baseTime,
            updatedAt = baseTime * 1_000L,
        )
        devA.liseurDb.annotationDao().upsert(ann0)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)

        // 2. A edits to fA and B edits to fB offline; both upload before pulling.
        devA.liseurDb.annotationDao().upsert(ann0.copy(note = "fA", updatedAt = (baseTime + 1_000L) * 1_000L))
        devB.liseurDb.annotationDao().upsert(
            devB.liseurDb.annotationDao().byId("ann-bridge")!!.copy(note = "fB", updatedAt = (baseTime + 2_000L) * 1_000L),
        )
        server.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        server.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        server.unhideAll()

        // 3. C pulls B first (so C's local L = fB), then pulls A (opening conflict {fA, fB} on C!).
        server.hideOtherDeviceDeltasFor("dev-b-only-for-c", hiddenDevices = setOf("dev-a"))
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        assertEquals("fB", devC.liseurDb.annotationDao().byId("ann-bridge")!!.note)
        server.unhideAll()
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)

        val openOnC = devC.driveDb.conflicts().openForAccount(PERM_ID)
        assertEquals(1, openOnC.size)
        val conflictOnC = openOnC.single()
        assertEquals(DriveConflict.KIND_EDIT_EDIT, conflictOnC.kind)
        val fpA = conflictOnC.remoteFp
        val fpB = conflictOnC.localFp

        // 4. C now goes offline. A pulls B's fB (opening {fA, fB} on A) and resolves as res1 (KEEP_LOCAL = fA).
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val openOnA = devA.driveDb.conflicts().openForAccount(PERM_ID)
        assertEquals(1, openOnA.size)
        devA.driveSync.resolveConflicts(openOnA, DriveConflictChoice.KEEP_LOCAL, syncAfter = true)
        val res1 = devA.driveDb.resolutions().forRecord(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-bridge").single()

        // 5. A performs 70 successor edits (f1..f70), triggering >= 3 compactions (pruning old deltas & snapshots).
        var compactionsOnA = 0
        for (i in 1..70) {
            devA.liseurDb.annotationDao().upsert(
                ann0.copy(
                    note = "f$i",
                    updatedAt = (baseTime + (10 + i) * 1_000L) * 1_000L,
                ),
            )
            val run = devA.changeEngine.sync()
            assertEquals(SyncOutcome.Success, run.outcome)
            if (run.compacted) compactionsOnA++
        }
        assertTrue("Expected >= 3 compactions across 70 successor edits, got $compactionsOnA", compactionsOnA >= 3)

        // Verify f70's anc has aged out fA, fB, AND res1.fp (capped at 64), while res1.fp IS in f70.resRefs
        // and res1 IS in A's compacted snapshot `resolutions` section!
        val stateA70 = devA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-bridge")!!
        val anc70 = DriveWorkIdentity.decodeStringArray(stateA70.ancestorsJson)
        val resRefs70 = DriveWorkIdentity.decodeStringArray(stateA70.resRefsJson)
        assertEquals(DriveWireCodec.MAX_ANCESTORS, anc70.size)
        assertFalse("fA must be aged out of f70.anc", fpA in anc70)
        assertFalse("fB must be aged out of f70.anc", fpB in anc70)
        assertFalse("res1.fp must be aged out of f70.anc", res1.resolutionFp in anc70)
        assertTrue("res1.fp MUST survive in uncapped f70.resRefs", res1.resolutionFp in resRefs70)

        val manifestA = server.decodeManifestFor("dev-a")!!
        assertNotNull(manifestA.snapshot)
        val snapParts = manifestA.snapshot!!.parts.map { ref ->
            DriveWireCodec.decodeSnapshotPart(server.rawBytes(ref.fileId)!!.toString(Charsets.UTF_8))
        }
        assertTrue(
            "res1 must be in snapshot.resolutions",
            snapParts.flatMap { it.resolutions }.any { it.resolutionFp == res1.resolutionFp },
        )

        // 6. C reconnects after 70 edits + >=3 compactions!
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)

        // Assert C closed {fA, fB} cleanly via resRefs bridge, converged to f70, zero open conflicts!
        assertEquals("f70", devC.liseurDb.annotationDao().byId("ann-bridge")!!.note)
        assertTrue("Expected zero open conflicts on C", devC.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        val settledC = devC.driveDb.conflicts().get(PERM_ID, conflictOnC.conflictId)!!
        assertEquals(DriveConflict.STATE_RESOLVED, settledC.state)
        assertEquals(res1.resolutionFp, settledC.resolutionFp)
    }

    @Test
    fun `independent branch with skewed higher clock without resRefs MUST NOT bridge or close C conflict`() = runTest {
        val devA = createDevice(deviceId = "dev-a")
        val devB = createDevice(deviceId = "dev-b")
        val devC = createDevice(deviceId = "dev-c")
        devA.connect()
        devB.connect()
        devC.connect()

        // 1. Converge A, B, C on f0.
        val bookA = seedBook(devA, "skew.epub", "9780345391803", "Hitchhiker", "Douglas Adams")
        val baseTime = 1_745_300_000_000L
        val ann0 = BookAnnotation(
            id = "ann-skew",
            bookId = bookA.url,
            kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""",
            text = "Don't Panic",
            note = "f0",
            createdAt = baseTime,
            updatedAt = baseTime * 1_000L,
        )
        devA.liseurDb.annotationDao().upsert(ann0)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)

        // 2. A edits f0 -> fA; B edits f0 -> fB. Both upload before pulling.
        devA.liseurDb.annotationDao().upsert(ann0.copy(note = "fA", updatedAt = (baseTime + 1_000L) * 1_000L))
        devB.liseurDb.annotationDao().upsert(
            devB.liseurDb.annotationDao().byId("ann-skew")!!.copy(note = "fB", updatedAt = (baseTime + 2_000L) * 1_000L),
        )
        server.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        server.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        server.unhideAll()

        // 3. C pulls B (L = fB), then pulls A -> C holds OPEN conflict {fA, fB} with L = fB.
        server.hideOtherDeviceDeltasFor("hide-a", hiddenDevices = setOf("dev-a"))
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        server.unhideAll()
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        val openOnCBefore = devC.driveDb.conflicts().openForAccount(PERM_ID)
        assertEquals(1, openOnCBefore.size)
        val conflictAB = openOnCBefore.single()
        val fpB = conflictAB.localFp

        // 4. A pulls B and resolves {fA, fB} as res1 (KEEP_LOCAL = fA).
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        devA.driveSync.resolveConflicts(
            devA.driveDb.conflicts().openForAccount(PERM_ID),
            DriveConflictChoice.KEEP_LOCAL,
            syncAfter = true,
        )
        val res1 = devA.driveDb.resolutions().forRecord(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-skew").single()

        // 5. C ingests ONLY res1's durable resolution fact into `drive_resolution` (simulating receiving
        //    res1 metadata from snapshot.resolutions while C still holds OPEN conflict {fA, fB} and L = fB),
        //    then receives Device D's independent edit fB -> fD with skewed HIGHER HLC and EMPTY resRefs!
        devC.driveDb.resolutions().upsert(res1)

        val skewedBody = DriveAnnotationBody(
            id = "ann-skew",
            workKey = "isbn:9780345391803",
            editionSha = null,
            kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""",
            text = "Don't Panic",
            note = "fD-skewed-independent-branch",
            tint = null,
            chapter = null,
            totalProgression = null,
            createdAt = baseTime,
            noteCreatedAt = null,
            noteUpdatedAt = null,
            updatedAt = (baseTime + 999_999L) * 1_000L,
        )
        val skewedBodyMap = DriveWireCodec.encodeAnnotationBody(skewedBody)
        val skewedFp = DriveWireCodec.annotationContentFp(skewedBody)
        val skewedHigherHlc = "9999999999999-0001-dev-d"
        assertTrue("fD.hlc must be strictly higher than res1.hlc", skewedHigherHlc > res1.hlc)
        val fDRecord = DriveWireCodec.putRecordWithFp(
            domain = DriveWireCodec.DOMAIN_ANNOTATION,
            key = "ann-skew",
            hlc = skewedHigherHlc,
            fp = skewedFp,
            bodyMap = skewedBodyMap,
            ancestors = listOf(fpB), // fB in fD.anc, BUT fB is retired & in an OPEN conflict!
            resRefs = emptyList(),    // res1.fp NOT in fD.resRefs!
        )

        // Inject D's delta onto Drive and let C pull ONLY D's delta (without pulling A's resolve record).
        server.injectRemoteDelta(
            deviceId = "dev-d",
            seq = 1L,
            createdHlc = skewedHigherHlc,
            records = listOf(fDRecord),
        )
        server.hideOtherDeviceDeltasFor("hide-a-for-c", hiddenDevices = setOf("dev-a"))
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        server.unhideAll()

        // Assert: C MUST NOT silently apply fD, MUST NOT overwrite fB, and MUST NOT close {fA, fB}!
        assertEquals("fB", devC.liseurDb.annotationDao().byId("ann-skew")!!.note)
        val conflictABAfter = devC.driveDb.conflicts().get(PERM_ID, conflictAB.conflictId)!!
        assertEquals(
            "Original conflict {fA, fB} MUST remain OPEN when fD lacks res1.fp in resRefs",
            DriveConflict.STATE_OPEN,
            conflictABAfter.state,
        )
        assertTrue(
            "fD must be recorded as concurrent divergence rather than bridged",
            devC.driveDb.conflicts().openForAccount(PERM_ID).any { it.remoteFp == skewedFp },
        )
    }

    @Test
    fun `compacted-successor crash-between-transactions closes open conflict via Step 0 resRefs bridge for both unedited Agreement and post-crash local edit variants`() = runTest {
        runCompactedSuccessorCrashVariant(withPostCrashLocalEdit = false)
        runCompactedSuccessorCrashVariant(withPostCrashLocalEdit = true)
    }

    private suspend fun runCompactedSuccessorCrashVariant(withPostCrashLocalEdit: Boolean) {
        val variantServer = Phase6FakeDriveServer()
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 20, driveServer = variantServer)
        val devB = createDevice(deviceId = "dev-b", driveServer = variantServer)
        val devC = createDevice(deviceId = "dev-c", driveServer = variantServer)
        devA.connect()
        devB.connect()
        devC.connect()

        // Converge A, B, C on f0.
        val bookA = seedBook(devA, "crash-bridge.epub", "9780441172719", "Dune", "Frank Herbert")
        val baseTime = 1_745_400_000_000L
        val ann0 = BookAnnotation(
            id = "ann-crash-bridge",
            bookId = bookA.url,
            kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""",
            text = "Muad'Dib",
            note = "f0",
            createdAt = baseTime,
            updatedAt = baseTime * 1_000L,
        )
        devA.liseurDb.annotationDao().upsert(ann0)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)

        // Offline conflict {fA, fB}; C pulls B then A -> C holds OPEN {fA, fB} with L = fB.
        devA.liseurDb.annotationDao().upsert(ann0.copy(note = "fA", updatedAt = (baseTime + 1_000L) * 1_000L))
        devB.liseurDb.annotationDao().upsert(
            devB.liseurDb.annotationDao().byId("ann-crash-bridge")!!.copy(note = "fB", updatedAt = (baseTime + 2_000L) * 1_000L),
        )
        variantServer.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        variantServer.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        variantServer.unhideAll()

        variantServer.hideOtherDeviceDeltasFor("hide-a", hiddenDevices = setOf("dev-a"))
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        variantServer.unhideAll()
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)

        // Snapshot C's pre-compaction drive_sync.db state before C goes offline.
        val conflictBefore = devC.driveDb.conflicts().openForAccount(PERM_ID).single()
        val recordStateBefore = devC.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-crash-bridge")!!
        val cursorsBefore = devC.driveDb.cursors().forDevice(PERM_ID, "dev-a")
        val preSnapshotCursorSeq = cursorsBefore.firstOrNull { it.domain == DriveWireCodec.DOMAIN_ANNOTATION }?.appliedSeq ?: 0L
        val startTokenBefore = devC.driveDb.accounts().get(PERM_ID)!!.startPageToken

        // A resolves {fA, fB} -> res1, then makes >= 70 successor edits ending RIGHT on a compaction boundary
        // so the final compacted snapshot S_k holds `fLast` directly with zero trailing deltas.
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        devA.driveSync.resolveConflicts(
            devA.driveDb.conflicts().openForAccount(PERM_ID),
            DriveConflictChoice.KEEP_LOCAL,
            syncAfter = true,
        )
        val res1 = devA.driveDb.resolutions().forRecord(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-crash-bridge").single()
        var compactions = 0
        var editNum = 0
        var lastCompacted = false
        while (editNum < 70 || !lastCompacted) {
            editNum++
            devA.liseurDb.annotationDao().upsert(
                ann0.copy(note = "f$editNum", updatedAt = (baseTime + (10 + editNum) * 1_000L) * 1_000L),
            )
            val r = devA.changeEngine.sync()
            assertEquals(SyncOutcome.Success, r.outcome)
            lastCompacted = r.compacted
            if (r.compacted) compactions++
        }
        val finalNote = "f$editNum"
        assertTrue("Expected >= 3 compactions, got $compactions", compactions >= 3)

        // C syncs compacted snapshot (committing finalNote + acked_fingerprint to liseur.db in Tx 1 AND drive_sync.db in Tx 2)...
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        assertEquals(finalNote, devC.liseurDb.annotationDao().byId("ann-crash-bridge")!!.note)

        // ...then roll back ONLY C's drive_sync.db to simulate an EXACT crash after Tx 1 (liseur.db)
        // and BEFORE Tx 2 (drive_sync.db): {fA, fB} is still OPEN, record_state is still fB,
        // drive_cursor and start_page_token have NOT advanced!
        devC.driveDb.withTransaction {
            devC.driveDb.recordStates().upsert(recordStateBefore)
            devC.driveDb.conflicts().upsert(conflictBefore)
            devC.driveDb.openHelper.writableDatabase.execSQL(
                "DELETE FROM drive_resolution WHERE permission_id = ? AND domain = ? AND record_key = ?",
                arrayOf<Any>(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-crash-bridge"),
            )
            for (c in cursorsBefore) {
                devC.driveDb.cursors().upsert(c)
            }
            devC.driveDb.accounts().updateStartPageToken(PERM_ID, startTokenBefore)
        }
        assertEquals(DriveConflict.STATE_OPEN, devC.driveDb.conflicts().get(PERM_ID, conflictBefore.conflictId)!!.state)
        assertEquals(
            preSnapshotCursorSeq,
            devC.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_ANNOTATION)!!.appliedSeq,
        )

        if (withPostCrashLocalEdit) {
            // Variant B: reader edits the annotation locally on C after crash and before replay sync!
            devC.liseurDb.annotationDao().upsert(
                devC.liseurDb.annotationDao().byId("ann-crash-bridge")!!.copy(
                    note = "Post-crash local edit on C",
                    updatedAt = (baseTime + 500_000L) * 1_000L,
                ),
            )
        }

        // Restart sync on C: replays A's compacted snapshot!
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)

        // Assert in BOTH variants:
        // 1. Step 0's resRefs-bridge settlement closed {fA, fB} as RESOLVED with resolution_fp = res1.fp!
        assertTrue(devC.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        val settled = devC.driveDb.conflicts().get(PERM_ID, conflictBefore.conflictId)!!
        assertEquals(DriveConflict.STATE_RESOLVED, settled.state)
        assertEquals(res1.resolutionFp, settled.resolutionFp)

        // 2. Cursor advanced AFTER Tx 2 committed!
        val afterCursorSeq = devC.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_ANNOTATION)!!.appliedSeq
        assertTrue("Expected cursor to advance past $preSnapshotCursorSeq, got $afterCursorSeq", afterCursorSeq > preSnapshotCursorSeq)

        if (withPostCrashLocalEdit) {
            // 3b. Post-crash local edit was preserved and published with res1.fp in resRefs so A converges to it!
            assertEquals("Post-crash local edit on C", devC.liseurDb.annotationDao().byId("ann-crash-bridge")!!.note)
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
            assertEquals("Post-crash local edit on C", devA.liseurDb.annotationDao().byId("ann-crash-bridge")!!.note)
            assertTrue(devA.driveDb.conflicts().openForAccount(PERM_ID).isEmpty())
        } else {
            // 3a. Unedited Agreement replay kept finalNote cleanly!
            assertEquals(finalNote, devC.liseurDb.annotationDao().byId("ann-crash-bridge")!!.note)
        }
    }

    @Test
    fun `clean reinstall from compacted snapshots alone reconstructs all 12 domains and EPUB availability identically`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 4)
        devA.connect()
        val baseTime = 1_745_500_000_000L

        // 1. Catalog + EPUB upload + archived second book
        val book1 = seedBook(
            devA,
            "hobbit.epub",
            "9780547928227",
            "The Hobbit",
            "J.R.R. Tolkien",
            fileSeriesName = "Middle-earth",
            fileSeriesIndex = 1.0,
            content = "EPUB-CONTENT-THE-HOBBIT-VERIFIED-BYTES",
        )
        val book2 = seedBook(
            devA,
            "silmarillion.epub",
            "9780618391110",
            "The Silmarillion",
            "J.R.R. Tolkien",
            content = "EPUB-CONTENT-SILMARILLION",
        )
        devA.liseurDb.bookDao().setArchived(book2.url, baseTime + 500L)
        // Seed explicit workAlias merge & reject records so all 12 domains are compacted and verified.
        val mergedFromShaKey = "sha:${"1".repeat(64)}"
        val rejectedShaKey = "sha:${"2".repeat(64)}"
        assertEquals(
            "isbn:9780547928227",
            devA.driveSync.workIdentity.confirmMerge(
                permissionId = PERM_ID,
                bookUrl = book1.url,
                keyA = "isbn:9780547928227",
                keyB = mergedFromShaKey,
                hlc = devA.changeEngine.nextHlc(PERM_ID),
            ),
        )
        devA.driveSync.workIdentity.rejectMerge(
            permissionId = PERM_ID,
            bookUrl = book2.url,
            keyA = "isbn:9780618391110",
            keyB = rejectedShaKey,
            hlc = devA.changeEngine.nextHlc(PERM_ID),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // Upload book1's EPUB to Liseur/ folder.
        val queued = devA.driveSync.epubTransfers.queueSelectedBooks(PERM_ID, setOf(book1.url))
        assertEquals(1, queued)
        val uploadSummary = devA.driveSync.epubTransfers.uploadPending()
        assertEquals(1, uploadSummary.uploadedCount)

        // 2. Reading position + furthest position peak
        val posLocator = """{"href":"ch04.xhtml","locations":{"progression":0.5,"totalProgression":0.425},"type":"application/xhtml+xml"}"""
        val peakLocator = """{"href":"ch08.xhtml","locations":{"progression":0.2,"totalProgression":0.78},"type":"application/xhtml+xml"}"""
        devA.liseurDb.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = book1.url,
                locatorJson = posLocator,
                totalProgression = 0.425,
                updatedAt = baseTime + 2_000L,
                readAt = baseTime + 2_000L,
                status = ReadingStatus.READING.wireName,
                localRevision = 3L,
                positionRevision = 3L,
                statusRevision = 1L,
                peakProgression = 0.78,
                peakLocator = peakLocator,
                peakRevision = 2L,
                peakAt = baseTime + 1_500L,
                peakEdition = devA.liseurDb.workIdentityDao().fingerprint(book1.url)!!.sha256,
            ),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // 3. Annotations (live highlight + note + bookmark, PLUS a deleted annotation tombstone)
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-hl", bookId = book1.url, kind = AnnotationKind.HIGHLIGHT.name,
                locatorJson = """{"href":"ch01.xhtml"}""", text = "In a hole", note = "Opening",
                tint = "YELLOW", chapter = "Chapter 1", totalProgression = 0.02,
                createdAt = baseTime + 100L, noteCreatedAt = baseTime + 101L,
                noteUpdatedAt = (baseTime + 102L) * 1_000L, updatedAt = (baseTime + 102L) * 1_000L,
            ),
        )
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-bn", bookId = book1.url, kind = AnnotationKind.BOOK_NOTE.name,
                locatorJson = "", text = null, note = "Whole-book reflection",
                tint = null, chapter = null, totalProgression = null,
                createdAt = baseTime + 110L, noteCreatedAt = baseTime + 110L,
                noteUpdatedAt = (baseTime + 110L) * 1_000L, updatedAt = (baseTime + 110L) * 1_000L,
            ),
        )
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-del", bookId = book1.url, kind = AnnotationKind.BOOKMARK.name,
                locatorJson = """{"href":"ch02.xhtml"}""", text = null, note = null,
                createdAt = baseTime + 120L, updatedAt = (baseTime + 120L) * 1_000L,
            ),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        devA.liseurDb.annotationDao().deleteById("ann-del")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // 4. Evaluations (rating + review)
        devA.liseurDb.bookEvaluationDao().edit(book1.url, PersonalEvaluation.RATING, "10", "The Hobbit", "J.R.R. Tolkien", baseTime + 300L)
        devA.liseurDb.bookEvaluationDao().edit(book1.url, PersonalEvaluation.REVIEW, "Timeless adventure", "The Hobbit", "J.R.R. Tolkien", baseTime + 301L)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // 5. Metadata overrides + Custom cover + Series override
        val metaRepoA = BookMetadataOverrideRepository(
            bookDao = devA.liseurDb.bookDao(),
            overrideDao = devA.liseurDb.bookMetadataOverrideDao(),
            coversDir = devA.coversDir,
            inTransaction = { work -> devA.liseurDb.withTransaction { work() } },
        )
        metaRepoA.applyTextEdits(
            book1.url,
            title = FieldEdit.Set("The Hobbit (Deluxe)"),
            author = FieldEdit.Set("Tolkien"),
            description = FieldEdit.Set("There and back again"),
        )
        assertNotNull(metaRepoA.coverFromBytes(book1.url, "CUSTOM-COVER-WEBP-BYTES-HOBBIT".toByteArray()))
        val expectedCoverSha = devA.liseurDb.bookDao().getByUrl(book1.url)!!.userCoverSha256
        assertNotNull(expectedCoverSha)
        devA.liseurDb.bookDao().setSeriesOverride(book1.url, "Tolkien Legendarium", 1.5, baseTime + 400L)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // 6. Vocabulary word + legacy baseline + occurrence + reviews + derived schedule
        val vocabRepoA = VocabularyRepository(devA.liseurDb)
        val wordIdA = devA.liseurDb.vocabularyDao().insertWord(
            VocabularyWord(
                word = "dragon",
                normalized = "dragon",
                language = "en",
                definition = "fire-breathing wyrm",
                translationLanguage = "fr",
                notes = "Smaug",
                addedAt = baseTime,
                dueAt = baseTime + 86_400_000L,
                reviewedAt = baseTime + 60_000L,
                streak = 1,
                intervalMinutes = 1440,
                repetitions = 1,
                definitionEdited = true,
                definitionUpdatedAt = baseTime + 5L,
                notesUpdatedAt = baseTime + 6L,
                baselineDueAt = baseTime + 86_400_000L,
                baselineReviewedAt = baseTime + 60_000L,
                baselineStreak = 1,
                baselineIntervalMinutes = 1440,
                baselineRepetitions = 1,
            ),
        )
        devA.liseurDb.vocabularyDao().insertOccurrence(
            VocabularyOccurrence(
                wordId = wordIdA,
                bookUrl = book1.url,
                bookTitle = "The Hobbit",
                locationKey = "ch12#p3",
                locator = """{"href":"ch12.xhtml"}""",
                sentence = "There he lay, a vast red-golden dragon.",
                lastSeenAt = baseTime + 500L,
                encounters = 3,
            ),
        )
        vocabRepoA.review(
            card = devA.liseurDb.vocabularyDao().word(wordIdA)!!,
            rating = Recall.GOOD,
            newLimit = 0,
            reviewLimit = 0,
            now = baseTime + 90_000_000L,
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // 7. Reading sessions (multiple sessions so cross-device stats can be compared identically)
        devA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = book1.url,
                startedAt = baseTime + 10_000L,
                endedAt = baseTime + 1_810_000L,
                lastCheckpointAt = baseTime + 1_810_000L,
                durationMs = 1_800_000L,
                startProgression = 0.0,
                endProgression = 0.25,
                idleMs = 15_000L,
            ),
        )
        devA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = book1.url,
                startedAt = baseTime + 3_600_000L,
                endedAt = baseTime + 4_500_000L,
                lastCheckpointAt = baseTime + 4_500_000L,
                durationMs = 900_000L,
                startProgression = 0.25,
                endProgression = 0.425,
                idleMs = 0L,
            ),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // Trigger additional deltas so >= 2 compactions run and ALL earlier deltas are pruned,
        // leaving compacted snapshots as the SOLE source of all early domains!
        for (i in 1..6) {
            devA.liseurDb.annotationDao().upsert(
                devA.liseurDb.annotationDao().byId("ann-bn")!!.copy(
                    note = "Whole-book reflection v$i",
                    updatedAt = (baseTime + 2_000L + i * 100L) * 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        }

        val manifestA = server.decodeManifestFor("dev-a")!!
        assertNotNull(manifestA.snapshot)
        assertNotNull(manifestA.previousSnapshot)
        // Confirm early deltas (seq = 1..previousSnapshot.throughSeq) were pruned from Drive!
        val survivingDeltaSeqs = server.liveAppDataFiles()
            .filter { it.appProperties["kind"] == DriveFile.KIND_DELTA && it.appProperties["deviceId"] == "dev-a" }
            .mapNotNull { it.appProperties["seq"]?.toLongOrNull() }
        assertFalse("Seq 1 must be pruned before clean reinstall", 1L in survivingDeltaSeqs)

        // Confirm all 12 supported domains are represented in A's compacted snapshot!
        val snapshotDomains = manifestA.snapshot!!.parts
            .flatMap { ref ->
                DriveWireCodec.decodeSnapshotPart(server.rawBytes(ref.fileId)!!.toString(Charsets.UTF_8)).records
            }
            .map { it.d }
            .toSet()
        assertEquals(DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS.keys, snapshotDomains)

        // Capture A's expected stats & vocab schedule before fresh reinstall on Device D.
        val wordA = devA.liseurDb.vocabularyDao().findWord("dragon", "en")!!
        val statsA = computeStats(devA.liseurDb, baseTime + 10_000_000L)

        // --- FRESH PLAY INSTALLATION (Device D) connects from scratch! ---
        val devD = createDevice(deviceId = "dev-d-fresh")
        devD.connect()
        assertEquals(SyncOutcome.Success, devD.changeEngine.sync().outcome)

        val stub1 = "drive:isbn:9780547928227"
        val stub2 = "drive:isbn:9780618391110"

        // Verify workAlias merge & reject survived compaction and restored on Device D!
        assertEquals(
            "isbn:9780547928227",
            devD.driveSync.workIdentity.canonicalWorkKey(PERM_ID, mergedFromShaKey),
        )
        assertTrue(
            "Rejected workAlias pair must survive compaction and restore on Device D",
            devD.driveSync.workIdentity.isRejectedPair(PERM_ID, "isbn:9780618391110", rejectedShaKey),
        )

        // Verify catalog + EPUB availability + archived state
        val book1D = devD.liseurDb.bookDao().getByUrl(stub1)
        val book2D = devD.liseurDb.bookDao().getByUrl(stub2)
        assertNotNull(book1D)
        assertNotNull(book2D)
        assertEquals(DownloadState.REMOTE, book1D!!.downloadState)
        assertNull(book1D.remoteUuid)
        assertNotNull("Uploaded EPUB must be downloadable on reinstalled device", DriveCatalogSync.fileIdFromHref(book1D.downloadHref))
        assertEquals(baseTime + 500L, book2D!!.archivedAt)

        // Verify metadata overrides & custom cover on REMOTE catalog entry!
        assertEquals("The Hobbit (Deluxe)", book1D.title)
        assertEquals("Tolkien", book1D.author)
        assertEquals("There and back again", book1D.userDescription)
        assertTrue(book1D.coverOverridden)
        assertEquals(expectedCoverSha, book1D.userCoverSha256)
        assertNotNull(book1D.userCoverPath)
        assertTrue(File(book1D.userCoverPath!!).isFile)

        // Verify series override
        assertTrue(book1D.seriesOverridden)
        assertEquals("Tolkien Legendarium", book1D.userSeriesName)
        assertEquals(1.5, book1D.userSeriesIndex!!, 0.0001)

        // Verify reading position + furthest peak
        val progressD = devD.liseurDb.readingProgressDao().get(stub1)!!
        assertEquals(0.425, progressD.totalProgression!!, 0.0001)
        assertEquals(ReadingStatus.READING.wireName, progressD.status)
        val peaksD = devD.liseurDb.furthestPositionDao().forWork(DriveLibrarySync.accountPeerId(PERM_ID), "isbn:9780547928227")
        assertTrue(peaksD.any { kotlin.math.abs(it.progression - 0.78) < 0.0001 })

        // Verify annotations (live preserved, deleted absent)
        val hlD = devD.liseurDb.annotationDao().byId("ann-hl")
        val bnD = devD.liseurDb.annotationDao().byId("ann-bn")
        assertNotNull(hlD)
        assertEquals("Opening", hlD!!.note)
        assertEquals("YELLOW", hlD.tint)
        assertNotNull(bnD)
        assertEquals("Whole-book reflection v6", bnD!!.note)
        assertNull("Deleted annotation tombstone must keep ann-del absent", devD.liseurDb.annotationDao().byId("ann-del"))

        // Verify evaluations
        val ratingD = devD.liseurDb.bookEvaluationDao().get(stub1, PersonalEvaluation.RATING)
        val reviewD = devD.liseurDb.bookEvaluationDao().get(stub1, PersonalEvaluation.REVIEW)
        assertEquals("10", ratingD?.value)
        assertEquals("Timeless adventure", reviewD?.value)

        // Verify vocabulary + occurrence + derived SRS schedule
        val wordD = devD.liseurDb.vocabularyDao().findWord("dragon", "en")!!
        assertEquals(wordA.definition, wordD.definition)
        assertEquals(wordA.notes, wordD.notes)
        assertEquals(wordA.dueAt, wordD.dueAt)
        assertEquals(wordA.streak, wordD.streak)
        assertEquals(wordA.intervalMinutes, wordD.intervalMinutes)
        assertEquals(wordA.repetitions, wordD.repetitions)
        val occsD = devD.liseurDb.vocabularyDao().occurrencesList(wordD.id)
        assertEquals(1, occsD.size)
        assertEquals("There he lay, a vast red-golden dragon.", occsD.single().sentence)

        // Verify reading sessions & identical local cross-device statistics!
        val sessionsD = devD.liseurDb.readingSessionDao().allOnce()
        assertEquals(2, sessionsD.size)
        assertTrue(sessionsD.all { it.originDeviceId == "dev-a" && it.importedAt == null })
        val statsD = computeStats(devD.liseurDb, baseTime + 10_000_000L)
        assertEquals(statsA.totalMs, statsD.totalMs)
        assertEquals(2_700_000L, statsD.totalMs)

        // Verify on-demand EPUB download succeeds with SHA-256 verification!
        val downloadOutcome = devD.driveSync.epubTransfers.downloadBook(stub1)
        assertTrue("Expected Downloaded outcome, got $downloadOutcome", downloadOutcome is DriveEpubDownloadOutcome.Downloaded)
        val downloadedUri = (downloadOutcome as DriveEpubDownloadOutcome.Downloaded).localUri
        assertEquals("EPUB-CONTENT-THE-HOBBIT-VERIFIED-BYTES", File(java.net.URI(downloadedUri)).readText())
    }

    @Test
    fun `reordered and duplicate batches apply contiguously and idempotently without double counting`() = runTest {
        val devA = createDevice(deviceId = "dev-a")
        val devB = createDevice(deviceId = "dev-b")
        devA.connect()
        devB.connect()

        val baseTime = 1_745_600_000_000L
        val bookA = seedBook(devA, "order.epub", "9780141439518", "Pride and Prejudice", "Jane Austen")
        // Seq 1: catalog + session 1 + vocab word + review 1
        devA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = bookA.url,
                startedAt = baseTime,
                endedAt = baseTime + 600_000L,
                lastCheckpointAt = baseTime + 600_000L,
                durationMs = 600_000L,
                startProgression = 0.0,
                endProgression = 0.1,
                idleMs = 0L,
            ),
        )
        val vocabRepoA = VocabularyRepository(devA.liseurDb)
        val wordId = vocabRepoA.collect(VocabularyLookup("ardent", "en", null), "passionate", "en", now = baseTime)!!
        vocabRepoA.review(devA.liseurDb.vocabularyDao().word(wordId)!!, Recall.GOOD, 0, 0, now = baseTime + 1_000L)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome) // seq = 1

        // Seq 2: annotation + session 2
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-seq2", bookId = bookA.url, kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch01.xhtml"}""", text = "Truth universally acknowledged",
                note = "From seq 2", createdAt = baseTime + 2_000L, updatedAt = (baseTime + 2_000L) * 1_000L,
            ),
        )
        devA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = bookA.url,
                startedAt = baseTime + 700_000L,
                endedAt = baseTime + 1_300_000L,
                lastCheckpointAt = baseTime + 1_300_000L,
                durationMs = 600_000L,
                startProgression = 0.1,
                endProgression = 0.2,
                idleMs = 0L,
            ),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome) // seq = 2

        // Reordered delivery: hide seq 1 so B ONLY sees seq 2 (gap!) -> B MUST wait at cursor 0!
        server.hideDeltaSeq("dev-a", 1L)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        assertNull(devB.liseurDb.annotationDao().byId("ann-seq2"))
        assertEquals(0L, devB.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_ANNOTATION)?.appliedSeq ?: 0L)

        // Unhide seq 1 -> B applies seq 1 THEN seq 2 in contiguous order!
        server.unhideAll()
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync(forceManifestRescan = true).outcome)
        assertEquals("From seq 2", devB.liseurDb.annotationDao().byId("ann-seq2")!!.note)
        assertEquals(2L, devB.driveDb.cursors().get(PERM_ID, "dev-a", DriveWireCodec.DOMAIN_ANNOTATION)!!.appliedSeq)
        assertEquals(2, devB.liseurDb.readingSessionDao().allOnce().size)
        val wordB = devB.liseurDb.vocabularyDao().findWord("ardent", "en")!!
        assertEquals(1, wordB.repetitions)

        // Duplicate delivery: reset B's cursors & startPageToken and replay seq 1 + seq 2 -> strictly idempotent!
        devB.driveDb.withTransaction {
            for (c in devB.driveDb.cursors().forDevice(PERM_ID, "dev-a")) {
                devB.driveDb.cursors().upsert(c.copy(appliedSeq = 0L))
            }
            devB.driveDb.accounts().updateStartPageToken(PERM_ID, null)
        }
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        assertEquals(2, devB.liseurDb.readingSessionDao().allOnce().size)
        assertEquals(1_200_000L, devB.liseurDb.readingSessionDao().allOnce().sumOf { it.durationMs })
        val wordBAfterDup = devB.liseurDb.vocabularyDao().findWord("ardent", "en")!!
        assertEquals(wordB.repetitions, wordBAfterDup.repetitions)
        assertEquals(wordB.dueAt, wordBAfterDup.dueAt)
    }

    @Test
    fun `clock skew preserves intentional backward move and furthest-position reset drops stale peaks`() = runTest {
        val clockA = AtomicLong(1_745_700_000_000L)
        // Device B's wall clock is skewed +1 hour ahead of A!
        val clockB = AtomicLong(1_745_703_600_000L)
        val devA = createDevice(deviceId = "dev-a", clock = { clockA.getAndIncrement() })
        val devB = createDevice(deviceId = "dev-b", clock = { clockB.getAndIncrement() })
        devA.connect()
        devB.connect()

        val bookA = seedBook(devA, "clock.epub", "9780060850524", "Brave New World", "Aldous Huxley")
        val sha = devA.liseurDb.workIdentityDao().fingerprint(bookA.url)!!.sha256

        // 1. A reads to 50% with furthest peak at 80%; B pulls and agrees at 50% + sees 80% peak.
        devA.liseurDb.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = bookA.url,
                locatorJson = """{"href":"ch05.xhtml","locations":{"totalProgression":0.5}}""",
                totalProgression = 0.5,
                updatedAt = clockA.get(),
                readAt = clockA.get(),
                status = ReadingStatus.READING.wireName,
                localRevision = 1L,
                positionRevision = 1L,
                statusRevision = 1L,
                peakProgression = 0.8,
                peakLocator = """{"href":"ch08.xhtml","locations":{"totalProgression":0.8}}""",
                peakRevision = 5L,
                peakAt = clockA.get(),
                peakEdition = sha,
            ),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)

        val stubB = "drive:isbn:9780060850524"
        assertEquals(0.5, devB.liseurDb.readingProgressDao().get(stubB)!!.totalProgression!!, 0.0001)
        assertEquals(
            1,
            devB.liseurDb.furthestPositionDao().forWork(DriveLibrarySync.accountPeerId(PERM_ID), "isbn:9780060850524").size,
        )

        // 2. Device A intentionally moves BACKWARD from agreed 50% -> 20% while B (with +1h skewed clock) is untouched.
        devA.liseurDb.readingProgressDao().upsert(
            devA.liseurDb.readingProgressDao().get(bookA.url)!!.copy(
                locatorJson = """{"href":"ch02.xhtml","locations":{"totalProgression":0.2}}""",
                totalProgression = 0.2,
                updatedAt = clockA.addAndGet(5_000L),
                readAt = clockA.get(),
                localRevision = 2L,
                positionRevision = 2L,
            ),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)

        // B MUST pull the intentional backward move to 20% despite B's skewed future clock!
        assertEquals(0.2, devB.liseurDb.readingProgressDao().get(stubB)!!.totalProgression!!, 0.0001)

        // 3. Device A triggers explicit furthest-position reset -> deletes peaks <= floor on B!
        val previewA = devA.driveSync.positionSync.previewKnownBook(bookA.url)
        val resetDest = requireNotNull(
            (previewA as? com.chmouel.liseur.data.remote.PreviewOutcome.Ready)?.preview?.furthest,
        ) { "Expected furthest destination in previewA=$previewA" }
        assertEquals(
            ResolveOutcome.Done,
            devA.driveSync.positionSync.forgetFurthestPosition(bookA.url, resetDest, uptoSeq = 5L),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        val peersAfterReset = devB.liseurDb.furthestPositionDao()
            .forWork(DriveLibrarySync.accountPeerId(PERM_ID), "isbn:9780060850524")
        val resetFloorOnB = devB.driveSync.positionSync.getPersistedFloor(PERM_ID, "isbn:9780060850524", "dev-a")
        assertTrue(
            "Furthest reset must delete peaks <= floor on B and keep only the post-reset peak",
            peersAfterReset.all { it.seq > resetFloorOnB } && peersAfterReset.size == 1,
        )

        // Late-arriving stale peak from A with seq <= floor MUST be dropped on B;
        // a new peak with seq = floor + 1 MUST survive.
        val floorOnB = devB.driveSync.positionSync.getPersistedFloor(PERM_ID, "isbn:9780060850524", "dev-a")
        assertTrue("Reset must persist a non-zero floor for dev-a", floorOnB > 0L)

        val stalePeak = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_FURTHEST,
            key = DrivePositionSync.peakRecordKey("isbn:9780060850524", sha, "dev-a"),
            hlc = "0017457000900-0001-dev-a",
            bodyMap = DriveWireCodec.encodeFurthestBody(
                DriveFurthestBody.Peak(
                    workKey = "isbn:9780060850524",
                    editionSha = sha,
                    deviceId = "dev-a",
                    seq = floorOnB,
                    progression = 0.85,
                    locatorJson = """{"href":"ch09.xhtml"}""",
                    coverage = null,
                    peakAt = clockA.get(),
                ),
            ),
        )
        devB.driveSync.positionSync.applyRemoteFurthest(PERM_ID, "dev-b", stalePeak, "dev-a", 99L)
        assertEquals(
            "Stale peak with seq <= floor must be ignored",
            peersAfterReset,
            devB.liseurDb.furthestPositionDao().forWork(DriveLibrarySync.accountPeerId(PERM_ID), "isbn:9780060850524"),
        )

        val freshPeak = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_FURTHEST,
            key = DrivePositionSync.peakRecordKey("isbn:9780060850524", sha, "dev-a"),
            hlc = "0017457001000-0001-dev-a",
            bodyMap = DriveWireCodec.encodeFurthestBody(
                DriveFurthestBody.Peak(
                    workKey = "isbn:9780060850524",
                    editionSha = sha,
                    deviceId = "dev-a",
                    seq = floorOnB + 1L,
                    progression = 0.35,
                    locatorJson = """{"href":"ch03.xhtml"}""",
                    coverage = null,
                    peakAt = clockA.get(),
                ),
            ),
        )
        devB.driveSync.positionSync.applyRemoteFurthest(PERM_ID, "dev-b", freshPeak, "dev-a", 100L)
        val afterFresh = devB.liseurDb.furthestPositionDao().forWork(DriveLibrarySync.accountPeerId(PERM_ID), "isbn:9780060850524")
        assertEquals(1, afterFresh.size)
        assertEquals(0.35, afterFresh.single().progression, 0.0001)
    }

    @Test
    fun `unsupported schemaVersion pauses remote device stream with upgrade notice without downloading deltas, while unsupported domain pauses only that domain`() = runTest {
        val devB = createDevice(deviceId = "dev-b")
        devB.connect()

        // 1. Remote device X advertises unsupported envelope `schemaVersion = 99`.
        server.injectRemoteManifest(
            DriveManifest(
                schemaVersion = 99,
                deviceId = "dev-future-stream",
                deviceLabel = "Pixel 12 Ultra",
                appVersionCode = 999,
                updatedHlc = "0017458000000-0001-dev-future-stream",
                domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
            ),
        )
        val futureDeltaId = server.injectRemoteDelta(
            deviceId = "dev-future-stream",
            seq = 1L,
            createdHlc = "0017458000001-0001-dev-future-stream",
            records = emptyList(),
            schemaVersion = 99,
        )

        // 2. Remote device Y advertises supported envelope `schemaVersion = 1`, supported `catalog = 1`,
        //    and unsupported `annotation = 2`.
        val catalogBody = DriveCatalogBody(
            workKey = "isbn:9780547928227",
            title = "The Hobbit",
            author = "J.R.R. Tolkien",
            identifiers = listOf(DriveCatalogIdentifier("isbn", "9780547928227")),
            editions = emptyList(),
            fileSeriesName = null,
            fileSeriesIndex = null,
            addedAt = 1_745_800_000_000L,
            archivedAt = null,
            updatedAt = 1_745_800_000_000L,
        )
        val catalogRec = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_CATALOG,
            key = catalogBody.workKey,
            hlc = "0017458000010-0001-dev-future-domain",
            bodyMap = DriveWireCodec.encodeCatalogBody(catalogBody),
        )
        val annBody = DriveAnnotationBody(
            id = "ann-future-v2", workKey = catalogBody.workKey, editionSha = null,
            kind = AnnotationKind.NOTE.name, locatorJson = """{"href":"ch01.xhtml"}""",
            text = "Future", note = "Requires v2", tint = null, chapter = null,
            totalProgression = null, createdAt = 1_745_800_000_000L,
            noteCreatedAt = null, noteUpdatedAt = null, updatedAt = 1_745_800_000_000_000L,
        )
        val annRec = DriveWireCodec.putRecordWithFp(
            domain = DriveWireCodec.DOMAIN_ANNOTATION,
            key = annBody.id,
            hlc = "0017458000011-0001-dev-future-domain",
            fp = DriveWireCodec.annotationContentFp(annBody),
            bodyMap = DriveWireCodec.encodeAnnotationBody(annBody),
        )
        server.injectRemoteManifest(
            DriveManifest(
                schemaVersion = 1,
                deviceId = "dev-future-domain",
                deviceLabel = "Future Tablet",
                appVersionCode = 500,
                updatedHlc = "0017458000012-0001-dev-future-domain",
                domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS + (DriveWireCodec.DOMAIN_ANNOTATION to 2),
            ),
        )
        server.injectRemoteDelta(
            deviceId = "dev-future-domain",
            seq = 1L,
            createdHlc = "0017458000012-0001-dev-future-domain",
            records = listOf(catalogRec, annRec),
            domains = mapOf(DriveWireCodec.DOMAIN_CATALOG to 1, DriveWireCodec.DOMAIN_ANNOTATION to 2),
        )

        val run = devB.changeEngine.sync()
        assertEquals(SyncOutcome.Success, run.outcome)
        assertTrue("Expected pausedBySchemaVersion = true", run.pausedBySchemaVersion)

        // Device X's stream cursor is paused at 99 AND its delta was NEVER downloaded!
        val streamCursorX = devB.driveDb.cursors().get(PERM_ID, "dev-future-stream", DriveWireCodec.STREAM_PAUSE_DOMAIN)
        assertEquals(99, streamCursorX?.pausedSchemaVersion)
        assertFalse("Unsupported schemaVersion stream delta must NOT be downloaded", futureDeltaId in server.downloadedFileIds)

        // Device Y's `catalog` imported cleanly while ONLY its `annotation` domain stayed paused at version 2!
        assertNotNull(devB.liseurDb.bookDao().getByUrl("drive:isbn:9780547928227"))
        assertNull(devB.liseurDb.annotationDao().byId("ann-future-v2"))
        assertEquals(1L, devB.driveDb.cursors().get(PERM_ID, "dev-future-domain", DriveWireCodec.DOMAIN_CATALOG)!!.appliedSeq)
        val annCursorY = devB.driveDb.cursors().get(PERM_ID, "dev-future-domain", DriveWireCodec.DOMAIN_ANNOTATION)!!
        assertEquals(2, annCursorY.pausedSchemaVersion)
        assertEquals(0L, annCursorY.appliedSeq)

        // Upgrade notice flow exposes both paused cursors cleanly for Settings UI.
        val pausedCursors = devB.driveDb.cursors().observePausedForAccount(PERM_ID).first()
        assertTrue(pausedCursors.any { it.deviceId == "dev-future-stream" && it.domain == DriveWireCodec.STREAM_PAUSE_DOMAIN })
        assertTrue(pausedCursors.any { it.deviceId == "dev-future-domain" && it.domain == DriveWireCodec.DOMAIN_ANNOTATION })
    }

    @Test
    fun `pruned delta and pruned snapshot part HTTP 404 trigger manifest refresh and compacted snapshot fallback`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 3)
        val devB = createDevice(deviceId = "dev-b")
        devA.connect()
        devB.connect()

        val bookA = seedBook(devA, "fallback.epub", "9780441172719", "Dune", "Frank Herbert")
        val ann = BookAnnotation(
            id = "ann-404", bookId = bookA.url, kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""", text = "Spice", note = "v1",
            createdAt = 1_000L, updatedAt = 1_000_000L,
        )
        devA.liseurDb.annotationDao().upsert(ann)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome) // seq 1
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome) // B at seq 1

        // Capture A's stale pre-compaction manifest (snapshot = null) and seq 2 metadata before A compacts.
        val manifestFileIdA = devA.driveDb.accounts().get(PERM_ID)!!.manifestFileId!!
        val stalePreCompactionManifestBytes = server.rawBytes(manifestFileIdA)!!.copyOf()
        devA.liseurDb.annotationDao().upsert(ann.copy(note = "v2", updatedAt = 2_000_000L))
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome) // seq 2
        val seq2File = server.liveAppDataFiles().first {
            it.appProperties["kind"] == DriveFile.KIND_DELTA &&
                it.appProperties["deviceId"] == "dev-a" &&
                it.appProperties["seq"] == "2"
        }

        // A performs edits v3..v9 -> 3 compactions (at seq 3, 6, 9), pruning seq 1..6 and S_1!
        var staleS1ManifestBytes: ByteArray? = null
        for (i in 3..9) {
            devA.liseurDb.annotationDao().upsert(ann.copy(note = "v$i", updatedAt = i * 1_000_000L))
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
            if (i == 3) {
                staleS1ManifestBytes = server.rawBytes(manifestFileIdA)!!.copyOf()
            }
        }
        assertNotNull(staleS1ManifestBytes)
        assertNull("seq 2 should be pruned from Fake Drive", server.rawBytes(seq2File.id))

        // 1. Pruned delta 404 test: serve A's stale pre-compaction manifest ONCE while B holds stale seq 2,
        //    so B attempts to download pruned seq 2 -> HTTP 404 -> refreshes manifest -> ingests S_3 -> v9!
        devB.driveDb.files().upsert(
            DriveFile(
                permissionId = PERM_ID,
                fileId = seq2File.id,
                name = seq2File.name,
                kind = DriveFile.KIND_DELTA,
                deviceId = "dev-a",
                seq = 2L,
            ),
        )
        server.advanceStartTokenForNoChanges(devB)
        server.serveStaleBytesOnce(manifestFileIdA, stalePreCompactionManifestBytes)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        assertEquals("v9", devB.liseurDb.annotationDao().byId("ann-404")!!.note)

        // 2. Pruned snapshot part 404 test: fresh Device C gets stale S_1 manifest ONCE (whose parts were pruned
        //    during compaction 3) -> HTTP 404 on S_1 part -> refreshes manifest -> ingests S_3 -> v9!
        val devC = createDevice(deviceId = "dev-c-snap404")
        devC.connect()
        server.serveStaleBytesOnce(manifestFileIdA, staleS1ManifestBytes!!)
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        assertEquals("v9", devC.liseurDb.annotationDao().byId("ann-404")!!.note)
    }

    @Test
    fun `competing offline resolution vs subsequent local edit preserves edit without silent overwrite`() = runTest {
        val devA = createDevice(deviceId = "dev-a")
        val devB = createDevice(deviceId = "dev-b")
        devA.connect()
        devB.connect()

        val bookA = seedBook(devA, "compete.epub", "9780441172719", "Dune", "Frank Herbert")
        val ann0 = BookAnnotation(
            id = "ann-compete", bookId = bookA.url, kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""", text = "Litany", note = "f0",
            createdAt = 1_000L, updatedAt = 1_000_000L,
        )
        devA.liseurDb.annotationDao().upsert(ann0)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)

        // Create {fA, fB} conflict on both A and B.
        devA.liseurDb.annotationDao().upsert(ann0.copy(note = "fA", updatedAt = 2_000_000L))
        devB.liseurDb.annotationDao().upsert(
            devB.liseurDb.annotationDao().byId("ann-compete")!!.copy(note = "fB", updatedAt = 2_100_000L),
        )
        server.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        server.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        server.unhideAll()
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)

        // Offline: A resolves as resA (KEEP_LOCAL = fA) AND THEN makes a subsequent edit fNew!
        devA.driveSync.resolveConflicts(
            devA.driveDb.conflicts().openForAccount(PERM_ID),
            DriveConflictChoice.KEEP_LOCAL,
            syncAfter = false,
        )
        server.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        devA.liseurDb.annotationDao().upsert(ann0.copy(note = "fNew-after-resA", updatedAt = 5_000_000L))
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // B (offline, with a strictly higher HLC) resolves {fA, fB} as resB (DELETE)!
        devB.clockRef.addAndGet(60_000L)
        devB.driveSync.resolveConflicts(
            devB.driveDb.conflicts().openForAccount(PERM_ID),
            DriveConflictChoice.DELETE,
            syncAfter = false,
        )
        server.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        server.unhideAll()

        // A pulls B's higher-HLC resB: A MUST NOT silently overwrite fNew-after-resA!
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals("fNew-after-resA", devA.liseurDb.annotationDao().byId("ann-compete")!!.note)
        val newConflictsA = devA.driveDb.conflicts().openForAccount(PERM_ID)
        assertEquals(1, newConflictsA.size)
        assertEquals(DriveConflict.KIND_DELETE_EDIT, newConflictsA.single().kind)
    }

    @Test
    fun `byte-size compaction threshold triggers compaction and interrupted pruning retries cleanly on next sync`() = runTest {
        val devA = createDevice(
            deviceId = "dev-a",
            compactionDeltaThreshold = 100, // high count threshold so byte threshold triggers first
            compactionBytesThreshold = 1_500L,
        )
        devA.connect()

        val bookA = seedBook(devA, "bytes.epub", "9780441172719", "Dune", "Frank Herbert")
        val ann = BookAnnotation(
            id = "ann-bytes", bookId = bookA.url, kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""", text = "Long excerpt " + "x".repeat(400),
            note = "Initial", createdAt = 1_000L, updatedAt = 1_000_000L,
        )
        devA.liseurDb.annotationDao().upsert(ann)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // Fail the next 2 deleteFile calls during compaction 2 so pruning is interrupted mid-flight!
        var compactions = 0
        var editIdx = 0
        while (compactions < 2 && editIdx < 20) {
            editIdx++
            if (compactions == 1) {
                server.failNextDeletes(2)
            }
            devA.liseurDb.annotationDao().upsert(
                ann.copy(note = "Large edit #$editIdx " + "y".repeat(600), updatedAt = (1_000L + editIdx) * 1_000L),
            )
            val run = devA.changeEngine.sync()
            assertEquals(SyncOutcome.Success, run.outcome)
            if (run.compacted) compactions++
        }
        assertEquals("Expected byte threshold to trigger 2 compactions well below 100 deltas", 2, compactions)

        val prevSnapSeq = server.decodeManifestFor("dev-a")!!.previousSnapshot!!.throughSeq
        // Some deltas <= prevSnapSeq survived because deleteFile failed transiently.
        val unprunedBeforeRetry = server.liveAppDataFiles().count {
            it.appProperties["kind"] == DriveFile.KIND_DELTA &&
                it.appProperties["deviceId"] == "dev-a" &&
                (it.appProperties["seq"]?.toLongOrNull() ?: Long.MAX_VALUE) <= prevSnapSeq
        }
        assertTrue("Expected interrupted prune to leave unpruned deltas before retry", unprunedBeforeRetry > 0)

        // Next sync (even with ZERO new edits!) drains the interrupted prunes cleanly!
        server.failNextDeletes(0)
        val drainRun = devA.changeEngine.sync()
        assertEquals(SyncOutcome.Success, drainRun.outcome)
        assertTrue("Expected interrupted deltas to be pruned on retry", drainRun.prunedDeltaCount >= unprunedBeforeRetry)

        val unprunedAfterRetry = server.liveAppDataFiles().count {
            it.appProperties["kind"] == DriveFile.KIND_DELTA &&
                it.appProperties["deviceId"] == "dev-a" &&
                (it.appProperties["seq"]?.toLongOrNull() ?: Long.MAX_VALUE) <= prevSnapSeq
        }
        assertEquals(0, unprunedAfterRetry)
    }

    @Test
    fun `a delete that saw an occurrence still applies silently when that occurrence arrives through a later snapshot`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 3)
        val devB = createDevice(deviceId = "dev-b")
        devA.connect()
        devB.connect()
        val baseTime = 1_746_100_000_000L
        val bookA = seedBook(devA, "vocab-seq.epub", "9780140449143", "Germinal", "Zola")
        val wordIdA = devA.liseurDb.vocabularyDao().insertWord(
            VocabularyWord(
                word = "mine", normalized = "mine", language = "fr", definition = "mine",
                translationLanguage = "en", notes = "", addedAt = baseTime, dueAt = baseTime,
                definitionUpdatedAt = baseTime, notesUpdatedAt = baseTime,
            ),
        )
        devA.liseurDb.vocabularyDao().insertOccurrence(
            VocabularyOccurrence(
                wordId = wordIdA, bookUrl = bookA.url, bookTitle = "Germinal", locationKey = "ch01#p1",
                locator = """{"href":"ch01.xhtml"}""", sentence = "Au fond de la mine", lastSeenAt = baseTime,
                encounters = 1, sentenceEdited = false, sentenceUpdatedAt = baseTime,
            ),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)

        // B saw the occurrence and deletes the untouched word.
        val wordB = devB.liseurDb.vocabularyDao().findWord("mine", "fr")!!
        VocabularyRepository(devB.liseurDb).deleteWord(wordB.id)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)

        // A, not seeing B's delete, compacts unrelated edits well past the occurrence's seq.
        server.hideOtherDeviceDeltasFor("dev-a")
        for (i in 1..10) {
            devA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-seq-$i", bookId = bookA.url, kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch02.xhtml"}""", text = "t$i", note = "n$i",
                    createdAt = baseTime + i, updatedAt = (baseTime + i) * 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        }
        server.unhideAll()
        assertNotNull(devA.changeEngine.compactor.currentCompactionState(PERM_ID).snapshot)

        // A fresh device reads A's snapshot, then B's delete: the occurrence was observed, so the delete wins.
        val devC = createDevice(deviceId = "dev-c")
        devC.connect()
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        assertNull(devC.liseurDb.vocabularyDao().findWord("mine", "fr"))
    }

    @Test
    fun `converged two-device vocabulary word and occurrence survive repeated compactions on both devices and recover on fresh reinstall`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 5)
        val devB = createDevice(deviceId = "dev-b", compactionDeltaThreshold = 5)
        devA.connect()
        devB.connect()

        val baseTime = 1_746_000_000_000L
        val bookA = seedBook(devA, "vocab-conv.epub", "9780140449136", "Les Misérables", "Victor Hugo")
        val wordIdA = devA.liseurDb.vocabularyDao().insertWord(
            VocabularyWord(
                word = "sérénité",
                normalized = "serenite",
                language = "fr",
                definition = "calm",
                translationLanguage = "en",
                notes = "initial note",
                addedAt = baseTime,
                dueAt = baseTime,
                definitionUpdatedAt = baseTime,
                notesUpdatedAt = baseTime,
            ),
        )
        devA.liseurDb.vocabularyDao().insertOccurrence(
            VocabularyOccurrence(
                wordId = wordIdA,
                bookUrl = bookA.url,
                bookTitle = "Les Misérables",
                locationKey = "ch01#p1",
                locator = """{"href":"ch01.xhtml"}""",
                sentence = "Initial context sentence",
                lastSeenAt = baseTime,
                encounters = 1,
                sentenceEdited = false,
                sentenceUpdatedAt = baseTime,
            ),
        )
        // Also seed a helper annotation on each device to trigger subsequent compactions cleanly.
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-compact-a",
                bookId = bookA.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch01.xhtml"}""",
                text = "A helper",
                note = "a0",
                createdAt = baseTime,
                updatedAt = baseTime * 1_000L,
            ),
        )

        // Converge initial state A -> B.
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        val stubB = "drive:isbn:9780140449136"
        devB.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-compact-b",
                bookId = stubB,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch02.xhtml"}""",
                text = "B helper",
                note = "b0",
                createdAt = baseTime,
                updatedAt = baseTime * 1_000L,
            ),
        )
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // Concurrent field edits on BOTH the vocabulary word AND its occurrence:
        // - Device A edits word `definition` and occurrence `sentence`.
        // - Device B edits word `notes` and occurrence `encounters` / `lastSeenAt`.
        val wordA0 = devA.liseurDb.vocabularyDao().findWord("serenite", "fr")!!
        devA.liseurDb.vocabularyDao().updateWord(
            wordA0.copy(
                definition = "deep peace",
                definitionEdited = true,
                definitionUpdatedAt = baseTime + 10_000L,
            ),
        )
        val occA0 = devA.liseurDb.vocabularyDao().occurrencesList(wordA0.id).single()
        devA.liseurDb.vocabularyDao().deleteOccurrencesForWord(wordA0.id)
        devA.liseurDb.vocabularyDao().insertOccurrence(
            occA0.copy(
                sentence = "A edited context sentence",
                sentenceEdited = true,
                sentenceUpdatedAt = baseTime + 10_000L,
            ),
        )

        val wordB0 = devB.liseurDb.vocabularyDao().findWord("serenite", "fr")!!
        devB.liseurDb.vocabularyDao().updateWord(
            wordB0.copy(
                notes = "B personal note",
                notesUpdatedAt = baseTime + 20_000L,
            ),
        )
        val occB0 = devB.liseurDb.vocabularyDao().occurrencesList(wordB0.id).single()
        devB.liseurDb.vocabularyDao().deleteOccurrencesForWord(wordB0.id)
        devB.liseurDb.vocabularyDao().insertOccurrence(
            occB0.copy(
                encounters = 5,
                lastSeenAt = baseTime + 20_000L,
            ),
        )

        // Both devices upload their concurrent edits while offline from each other.
        server.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val seqABeforeMerge = devA.driveDb.accounts().get(PERM_ID)!!.nextSeq - 1L
        server.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        server.unhideAll()

        // A pulls B's edit, merges word + occurrence, and publishes the converged state (seqAMerge).
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val seqAMerge = devA.driveDb.accounts().get(PERM_ID)!!.nextSeq - 1L
        assertTrue(seqAMerge > seqABeforeMerge)

        // Hide A's merged batch so B independently pulls A's pre-merge edit, merges to the EXACT
        // same converged content, and publishes its own converged batch (seqBMerge).
        server.hideDeltaSeq("dev-a", seqAMerge)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        server.unhideAll()

        // Now cross-pull the converged batches on BOTH A and B!
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync(forceManifestRescan = true).outcome)

        // Verify both A and B converged AND preserved local authorship (originDeviceId == null).
        val wordKey = DriveVocabularySync.wordKey("fr", "serenite")
        val wordStateA = devA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_WORD, wordKey)!!
        val wordStateB = devB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_WORD, wordKey)!!
        assertNull("A must retain local origin on converged vocabWord", wordStateA.originDeviceId)
        assertNull("B must retain local origin on converged vocabWord", wordStateB.originDeviceId)
        val occStatesA = devA.driveDb.recordStates().forDomain(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE)
        val occStatesB = devB.driveDb.recordStates().forDomain(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE)
        assertEquals(1, occStatesA.size)
        assertEquals(1, occStatesB.size)
        assertNull("A must retain local origin on converged vocabOccurrence", occStatesA.single().originDeviceId)
        assertNull("B must retain local origin on converged vocabOccurrence", occStatesB.single().originDeviceId)

        // Repeatedly compact BOTH devices so all original vocabulary deltas are pruned!
        var compactionsA = 0
        var compactionsB = 0
        for (i in 1..12) {
            devA.liseurDb.annotationDao().upsert(
                devA.liseurDb.annotationDao().byId("ann-compact-a")!!.copy(
                    note = "a-compact-$i",
                    updatedAt = (baseTime + 50_000L + i * 1_000L) * 1_000L,
                ),
            )
            val runA = devA.changeEngine.sync()
            assertEquals(SyncOutcome.Success, runA.outcome)
            if (runA.compacted) compactionsA++

            devB.liseurDb.annotationDao().upsert(
                devB.liseurDb.annotationDao().byId("ann-compact-b")!!.copy(
                    note = "b-compact-$i",
                    updatedAt = (baseTime + 60_000L + i * 1_000L) * 1_000L,
                ),
            )
            val runB = devB.changeEngine.sync()
            assertEquals(SyncOutcome.Success, runB.outcome)
            if (runB.compacted) compactionsB++
        }
        assertTrue("Expected >= 2 compactions on A, got $compactionsA", compactionsA >= 2)
        assertTrue("Expected >= 2 compactions on B, got $compactionsB", compactionsB >= 2)

        // Confirm original vocabulary deltas were pruned on BOTH A and B!
        val survivingASeqs = server.liveAppDataFiles()
            .filter { it.appProperties["kind"] == DriveFile.KIND_DELTA && it.appProperties["deviceId"] == "dev-a" }
            .mapNotNull { it.appProperties["seq"]?.toLongOrNull() }
        val survivingBSeqs = server.liveAppDataFiles()
            .filter { it.appProperties["kind"] == DriveFile.KIND_DELTA && it.appProperties["deviceId"] == "dev-b" }
            .mapNotNull { it.appProperties["seq"]?.toLongOrNull() }
        assertTrue(survivingASeqs.all { it > seqAMerge })
        assertFalse(1L in survivingBSeqs)

        // Fresh Device D connects and recovers BOTH the converged word AND occurrence from compacted snapshots!
        val devD = createDevice(deviceId = "dev-d-fresh")
        devD.connect()
        assertEquals(SyncOutcome.Success, devD.changeEngine.sync().outcome)

        val recoveredWord = devD.liseurDb.vocabularyDao().findWord("serenite", "fr")
        assertNotNull("Converged vocabulary word MUST be recovered on fresh install after both devices compact", recoveredWord)
        assertEquals("deep peace", recoveredWord!!.definition)
        assertEquals("B personal note", recoveredWord.notes)

        val recoveredOccs = devD.liseurDb.vocabularyDao().occurrencesList(recoveredWord.id)
        assertEquals("Converged vocabulary occurrence MUST be recovered on fresh install", 1, recoveredOccs.size)
        assertEquals("A edited context sentence", recoveredOccs.single().sentence)
        assertEquals(5, recoveredOccs.single().encounters)
    }

    @Test
    fun `failure mid-multipart snapshot upload followed by retry reuses uploaded parts and leaves zero orphan snapshot parts`() = runTest {
        val maxPartBytes = 1_300
        val devA = createDevice(
            deviceId = "dev-a",
            compactionDeltaThreshold = 4,
            compactionMaxPartBytes = maxPartBytes,
        )
        devA.connect()
        val bookA = seedBook(devA, "multipart-fail.epub", "9780451524935", "1984", "George Orwell")
        val baseTime = 1_746_100_000_000L

        // Seed 3 small distinct annotations across 3 deltas so the snapshot splits into >= 3 parts.
        for (i in 1..3) {
            devA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-mp-$i",
                    bookId = bookA.url,
                    kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch0$i.xhtml"}""",
                    text = "Quote #$i " + "a".repeat(120),
                    note = "Note #$i " + "b".repeat(120),
                    createdAt = baseTime + i * 100L,
                    updatedAt = (baseTime + i * 100L) * 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        }

        // 4th delta hits threshold = 4; inject failure on the 2nd snapshot part upload!
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-mp-4",
                bookId = bookA.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch04.xhtml"}""",
                text = "Quote #4 " + "a".repeat(120),
                note = "Note #4 " + "b".repeat(120),
                createdAt = baseTime + 400L,
                updatedAt = (baseTime + 400L) * 1_000L,
            ),
        )
        server.failSnapshotUploadOnCall(2)
        val failedRun = devA.changeEngine.sync()
        assertTrue("Expected offline failure on mid-multipart upload", failedRun.outcome is SyncOutcome.Failure)
        assertNull("Manifest must not publish snapshot before all parts upload", server.decodeManifestFor("dev-a")?.snapshot)
        assertEquals(0L, devA.driveDb.accounts().get(PERM_ID)!!.snapshotThroughSeq)

        val partialUploadedFiles = server.liveSnapshotFilesFor("dev-a")
        assertEquals("Exactly 1 part should have uploaded before part 2 failed", 1, partialUploadedFiles.size)
        val firstPartFileId = partialUploadedFiles.single().id

        // Clear fault and retry without new edits -> MUST reuse part 0 and leave ZERO orphans!
        server.failSnapshotUploadOnCall(null)
        val retryRun = devA.changeEngine.sync()
        assertEquals(SyncOutcome.Success, retryRun.outcome)
        assertTrue(retryRun.compacted)

        val manifest1 = server.decodeManifestFor("dev-a")!!
        val snap1 = manifest1.snapshot!!
        assertTrue("Expected multi-part snapshot (>= 2 parts), got ${snap1.parts.size}", snap1.parts.size >= 2)
        assertEquals("Part 0 MUST be reused on retry instead of creating a duplicate", firstPartFileId, snap1.parts.first().fileId)
        assertEquals(
            "Total snapshot uploads across failure + retry must equal exact part count (no duplicate part 0)",
            snap1.parts.size,
            server.snapshotUploadCount,
        )
        assertEquals(
            "Zero orphan snapshot parts allowed after retry",
            snap1.parts.map { it.fileId }.toSet(),
            server.liveSnapshotFilesFor("dev-a").map { it.id }.toSet(),
        )
        for (ref in snap1.parts) {
            assertTrue(server.rawBytes(ref.fileId)!!.size <= maxPartBytes)
        }

        // Sub-scenario B: mid-multipart failure on 2nd compaction, followed by a new edit BEFORE retry
        // (advancing throughSeq so the partial upload from the failed generation is abandoned and MUST be GC'd!).
        for (i in 5..7) {
            devA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-mp-$i",
                    bookId = bookA.url,
                    kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch0$i.xhtml"}""",
                    text = "Quote #$i " + "c".repeat(80),
                    note = "Note #$i " + "d".repeat(80),
                    createdAt = baseTime + i * 100L,
                    updatedAt = (baseTime + i * 100L) * 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        }
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-mp-8",
                bookId = bookA.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch08.xhtml"}""",
                text = "Quote #8 " + "c".repeat(80),
                note = "Note #8 " + "d".repeat(80),
                createdAt = baseTime + 800L,
                updatedAt = (baseTime + 800L) * 1_000L,
            ),
        )
        server.failSnapshotUploadOnCall(2)
        val failedRun2 = devA.changeEngine.sync()
        assertTrue(failedRun2.outcome is SyncOutcome.Failure)

        // New edit before retry changes throughSeq from 8 -> 9, abandoning generation 8's partial part!
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-mp-9",
                bookId = bookA.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch09.xhtml"}""",
                text = "Quote #9 " + "c".repeat(80),
                note = "Note #9 " + "d".repeat(80),
                createdAt = baseTime + 900L,
                updatedAt = (baseTime + 900L) * 1_000L,
            ),
        )
        server.failSnapshotUploadOnCall(null)
        val retryRun2 = devA.changeEngine.sync()
        assertEquals(SyncOutcome.Success, retryRun2.outcome)
        assertTrue(retryRun2.compacted)

        val manifest2 = server.decodeManifestFor("dev-a")!!
        val expectedLiveIds = (manifest2.snapshot!!.parts + manifest2.previousSnapshot!!.parts).map { it.fileId }.toSet()
        assertEquals(
            "Abandoned partial snapshot parts from failed generation must be garbage-collected (zero orphans)",
            expectedLiveIds,
            server.liveSnapshotFilesFor("dev-a").map { it.id }.toSet(),
        )
    }

    @Test
    fun `failed orphan snapshot listing keeps garbage collection pending for the next sync`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 2)
        devA.connect()
        val bookA = seedBook(devA, "orphan-list.epub", "9780451524936", "Orphan List", "Author")
        val baseTime = 1_746_300_000_000L
        suspend fun addNote(i: Int) = devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-ol-$i",
                bookId = bookA.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch0$i.xhtml"}""",
                text = "Quote #$i",
                note = "Note #$i",
                createdAt = baseTime + i * 100L,
                updatedAt = (baseTime + i * 100L) * 1_000L,
            ),
        )
        for (i in 1..4) {
            addNote(i)
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        }

        addNote(5)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        addNote(6)
        server.failSnapshotListingAfterSnapshotAdvancesBeyond(devA.driveDb.accounts().get(PERM_ID)!!.snapshotThroughSeq)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        server.clearSnapshotListingFailure()

        val orphanId = server.plantOrphanSnapshot("dev-a")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertTrue(
            "Orphan must be collected by a no-edit sync after the listing failure",
            server.liveSnapshotFilesFor("dev-a").none { it.id == orphanId },
        )
    }

    private suspend fun reviewSyncIds(device: Phase6Device, language: String, normalized: String): Set<String> {
        val word = device.liseurDb.vocabularyDao().findWord(normalized, language) ?: return emptySet()
        return device.liseurDb.vocabularyDao().reviews(word.id).mapNotNull { it.syncId }.toSet()
    }

    private suspend fun reviewWord(
        device: Phase6Device,
        language: String,
        normalized: String,
        now: Long,
    ): String {
        val before = reviewSyncIds(device, language, normalized)
        val card = device.liseurDb.vocabularyDao().findWord(normalized, language)!!
        assertNotNull(VocabularyRepository(device.liseurDb).review(card, Recall.GOOD, 0, 0, now = now))
        return (reviewSyncIds(device, language, normalized) - before).single()
    }

    @Test
    fun `authored version index is stored in bounded per-key rows and round-trips for 20k keys`() = runTest {
        val devA = createDevice(deviceId = "dev-a")
        devA.connect()
        val book = seedBook(devA, "bounded-index.epub", "9780451524936", "Bounded", "Author")
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-real", bookId = book.url, kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch01.xhtml"}""", text = "real", note = "n",
                createdAt = 1_746_000_000_000L, updatedAt = 1_746_000_000_000_000L,
            ),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val hlc = devA.driveDb.recordStates().forDomain(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION).first().lastHlc

        val total = 20_000
        val foreign = 5
        val seeded = (0 until total).map { i ->
            DriveRecordState(
                permissionId = PERM_ID,
                domain = DriveWireCodec.DOMAIN_ANNOTATION,
                recordKey = "00000000-0000-4000-8000-${i.toString().padStart(12, '0')}",
                versionFp = "fp-seed-$i",
                versionOp = DriveRecordState.OP_PUT,
                publishedFp = "fp-seed-$i",
                contentFp = "fp-seed-$i",
                originDeviceId = if (i < foreign) "dev-b" else null,
                originSeq = if (i < foreign) 9L else null,
                lastHlc = hlc,
                bodyJson = """{"id":"seed-$i"}""",
            )
        }
        devA.driveDb.recordStates().upsertAll(seeded)

        val compactor = DriveCompactor(devA.driveDb, server.clientFor("dev-a"), deltaCountThreshold = 1)
        val result = compactor.compactIfNeeded(PERM_ID, "dev-a", "token-dev-a", { hlc }, { _, _ -> null })
        assertTrue(result.failureReason, result.compacted)

        val internalRows = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS.keys.flatMap {
            devA.driveDb.recordStates().forDomain(PERM_ID, DriveCompactor.AUTHORED_DOMAIN_PREFIX + it)
        } + devA.driveDb.recordStates().forDomain(PERM_ID, DriveCompactor.COMPACTION_STATE_DOMAIN)
        assertTrue(internalRows.size > total - foreign)
        val largest = internalRows.maxOf { (it.bodyJson?.length ?: 0) + it.recordKey.length + it.versionFp.length }
        assertTrue("single row must stay far below the CursorWindow size: $largest", largest < 4_096)

        val restarted = DriveCompactor(devA.driveDb, server.clientFor("dev-a"))
        assertNotNull(restarted.currentCompactionState(PERM_ID).snapshot)
        val index = restarted.loadAuthoredIndex(PERM_ID)
        val annotationKeys = index.keys.filter { it.first == DriveWireCodec.DOMAIN_ANNOTATION }.map { it.second }.toSet()
        assertEquals(total - foreign + 1, annotationKeys.size)
        assertEquals("fp-seed-${total - 1}", index[DriveWireCodec.DOMAIN_ANNOTATION to "00000000-0000-4000-8000-${(total - 1).toString().padStart(12, '0')}"]?.fp)
        assertFalse(
            "remote-origin versions must not be marked locally authored",
            index.containsKey(DriveWireCodec.DOMAIN_ANNOTATION to "00000000-0000-4000-8000-000000000000"),
        )
    }

    @Test
    fun `independent equivalent catalog versions survive cross-pulls and repeated compaction`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 2)
        val devB = createDevice(deviceId = "dev-b", compactionDeltaThreshold = 2)
        devA.connect()
        devB.connect()
        val bookA = seedBook(devA, "same.epub", "9780451524936", "Same", "Author")
        val bookB = seedBook(devB, "same.epub", "9780451524936", "Same", "Author")

        server.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        server.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        val stateA = devA.driveDb.recordStates().forDomain(PERM_ID, DriveWireCodec.DOMAIN_CATALOG).single()
        val stateB = devB.driveDb.recordStates().forDomain(PERM_ID, DriveWireCodec.DOMAIN_CATALOG).single()
        val workKey = stateA.recordKey
        assertEquals(workKey, stateB.recordKey)
        assertEquals(stateA.contentFp, stateB.contentFp)
        assertNotEquals(stateA.versionFp, stateB.versionFp)

        server.unhideAll()
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync(forceManifestRescan = true).outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync(forceManifestRescan = true).outcome)
        assertEquals(stateB.versionFp, devA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, workKey)!!.versionFp)
        assertEquals(stateA.versionFp, devB.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, workKey)!!.versionFp)

        repeat(3) { syncWithNewNoteUntilSnapshotAdvances(devA, bookA.url, "equivalent-a-$it") }
        repeat(3) { syncWithNewNoteUntilSnapshotAdvances(devB, bookB.url, "equivalent-b-$it") }
        assertTrue(devA.driveDb.outbox().forAccount(PERM_ID).none { it.seq == 1L })
        assertTrue(devB.driveDb.outbox().forAccount(PERM_ID).none { it.seq == 1L })
        for ((device, original) in listOf(devA to stateA, devB to stateB)) {
            val snapshot = restartedCompactionState(device).snapshot!!
            val catalog = snapshot.parts.flatMap {
                DriveWireCodec.decodeSnapshotPart(server.rawBytes(it.fileId)!!.toString(Charsets.UTF_8)).records
            }.single { it.d == DriveWireCodec.DOMAIN_CATALOG && it.k == workKey }
            assertEquals(original.versionFp, catalog.fp)
        }

        val devC = createDevice(deviceId = "dev-c")
        devC.connect()
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        assertNotNull(devC.liseurDb.bookDao().allOnce().find { it.title == "Same" })
    }

    @Test
    fun `a readded catalog entry survives an old authored deletion after ancestry rolls over`() = runTest {
        val devA = createDevice(deviceId = "dev-z", compactionDeltaThreshold = 2)
        devA.connect()
        val book = seedBook(devA, "readded.epub", "9780451524936", "Readded", "Author")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val original = devA.driveDb.recordStates().forDomain(PERM_ID, DriveWireCodec.DOMAIN_CATALOG).single()
        val body = DriveWireCodec.decodeCatalogBody(JSONObject(original.bodyJson!!))!!
        assertTrue(devA.driveSync.catalogSync.deleteFromSyncedLibrary(PERM_ID, book, { devA.changeEngine.nextHlc(PERM_ID) }))
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val deletion = devA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, original.recordKey)!!
        assertEquals(DriveRecordState.OP_DEL, deletion.versionOp)

        var ancestors = listOf(deletion.versionFp)
        repeat(66) { index ->
            val hlc = "001746${index.toString().padStart(7, '0')}-0000-dev-b"
            val record = DriveWireCodec.putRecord(
                domain = DriveWireCodec.DOMAIN_CATALOG, key = original.recordKey, hlc = hlc,
                bodyMap = DriveWireCodec.encodeCatalogBody(body), ancestors = ancestors,
            )
            server.injectRemoteDelta("dev-b", index + 1L, hlc, listOf(record))
            ancestors = (listOf(record.fp) + ancestors).take(DriveWireCodec.MAX_ANCESTORS)
        }
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val readded = devA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_CATALOG, original.recordKey)!!
        assertEquals(DriveRecordState.OP_PUT, readded.versionOp)
        assertFalse(deletion.versionFp in DriveWorkIdentity.decodeStringArray(readded.ancestorsJson))

        val driver = seedBook(devA, "driver.epub", "9780547928227", "Driver", "Author")
        repeat(3) { syncWithNewNoteUntilSnapshotAdvances(devA, driver.url, "readded-$it") }
        val snapshot = restartedCompactionState(devA).snapshot!!
        val records = snapshot.parts.flatMap {
            DriveWireCodec.decodeSnapshotPart(server.rawBytes(it.fileId)!!.toString(Charsets.UTF_8)).records
        }
        assertTrue(records.none { it.d == DriveWireCodec.DOMAIN_CATALOG && it.k == original.recordKey && it.op == DriveRecordState.OP_DEL })
        val devC = createDevice(deviceId = "dev-c")
        devC.connect()
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        assertNotNull(devC.liseurDb.bookDao().allOnce().find { it.title == "Readded" })
    }

    @Test
    fun `snapshot does not restore an authored word after another device deletes it`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 2)
        val devB = createDevice(deviceId = "dev-b")
        devA.connect()
        devB.connect()
        val book = seedBook(devA, "deleted-word.epub", "9780451524936", "Book", "Author")
        val repoA = VocabularyRepository(devA.liseurDb)
        val repoB = VocabularyRepository(devB.liseurDb)
        repoA.collect(VocabularyLookup("lumière", "fr", null), "light", "en", now = 1_746_000_000_000L)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        repoB.deleteWord(devB.liseurDb.vocabularyDao().findWord("lumière", "fr")!!.id)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val wordKey = DriveVocabularySync.wordKey("fr", "lumière")
        assertEquals(
            DriveRecordState.OP_DEL,
            devA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_WORD, wordKey)!!.versionOp,
        )

        repeat(3) { syncWithNewNoteUntilSnapshotAdvances(devA, book.url, "deleted-word-$it") }
        val snapshot = restartedCompactionState(devA).snapshot!!
        val records = snapshot.parts.flatMap {
            DriveWireCodec.decodeSnapshotPart(server.rawBytes(it.fileId)!!.toString(Charsets.UTF_8)).records
        }
        assertTrue(records.none { it.d == DriveWireCodec.DOMAIN_VOCAB_WORD && it.k == wordKey && it.op == DriveRecordState.OP_PUT })
    }

    @Test
    fun `snapshot omits covered authored occurrences but keeps later ones after remote agreement`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 2)
        devA.connect()
        val book = seedBook(devA, "occurrences.epub", "9780451524936", "Book", "Author")
        val wordId = VocabularyRepository(devA.liseurDb).collect(
            VocabularyLookup("mine", "fr", null), "mine", "en", now = 1_746_000_000_000L,
        )!!
        suspend fun addOccurrence(location: String) {
            devA.liseurDb.vocabularyDao().insertOccurrence(
                VocabularyOccurrence(
                    wordId = wordId, bookUrl = book.url, bookTitle = "Book", locationKey = location,
                    locator = """{"href":"ch01.xhtml"}""", sentence = location,
                    lastSeenAt = 1_746_000_000_000L, encounters = 1,
                ),
            )
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        }
        addOccurrence("ch01#p1")
        val covered = devA.driveDb.recordStates().forDomain(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE).single()
        val coveredSeq = requireNotNull(covered.originSeq)
        addOccurrence("ch01#p2")
        val occurrences = devA.driveDb.recordStates().forDomain(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE)
        val later = occurrences.single { it.recordKey != covered.recordKey }
        assertTrue(later.originSeq!! > coveredSeq)
        for (state in occurrences) {
            val body = DriveWireCodec.decodeVocabOccurrenceBody(JSONObject(state.bodyJson!!))!!
            val remote = DriveWireCodec.putRecord(
                domain = state.domain, key = state.recordKey, hlc = "0017469999999-0000-dev-b",
                bodyMap = DriveWireCodec.encodeVocabOccurrenceBody(body),
            )
            assertTrue(devA.driveSync.vocabularySync.applyRemoteOccurrence(PERM_ID, remote, "dev-b", 100L))
            assertEquals(remote.fp, devA.driveDb.recordStates().get(PERM_ID, state.domain, state.recordKey)!!.versionFp)
        }
        val wordKey = DriveVocabularySync.wordKey("fr", "mine")
        val wordState = devA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_WORD, wordKey)!!
        val wordBody = DriveWireCodec.decodeVocabWordBody(JSONObject(wordState.bodyJson!!))!!
        val wordRecord = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_VOCAB_WORD, key = wordKey, hlc = "0017469999999-0001-dev-b",
            bodyMap = DriveWireCodec.encodeVocabWordBody(wordBody),
        ).copy(observed = mapOf("dev-a" to coveredSeq))
        devA.driveSync.vocabularySync.applyRemoteWord(PERM_ID, wordRecord, "dev-b", 101L)

        repeat(3) { syncWithNewNoteUntilSnapshotAdvances(devA, book.url, "covered-occurrence-$it") }
        val snapshot = restartedCompactionState(devA).snapshot!!
        val records = snapshot.parts.flatMap {
            DriveWireCodec.decodeSnapshotPart(server.rawBytes(it.fileId)!!.toString(Charsets.UTF_8)).records
        }.filter { it.d == DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE }.map { it.k }.toSet()
        assertFalse("covered occurrence in snapshot: $records", covered.recordKey in records)
        assertTrue(later.recordKey in records)
    }

    @Test
    fun `snapshot keeps the exact local publication seq when agreement replaced provenance with a remote origin, even after outbox pruning`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 2)
        devA.connect()
        val book = seedBook(devA, "foreign-origin.epub", "9780451524936", "Foreign", "Author")
        val repoA = VocabularyRepository(devA.liseurDb)
        val baseTime = 1_746_400_000_000L
        repoA.collect(VocabularyLookup("lumière", "fr", null), "light", "en", now = baseTime)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val reviewId = reviewWord(devA, "fr", "lumière", baseTime + 1_000L)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        val states = devA.driveDb.recordStates()
        val authored = states.get(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_REVIEW, reviewId)!!
        val localSeq = authored.originSeq!!
        assertNull(authored.originDeviceId)
        // A later agreement with dev-b replaced the provenance of the identical version.
        states.upsert(authored.copy(originDeviceId = "dev-b", originSeq = 50L))

        // Three snapshot generations: the review's batch is pruned from the outbox after the second.
        repeat(3) { syncWithNewNoteUntilSnapshotAdvances(devA, book.url, "fo$it") }
        assertNotNull(restartedCompactionState(devA).previousSnapshot)
        assertTrue(
            "the review's batch must be gone so only the persisted index can supply its seq",
            devA.driveDb.outbox().forAccount(PERM_ID).none { it.seq == localSeq },
        )

        val devC = createDevice(deviceId = "dev-c")
        devC.connect()
        assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome)
        val received = devC.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_REVIEW, reviewId)!!
        assertEquals("dev-a", received.originDeviceId)
        assertEquals(localSeq, received.originSeq)
        assertEquals(setOf(reviewId), reviewSyncIds(devC, "fr", "lumière"))
        val internalLeak = devC.driveDb.recordStates().forAccount(PERM_ID)
            .any { it.domain.startsWith(DriveCompactor.AUTHORED_DOMAIN_PREFIX) }
        assertFalse("snapshot receivers must not adopt the producer's internal index", internalLeak)
    }

    @Test
    fun `delete then compact then fresh install then recollect drops covered reviews but keeps a concurrent unobserved review`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 2)
        val devB = createDevice(deviceId = "dev-b")
        devA.connect()
        devB.connect()
        val book = seedBook(devA, "delete-compact.epub", "9780451524936", "DeleteCompact", "Author")
        val repoA = VocabularyRepository(devA.liseurDb)
        val baseTime = 1_746_500_000_000L
        val day = 86_400_000L
        val lookup = VocabularyLookup("lumière", "fr", null)
        repoA.collect(lookup, "light", "en", now = baseTime)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)

        val coveredA = reviewWord(devA, "fr", "lumière", baseTime + 1_000L)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        val coveredB = reviewWord(devB, "fr", "lumière", baseTime + 30 * day)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

        // B reviews again but A never sees it before deleting.
        val concurrent = reviewWord(devB, "fr", "lumière", baseTime + 90 * day)
        server.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        val wordA = devA.liseurDb.vocabularyDao().findWord("lumière", "fr")!!
        repoA.deleteWord(wordA.id)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        repeat(2) { syncWithNewNoteUntilSnapshotAdvances(devA, book.url, "dc$it") }
        server.unhideAll()
        assertNotNull(restartedCompactionState(devA).snapshot)

        val boundaryRow = devA.driveDb.recordStates()
            .get(PERM_ID, "_vocab_delete_boundary", DriveVocabularySync.wordKey("fr", "lumière"))!!
        assertTrue("the delete must also cover A's own reviews", boundaryRow.bodyJson!!.contains("dev-a"))

        // Fresh install reads A's snapshots + B's deltas in either order and then recollects the word.
        val devC = createDevice(deviceId = "dev-c")
        devC.connect()
        repeat(3) { assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome) }
        assertNull(devC.liseurDb.vocabularyDao().findWord("lumière", "fr"))
        // The word is collected again on another device; its word record reaches C remotely.
        repoA.collect(lookup, "light", "en", now = baseTime + 120 * day)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        repeat(2) { assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome) }

        val recollected = reviewSyncIds(devC, "fr", "lumière")
        assertFalse("A's covered review resurrected", coveredA in recollected)
        assertFalse("B's covered review resurrected", coveredB in recollected)
        assertEquals("concurrent unobserved review must survive", setOf(concurrent), recollected)

        // A's own restart-free view: nothing leaked into A's snapshot either.
        val restarted = DriveCompactor(devA.driveDb, server.clientFor("dev-a"))
        assertNull(
            restarted.loadAuthoredIndex(PERM_ID)[DriveWireCodec.DOMAIN_VOCAB_REVIEW to coveredA],
        )
    }

    @Test
    fun `recollect and pruning before a fresh device's first sync keep the delete boundary and the concurrent unobserved review`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 2)
        val devB = createDevice(deviceId = "dev-b")
        devA.connect()
        devB.connect()
        val book = seedBook(devA, "recollect-prune.epub", "9780451524936", "RecollectPrune", "Author")
        val repoA = VocabularyRepository(devA.liseurDb)
        val baseTime = 1_746_550_000_000L
        val day = 86_400_000L
        val lookup = VocabularyLookup("lumière", "fr", null)
        repoA.collect(lookup, "light", "en", now = baseTime)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)

        val observedByA = reviewWord(devB, "fr", "lumière", baseTime + 30 * day)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(setOf(observedByA), reviewSyncIds(devA, "fr", "lumière"))

        // B goes offline with a review A never sees.
        val concurrent = reviewWord(devB, "fr", "lumière", baseTime + 90 * day)
        server.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)

        repoA.deleteWord(devA.liseurDb.vocabularyDao().findWord("lumière", "fr")!!.id)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        repoA.collect(lookup, "light", "en", now = baseTime + 120 * day)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val wordKey = DriveVocabularySync.wordKey("fr", "lumière")
        val authored = devA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_WORD, wordKey)!!
        val body = DriveWireCodec.decodeVocabWordBody(JSONObject(authored.bodyJson!!))!!
        val equivalent = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
            key = wordKey,
            hlc = "0017469999999-0000-dev-b",
            bodyMap = DriveWireCodec.encodeVocabWordBody(body),
        )
        devA.driveSync.vocabularySync.applyRemoteWord(PERM_ID, equivalent, "dev-b", 100L)
        assertEquals(equivalent.fp, devA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_WORD, wordKey)!!.versionFp)
        assertNotEquals(authored.versionFp, equivalent.fp)
        repeat(3) { syncWithNewNoteUntilSnapshotAdvances(devA, book.url, "rp$it") }
        server.unhideAll()
        val state = restartedCompactionState(devA)
        assertNotNull(state.previousSnapshot)
        val snapshotWord = state.snapshot!!.parts.flatMap {
            DriveWireCodec.decodeSnapshotPart(server.rawBytes(it.fileId)!!.toString(Charsets.UTF_8)).records
        }.single { it.d == DriveWireCodec.DOMAIN_VOCAB_WORD && it.k == wordKey }
        assertTrue((snapshotWord.observed?.get("dev-b") ?: 0L) > 0L)

        val devC = createDevice(deviceId = "dev-c")
        devC.connect()
        repeat(3) { assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome) }
        assertNotNull(devC.liseurDb.vocabularyDao().findWord("lumière", "fr"))
        assertEquals(setOf(concurrent), reviewSyncIds(devC, "fr", "lumière"))
    }

    @Test
    fun `snapshot word tombstone covers the author's own reviews`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 2)
        devA.connect()
        val book = seedBook(devA, "own-coverage.epub", "9780451524936", "OwnCoverage", "Author")
        val repoA = VocabularyRepository(devA.liseurDb)
        val baseTime = 1_746_560_000_000L
        repoA.collect(VocabularyLookup("lumière", "fr", null), "light", "en", now = baseTime)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val reviewId = reviewWord(devA, "fr", "lumière", baseTime + 1_000L)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val reviewSeq = devA.driveDb.recordStates()
            .get(PERM_ID, DriveWireCodec.DOMAIN_VOCAB_REVIEW, reviewId)!!.originSeq!!
        repoA.deleteWord(devA.liseurDb.vocabularyDao().findWord("lumière", "fr")!!.id)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        syncWithNewNoteUntilSnapshotAdvances(devA, book.url, "oc")

        val snapshot = restartedCompactionState(devA).snapshot!!
        val tombstone = snapshot.parts
            .flatMap { DriveWireCodec.decodeSnapshotPart(server.rawBytes(it.fileId)!!.toString(Charsets.UTF_8)).records }
            .single { it.d == DriveWireCodec.DOMAIN_VOCAB_WORD && it.k == DriveVocabularySync.wordKey("fr", "lumière") }
        assertEquals(DriveRecordState.OP_DEL, tombstone.op)
        assertTrue(
            "tombstone must cover the author's review: ${tombstone.observed}",
            (tombstone.observed?.get("dev-a") ?: 0L) >= reviewSeq,
        )
    }

    @Test
    fun `compaction defers while a published key has an unpublished newer version`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 1_000)
        devA.connect()
        val book = seedBook(devA, "dirty-key.epub", "9780451524936", "DirtyKey", "Author")
        repeat(3) { i ->
            devA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "dk-$i", bookId = book.url, kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch0$i.xhtml"}""", text = "q", note = "n",
                    createdAt = 1_746_300_000_000L + i, updatedAt = (1_746_300_000_000L + i) * 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        }

        val states = devA.driveDb.recordStates()
        val held = states.forDomain(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION).first { it.publishedFp != null }
        assertEquals(held.versionFp, held.publishedFp)
        val hlc = held.lastHlc
        val compactor = DriveCompactor(devA.driveDb, server.clientFor("dev-a"), deltaCountThreshold = 1)

        // A local edit held back by an open conflict: published version differs from the current one.
        states.upsert(held.copy(versionFp = "fp-held-by-conflict"))
        val deferred = compactor.compactIfNeeded(PERM_ID, "dev-a", "token-dev-a", { hlc }, { _, _ -> null })
        assertFalse(deferred.failureReason, deferred.compacted)

        states.upsert(held)
        val compacted = compactor.compactIfNeeded(PERM_ID, "dev-a", "token-dev-a", { hlc }, { _, _ -> null })
        assertTrue(compacted.failureReason, compacted.compacted)
    }

    @Test
    fun `reset-covered never-published peak does not block compaction`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 2)
        devA.connect()
        val book = seedBook(devA, "unsent-peak.epub", "9780060850524", "Unsent", "Author")
        val sha = devA.liseurDb.workIdentityDao().fingerprint(book.url)!!.sha256
        devA.liseurDb.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = book.url,
                locatorJson = """{"href":"ch05.xhtml","locations":{"totalProgression":0.5}}""",
                totalProgression = 0.5,
                updatedAt = 1_745_700_000_000L,
                readAt = 1_745_700_000_000L,
                status = ReadingStatus.READING.wireName,
                localRevision = 1L,
                positionRevision = 1L,
                statusRevision = 1L,
                peakProgression = 0.8,
                peakLocator = """{"href":"ch08.xhtml","locations":{"totalProgression":0.8}}""",
                peakRevision = 5L,
                peakAt = 1_745_700_000_000L,
                peakEdition = sha,
            ),
        )
        val preview = devA.driveSync.positionSync.previewKnownBook(book.url)
        val dest = requireNotNull(
            (preview as? com.chmouel.liseur.data.remote.PreviewOutcome.Ready)?.preview?.furthest,
        ) { "Expected furthest destination in $preview" }
        assertEquals(
            ResolveOutcome.Done,
            devA.driveSync.positionSync.forgetFurthestPosition(book.url, dest, uptoSeq = 0L),
        )
        val peakKey = DrivePositionSync.peakRecordKey("isbn:9780060850524", sha, "dev-a")
        assertNull(devA.driveDb.recordStates().get(PERM_ID, DriveWireCodec.DOMAIN_FURTHEST, peakKey))

        syncWithNewNoteUntilSnapshotAdvances(devA, book.url, "up")
    }

    @Test
    fun `review authored after a recollect is not covered by the earlier delete and survives snapshot reinstall`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 2)
        devA.connect()
        val book = seedBook(devA, "recollect.epub", "9780451524936", "Recollect", "Author")
        val repoA = VocabularyRepository(devA.liseurDb)
        val baseTime = 1_746_600_000_000L
        val lookup = VocabularyLookup("lumière", "fr", null)
        repoA.collect(lookup, "light", "en", now = baseTime)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val old = reviewWord(devA, "fr", "lumière", baseTime + 1_000L)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        repoA.deleteWord(devA.liseurDb.vocabularyDao().findWord("lumière", "fr")!!.id)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        repoA.collect(lookup, "light", "en", now = baseTime + 10_000L)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val fresh = reviewWord(devA, "fr", "lumière", baseTime + 86_400_000L * 30)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        repeat(2) { syncWithNewNoteUntilSnapshotAdvances(devA, book.url, "rc$it") }

        val devC = createDevice(deviceId = "dev-c")
        devC.connect()
        repeat(2) { assertEquals(SyncOutcome.Success, devC.changeEngine.sync().outcome) }
        val reviews = reviewSyncIds(devC, "fr", "lumière")
        assertTrue("post-recollect review must survive", fresh in reviews)
        assertFalse("pre-delete review must not be resurrected", old in reviews)
    }

    private suspend fun restartedCompactionState(device: Phase6Device): DriveCompactionState =
        DriveCompactor(device.driveDb, server.clientFor(device.deviceId)).currentCompactionState(PERM_ID)

    private suspend fun syncWithNewNoteUntilSnapshotAdvances(device: Phase6Device, bookId: String, prefix: String) {
        val before = device.driveDb.accounts().get(PERM_ID)!!.snapshotThroughSeq
        for (i in 1..6) {
            device.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "$prefix-$i-${System.nanoTime()}",
                    bookId = bookId,
                    kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch0$i.xhtml"}""",
                    text = "Quote $i",
                    note = "Note $i",
                    createdAt = 1_746_300_000_000L + i,
                    updatedAt = (1_746_300_000_000L + i) * 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, device.changeEngine.sync().outcome)
            if (device.driveDb.accounts().get(PERM_ID)!!.snapshotThroughSeq > before) return
        }
        throw AssertionError("snapshot did not advance")
    }

    @Test
    fun `first snapshot orphan listing failure persists cleanup flag across restart and retries without edits`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 2)
        devA.connect()
        val book = seedBook(devA, "orphan-first.epub", "9780451524936", "Orphan First", "Author")
        server.failSnapshotListingAfterSnapshotAdvancesBeyond(0L)
        syncWithNewNoteUntilSnapshotAdvances(devA, book.url, "first")
        server.clearSnapshotListingFailure()

        val failed = restartedCompactionState(devA)
        assertTrue(failed.snapshot != null)
        assertNull(failed.previousSnapshot)
        assertEquals(0L, failed.prunedThroughSeq)
        assertTrue(failed.pendingPruneFileIds.isEmpty())
        assertTrue("Failed discovery must persist the cleanup flag", failed.orphanCleanupNeeded)

        val orphanId = server.plantOrphanSnapshot("dev-a")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertTrue(server.liveSnapshotFilesFor("dev-a").none { it.id == orphanId })
        assertFalse(restartedCompactionState(devA).orphanCleanupNeeded)
    }

    @Test
    fun `orphan listing failure after last pending id is pruned persists cleanup flag across restart`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 2)
        devA.connect()
        val book = seedBook(devA, "orphan-pruned.epub", "9780451524936", "Orphan Pruned", "Author")
        syncWithNewNoteUntilSnapshotAdvances(devA, book.url, "a")
        syncWithNewNoteUntilSnapshotAdvances(devA, book.url, "b")

        server.failNextDeletes(1)
        syncWithNewNoteUntilSnapshotAdvances(devA, book.url, "c")
        server.failNextDeletes(0)
        val stuck = restartedCompactionState(devA)
        assertTrue(stuck.pendingPruneFileIds.isNotEmpty())

        server.failSnapshotListingNow()
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        val failed = restartedCompactionState(devA)
        assertTrue(failed.pendingPruneFileIds.isEmpty())
        assertEquals(failed.previousSnapshot!!.throughSeq, failed.prunedThroughSeq)
        assertTrue("Failed discovery must persist the cleanup flag", failed.orphanCleanupNeeded)
        server.clearSnapshotListingFailure()

        val orphanId = server.plantOrphanSnapshot("dev-a")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertTrue(server.liveSnapshotFilesFor("dev-a").none { it.id == orphanId })
        assertFalse(restartedCompactionState(devA).orphanCleanupNeeded)
    }

    @Test
    fun `crash between remote manifest publication and local state commit adopts published snapshot on retry with zero orphan parts`() = runTest {
        val devA = createDevice(
            deviceId = "dev-a",
            compactionDeltaThreshold = 3,
            compactionMaxPartBytes = 1_200,
        )
        devA.connect()
        val bookA = seedBook(devA, "manifest-crash.epub", "9780061120084", "To Kill a Mockingbird", "Harper Lee")
        val baseTime = 1_746_200_000_000L

        // Publish 2 deltas (below threshold = 3).
        for (i in 1..2) {
            devA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-mc-$i",
                    bookId = bookA.url,
                    kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch0$i.xhtml"}""",
                    text = "Text #$i " + "x".repeat(120),
                    note = "Note #$i " + "y".repeat(120),
                    createdAt = baseTime + i * 100L,
                    updatedAt = (baseTime + i * 100L) * 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        }

        // 3rd delta triggers compaction; inject crash RIGHT AFTER remote manifest write and BEFORE local DB commit!
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-mc-3",
                bookId = bookA.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch03.xhtml"}""",
                text = "Text #3 " + "x".repeat(120),
                note = "Note #3 " + "y".repeat(120),
                createdAt = baseTime + 300L,
                updatedAt = (baseTime + 300L) * 1_000L,
            ),
        )
        server.failAfterNextSnapshotManifestWrite(throughSeq = 3L)
        val crashedRun = devA.changeEngine.sync()
        assertTrue("Expected failure simulating crash after remote manifest write", crashedRun.outcome is SyncOutcome.Failure)

        // Confirm remote manifest ALREADY published throughSeq=3 while local DB still thinks snapshotThroughSeq=0!
        val remoteManifestAfterCrash = server.decodeManifestFor("dev-a")!!
        val publishedSnap1 = remoteManifestAfterCrash.snapshot
        assertNotNull("Remote manifest must already hold the published snapshot", publishedSnap1)
        assertEquals(3L, publishedSnap1!!.throughSeq)
        assertEquals(0L, devA.driveDb.accounts().get(PERM_ID)!!.snapshotThroughSeq)
        assertNull(devA.changeEngine.compactor.currentCompactionState(PERM_ID).snapshot)

        val uploadsBeforeRetry = server.snapshotUploadCount

        // Retry sync -> MUST adopt the already-published remote manifest WITHOUT re-uploading any snapshot parts!
        val recoveryRun = devA.changeEngine.sync()
        assertEquals(SyncOutcome.Success, recoveryRun.outcome)
        assertEquals("Retry must NOT re-upload parts of an already-published manifest snapshot", uploadsBeforeRetry, server.snapshotUploadCount)
        assertEquals(3L, devA.driveDb.accounts().get(PERM_ID)!!.snapshotThroughSeq)
        assertEquals(publishedSnap1, devA.changeEngine.compactor.currentCompactionState(PERM_ID).snapshot)
        assertNull(devA.changeEngine.compactor.currentCompactionState(PERM_ID).pendingSnapshot)
        assertEquals(
            "Zero orphan snapshot parts after manifest-crash recovery",
            publishedSnap1.parts.map { it.fileId }.toSet(),
            server.liveSnapshotFilesFor("dev-a").map { it.id }.toSet(),
        )

        // Sub-scenario B: crash after 2nd compaction's remote manifest write, PLUS a new local edit BEFORE retry!
        // Ensures pending published manifest is adopted BEFORE publishPending rewrites the manifest!
        for (i in 4..5) {
            devA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-mc-$i",
                    bookId = bookA.url,
                    kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch0$i.xhtml"}""",
                    text = "Text #$i " + "z".repeat(80),
                    note = "Note #$i",
                    createdAt = baseTime + i * 100L,
                    updatedAt = (baseTime + i * 100L) * 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        }
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-mc-6",
                bookId = bookA.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch06.xhtml"}""",
                text = "Text #6 " + "z".repeat(80),
                note = "Note #6",
                createdAt = baseTime + 600L,
                updatedAt = (baseTime + 600L) * 1_000L,
            ),
        )
        server.failAfterNextSnapshotManifestWrite(throughSeq = 6L)
        val crashedRun2 = devA.changeEngine.sync()
        assertTrue(crashedRun2.outcome is SyncOutcome.Failure)

        val publishedSnap2 = server.decodeManifestFor("dev-a")!!.snapshot!!
        assertEquals(6L, publishedSnap2.throughSeq)
        assertEquals(3L, devA.driveDb.accounts().get(PERM_ID)!!.snapshotThroughSeq)
        val uploadsBeforeRetry2 = server.snapshotUploadCount

        // Make a new local edit BEFORE retrying!
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-mc-7",
                bookId = bookA.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch07.xhtml"}""",
                text = "Post-crash local edit",
                note = "Note #7",
                createdAt = baseTime + 700L,
                updatedAt = (baseTime + 700L) * 1_000L,
            ),
        )
        val recoveryRun2 = devA.changeEngine.sync()
        assertEquals(SyncOutcome.Success, recoveryRun2.outcome)
        assertEquals(1, recoveryRun2.publishedBatches)
        assertEquals("Must adopt snap2 without re-uploading any snapshot parts", uploadsBeforeRetry2, server.snapshotUploadCount)

        val finalManifest = server.decodeManifestFor("dev-a")!!
        assertEquals(publishedSnap2, finalManifest.snapshot)
        assertEquals(publishedSnap1, finalManifest.previousSnapshot)
        assertEquals(6L, devA.driveDb.accounts().get(PERM_ID)!!.snapshotThroughSeq)
        assertEquals(publishedSnap2, devA.changeEngine.compactor.currentCompactionState(PERM_ID).snapshot)
        assertEquals(
            "Zero orphan snapshot parts after post-crash edit + retry",
            (publishedSnap2.parts + publishedSnap1.parts).map { it.fileId }.toSet(),
            server.liveSnapshotFilesFor("dev-a").map { it.id }.toSet(),
        )
    }

    @Test
    fun `compaction defers while a supported-domain record is still unpublished`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 3)
        devA.connect()
        val bookA = seedBook(devA, "deferred.epub", "9780743273565", "Deferred", "Author")
        val baseTime = 1_746_400_000_000L
        devA.driveDb.recordStates().upsert(
            DriveRecordState(
                permissionId = PERM_ID,
                domain = DriveWireCodec.DOMAIN_ANNOTATION,
                recordKey = "ann-unpublished",
                versionFp = "fp-unpublished",
                versionOp = DriveRecordState.OP_RESOLVE,
                publishedFp = null,
                contentFp = null,
                ancestorsJson = "[]",
                resRefsJson = "[]",
                lastHlc = "0017464000000-0001-dev-a",
            ),
        )
        for (i in 1..4) {
            devA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-defer-$i",
                    bookId = bookA.url,
                    kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch0$i.xhtml"}""",
                    text = "Quote $i",
                    note = "n-$i",
                    createdAt = baseTime + i * 100L,
                    updatedAt = (baseTime + i * 100L) * 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        }
        assertNull(server.decodeManifestFor("dev-a")?.snapshot)

        devA.driveDb.recordStates().delete(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-unpublished")
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-defer-5",
                bookId = bookA.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch05.xhtml"}""",
                text = "Quote 5",
                note = "n-5",
                createdAt = baseTime + 500L,
                updatedAt = (baseTime + 500L) * 1_000L,
            ),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertNotNull(server.decodeManifestFor("dev-a")?.snapshot)
    }

    @Test
    fun `indivisible oversized snapshot record aborts compaction safely before uploading or publishing and retains existing snapshots and deltas`() = runTest {
        val maxPartBytes = 1_100
        val devA = createDevice(
            deviceId = "dev-a",
            compactionDeltaThreshold = 3,
            compactionMaxPartBytes = maxPartBytes,
        )
        devA.connect()
        val bookA = seedBook(devA, "oversized.epub", "9780743273565", "The Great Gatsby", "F. Scott Fitzgerald")
        val baseTime = 1_746_300_000_000L

        // First perform 3 small deltas to publish a valid initial snapshot S_1.
        for (i in 1..3) {
            devA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-ok-$i",
                    bookId = bookA.url,
                    kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch0$i.xhtml"}""",
                    text = "Short quote $i",
                    note = "ok-$i",
                    createdAt = baseTime + i * 100L,
                    updatedAt = (baseTime + i * 100L) * 1_000L,
                ),
            )
            val run = devA.changeEngine.sync()
            assertEquals(SyncOutcome.Success, run.outcome)
        }
        val manifestBefore = server.decodeManifestFor("dev-a")!!
        val snap1 = manifestBefore.snapshot!!
        assertEquals(3L, snap1.throughSeq)
        for (ref in snap1.parts) {
            assertTrue(server.rawBytes(ref.fileId)!!.size <= maxPartBytes)
        }

        // Now insert an indivisible annotation whose single serialized record exceeds maxPartBytes (900 bytes),
        // followed by 2 more deltas to reach the compaction threshold again.
        val hugeNote = "HUGE-INDIVISIBLE-NOTE-" + "X".repeat(1_200)
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-oversized",
                bookId = bookA.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch09.xhtml"}""",
                text = "Huge quote",
                note = hugeNote,
                createdAt = baseTime + 1_000L,
                updatedAt = (baseTime + 1_000L) * 1_000L,
            ),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome) // seq 4

        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-small-5",
                bookId = bookA.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch05.xhtml"}""",
                text = "Small 5",
                note = "s5",
                createdAt = baseTime + 2_000L,
                updatedAt = (baseTime + 2_000L) * 1_000L,
            ),
        )
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome) // seq 5

        val uploadsBeforeAttempt = server.snapshotUploadCount
        devA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "ann-small-6",
                bookId = bookA.url,
                kind = AnnotationKind.NOTE.name,
                locatorJson = """{"href":"ch06.xhtml"}""",
                text = "Small 6",
                note = "s6",
                createdAt = baseTime + 3_000L,
                updatedAt = (baseTime + 3_000L) * 1_000L,
            ),
        )
        val abortedRun = devA.changeEngine.sync() // seq 6 -> hits compaction threshold, must abort safely!

        assertEquals(SyncOutcome.Success, abortedRun.outcome)
        assertFalse("Compaction must abort when an indivisible record exceeds maxPartBytes", abortedRun.compacted)
        assertNotNull("Controlled compaction error must be reported", abortedRun.compactionError)
        assertTrue(abortedRun.compactionError!!.contains("maxPartBytes=$maxPartBytes"))
        assertEquals(abortedRun.compactionError, devA.driveDb.accounts().get(PERM_ID)!!.lastError)

        // Assert ZERO snapshot parts were uploaded, manifest STILL points to S_1, and ALL deltas 1..6 are retained!
        assertEquals("Must abort BEFORE uploading any snapshot part", uploadsBeforeAttempt, server.snapshotUploadCount)
        assertEquals(snap1, server.decodeManifestFor("dev-a")!!.snapshot)
        assertEquals(3L, devA.driveDb.accounts().get(PERM_ID)!!.snapshotThroughSeq)
        assertEquals(
            snap1.parts.map { it.fileId }.toSet(),
            server.liveSnapshotFilesFor("dev-a").map { it.id }.toSet(),
        )
        val liveDeltaSeqs = server.liveAppDataFiles()
            .filter { it.appProperties["kind"] == DriveFile.KIND_DELTA && it.appProperties["deviceId"] == "dev-a" }
            .mapNotNull { it.appProperties["seq"]?.toLongOrNull() }
            .toSet()
        assertTrue("Retained deltas 4..6 must survive aborted compaction", setOf(4L, 5L, 6L).all { it in liveDeltaSeqs })

        // Verify a fresh device D still recovers both S_1 AND the oversized record from retained deltas!
        val devD = createDevice(deviceId = "dev-d-fresh")
        devD.connect()
        assertEquals(SyncOutcome.Success, devD.changeEngine.sync().outcome)
        assertEquals(hugeNote, devD.liseurDb.annotationDao().byId("ann-oversized")!!.note)
        assertEquals("s6", devD.liseurDb.annotationDao().byId("ann-small-6")!!.note)
    }

    @Test
    fun `snapshots only include conflict resolutions authored by the snapshot owner device`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 3)
        val devB = createDevice(deviceId = "dev-b", compactionDeltaThreshold = 3)
        devA.connect()
        devB.connect()

        val bookA = seedBook(devA, "res-owner.epub", "9780142437247", "Moby-Dick", "Herman Melville")
        val baseTime = 1_746_400_000_000L
        val ann0 = BookAnnotation(
            id = "ann-res-owner",
            bookId = bookA.url,
            kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""",
            text = "Call me Ishmael",
            note = "f0",
            createdAt = baseTime,
            updatedAt = baseTime * 1_000L,
        )
        devA.liseurDb.annotationDao().upsert(ann0)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)

        // Create conflict {fA, fB} offline.
        devA.liseurDb.annotationDao().upsert(ann0.copy(note = "fA", updatedAt = (baseTime + 1_000L) * 1_000L))
        devB.liseurDb.annotationDao().upsert(
            devB.liseurDb.annotationDao().byId("ann-res-owner")!!.copy(note = "fB", updatedAt = (baseTime + 2_000L) * 1_000L),
        )
        server.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        server.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        server.unhideAll()

        // Device A pulls B and authors resolution `resA`.
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        devA.driveSync.resolveConflicts(
            devA.driveDb.conflicts().openForAccount(PERM_ID),
            DriveConflictChoice.KEEP_LOCAL,
            syncAfter = true,
        )
        val resA = devA.driveDb.resolutions().forRecord(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-res-owner").single()
        assertEquals("dev-a", resA.authorDeviceId)

        // Device B pulls `resA` so `resA` is persisted in B's `drive_resolution` table with authorDeviceId = "dev-a".
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        val resOnB = devB.driveDb.resolutions().forRecord(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-res-owner").single()
        assertEquals(resA.resolutionFp, resOnB.resolutionFp)
        assertEquals("dev-a", resOnB.authorDeviceId)

        // Trigger compaction on BOTH Device A and Device B!
        val stubB = "drive:isbn:9780142437247"
        for (i in 1..4) {
            devA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-pad-a-$i",
                    bookId = bookA.url,
                    kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch0$i.xhtml"}""",
                    text = "padA",
                    note = "pa$i",
                    createdAt = baseTime + 10_000L + i * 100L,
                    updatedAt = (baseTime + 10_000L + i * 100L) * 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)

            devB.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-pad-b-$i",
                    bookId = stubB,
                    kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch0$i.xhtml"}""",
                    text = "padB",
                    note = "pb$i",
                    createdAt = baseTime + 20_000L + i * 100L,
                    updatedAt = (baseTime + 20_000L + i * 100L) * 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        }

        val snapA = server.decodeManifestFor("dev-a")!!.snapshot!!
        val snapB = server.decodeManifestFor("dev-b")!!.snapshot!!
        val resolutionsInA = snapA.parts.flatMap { ref ->
            DriveWireCodec.decodeSnapshotPart(server.rawBytes(ref.fileId)!!.toString(Charsets.UTF_8)).resolutions
        }
        val resolutionsInB = snapB.parts.flatMap { ref ->
            DriveWireCodec.decodeSnapshotPart(server.rawBytes(ref.fileId)!!.toString(Charsets.UTF_8)).resolutions
        }

        assertTrue(
            "Device A's snapshot MUST include A's authored resolution",
            resolutionsInA.any { it.resolutionFp == resA.resolutionFp },
        )
        assertFalse(
            "Device B's snapshot MUST NOT copy Device A's authored resolution",
            resolutionsInB.any { it.resolutionFp == resA.resolutionFp },
        )
    }

    @Test
    fun `snapshot resolution record keeps the resolution HLC when record state lastHlc was raised`() = runTest {
        val devA = createDevice(deviceId = "dev-a", compactionDeltaThreshold = 3)
        val devB = createDevice(deviceId = "dev-b", compactionDeltaThreshold = 3)
        devA.connect()
        devB.connect()

        val bookA = seedBook(devA, "res-hlc.epub", "9780142437248", "Moby-Dick", "Herman Melville")
        val baseTime = 1_746_500_000_000L
        val ann0 = BookAnnotation(
            id = "ann-res-hlc",
            bookId = bookA.url,
            kind = AnnotationKind.NOTE.name,
            locatorJson = """{"href":"ch01.xhtml"}""",
            text = "Call me Ishmael",
            note = "f0",
            createdAt = baseTime,
            updatedAt = baseTime * 1_000L,
        )
        devA.liseurDb.annotationDao().upsert(ann0)
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        devA.liseurDb.annotationDao().upsert(ann0.copy(note = "fA", updatedAt = (baseTime + 1_000L) * 1_000L))
        devB.liseurDb.annotationDao().upsert(
            devB.liseurDb.annotationDao().byId("ann-res-hlc")!!.copy(note = "fB", updatedAt = (baseTime + 2_000L) * 1_000L),
        )
        server.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        server.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, devB.changeEngine.sync().outcome)
        server.unhideAll()
        assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        devA.driveSync.resolveConflicts(
            devA.driveDb.conflicts().openForAccount(PERM_ID),
            DriveConflictChoice.KEEP_LOCAL,
            syncAfter = true,
        )
        val resA = devA.driveDb.resolutions().forRecord(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-res-hlc").single()

        val raisedHlc = "9999999999999" + resA.hlc.substring(13)
        val resolveState = devA.driveDb.recordStates()
            .get(PERM_ID, DriveWireCodec.DOMAIN_ANNOTATION, "ann-res-hlc")!!
        assertEquals(DriveRecordState.OP_RESOLVE, resolveState.versionOp)
        devA.driveDb.recordStates().upsert(resolveState.copy(lastHlc = raisedHlc))

        for (i in 1..4) {
            devA.liseurDb.annotationDao().upsert(
                BookAnnotation(
                    id = "ann-hlc-pad-$i",
                    bookId = bookA.url,
                    kind = AnnotationKind.NOTE.name,
                    locatorJson = """{"href":"ch0$i.xhtml"}""",
                    text = "pad",
                    note = "p$i",
                    createdAt = baseTime + 10_000L + i * 100L,
                    updatedAt = (baseTime + 10_000L + i * 100L) * 1_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, devA.changeEngine.sync().outcome)
        }

        val snapA = server.decodeManifestFor("dev-a")!!.snapshot!!
        val resolveRecords = snapA.parts.flatMap { ref ->
            DriveWireCodec.decodeSnapshotPart(server.rawBytes(ref.fileId)!!.toString(Charsets.UTF_8)).records
        }.filter { it.op == DriveRecordState.OP_RESOLVE && it.fp == resA.resolutionFp }
        assertEquals(1, resolveRecords.size)
        assertEquals(resA.hlc, resolveRecords.single().hlc)
    }

    // -- Harness helpers ------------------------------------------------------

    private suspend fun computeStats(db: LiseurDatabase, nowMs: Long) = run {
        val booksByUrl = db.bookDao().allOnce().associate { b ->
            b.url to StatsBook(
                bookUrl = b.url,
                title = b.title,
                author = b.author,
                progression = 0.425,
                finished = b.finishedAt != null,
                coverPath = b.userCoverPath,
            )
        }
        val spans = db.readingSessionDao().allOnce().map { s ->
            SessionSpan(
                bookUrl = s.bookUrl,
                startedAt = s.startedAt,
                durationMs = s.durationMs,
                lastReadAt = s.endedAt ?: s.lastCheckpointAt,
                startProgression = s.startProgression,
                endProgression = s.endProgression,
            )
        }
        readingStats(
            sessions = spans,
            books = booksByUrl,
            zone = ZoneOffset.UTC,
            today = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate(),
            range = StatsRange.ALL_TIME,
            weekStart = DayOfWeek.MONDAY,
        )
    }

    private suspend fun seedBook(
        device: Phase6Device,
        fileName: String,
        isbn: String,
        title: String,
        author: String,
        fileSeriesName: String? = null,
        fileSeriesIndex: Double? = null,
        content: String = "EPUB-DATA-$fileName-$isbn",
    ): Book {
        val bytes = content.toByteArray()
        val epubFile = File(device.dir, fileName).apply { writeBytes(bytes) }
        val url = epubFile.toURI().toString()
        val fp = BookFingerprints.of(ByteArrayInputStream(bytes))
        val book = Book(
            url = url,
            title = title,
            author = author,
            coverPath = null,
            source = null,
            addedAt = 1_745_000_000_000L,
            lastOpenedAt = null,
            localUri = url,
            downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size,
            fileMetadataChecked = true,
            fileModifiedAt = epubFile.lastModified(),
            fileTitle = title,
            fileAuthor = author,
            fileSeriesName = fileSeriesName,
            fileSeriesIndex = fileSeriesIndex,
            seriesName = fileSeriesName,
            seriesIndex = fileSeriesIndex,
        )
        device.liseurDb.bookDao().upsert(book)
        device.liseurDb.workIdentityDao().upsert(
            BookFingerprintRow(url, fp.sha256, fp.partialMd5, fp.size, epubFile.lastModified(), 1_745_000_000_000L),
        )
        device.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )
        return book
    }

    private fun createDevice(
        deviceId: String,
        compactionDeltaThreshold: Int = DriveCompactor.DEFAULT_DELTA_COUNT_THRESHOLD,
        compactionBytesThreshold: Long = DriveCompactor.DEFAULT_DELTA_BYTES_THRESHOLD,
        compactionMaxPartBytes: Int = DriveWireCodec.MAX_SNAPSHOT_PART_BYTES,
        driveServer: Phase6FakeDriveServer = server,
        clock: (() -> Long)? = null,
    ): Phase6Device {
        val dir = File(tempDir, "$deviceId-${System.nanoTime()}").apply { mkdirs() }
        val coversDir = File(dir, "covers").apply { mkdirs() }
        val liseurDb = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val driveDb = Room.inMemoryDatabaseBuilder(context, DriveSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clockRef = AtomicLong(1_745_000_000_000L)
        val effectiveClock: () -> Long = clock ?: { clockRef.addAndGet(10L) }
        val bookRemoval = BookRemoval(
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
            bookRemoval = bookRemoval,
            inTransaction = { work -> liseurDb.withTransaction { work() } },
        )
        val auth = Phase6FakeAuthClient("token-$deviceId")
        val driveSync = DriveLibrarySync(
            context = context,
            database = liseurDb,
            remoteAccount = remoteAccount,
            bookRemoval = bookRemoval,
            switchCoordinator = LibrarySyncSwitchCoordinator(),
            deviceIdGenerator = { deviceId },
            openStore = { driveDb },
            authClient = auth,
            driveApi = driveServer.clientFor(deviceId),
            coversDirProvider = { coversDir },
            coverEncoder = FakeWebpEncoder,
            compactionDeltaThreshold = compactionDeltaThreshold,
            compactionBytesThreshold = compactionBytesThreshold,
            compactionMaxPartBytes = compactionMaxPartBytes,
            clock = effectiveClock,
        )
        return Phase6Device(
            deviceId = deviceId,
            dir = dir,
            coversDir = coversDir,
            liseurDb = liseurDb,
            driveDb = driveDb,
            driveSync = driveSync,
            clockRef = clockRef,
        ).also { openDevices.add(it) }
    }

    private data class Phase6Device(
        val deviceId: String,
        val dir: File,
        val coversDir: File,
        val liseurDb: LiseurDatabase,
        val driveDb: DriveSyncDatabase,
        val driveSync: DriveLibrarySync,
        val clockRef: AtomicLong,
    ) {
        val changeEngine: DriveChangeEngine get() = driveSync.changeEngine

        suspend fun connect(permissionId: String = PERM_ID, email: String = "reader@example.com") {
            driveSync.activateAccount(permissionId, email, now = clockRef.get())
        }

        fun close() {
            liseurDb.close()
            driveDb.close()
        }
    }

    private object FakeWebpEncoder : DriveWebpEncoder {
        override fun encode(sourceBytes: ByteArray, maxLongEdge: Int, quality: Int): EncodedImageFrame =
            EncodedImageFrame(
                bytes = sourceBytes,
                width = 600,
                height = 900,
            )
    }

    private class Phase6FakeAuthClient(private val accessToken: String) : DriveAuthClient {
        override suspend fun authorize(accountEmail: String?, selectAccount: Boolean): DriveAuthResult =
            DriveAuthResult.Authorized(accessToken)
        override suspend fun authorizeSilently(accountEmail: String?): DriveAuthResult =
            DriveAuthResult.Authorized(accessToken)
        override fun parseAuthorizationResult(data: Intent?): DriveAuthResult =
            DriveAuthResult.Authorized(accessToken)
        override suspend fun clearCachedToken(accessToken: String) = Unit
    }

    private class Phase6FakeDriveServer {
        private data class StoredFile(
            val meta: DriveRemoteFile,
            val bytes: ByteArray,
            val space: String,
        )

        private val idGen = AtomicInteger(1)
        private val files = linkedMapOf<String, StoredFile>()
        private val changeLog = mutableListOf<DriveChange>()
        private var hiddenFilterDeviceId: String? = null
        private var hiddenRemoteDevices: Set<String> = emptySet()
        private var hiddenDeltaSeqs: MutableSet<Pair<String, Long>> = mutableSetOf()
        private val staleOnceBytes = mutableMapOf<String, ByteArray>()
        private var remainingDeleteFailures = 0
        private var failSnapshotListing = false
        private var armSnapshotListingFailureAboveSeq: Long? = null
        private var failSnapshotUploadOnNthCall: Int? = null
        private var snapshotUploadAttemptCounter = 0
        private var failAfterSnapshotManifestThroughSeq: Long? = null
        var snapshotUploadCount = 0
            private set
        val downloadedFileIds = mutableSetOf<String>()

        fun serveStaleBytesOnce(fileId: String, bytes: ByteArray) {
            staleOnceBytes[fileId] = bytes.copyOf()
        }

        fun failNextDeletes(count: Int) {
            remainingDeleteFailures = count
        }

        fun failSnapshotListingAfterSnapshotAdvancesBeyond(throughSeq: Long) {
            armSnapshotListingFailureAboveSeq = throughSeq
        }

        fun failSnapshotListingNow() {
            failSnapshotListing = true
        }

        fun clearSnapshotListingFailure() {
            armSnapshotListingFailureAboveSeq = null
            failSnapshotListing = false
        }

        fun plantOrphanSnapshot(deviceId: String): String {
            val id = "orphan-${idGen.getAndIncrement()}"
            val meta = DriveRemoteFile(
                id = id,
                name = "orphan-snapshot",
                mimeType = "application/json",
                parents = listOf(DriveRestClient.SPACE_APPDATA),
                appProperties = mapOf("kind" to DriveFile.KIND_SNAPSHOT, "deviceId" to deviceId),
                size = 2L,
            )
            files[id] = StoredFile(meta, "{}".toByteArray(), DriveRestClient.SPACE_APPDATA)
            return id
        }

        fun failSnapshotUploadOnCall(nthCall: Int?) {
            failSnapshotUploadOnNthCall = nthCall
            snapshotUploadAttemptCounter = 0
        }

        fun failAfterNextSnapshotManifestWrite(throughSeq: Long) {
            failAfterSnapshotManifestThroughSeq = throughSeq
        }

        fun liveSnapshotFilesFor(deviceId: String): List<DriveRemoteFile> =
            liveAppDataFiles().filter {
                it.appProperties["kind"] == DriveFile.KIND_SNAPSHOT &&
                    it.appProperties["deviceId"] == deviceId
            }

        fun hideOtherDeviceDeltasFor(visibleDeviceId: String, hiddenDevices: Set<String> = emptySet()) {
            hiddenFilterDeviceId = visibleDeviceId
            hiddenRemoteDevices = hiddenDevices
        }

        fun hideDeltaSeq(deviceId: String, seq: Long) {
            hiddenDeltaSeqs.add(deviceId to seq)
        }

        fun unhideAll() {
            hiddenFilterDeviceId = null
            hiddenRemoteDevices = emptySet()
            hiddenDeltaSeqs.clear()
        }

        fun liveAppDataFiles(): List<DriveRemoteFile> =
            files.values.filter { it.space == DriveRestClient.SPACE_APPDATA && !it.meta.trashed }.map { it.meta }

        fun rawBytes(fileId: String): ByteArray? = files[fileId]?.bytes

        fun decodeManifestFor(deviceId: String): DriveManifest? {
            val stored = files.values.firstOrNull {
                it.space == DriveRestClient.SPACE_APPDATA &&
                    !it.meta.trashed &&
                    it.meta.appProperties["kind"] == DriveFile.KIND_MANIFEST &&
                    it.meta.appProperties["deviceId"] == deviceId
            } ?: return null
            return DriveWireCodec.decodeManifest(stored.bytes.toString(Charsets.UTF_8))
        }

        fun injectRemoteManifest(manifest: DriveManifest): String {
            val bytes = DriveWireCodec.encodeManifest(manifest).toByteArray(Charsets.UTF_8)
            val id = "inj-manifest-${idGen.getAndIncrement()}"
            val props = mapOf(
                "liseur" to "1",
                "kind" to DriveFile.KIND_MANIFEST,
                "schemaVersion" to manifest.schemaVersion.toString(),
                "deviceId" to manifest.deviceId,
            )
            val meta = DriveRemoteFile(
                id = id,
                name = DriveWireCodec.manifestFileName(manifest.deviceId),
                mimeType = "application/json",
                parents = listOf(DriveRestClient.SPACE_APPDATA),
                appProperties = props,
                size = bytes.size.toLong(),
            )
            files[id] = StoredFile(meta, bytes, DriveRestClient.SPACE_APPDATA)
            changeLog.add(DriveChange(id, false, meta))
            return id
        }

        fun injectRemoteDelta(
            deviceId: String,
            seq: Long,
            createdHlc: String,
            records: List<DriveWireRecord>,
            schemaVersion: Int = DriveWireCodec.SCHEMA_VERSION,
            domains: Map<String, Int> = records.map { it.d }.distinct().associateWith { 1 },
        ): String {
            val batchId = DriveCanonicalJson.batchId(deviceId, seq)
            val batch = DriveDeltaBatch(
                schemaVersion = schemaVersion,
                batchId = batchId,
                deviceId = deviceId,
                seq = seq,
                createdHlc = createdHlc,
                domains = domains,
                records = records,
            )
            val bytes = DriveWireCodec.encodeDeltaBatch(batch).toByteArray(Charsets.UTF_8)
            val id = "inj-delta-${idGen.getAndIncrement()}"
            val props = mapOf(
                "liseur" to "1",
                "kind" to DriveFile.KIND_DELTA,
                "schemaVersion" to schemaVersion.toString(),
                "deviceId" to deviceId,
                "seq" to seq.toString(),
                "batchId" to batchId,
            )
            val meta = DriveRemoteFile(
                id = id,
                name = DriveWireCodec.deltaFileName(deviceId, seq),
                mimeType = "application/json",
                parents = listOf(DriveRestClient.SPACE_APPDATA),
                appProperties = props,
                size = bytes.size.toLong(),
            )
            files[id] = StoredFile(meta, bytes, DriveRestClient.SPACE_APPDATA)
            changeLog.add(DriveChange(id, false, meta))
            return id
        }

        suspend fun advanceStartTokenForNoChanges(device: Phase6Device) {
            device.driveDb.accounts().updateStartPageToken(PERM_ID, changeLog.size.toString())
        }

        private fun isVisibleTo(callerDeviceId: String, file: DriveRemoteFile): Boolean {
            val fileDev = file.appProperties["deviceId"] ?: return true
            val fileSeq = file.appProperties["seq"]?.toLongOrNull()
            if (fileSeq != null && (fileDev to fileSeq) in hiddenDeltaSeqs) return false
            if (hiddenRemoteDevices.isNotEmpty() && fileDev in hiddenRemoteDevices) return false
            val filter = hiddenFilterDeviceId ?: return true
            if (hiddenRemoteDevices.isEmpty() && callerDeviceId == filter && fileDev != filter) return false
            return true
        }

        fun clientFor(callerDeviceId: String): DriveRemoteApi = object : DriveRemoteApi {
            override suspend fun getAbout(accessToken: String) =
                DriveUserAbout(permissionId = PERM_ID, emailAddress = "reader@example.com")

            override suspend fun listLiseurFolders(accessToken: String): List<DriveRemoteFile> =
                files.values
                    .filter {
                        it.space == DriveRestClient.SPACE_DRIVE &&
                            !it.meta.trashed &&
                            it.meta.mimeType == DriveRestClient.FOLDER_MIME_TYPE &&
                            it.meta.name == DriveRestClient.LISEUR_FOLDER_NAME
                    }
                    .map { it.meta }
                    .sortedWith(compareBy({ it.createdTime ?: "" }, { it.id }))

            override suspend fun ensureLiseurFolder(accessToken: String): LiseurFolderResolution {
                val existing = listLiseurFolders(accessToken)
                if (existing.isNotEmpty()) return LiseurFolderResolution(existing.first(), existing)
                val id = "folder-${idGen.getAndIncrement()}"
                val meta = DriveRemoteFile(
                    id = id,
                    name = DriveRestClient.LISEUR_FOLDER_NAME,
                    mimeType = DriveRestClient.FOLDER_MIME_TYPE,
                    appProperties = mapOf("liseur" to "1", "kind" to DriveFile.KIND_FOLDER, "schemaVersion" to "1"),
                    createdTime = "2026-04-01T00:00:00.000Z",
                )
                files[id] = StoredFile(meta, ByteArray(0), DriveRestClient.SPACE_DRIVE)
                changeLog.add(DriveChange(id, false, meta))
                return LiseurFolderResolution(meta, listOf(meta))
            }

            override suspend fun listDriveEpubFiles(accessToken: String): List<DriveRemoteFile> =
                files.values
                    .filter {
                        it.space == DriveRestClient.SPACE_DRIVE &&
                            !it.meta.trashed &&
                            it.meta.appProperties["liseurKind"] == DriveFile.KIND_EPUB
                    }
                    .map { it.meta }

            override suspend fun getStartPageToken(accessToken: String): String = changeLog.size.toString()

            override suspend fun listChanges(
                accessToken: String,
                pageToken: String,
                pageSize: Int,
            ): DriveChangesPage {
                val idx = pageToken.toIntOrNull()
                    ?: throw DriveInvalidPageTokenException("invalid token", 400)
                val visible = mutableListOf<DriveChange>()
                var firstHiddenIdx: Int? = null
                for (i in idx until changeLog.size) {
                    val change = changeLog[i]
                    val file = change.file
                    if (file == null || isVisibleTo(callerDeviceId, file)) {
                        visible.add(change)
                    } else if (firstHiddenIdx == null) {
                        firstHiddenIdx = i
                    }
                }
                return DriveChangesPage(
                    changes = visible,
                    nextPageToken = null,
                    newStartPageToken = (firstHiddenIdx ?: changeLog.size).toString(),
                )
            }

            override suspend fun listAppDataFiles(
                accessToken: String,
                query: String?,
                pageSize: Int,
            ): List<DriveRemoteFile> {
                if (failSnapshotListing && query?.contains(DriveFile.KIND_SNAPSHOT) == true) {
                    throw java.io.IOException("injected snapshot listing failure")
                }
                return files.values
                    .filter { it.space == DriveRestClient.SPACE_APPDATA && !it.meta.trashed && isVisibleTo(callerDeviceId, it.meta) }
                    .map { it.meta }
            }

            override suspend fun findAppDataFileByBatchId(
                accessToken: String,
                batchId: String,
            ): DriveRemoteFile? = files.values.firstOrNull {
                it.space == DriveRestClient.SPACE_APPDATA &&
                    !it.meta.trashed &&
                    it.meta.appProperties["batchId"] == batchId
            }?.meta

            override suspend fun downloadMedia(accessToken: String, fileId: String): ByteArray {
                downloadedFileIds.add(fileId)
                staleOnceBytes.remove(fileId)?.let { return it }
                val stored = files[fileId] ?: throw DriveNotFoundException("Missing $fileId", fileId)
                if (stored.meta.trashed) throw DriveNotFoundException("Trashed $fileId", fileId)
                return stored.bytes.copyOf()
            }

            override suspend fun uploadAppDataJsonBatch(
                accessToken: String,
                name: String,
                jsonPayload: ByteArray,
                appProperties: Map<String, String>,
            ): DriveRemoteFile {
                val batchId = appProperties["batchId"]
                if (batchId != null) {
                    findAppDataFileByBatchId(accessToken, batchId)?.let { return it }
                }
                return createAppDataFile(accessToken, name, "application/json", jsonPayload, appProperties)
            }

            override suspend fun createAppDataFile(
                accessToken: String,
                name: String,
                mimeType: String,
                content: ByteArray,
                appProperties: Map<String, String>,
            ): DriveRemoteFile {
                val id = "appdata-${idGen.getAndIncrement()}"
                val meta = DriveRemoteFile(
                    id = id,
                    name = name,
                    mimeType = mimeType,
                    parents = listOf(DriveRestClient.SPACE_APPDATA),
                    appProperties = appProperties,
                    size = content.size.toLong(),
                )
                files[id] = StoredFile(meta, content.copyOf(), DriveRestClient.SPACE_APPDATA)
                changeLog.add(DriveChange(id, false, meta))
                maybeCrashAfterSnapshotManifestWrite(appProperties, content)
                return meta
            }

            override suspend fun updateFileMedia(
                accessToken: String,
                fileId: String,
                mimeType: String,
                content: ByteArray,
                appProperties: Map<String, String>?,
            ): DriveRemoteFile {
                val existing = files[fileId] ?: throw DriveNotFoundException("Missing $fileId", fileId)
                val effectiveProps = appProperties ?: existing.meta.appProperties
                val updatedMeta = existing.meta.copy(
                    mimeType = mimeType,
                    appProperties = effectiveProps,
                    size = content.size.toLong(),
                )
                files[fileId] = existing.copy(meta = updatedMeta, bytes = content.copyOf())
                changeLog.add(DriveChange(fileId, false, updatedMeta))
                maybeCrashAfterSnapshotManifestWrite(effectiveProps, content)
                return updatedMeta
            }

            private fun maybeCrashAfterSnapshotManifestWrite(
                props: Map<String, String>,
                content: ByteArray,
            ) {
                val armedSeq = armSnapshotListingFailureAboveSeq
                if (armedSeq != null && props["kind"] == DriveFile.KIND_MANIFEST) {
                    val published = runCatching { DriveWireCodec.decodeManifest(content.toString(Charsets.UTF_8)) }.getOrNull()
                    if ((published?.snapshot?.throughSeq ?: 0L) > armedSeq) failSnapshotListing = true
                }
                val targetSeq = failAfterSnapshotManifestThroughSeq ?: return
                if (props["kind"] != DriveFile.KIND_MANIFEST) return
                val decoded = runCatching { DriveWireCodec.decodeManifest(content.toString(Charsets.UTF_8)) }.getOrNull()
                if (decoded?.snapshot?.throughSeq == targetSeq) {
                    failAfterSnapshotManifestThroughSeq = null
                    throw java.io.IOException("Simulated crash between remote manifest publication and local state commit")
                }
            }

            override suspend fun deleteFile(accessToken: String, fileId: String) {
                if (remainingDeleteFailures > 0) {
                    remainingDeleteFailures--
                    throw DriveHttpException("Transient 503 during delete", 503)
                }
                val removed = files.remove(fileId) ?: throw DriveNotFoundException("Missing $fileId", fileId)
                changeLog.add(DriveChange(fileId, removed = true, file = null))
                removed.meta.id
            }

            override suspend fun trashFile(accessToken: String, fileId: String): DriveRemoteFile {
                val existing = files[fileId] ?: throw DriveNotFoundException("Missing $fileId", fileId)
                val trashed = existing.meta.copy(trashed = true)
                files[fileId] = existing.copy(meta = trashed)
                changeLog.add(DriveChange(fileId, removed = false, file = trashed))
                return trashed
            }

            override suspend fun initiateResumableUpload(
                accessToken: String,
                name: String,
                mimeType: String,
                totalBytes: Long,
                parents: List<String>,
                appProperties: Map<String, String>,
            ): String = throw UnsupportedOperationException()

            override suspend fun queryResumableUploadOffset(
                accessToken: String,
                sessionUri: String,
                totalBytes: Long,
            ): ResumableUploadStatus = throw UnsupportedOperationException()

            override suspend fun uploadResumableChunk(
                accessToken: String,
                sessionUri: String,
                chunk: ByteArray,
                offset: Long,
                totalBytes: Long,
                mimeType: String,
            ): ResumableUploadStatus = throw UnsupportedOperationException()

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
                val bytes = openStream().use { it.readBytes() }
                val isAppData = DriveRestClient.SPACE_APPDATA in parents
                if (isAppData && appProperties["kind"] == DriveFile.KIND_SNAPSHOT) {
                    snapshotUploadAttemptCounter++
                    if (failSnapshotUploadOnNthCall != null && snapshotUploadAttemptCounter == failSnapshotUploadOnNthCall) {
                        throw java.io.IOException("Simulated mid-multipart snapshot upload failure at call $snapshotUploadAttemptCounter")
                    }
                    snapshotUploadCount++
                }
                val space = if (isAppData) DriveRestClient.SPACE_APPDATA else DriveRestClient.SPACE_DRIVE
                val prefix = if (isAppData) "snap" else "epub"
                val id = "$prefix-${idGen.getAndIncrement()}"
                val meta = DriveRemoteFile(
                    id = id,
                    name = name,
                    mimeType = mimeType,
                    parents = parents,
                    appProperties = appProperties,
                    size = bytes.size.toLong(),
                )
                files[id] = StoredFile(meta, bytes, space)
                changeLog.add(DriveChange(id, false, meta))
                return meta
            }

            override suspend fun downloadResumable(
                accessToken: String,
                fileId: String,
                destination: OutputStream,
                startOffset: Long,
                onRestartFromZero: () -> Unit,
                onProgress: (Long) -> Unit,
            ): DriveDownloadResult {
                val stored = files[fileId] ?: throw DriveNotFoundException("Missing $fileId", fileId)
                if (stored.meta.trashed) throw DriveNotFoundException("Trashed $fileId", fileId)
                val slice = stored.bytes.copyOfRange(startOffset.toInt(), stored.bytes.size)
                destination.write(slice)
                onProgress(stored.bytes.size.toLong())
                return DriveDownloadResult(
                    bytesWritten = slice.size.toLong(),
                    totalBytes = stored.bytes.size.toLong(),
                    restartedFromZero = false,
                )
            }
        }
    }

    private companion object {
        const val PERM_ID = "perm-phase6-account"
    }
}
