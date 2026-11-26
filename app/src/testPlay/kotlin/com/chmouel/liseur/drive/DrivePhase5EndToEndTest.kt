package com.chmouel.liseur.drive

import android.app.Application
import android.content.Intent
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.AnnotationSync
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.BookEvaluation
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.BookMetadata
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.liseursync.SessionUploads
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.BookFingerprints
import com.chmouel.liseur.domain.Identifier
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.StatsBook
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.domain.VocabularyMerge
import com.chmouel.liseur.domain.readingStats
import com.chmouel.liseur.sync.LibrarySyncSwitchCoordinator
import com.chmouel.liseur.vocabulary.Recall
import com.chmouel.liseur.vocabulary.VocabularyLookup
import com.chmouel.liseur.vocabulary.VocabularyOccurrence
import com.chmouel.liseur.vocabulary.VocabularyRepository
import com.chmouel.liseur.vocabulary.VocabularyReview
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
import org.json.JSONObject
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

/**
 * Deterministic two-device end-to-end verification suite for Phase 5 Google Drive sync:
 * - Vocabulary words, occurrences, legacy SRS `baseline`, and `VocabularyMerge.reconcileSchedule`
 * - Concurrent word metadata edits, provisional/canonical occurrence dedupe, retired `ownerAccount`
 * - Word `del` on untouched copy (silent) vs. concurrent edit or review (add-wins silently)
 * - Event-ID-deduplicated review history, duplicate/reordered replay idempotency,
 *   out-of-order staged review attachment, legacy null-`syncId` backfill, and final undo tombstones
 * - Reading sessions: deterministic content-derived `remote_session_id`, per-account
 *   `drive_session_ack` eligibility (Account A -> Account B seeding & backup restore),
 *   `INSERT OR IGNORE` duplicate/crash replay idempotency, `liseur-sync` upload exclusion,
 *   and identical local cross-device statistics on both devices
 * - Seamless upgrade unpausing of pre-existing Phase 4 paused Phase 5 cursors
 */
@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DrivePhase5EndToEndTest {

    private lateinit var context: Application
    private lateinit var serverA: Phase5FakeDriveServer
    private lateinit var deviceA: Phase5Device
    private lateinit var deviceB: Phase5Device
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "drive-p5-test-${System.nanoTime()}").apply { mkdirs() }
        serverA = Phase5FakeDriveServer()
        deviceA = createDevice("dev-a", File(tempDir, "a"), serverA)
        deviceB = createDevice("dev-b", File(tempDir, "b"), serverA)
    }

    @After
    fun tearDown() {
        deviceA.close()
        deviceB.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `vocabulary words, occurrences, legacy baseline, and retired ownerAccount sync and merge cleanly`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        deviceB.connect(PERM_A, "a@example.com")
        val bookA = seedBook(deviceA, "prince.epub", "9780156012195", "Le Petit Prince", "Antoine de Saint-Exupery")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // 1. Seed a word with legacy pre-event-log baseline + retired liseur-sync ownerAccount + book occurrence.
        val addedAt = 1_745_000_000_000L
        val baselineReviewedAt = addedAt + 60_000L
        val baselineDueAt = baselineReviewedAt + 86_400_000L
        val wordIdA = deviceA.liseurDb.vocabularyDao().insertWord(
            VocabularyWord(
                word = "éphémère",
                normalized = "ephemere",
                language = "fr",
                definition = "short-lived",
                translationLanguage = "en",
                notes = "Chapter 3 note",
                addedAt = addedAt,
                dueAt = baselineDueAt,
                reviewedAt = baselineReviewedAt,
                streak = 2,
                intervalMinutes = 1440,
                repetitions = 2,
                definitionEdited = true,
                ownerAccount = "liseursync|https://old.example|acct-1",
                definitionUpdatedAt = addedAt + 10L,
                notesUpdatedAt = addedAt + 20L,
                baselineDueAt = baselineDueAt,
                baselineReviewedAt = baselineReviewedAt,
                baselineStreak = 2,
                baselineIntervalMinutes = 1440,
                baselineRepetitions = 2,
            ),
        )
        deviceA.liseurDb.vocabularyDao().insertOccurrence(
            VocabularyOccurrence(
                wordId = wordIdA,
                bookUrl = bookA.url,
                bookTitle = "Le Petit Prince",
                locationKey = "ch03#p12",
                locator = """{"href":"ch03.xhtml","locations":{"progression":0.25}}""",
                sentence = "Cette fleur est éphémère.",
                lastSeenAt = addedAt + 30L,
                encounters = 2,
            ),
        )

        // Sync A -> B.
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        val wordB = deviceB.liseurDb.vocabularyDao().findWord("ephemere", "fr")
        assertNotNull(wordB)
        assertEquals("éphémère", wordB!!.word)
        assertEquals("short-lived", wordB.definition)
        assertEquals("Chapter 3 note", wordB.notes)
        assertEquals("", wordB.ownerAccount) // newly pulled words insert with ownerAccount = ""
        // Baseline + derived schedule match exactly!
        assertEquals(baselineDueAt, wordB.dueAt)
        assertEquals(baselineReviewedAt, wordB.reviewedAt)
        assertEquals(2, wordB.streak)
        assertEquals(1440, wordB.intervalMinutes)
        assertEquals(2, wordB.repetitions)
        assertEquals(baselineDueAt, wordB.baselineDueAt)
        assertEquals(2, wordB.baselineRepetitions)

        val occsB = deviceB.liseurDb.vocabularyDao().occurrencesList(wordB.id)
        assertEquals(1, occsB.size)
        assertEquals("isbn:9780156012195", occsB.single().workId)
        assertEquals(
            VocabularyMerge.canonicalOccurrenceKey("isbn:9780156012195", "ch03#p12"),
            occsB.single().occurrenceKey,
        )
        assertEquals("Cette fleur est éphémère.", occsB.single().sentence)
        assertEquals(2, occsB.single().encounters)

        // 2. Concurrent edits: A edits definition while B edits notes offline -> 3-way merge converges!
        val repoA = VocabularyRepository(deviceA.liseurDb)
        val repoB = VocabularyRepository(deviceB.liseurDb)
        repoA.edit(wordIdA, definition = "fleeting, transitory", notes = "Chapter 3 note", now = addedAt + 100L)
        repoB.edit(wordB.id, definition = "short-lived", notes = "Updated note from tablet", now = addedAt + 110L)

        serverA.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        serverA.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        serverA.unhideAll()

        // Pull + republish merge -> both converge with BOTH edits preserved!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        val convergedA = deviceA.liseurDb.vocabularyDao().findWord("ephemere", "fr")!!
        val convergedB = deviceB.liseurDb.vocabularyDao().findWord("ephemere", "fr")!!
        assertEquals("fleeting, transitory", convergedA.definition)
        assertEquals("Updated note from tablet", convergedA.notes)
        assertEquals(convergedA.definition, convergedB.definition)
        assertEquals(convergedA.notes, convergedB.notes)
    }

    @Test
    fun `vocabulary reviews dedupe by syncId across duplicate and reordered replays, stage out of order, and honor final undo tombstones`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        deviceB.connect(PERM_A, "a@example.com")

        val baseTime = 1_745_100_000_000L
        val repoA = VocabularyRepository(deviceA.liseurDb)
        val repoB = VocabularyRepository(deviceB.liseurDb)

        val wordIdA = repoA.collect(
            lookup = VocabularyLookup("lumière", "fr", null),
            definition = "light",
            translationLanguage = "en",
            now = baseTime,
        )!!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // Record a legacy review with null syncId on A -> deterministic UUID assigned once before publish.
        val legacyReviewedAt = baseTime + 10_000L
        deviceA.liseurDb.vocabularyDao().addReview(
            VocabularyReview(
                wordId = wordIdA,
                rating = Recall.GOOD.name,
                reviewedAt = legacyReviewedAt,
                intervalMinutes = 60,
                dueAt = legacyReviewedAt + 3_600_000L,
                firstReview = true,
                syncId = null,
            ),
        )
        deviceA.driveSync.vocabularySync.reconcileWordSchedule(wordIdA)

        // Sync A -> B.
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        val expectedLegacyId = DriveVocabularySync.deterministicLegacyReviewId(
            language = "fr",
            normalized = "lumière",
            reviewedAt = legacyReviewedAt,
            rating = Recall.GOOD.name,
        )
        val wordB1 = deviceB.liseurDb.vocabularyDao().findWord("lumière", "fr")!!
        val reviewsB1 = deviceB.liseurDb.vocabularyDao().reviews(wordB1.id)
        assertEquals(1, reviewsB1.size)
        assertEquals(expectedLegacyId, reviewsB1.single().syncId)
        assertEquals(1, wordB1.repetitions)
        assertEquals(1, wordB1.streak)
        assertTrue(reviewsB1.single().firstReview)

        // Duplicate replay: reset B's vocabReview cursor to 0 and re-sync -> schedule MUST NOT advance twice!
        deviceB.driveDb.cursors().upsert(
            DriveCursor(PERM_A, "dev-a", DriveWireCodec.DOMAIN_VOCAB_REVIEW, appliedSeq = 0L),
        )
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val wordBAfterDup = deviceB.liseurDb.vocabularyDao().findWord("lumière", "fr")!!
        assertEquals(1, deviceB.liseurDb.vocabularyDao().reviews(wordBAfterDup.id).size)
        assertEquals(wordB1.repetitions, wordBAfterDup.repetitions)
        assertEquals(wordB1.streak, wordBAfterDup.streak)
        assertEquals(wordB1.dueAt, wordBAfterDup.dueAt)
        assertEquals(wordB1.intervalMinutes, wordBAfterDup.intervalMinutes)

        // Concurrent reviews on A and B -> union by syncId and converge identically!
        val reviewTimeA = wordB1.dueAt + 5_000L
        val reviewTimeB = wordB1.dueAt + 15_000L
        val undoA = repoA.review(
            card = deviceA.liseurDb.vocabularyDao().word(wordIdA)!!,
            rating = Recall.GOOD,
            newLimit = 0,
            reviewLimit = 0,
            now = reviewTimeA,
        )!!
        repoB.review(
            card = deviceB.liseurDb.vocabularyDao().word(wordB1.id)!!,
            rating = Recall.EASY,
            newLimit = 0,
            reviewLimit = 0,
            now = reviewTimeB,
        )!!

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        val convergedA = deviceA.liseurDb.vocabularyDao().findWord("lumière", "fr")!!
        val convergedB = deviceB.liseurDb.vocabularyDao().findWord("lumière", "fr")!!
        assertEquals(3, convergedA.repetitions)
        assertEquals(convergedA.repetitions, convergedB.repetitions)
        assertEquals(convergedA.streak, convergedB.streak)
        assertEquals(convergedA.dueAt, convergedB.dueAt)
        assertEquals(convergedA.intervalMinutes, convergedB.intervalMinutes)
        assertEquals(3, deviceA.liseurDb.vocabularyDao().reviews(convergedA.id).size)
        assertEquals(3, deviceB.liseurDb.vocabularyDao().reviews(convergedB.id).size)

        // Undo a review on B (`op: "del"` final tombstone): add one more review on B and undo it after syncing!
        val reviewToUndoTime = convergedB.dueAt + 10_000L
        val undoable = repoB.review(
            card = deviceB.liseurDb.vocabularyDao().word(convergedB.id)!!,
            rating = Recall.HARD,
            newLimit = 0,
            reviewLimit = 0,
            now = reviewToUndoTime,
        )!!
        val undoneSyncId = deviceB.liseurDb.vocabularyDao().review(undoable.reviewId)!!.syncId!!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(4, deviceA.liseurDb.vocabularyDao().reviews(convergedA.id).size)

        // Now undo on B and sync -> final tombstone removes it on A and recomputes schedule!
        assertTrue(repoB.undoReview(undoable))
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        val afterUndoA = deviceA.liseurDb.vocabularyDao().findWord("lumière", "fr")!!
        val afterUndoB = deviceB.liseurDb.vocabularyDao().findWord("lumière", "fr")!!
        assertEquals(3, afterUndoA.repetitions)
        assertEquals(afterUndoB.repetitions, afterUndoA.repetitions)
        assertEquals(afterUndoB.streak, afterUndoA.streak)
        assertEquals(afterUndoB.dueAt, afterUndoA.dueAt)
        assertNull(deviceA.liseurDb.vocabularyDao().reviewBySyncIdAnyOwner(undoneSyncId))

        // Replaying the undone review's old `put` MUST be ignored permanently!
        val replayPut = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_VOCAB_REVIEW,
            key = undoneSyncId,
            hlc = "0017459000000-0001-dev-b",
            bodyMap = DriveWireCodec.encodeVocabReviewBody(
                DriveVocabReviewBody(
                    syncId = undoneSyncId,
                    wordKey = DriveVocabularySync.wordKey("fr", "lumière"),
                    language = "fr",
                    normalized = "lumière",
                    rating = Recall.HARD.name,
                    reviewedAt = reviewToUndoTime,
                    intervalMinutes = 60,
                    dueAt = reviewToUndoTime + 3_600_000L,
                ),
            ),
        )
        deviceA.driveSync.vocabularySync.applyRemoteReview(PERM_A, replayPut, "dev-b", 99L)
        assertNull(deviceA.liseurDb.vocabularyDao().reviewBySyncIdAnyOwner(undoneSyncId))
        assertEquals(3, deviceA.liseurDb.vocabularyDao().findWord("lumière", "fr")!!.repetitions)

        // Out-of-order arrival: review arrives BEFORE parent vocabWord -> staged, then attached when word arrives!
        val stagedReviewId = "staged-review-uuid-1"
        val outOfOrderReview = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_VOCAB_REVIEW,
            key = stagedReviewId,
            hlc = "0017459000001-0001-dev-c",
            bodyMap = DriveWireCodec.encodeVocabReviewBody(
                DriveVocabReviewBody(
                    syncId = stagedReviewId,
                    wordKey = DriveVocabularySync.wordKey("es", "alba"),
                    language = "es",
                    normalized = "alba",
                    rating = Recall.GOOD.name,
                    reviewedAt = baseTime + 50_000L,
                    intervalMinutes = 60,
                    dueAt = baseTime + 3_650_000L,
                ),
            ),
        )
        deviceB.driveSync.vocabularySync.applyRemoteReview(PERM_A, outOfOrderReview, "dev-c", 1L)
        assertNull(deviceB.liseurDb.vocabularyDao().findWord("alba", "es"))
        assertEquals(
            1,
            deviceB.liseurDb.vocabularySyncDao()
                .stagedForWord(DriveLibrarySync.accountPeerId(PERM_A), "es", "alba", 0L).size,
        )

        val delayedWord = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
            key = DriveVocabularySync.wordKey("es", "alba"),
            hlc = "0017459000002-0001-dev-c",
            bodyMap = DriveWireCodec.encodeVocabWordBody(
                DriveVocabWordBody(
                    language = "es",
                    normalized = "alba",
                    word = "alba",
                    definition = "dawn",
                    translationLanguage = "en",
                    notes = "",
                    definitionEdited = false,
                    definitionUpdatedAt = baseTime,
                    notesUpdatedAt = 0L,
                    addedAt = baseTime,
                    baseline = null,
                ),
            ),
        )
        deviceB.driveSync.vocabularySync.applyRemoteWord(PERM_A, delayedWord, "dev-c", 2L)
        val attachedWord = deviceB.liseurDb.vocabularyDao().findWord("alba", "es")!!
        assertEquals(1, attachedWord.repetitions)
        assertEquals(1, attachedWord.streak)
        assertEquals(1, deviceB.liseurDb.vocabularyDao().reviews(attachedWord.id).size)
        assertTrue(deviceB.liseurDb.vocabularyDao().reviews(attachedWord.id).single().firstReview)
        assertTrue(
            deviceB.liseurDb.vocabularySyncDao()
                .stagedForWord(DriveLibrarySync.accountPeerId(PERM_A), "es", "alba", 0L).isEmpty(),
        )
    }

    @Test
    fun `vocabulary word delete is silent on untouched copy and add-wins on concurrent edit or review`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        deviceB.connect(PERM_A, "a@example.com")
        val repoA = VocabularyRepository(deviceA.liseurDb)
        val repoB = VocabularyRepository(deviceB.liseurDb)
        val now = 1_745_200_000_000L

        val untouchedIdA = repoA.collect(VocabularyLookup("ombre", "fr", null), "shadow", "en", now)!!
        val editedIdA = repoA.collect(VocabularyLookup("soleil", "fr", null), "sun", "en", now)!!
        val reviewedIdA = repoA.collect(VocabularyLookup("lune", "fr", null), "moon", "en", now)!!

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // 1. Stale delete on untouched copy ("ombre") -> silent delete on B!
        repoA.deleteWord(untouchedIdA)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertNull(deviceB.liseurDb.vocabularyDao().findWord("ombre", "fr"))

        // 2. Concurrent delete on A vs. edit on B ("soleil") -> add-wins silently!
        val soleilB = deviceB.liseurDb.vocabularyDao().findWord("soleil", "fr")!!
        repoA.deleteWord(editedIdA)
        repoB.edit(soleilB.id, definition = "bright sun", notes = "keep me", now = now + 500L)

        serverA.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        serverA.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        serverA.unhideAll()

        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        val keptA = deviceA.liseurDb.vocabularyDao().findWord("soleil", "fr")
        val keptB = deviceB.liseurDb.vocabularyDao().findWord("soleil", "fr")
        assertNotNull("Add-wins must resurrect soleil on deleter A", keptA)
        assertNotNull(keptB)
        assertEquals("bright sun", keptA!!.definition)
        assertEquals("keep me", keptA.notes)
        assertEquals(keptA.definition, keptB!!.definition)

        // 3. Concurrent delete on A vs. live review on B ("lune"), BOTH uploaded before pulling -> add-wins silently!
        val luneB = deviceB.liseurDb.vocabularyDao().findWord("lune", "fr")!!
        repoA.deleteWord(reviewedIdA)
        repoB.review(luneB, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = now + 1_000L)!!

        serverA.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        serverA.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        serverA.unhideAll()

        // Pull + republish -> both devices converge with "lune" alive AND its review preserved!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        val luneResA = deviceA.liseurDb.vocabularyDao().findWord("lune", "fr")
        val luneResB = deviceB.liseurDb.vocabularyDao().findWord("lune", "fr")
        assertNotNull("Add-wins must resurrect lune on deleter A after concurrent review", luneResA)
        assertNotNull(luneResB)
        assertEquals(1, luneResA!!.repetitions)
        assertEquals(1, luneResB!!.repetitions)
        assertEquals(luneResB.dueAt, luneResA.dueAt)
    }

    @Test
    fun `reading sessions sync idempotently, exclude pulled rows from liseur-sync, and produce identical cross-device stats`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        deviceB.connect(PERM_A, "a@example.com")

        val bookA = seedBook(deviceA, "dune.epub", "9780441172719", "Dune", "Frank Herbert")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val stubUrlB = "drive:isbn:9780441172719"

        val dayStart = Instant.parse("2026-04-10T10:00:00Z").toEpochMilli()
        // Closed eligible session on A.
        val sessIdA1 = deviceA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = bookA.url,
                startedAt = dayStart,
                endedAt = dayStart + 1_800_000L,
                lastCheckpointAt = dayStart + 1_800_000L,
                durationMs = 1_750_000L,
                startProgression = 0.10,
                endProgression = 0.25,
                idleMs = 50_000L,
            ),
        )
        // Ineligible sessions on A that MUST NOT upload:
        // (a) open session (ended_at == null)
        deviceA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = bookA.url,
                startedAt = dayStart + 2_000_000L,
                endedAt = null,
                lastCheckpointAt = dayStart + 2_100_000L,
                durationMs = 100_000L,
                startProgression = 0.25,
                endProgression = 0.26,
            ),
        )
        // (b) imported backup session (imported_at != null)
        deviceA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = bookA.url,
                startedAt = dayStart - 3_600_000L,
                endedAt = dayStart - 3_000_000L,
                lastCheckpointAt = dayStart - 3_000_000L,
                durationMs = 600_000L,
                startProgression = 0.0,
                endProgression = 0.05,
                importedAt = dayStart - 1_000L,
            ),
        )
        // (c) progression-less session
        deviceA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = bookA.url,
                startedAt = dayStart - 7_200_000L,
                endedAt = dayStart - 7_000_000L,
                lastCheckpointAt = dayStart - 7_000_000L,
                durationMs = 200_000L,
                startProgression = null,
                endProgression = null,
            ),
        )

        // Closed eligible session on B.
        deviceB.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = stubUrlB,
                startedAt = dayStart + 4_000_000L,
                endedAt = dayStart + 5_200_000L,
                lastCheckpointAt = dayStart + 5_200_000L,
                durationMs = 1_100_000L,
                startProgression = 0.25,
                endProgression = 0.40,
                idleMs = 100_000L,
            ),
        )

        // Sync both ways.
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // Verify deterministic remote_session_id persisted on A's local session.
        val expectedIdA = DriveSessionSync.deterministicSessionId(
            workKey = "isbn:9780441172719",
            startedAt = dayStart,
            endedAt = dayStart + 1_800_000L,
            durationMs = 1_750_000L,
            startProgression = 0.10,
            endProgression = 0.25,
        )
        val localRowA = deviceA.liseurDb.readingSessionDao().get(sessIdA1)!!
        assertEquals(expectedIdA, localRowA.remoteSessionId)
        assertNull(localRowA.originDeviceId)

        // Device B received ONLY A's eligible session (with origin_device_id = "dev-a", imported_at = null).
        val sessionsOnB = deviceB.liseurDb.readingSessionDao().allOnce()
        assertEquals(2, sessionsOnB.size)
        val pulledOnB = sessionsOnB.single { it.remoteSessionId == expectedIdA }
        assertEquals("dev-a", pulledOnB.originDeviceId)
        assertNull(pulledOnB.importedAt)

        // Pulled remote session is NEVER uploaded to liseur-sync (`awaitingUpload` / `awaitingUploadTo` / `SessionUploads.toJson`).
        val awaitingOnB = deviceB.liseurDb.readingSessionDao().awaitingUpload(100)
        assertEquals(1, awaitingOnB.size)
        assertNull(awaitingOnB.single().originDeviceId)
        assertNull(SessionUploads.toJson(pulledOnB, "dev-b", "work-1", null))

        // Duplicate / Tx1-Tx2 crash replay: rewind B's session cursor to 0 and re-sync -> 0 duplicate rows!
        deviceB.driveDb.cursors().upsert(
            DriveCursor(PERM_A, "dev-a", DriveWireCodec.DOMAIN_SESSION, appliedSeq = 0L),
        )
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(2, deviceB.liseurDb.readingSessionDao().allOnce().size)

        // Local cross-device statistics agree identically on both devices for the combined synced sessions!
        val syncedSessionsA = deviceA.liseurDb.readingSessionDao().allOnce()
            .filter { it.endedAt != null && it.importedAt == null && it.startProgression != null }
        val syncedSessionsB = deviceB.liseurDb.readingSessionDao().allOnce()
        assertEquals(2, syncedSessionsA.size)
        assertEquals(2, syncedSessionsB.size)
        assertEquals(2_850_000L, syncedSessionsA.sumOf { it.durationMs })
        assertEquals(2_850_000L, syncedSessionsB.sumOf { it.durationMs })
        assertEquals(2_850_000L, deviceB.liseurDb.readingSessionDao().observeTotalDuration(stubUrlB).first())

        val toSpans: (List<ReadingSession>) -> List<SessionSpan> = { list ->
            list.map {
                SessionSpan(
                    bookUrl = it.bookUrl,
                    startedAt = it.startedAt,
                    durationMs = it.durationMs,
                    lastReadAt = it.endedAt ?: it.lastCheckpointAt,
                    startProgression = it.startProgression,
                    endProgression = it.endProgression,
                )
            }
        }
        val today = Instant.ofEpochMilli(dayStart).atZone(ZoneOffset.UTC).toLocalDate()
        val statsA = readingStats(
            sessions = toSpans(syncedSessionsA),
            books = mapOf(bookA.url to StatsBook(bookA.url, "Dune", "Frank Herbert", 0.4, false, null, null)),
            zone = ZoneOffset.UTC,
            today = today,
            range = StatsRange.ALL_TIME,
            weekStart = DayOfWeek.MONDAY,
        )
        val statsB = readingStats(
            sessions = toSpans(syncedSessionsB),
            books = mapOf(stubUrlB to StatsBook(stubUrlB, "Dune", "Frank Herbert", 0.4, false, null, null)),
            zone = ZoneOffset.UTC,
            today = today,
            range = StatsRange.ALL_TIME,
            weekStart = DayOfWeek.MONDAY,
        )
        assertEquals(2_850_000L, statsA.totalMs)
        assertEquals(statsA.totalMs, statsB.totalMs)
        assertEquals(statsA.sessions, statsB.sessions)
        assertEquals(2, statsA.sessions)
        assertEquals(statsA.books.single().totalMs, statsB.books.single().totalMs)
        assertEquals(statsA.books.single().sessions, statsB.books.single().sessions)
    }

    @Test
    fun `switching to Account B seeds all eligible local sessions via missing drive_session_ack despite existing remote_session_id`() = runTest {
        val serverB = Phase5FakeDriveServer(PERM_B, "b@example.com")
        val deviceOnB = createDevice("dev-b-acct", File(tempDir, "b-acct"), serverB)
        try {
            // 1. Device A syncs local session to Account A -> assigns remote_session_id and records drive_session_ack(PERM_A).
            deviceA.connect(PERM_A, "a@example.com")
            val bookA = seedBook(deviceA, "hobbit.epub", "9780547928227", "The Hobbit", "J.R.R. Tolkien")
            val started = 1_745_300_000_000L
            val sessRowId = deviceA.liseurDb.readingSessionDao().insert(
                ReadingSession(
                    bookUrl = bookA.url,
                    startedAt = started,
                    endedAt = started + 900_000L,
                    lastCheckpointAt = started + 900_000L,
                    durationMs = 850_000L,
                    startProgression = 0.0,
                    endProgression = 0.15,
                    idleMs = 50_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            val assignedId = deviceA.liseurDb.readingSessionDao().get(sessRowId)!!.remoteSessionId
            assertNotNull(assignedId)
            assertTrue(deviceA.driveDb.sessionAcks().isAcked(PERM_A, assignedId!!))

            // 2. Switch Device A to Account B (keeping Account A paused; Account B has empty drive_session_ack).
            deviceA.driveSync.deactivate()
            val switchedDriveSync = DriveLibrarySync(
                context = context,
                database = deviceA.liseurDb,
                remoteAccount = RemoteAccountRepository(
                    dao = deviceA.liseurDb.remoteServerDao(),
                    bookDao = deviceA.liseurDb.bookDao(),
                    progressDao = deviceA.liseurDb.readingProgressDao(),
                    seriesExtraDao = deviceA.liseurDb.seriesExtraDao(),
                    bookRemoval = BookRemoval(
                        bookDao = deviceA.liseurDb.bookDao(),
                        sessionDao = deviceA.liseurDb.readingSessionDao(),
                        peerStateDao = deviceA.liseurDb.syncPeerStateDao(),
                        identityDao = deviceA.liseurDb.workIdentityDao(),
                        progressDao = deviceA.liseurDb.readingProgressDao(),
                        annotationDao = deviceA.liseurDb.annotationDao(),
                        annotationSyncDao = deviceA.liseurDb.annotationSyncDao(),
                        inTransaction = { work -> deviceA.liseurDb.withTransaction { work() } },
                        metadataDao = deviceA.liseurDb.bookMetadataDao(),
                        evaluationDao = deviceA.liseurDb.bookEvaluationDao(),
                    ),
                    inTransaction = { work -> deviceA.liseurDb.withTransaction { work() } },
                ),
                switchCoordinator = LibrarySyncSwitchCoordinator(),
                deviceIdGenerator = { "dev-a" },
                openStore = { deviceA.driveDb },
                authClient = alwaysAuthClient("token-b"),
                driveApi = serverB.clientFor("dev-a"),
            )
            switchedDriveSync.activateAccount(PERM_B, "b@example.com", now = 2_000L)
            assertEquals(SyncOutcome.Success, switchedDriveSync.changeEngine.sync().outcome)

            // Account B received the already-identified local session cleanly!
            assertTrue(deviceA.driveDb.sessionAcks().isAcked(PERM_B, assignedId))

            // Second device connected to Account B pulls the session with identical ID & duration!
            deviceOnB.connect(PERM_B, "b@example.com")
            assertEquals(SyncOutcome.Success, deviceOnB.changeEngine.sync().outcome)
            val pulledOnB = deviceOnB.liseurDb.readingSessionDao().byRemoteSessionId(assignedId)
            assertNotNull(pulledOnB)
            assertEquals(850_000L, pulledOnB!!.durationMs)
            assertEquals("dev-a", pulledOnB.originDeviceId)
        } finally {
            deviceOnB.close()
        }
    }

    @Test
    fun `backup restore without drive_sync_db deduplicates identical local sitting and prevents double counting`() = runTest {
        // Phone A records a local sitting and syncs to Drive.
        deviceA.connect(PERM_A, "a@example.com")
        val bookA = seedBook(deviceA, "foundation.epub", "9780553293357", "Foundation", "Isaac Asimov")
        val started = 1_745_400_000_000L
        deviceA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = bookA.url,
                startedAt = started,
                endedAt = started + 1_200_000L,
                lastCheckpointAt = started + 1_200_000L,
                durationMs = 1_200_000L,
                startProgression = 0.0,
                endProgression = 0.2,
                idleMs = 0L,
            ),
        )
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // Simulate Tablet B restored from Android Auto Backup of liseur.db BEFORE A's sync
        // (same local sitting with remote_session_id == NULL, brand-new drive_sync.db with deviceId = "dev-b").
        val bookB = seedBook(deviceB, "foundation.epub", "9780553293357", "Foundation", "Isaac Asimov")
        deviceB.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = bookB.url,
                startedAt = started,
                endedAt = started + 1_200_000L,
                lastCheckpointAt = started + 1_200_000L,
                durationMs = 1_200_000L,
                startProgression = 0.0,
                endProgression = 0.2,
                idleMs = 0L,
                remoteSessionId = null,
                originDeviceId = null,
            ),
        )
        deviceB.connect(PERM_A, "a@example.com")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // Pre-pull deterministic ID assignment prevents double counting on restore!
        val allOnB = deviceB.liseurDb.readingSessionDao().allOnce()
        assertEquals(1, allOnB.size)
        assertEquals(1_200_000L, allOnB.single().durationMs)
        assertNotNull(allOnB.single().remoteSessionId)
    }

    @Test
    fun `pre-existing paused Phase 5 cursors unpause cleanly on upgrade and replay skipped deltas`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val bookA = seedBook(deviceA, "upgrade.epub", "9780345391803", "Hitchhiker", "Douglas Adams")
        val repoA = VocabularyRepository(deviceA.liseurDb)
        repoA.collect(VocabularyLookup("galaxie", "fr", null), "galaxy", "en", 1_745_500_000_000L)
        deviceA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = bookA.url,
                startedAt = 1_745_500_000_000L,
                endedAt = 1_745_500_600_000L,
                lastCheckpointAt = 1_745_500_600_000L,
                durationMs = 600_000L,
                startProgression = 0.0,
                endProgression = 0.1,
                idleMs = 0L,
            ),
        )
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // Simulate Device B previously running Phase 4: catalog applied at seq 1,
        // while Phase 5 domains were paused at appliedSeq = 0, pausedSchemaVersion = 1.
        deviceB.connect(PERM_A, "a@example.com")
        deviceB.driveDb.cursors().upsert(DriveCursor(PERM_A, "dev-a", DriveWireCodec.DOMAIN_CATALOG, appliedSeq = 0L))
        for (domain in listOf(
            DriveWireCodec.DOMAIN_VOCAB_WORD,
            DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE,
            DriveWireCodec.DOMAIN_VOCAB_REVIEW,
            DriveWireCodec.DOMAIN_SESSION,
        )) {
            deviceB.driveDb.cursors().upsert(
                DriveCursor(PERM_A, "dev-a", domain, appliedSeq = 0L, pausedSchemaVersion = 1),
            )
        }

        // Phase 5 build syncs -> pauses clear automatically and skipped vocab + session records replay!
        val summary = deviceB.changeEngine.sync()
        assertEquals(SyncOutcome.Success, summary.outcome)
        assertFalse(summary.pausedBySchemaVersion)
        assertNotNull(deviceB.liseurDb.vocabularyDao().findWord("galaxie", "fr"))
        assertEquals(1, deviceB.liseurDb.readingSessionDao().allOnce().size)
        assertEquals(600_000L, deviceB.liseurDb.readingSessionDao().allOnce().single().durationMs)
        assertTrue(deviceB.driveDb.cursors().pausedForAccount(PERM_A).isEmpty())
    }

    @Test
    fun `deleting a word keeps occurrence state of a word whose key extends it`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val bookA = seedBook(deviceA, "prince-slash.epub", "9780156012196", "Le Petit Prince", "Antoine")
        val now = 1_745_000_000_000L
        val ids = listOf("and", "and/or").map { norm ->
            val id = deviceA.liseurDb.vocabularyDao().insertWord(
                VocabularyWord(word = norm, normalized = norm, language = "en", definition = "", translationLanguage = "en", addedAt = now, dueAt = now),
            )
            deviceA.liseurDb.vocabularyDao().insertOccurrence(
                VocabularyOccurrence(
                    wordId = id,
                    bookUrl = bookA.url,
                    bookTitle = "Le Petit Prince",
                    locationKey = "ch01#p1",
                    locator = """{"href":"ch01.xhtml"}""",
                    sentence = "Sentence with $norm.",
                    lastSeenAt = now,
                    encounters = 1,
                ),
            )
            id
        }
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val states = deviceA.driveDb.recordStates()
        val andKey = DriveVocabularySync.wordKey("en", "and")
        val andOrKey = DriveVocabularySync.wordKey("en", "and/or")
        suspend fun occKeys() = states.forDomain(PERM_A, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE).map { it.recordKey }
        assertEquals(2, occKeys().size)

        VocabularyRepository(deviceA.liseurDb).deleteWord(ids[0])
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        val remaining = occKeys()
        assertTrue("and/or occurrence state must survive: $remaining", remaining.any { it.startsWith("$andOrKey/") })
        assertTrue("and occurrence state must be removed: $remaining", remaining.none { it.startsWith("$andKey/") && !it.startsWith("$andOrKey/") })
    }

    @Test
    fun `occurrences arriving before their parent word are deferred and applied once the word arrives`() = runTest {
        val isolatedServer = Phase5FakeDriveServer()
        // Note device ID ordering: "dev-b-editor" sorts lexicographically BEFORE "dev-z-creator".
        val creatorA = createDevice("dev-z-creator", File(tempDir, "occ-a"), isolatedServer)
        val editorB = createDevice("dev-b-editor", File(tempDir, "occ-b"), isolatedServer)
        val freshC = createDevice("dev-c-fresh", File(tempDir, "occ-c"), isolatedServer)
        val staggeredD = createDevice("dev-d-staggered", File(tempDir, "occ-d"), isolatedServer)
        try {
            creatorA.connect(PERM_A, "a@example.com")
            editorB.connect(PERM_A, "a@example.com")

            val now = 1_745_600_000_000L
            val repoA = VocabularyRepository(creatorA.liseurDb)
            repoA.collect(VocabularyLookup("aurore", "fr", null), "dawn", "en", now)!!
            assertEquals(SyncOutcome.Success, creatorA.changeEngine.sync().outcome)

            // Editor B pulls the word from A, then publishes an occurrence-only delta batch.
            assertEquals(SyncOutcome.Success, editorB.changeEngine.sync().outcome)
            val wordOnB = editorB.liseurDb.vocabularyDao().findWord("aurore", "fr")!!
            editorB.liseurDb.vocabularyDao().insertOccurrence(
                VocabularyOccurrence(
                    wordId = wordOnB.id,
                    bookUrl = "file:///aurora.epub",
                    bookTitle = "L'Aurore",
                    locationKey = "ch01#p1",
                    locator = """{"href":"ch01.xhtml","locations":{"progression":0.05}}""",
                    sentence = "L'aurore se lève doucement.",
                    lastSeenAt = now + 500L,
                    encounters = 1,
                ),
            )
            assertEquals(SyncOutcome.Success, editorB.changeEngine.sync().outcome)

            // 1. Fresh receiver C sees both devices on initial sync: "dev-b-editor" is processed
            // BEFORE "dev-z-creator", defers vocabOccurrence, then replays it once A's word arrives.
            freshC.connect(PERM_A, "a@example.com")
            assertEquals(SyncOutcome.Success, freshC.changeEngine.sync().outcome)
            val wordOnC = freshC.liseurDb.vocabularyDao().findWord("aurore", "fr")
            assertNotNull(wordOnC)
            val occsOnC = freshC.liseurDb.vocabularyDao().occurrencesList(wordOnC!!.id)
            assertEquals(1, occsOnC.size)
            assertEquals("L'aurore se lève doucement.", occsOnC.single().sentence)
            assertEquals(
                1L,
                freshC.driveDb.cursors().get(PERM_A, "dev-b-editor", DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE)?.appliedSeq,
            )

            // 2. Staggered receiver D sees ONLY editor B's occurrence-only batch on sync 1:
            // cursor for vocabOccurrence must remain unadvanced (0), then apply once A's word arrives.
            staggeredD.connect(PERM_A, "a@example.com")
            isolatedServer.hideSourceDeviceFrom(sourceDeviceId = "dev-z-creator", viewerDeviceId = "dev-d-staggered")
            assertEquals(SyncOutcome.Success, staggeredD.changeEngine.sync().outcome)
            assertNull(staggeredD.liseurDb.vocabularyDao().findWord("aurore", "fr"))
            val deferredOccCursor = staggeredD.driveDb.cursors()
                .get(PERM_A, "dev-b-editor", DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE)
            assertEquals(0L, deferredOccCursor?.appliedSeq ?: 0L)

            isolatedServer.unhideAll()
            assertEquals(SyncOutcome.Success, staggeredD.changeEngine.sync(forceManifestRescan = true).outcome)
            val wordOnD = staggeredD.liseurDb.vocabularyDao().findWord("aurore", "fr")
            assertNotNull(wordOnD)
            val occsOnD = staggeredD.liseurDb.vocabularyDao().occurrencesList(wordOnD!!.id)
            assertEquals(1, occsOnD.size)
            assertEquals("L'aurore se lève doucement.", occsOnD.single().sentence)
            assertEquals(
                1L,
                staggeredD.driveDb.cursors().get(PERM_A, "dev-b-editor", DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE)?.appliedSeq,
            )
        } finally {
            creatorA.close()
            editorB.close()
            freshC.close()
            staggeredD.close()
        }
    }

    @Test
    fun `applying a remote occurrence keeps ids of unchanged local occurrences`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        deviceB.connect(PERM_A, "a@example.com")
        val now = 1_745_600_000_000L
        VocabularyRepository(deviceA.liseurDb).collect(VocabularyLookup("aurore", "fr", null), "dawn", "en", now)!!
        val wordA = deviceA.liseurDb.vocabularyDao().findWord("aurore", "fr")!!
        fun occ(wordId: Long, loc: String) = VocabularyOccurrence(
            wordId = wordId,
            bookUrl = "file:///aurora.epub",
            bookTitle = "L'Aurore",
            locationKey = loc,
            locator = null,
            sentence = "phrase $loc",
            lastSeenAt = now,
        )
        deviceA.liseurDb.vocabularyDao().insertOccurrence(occ(wordA.id, "ch01#p1"))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val wordB = deviceB.liseurDb.vocabularyDao().findWord("aurore", "fr")!!
        val before = deviceB.liseurDb.vocabularyDao().occurrencesList(wordB.id).single()

        deviceA.liseurDb.vocabularyDao().insertOccurrence(occ(wordA.id, "ch02#p1"))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        val after = deviceB.liseurDb.vocabularyDao().occurrencesList(wordB.id)
        assertEquals(2, after.size)
        assertEquals(before.id, after.single { it.locationKey == "ch01#p1" }.id)
    }

    @Test
    fun `coalescing duplicate word rows keeps ids of the survivor's occurrences`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val addedAt = 1_745_900_000_000L
        val dao = deviceA.liseurDb.vocabularyDao()
        fun word(owner: String) = VocabularyWord(
            word = "clarté", normalized = "clarté", language = "fr", definition = "clarity",
            translationLanguage = "en", notes = "", addedAt = addedAt, dueAt = addedAt, ownerAccount = owner,
        )
        val primaryId = dao.insertWord(word(""))
        val dupId = dao.insertWord(word("liseursync|https://old.example|acct-1"))
        fun occ(wordId: Long, loc: String) = VocabularyOccurrence(
            wordId = wordId, bookUrl = "file:///b.epub", bookTitle = "B", locationKey = loc,
            locator = null, sentence = "s $loc", lastSeenAt = addedAt,
        )
        dao.insertOccurrence(occ(primaryId, "ch01#p1"))
        dao.insertOccurrence(occ(dupId, "ch02#p1"))
        val before = dao.occurrencesList(primaryId).single()

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        val survivor = dao.findAllWordsByKey("clarté", "fr").single()
        val after = dao.occurrencesList(survivor.id)
        assertEquals(2, after.size)
        assertEquals(before.id, after.single { it.locationKey == "ch01#p1" }.id)
    }

    @Test
    fun `provisional to canonical key upgrade keeps newer committed occurrence edits`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val now = 1_745_600_000_000L
        val dao = deviceA.liseurDb.vocabularyDao()
        VocabularyRepository(deviceA.liseurDb).collect(VocabularyLookup("aurore", "fr", null), "dawn", "en", now)!!
        val wordId = dao.findWord("aurore", "fr")!!.id
        val rowId = dao.insertOccurrence(
            VocabularyOccurrence(
                wordId = wordId, bookUrl = "file:///a.epub", bookTitle = "A", locationKey = "ch01#p1",
                locator = null, sentence = "old", lastSeenAt = now,
            ),
        )
        val stale = dao.occurrenceById(rowId)!!
        dao.updateOccurrence(
            stale.copy(sentence = "newer", sentenceEdited = true, sentenceUpdatedAt = now + 5, encounters = 3),
        )

        val upgraded = deviceA.driveSync.vocabularySync.upgradeOccurrenceKeyIfNeeded(stale, "work-1")!!

        assertEquals("newer", upgraded.sentence)
        assertEquals(3, upgraded.encounters)
        val stored = dao.occurrenceById(rowId)!!
        assertEquals("newer", stored.sentence)
        assertEquals(3, stored.encounters)
        assertEquals("work-1", stored.workId)

        dao.deleteOccurrence(rowId)
        assertNull(deviceA.driveSync.vocabularySync.upgradeOccurrenceKeyIfNeeded(stored.copy(workId = null), "work-1"))
        assertNull(dao.occurrenceById(rowId))
    }

    @Test
    fun `session after catalog deletion remains as hidden history`() = runTest {
        val key = "isbn:9780156027601"
        val hlc = "0017450000000-0001-dev-a"
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(
                permissionId = PERM_A,
                domain = DriveWireCodec.DOMAIN_CATALOG,
                recordKey = key,
                versionFp = "deleted",
                versionOp = DriveRecordState.OP_DEL,
                lastHlc = hlc,
            ),
        )
        val body = DriveSessionBody("session-1", key, 1000L, 2000L, 1000L, 0L, 0.0, 0.1, "dev-a")
        val record = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_SESSION, body.sessionId, hlc, DriveWireCodec.encodeSessionBody(body),
        )
        val identity = DriveWorkIdentity(deviceA.liseurDb, deviceA.driveDb)
        val sync = DriveSessionSync(deviceA.liseurDb, deviceA.driveDb, identity)
        assertTrue(sync.applyRemoteRecord(PERM_A, record, "dev-a", 1L))
        assertTrue(sync.applyRemoteRecord(PERM_A, record, "dev-a", 1L))
        assertNull(deviceA.liseurDb.bookDao().getByUrl("drive:$key"))
        assertEquals("drive:$key", deviceA.liseurDb.readingSessionDao().allOnce().single().bookUrl)
    }

    @Test
    fun `annotation after catalog deletion remains as hidden history`() = runTest {
        val key = "isbn:9780156027601"
        val hlc = "0017450000000-0001-dev-a"
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc),
        )
        val body = DriveAnnotationBody(
            id = "note-1", workKey = key, editionSha = null, kind = "BOOK_NOTE", locatorJson = "",
            text = null, note = "Remember this", tint = null, chapter = null, totalProgression = null,
            createdAt = 1000L, noteCreatedAt = 1000L, noteUpdatedAt = 1000L, updatedAt = 1000L,
        )
        val record = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_ANNOTATION, body.id, hlc, DriveWireCodec.encodeAnnotationBody(body),
        )
        val identity = DriveWorkIdentity(deviceA.liseurDb, deviceA.driveDb)
        val sync = DriveAnnotationSync(deviceA.liseurDb, deviceA.driveDb, identity)
        assertTrue(sync.applyRemoteRecord(PERM_A, record, "dev-a", 1L) { hlc })
        assertTrue(sync.applyRemoteRecord(PERM_A, record, "dev-a", 1L) { hlc })
        assertNull(deviceA.liseurDb.bookDao().getByUrl("drive:$key"))
        assertEquals("drive:$key", deviceA.liseurDb.annotationDao().byId(body.id)?.bookId)
        assertEquals(record.fp, deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_ANNOTATION, body.id)?.versionFp)
    }

    @Test
    fun `hidden annotation under an alias accepts a later edit`() = runTest {
        val key = "isbn:9780156027601"
        val alias = "sha:old"
        val deletedAlias = "sha:older"
        val hlc = "0017450000000-0001-dev-a"
        val identity = DriveWorkIdentity(deviceA.liseurDb, deviceA.driveDb)
        val merge = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_WORK_ALIAS, "merge:$alias", hlc,
            DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge(alias, key, 1L)),
        )
        identity.applyRemoteRecord(PERM_A, merge, "dev-a", 1L)
        val siblingMerge = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_WORK_ALIAS, "merge:$deletedAlias", hlc,
            DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge(deletedAlias, key, 1L)),
        )
        identity.applyRemoteRecord(PERM_A, siblingMerge, "dev-a", 2L)
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, deletedAlias, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc),
        )
        assertFalse(identity.awaitsCatalogEntry(PERM_A, alias))
        val body = DriveAnnotationBody(
            id = "note-alias", workKey = alias, editionSha = null, kind = "BOOK_NOTE", locatorJson = "",
            text = null, note = "First", tint = null, chapter = null, totalProgression = null,
            createdAt = 1000L, noteCreatedAt = 1000L, noteUpdatedAt = 1000L, updatedAt = 1000L,
        )
        val first = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_ANNOTATION, body.id, hlc, DriveWireCodec.encodeAnnotationBody(body),
        )
        val successor = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_ANNOTATION, body.id, "0017450000000-0002-dev-a",
            DriveWireCodec.encodeAnnotationBody(body.copy(note = "Second", updatedAt = 2000L)),
            ancestors = listOf(first.fp),
        )
        val sync = DriveAnnotationSync(deviceA.liseurDb, deviceA.driveDb, identity)
        assertTrue(sync.applyRemoteRecord(PERM_A, first, "dev-a", 1L) { hlc })
        val laterMerge = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_WORK_ALIAS, "merge:$key", "0017450000000-0002-dev-b",
            DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge(key, "dc:solaris", 2L)),
        )
        identity.applyRemoteRecord(PERM_A, laterMerge, "dev-b", 1L)
        assertTrue(sync.applyRemoteRecord(PERM_A, successor, "dev-a", 2L) { hlc })
        assertEquals("Second", deviceA.liseurDb.annotationDao().byId(body.id)?.note)
        assertTrue(deviceA.driveDb.conflicts().unsettledForRecord(PERM_A, DriveWireCodec.DOMAIN_ANNOTATION, body.id).isEmpty())
    }

    @Test
    fun `series after a catalog deletion is retained and materialized when the book returns`() = runTest {
        val key = "isbn:9780156027601"
        val hlc = "0017450000000-0001-dev-a"
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc),
        )
        val identity = DriveWorkIdentity(deviceA.liseurDb, deviceA.driveDb)
        val sync = DriveSeriesSync(deviceA.liseurDb, deviceA.driveDb, identity)
        val body = DriveSeriesBody(
            workKey = key,
            seriesOverridden = true,
            userSeriesName = "Solaris Cycle",
            indexOverridden = true,
            userSeriesIndex = 2.0,
            seriesKey = "solaris cycle",
            updatedAt = 1_000L,
        )
        val record = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_SERIES, key, hlc, DriveWireCodec.encodeSeriesBody(body),
        )

        assertTrue(sync.applyRemoteRecord(PERM_A, record, "dev-a", 1L) { hlc })

        val held = deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)!!
        assertTrue(held.held)
        assertEquals(record.fp, held.versionFp)
        assertNotNull(held.bodyJson)
        assertNull(deviceA.liseurDb.bookDao().getByUrl("drive:$key"))

        seedRestoredBook(deviceA, key)
        deviceA.changeEngine.materializeHeldRecords(PERM_A, "dev-a", "token")

        val restored = deviceA.liseurDb.bookDao().getByUrl("drive:$key")!!
        assertTrue(restored.seriesOverridden)
        assertEquals("Solaris Cycle", restored.userSeriesName)
        assertEquals(2.0, restored.userSeriesIndex)
        assertFalse(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)!!.held)
    }

    @Test
    fun `evaluation after a catalog deletion is retained and materialized when the book returns`() = runTest {
        val key = "isbn:9780156027601"
        val hlc = "0017450000000-0001-dev-a"
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc),
        )
        val identity = DriveWorkIdentity(deviceA.liseurDb, deviceA.driveDb)
        val sync = DriveEvaluationSync(deviceA.liseurDb, deviceA.driveDb, identity)
        val rating = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_EVALUATION, "$key/rating", hlc,
            DriveWireCodec.encodeEvaluationBody(DriveEvaluationBody(key, "rating", "9", 1_000L)),
        )
        val review = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_EVALUATION, "$key/review", hlc,
            DriveWireCodec.encodeEvaluationBody(DriveEvaluationBody(key, "review", "The ocean speaks", 1_000L)),
        )

        assertTrue(sync.applyRemoteRecord(PERM_A, rating, "dev-a", 1L) { hlc })
        assertTrue(sync.applyRemoteRecord(PERM_A, review, "dev-a", 1L) { hlc })
        assertTrue(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_EVALUATION, "$key/rating")!!.held)
        assertTrue(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_EVALUATION, "$key/review")!!.held)

        seedRestoredBook(deviceA, key)
        deviceA.changeEngine.materializeHeldRecords(PERM_A, "dev-a", "token")

        assertEquals("9", deviceA.liseurDb.bookEvaluationDao().get("drive:$key", "rating")?.value)
        assertEquals("The ocean speaks", deviceA.liseurDb.bookEvaluationDao().get("drive:$key", "review")?.value)
        assertFalse(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_EVALUATION, "$key/rating")!!.held)
    }

    @Test
    fun `metadata override after a catalog deletion is retained and materialized when the book returns`() = runTest {
        val key = "isbn:9780156027601"
        val hlc = "0017450000000-0001-dev-a"
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc),
        )
        val identity = DriveWorkIdentity(deviceA.liseurDb, deviceA.driveDb)
        val sync = deviceA.driveSync.metadataSync
        val record = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_METADATA, "$key/title", hlc,
            DriveWireCodec.encodeMetadataBody(DriveMetadataBody.Text(key, "title", "Solaris, Revised", 1_000L)),
        )

        assertTrue(sync.applyRemoteRecord(PERM_A, "token", record, "dev-a", 1L) { hlc })
        assertTrue(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_METADATA, "$key/title")!!.held)

        val restored = seedRestoredBook(deviceA, key)
        deviceA.changeEngine.materializeHeldRecords(PERM_A, "dev-a", "token")

        assertEquals("Solaris, Revised", deviceA.liseurDb.bookDao().getByUrl(restored.url)?.title)
        assertFalse(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_METADATA, "$key/title")!!.held)
    }

    @Test
    fun `history kept under the synthetic key follows the book onto a different live url`() = runTest {
        val key = "isbn:9780156027601"
        val hidden = "drive:$key"
        val live = "file:///calibre/solaris.epub"
        deviceA.liseurDb.bookDao().upsert(restoredBook(live))
        deviceA.liseurDb.workIdentityDao().upsert(
            WorkAlias(
                bookUrl = live,
                peerId = DriveLibrarySync.accountPeerId(PERM_A),
                workId = key,
                confidence = "high",
                resolvedAt = 0L,
            ),
        )
        deviceA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "note-1", bookId = hidden, kind = "BOOK_NOTE", locatorJson = "",
                note = "Remember this", createdAt = 1_000L,
            ),
        )
        deviceA.liseurDb.annotationSyncDao().upsert(
            AnnotationSync(id = "note-1", peerId = DriveLibrarySync.accountPeerId(PERM_A), bookId = hidden, workId = key),
        )
        deviceA.liseurDb.readingSessionDao().insert(
            ReadingSession(
                bookUrl = hidden, startedAt = 1_000L, endedAt = 2_000L, lastCheckpointAt = 2_000L,
                durationMs = 1_000L, startProgression = 0.0, endProgression = 0.1,
            ),
        )

        DriveWorkIdentity(deviceA.liseurDb, deviceA.driveDb).reattachHiddenHistory(PERM_A)

        assertEquals(live, deviceA.liseurDb.annotationDao().byId("note-1")?.bookId)
        assertEquals("Remember this", deviceA.liseurDb.annotationDao().byId("note-1")?.note)
        assertEquals(live, deviceA.liseurDb.readingSessionDao().allOnce().single().bookUrl)
        assertEquals(
            live,
            deviceA.liseurDb.annotationSyncDao().get(DriveLibrarySync.accountPeerId(PERM_A), "note-1")?.bookId,
        )
    }

    @Test
    fun `series held under a catalog tombstone is materialized once the work is restored`() = runTest {
        val key = "isbn:9780156027601"
        val firstHlc = "0017450000000-0001-dev-a"
        val deleterHlc = "0017450000000-0002-dev-z"
        val restorerHlc = "0017450000000-0003-dev-r"
        val catalog = deviceA.driveSync.catalogSync
        val series = deviceA.driveSync.seriesSync

        fun catalogBody() = DriveCatalogBody(
            workKey = key,
            title = "Solaris",
            author = "Stanislaw Lem",
            identifiers = emptyList(),
            editions = listOf(
                DriveCatalogEdition(
                    sha256 = "a".repeat(64),
                    fileName = "solaris.epub",
                    sizeBytes = 34L,
                    driveFileId = null,
                    uploadedAt = null,
                    availabilityHlc = null,
                ),
            ),
            fileSeriesName = null,
            fileSeriesIndex = null,
            addedAt = 1_000L,
            archivedAt = null,
            updatedAt = 1_000L,
        )

        // Another device publishes the work, so the catalog entry exists before anything else.
        val publish = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_CATALOG, key, firstHlc, DriveWireCodec.encodeCatalogBody(catalogBody()),
        )
        catalog.applyRemoteRecord(PERM_A, publish, "dev-a", 1L) { firstHlc }
        assertNotNull(deviceA.liseurDb.bookDao().getByUrl("drive:$key"))

        // A third device deletes it from its synced library: the tombstone supersedes the publication.
        val deletion = DriveWireCodec.delRecord(
            DriveWireCodec.DOMAIN_CATALOG, key, deleterHlc, ancestors = listOf(publish.fp),
        )
        catalog.applyRemoteRecord(PERM_A, deletion, "dev-z", 1L) { deleterHlc }
        assertNull(deviceA.liseurDb.bookDao().getByUrl("drive:$key"))
        assertEquals(
            DriveRecordState.OP_DEL,
            deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key)?.versionOp,
        )

        // The series record for that work arrives after the tombstone: retained, not acknowledged and dropped.
        val seriesBody = DriveSeriesBody(
            workKey = key,
            seriesOverridden = true,
            userSeriesName = "Solaris Cycle",
            indexOverridden = true,
            userSeriesIndex = 2.0,
            seriesKey = "solaris cycle",
            updatedAt = 1_000L,
        )
        val seriesRecord = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_SERIES, key, firstHlc, DriveWireCodec.encodeSeriesBody(seriesBody),
        )
        assertTrue(series.applyRemoteRecord(PERM_A, seriesRecord, "dev-a", 2L) { firstHlc })
        val held = deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)!!
        assertTrue(held.held)
        assertEquals(seriesRecord.fp, held.versionFp)
        assertNotNull(held.bodyJson)

        // A device that never saw the deletion re-imports the same edition, restoring the work.
        val restore = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_CATALOG, key, restorerHlc,
            DriveWireCodec.encodeCatalogBody(catalogBody().copy(updatedAt = 3_000L)),
            ancestors = listOf(deletion.fp),
        )
        catalog.applyRemoteRecord(PERM_A, restore, "dev-r", 1L) { restorerHlc }

        deviceA.changeEngine.materializeHeldRecords(PERM_A, "dev-a", "token")

        val restored = deviceA.liseurDb.bookDao().getByUrl("drive:$key")
        assertNotNull(restored)
        assertTrue(restored!!.seriesOverridden)
        assertEquals("Solaris Cycle", restored.userSeriesName)
        assertEquals(2.0, restored.userSeriesIndex)
        assertFalse(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)!!.held)

        // Later edits and deletes keep applying normally once the record is materialized.
        val successor = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_SERIES, key, "0017450000000-0004-dev-a",
            DriveWireCodec.encodeSeriesBody(seriesBody.copy(userSeriesName = "Solaris Cycle II", updatedAt = 4_000L)),
            ancestors = listOf(seriesRecord.fp),
        )
        assertTrue(series.applyRemoteRecord(PERM_A, successor, "dev-a", 3L) { firstHlc })
        assertEquals("Solaris Cycle II", deviceA.liseurDb.bookDao().getByUrl("drive:$key")?.userSeriesName)
    }

    @Test
    fun `a snapshot carrying the tombstone next to the series record retains that record`() = runTest {
        val server = Phase5FakeDriveServer()
        val snapshotSource = "dev-s-source"
        val fresh = createDevice("dev-t-fresh", File(tempDir, "snap-fresh"), server)
        try {
            fresh.connect(PERM_A, "a@example.com")
            val key = "isbn:9780156027601"
            val hlc = "0017450000000-0001-dev-s-source"
            val catalogPut = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_CATALOG, key, hlc,
                DriveWireCodec.encodeCatalogBody(
                    DriveCatalogBody(
                        workKey = key,
                        title = "Solaris",
                        author = "Stanislaw Lem",
                        identifiers = emptyList(),
                        editions = emptyList(),
                        fileSeriesName = null,
                        fileSeriesIndex = null,
                        addedAt = 1_000L,
                        archivedAt = null,
                        updatedAt = 1_000L,
                    ),
                ),
            )
            val deletion = DriveWireCodec.delRecord(
                DriveWireCodec.DOMAIN_CATALOG, key, hlc, ancestors = listOf(catalogPut.fp),
            )
            val seriesBody = DriveSeriesBody(
                workKey = key,
                seriesOverridden = true,
                userSeriesName = "Solaris Cycle",
                indexOverridden = true,
                userSeriesIndex = 2.0,
                seriesKey = "solaris cycle",
                updatedAt = 1_000L,
            )
            val seriesRecord = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES, key, hlc, DriveWireCodec.encodeSeriesBody(seriesBody),
            )
            val batch = DriveDeltaBatch(
                batchId = "snapshot-held-part",
                deviceId = snapshotSource,
                seq = 1L,
                createdHlc = hlc,
                domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
                records = listOf(deletion, seriesRecord),
            )
            val bytes = JSONObject(DriveWireCodec.encodeDeltaBatch(batch))
                .put("throughSeq", 5L).put("partIndex", 0).put("partCount", 1)
                .toString().toByteArray()
            val file = server.clientFor(snapshotSource)
                .createAppDataFile("token", "snapshot-held-part.json", "application/json", bytes, emptyMap())

            fresh.changeEngine.applySnapshot(
                PERM_A, "dev-t-fresh", snapshotSource,
                DriveSnapshotRef(
                    5L,
                    listOf(
                        DriveSnapshotPartRef(
                            "snapshot-held-part.json",
                            file.id,
                            DriveCanonicalJson.sha256Hex(bytes),
                            2,
                            0,
                        ),
                    ),
                ),
                "token",
            )

            // The snapshot holds the tombstone, so the work has no book and the series record is retained.
            assertNull(fresh.liseurDb.bookDao().getByUrl("drive:$key"))
            val held = fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)!!
            assertTrue(held.held)
            assertEquals(seriesRecord.fp, held.versionFp)

            // Restoring the work from a later catalog version materializes the retained history.
            fresh.liseurDb.bookDao().upsert(restoredBook("drive:$key"))
            fresh.changeEngine.materializeHeldRecords(PERM_A, "dev-t-fresh", "token")

            val restored = fresh.liseurDb.bookDao().getByUrl("drive:$key")!!
            assertTrue(restored.seriesOverridden)
            assertEquals("Solaris Cycle", restored.userSeriesName)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `a version older than the retained one cannot land on a book restored in the same run`() = runTest {
        val server = Phase5FakeDriveServer()
        val source = "dev-s-source"
        val fresh = createDevice("dev-u-fresh", File(tempDir, "snap-older"), server)
        try {
            fresh.connect(PERM_A, "a@example.com")
            val key = "isbn:9780156027601"
            val hlc = "0017450000000-0001-dev-s-source"
            fun catalogBody() = DriveCatalogBody(
                workKey = key,
                title = "Solaris",
                author = "Stanislaw Lem",
                identifiers = emptyList(),
                editions = emptyList(),
                fileSeriesName = null,
                fileSeriesIndex = null,
                addedAt = 1_000L,
                archivedAt = null,
                updatedAt = 1_000L,
            )
            val catalogPut = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_CATALOG, key, hlc, DriveWireCodec.encodeCatalogBody(catalogBody()),
            )
            val deletion = DriveWireCodec.delRecord(
                DriveWireCodec.DOMAIN_CATALOG, key, hlc, ancestors = listOf(catalogPut.fp),
            )
            fun seriesBody(name: String) = DriveSeriesBody(
                workKey = key,
                seriesOverridden = true,
                userSeriesName = name,
                indexOverridden = false,
                userSeriesIndex = null,
                seriesKey = name.lowercase(),
                updatedAt = 1_000L,
            )
            val older = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES, key, hlc, DriveWireCodec.encodeSeriesBody(seriesBody("Old Shelf")),
            )
            val newer = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES, key, "0017450000000-0002-dev-s-source",
                DriveWireCodec.encodeSeriesBody(seriesBody("Solaris Cycle")),
                ancestors = listOf(older.fp),
            )

            suspend fun apply(deviceId: String, records: List<DriveWireRecord>, seq: Long) {
                val batch = DriveDeltaBatch(
                    batchId = "snapshot-$deviceId",
                    deviceId = deviceId,
                    seq = seq,
                    createdHlc = hlc,
                    domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
                    records = records,
                )
                val bytes = JSONObject(DriveWireCodec.encodeDeltaBatch(batch))
                    .put("throughSeq", seq).put("partIndex", 0).put("partCount", 1)
                    .toString().toByteArray()
                val file = server.clientFor(deviceId)
                    .createAppDataFile("token", "snapshot-$deviceId.json", "application/json", bytes, emptyMap())
                fresh.changeEngine.applySnapshot(
                    PERM_A, "dev-u-fresh", deviceId,
                    DriveSnapshotRef(
                        seq,
                        listOf(
                            DriveSnapshotPartRef(
                                "snapshot-$deviceId.json",
                                file.id,
                                DriveCanonicalJson.sha256Hex(bytes),
                                records.size,
                                0,
                            ),
                        ),
                    ),
                    "token",
                )
            }

            // The tombstone lands first and the newer series version is retained.
            apply(source, listOf(deletion, newer), 1L)
            assertNull(fresh.liseurDb.bookDao().getByUrl("drive:$key"))
            assertTrue(fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)!!.held)

            // Another device restores the work and still carries the older version in its snapshot.
            val restoringPut = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_CATALOG, key, "0017450000000-0003-dev-o-older",
                DriveWireCodec.encodeCatalogBody(catalogBody().copy(updatedAt = 3_000L)),
                ancestors = listOf(deletion.fp),
            )
            apply("dev-o-older", listOf(restoringPut, older), 2L)

            val restored = fresh.liseurDb.bookDao().getByUrl("drive:$key")
            assertNotNull(restored)
            assertEquals("Solaris Cycle", restored!!.userSeriesName)
            assertTrue(fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)?.held != true)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `a retained cover whose blob cannot be fetched yet keeps the older record waiting`() = runTest {
        val server = Phase5FakeDriveServer()
        val fresh = createDevice("dev-v-fresh", File(tempDir, "cover-held"), server)
        try {
            fresh.connect(PERM_A, "a@example.com")
            val key = "isbn:9780156027601"
            val hlc = "0017450000000-0001-dev-v-fresh"
            fresh.driveDb.recordStates().upsert(
                DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc),
            )
            val identity = DriveWorkIdentity(fresh.liseurDb, fresh.driveDb)
            val metadataSync = DriveMetadataSync(fresh.liseurDb, fresh.driveDb, identity, DriveCoverCodec(File(tempDir, "covers-v"), fresh.driveDb, server.clientFor("dev-v-fresh")))
            val cover = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_METADATA, "$key/cover", hlc,
                DriveWireCodec.encodeMetadataBody(
                    DriveMetadataBody.Cover(key, "b".repeat(64), 100, 150, 1_000L, 1_000L),
                ),
            )
            assertTrue(metadataSync.applyRemoteRecord(PERM_A, "token", cover, "dev-v-fresh", 1L) { hlc })
            assertTrue(fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_METADATA, "$key/cover")!!.held)

            // The book comes back, and an older cover deletion arrives while the blob is not there yet.
            fresh.liseurDb.bookDao().upsert(restoredBook("drive:$key"))
            val deletion = DriveWireCodec.delRecord(
                DriveWireCodec.DOMAIN_METADATA, "$key/cover", "0017450000000-0002-dev-v-other",
            )
            val batch = DriveDeltaBatch(
                batchId = "snapshot-cover",
                deviceId = "dev-v-other",
                seq = 2L,
                createdHlc = "0017450000000-0002-dev-v-other",
                domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
                records = listOf(deletion),
            )
            val bytes = JSONObject(DriveWireCodec.encodeDeltaBatch(batch))
                .put("throughSeq", 2L).put("partIndex", 0).put("partCount", 1)
                .toString().toByteArray()
            val file = server.clientFor("dev-v-other")
                .createAppDataFile("token", "snapshot-cover.json", "application/json", bytes, emptyMap())
            fresh.changeEngine.applySnapshot(
                PERM_A, "dev-v-fresh", "dev-v-other",
                DriveSnapshotRef(
                    2L,
                    listOf(DriveSnapshotPartRef("snapshot-cover.json", file.id, DriveCanonicalJson.sha256Hex(bytes), 1, 0)),
                ),
                "token",
            )

            // The retained cover keeps its place and its retry, instead of being replaced by the deletion.
            val state = fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_METADATA, "$key/cover")!!
            assertTrue(state.held)
            assertEquals(cover.fp, state.versionFp)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `a successor of a retained cover is applied without waiting for its blob`() = runTest {
        val server = Phase5FakeDriveServer()
        val fresh = createDevice("dev-w-fresh", File(tempDir, "cover-succ"), server)
        try {
            fresh.connect(PERM_A, "a@example.com")
            val key = "isbn:9780156027601"
            val hlc = "0017450000000-0001-dev-w-fresh"
            fresh.driveDb.recordStates().upsert(
                DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc),
            )
            val identity = DriveWorkIdentity(fresh.liseurDb, fresh.driveDb)
            val metadataSync = DriveMetadataSync(
                fresh.liseurDb,
                fresh.driveDb,
                identity,
                DriveCoverCodec(File(tempDir, "covers-w"), fresh.driveDb, server.clientFor("dev-w-fresh")),
            )
            val cover = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_METADATA, "$key/cover", hlc,
                DriveWireCodec.encodeMetadataBody(
                    DriveMetadataBody.Cover(key, "c".repeat(64), 100, 150, 1_000L, 1_000L),
                ),
            )
            assertTrue(metadataSync.applyRemoteRecord(PERM_A, "token", cover, "dev-w-fresh", 1L) { hlc })
            // A competing cover arrives while the work still has no book, so its conflict is parked.
            val competing = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_METADATA, "$key/cover", "0017450000000-0002-dev-w-other",
                DriveWireCodec.encodeMetadataBody(
                    DriveMetadataBody.Cover(key, "d".repeat(64), 100, 150, 2_000L, 2_000L),
                ),
            )
            assertTrue(metadataSync.applyRemoteRecord(PERM_A, "token", competing, "dev-w-other", 2L) { hlc })
            assertEquals(
                1,
                fresh.driveDb.conflicts().unsettledForRecord(PERM_A, DriveWireCodec.DOMAIN_METADATA, "$key/cover").size,
            )
            fresh.liseurDb.bookDao().upsert(restoredBook("drive:$key"))

            // A deletion that descends from the retained cover is its successor, not a competing version.
            val deletion = DriveWireCodec.delRecord(
                DriveWireCodec.DOMAIN_METADATA,
                "$key/cover",
                "0017450000000-0002-dev-w-other",
                ancestors = listOf(cover.fp),
            )
            val batch = DriveDeltaBatch(
                batchId = "snapshot-cover-succ",
                deviceId = "dev-w-other",
                seq = 2L,
                createdHlc = "0017450000000-0002-dev-w-other",
                domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
                records = listOf(deletion),
            )
            val bytes = JSONObject(DriveWireCodec.encodeDeltaBatch(batch))
                .put("throughSeq", 2L).put("partIndex", 0).put("partCount", 1)
                .toString().toByteArray()
            val file = server.clientFor("dev-w-other")
                .createAppDataFile("token", "snapshot-cover-succ.json", "application/json", bytes, emptyMap())
            fresh.changeEngine.applySnapshot(
                PERM_A, "dev-w-fresh", "dev-w-other",
                DriveSnapshotRef(
                    2L,
                    listOf(
                        DriveSnapshotPartRef(
                            "snapshot-cover-succ.json",
                            file.id,
                            DriveCanonicalJson.sha256Hex(bytes),
                            1,
                            0,
                        ),
                    ),
                ),
                "token",
            )

            val state = fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_METADATA, "$key/cover")!!
            assertFalse(state.held)
            // The parked competition now belongs to the restored book instead of staying hidden.
            assertEquals(
                "drive:$key",
                fresh.driveDb.conflicts()
                    .unsettledForRecord(PERM_A, DriveWireCodec.DOMAIN_METADATA, "$key/cover")
                    .singleOrNull()
                    ?.bookUrl,
            )
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `a series the reader reset is not reinstated when the retained version replays`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val key = "isbn:9780156027601"
        val hlc = "0017450000000-0001-dev-a"
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc),
        )
        val identity = DriveWorkIdentity(deviceA.liseurDb, deviceA.driveDb)
        val sync = DriveSeriesSync(deviceA.liseurDb, deviceA.driveDb, identity)
        val body = DriveSeriesBody(
            workKey = key,
            seriesOverridden = true,
            userSeriesName = "Solaris Cycle",
            indexOverridden = false,
            userSeriesIndex = null,
            seriesKey = "solaris cycle",
            updatedAt = 1_000L,
        )
        assertTrue(
            sync.applyRemoteRecord(
                PERM_A,
                DriveWireCodec.putRecord(DriveWireCodec.DOMAIN_SERIES, key, hlc, DriveWireCodec.encodeSeriesBody(body)),
                "dev-a",
                1L,
            ) { hlc },
        )

        // The book returns and the reader drops the series before the retained version replays.
        val book = restoredBook("drive:$key")
        deviceA.liseurDb.bookDao().upsert(book.copy(userSeriesUpdatedAt = 2_000L))

        deviceA.changeEngine.materializeHeldRecords(PERM_A, "dev-a", "token")

        val restored = deviceA.liseurDb.bookDao().getByUrl("drive:$key")!!
        assertFalse(restored.seriesOverridden)
        assertNull(restored.userSeriesName)
        assertEquals(
            1,
            deviceA.driveDb.conflicts().unsettledForRecord(PERM_A, DriveWireCodec.DOMAIN_SERIES, key).size,
        )
    }

    @Test
    fun `a retained record survives a work key merge and stops being retained once applied`() = runTest {
        val alias = "sha:old"
        val key = "isbn:9780156027601"
        val hlc = "0017450000000-0001-dev-a"
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, alias, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc),
        )
        val identity = DriveWorkIdentity(deviceA.liseurDb, deviceA.driveDb)
        val sync = DriveSeriesSync(deviceA.liseurDb, deviceA.driveDb, identity)
        val body = DriveSeriesBody(
            workKey = alias,
            seriesOverridden = true,
            userSeriesName = "Solaris Cycle",
            indexOverridden = false,
            userSeriesIndex = null,
            seriesKey = "solaris cycle",
            updatedAt = 1_000L,
        )
        assertTrue(
            sync.applyRemoteRecord(
                PERM_A,
                DriveWireCodec.putRecord(DriveWireCodec.DOMAIN_SERIES, alias, hlc, DriveWireCodec.encodeSeriesBody(body)),
                "dev-a",
                1L,
            ) { hlc },
        )
        assertTrue(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, alias)!!.held)

        // The work key is merged into an ISBN after retention, and the book comes back under it.
        identity.applyRemoteRecord(
            PERM_A,
            DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_WORK_ALIAS, "merge:$alias", hlc,
                DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge(alias, key, 1L)),
            ),
            "dev-a",
            2L,
        )
        deviceA.liseurDb.bookDao().upsert(restoredBook("drive:$key"))

        deviceA.changeEngine.materializeHeldRecords(PERM_A, "dev-a", "token")

        assertEquals("Solaris Cycle", deviceA.liseurDb.bookDao().getByUrl("drive:$key")?.userSeriesName)
        assertNull(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, alias))
        assertTrue(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)?.held != true)
    }

    @Test
    fun `a rating the reader cleared is not overwritten when the retained version replays`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val key = "isbn:9780156027601"
        val hlc = "0017450000000-0001-dev-a"
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc),
        )
        val identity = DriveWorkIdentity(deviceA.liseurDb, deviceA.driveDb)
        val sync = DriveEvaluationSync(deviceA.liseurDb, deviceA.driveDb, identity)
        val rating = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_EVALUATION, "$key/rating", hlc,
            DriveWireCodec.encodeEvaluationBody(DriveEvaluationBody(key, "rating", "9", 1_000L)),
        )
        assertTrue(sync.applyRemoteRecord(PERM_A, rating, "dev-a", 1L) { hlc })

        // The book comes back, and the reader clears the rating before the retained version replays.
        seedRestoredBook(deviceA, key)
        deviceA.liseurDb.bookEvaluationDao().upsert(
            BookEvaluation(bookUrl = "drive:$key", field = "rating", value = null, title = "Solaris", author = "Lem", revision = 1L, updatedAt = 2_000L),
        )

        deviceA.changeEngine.materializeHeldRecords(PERM_A, "dev-a", "token")

        assertNull(deviceA.liseurDb.bookEvaluationDao().get("drive:$key", "rating")?.value)
        assertEquals(
            1,
            deviceA.driveDb.conflicts().unsettledForRecord(PERM_A, DriveWireCodec.DOMAIN_EVALUATION, "$key/rating").size,
        )
    }

    @Test
    fun `a retained rating is never published over by a local edit made before it replays`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val key = "isbn:9780156027601"
        val hlc = "0017450000000-0001-dev-a"
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc),
        )
        val identity = DriveWorkIdentity(deviceA.liseurDb, deviceA.driveDb)
        val sync = DriveEvaluationSync(deviceA.liseurDb, deviceA.driveDb, identity)
        val rating = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_EVALUATION, "$key/rating", hlc,
            DriveWireCodec.encodeEvaluationBody(DriveEvaluationBody(key, "rating", "9", 1_000L)),
        )
        assertTrue(sync.applyRemoteRecord(PERM_A, rating, "dev-a", 1L) { hlc })
        seedRestoredBook(deviceA, key)
        deviceA.liseurDb.bookEvaluationDao().upsert(
            BookEvaluation(bookUrl = "drive:$key", field = "rating", value = "5", title = "Solaris", author = "Lem", revision = 2L, updatedAt = 2_000L),
        )

        val outbound = sync.collectDirtyRecords(PERM_A) { "0017450000000-0009-dev-a" }

        assertTrue(outbound.records.none { it.k == "$key/rating" })
        assertTrue(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_EVALUATION, "$key/rating")!!.held)
    }

    @Test
    fun `history is left alone while the synthetic key still names a live book`() = runTest {
        val key = "isbn:9780156027601"
        val hidden = "drive:$key"
        val other = "file:///calibre/solaris.epub"
        deviceA.liseurDb.bookDao().upsert(restoredBook(hidden))
        deviceA.liseurDb.bookDao().upsert(restoredBook(other))
        deviceA.liseurDb.workIdentityDao().upsert(
            WorkAlias(
                bookUrl = other,
                peerId = DriveLibrarySync.accountPeerId(PERM_A),
                workId = key,
                confidence = "high",
                resolvedAt = 0L,
            ),
        )
        deviceA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "note-2", bookId = hidden, kind = "BOOK_NOTE", locatorJson = "",
                note = "Still here", createdAt = 1_000L,
            ),
        )

        DriveWorkIdentity(deviceA.liseurDb, deviceA.driveDb).reattachHiddenHistory(PERM_A)

        assertEquals(hidden, deviceA.liseurDb.annotationDao().byId("note-2")?.bookId)
    }

    @Test
    fun `stale work alias without a local book still retains series evaluation and metadata as held`() = runTest {
        val key = "isbn:9780156027601"
        val hlc = "0017450000000-0001-dev-a"
        val peerId = DriveLibrarySync.accountPeerId(PERM_A)
        // Stale usable alias whose local book row is gone, alongside a catalog tombstone.
        deviceA.liseurDb.workIdentityDao().upsert(
            WorkAlias(
                bookUrl = "file:///gone/solaris.epub",
                peerId = peerId,
                workId = key,
                confidence = "high",
                resolvedAt = 0L,
            ),
        )
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc),
        )

        val seriesRecord = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_SERIES,
            key,
            hlc,
            DriveWireCodec.encodeSeriesBody(
                DriveSeriesBody(key, true, "Solaris Cycle", false, null, "solaris cycle", 1_000L),
            ),
        )
        val evalRecord = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_EVALUATION,
            "$key/rating",
            hlc,
            DriveWireCodec.encodeEvaluationBody(DriveEvaluationBody(key, "rating", "9", 1_000L)),
        )
        val metaRecord = DriveWireCodec.putRecord(
            DriveWireCodec.DOMAIN_METADATA,
            "$key/title",
            hlc,
            DriveWireCodec.encodeMetadataBody(DriveMetadataBody.Text(key, "title", "Solaris, Revised", 1_000L)),
        )

        assertTrue(deviceA.driveSync.seriesSync.applyRemoteRecord(PERM_A, seriesRecord, "dev-a", 1L) { hlc })
        assertTrue(deviceA.driveSync.evaluationSync.applyRemoteRecord(PERM_A, evalRecord, "dev-a", 2L) { hlc })
        assertTrue(deviceA.driveSync.metadataSync.applyRemoteRecord(PERM_A, "token", metaRecord, "dev-a", 3L) { hlc })

        assertTrue(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)!!.held)
        assertTrue(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_EVALUATION, "$key/rating")!!.held)
        assertTrue(deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_METADATA, "$key/title")!!.held)
        assertNull(deviceA.liseurDb.bookEvaluationDao().get("file:///gone/solaris.epub", "rating"))
    }

    @Test
    fun `merged held record blocks older incoming version and migrates parked conflict and resolution`() = runTest {
        val server = Phase5FakeDriveServer()
        val fresh = createDevice("dev-m-fresh", File(tempDir, "merge-held"), server)
        try {
            fresh.connect(PERM_A, "a@example.com")
            val alias = "sha:old"
            val key = "isbn:9780156027601"
            val hlc1 = "0017450000000-0001-dev-a"
            val hlc2 = "0017450000000-0002-dev-a"
            val hlc3 = "0017450000000-0003-dev-b"
            fresh.driveDb.recordStates().upsert(
                DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, alias, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc1),
            )

            fun seriesBody(workKey: String, name: String) = DriveSeriesBody(
                workKey = workKey,
                seriesOverridden = true,
                userSeriesName = name,
                indexOverridden = false,
                userSeriesIndex = null,
                seriesKey = name.lowercase(),
                updatedAt = 1_000L,
            )
            val older = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES, key, hlc1, DriveWireCodec.encodeSeriesBody(seriesBody(key, "Old Shelf")),
            )
            val newer = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES,
                alias,
                hlc2,
                DriveWireCodec.encodeSeriesBody(seriesBody(alias, "Solaris Cycle")),
                ancestors = listOf(older.fp),
            )
            val competing = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES,
                alias,
                hlc3,
                DriveWireCodec.encodeSeriesBody(seriesBody(alias, "Competing Shelf")),
            )

            assertTrue(fresh.driveSync.seriesSync.applyRemoteRecord(PERM_A, newer, "dev-a", 1L) { hlc2 })
            assertTrue(fresh.driveSync.seriesSync.applyRemoteRecord(PERM_A, competing, "dev-b", 1L) { hlc3 })
            fresh.driveDb.resolutions().upsert(
                DriveResolution(
                    permissionId = PERM_A,
                    domain = DriveWireCodec.DOMAIN_SERIES,
                    recordKey = alias,
                    resolutionFp = "res-old",
                    resolvedFpsJson = DriveCanonicalJson.encode(listOf("fp-a", "fp-b")),
                    outcome = DriveResolution.OUTCOME_PUT,
                    hlc = hlc1,
                    authorDeviceId = "dev-a",
                    createdAt = 1_000L,
                ),
            )

            // Merge sha:old -> isbn:9780156027601, then restore the book and deliver `older` under the canonical key.
            fresh.driveSync.workIdentity.applyRemoteRecord(
                PERM_A,
                DriveWireCodec.putRecord(
                    DriveWireCodec.DOMAIN_WORK_ALIAS,
                    "merge:$alias",
                    hlc3,
                    DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge(alias, key, 2L)),
                ),
                "dev-b",
                2L,
            )
            fresh.liseurDb.bookDao().upsert(restoredBook("drive:$key"))

            val batch = DriveDeltaBatch(
                batchId = "snap-older-canonical",
                deviceId = "dev-c",
                seq = 1L,
                createdHlc = hlc3,
                domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
                records = listOf(older),
            )
            val bytes = JSONObject(DriveWireCodec.encodeDeltaBatch(batch))
                .put("throughSeq", 1L).put("partIndex", 0).put("partCount", 1)
                .toString().toByteArray()
            val file = server.clientFor("dev-c")
                .createAppDataFile("token", "snap-older-canonical.json", "application/json", bytes, emptyMap())
            fresh.changeEngine.applySnapshot(
                PERM_A,
                "dev-m-fresh",
                "dev-c",
                DriveSnapshotRef(
                    1L,
                    listOf(DriveSnapshotPartRef("snap-older-canonical.json", file.id, DriveCanonicalJson.sha256Hex(bytes), 1, 0)),
                ),
                "token",
            )

            assertEquals("Solaris Cycle", fresh.liseurDb.bookDao().getByUrl("drive:$key")?.userSeriesName)
            assertNull(fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, alias))
            assertTrue(fresh.driveDb.conflicts().unsettledForRecord(PERM_A, DriveWireCodec.DOMAIN_SERIES, alias).isEmpty())
            val expectedConflictId = DriveCanonicalJson.conflictId(
                DriveWireCodec.DOMAIN_SERIES,
                key,
                newer.fp,
                competing.fp,
            )
            val migratedConflict = fresh.driveDb.conflicts().unsettledForRecord(PERM_A, DriveWireCodec.DOMAIN_SERIES, key).single()
            assertEquals("drive:$key", migratedConflict.bookUrl)
            assertEquals(expectedConflictId, migratedConflict.conflictId)
            assertTrue(fresh.driveDb.resolutions().forRecord(PERM_A, DriveWireCodec.DOMAIN_SERIES, alias).isEmpty())
            assertEquals(
                listOf("res-old"),
                fresh.driveDb.resolutions().forRecord(PERM_A, DriveWireCodec.DOMAIN_SERIES, key).map { it.resolutionFp },
            )

            // Replaying the competing version under the canonical key must coalesce onto the migrated conflict.
            val replayedCompeting = competing.copy(
                k = key,
                body = JSONObject(DriveCanonicalJson.encode(DriveWireCodec.encodeSeriesBody(seriesBody(key, "Competing Shelf")))),
            )
            assertTrue(fresh.driveSync.seriesSync.applyRemoteRecord(PERM_A, replayedCompeting, "dev-b", 1L) { hlc3 })
            val unsettledAfterReplay = fresh.driveDb.conflicts().unsettledForRecord(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)
            assertEquals(1, unsettledAfterReplay.size)
            assertEquals(expectedConflictId, unsettledAfterReplay.single().conflictId)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `alias-held ancestor is not replayed over newer canonical-held version when successor arrives`() = runTest {
        val server = Phase5FakeDriveServer()
        val fresh = createDevice("dev-two-held", File(tempDir, "two-held"), server)
        try {
            fresh.connect(PERM_A, "a@example.com")
            val alias = "sha:old"
            val key = "isbn:9780156027601"
            val hlc1 = "0017450000000-0001-dev-a"
            val hlc2 = "0017450000000-0002-dev-a"
            val hlc3 = "0017450000000-0003-dev-b"
            fresh.driveDb.recordStates().upsert(
                DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, alias, "deleted-alias", DriveRecordState.OP_DEL, lastHlc = hlc1),
            )
            fresh.driveDb.recordStates().upsert(
                DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key, "deleted-key", DriveRecordState.OP_DEL, lastHlc = hlc1),
            )

            fun seriesBody(workKey: String, name: String, updatedAt: Long) = DriveSeriesBody(
                workKey = workKey,
                seriesOverridden = true,
                userSeriesName = name,
                indexOverridden = false,
                userSeriesIndex = null,
                seriesKey = name.lowercase(),
                updatedAt = updatedAt,
            )
            val heldX = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES,
                alias,
                hlc1,
                DriveWireCodec.encodeSeriesBody(seriesBody(alias, "Alias Series", 1_000L)),
                ancestors = listOf("fp-root-x"),
                resRefs = listOf("res-x"),
            )
            val heldY = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES,
                key,
                hlc2,
                DriveWireCodec.encodeSeriesBody(seriesBody(key, "Canonical Series", 2_000L)),
                ancestors = listOf(heldX.fp),
                resRefs = listOf("res-y"),
            )
            assertTrue(fresh.driveSync.seriesSync.applyRemoteRecord(PERM_A, heldX, "dev-a", 1L) { hlc1 })
            assertTrue(fresh.driveSync.seriesSync.applyRemoteRecord(PERM_A, heldY, "dev-a", 2L) { hlc2 })
            assertTrue(fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, alias)!!.held)
            assertTrue(fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)!!.held)

            // Merge alias -> canonical key, restore the book with no series override, and deliver successor R of Y.
            fresh.driveSync.workIdentity.applyRemoteRecord(
                PERM_A,
                DriveWireCodec.putRecord(
                    DriveWireCodec.DOMAIN_WORK_ALIAS,
                    "merge:$alias",
                    hlc2,
                    DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge(alias, key, 2L)),
                ),
                "dev-a",
                3L,
            )
            fresh.liseurDb.bookDao().upsert(restoredBook("drive:$key"))

            val incomingR = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES,
                key,
                hlc3,
                DriveWireCodec.encodeSeriesBody(seriesBody(key, "Successor Series", 3_000L)),
                ancestors = listOf(heldY.fp),
            )
            val batch = DriveDeltaBatch(
                batchId = "snap-two-held-r",
                deviceId = "dev-b",
                seq = 1L,
                createdHlc = hlc3,
                domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
                records = listOf(incomingR),
            )
            val bytes = JSONObject(DriveWireCodec.encodeDeltaBatch(batch))
                .put("throughSeq", 1L).put("partIndex", 0).put("partCount", 1)
                .toString().toByteArray()
            val file = server.clientFor("dev-b")
                .createAppDataFile("token", "snap-two-held-r.json", "application/json", bytes, emptyMap())
            fresh.changeEngine.applySnapshot(
                PERM_A,
                "dev-two-held",
                "dev-b",
                DriveSnapshotRef(
                    1L,
                    listOf(DriveSnapshotPartRef("snap-two-held-r.json", file.id, DriveCanonicalJson.sha256Hex(bytes), 1, 0)),
                ),
                "token",
            )

            assertEquals("Successor Series", fresh.liseurDb.bookDao().getByUrl("drive:$key")?.userSeriesName)
            assertTrue(fresh.driveDb.conflicts().openForAccount(PERM_A).isEmpty())
            assertTrue(fresh.driveDb.conflicts().unsettledForRecord(PERM_A, DriveWireCodec.DOMAIN_SERIES, key).isEmpty())
            assertNull(fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, alias))
            val state = fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)!!
            assertFalse(state.held)
            assertEquals(incomingR.fp, state.versionFp)
            val ancestors = DriveWorkIdentity.decodeStringArray(state.ancestorsJson)
            assertTrue(heldY.fp in ancestors)
            assertTrue(heldX.fp in ancestors)
            assertTrue("fp-root-x" in ancestors)
            val resRefs = DriveWorkIdentity.decodeStringArray(state.resRefsJson)
            assertTrue("res-x" in resRefs)
            assertTrue("res-y" in resRefs)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `skipped alias-held row is retained when its incoming successor opens a conflict`() = runTest {
        val server = Phase5FakeDriveServer()
        val fresh = createDevice("dev-c-fresh", File(tempDir, "conflict-skip-held"), server)
        try {
            fresh.connect(PERM_A, "a@example.com")
            val alias = "sha:old"
            val key = "isbn:9780156027601"
            val hlc1 = "0017450000000-0001-dev-a"
            val hlc2 = "0017450000000-0002-dev-b"
            val hlc3 = "0017450000000-0003-dev-c"

            fun seriesBody(workKey: String, name: String) = DriveSeriesBody(
                workKey = workKey,
                seriesOverridden = true,
                userSeriesName = name,
                indexOverridden = false,
                userSeriesIndex = null,
                seriesKey = name.lowercase(),
                updatedAt = 1_000L,
            )

            // Canonical key already has a live, materialized series override on the restored book.
            fresh.liseurDb.bookDao().upsert(restoredBook("drive:$key"))
            val liveCanonical = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES,
                key,
                hlc1,
                DriveWireCodec.encodeSeriesBody(seriesBody(key, "Canonical Live")),
            )
            assertTrue(fresh.driveSync.seriesSync.applyRemoteRecord(PERM_A, liveCanonical, "dev-a", 1L) { hlc1 })

            // Alias key has an independent held record from a deleted work.
            val heldAlias = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES,
                alias,
                hlc2,
                DriveWireCodec.encodeSeriesBody(seriesBody(alias, "Alias Held")),
                ancestors = listOf("fp-alias-root"),
                resRefs = listOf("res-alias"),
            )
            fresh.driveDb.recordStates().upsert(
                DriveRecordState(
                    permissionId = PERM_A,
                    domain = DriveWireCodec.DOMAIN_SERIES,
                    recordKey = alias,
                    versionFp = heldAlias.fp,
                    versionOp = DriveRecordState.OP_PUT,
                    publishedFp = heldAlias.fp,
                    contentFp = heldAlias.fp,
                    originDeviceId = "dev-b",
                    originSeq = 1L,
                    ancestorsJson = DriveCanonicalJson.encode(heldAlias.anc),
                    resRefsJson = DriveCanonicalJson.encode(heldAlias.resRefs),
                    lastHlc = hlc2,
                    bodyJson = DriveCanonicalJson.encode(heldAlias.body!!),
                    held = true,
                ),
            )

            // Merge alias -> canonical, then deliver a successor of heldAlias that conflicts with liveCanonical.
            fresh.driveSync.workIdentity.applyRemoteRecord(
                PERM_A,
                DriveWireCodec.putRecord(
                    DriveWireCodec.DOMAIN_WORK_ALIAS,
                    "merge:$alias",
                    hlc2,
                    DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge(alias, key, 2L)),
                ),
                "dev-b",
                2L,
            )

            val successorOfAlias = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES,
                key,
                hlc3,
                DriveWireCodec.encodeSeriesBody(seriesBody(key, "Alias Successor")),
                ancestors = listOf(heldAlias.fp),
            )
            val batch = DriveDeltaBatch(
                batchId = "snap-conflict-skip",
                deviceId = "dev-c",
                seq = 1L,
                createdHlc = hlc3,
                domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
                records = listOf(successorOfAlias),
            )
            val bytes = JSONObject(DriveWireCodec.encodeDeltaBatch(batch))
                .put("throughSeq", 1L).put("partIndex", 0).put("partCount", 1)
                .toString().toByteArray()
            val file = server.clientFor("dev-c")
                .createAppDataFile("token", "snap-conflict-skip.json", "application/json", bytes, emptyMap())
            fresh.changeEngine.applySnapshot(
                PERM_A,
                "dev-c-fresh",
                "dev-c",
                DriveSnapshotRef(
                    1L,
                    listOf(DriveSnapshotPartRef("snap-conflict-skip.json", file.id, DriveCanonicalJson.sha256Hex(bytes), 1, 0)),
                ),
                "token",
            )

            // Successor opened a conflict instead of replacing liveCanonical, so heldAlias must stay retained.
            assertEquals("Canonical Live", fresh.liseurDb.bookDao().getByUrl("drive:$key")?.userSeriesName)
            assertEquals(1, fresh.driveDb.conflicts().unsettledForRecord(PERM_A, DriveWireCodec.DOMAIN_SERIES, key).size)
            val retainedAlias = fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, alias)
            assertNotNull(retainedAlias)
            assertTrue(retainedAlias!!.held)
            assertEquals(heldAlias.fp, retainedAlias.versionFp)

            // The next sync must not ask about the older value as well: only the successor's
            // question stays parked, and the retained row is kept for the history it carries.
            fresh.changeEngine.materializeHeldRecords(PERM_A, "dev-c-fresh", "token")
            assertEquals(1, fresh.driveDb.conflicts().unsettledForRecord(PERM_A, DriveWireCodec.DOMAIN_SERIES, key).size)
            val stillRetained = fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, alias)
            assertTrue(stillRetained?.held == true)
            assertEquals(heldAlias.fp, stillRetained?.versionFp)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `hidden annotation conflict stays hidden until restored and reattaches to the live book url`() = runTest {
        val key = "isbn:9780156027601"
        val hidden = "drive:$key"
        val live = "file:///calibre/solaris.epub"
        val hlc = "0017450000000-0001-dev-a"
        val peerId = DriveLibrarySync.accountPeerId(PERM_A)

        deviceA.liseurDb.annotationDao().upsert(
            BookAnnotation(
                id = "note-hidden-conflict",
                bookId = hidden,
                kind = "BOOK_NOTE",
                locatorJson = "",
                note = "Local note",
                createdAt = 1_000L,
            ),
        )
        deviceA.driveDb.conflicts().upsert(
            DriveConflict(
                permissionId = PERM_A,
                conflictId = "c-hidden-ann",
                domain = DriveWireCodec.DOMAIN_ANNOTATION,
                recordKey = "note-hidden-conflict",
                bookUrl = hidden,
                kind = DriveConflict.KIND_EDIT_EDIT,
                localFp = "fp-local",
                remoteFp = "fp-remote",
                remoteJson = DriveCanonicalJson.encode(
                    DriveWireCodec.encodeAnnotationBody(
                        DriveAnnotationBody(
                            id = "note-hidden-conflict",
                            workKey = key,
                            editionSha = null,
                            kind = "BOOK_NOTE",
                            locatorJson = "",
                            text = null,
                            note = "Remote note",
                            tint = null,
                            chapter = null,
                            totalProgression = null,
                            createdAt = 1_000L,
                            noteCreatedAt = 1_000L,
                            noteUpdatedAt = 1_000L,
                            updatedAt = 2_000L,
                        ),
                    ),
                ),
                remoteDeviceId = "dev-b",
                remoteHlc = hlc,
                state = DriveConflict.STATE_OPEN,
                openedAt = 1_000L,
            ),
        )

        val removal = BookRemoval(
            bookDao = deviceA.liseurDb.bookDao(),
            sessionDao = deviceA.liseurDb.readingSessionDao(),
            peerStateDao = deviceA.liseurDb.syncPeerStateDao(),
            identityDao = deviceA.liseurDb.workIdentityDao(),
            progressDao = deviceA.liseurDb.readingProgressDao(),
            annotationDao = deviceA.liseurDb.annotationDao(),
            annotationSyncDao = deviceA.liseurDb.annotationSyncDao(),
            inTransaction = { work -> deviceA.liseurDb.withTransaction { work() } },
            metadataDao = deviceA.liseurDb.bookMetadataDao(),
            evaluationDao = deviceA.liseurDb.bookEvaluationDao(),
        )
        val remoteAccount = RemoteAccountRepository(
            dao = deviceA.liseurDb.remoteServerDao(),
            bookDao = deviceA.liseurDb.bookDao(),
            progressDao = deviceA.liseurDb.readingProgressDao(),
            seriesExtraDao = deviceA.liseurDb.seriesExtraDao(),
            bookRemoval = removal,
            inTransaction = { work -> deviceA.liseurDb.withTransaction { work() } },
        )
        val controller = DriveAccountController(
            database = deviceA.liseurDb,
            remoteAccount = remoteAccount,
            driveSync = deviceA.driveSync,
            authClient = alwaysAuthClient("token"),
            driveApi = serverA.clientFor("dev-a"),
        )
        val beforeRestore = controller.buildConflictUiGroups(deviceA.driveDb.conflicts().openForAccount(PERM_A))
        assertTrue(beforeRestore.isEmpty())

        // Restore the book at a different local URL and reattach hidden history.
        deviceA.liseurDb.bookDao().upsert(restoredBook(live))
        deviceA.liseurDb.workIdentityDao().upsert(
            WorkAlias(bookUrl = live, peerId = peerId, workId = key, confidence = "high", resolvedAt = 0L),
        )
        deviceA.driveSync.workIdentity.reattachHiddenHistory(PERM_A)

        assertEquals(live, deviceA.driveDb.conflicts().get(PERM_A, "c-hidden-ann")?.bookUrl)
        val afterRestore = controller.buildConflictUiGroups(deviceA.driveDb.conflicts().openForAccount(PERM_A))
        assertEquals(1, afterRestore.size)
        assertEquals("Solaris", afterRestore.single().bookTitle)
    }

    @Test
    fun `reattachHiddenHistory discovers hidden urls from open and resolving conflicts alone`() = runTest {
        val openKey = "isbn:9780156027601"
        val resolvingKey = "isbn:9780156027602"
        val hiddenOpen = "drive:$openKey"
        val hiddenResolving = "drive:$resolvingKey"
        val liveOpen = "file:///calibre/solaris.epub"
        val liveResolving = "file:///calibre/eden.epub"
        val hlc = "0017450000000-0001-dev-a"
        val peerId = DriveLibrarySync.accountPeerId(PERM_A)

        // No annotation, agreement, or session rows exist at either hidden `drive:` URL.
        deviceA.driveDb.conflicts().upsert(
            DriveConflict(
                permissionId = PERM_A,
                conflictId = "c-only-open",
                domain = DriveWireCodec.DOMAIN_SERIES,
                recordKey = openKey,
                bookUrl = hiddenOpen,
                kind = DriveConflict.KIND_EDIT_EDIT,
                localFp = "fp-open-local",
                remoteFp = "fp-open-remote",
                remoteJson = null,
                remoteDeviceId = "dev-b",
                remoteHlc = hlc,
                state = DriveConflict.STATE_OPEN,
                openedAt = 1_000L,
            ),
        )
        deviceA.driveDb.conflicts().upsert(
            DriveConflict(
                permissionId = PERM_A,
                conflictId = "c-only-resolving",
                domain = DriveWireCodec.DOMAIN_SERIES,
                recordKey = resolvingKey,
                bookUrl = hiddenResolving,
                kind = DriveConflict.KIND_EDIT_EDIT,
                localFp = "fp-res-local",
                remoteFp = "fp-res-remote",
                remoteJson = null,
                remoteDeviceId = "dev-b",
                remoteHlc = hlc,
                state = DriveConflict.STATE_RESOLVING,
                choice = DriveConflictChoice.KEEP_REMOTE.name,
                resolutionFp = "res-fp-1",
                openedAt = 1_000L,
            ),
        )

        deviceA.liseurDb.bookDao().upsert(restoredBook(liveOpen))
        deviceA.liseurDb.bookDao().upsert(restoredBook(liveResolving).copy(title = "Eden", fileTitle = "Eden"))
        deviceA.liseurDb.workIdentityDao().upsert(
            WorkAlias(bookUrl = liveOpen, peerId = peerId, workId = openKey, confidence = "high", resolvedAt = 0L),
        )
        deviceA.liseurDb.workIdentityDao().upsert(
            WorkAlias(bookUrl = liveResolving, peerId = peerId, workId = resolvingKey, confidence = "high", resolvedAt = 0L),
        )

        deviceA.driveSync.workIdentity.reattachHiddenHistory(PERM_A)

        val movedOpen = deviceA.driveDb.conflicts().get(PERM_A, "c-only-open")!!
        assertEquals(liveOpen, movedOpen.bookUrl)
        assertEquals(DriveConflict.STATE_OPEN, movedOpen.state)

        val movedResolving = deviceA.driveDb.conflicts().get(PERM_A, "c-only-resolving")!!
        assertEquals(liveResolving, movedResolving.bookUrl)
        assertEquals(DriveConflict.STATE_RESOLVING, movedResolving.state)
        assertEquals(DriveConflictChoice.KEEP_REMOTE.name, movedResolving.choice)
        assertEquals("res-fp-1", movedResolving.resolutionFp)
    }

    @Test
    fun `successor of a held record preserves held ancestors and resolution refs`() = runTest {
        val server = Phase5FakeDriveServer()
        val fresh = createDevice("dev-l-fresh", File(tempDir, "held-lineage"), server)
        try {
            fresh.connect(PERM_A, "a@example.com")
            val key = "isbn:9780156027601"
            val hlc1 = "0017450000000-0001-dev-a"
            val hlc2 = "0017450000000-0002-dev-b"
            fresh.driveDb.recordStates().upsert(
                DriveRecordState(PERM_A, DriveWireCodec.DOMAIN_CATALOG, key, "deleted", DriveRecordState.OP_DEL, lastHlc = hlc1),
            )
            val heldBody = DriveSeriesBody(key, true, "First", false, null, "first", 1_000L)
            val heldRecord = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES,
                key,
                hlc1,
                DriveWireCodec.encodeSeriesBody(heldBody),
                ancestors = listOf("fp-root"),
                resRefs = listOf("res-prior"),
            )
            assertTrue(fresh.driveSync.seriesSync.applyRemoteRecord(PERM_A, heldRecord, "dev-a", 1L) { hlc1 })

            fresh.liseurDb.bookDao().upsert(restoredBook("drive:$key"))
            val successor = DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_SERIES,
                key,
                hlc2,
                DriveWireCodec.encodeSeriesBody(heldBody.copy(userSeriesName = "Second", seriesKey = "second", updatedAt = 2_000L)),
                ancestors = listOf(heldRecord.fp),
                resRefs = emptyList(),
            )
            val batch = DriveDeltaBatch(
                batchId = "snap-succ-lineage",
                deviceId = "dev-b",
                seq = 2L,
                createdHlc = hlc2,
                domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
                records = listOf(successor),
            )
            val bytes = JSONObject(DriveWireCodec.encodeDeltaBatch(batch))
                .put("throughSeq", 2L).put("partIndex", 0).put("partCount", 1)
                .toString().toByteArray()
            val file = server.clientFor("dev-b")
                .createAppDataFile("token", "snap-succ-lineage.json", "application/json", bytes, emptyMap())
            fresh.changeEngine.applySnapshot(
                PERM_A,
                "dev-l-fresh",
                "dev-b",
                DriveSnapshotRef(
                    2L,
                    listOf(DriveSnapshotPartRef("snap-succ-lineage.json", file.id, DriveCanonicalJson.sha256Hex(bytes), 1, 0)),
                ),
                "token",
            )

            val state = fresh.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_SERIES, key)!!
            assertFalse(state.held)
            assertEquals(successor.fp, state.versionFp)
            assertTrue("fp-root" in DriveWorkIdentity.decodeStringArray(state.ancestorsJson))
            assertTrue("res-prior" in DriveWorkIdentity.decodeStringArray(state.resRefsJson))
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `interrupted annotation conflict recovery finishes after a work key merge`() = runTest {
        val alias = "sha:old"
        val key = "isbn:9780156027601"
        val hlc = "0017450000000-0001-dev-a"
        val resHlc = "0017450000000-0002-dev-a"
        val peerId = DriveLibrarySync.accountPeerId(PERM_A)
        val bookUrl = "file:///calibre/solaris.epub"
        deviceA.liseurDb.bookDao().upsert(restoredBook(bookUrl))
        deviceA.liseurDb.workIdentityDao().upsert(
            WorkAlias(bookUrl = bookUrl, peerId = peerId, workId = alias, confidence = "high", resolvedAt = 0L),
        )
        val localAnn = BookAnnotation(
            id = "note-recover-merge",
            bookId = bookUrl,
            kind = "BOOK_NOTE",
            locatorJson = "",
            note = "Local version",
            createdAt = 1_000L,
            noteCreatedAt = 1_000L,
            noteUpdatedAt = 1_000L,
            updatedAt = 1_000L,
        )
        deviceA.liseurDb.annotationDao().upsert(localAnn)

        val localBody = deviceA.driveSync.annotationSync.toWireBody(localAnn, alias, editionSha = null)
        val localFp = DriveWireCodec.annotationContentFp(localBody)
        val chosenBody = localBody.copy(note = "Chosen remote version", updatedAt = 2_000L)
        val chosenBodyMap = DriveWireCodec.encodeAnnotationBody(chosenBody)
        val chosenFp = DriveWireCodec.annotationContentFp(chosenBody)
        val resolveFp = DriveCanonicalJson.resolveFp(
            outcome = DriveResolution.OUTCOME_PUT,
            resolvedFps = listOf(localFp, chosenFp),
            hlc = resHlc,
            body = chosenBodyMap,
        )
        val conflict = DriveConflict(
            permissionId = PERM_A,
            conflictId = "c-recover-merge",
            domain = DriveWireCodec.DOMAIN_ANNOTATION,
            recordKey = localAnn.id,
            bookUrl = bookUrl,
            kind = DriveConflict.KIND_EDIT_EDIT,
            localFp = localFp,
            remoteFp = chosenFp,
            remoteJson = DriveCanonicalJson.encode(chosenBodyMap),
            remoteDeviceId = "dev-b",
            remoteHlc = hlc,
            state = DriveConflict.STATE_RESOLVING,
            choice = DriveConflictChoice.KEEP_REMOTE.name,
            resolutionFp = resolveFp,
            openedAt = 1_000L,
        )
        deviceA.driveDb.conflicts().upsert(conflict)
        deviceA.driveDb.resolutions().upsert(
            DriveResolution(
                permissionId = PERM_A,
                domain = DriveWireCodec.DOMAIN_ANNOTATION,
                recordKey = localAnn.id,
                resolutionFp = resolveFp,
                resolvedFpsJson = DriveCanonicalJson.encode(listOf(localFp, chosenFp).sorted()),
                outcome = DriveResolution.OUTCOME_PUT,
                hlc = resHlc,
                authorDeviceId = "dev-a",
                createdAt = 2_000L,
            ),
        )
        deviceA.driveDb.recordStates().upsert(
            DriveRecordState(
                permissionId = PERM_A,
                domain = DriveWireCodec.DOMAIN_ANNOTATION,
                recordKey = localAnn.id,
                versionFp = resolveFp,
                versionOp = DriveRecordState.OP_RESOLVE,
                publishedFp = null,
                contentFp = chosenFp,
                lastHlc = resHlc,
                bodyJson = DriveCanonicalJson.encode(chosenBodyMap),
            ),
        )

        // An alias merge arrives before Tx 1 finishes.
        deviceA.driveSync.workIdentity.applyRemoteRecord(
            PERM_A,
            DriveWireCodec.putRecord(
                DriveWireCodec.DOMAIN_WORK_ALIAS,
                "merge:$alias",
                resHlc,
                DriveWireCodec.encodeWorkAliasBody(DriveWorkAliasBody.Merge(alias, key, 2L)),
            ),
            "dev-b",
            1L,
        )

        deviceA.driveSync.annotationSync.recoverResolvingConflict(PERM_A, conflict, "dev-a") { resHlc }

        assertEquals("Chosen remote version", deviceA.liseurDb.annotationDao().byId(localAnn.id)?.note)
        assertEquals(chosenFp, deviceA.liseurDb.annotationSyncDao().get(peerId, localAnn.id)?.ackedFingerprint)
    }

    private suspend fun seedRestoredBook(device: Phase5Device, workKey: String): Book {
        val book = restoredBook("drive:$workKey")
        device.liseurDb.bookDao().upsert(book)
        return book
    }

    private fun restoredBook(url: String) = Book(
        url = url,
        title = "Solaris",
        author = "Stanislaw Lem",
        coverPath = null,
        source = null,
        addedAt = 1_000L,
        lastOpenedAt = null,
        localUri = null,
        downloadState = DownloadState.REMOTE,
        fileTitle = "Solaris",
        fileAuthor = "Stanislaw Lem",
    )

    @Test
    fun `sessions arriving before their catalog entry are deferred and applied once catalog arrives`() = runTest {
        val isolatedServer = Phase5FakeDriveServer()
        // Note device ID ordering: "dev-b-reader" sorts lexicographically BEFORE "dev-z-catalog".
        val catalogA = createDevice("dev-z-catalog", File(tempDir, "sess-a"), isolatedServer)
        val readerB = createDevice("dev-b-reader", File(tempDir, "sess-b"), isolatedServer)
        val freshC = createDevice("dev-c-fresh", File(tempDir, "sess-c"), isolatedServer)
        val staggeredD = createDevice("dev-d-staggered", File(tempDir, "sess-d"), isolatedServer)
        try {
            catalogA.connect(PERM_A, "a@example.com")
            readerB.connect(PERM_A, "a@example.com")

            seedBook(catalogA, "solaris.epub", "9780156027601", "Solaris", "Stanislaw Lem")
            assertEquals(SyncOutcome.Success, catalogA.changeEngine.sync().outcome)

            // Reader B pulls the catalog stub, records a closed session, and uploads a session-only batch.
            assertEquals(SyncOutcome.Success, readerB.changeEngine.sync().outcome)
            val stubUrl = "drive:isbn:9780156027601"
            val started = 1_745_700_000_000L
            readerB.liseurDb.readingSessionDao().insert(
                ReadingSession(
                    bookUrl = stubUrl,
                    startedAt = started,
                    endedAt = started + 1_600_000L,
                    lastCheckpointAt = started + 1_600_000L,
                    durationMs = 1_500_000L,
                    startProgression = 0.05,
                    endProgression = 0.22,
                    idleMs = 100_000L,
                ),
            )
            assertEquals(SyncOutcome.Success, readerB.changeEngine.sync().outcome)

            // 1. Fresh receiver C processes "dev-b-reader" BEFORE "dev-z-catalog":
            // session defers until catalog imports, then applies and matches reading totals.
            freshC.connect(PERM_A, "a@example.com")
            assertEquals(SyncOutcome.Success, freshC.changeEngine.sync().outcome)
            val sessionsOnC = freshC.liseurDb.readingSessionDao().allOnce()
            assertEquals(1, sessionsOnC.size)
            assertEquals(1_500_000L, sessionsOnC.single().durationMs)
            assertEquals(1_500_000L, freshC.liseurDb.readingSessionDao().observeTotalDuration(stubUrl).first())
            assertEquals(
                1L,
                freshC.driveDb.cursors().get(PERM_A, "dev-b-reader", DriveWireCodec.DOMAIN_SESSION)?.appliedSeq,
            )

            // 2. Staggered receiver D sees ONLY reader B's session-only batch on sync 1:
            // session cursor stays at 0, then replays and matches totals once A's catalog arrives.
            staggeredD.connect(PERM_A, "a@example.com")
            isolatedServer.hideSourceDeviceFrom(sourceDeviceId = "dev-z-catalog", viewerDeviceId = "dev-d-staggered")
            assertEquals(SyncOutcome.Success, staggeredD.changeEngine.sync().outcome)
            assertTrue(staggeredD.liseurDb.readingSessionDao().allOnce().isEmpty())
            val deferredSessionCursor = staggeredD.driveDb.cursors()
                .get(PERM_A, "dev-b-reader", DriveWireCodec.DOMAIN_SESSION)
            assertEquals(0L, deferredSessionCursor?.appliedSeq ?: 0L)

            isolatedServer.unhideAll()
            assertEquals(SyncOutcome.Success, staggeredD.changeEngine.sync(forceManifestRescan = true).outcome)
            val sessionsOnD = staggeredD.liseurDb.readingSessionDao().allOnce()
            assertEquals(1, sessionsOnD.size)
            assertEquals(1_500_000L, sessionsOnD.single().durationMs)
            assertEquals(1_500_000L, staggeredD.liseurDb.readingSessionDao().observeTotalDuration(stubUrl).first())
            assertEquals(
                1L,
                staggeredD.driveDb.cursors().get(PERM_A, "dev-b-reader", DriveWireCodec.DOMAIN_SESSION)?.appliedSeq,
            )
        } finally {
            catalogA.close()
            readerB.close()
            freshC.close()
            staggeredD.close()
        }
    }

    @Test
    fun `unchanged vocabulary sync retains its published version`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val repo = VocabularyRepository(deviceA.liseurDb)
        val wordId = repo.collect(
            VocabularyLookup("nuit", "fr", null), "night", "en", 1_745_900_000_000L,
        )!!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val key = DriveVocabularySync.wordKey("fr", "nuit")
        val before = deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_VOCAB_WORD, key)!!

        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val after = deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_VOCAB_WORD, key)!!
        assertEquals(before.versionFp, after.versionFp)
        assertEquals(before.publishedFp, after.publishedFp)
        assertEquals(before.contentFp, after.contentFp)

        repo.edit(wordId, definition = "nightfall", notes = "", now = 1_745_900_001_000L)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val edited = deviceA.driveDb.recordStates().get(PERM_A, DriveWireCodec.DOMAIN_VOCAB_WORD, key)!!
        assertTrue(before.versionFp in DriveWorkIdentity.decodeStringArray(edited.ancestorsJson))
    }

    @Test
    fun `a word deleted after a crash between its pull transactions stays deleted on replay`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        deviceB.connect(PERM_A, "a@example.com")
        val repoA = VocabularyRepository(deviceA.liseurDb)
        val now = 1_745_900_000_000L
        repoA.collect(VocabularyLookup("nuit", "fr", null), "night", "en", now)!!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val nuitB = deviceB.liseurDb.vocabularyDao().findWord("nuit", "fr")!!

        // Crash after Tx1 (word + receipt in liseur.db) and before Tx2: drive_sync.db never saw it.
        val key = DriveVocabularySync.wordKey("fr", "nuit")
        deviceB.driveDb.recordStates().delete(PERM_A, DriveWireCodec.DOMAIN_VOCAB_WORD, key)
        deviceB.driveDb.cursors().upsert(DriveCursor(PERM_A, "dev-a", DriveWireCodec.DOMAIN_VOCAB_WORD, appliedSeq = 0L))
        // The reader deletes the word before sync runs again.
        VocabularyRepository(deviceB.liseurDb).deleteWord(nuitB.id)

        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertNull(deviceB.liseurDb.vocabularyDao().findWord("nuit", "fr"))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertNull(deviceA.liseurDb.vocabularyDao().findWord("nuit", "fr"))
    }

    @Test
    fun `a delete that never saw a third device's review keeps the word on a device that did`() = runTest {
        val deviceC = createDevice("dev-c", File(tempDir, "c"), serverA)
        try {
            deviceA.connect(PERM_A, "a@example.com")
            deviceB.connect(PERM_A, "a@example.com")
            deviceC.connect(PERM_A, "a@example.com")
            val now = 1_745_950_000_000L
            VocabularyRepository(deviceA.liseurDb).collect(VocabularyLookup("etoile", "fr", null), "star", "en", now)!!
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, deviceC.changeEngine.sync().outcome)

            // C reviews and publishes; A receives the review; B deletes without ever seeing it.
            val etoileC = deviceC.liseurDb.vocabularyDao().findWord("etoile", "fr")!!
            VocabularyRepository(deviceC.liseurDb).review(etoileC, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = now + 1_000L)!!
            assertEquals(SyncOutcome.Success, deviceC.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            assertEquals(1, deviceA.liseurDb.vocabularyDao().findWord("etoile", "fr")!!.repetitions)

            val etoileB = deviceB.liseurDb.vocabularyDao().findWord("etoile", "fr")!!
            VocabularyRepository(deviceB.liseurDb).deleteWord(etoileB.id)
            serverA.hideOtherDeviceDeltasFor("dev-b")
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
            serverA.unhideAll()

            // A saw C's review, which B's delete vector does not cover: add-wins keeps the word.
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            assertNotNull(deviceA.liseurDb.vocabularyDao().findWord("etoile", "fr"))
        } finally {
            deviceC.close()
        }
    }

    @Test
    fun `a delete whose observing review has no provenance row is treated as unobserved and keeps the word`() = runTest {
        val deviceC = createDevice("dev-c", File(tempDir, "c-prov"), serverA)
        try {
            deviceA.connect(PERM_A, "a@example.com")
            deviceB.connect(PERM_A, "a@example.com")
            deviceC.connect(PERM_A, "a@example.com")
            val now = 1_745_960_000_000L
            VocabularyRepository(deviceA.liseurDb).collect(VocabularyLookup("soleil", "fr", null), "sun", "en", now)!!
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, deviceC.changeEngine.sync().outcome)

            val soleilC = deviceC.liseurDb.vocabularyDao().findWord("soleil", "fr")!!
            VocabularyRepository(deviceC.liseurDb).review(soleilC, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = now + 1_000L)!!
            assertEquals(SyncOutcome.Success, deviceC.changeEngine.sync().outcome)
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

            val soleilB = deviceB.liseurDb.vocabularyDao().findWord("soleil", "fr")!!
            VocabularyRepository(deviceB.liseurDb).deleteWord(soleilB.id)
            serverA.hideOtherDeviceDeltasFor("dev-b")
            assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
            serverA.unhideAll()

            // A lost the review's provenance row: the review must not count as observed by B.
            for (state in deviceA.driveDb.recordStates().forDomain(PERM_A, DriveWireCodec.DOMAIN_VOCAB_REVIEW)) {
                deviceA.driveDb.recordStates().delete(PERM_A, DriveWireCodec.DOMAIN_VOCAB_REVIEW, state.recordKey)
            }
            assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
            assertNotNull(deviceA.liseurDb.vocabularyDao().findWord("soleil", "fr"))
        } finally {
            deviceC.close()
        }
    }

    @Test
    fun `deleter pulling a concurrent review before publishing its delete does not claim to have observed it`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        deviceB.connect(PERM_A, "a@example.com")
        val repoA = VocabularyRepository(deviceA.liseurDb)
        val now = 1_745_970_000_000L
        val idA = repoA.collect(VocabularyLookup("fleur", "fr", null), "flower", "en", now)!!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // A deletes offline; B reviews and publishes meanwhile.
        repoA.deleteWord(idA)
        val fleurB = deviceB.liseurDb.vocabularyDao().findWord("fleur", "fr")!!
        VocabularyRepository(deviceB.liseurDb).review(fleurB, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = now + 1_000L)!!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // A reconnects: pulls B's review (staged, word absent) and then publishes its delete.
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertNotNull("B's concurrent review must survive A's delete", deviceB.liseurDb.vocabularyDao().findWord("fleur", "fr"))
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertNotNull(deviceA.liseurDb.vocabularyDao().findWord("fleur", "fr"))
    }

    @Test
    fun `deleter reconnecting first and publishing unrelated edit still preserves concurrent review via add-wins`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        deviceB.connect(PERM_A, "a@example.com")
        val repoA = VocabularyRepository(deviceA.liseurDb)
        val repoB = VocabularyRepository(deviceB.liseurDb)
        val now = 1_745_800_000_000L

        val luneIdA = repoA.collect(VocabularyLookup("lune", "fr", null), "moon", "en", now)!!
        val etoileIdA = repoA.collect(VocabularyLookup("etoile", "fr", null), "star", "en", now)!!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        // Offline concurrent actions: A deletes "lune" while B reviews "lune".
        val luneOnB = deviceB.liseurDb.vocabularyDao().findWord("lune", "fr")!!
        repoA.deleteWord(luneIdA)
        repoB.review(luneOnB, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = now + 1_000L)!!

        // Both publish independently while isolated from each other.
        serverA.hideOtherDeviceDeltasFor("dev-a")
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        serverA.hideOtherDeviceDeltasFor("dev-b")
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        serverA.unhideAll()

        // Deleter A reconnects FIRST, pulls/stages B's concurrent review, then publishes an
        // unrelated edit so A's manifest appliedVector now acknowledges B's review batch!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        repoA.edit(etoileIdA, definition = "bright star", notes = "unrelated edit", now = now + 2_000L)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        // Now reviewer B syncs and applies A's earlier delete: must use the delete's immutable
        // author-time observed vector (0), NOT A's newer manifest vector (1), so add-wins fires!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertNotNull("Add-wins must keep lune on B despite A's newer manifest vector", deviceB.liseurDb.vocabularyDao().findWord("lune", "fr"))

        // A syncs again -> resurrects "lune" with B's review attached, and both devices converge!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val luneResA = deviceA.liseurDb.vocabularyDao().findWord("lune", "fr")
        val luneResB = deviceB.liseurDb.vocabularyDao().findWord("lune", "fr")
        assertNotNull("Add-wins must resurrect lune on A", luneResA)
        assertNotNull(luneResB)
        assertEquals(1, luneResA!!.repetitions)
        assertEquals(1, luneResB!!.repetitions)
        assertEquals(luneResB.dueAt, luneResA.dueAt)
        assertEquals(1, deviceA.liseurDb.vocabularyDao().reviews(luneResA.id).size)
        assertEquals(1, deviceB.liseurDb.vocabularyDao().reviews(luneResB.id).size)
        assertEquals("bright star", deviceB.liseurDb.vocabularyDao().findWord("etoile", "fr")!!.definition)

        // Contrast: deleting "lune" on A AFTER A has observed B's review deletes silently on B.
        repoA.deleteWord(luneResA.id)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertNull(deviceB.liseurDb.vocabularyDao().findWord("lune", "fr"))
    }

    @Test
    fun `coalescing duplicate unowned and retired-account word rows collapses matching legacy null-syncId reviews into one event`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        deviceB.connect(PERM_A, "a@example.com")

        val addedAt = 1_745_900_000_000L
        val reviewedAt = addedAt + 60_000L
        val unownedWordId = deviceA.liseurDb.vocabularyDao().insertWord(
            VocabularyWord(
                word = "clarté",
                normalized = "clarté",
                language = "fr",
                definition = "clarity",
                translationLanguage = "en",
                notes = "",
                addedAt = addedAt,
                dueAt = addedAt,
                ownerAccount = "",
            ),
        )
        val retiredWordId = deviceA.liseurDb.vocabularyDao().insertWord(
            VocabularyWord(
                word = "clarté",
                normalized = "clarté",
                language = "fr",
                definition = "clarity",
                translationLanguage = "en",
                notes = "retired account note",
                addedAt = addedAt,
                dueAt = addedAt,
                ownerAccount = "liseursync|https://old.example|acct-1",
                notesUpdatedAt = addedAt + 10L,
            ),
        )
        // Insert matching legacy null-syncId reviews on BOTH the unowned and retired-account rows.
        val legacyReview = VocabularyReview(
            wordId = unownedWordId,
            rating = Recall.GOOD.name,
            reviewedAt = reviewedAt,
            intervalMinutes = 60,
            dueAt = reviewedAt + 3_600_000L,
            firstReview = true,
            syncId = null,
        )
        deviceA.liseurDb.vocabularyDao().addReview(legacyReview)
        deviceA.liseurDb.vocabularyDao().addReview(legacyReview.copy(wordId = retiredWordId))

        // Sync A -> B triggers coalescing + deterministic legacy syncId assignment.
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)

        val expectedDeterministicId = DriveVocabularySync.deterministicLegacyReviewId(
            language = "fr",
            normalized = "clarté",
            reviewedAt = reviewedAt,
            rating = Recall.GOOD.name,
        )

        val wordsOnA = deviceA.liseurDb.vocabularyDao().findAllWordsByKey("clarté", "fr")
        assertEquals(1, wordsOnA.size)
        val survivorA = wordsOnA.single()
        val reviewsOnA = deviceA.liseurDb.vocabularyDao().reviews(survivorA.id)
        assertEquals("Matching legacy reviews must collapse into one event", 1, reviewsOnA.size)
        assertEquals(expectedDeterministicId, reviewsOnA.single().syncId)
        assertTrue(reviewsOnA.single().firstReview)
        assertEquals(1, survivorA.repetitions)
        assertEquals(1, survivorA.streak)
        assertEquals("retired account note", survivorA.notes)

        val wordOnB = deviceB.liseurDb.vocabularyDao().findWord("clarté", "fr")!!
        val reviewsOnB = deviceB.liseurDb.vocabularyDao().reviews(wordOnB.id)
        assertEquals(1, reviewsOnB.size)
        assertEquals(expectedDeterministicId, reviewsOnB.single().syncId)
        assertEquals(1, wordOnB.repetitions)
        assertEquals(1, wordOnB.streak)
        assertEquals(survivorA.dueAt, wordOnB.dueAt)
    }

    // -- Harness helpers -----------------------------------------------------

    @Test
    fun `an undo recorded while its put is built but not acknowledged survives the parent word delete`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val repoA = VocabularyRepository(deviceA.liseurDb)
        val vocabSync = deviceA.driveSync.vocabularySync
        val now = 1_746_000_000_000L
        val wordId = repoA.collect(VocabularyLookup("brume", "fr", null), "mist", "en", now)!!
        val undo = repoA.review(
            deviceA.liseurDb.vocabularyDao().word(wordId)!!, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = now + 1_000L,
        )!!
        val sid = deviceA.liseurDb.vocabularyDao().review(undo.reviewId)!!.syncId!!

        val built = vocabSync.collectDirtyRecords(PERM_A) { nextTestHlc() }
        assertTrue(built.reviewAcks.any { it.reviewId == sid && !it.isDelete })

        // The user undoes while the frozen batch is uploading, then the word is deleted.
        assertTrue(repoA.undoReview(undo))
        repoA.deleteWord(wordId)
        vocabSync.acknowledgePublished(PERM_A, 50L, built.records, built.wordAcks, built.reviewAcks)

        val peerId = DriveLibrarySync.accountPeerId(PERM_A)
        assertTrue(deviceA.liseurDb.vocabularySyncDao().review(peerId, sid)!!.pendingDeleted)
        val next = vocabSync.collectDirtyRecords(PERM_A) { nextTestHlc() }
        assertTrue(
            "the undo must publish a tombstone even though its word is gone",
            next.records.any { it.d == DriveWireCodec.DOMAIN_VOCAB_REVIEW && it.k == sid && it.op == DriveRecordState.OP_DEL },
        )
    }

    @Test
    fun `deleting and recollecting a synced word does not publish review tombstones for the cascaded reviews`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val repoA = VocabularyRepository(deviceA.liseurDb)
        val now = 1_746_010_000_000L
        val lookup = VocabularyLookup("givre", "fr", null)
        val wordId = repoA.collect(lookup, "frost", "en", now)!!
        repoA.review(deviceA.liseurDb.vocabularyDao().word(wordId)!!, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = now + 1_000L)!!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val peerId = DriveLibrarySync.accountPeerId(PERM_A)
        val rows = deviceA.liseurDb.vocabularySyncDao().allReviews(peerId)
        assertEquals(1, rows.size)

        repoA.deleteWord(wordId)
        repoA.collect(lookup, "frost", "en", now + 5_000L)!!

        val bundle = deviceA.driveSync.vocabularySync.collectDirtyRecords(PERM_A) { nextTestHlc() }
        assertTrue(
            "a cascade is not an undo",
            bundle.records.none { it.d == DriveWireCodec.DOMAIN_VOCAB_REVIEW && it.op == DriveRecordState.OP_DEL },
        )
        assertFalse(deviceA.liseurDb.vocabularySyncDao().review(peerId, rows.single().reviewId)!!.ackedDeleted)
    }

    @Test
    fun `a review undo removes every copy of the event held by duplicate unowned and retired-account words`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val dao = deviceA.liseurDb.vocabularyDao()
        val addedAt = 1_746_020_000_000L
        fun word(owner: String) = VocabularyWord(
            word = "vent", normalized = "vent", language = "fr", definition = "wind", translationLanguage = "en",
            notes = "", addedAt = addedAt, dueAt = addedAt, ownerAccount = owner,
        )
        val unowned = dao.insertWord(word(""))
        val retired = dao.insertWord(word("liseursync|https://old.example|acct-1"))
        for (id in listOf(unowned, retired)) {
            dao.addReview(
                VocabularyReview(
                    wordId = id, rating = Recall.GOOD.name, reviewedAt = addedAt + 1_000L, intervalMinutes = 60,
                    dueAt = addedAt + 3_601_000L, firstReview = true, syncId = "dup-event",
                ),
            )
            deviceA.driveSync.vocabularySync.reconcileWordSchedule(id)
        }
        assertEquals(1, dao.word(unowned)!!.repetitions)
        assertEquals(1, dao.word(retired)!!.repetitions)

        deviceA.driveSync.vocabularySync.applyRemoteReview(
            PERM_A,
            DriveWireCodec.delRecord(DriveWireCodec.DOMAIN_VOCAB_REVIEW, "dup-event", nextTestHlc()),
            "dev-b",
            7L,
        )

        assertTrue(dao.reviewsBySyncIdAnyOwner("dup-event").isEmpty())
        assertEquals(0, dao.word(unowned)!!.repetitions)
        assertEquals(0, dao.word(retired)!!.repetitions)
    }

    @Test
    fun `legacy backfill does not give a tombstoned deterministic id to a restored null-syncId review`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val dao = deviceA.liseurDb.vocabularyDao()
        val addedAt = 1_746_030_000_000L
        val wordId = dao.insertWord(
            VocabularyWord(
                word = "pluie", normalized = "pluie", language = "fr", definition = "rain", translationLanguage = "en",
                notes = "", addedAt = addedAt, dueAt = addedAt,
            ),
        )
        val undoneAt = addedAt + 1_000L
        val keptAt = addedAt + 2_000L
        for (at in listOf(undoneAt, keptAt)) {
            dao.addReview(
                VocabularyReview(
                    wordId = wordId, rating = Recall.GOOD.name, reviewedAt = at, intervalMinutes = 60,
                    dueAt = at + 3_600_000L, firstReview = false, syncId = null,
                ),
            )
        }
        val undoneId = DriveVocabularySync.deterministicLegacyReviewId("fr", "pluie", undoneAt, Recall.GOOD.name)
        val keptId = DriveVocabularySync.deterministicLegacyReviewId("fr", "pluie", keptAt, Recall.GOOD.name)
        deviceA.driveSync.vocabularySync.applyRemoteReview(
            PERM_A,
            DriveWireCodec.delRecord(DriveWireCodec.DOMAIN_VOCAB_REVIEW, undoneId, nextTestHlc()),
            "dev-b",
            3L,
        )

        val bundle = deviceA.driveSync.vocabularySync.collectDirtyRecords(PERM_A) { nextTestHlc() }

        assertNull(dao.reviewBySyncIdAnyOwner(undoneId))
        assertNotNull("an untombstoned legacy review is preserved and backfilled", dao.reviewBySyncIdAnyOwner(keptId))
        assertEquals(1, dao.word(wordId)!!.repetitions)
        assertTrue(bundle.records.none { it.d == DriveWireCodec.DOMAIN_VOCAB_REVIEW && it.k == undoneId && it.op == DriveRecordState.OP_PUT })
        assertTrue(bundle.records.any { it.d == DriveWireCodec.DOMAIN_VOCAB_REVIEW && it.k == keptId && it.op == DriveRecordState.OP_PUT })
    }

    @Test
    fun `a delayed review the delete already covered is not reattached on recollect while an unobserved one survives`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val vocabSync = deviceA.driveSync.vocabularySync
        val dao = deviceA.liseurDb.vocabularyDao()
        val syncDao = deviceA.liseurDb.vocabularySyncDao()
        val peerId = DriveLibrarySync.accountPeerId(PERM_A)
        val key = DriveVocabularySync.wordKey("fr", "neige")
        val delivered = deliveredWordRecords("neige")

        // dev-x deletes after observing dev-c up to seq 3. Nothing about the word is local here.
        val del = DriveWireCodec.delRecord(DriveWireCodec.DOMAIN_VOCAB_WORD, key, nextTestHlc(), observed = mapOf("dev-c" to 3L))
        vocabSync.applyRemoteWord(PERM_A, del, "dev-x", 5L, localDeviceId = "dev-a", remoteObserved = del.observed)

        // Covered historical review arrives late; unobserved one is concurrent.
        vocabSync.applyRemoteReview(PERM_A, reviewPut("covered", "neige", 1_000L), "dev-c", 3L)
        vocabSync.applyRemoteReview(PERM_A, reviewPut("concurrent", "neige", 2_000L), "dev-c", 4L)
        assertEquals(listOf("concurrent"), syncDao.stagedForWord(peerId, "fr", "neige", 0L).map { it.reviewId })

        // A snapshot cannot prove origin seq, so it never drops on the boundary alone.
        vocabSync.applyRemoteReview(PERM_A, reviewPut("from-snapshot", "neige", 3_000L), "dev-c", 3L, provenanceKnown = false)
        assertEquals(
            setOf("concurrent", "from-snapshot"),
            syncDao.stagedForWord(peerId, "fr", "neige", 0L).map { it.reviewId }.toSet(),
        )

        // Recollect: the word's put supersedes the delete.
        val recollect = DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
            key = key,
            hlc = nextTestHlc(),
            bodyMap = DriveWireCodec.encodeVocabWordBody(DriveWireCodec.decodeVocabWordBody(delivered.body!!)!!),
            ancestors = listOf(del.fp),
        )
        vocabSync.applyRemoteWord(PERM_A, recollect, "dev-y", 6L, localDeviceId = "dev-a", remoteObserved = emptyMap())

        val word = dao.findWord("neige", "fr")!!
        val syncIds = dao.reviews(word.id).mapNotNull { it.syncId }.toSet()
        assertEquals(setOf("concurrent", "from-snapshot"), syncIds)
        assertNull(dao.reviewBySyncIdAnyOwner("covered"))
    }

    @Test
    fun `a delete applied after staged reviews drops only the covered ones`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val vocabSync = deviceA.driveSync.vocabularySync
        val syncDao = deviceA.liseurDb.vocabularySyncDao()
        val peerId = DriveLibrarySync.accountPeerId(PERM_A)
        val key = DriveVocabularySync.wordKey("fr", "orage")

        vocabSync.applyRemoteReview(PERM_A, reviewPut("covered", "orage", 1_000L), "dev-c", 3L)
        vocabSync.applyRemoteReview(PERM_A, reviewPut("concurrent", "orage", 2_000L), "dev-c", 4L)
        assertEquals(2, syncDao.stagedForWord(peerId, "fr", "orage", 0L).size)

        val del = DriveWireCodec.delRecord(DriveWireCodec.DOMAIN_VOCAB_WORD, key, nextTestHlc(), observed = mapOf("dev-c" to 3L))
        vocabSync.applyRemoteWord(PERM_A, del, "dev-x", 5L, localDeviceId = "dev-a", remoteObserved = del.observed)

        assertEquals(listOf("concurrent"), syncDao.stagedForWord(peerId, "fr", "orage", 0L).map { it.reviewId })
    }

    @Test
    fun `a published local delete covers its observed reviews so a delayed replay is not staged`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        deviceB.connect(PERM_A, "a@example.com")
        val repoA = VocabularyRepository(deviceA.liseurDb)
        val now = 1_746_040_000_000L
        val lookup = VocabularyLookup("rosee", "fr", null)
        val wordIdA = repoA.collect(lookup, "dew", "en", now)!!
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        val wordB = deviceB.liseurDb.vocabularyDao().findWord("rosee", "fr")!!
        val undoB = VocabularyRepository(deviceB.liseurDb).review(wordB, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = now + 1_000L)!!
        val review = deviceB.liseurDb.vocabularyDao().review(undoB.reviewId)!!
        assertEquals(SyncOutcome.Success, deviceB.changeEngine.sync().outcome)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)
        val seenAt = deviceA.driveDb.recordStates()
            .get(PERM_A, DriveWireCodec.DOMAIN_VOCAB_REVIEW, review.syncId!!)!!.originSeq!!

        repoA.deleteWord(wordIdA)
        assertEquals(SyncOutcome.Success, deviceA.changeEngine.sync().outcome)

        val peerId = DriveLibrarySync.accountPeerId(PERM_A)
        val replay = reviewPut(review.syncId!!, "rosee", review.reviewedAt)
        deviceA.driveSync.vocabularySync.applyRemoteReview(PERM_A, replay, "dev-b", seenAt)
        assertTrue(deviceA.liseurDb.vocabularySyncDao().stagedForWord(peerId, "fr", "rosee", 0L).isEmpty())

        // A genuinely later review from the same device is not covered.
        deviceA.driveSync.vocabularySync.applyRemoteReview(PERM_A, reviewPut("later", "rosee", review.reviewedAt + 1L), "dev-b", seenAt + 10L)
        assertEquals(listOf("later"), deviceA.liseurDb.vocabularySyncDao().stagedForWord(peerId, "fr", "rosee", 0L).map { it.reviewId })
    }

    @Test
    fun `a delete boundary of one account does not drop reviews arriving for another account`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val vocabSync = deviceA.driveSync.vocabularySync
        val syncDao = deviceA.liseurDb.vocabularySyncDao()
        val key = DriveVocabularySync.wordKey("fr", "brise")
        val del = DriveWireCodec.delRecord(DriveWireCodec.DOMAIN_VOCAB_WORD, key, nextTestHlc(), observed = mapOf("dev-c" to 9L))
        vocabSync.applyRemoteWord(PERM_A, del, "dev-x", 5L, localDeviceId = "dev-a", remoteObserved = del.observed)

        vocabSync.applyRemoteReview(PERM_A, reviewPut("same", "brise", 1_000L), "dev-c", 3L)
        vocabSync.applyRemoteReview(PERM_B, reviewPut("same", "brise", 1_000L), "dev-c", 3L)

        assertTrue(syncDao.stagedForWord(DriveLibrarySync.accountPeerId(PERM_A), "fr", "brise", 0L).isEmpty())
        assertEquals(
            listOf("same"),
            syncDao.stagedForWord(DriveLibrarySync.accountPeerId(PERM_B), "fr", "brise", 0L).map { it.reviewId },
        )
    }

    @Test
    fun `recollecting a deleted word locally attaches only its concurrent staged reviews for this account`() = runTest {
        deviceA.connect(PERM_A, "a@example.com")
        val vocabSync = deviceA.driveSync.vocabularySync
        val dao = deviceA.liseurDb.vocabularyDao()
        val syncDao = deviceA.liseurDb.vocabularySyncDao()
        val key = DriveVocabularySync.wordKey("fr", "givre")
        val del = DriveWireCodec.delRecord(DriveWireCodec.DOMAIN_VOCAB_WORD, key, nextTestHlc(), observed = mapOf("dev-c" to 3L))
        vocabSync.applyRemoteWord(PERM_A, del, "dev-x", 5L, localDeviceId = "dev-a", remoteObserved = del.observed)
        vocabSync.applyRemoteReview(PERM_A, reviewPut("covered", "givre", 1_000L), "dev-c", 3L)
        vocabSync.applyRemoteReview(PERM_A, reviewPut("concurrent", "givre", 2_000L), "dev-c", 4L)
        vocabSync.applyRemoteReview(PERM_B, reviewPut("other-account", "givre", 3_000L), "dev-c", 4L)

        val wordId = VocabularyRepository(deviceA.liseurDb)
            .collect(VocabularyLookup("givre", "fr", null), "frost", "en", 1_746_010_000_000L)!!
        assertTrue(dao.reviews(wordId).isEmpty())

        vocabSync.collectDirtyRecords(PERM_A) { nextTestHlc() }

        val word = dao.word(wordId)!!
        assertEquals(listOf("concurrent"), dao.reviews(wordId).mapNotNull { it.syncId })
        assertEquals(2_000L, word.reviewedAt)
        assertNull(dao.reviewBySyncIdAnyOwner("covered"))
        assertNull(dao.reviewBySyncIdAnyOwner("other-account"))
        assertTrue(syncDao.stagedForWord(DriveLibrarySync.accountPeerId(PERM_A), "fr", "givre", 0L).isEmpty())
        assertEquals(
            listOf("other-account"),
            syncDao.stagedForWord(DriveLibrarySync.accountPeerId(PERM_B), "fr", "givre", 0L).map { it.reviewId },
        )
    }

    private var testHlcCounter = 0

    private fun nextTestHlc(): String = "0017460000000-%04d-dev-a".format(++testHlcCounter)

    private fun reviewPut(syncId: String, normalized: String, reviewedAt: Long): DriveWireRecord =
        DriveWireCodec.putRecord(
            domain = DriveWireCodec.DOMAIN_VOCAB_REVIEW,
            key = syncId,
            hlc = nextTestHlc(),
            bodyMap = DriveWireCodec.encodeVocabReviewBody(
                DriveVocabReviewBody(
                    syncId = syncId,
                    wordKey = DriveVocabularySync.wordKey("fr", normalized),
                    language = "fr",
                    normalized = normalized,
                    rating = Recall.GOOD.name,
                    reviewedAt = reviewedAt,
                    intervalMinutes = 60,
                    dueAt = reviewedAt + 3_600_000L,
                ),
            ),
        )

    private fun deliveredWordRecords(normalized: String): DriveWireRecord = DriveWireCodec.putRecord(
        domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
        key = DriveVocabularySync.wordKey("fr", normalized),
        hlc = nextTestHlc(),
        bodyMap = DriveWireCodec.encodeVocabWordBody(
            DriveVocabWordBody(
                language = "fr",
                normalized = normalized,
                word = normalized,
                definition = "snow",
                translationLanguage = "en",
                notes = "",
                definitionEdited = false,
                definitionUpdatedAt = 0L,
                notesUpdatedAt = 0L,
                addedAt = 1_746_050_000_000L,
                baseline = null,
            ),
        ),
    )

    private suspend fun seedBook(
        device: Phase5Device,
        fileName: String,
        isbn: String,
        title: String,
        author: String,
    ): Book {
        val bytes = "EPUB-P5:$fileName:$isbn".toByteArray()
        val file = File(device.booksDir, fileName).apply { writeBytes(bytes) }
        val fp = BookFingerprints.of(ByteArrayInputStream(bytes))
        val url = file.toURI().toString()
        val book = Book(
            url = url,
            title = title,
            author = author,
            coverPath = null,
            source = null,
            addedAt = 1_000L,
            lastOpenedAt = null,
            localUri = url,
            downloadState = DownloadState.DOWNLOADED,
            sizeBytes = fp.size,
            fileTitle = title,
            fileAuthor = author,
            fileMetadataChecked = true,
            fileModifiedAt = file.lastModified(),
        )
        device.liseurDb.bookDao().upsert(book)
        device.liseurDb.workIdentityDao().upsert(
            BookFingerprintRow(url, fp.sha256, fp.partialMd5, fp.size, file.lastModified(), 1L),
        )
        device.liseurDb.bookMetadataDao().upsert(
            BookMetadata.of(url, MetadataSource.FILE, MetadataSnapshot(identifiers = listOf(Identifier("isbn", isbn)))),
        )
        return book
    }

    private fun alwaysAuthClient(token: String): DriveAuthClient = object : DriveAuthClient {
        override suspend fun authorize(accountEmail: String?, selectAccount: Boolean) =
            DriveAuthResult.Authorized(token)
        override suspend fun authorizeSilently(accountEmail: String?) =
            DriveAuthResult.Authorized(token)
        override fun parseAuthorizationResult(data: Intent?) =
            DriveAuthResult.Authorized(token)
        override suspend fun clearCachedToken(accessToken: String) = Unit
    }

    private fun createDevice(
        deviceId: String,
        dir: File,
        server: Phase5FakeDriveServer,
    ): Phase5Device {
        val booksDir = File(dir, "books").apply { mkdirs() }
        val coversDir = File(dir, "covers").apply { mkdirs() }
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
        val clock: () -> Long = { clockVal.addAndGet(100L) }
        val driveSync = DriveLibrarySync(
            context = context,
            database = liseurDb,
            remoteAccount = remoteAccount,
            bookRemoval = removal,
            switchCoordinator = LibrarySyncSwitchCoordinator(),
            deviceIdGenerator = { deviceId },
            openStore = { driveDb },
            authClient = alwaysAuthClient("token-$deviceId"),
            driveApi = server.clientFor(deviceId),
            coversDirProvider = { coversDir },
            clock = clock,
        )
        return Phase5Device(deviceId, booksDir, liseurDb, driveDb, driveSync)
    }

    private class Phase5Device(
        val deviceId: String,
        val booksDir: File,
        val liseurDb: LiseurDatabase,
        val driveDb: DriveSyncDatabase,
        val driveSync: DriveLibrarySync,
    ) {
        val changeEngine: DriveChangeEngine get() = driveSync.changeEngine

        suspend fun connect(permissionId: String, email: String) {
            driveSync.activateAccount(permissionId, email, now = 1_000L)
        }

        fun close() {
            driveDb.close()
            liseurDb.close()
        }
    }

    private class Phase5FakeDriveServer(
        private val permissionId: String = PERM_A,
        private val email: String = "a@example.com",
    ) : DriveRemoteApi {
        private data class StoredFile(
            var meta: DriveRemoteFile,
            var bytes: ByteArray,
            val space: String,
        )

        private val idGen = AtomicInteger(1)
        private val files = linkedMapOf<String, StoredFile>()
        private val changeLog = mutableListOf<DriveChange>()
        private var hiddenForDevice: String? = null
        private val hiddenSourceForViewer = mutableMapOf<String, MutableSet<String>>()

        fun hideOtherDeviceDeltasFor(deviceId: String) {
            hiddenForDevice = deviceId
        }

        fun hideSourceDeviceFrom(sourceDeviceId: String, viewerDeviceId: String) {
            hiddenSourceForViewer.getOrPut(viewerDeviceId) { mutableSetOf() }.add(sourceDeviceId)
        }

        fun unhideAll() {
            hiddenForDevice = null
            hiddenSourceForViewer.clear()
        }

        fun clientFor(deviceId: String): DriveRemoteApi = object : DriveRemoteApi by this@Phase5FakeDriveServer {
            override suspend fun listAppDataFiles(accessToken: String, query: String?, pageSize: Int): List<DriveRemoteFile> =
                this@Phase5FakeDriveServer.listAppDataFiles(accessToken, query, pageSize).filter { file ->
                    val fileOwner = file.appProperties["deviceId"]
                    val hiddenBySource = fileOwner != null && fileOwner in hiddenSourceForViewer[deviceId].orEmpty()
                    val hiddenByOffline = hiddenForDevice == deviceId &&
                        fileOwner != deviceId &&
                        file.appProperties["kind"] == DriveFile.KIND_DELTA
                    !hiddenBySource && !hiddenByOffline
                }

            override suspend fun listChanges(accessToken: String, pageToken: String, pageSize: Int): DriveChangesPage {
                if (hiddenForDevice == deviceId) {
                    return DriveChangesPage(emptyList(), null, pageToken)
                }
                val page = this@Phase5FakeDriveServer.listChanges(accessToken, pageToken, pageSize)
                val hiddenSources = hiddenSourceForViewer[deviceId].orEmpty()
                if (hiddenSources.isEmpty()) return page
                return page.copy(
                    changes = page.changes.filter { change ->
                        val owner = change.file?.appProperties?.get("deviceId")
                        owner == null || owner !in hiddenSources
                    },
                )
            }
        }

        override suspend fun getAbout(accessToken: String) =
            DriveUserAbout(permissionId, email)

        override suspend fun getStartPageToken(accessToken: String): String =
            (changeLog.size + 1).toString()

        override suspend fun listChanges(accessToken: String, pageToken: String, pageSize: Int): DriveChangesPage {
            val fromIdx = (pageToken.toIntOrNull() ?: 1) - 1
            val slice = if (fromIdx in 0..changeLog.size) changeLog.subList(fromIdx, changeLog.size).toList() else emptyList()
            return DriveChangesPage(slice, null, (changeLog.size + 1).toString())
        }

        override suspend fun listAppDataFiles(accessToken: String, query: String?, pageSize: Int): List<DriveRemoteFile> {
            val all = files.values.filter { it.space == "appDataFolder" && !it.meta.trashed }.map { it.meta }
            if (query.isNullOrBlank()) return all
            val batchMatch = Regex("key='batchId'\\s+and\\s+value='([^']+)'").find(query)?.groupValues?.get(1)
            val devMatch = Regex("key='deviceId'\\s+and\\s+value='([^']+)'").find(query)?.groupValues?.get(1)
            return all.filter { f ->
                (batchMatch == null || f.appProperties["batchId"] == batchMatch) &&
                    (devMatch == null || f.appProperties["deviceId"] == devMatch)
            }
        }

        override suspend fun listLiseurFolders(accessToken: String): List<DriveRemoteFile> =
            files.values.filter { it.space == "drive" && it.meta.mimeType == DriveRestClient.FOLDER_MIME_TYPE && !it.meta.trashed }
                .map { it.meta }

        override suspend fun listDriveEpubFiles(accessToken: String): List<DriveRemoteFile> =
            files.values.filter { it.space == "drive" && it.meta.appProperties["liseurKind"] == DriveFile.KIND_EPUB && !it.meta.trashed }
                .map { it.meta }

        override suspend fun ensureLiseurFolder(accessToken: String): LiseurFolderResolution {
            val existing = listLiseurFolders(accessToken)
            if (existing.isNotEmpty()) return LiseurFolderResolution(existing.first(), existing)
            val id = "folder-${idGen.getAndIncrement()}"
            val meta = DriveRemoteFile(id, DriveRestClient.LISEUR_FOLDER_NAME, DriveRestClient.FOLDER_MIME_TYPE, createdTime = "2026-04-01T00:00:00Z")
            files[id] = StoredFile(meta, ByteArray(0), "drive")
            changeLog.add(DriveChange(id, false, meta))
            return LiseurFolderResolution(meta, listOf(meta))
        }

        override suspend fun findAppDataFileByBatchId(accessToken: String, batchId: String): DriveRemoteFile? =
            files.values.firstOrNull { it.space == "appDataFolder" && it.meta.appProperties["batchId"] == batchId && !it.meta.trashed }?.meta

        override suspend fun createAppDataFile(
            accessToken: String,
            name: String,
            mimeType: String,
            content: ByteArray,
            appProperties: Map<String, String>,
        ): DriveRemoteFile {
            val id = "appdata-${idGen.getAndIncrement()}"
            val meta = DriveRemoteFile(id, name, mimeType, size = content.size.toLong(), appProperties = appProperties)
            files[id] = StoredFile(meta, content.copyOf(), "appDataFolder")
            changeLog.add(DriveChange(id, false, meta))
            return meta
        }

        override suspend fun updateFileMedia(
            accessToken: String,
            fileId: String,
            mimeType: String,
            content: ByteArray,
            appProperties: Map<String, String>?,
        ): DriveRemoteFile {
            val stored = files[fileId] ?: throw DriveNotFoundException("Not found: $fileId", fileId)
            val updated = stored.meta.copy(
                size = content.size.toLong(),
                appProperties = appProperties ?: stored.meta.appProperties,
            )
            stored.meta = updated
            stored.bytes = content.copyOf()
            changeLog.add(DriveChange(fileId, false, updated))
            return updated
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

        override suspend fun downloadMedia(accessToken: String, fileId: String): ByteArray =
            files[fileId]?.bytes?.copyOf() ?: throw DriveNotFoundException("Not found: $fileId", fileId)

        override suspend fun downloadResumable(
            accessToken: String,
            fileId: String,
            destination: OutputStream,
            startOffset: Long,
            onRestartFromZero: () -> Unit,
            onProgress: (Long) -> Unit,
        ): DriveDownloadResult {
            val bytes = downloadMedia(accessToken, fileId)
            val slice = bytes.copyOfRange(startOffset.toInt(), bytes.size)
            destination.write(slice)
            onProgress(bytes.size.toLong())
            return DriveDownloadResult(slice.size.toLong(), bytes.size.toLong(), false)
        }

        override suspend fun trashFile(accessToken: String, fileId: String): DriveRemoteFile {
            val stored = files[fileId] ?: throw DriveNotFoundException("Not found: $fileId", fileId)
            stored.meta = stored.meta.copy(trashed = true)
            changeLog.add(DriveChange(fileId, false, stored.meta))
            return stored.meta
        }

        override suspend fun deleteFile(accessToken: String, fileId: String) {
            files.remove(fileId)
            changeLog.add(DriveChange(fileId, true, null))
        }

        override suspend fun initiateResumableUpload(
            accessToken: String,
            name: String,
            mimeType: String,
            totalBytes: Long,
            parents: List<String>,
            appProperties: Map<String, String>,
        ): String = "https://upload.example/${idGen.getAndIncrement()}"

        override suspend fun queryResumableUploadOffset(
            accessToken: String,
            sessionUri: String,
            totalBytes: Long,
        ): ResumableUploadStatus = ResumableUploadStatus.InProgress(0L)

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
            val id = "epub-${idGen.getAndIncrement()}"
            val meta = DriveRemoteFile(id, name, mimeType, parents = parents, appProperties = appProperties, size = bytes.size.toLong())
            files[id] = StoredFile(meta, bytes, "drive")
            changeLog.add(DriveChange(id, false, meta))
            return meta
        }
    }

    private companion object {
        const val PERM_A = "perm-account-a"
        const val PERM_B = "perm-account-b"
    }
}
