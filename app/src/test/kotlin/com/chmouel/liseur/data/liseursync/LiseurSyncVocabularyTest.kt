package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.VocabularyWordSync
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.vocabulary.Recall
import com.chmouel.liseur.vocabulary.VocabularyLookup
import com.chmouel.liseur.vocabulary.VocabularyOccurrence
import com.chmouel.liseur.vocabulary.VocabularyRepository
import com.chmouel.liseur.vocabulary.readVocabulary
import com.chmouel.liseur.vocabulary.writeVocabularyJson
import java.io.StringWriter
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
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

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncVocabularyTest {

    private lateinit var db: LiseurDatabase
    private lateinit var server: MockWebServer
    private lateinit var account: RemoteServer
    private lateinit var repo: VocabularyRepository
    private lateinit var sync: LiseurSyncVocabulary
    private lateinit var fake: FakeVocabularyServer

    @Before
    fun setUp() = runTest {
        CredentialCipher.keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        fake = FakeVocabularyServer()
        server = MockWebServer()
        server.dispatcher = fake.dispatcher
        server.start(InetAddress.getByName("127.0.0.1"), 0)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()

        account = makeServer("alice", "acct-alice")
        db.remoteServerDao().upsert(account)

        repo = VocabularyRepository(db)
        sync = LiseurSyncVocabulary(
            servers = db.remoteServerDao(),
            identities = db.workIdentityDao(),
            vocabularyDao = db.vocabularyDao(),
            syncDao = db.vocabularySyncDao(),
            inTransaction = { block -> db.withTransaction { block() } },
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
        CredentialCipher.keyForTesting = null
    }

    private fun makeServer(user: String, accountId: String): RemoteServer = RemoteServer(
        kind = ServerKind.LISEUR_SYNC,
        baseUrl = "http://127.0.0.1:${server.port}",
        username = user,
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = "dev-$user",
        userId = null,
        koboTokenCipher = null,
        canDownload = true,
        addedAt = 1L,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
        liseurTokenCipher = RemoteServer.seal("tok-$user"),
        liseurAccountId = accountId,
    )

    @Test
    fun `round trip enrolls unowned word, pushes word and review, and pulls on fresh device`() = runTest {
        // Insert an unowned word (created signed-out) before syncing.
        db.remoteServerDao().delete()
        val id = checkNotNull(
            repo.collect(
                VocabularyLookup(
                    word = "Ephemeral",
                    language = "en",
                    occurrence = VocabularyOccurrence(
                        bookUrl = "file:///book1.epub",
                        bookTitle = "Dune",
                        locationKey = "ch1:10",
                        locator = """{"href":"ch1.xhtml"}""",
                        sentence = "An ephemeral breeze.",
                        lastSeenAt = 1_000L,
                    ),
                ),
                definition = "lasting a very short time",
                translationLanguage = null,
                now = 1_000L,
            ),
        )
        assertEquals("", db.vocabularyDao().word(id)!!.ownerAccount)
        val word = db.vocabularyDao().word(id)!!
        assertNotNull(repo.review(word, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = 2_000L))

        // Sign in as Alice and sync.
        db.remoteServerDao().upsert(account)
        sync.sync(account)

        // F14: Word was enrolled into Alice's ownerAccount and pushed with its review.
        val enrolled = db.vocabularyDao().word(id)!!
        assertEquals(account.accountKey, enrolled.ownerAccount)
        assertEquals(1, fake.words.size)
        assertEquals(1, fake.reviews.size)
        val wordSync = db.vocabularySyncDao().word(account.accountKey, "en", "ephemeral")!!
        assertEquals(1L, wordSync.rev)
        assertEquals(1L, wordSync.generation)
        assertEquals(id, wordSync.syncedWordId)

        // Second device pulls from scratch and gets identical word + reconciled schedule.
        val db2 = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            db2.remoteServerDao().upsert(account)
            val sync2 = LiseurSyncVocabulary(
                servers = db2.remoteServerDao(),
                identities = db2.workIdentityDao(),
                vocabularyDao = db2.vocabularyDao(),
                syncDao = db2.vocabularySyncDao(),
                inTransaction = { block -> db2.withTransaction { block() } },
            )
            sync2.sync(account)

            val pulled = db2.vocabularyDao().findWordForOwner("ephemeral", "en", account.accountKey)
            assertNotNull(pulled)
            assertEquals("lasting a very short time", pulled!!.definition)
            assertEquals(1, pulled.repetitions)
            assertEquals(1, pulled.streak)
            val occs = db2.vocabularyDao().occurrencesList(pulled.id)
            assertEquals(1, occs.size)
            assertEquals("An ephemeral breeze.", occs[0].sentence)
            val revs = db2.vocabularyDao().reviews(pulled.id)
            assertEquals(1, revs.size)
            assertTrue(revs[0].firstReview)
        } finally {
            db2.close()
        }
    }

    @Test
    fun `offline concurrent reviews merge deterministically and undo recomputes schedule`() = runTest {
        val id = checkNotNull(
            repo.collect(VocabularyLookup("Labyrinth", "en", null), "A maze", null, now = 1_000L),
        )
        sync.sync(account)

        // Local device reviews GOOD at t=2000; peer reviews EASY at t=3000 directly on server.
        val undo = checkNotNull(
            repo.review(db.vocabularyDao().word(id)!!, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = 2_000L),
        )
        fake.injectReview(
            reviewId = "peer-rev-1",
            language = "en",
            normalized = "labyrinth",
            wordGeneration = 1,
            rating = "EASY",
            reviewedAt = 3_000L,
            intervalMinutes = 720,
            dueAt = 3_000L + 720 * 60_000L,
        )

        sync.sync(account)
        val afterMerge = db.vocabularyDao().word(id)!!
        assertEquals(2, afterMerge.repetitions)
        assertEquals(3, afterMerge.streak) // GOOD (0->1), EASY (1->3)

        // Peer undoes its review -> schedule recomputes back to 1 repetition, streak 1.
        fake.tombstoneReview("peer-rev-1")
        sync.sync(account)
        val afterPeerUndo = db.vocabularyDao().word(id)!!
        assertEquals(1, afterPeerUndo.repetitions)
        assertEquals(1, afterPeerUndo.streak)

        // Local device undoes its own review -> server receives delete tombstone.
        assertTrue(repo.undoReview(undo))
        sync.sync(account)
        val synced = fake.reviews.values.filter { !it.optBoolean("deleted", false) }
        assertTrue(synced.isEmpty())
    }

    @Test
    fun `F3 delete then recollect retires old generation and resurrects without resurrecting old reviews`() = runTest {
        val id1 = checkNotNull(
            repo.collect(VocabularyLookup("Serendipity", "en", null), "Happy accident", null, now = 1_000L),
        )
        sync.sync(account)
        assertNotNull(
            repo.review(db.vocabularyDao().word(id1)!!, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = 2_000L),
        )
        sync.sync(account)

        // Delete and immediately re-collect offline.
        repo.deleteWord(id1)
        assertEquals(
            VocabularyWordSync.LOCAL_DELETE_PENDING,
            db.vocabularySyncDao().word(account.accountKey, "en", "serendipity")!!.lifecycle,
        )
        val id2 = checkNotNull(
            repo.collect(VocabularyLookup("Serendipity", "en", null), "Fresh meaning", null, now = 3_000L),
        )
        assertEquals(
            VocabularyWordSync.DELETE_THEN_RESURRECT,
            db.vocabularySyncDao().word(account.accountKey, "en", "serendipity")!!.lifecycle,
        )

        sync.sync(account)
        val wSync = db.vocabularySyncDao().word(account.accountKey, "en", "serendipity")!!
        assertEquals(VocabularyWordSync.SYNCED, wSync.lifecycle)
        assertEquals(2L, wSync.generation)
        assertEquals(id2, wSync.syncedWordId)
        assertEquals("Fresh meaning", db.vocabularyDao().word(id2)!!.definition)
        assertEquals(0, db.vocabularyDao().reviews(id2).size)
    }

    @Test
    fun `F15 page boundary stages review until parent word arrives and review tombstone clears staged`() = runTest {
        // Page 1 has two live reviews (one will be tombstoned before word arrives) and no word yet.
        // Page 2 has the review tombstone for r-undone and the parent word at gen 1.
        fake.customPages = listOf(
            JSONObject()
                .put("words", JSONArray())
                .put(
                    "reviews",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("review_id", "r-keep")
                                .put("language", "en")
                                .put("normalized", "quixotic")
                                .put("word_generation", 1)
                                .put("seq", 1)
                                .put("rating", "GOOD")
                                .put("reviewed_at", "2026-01-01T00:01:00Z")
                                .put("interval_minutes", 30)
                                .put("due_at", "2026-01-01T00:31:00Z"),
                        )
                        .put(
                            JSONObject()
                                .put("review_id", "r-undone")
                                .put("language", "en")
                                .put("normalized", "quixotic")
                                .put("word_generation", 1)
                                .put("seq", 2)
                                .put("rating", "EASY")
                                .put("reviewed_at", "2026-01-01T00:02:00Z")
                                .put("interval_minutes", 720)
                                .put("due_at", "2026-01-01T12:02:00Z"),
                        ),
                )
                .put("high_water", 4)
                .put("next_cursor", 2)
                .put("has_more", true),
            JSONObject()
                .put(
                    "words",
                    JSONArray().put(
                        JSONObject()
                            .put("language", "en")
                            .put("normalized", "quixotic")
                            .put("word", "Quixotic")
                            .put("definition", "idealistic")
                            .put("notes", "")
                            .put("added_at", "2026-01-01T00:00:00Z")
                            .put("occurrences", JSONArray())
                            .put("rev", 1)
                            .put("generation", 1)
                            .put("seq", 4),
                    ),
                )
                .put(
                    "reviews",
                    JSONArray().put(
                        JSONObject()
                            .put("review_id", "r-undone")
                            .put("language", "en")
                            .put("normalized", "quixotic")
                            .put("word_generation", 1)
                            .put("seq", 3)
                            .put("deleted", true),
                    ),
                )
                .put("high_water", 4)
                .put("next_cursor", 4)
                .put("has_more", false),
        )

        sync.sync(account)

        val word = db.vocabularyDao().findWordForOwner("quixotic", "en", account.accountKey)!!
        assertEquals(1, word.repetitions)
        assertEquals(1, word.streak)
        val reviews = db.vocabularyDao().reviews(word.id)
        assertEquals(1, reviews.size)
        assertEquals("r-keep", reviews[0].syncId)
        assertTrue(reviews[0].firstReview)
        assertEquals(4L, db.remoteServerDao().get()!!.vocabularyCursorSeq)
    }

    @Test
    fun `account switch isolates words so Account B never sees or uploads Account A words`() = runTest {
        val idA = checkNotNull(
            repo.collect(VocabularyLookup("Solitude", "en", null), "Alice's private note", null, now = 1_000L),
        )
        sync.sync(account)
        assertEquals(account.accountKey, db.vocabularyDao().word(idA)!!.ownerAccount)

        // Switch to Account B and clear peer sync tables (simulating forgetSyncPeer).
        db.vocabularySyncDao().forgetPeer(account.accountKey)
        val accountB = makeServer("bob", "acct-bob")
        db.remoteServerDao().upsert(accountB)
        fake.words.clear()
        fake.reviews.clear()

        // Bob should NOT see Alice's word via DAO queries and syncing Bob must NOT upload Alice's word.
        assertNull(db.vocabularyDao().findWord("solitude", "en"))
        sync.sync(accountB)
        assertTrue(fake.words.isEmpty())

        // Bob can collect his own "Solitude" without colliding with Alice's row.
        val idB = checkNotNull(
            repo.collect(VocabularyLookup("Solitude", "en", null), "Bob's meaning", null, now = 2_000L),
        )
        sync.sync(accountB)
        assertEquals("Bob's meaning", fake.words["en|solitude"]!!.optString("definition"))
        assertEquals("Alice's private note", db.vocabularyDao().word(idA)!!.definition)
        assertEquals("Bob's meaning", db.vocabularyDao().word(idB)!!.definition)
    }

    @Test
    fun `invalid server refusal records refusedFingerprint without infinite retry`() = runTest {
        fake.refuseNormalized = "rejected"
        checkNotNull(repo.collect(VocabularyLookup("Rejected", "en", null), "Too big", null, now = 1_000L))
        checkNotNull(repo.collect(VocabularyLookup("Accepted", "en", null), "Fine", null, now = 1_000L))

        sync.sync(account)
        val refused = db.vocabularySyncDao().word(account.accountKey, "en", "rejected")!!
        assertEquals("word limit reached", refused.refusedReason)
        assertNotNull(refused.refusedFingerprint)
        assertTrue(fake.words.containsKey("en|accepted"))

        val pushesBefore = fake.wordPushCount
        sync.sync(account)
        assertEquals(pushesBefore, fake.wordPushCount)
    }

    @Test
    fun `concurrent undo during in-flight review UPSERT is preserved on ack and pushed as DELETE`() = runBlocking {
        val id = checkNotNull(
            repo.collect(VocabularyLookup("Ephemera", "en", null), "Short-lived things", null, now = 1_000L),
        )
        sync.sync(account)

        val undo = checkNotNull(
            repo.review(db.vocabularyDao().word(id)!!, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = 2_000L),
        )
        val reviewSyncId = checkNotNull(db.vocabularyDao().reviews(id).single().syncId)

        val upsertInFlight = CountDownLatch(1)
        val releaseUpsert = CountDownLatch(1)
        fake.onBeforeReviewPushResponse = { body ->
            val first = body.getJSONArray("reviews").getJSONObject(0)
            if (!first.optBoolean("deleted", false)) {
                upsertInFlight.countDown()
                assertTrue(releaseUpsert.await(10, TimeUnit.SECONDS))
            }
        }

        val firstSync = async(Dispatchers.IO) { sync.sync(account) }
        assertTrue(upsertInFlight.await(10, TimeUnit.SECONDS))

        // Undo while the review UPSERT HTTP response is still paused.
        assertTrue(repo.undoReview(undo))
        releaseUpsert.countDown()
        firstSync.await()
        fake.onBeforeReviewPushResponse = null

        val afterUpsertAck = db.vocabularySyncDao().review(account.accountKey, reviewSyncId)!!
        assertFalse(afterUpsertAck.ackedDeleted)
        assertTrue(afterUpsertAck.pendingDeleted)
        assertNull(afterUpsertAck.pendingJson)

        // Next sync must push the DELETE tombstone without resurrecting the review.
        sync.sync(account)
        assertTrue(db.vocabularyDao().reviews(id).isEmpty())
        assertTrue(fake.reviews[reviewSyncId]!!.optBoolean("deleted", false))
        val settled = db.vocabularySyncDao().review(account.accountKey, reviewSyncId)!!
        assertTrue(settled.ackedDeleted)
        assertFalse(settled.pendingDeleted)
    }

    @Test
    fun `pulling live review does not resurrect or cancel pending local undo`() = runTest {
        val id = checkNotNull(
            repo.collect(VocabularyLookup("Halcyon", "en", null), "Calm and peaceful", null, now = 1_000L),
        )
        sync.sync(account)

        val undo = checkNotNull(
            repo.review(db.vocabularyDao().word(id)!!, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = 2_000L),
        )
        val reviewSyncId = checkNotNull(db.vocabularyDao().reviews(id).single().syncId)

        // Push review; cursor has not yet advanced past the pushed review's seq.
        sync.sync(account)
        assertTrue(db.remoteServerDao().get()!!.vocabularyCursorSeq < fake.reviews[reviewSyncId]!!.getLong("seq"))

        // Undo immediately before any subsequent pull advances the cursor.
        assertTrue(repo.undoReview(undo))

        // Next sync pulls the pushed live review first, then pushes the pending DELETE tombstone.
        sync.sync(account)
        assertTrue(db.vocabularyDao().reviews(id).isEmpty())
        assertEquals(0, db.vocabularyDao().word(id)!!.repetitions)
        assertTrue(fake.reviews[reviewSyncId]!!.optBoolean("deleted", false))
        val settled = db.vocabularySyncDao().review(account.accountKey, reviewSyncId)!!
        assertTrue(settled.ackedDeleted)
        assertFalse(settled.pendingDeleted)
    }

    @Test
    fun `Account B cannot export, edit, or delete Account A word by stale id`() = runTest {
        val idA = checkNotNull(
            repo.collect(
                VocabularyLookup(
                    word = "Aureole",
                    language = "en",
                    occurrence = VocabularyOccurrence(
                        bookUrl = "file:///alice.epub",
                        bookTitle = "Alice Book",
                        locationKey = "ch1:1",
                        locator = null,
                        sentence = "Alice sentence.",
                        lastSeenAt = 1_000L,
                    ),
                ),
                definition = "Alice's definition",
                translationLanguage = null,
                now = 1_000L,
            ),
        )
        val occIdA = db.vocabularyDao().occurrencesList(idA).single().id
        sync.sync(account)

        // Switch to Account B.
        db.vocabularySyncDao().forgetPeer(account.accountKey)
        val accountB = makeServer("bob", "acct-bob")
        db.remoteServerDao().upsert(accountB)

        // Account B's export must not leak Account A's word.
        assertTrue(repo.export().isEmpty())

        // Account B's by-ID mutations must not mutate or delete Account A's word or occurrence.
        repo.edit(idA, "Tampered", "Tampered note", now = 2_000L)
        repo.editSentence(occIdA, "Tampered sentence", now = 2_000L)
        repo.deleteWord(idA)

        val aliceWord = db.vocabularyDao().word(idA)
        assertNotNull(aliceWord)
        assertEquals("Alice's definition", aliceWord!!.definition)
        assertEquals("", aliceWord.notes)
        assertEquals("Alice sentence.", db.vocabularyDao().occurrencesList(idA).single().sentence)
    }

    @Test
    fun `fresh-device JSON export then import then sync avoids duplicate reviews and keeps manual sentence edits`() = runTest {
        val id = checkNotNull(
            repo.collect(
                VocabularyLookup(
                    word = "Petrichor",
                    language = "en",
                    occurrence = VocabularyOccurrence(
                        bookUrl = "file:///rain.epub",
                        bookTitle = "Rain",
                        locationKey = "ch2:5",
                        locator = """{"href":"ch2.xhtml","type":"application/xhtml+xml"}""",
                        sentence = "Auto sentence.",
                        lastSeenAt = 1_000L,
                    ),
                ),
                definition = "Earth scent after rain",
                translationLanguage = null,
                now = 1_000L,
            ),
        )
        assertNotNull(
            repo.review(db.vocabularyDao().word(id)!!, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = 2_000L),
        )
        // Sync the word + auto sentence + review to the server first.
        sync.sync(account)
        val originalReviewSyncId = checkNotNull(db.vocabularyDao().reviews(id).single().syncId)

        // Manually edit the sentence locally and export JSON before syncing the edit.
        val occId = db.vocabularyDao().occurrencesList(id).single().id
        repo.editSentence(occId, "Manually edited sentence.", now = 3_000L)
        val exportedJson = StringWriter().also { writeVocabularyJson(repo.export(), it) }.toString()

        // Fresh device imports the JSON backup, then signs in and syncs with the server.
        val db2 = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repo2 = VocabularyRepository(db2)
            assertEquals(1, repo2.import(readVocabulary(exportedJson.reader(), now = 4_000L)))

            db2.remoteServerDao().upsert(account)
            val sync2 = LiseurSyncVocabulary(
                servers = db2.remoteServerDao(),
                identities = db2.workIdentityDao(),
                vocabularyDao = db2.vocabularyDao(),
                syncDao = db2.vocabularySyncDao(),
                inTransaction = { block -> db2.withTransaction { block() } },
            )
            sync2.sync(account)

            val word2 = checkNotNull(db2.vocabularyDao().findWordForOwner("petrichor", "en", account.accountKey))
            val reviews2 = db2.vocabularyDao().reviews(word2.id)
            assertEquals(1, reviews2.size)
            assertEquals(originalReviewSyncId, reviews2.single().syncId)
            assertEquals(1, fake.reviews.size)

            val occ2 = db2.vocabularyDao().occurrencesList(word2.id).single()
            assertEquals("Manually edited sentence.", occ2.sentence)
            assertTrue(occ2.sentenceEdited)
            assertEquals(3_000L, occ2.sentenceUpdatedAt)
        } finally {
            db2.close()
        }
    }

    @Test
    fun `import adopts canonical syncId onto migrated null-syncId review without duplicating locally or on server`() = runTest {
        // Create a local word with a migrated (v83) review whose syncId is null.
        val id = checkNotNull(
            repo.collect(VocabularyLookup("Vellichor", "en", null), "Wistfulness of used bookstores", null, now = 1_000L),
        )
        assertNotNull(
            repo.review(db.vocabularyDao().word(id)!!, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = 2_000L),
        )
        // Build a JSON backup carrying that same review with canonical syncId "r-canon",
        // then simulate a migrated (v83) local row whose syncId is null.
        val exportedRecords = repo.export().map { record ->
            record.copy(reviews = record.reviews.map { it.copy(syncId = "r-canon") })
        }
        val backupJson = StringWriter().also { writeVocabularyJson(exportedRecords, it) }.toString()
        val localReview = db.vocabularyDao().reviews(id).single()
        db.vocabularyDao().updateReview(localReview.copy(syncId = null))
        assertNull(db.vocabularyDao().reviews(id).single().syncId)

        // Seed the server with the word (gen 1) and the canonical review "r-canon".
        fake.seq++
        fake.words["en|vellichor"] = JSONObject()
            .put("language", "en")
            .put("normalized", "vellichor")
            .put("word", "Vellichor")
            .put("definition", "Wistfulness of used bookstores")
            .put("notes", "")
            .put("added_at", java.time.Instant.ofEpochMilli(1_000L).toString())
            .put("occurrences", JSONArray())
            .put("rev", 1)
            .put("generation", 1)
            .put("seq", fake.seq)
            .put("deleted", false)
        fake.injectReview(
            reviewId = "r-canon",
            language = "en",
            normalized = "vellichor",
            wordGeneration = 1,
            rating = localReview.rating,
            reviewedAt = localReview.reviewedAt,
            intervalMinutes = localReview.intervalMinutes,
            dueAt = localReview.dueAt,
        )

        // Import the JSON backup onto the existing word and verify canonical syncId was adopted in place.
        assertEquals(1, repo.import(readVocabulary(backupJson.reader(), now = 3_000L)))
        val afterImport = db.vocabularyDao().reviews(id)
        assertEquals(1, afterImport.size)
        assertEquals(localReview.id, afterImport.single().id)
        assertEquals("r-canon", afterImport.single().syncId)

        // Sync against the server holding "r-canon" and assert no duplicate review locally or pushed.
        sync.sync(account)
        val afterSync = db.vocabularyDao().reviews(id)
        assertEquals(1, afterSync.size)
        assertEquals("r-canon", afterSync.single().syncId)
        assertEquals(setOf("r-canon"), fake.reviews.keys)
        assertEquals(0, fake.reviewPushCount)
    }

    @Test
    fun `JSON export preserves imported baseline alongside newer reviews and undo rolls back cleanly`() = runTest {
        val wordId = db.vocabularyDao().insertWord(
            com.chmouel.liseur.vocabulary.VocabularyWord(
                word = "Sonder",
                normalized = "sonder",
                language = "en",
                definition = "Realization that others have vivid lives",
                translationLanguage = null,
                addedAt = 1_000L,
                dueAt = 2_000L,
                reviewedAt = 1_500L,
                streak = 2,
                intervalMinutes = 720,
                repetitions = 3,
                ownerAccount = account.accountKey,
                baselineDueAt = 2_000L,
                baselineReviewedAt = 1_500L,
                baselineStreak = 2,
                baselineIntervalMinutes = 720,
                baselineRepetitions = 3,
            ),
        )
        // Add one newer review on top of the imported baseline (repetitions 3 -> 4, streak 2 -> 3).
        assertNotNull(
            repo.review(db.vocabularyDao().word(wordId)!!, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = 3_000L),
        )
        val exported = java.io.StringWriter().also { writeVocabularyJson(repo.export(), it) }.toString()

        // Restore into a fresh DB and verify original baseline (not snapshot of rep=4) is preserved.
        val db2 = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            db2.remoteServerDao().upsert(account)
            val repo2 = VocabularyRepository(db2)
            assertEquals(1, repo2.import(readVocabulary(exported.reader(), now = 4_000L)))
            val restored = db2.vocabularyDao().findWordForOwner("sonder", "en", account.accountKey)!!
            assertEquals(3, restored.baselineRepetitions)
            assertEquals(2, restored.baselineStreak)
            assertEquals(1_500L, restored.baselineReviewedAt)
            assertEquals(2_000L, restored.baselineDueAt)
            assertEquals(4, restored.repetitions)
            assertEquals(3, restored.streak)
        } finally {
            db2.close()
        }
    }

    @Test
    fun `pulling own word echo keeps existing occurrence IDs stable`() = runTest {
        val wordId = checkNotNull(
            repo.collect(
                VocabularyLookup(
                    word = "Petrichor",
                    language = "en",
                    occurrence = VocabularyOccurrence(
                        bookUrl = "file:///rain.epub",
                        bookTitle = "Rain",
                        locationKey = "ch1:5",
                        locator = null,
                        sentence = "Scent of rain.",
                        lastSeenAt = 1_000L,
                    ),
                ),
                definition = "Smell of rain on dry earth",
                translationLanguage = null,
                now = 1_000L,
            ),
        )
        val initialOccId = db.vocabularyDao().occurrencesList(wordId).single().id

        // Push, then pull the server's own echo (cursor was 0 before first sync).
        sync.sync(account)
        sync.sync(account)

        val afterEcho = db.vocabularyDao().occurrencesList(wordId).single()
        assertEquals(initialOccId, afterEcho.id)
    }

    @Test
    fun `JSON export preserves occurrenceKey and workId for unbound occurrences without duplicating on sync`() = runTest {
        val canonKey = com.chmouel.liseur.domain.VocabularyMerge.canonicalOccurrenceKey("work-42", "ch2:10")
        fake.seq++
        fake.words["en|mellifluous"] = JSONObject()
            .put("language", "en")
            .put("normalized", "mellifluous")
            .put("word", "Mellifluous")
            .put("definition", "Sweet-sounding")
            .put("notes", "")
            .put("added_at", java.time.Instant.ofEpochMilli(1_000L).toString())
            .put(
                "occurrences",
                JSONArray().put(
                    JSONObject()
                        .put("occurrence_key", canonKey)
                        .put("work_id", "work-42")
                        .put("book_title", "Remote Book")
                        .put("location_key", "ch2:10")
                        .put("sentence", "A mellifluous voice.")
                        .put("last_seen_at", java.time.Instant.ofEpochMilli(1_000L).toString())
                        .put("encounters", 1),
                ),
            )
            .put("rev", 1)
            .put("generation", 1)
            .put("seq", fake.seq)
            .put("deleted", false)

        // Device 1 pulls word without having the book locally (bookUrl == ""), then exports JSON.
        sync.sync(account)
        val occ1 = db.vocabularyDao().occurrencesList(
            db.vocabularyDao().findWordForOwner("mellifluous", "en", account.accountKey)!!.id,
        ).single()
        assertEquals("", occ1.bookUrl)
        assertEquals(canonKey, occ1.occurrenceKey)
        assertEquals("work-42", occ1.workId)
        val exported = java.io.StringWriter().also { writeVocabularyJson(repo.export(), it) }.toString()

        // Device 2 imports the JSON and syncs against the same account -> still 1 occurrence, no duplicate.
        val db2 = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            db2.remoteServerDao().upsert(account)
            val repo2 = VocabularyRepository(db2)
            val sync2 = LiseurSyncVocabulary(
                servers = db2.remoteServerDao(),
                identities = db2.workIdentityDao(),
                vocabularyDao = db2.vocabularyDao(),
                syncDao = db2.vocabularySyncDao(),
                inTransaction = { block -> db2.withTransaction { block() } },
            )
            assertEquals(1, repo2.import(readVocabulary(exported.reader(), now = 2_000L)))
            sync2.sync(account)
            val w2 = db2.vocabularyDao().findWordForOwner("mellifluous", "en", account.accountKey)!!
            val occs2 = db2.vocabularyDao().occurrencesList(w2.id)
            assertEquals(1, occs2.size)
            assertEquals(canonKey, occs2.single().occurrenceKey)
            assertEquals("work-42", occs2.single().workId)
        } finally {
            db2.close()
        }
    }

    @Test
    fun `pulled occurrence with empty bookUrl rebinds when local work alias arrives`() = runTest {
        val canonKey = com.chmouel.liseur.domain.VocabularyMerge.canonicalOccurrenceKey("work-99", "ch3:1")
        fake.seq++
        fake.words["en|halcyon"] = JSONObject()
            .put("language", "en")
            .put("normalized", "halcyon")
            .put("word", "Halcyon")
            .put("definition", "Calm and peaceful")
            .put("notes", "")
            .put("added_at", java.time.Instant.ofEpochMilli(1_000L).toString())
            .put(
                "occurrences",
                JSONArray().put(
                    JSONObject()
                        .put("occurrence_key", canonKey)
                        .put("work_id", "work-99")
                        .put("book_title", "Later Book")
                        .put("location_key", "ch3:1")
                        .put("sentence", "Halcyon days.")
                        .put("last_seen_at", java.time.Instant.ofEpochMilli(1_000L).toString())
                        .put("encounters", 1),
                ),
            )
            .put("rev", 1)
            .put("generation", 1)
            .put("seq", fake.seq)
            .put("deleted", false)

        // First sync pulls the word before the book is downloaded locally.
        sync.sync(account)
        val wordId = db.vocabularyDao().findWordForOwner("halcyon", "en", account.accountKey)!!.id
        assertEquals("", db.vocabularyDao().occurrencesList(wordId).single().bookUrl)

        // Later, the book arrives and receives a usable WorkAlias for work-99.
        db.workIdentityDao().upsert(
            com.chmouel.liseur.data.db.WorkAlias(
                bookUrl = "file:///later-book.epub",
                peerId = account.accountKey,
                workId = "work-99",
                confidence = "exact",
                confirmed = true,
                editionSha = "sha-99",
                resolvedAt = 2_000L,
            ),
        )

        // Next sync rebinds the occurrence's empty bookUrl to the local book.
        sync.sync(account)
        val rebound = db.vocabularyDao().occurrencesList(wordId).single()
        assertEquals("file:///later-book.epub", rebound.bookUrl)
        assertEquals(canonKey, rebound.occurrenceKey)
    }

    @Test
    fun `import honors acked review tombstones and recomputes schedule`() = runTest {
        val wordId = checkNotNull(
            repo.collect(VocabularyLookup("Nostalgia", "en", null), "Longing", null, now = 1_000L),
        )
        val undo = checkNotNull(
            repo.review(db.vocabularyDao().word(wordId)!!, Recall.GOOD, newLimit = 0, reviewLimit = 0, now = 2_000L),
        )
        sync.sync(account)
        val backupBeforeUndo = java.io.StringWriter().also { writeVocabularyJson(repo.export(), it) }.toString()

        // Undo and sync so the review tombstone is acknowledged.
        assertTrue(repo.undoReview(undo))
        sync.sync(account)
        assertEquals(0, db.vocabularyDao().word(wordId)!!.repetitions)

        // Importing the older backup must NOT restore the undone review or inflate repetitions.
        assertEquals(1, repo.import(readVocabulary(backupBeforeUndo.reader(), now = 5_000L)))
        val afterImport = db.vocabularyDao().word(wordId)!!
        assertEquals(0, afterImport.repetitions)
        assertEquals(0, afterImport.streak)
        assertTrue(db.vocabularyDao().reviews(wordId).isEmpty())
    }

    @Test
    fun `resolveLocalOccurrences refreshes stale imported workId from current account alias`() = runTest {
        val wordId = checkNotNull(
            repo.collect(
                VocabularyLookup(
                    word = "Aether",
                    language = "en",
                    occurrence = VocabularyOccurrence(
                        bookUrl = "file:///book-a.epub",
                        bookTitle = "Book A",
                        locationKey = "ch1:1",
                        locator = null,
                        sentence = "Through the aether.",
                        lastSeenAt = 1_000L,
                        workId = "foreign-work-id",
                    ),
                ),
                definition = "Upper sky",
                translationLanguage = null,
                now = 1_000L,
            ),
        )
        db.workIdentityDao().upsert(
            com.chmouel.liseur.data.db.WorkAlias(
                bookUrl = "file:///book-a.epub",
                peerId = account.accountKey,
                workId = "local-account-work-id",
                confidence = "exact",
                confirmed = true,
                editionSha = "sha-a",
                resolvedAt = 1_500L,
            ),
        )

        sync.sync(account)
        val occ = db.vocabularyDao().occurrencesList(wordId).single()
        assertEquals("local-account-work-id", occ.workId)
        val wireOcc = fake.words["en|aether"]!!.getJSONArray("occurrences").getJSONObject(0)
        assertEquals("local-account-work-id", wireOcc.getString("work_id"))
    }

    private class FakeVocabularyServer {
        val words = linkedMapOf<String, JSONObject>()
        val reviews = linkedMapOf<String, JSONObject>()
        var seq = 0L
        var wordPushCount = 0
        var reviewPushCount = 0
        var refuseNormalized: String? = null
        var customPages: List<JSONObject>? = null
        var onBeforeReviewPushResponse: ((JSONObject) -> Unit)? = null
        private var customPageIdx = 0

        fun injectReview(
            reviewId: String,
            language: String,
            normalized: String,
            wordGeneration: Long,
            rating: String,
            reviewedAt: Long,
            intervalMinutes: Int,
            dueAt: Long,
        ) {
            seq++
            reviews[reviewId] = JSONObject()
                .put("review_id", reviewId)
                .put("language", language)
                .put("normalized", normalized)
                .put("word_generation", wordGeneration)
                .put("seq", seq)
                .put("rating", rating)
                .put("reviewed_at", java.time.Instant.ofEpochMilli(reviewedAt).toString())
                .put("interval_minutes", intervalMinutes)
                .put("due_at", java.time.Instant.ofEpochMilli(dueAt).toString())
        }

        fun tombstoneReview(reviewId: String) {
            val existing = reviews[reviewId] ?: return
            seq++
            reviews[reviewId] = JSONObject()
                .put("review_id", reviewId)
                .put("language", existing.getString("language"))
                .put("normalized", existing.getString("normalized"))
                .put("word_generation", existing.getLong("word_generation"))
                .put("seq", seq)
                .put("deleted", true)
        }

        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/v1/vocabulary/changes" && request.method == "GET" -> {
                        val pages = customPages
                        if (pages != null && customPageIdx < pages.size) {
                            return okJson(pages[customPageIdx++])
                        }
                        val since = request.url.queryParameter("since")?.toLongOrNull() ?: 0L
                        val outWords = JSONArray()
                        words.values.filter { it.getLong("seq") > since }.forEach { outWords.put(it) }
                        val outReviews = JSONArray()
                        reviews.values.filter { it.getLong("seq") > since }.forEach { outReviews.put(it) }
                        okJson(
                            JSONObject()
                                .put("words", outWords)
                                .put("reviews", outReviews)
                                .put("high_water", seq)
                                .put("next_cursor", maxOf(since, seq))
                                .put("has_more", false),
                        )
                    }

                    path == "/v1/vocabulary/words" && request.method == "POST" -> {
                        wordPushCount++
                        val body = JSONObject(request.body?.utf8().orEmpty())
                        val items = body.getJSONArray("words")
                        val results = JSONArray()
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val lang = item.getString("language")
                            val norm = item.getString("normalized")
                            val baseRev = item.getLong("base_rev")
                            val deleted = item.optBoolean("deleted", false)
                            val key = "$lang|$norm"
                            if (norm == refuseNormalized) {
                                results.put(
                                    JSONObject()
                                        .put("language", lang)
                                        .put("normalized", norm)
                                        .put("status", "invalid")
                                        .put("reason", "word limit reached"),
                                )
                                continue
                            }
                            val stored = words[key]
                            if (deleted) {
                                if (stored == null) {
                                    results.put(
                                        JSONObject()
                                            .put("language", lang)
                                            .put("normalized", norm)
                                            .put("status", "duplicate"),
                                    )
                                    continue
                                }
                                val storedRev = stored.getLong("rev")
                                if (storedRev != baseRev) {
                                    results.put(
                                        JSONObject()
                                            .put("language", lang)
                                            .put("normalized", norm)
                                            .put("status", "conflict")
                                            .put("server", stored),
                                    )
                                    continue
                                }
                                seq++
                                val nextRev = storedRev + 1
                                val gen = stored.getLong("generation")
                                words[key] = JSONObject()
                                    .put("language", lang)
                                    .put("normalized", norm)
                                    .put("rev", nextRev)
                                    .put("generation", gen)
                                    .put("seq", seq)
                                    .put("deleted", true)
                                // Cascade tombstone live reviews.
                                for ((rid, revObj) in reviews.toMap()) {
                                    if (revObj.getString("language") == lang &&
                                        revObj.getString("normalized") == norm &&
                                        !revObj.optBoolean("deleted", false)
                                    ) {
                                        tombstoneReview(rid)
                                    }
                                }
                                results.put(
                                    JSONObject()
                                        .put("language", lang)
                                        .put("normalized", norm)
                                        .put("status", "applied")
                                        .put("rev", nextRev)
                                        .put("generation", gen)
                                        .put("seq", seq),
                                )
                            } else {
                                if (stored != null && stored.getLong("rev") != baseRev) {
                                    results.put(
                                        JSONObject()
                                            .put("language", lang)
                                            .put("normalized", norm)
                                            .put("status", "conflict")
                                            .put("server", stored),
                                    )
                                    continue
                                }
                                seq++
                                val nextRev = (stored?.getLong("rev") ?: 0L) + 1
                                val nextGen = when {
                                    stored == null -> 1L
                                    stored.optBoolean("deleted", false) -> stored.getLong("generation") + 1
                                    else -> stored.getLong("generation")
                                }
                                val saved = JSONObject(item.toString())
                                    .put("rev", nextRev)
                                    .put("generation", nextGen)
                                    .put("seq", seq)
                                    .put("deleted", false)
                                words[key] = saved
                                results.put(
                                    JSONObject()
                                        .put("language", lang)
                                        .put("normalized", norm)
                                        .put("status", "applied")
                                        .put("rev", nextRev)
                                        .put("generation", nextGen)
                                        .put("seq", seq),
                                )
                            }
                        }
                        okJson(JSONObject().put("results", results))
                    }

                    path == "/v1/vocabulary/reviews" && request.method == "POST" -> {
                        reviewPushCount++
                        val body = JSONObject(request.body?.utf8().orEmpty())
                        val items = body.getJSONArray("reviews")
                        val results = JSONArray()
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val rid = item.getString("review_id")
                            val deleted = item.optBoolean("deleted", false)
                            seq++
                            val saved = JSONObject(item.toString()).put("seq", seq).put("deleted", deleted)
                            reviews[rid] = saved
                            results.put(
                                JSONObject()
                                    .put("review_id", rid)
                                    .put("status", "applied")
                                    .put("seq", seq),
                            )
                        }
                        onBeforeReviewPushResponse?.invoke(body)
                        okJson(JSONObject().put("results", results))
                    }

                    else -> MockResponse(code = 404, body = "404 page not found")
                }
            }

            private fun okJson(obj: JSONObject): MockResponse =
                MockResponse(code = 200, body = obj.toString())
        }
    }
}
