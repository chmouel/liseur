package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.AnnotationSync
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerKind
import java.net.InetAddress
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keeping highlights, notes and bookmarks in step (ADR-0028).
 *
 * The tests that matter most are the ones about a mark coming back from
 * the dead, and about an edit disappearing under a retry. Both are
 * silent when they go wrong: nothing fails, nothing is logged, and the
 * reader finds a highlight they deleted last month sitting in the book
 * again — or one they wrote yesterday replaced by an older copy of
 * itself.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncAnnotationsTest {

    private lateinit var server: MockWebServer
    private lateinit var db: LiseurDatabase
    private var clock = NOW

    @Before
    fun open() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).build()
        server.dispatcher = LiveSets { work -> agreed(work) }
        clock = NOW
    }

    @After
    fun close() {
        server.close()
        db.close()
        CredentialCipher.keyForTesting = null
    }

    // -- Creating and repeating -------------------------------------------

    @Test
    fun `a new highlight is offered once and then left alone`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        emptyFeed()
        server.enqueue(results("""{"id":"mark-1","status":"applied","rev":1,"seq":7}"""))

        sync()

        val sent = JSONObject(pushBody()).getJSONArray("annotations").getJSONObject(0)
        assertEquals("mark-1", sent.getString("id"))
        assertEquals(0, sent.getLong("base_rev"))
        assertEquals("highlight", sent.getString("kind"))
        assertEquals("yellow", sent.getString("color"))
        assertEquals(WORK, sent.getString("work_id"))

        val row = db.annotationSyncDao().get(peer(), "mark-1")!!
        assertEquals(1, row.rev)
        assertEquals(7, row.seq)
        assertNull(row.pendingKind)

        // Nothing has changed, so the next run has nothing to say.
        emptyFeed()
        sync()
        assertEquals(1, pushes())
    }

    @Test
    fun `a new book note is offered without an anchor`() = runTest {
        connect()
        // A real edition hash on the alias, or asserting the note leaves
        // `edition_sha` off would pass for a highlight too.
        alias(editionSha = "edition-sha")
        reconciled()
        db.annotationDao().upsert(bookNote())
        emptyFeed()
        server.enqueue(results("""{"id":"book-note","status":"applied","rev":1,"seq":7}"""))

        sync()

        val sent = JSONObject(pushBody()).getJSONArray("annotations").getJSONObject(0)
        assertEquals("book-note", sent.getString("id"))
        assertEquals("note", sent.getString("kind"))
        assertEquals("remember this", sent.getString("body"))
        assertNull(sent.opt("locator"))
        assertNull(sent.opt("progression"))
        assertNull(sent.opt("edition_sha"))
        assertEquals(1, db.annotationSyncDao().get(peer(), "book-note")!!.rev)
    }

    @Test
    fun `an interrupted push is repeated to the byte`() = runTest {
        // The server tells a retry from a fresh write by comparing the
        // whole payload. A replay that rebuilt the request could order
        // its keys differently and be taken for somebody else's edit.
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        emptyFeed()
        server.enqueue(MockResponse(code = 500, body = ""))

        sync()
        val first = pushBody()
        assertEquals(AnnotationSync.PENDING_WRITE, db.annotationSyncDao().get(peer(), "mark-1")!!.pendingKind)

        server.enqueue(results("""{"id":"mark-1","status":"duplicate","rev":1,"seq":7}"""))
        emptyFeed()
        sync()

        assertEquals(first, pushBody())
        val row = db.annotationSyncDao().get(peer(), "mark-1")!!
        assertEquals(1, row.rev)
        assertNull(row.pendingKind)
    }

    @Test
    fun `an edit made while a push was in the air survives it`() = runTest {
        // The response is about the request that was sent, not about
        // whatever the row says now. Adopting the rev is right; adopting
        // it as "and this content is agreed" would lose the newer words.
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        emptyFeed()
        server.enqueue(MockResponse(code = 500, body = ""))
        sync()

        db.annotationDao().upsert(mark(note = "second thoughts", updatedAt = MICROS + 1000))

        server.enqueue(results("""{"id":"mark-1","status":"duplicate","rev":1,"seq":7}"""))
        emptyFeed()
        server.enqueue(results("""{"id":"mark-1","status":"applied","rev":2,"seq":8}"""))
        sync()

        assertEquals("second thoughts", db.annotationDao().byId("mark-1")!!.note)
        val row = db.annotationSyncDao().get(peer(), "mark-1")!!
        assertEquals(2, row.rev)
        // And the second request quoted the rev the first one earned.
        val second = JSONObject(pushBodies().last()).getJSONArray("annotations").getJSONObject(0)
        assertEquals(1, second.getLong("base_rev"))
    }

    @Test
    fun `a whole-request failure leaves the request standing`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        emptyFeed()
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

        sync()

        val row = db.annotationSyncDao().get(peer(), "mark-1")!!
        assertEquals(AnnotationSync.PENDING_WRITE, row.pendingKind)
        assertNotNull(row.pendingJson)
    }

    // -- Reading what other devices did ------------------------------------

    @Test
    fun `a mark another device made arrives and is not sent back`() = runTest {
        connect()
        alias()
        reconciled()
        server.enqueue(
            json(
                """{"annotations":[${record(id = "theirs", rev = 3, seq = 11)}],
                    "high_water":11,"has_more":false}
                """.trimIndent(),
            ),
        )

        sync()

        val landed = db.annotationDao().byId("theirs")!!
        assertEquals(BOOK, landed.bookId)
        assertEquals(AnnotationKind.HIGHLIGHT.name, landed.kind)
        assertEquals("YELLOW", landed.tint)
        assertEquals(11, db.remoteServerDao().get()!!.annotationCursorSeq)
        // The whole point: it arrived, so it is not owed back.
        assertEquals(0, pushes())
    }

    @Test
    fun `a book note another device made arrives and can be edited`() = runTest {
        connect()
        alias()
        reconciled()
        server.enqueue(
            json(
                """{"annotations":[${bookNoteRecord(rev = 3, seq = 11)}],
                    "high_water":11,"has_more":false}
                """.trimIndent(),
            ),
        )

        sync()

        val landed = db.annotationDao().byId("book-note")!!
        assertEquals(AnnotationKind.BOOK_NOTE.name, landed.kind)
        assertEquals("remember this", landed.note)
        assertEquals("", landed.locatorJson)
        assertEquals(0, pushes())

        db.annotationDao().upsert(landed.copy(note = "remember this instead", updatedAt = MICROS + 1))
        emptyFeed()
        liveSet(bookNoteRecord(rev = 3, seq = 11))
        server.enqueue(results("""{"id":"book-note","status":"applied","rev":4,"seq":12}"""))

        sync()

        val sent = JSONObject(pushBody()).getJSONArray("annotations").getJSONObject(0)
        assertEquals(3, sent.getLong("base_rev"))
        assertEquals("note", sent.getString("kind"))
        assertEquals("remember this instead", sent.getString("body"))
        assertEquals(4, db.annotationSyncDao().get(peer(), "book-note")!!.rev)
    }

    @Test
    fun `a stale page changes nothing`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark(id = "theirs", note = "current"))
        db.annotationSyncDao().upsert(
            syncRow(
                id = "theirs",
                rev = 5,
                seq = 9,
                acked = fingerprintOf(mark(id = "theirs", note = "current")),
            ),
        )
        server.enqueue(
            json(
                """{"annotations":[${record(id = "theirs", rev = 2, seq = 4, body = "older")}],
                    "high_water":4,"has_more":false}
                """.trimIndent(),
            ),
        )

        sync()

        assertEquals("current", db.annotationDao().byId("theirs")!!.note)
        assertEquals(5, db.annotationSyncDao().get(peer(), "theirs")!!.rev)
    }

    @Test
    fun `a tombstone in the feed removes the mark here`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        db.annotationSyncDao().upsert(syncRow(rev = 1, acked = fingerprintOf(mark())))
        server.enqueue(
            json(
                """{"annotations":[{"id":"mark-1","rev":2,"seq":9,"deleted":true}],
                    "high_water":9,"has_more":false}
                """.trimIndent(),
            ),
        )

        sync()

        assertNull(db.annotationDao().byId("mark-1"))
        assertNull(db.annotationSyncDao().get(peer(), "mark-1"))
    }

    @Test
    fun `the cursor does not move past a page that never landed`() = runTest {
        connect()
        alias()
        reconciled()
        server.enqueue(MockResponse(code = 500, body = ""))

        sync()

        assertEquals(0, db.remoteServerDao().get()!!.annotationCursorSeq)
    }

    @Test
    fun `a mark for a book this device does not have is passed over`() = runTest {
        connect()
        alias()
        reconciled()
        server.enqueue(
            json(
                """{"annotations":[${record(id = "elsewhere", rev = 1, seq = 5, workId = "w-unknown")}],
                    "high_water":5,"has_more":false}
                """.trimIndent(),
            ),
        )

        sync()

        assertNull(db.annotationDao().byId("elsewhere"))
        // The cursor still moves — the same trade positions make — which
        // is exactly why reconciling a work outright has to exist.
        assertEquals(5, db.remoteServerDao().get()!!.annotationCursorSeq)
    }

    // -- Reconciling -------------------------------------------------------

    @Test
    fun `a book asks outright what it holds the first time`() = runTest {
        connect()
        alias()
        emptyFeed()
        liveSet(record(id = "old", rev = 4, seq = 2))

        sync()

        assertNotNull(db.annotationDao().byId("old"))
        assertTrue(requests().any { it.target == "/v1/works/$WORK/annotations" })
        assertTrue(db.workIdentityDao().alias(BOOK, peer())!!.annotationsReconciledAt > 0)
    }

    @Test
    fun `reconciliation recovers a book note skipped by an older client`() = runTest {
        connect()
        alias()
        emptyFeed()
        liveSet(bookNoteRecord(rev = 3, seq = 7))

        sync()

        val landed = db.annotationDao().byId("book-note")!!
        assertEquals(AnnotationKind.BOOK_NOTE.name, landed.kind)
        assertEquals("remember this", landed.note)
        assertEquals(3, db.annotationSyncDao().get(peer(), "book-note")!!.rev)
    }

    @Test
    fun `a mark the server has forgotten is not resurrected`() = runTest {
        // The case this whole ordering exists for. The mark was deleted
        // on another device long enough ago that the tombstone has been
        // swept, and this device edited it offline in the meantime.
        // Pushed first it would not be a conflict — it would be a
        // create, and the highlight would come back.
        connect()
        alias()
        db.annotationDao().upsert(mark(note = "edited offline", updatedAt = MICROS + 5))
        db.annotationSyncDao().upsert(syncRow(rev = 3, acked = "stale"))
        emptyFeed()
        liveSet()

        sync()

        assertNull(db.annotationDao().byId("mark-1"))
        assertNull(db.annotationSyncDao().get(peer(), "mark-1"))
        assertEquals(0, pushes())
    }

    @Test
    fun `a mark never pushed survives an empty live set`() = runTest {
        // Absence is evidence about what the server confirmed. It says
        // nothing about a mark the server has never been told of.
        connect()
        alias()
        db.annotationDao().upsert(mark())
        emptyFeed()
        liveSet()
        server.enqueue(results("""{"id":"mark-1","status":"applied","rev":1,"seq":1}"""))

        sync()

        assertNotNull(db.annotationDao().byId("mark-1"))
        assertEquals(1, pushes())
    }

    @Test
    fun `a work still waiting to be reconciled offers nothing new`() = runTest {
        connect()
        alias()
        db.annotationDao().upsert(mark())
        emptyFeed()
        // The live set never answers, so the work stays unsettled.
        liveSetIs(MockResponse(code = 500, body = ""))

        sync()

        assertEquals(0, pushes())
        assertNotNull(db.annotationDao().byId("mark-1"))
    }

    @Test
    fun `a request already in flight settles even for an unreconciled work`() = runTest {
        // Holding it back would strand bytes the server has already
        // seen. A replay is not a new write.
        connect()
        alias()
        db.annotationDao().upsert(mark())
        db.annotationSyncDao().upsert(
            syncRow(
                pendingKind = AnnotationSync.PENDING_WRITE,
                pendingJson = """{"id":"mark-1","base_rev":0,"work_id":"$WORK","kind":"highlight"}""",
                pendingFingerprint = "sent",
            ),
        )
        server.enqueue(results("""{"id":"mark-1","status":"applied","rev":1,"seq":3}"""))
        emptyFeed()
        liveSetIs(MockResponse(code = 500, body = ""))

        sync()

        val row = db.annotationSyncDao().get(peer(), "mark-1")!!
        assertEquals(1, row.rev)
        assertNull(row.pendingKind)
    }

    @Test
    fun `a settled book is not asked again until the interval is up`() = runTest {
        connect()
        alias()
        reconciled(at = NOW - 1000)
        emptyFeed()

        sync()

        assertTrue(requests().none { it.target == "/v1/works/$WORK/annotations" })

        clock = NOW + LiseurSyncAnnotations.RECONCILE_INTERVAL_MS + 1
        emptyFeed()
        liveSet()
        sync()

        assertTrue(requests().any { it.target == "/v1/works/$WORK/annotations" })
    }

    // -- Disagreement ------------------------------------------------------

    @Test
    fun `an edit from a stale rev gives way to the server`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark(note = "mine"))
        db.annotationSyncDao().upsert(syncRow(rev = 1, acked = "stale"))
        emptyFeed()
        server.enqueue(
            results(
                """{"id":"mark-1","status":"conflict",
                    "server":${record(id = "mark-1", rev = 4, seq = 12, body = "theirs")}}
                """.trimIndent(),
            ),
        )

        sync()

        assertEquals("theirs", db.annotationDao().byId("mark-1")!!.note)
        assertEquals(4, db.annotationSyncDao().get(peer(), "mark-1")!!.rev)
    }

    @Test
    fun `a conflict answered with a tombstone deletes the mark`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark(note = "mine"))
        db.annotationSyncDao().upsert(syncRow(rev = 1, acked = "stale"))
        emptyFeed()
        server.enqueue(
            results(
                """{"id":"mark-1","status":"conflict",
                    "server":{"id":"mark-1","rev":5,"seq":13,"deleted":true}}
                """.trimIndent(),
            ),
        )

        sync()

        assertNull(db.annotationDao().byId("mark-1"))
        assertNull(db.annotationSyncDao().get(peer(), "mark-1"))
    }

    @Test
    fun `a replay across a token rotation is recognised as ours`() = runTest {
        // The server stamps a write with the device its token belongs
        // to, so a rotated token makes a replay somebody else's — though
        // every word of it is ours. Taking the refusal at face value
        // would overwrite the reader's mark with a copy of itself.
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        emptyFeed()
        server.enqueue(MockResponse(code = 500, body = ""))
        sync()

        val sentItem = JSONObject(pushBody()).getJSONArray("annotations").getJSONObject(0)
        server.enqueue(
            results(
                """{"id":"mark-1","status":"conflict","server":${
                    sentItem.put("rev", 1).put("seq", 4).put("device_id", "a-token-ago")
                }}
                """.trimIndent(),
            ),
        )
        emptyFeed()
        sync()

        val row = db.annotationSyncDao().get(peer(), "mark-1")!!
        assertEquals(1, row.rev)
        assertNull(row.pendingKind)
        assertEquals(row.pendingFingerprint, null)
        // Nothing further owed: the server has it.
        assertEquals("a passage", db.annotationDao().byId("mark-1")!!.text)
    }

    // -- Refusals ----------------------------------------------------------

    @Test
    fun `a mark naming a work the server has dropped asks for a new name`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        emptyFeed()
        server.enqueue(results("""{"id":"mark-1","status":"invalid","reason":"unknown work"}"""))

        val outcome = sync()

        val row = db.annotationSyncDao().get(peer(), "mark-1")!!
        assertNull(row.pendingKind)
        // Not acknowledged, not written off: it is offered again once
        // the book has a name the server knows.
        assertNull(row.ackedFingerprint)
        assertNull(row.rejectedFingerprint)
        assertEquals(0, row.retryNotBefore)
        // And the book is named as owing a fresh resolve, which the
        // position sync is already equipped to do.
        assertEquals(mapOf(BOOK to WORK), outcome.reresolve)
    }

    @Test
    fun `a work already full is waited out, not hammered`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        emptyFeed()
        server.enqueue(
            results(
                """{"id":"mark-1","status":"invalid",
                    "reason":"annotation cap reached for this work"}
                """.trimIndent(),
            ),
        )

        sync()

        val row = db.annotationSyncDao().get(peer(), "mark-1")!!
        assertTrue(row.retryNotBefore > clock)
        assertNull(row.rejectedFingerprint)

        emptyFeed()
        sync()
        assertEquals(1, pushes())
    }

    @Test
    fun `a shape the server will never take is not offered for ever`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        emptyFeed()
        server.enqueue(results("""{"id":"mark-1","status":"invalid","reason":"body too large"}"""))
        sync()

        assertNotNull(db.annotationSyncDao().get(peer(), "mark-1")!!.rejectedFingerprint)

        emptyFeed()
        sync()
        assertEquals(1, pushes())

        // But an edit is a different mark, and gets its turn.
        db.annotationDao().upsert(mark(note = "shorter", updatedAt = MICROS + 1))
        emptyFeed()
        server.enqueue(results("""{"id":"mark-1","status":"applied","rev":1,"seq":2}"""))
        sync()
        assertEquals(2, pushes())
    }

    @Test
    fun `a refusal in a word this client does not know is not retried blindly`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        emptyFeed()
        server.enqueue(results("""{"id":"mark-1","status":"invalid","reason":"some new rule"}"""))
        sync()

        emptyFeed()
        sync()
        assertEquals(1, pushes())
    }

    @Test
    fun `answers are matched by name, never by position`() = runTest {
        // The route is documented as not atomic, and reading a short or
        // reordered array by index writes one mark's rev onto another.
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark(id = "a"))
        db.annotationDao().upsert(mark(id = "b"))
        emptyFeed()
        server.enqueue(results("""{"id":"b","status":"applied","rev":9,"seq":9}"""))

        sync()

        assertEquals(9, db.annotationSyncDao().get(peer(), "b")!!.rev)
        // "a" was never answered, so its request stands and is replayed.
        val a = db.annotationSyncDao().get(peer(), "a")!!
        assertEquals(0, a.rev)
        assertEquals(AnnotationSync.PENDING_WRITE, a.pendingKind)
    }

    // -- Deleting ----------------------------------------------------------

    @Test
    fun `deleting a mark here deletes it everywhere`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationSyncDao().upsert(syncRow(rev = 3, acked = "whatever"))
        emptyFeed()
        server.enqueue(json("""{"id":"mark-1","status":"applied","rev":4,"seq":20}"""))

        sync()

        val sent = requests().last { it.method == "DELETE" }
        assertEquals("/v1/annotations/mark-1?rev=3", sent.target)
        assertNull(db.annotationSyncDao().get(peer(), "mark-1"))
    }

    @Test
    fun `a repeated delete is answered and settled`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationSyncDao().upsert(
            syncRow(rev = 3, pendingKind = AnnotationSync.PENDING_DELETE, pendingRev = 3),
        )
        server.enqueue(json("""{"id":"mark-1","status":"duplicate","rev":4,"seq":20}"""))
        emptyFeed()

        sync()

        assertEquals("/v1/annotations/mark-1?rev=3", requests().first { it.method == "DELETE" }.target)
        assertNull(db.annotationSyncDao().get(peer(), "mark-1"))
    }

    @Test
    fun `an id that reads as a path still addresses its own mark`() = runTest {
        // The id is opaque, so another client may hand out `..`. Left
        // alone in a URL it stops being data: the server resolves it and
        // the delete lands on the collection instead of on the mark.
        connect()
        alias()
        reconciled()
        db.annotationSyncDao().upsert(syncRow(id = "..", rev = 3, acked = "whatever"))
        emptyFeed()

        sync()

        // Nobody can address it, so no request is made rather than one
        // made at the collection. The agreement is left standing, and
        // left *quiet*: a row stuck pending is skipped by the feed, by
        // reconciling and by every future push, so the id could never
        // converge again.
        assertTrue(requests().none { it.method == "DELETE" })
        val row = db.annotationSyncDao().get(peer(), "..")!!
        assertNull(row.pendingKind)
    }

    @Test
    fun `a mark the server never knew is simply forgotten`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationSyncDao().upsert(syncRow(rev = 3, acked = "whatever"))
        emptyFeed()
        server.enqueue(MockResponse(code = 404, body = """{"error":"not found"}"""))

        sync()

        assertNull(db.annotationSyncDao().get(peer(), "mark-1"))
    }

    @Test
    fun `a mark that moved on elsewhere comes back rather than going quietly`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationSyncDao().upsert(syncRow(rev = 3, acked = "whatever"))
        emptyFeed()
        server.enqueue(
            MockResponse(
                code = 409,
                body = """{"error":"rev mismatch","server":${record(rev = 6, seq = 30, body = "they added a note")}}""",
            ),
        )

        sync()

        val back = db.annotationDao().byId("mark-1")!!
        assertEquals("they added a note", back.note)
        assertEquals(6, db.annotationSyncDao().get(peer(), "mark-1")!!.rev)
    }

    @Test
    fun `a mark made again while its delete was in the air is kept`() = runTest {
        // Dropping the agreement here would make the next push a create
        // at rev 0, which the tombstone refuses; the newer copy would
        // then lose to a conflict that never needed to happen. Keeping
        // the tombstone's rev lets the server take the rewrite as the
        // deliberate revival it is.
        connect()
        alias()
        reconciled()
        // A previous run sent the delete and died before reading the
        // answer; the reader wrote the mark again in the meantime.
        db.annotationSyncDao().upsert(
            syncRow(rev = 3, pendingKind = AnnotationSync.PENDING_DELETE, pendingRev = 3),
        )
        db.annotationDao().upsert(mark(note = "on reflection", updatedAt = MICROS + 99))
        server.enqueue(json("""{"id":"mark-1","status":"duplicate","rev":4,"seq":20}"""))
        emptyFeed()
        server.enqueue(results("""{"id":"mark-1","status":"applied","rev":5,"seq":21}"""))

        sync()

        val row = db.annotationSyncDao().get(peer(), "mark-1")!!
        assertEquals(5, row.rev)
        val sent = JSONObject(pushBodies().last()).getJSONArray("annotations").getJSONObject(0)
        assertEquals(4, sent.getLong("base_rev"))
        assertEquals("on reflection", db.annotationDao().byId("mark-1")!!.note)
    }

    @Test
    fun `an agreement with no rev to quote is dropped rather than sent as zero`() = runTest {
        // `rev=0` is a 400, so there is nothing to send. Reconciling the
        // work is what settles the id one way or the other.
        connect()
        alias()
        reconciled()
        db.annotationSyncDao().upsert(syncRow(rev = 0))
        emptyFeed()

        sync()

        assertNull(db.annotationSyncDao().get(peer(), "mark-1"))
        assertTrue(requests().none { it.method == "DELETE" })
    }

    // -- Scope --------------------------------------------------------------

    @Test
    fun `asking about one book still settles the rest`() = runTest {
        // A book-scoped run is not a book-scoped pass: the requests in
        // flight and the feed cursor belong to the account, and settling
        // only one book's would strand the others indefinitely.
        connect()
        alias()
        alias(bookUrl = OTHER_BOOK, workId = OTHER_WORK)
        reconciled()
        reconciled(bookUrl = OTHER_BOOK)
        db.annotationSyncDao().upsert(
            syncRow(
                id = "other-1",
                bookId = OTHER_BOOK,
                workId = OTHER_WORK,
                pendingKind = AnnotationSync.PENDING_WRITE,
                pendingJson = """{"id":"other-1","base_rev":0}""",
                pendingFingerprint = "sent",
            ),
        )
        server.enqueue(results("""{"id":"other-1","status":"applied","rev":1,"seq":2}"""))
        server.enqueue(
            json(
                """{"annotations":[${record(id = "theirs", rev = 1, seq = 40, workId = OTHER_WORK)}],
                    "high_water":40,"has_more":false}
                """.trimIndent(),
            ),
        )

        sync(book = BOOK)

        assertEquals(1, db.annotationSyncDao().get(peer(), "other-1")!!.rev)
        assertNotNull(db.annotationDao().byId("theirs"))
        assertEquals(40, db.remoteServerDao().get()!!.annotationCursorSeq)
    }

    @Test
    fun `two copies of one book take an arriving mark the same way every time`() = runTest {
        connect()
        alias(bookUrl = BOOK, editionSha = "aaa")
        alias(bookUrl = OTHER_BOOK, editionSha = "bbb")
        reconciled()
        reconciled(bookUrl = OTHER_BOOK)
        server.enqueue(
            json(
                """{"annotations":[${record(id = "theirs", rev = 1, seq = 3, editionSha = "bbb")}],
                    "high_water":3,"has_more":false}
                """.trimIndent(),
            ),
        )

        sync()

        // The file it was actually marked in wins over alphabetical luck.
        assertEquals(OTHER_BOOK, db.annotationDao().byId("theirs")!!.bookId)
    }

    @Test
    fun `a page of records this device cannot read still moves the cursor past them`() = runTest {
        connect()
        alias()
        reconciled()
        // A future server kind this version cannot represent. The cursor
        // still moves by what the server sent, or the account's high water
        // mark would be taken for a page of nothing and every record after
        // this would be stepped over — once, silently, for ever.
        val unknown = JSONObject()
            .put("id", "unknown-1").put("rev", 1).put("seq", 4)
            .put("work_id", WORK).put("kind", "future-kind")
            .put("device_id", "other-device").put("client_ts", STAMP)
            .toString()
        server.enqueue(json("""{"annotations":[$unknown],"high_water":9000,"has_more":false}"""))

        sync()

        assertEquals(4, db.remoteServerDao().get()!!.annotationCursorSeq)
    }

    @Test
    fun `a note this device must refuse still moves the cursor past it`() = runTest {
        connect()
        alias()
        reconciled()
        // The two shapes the accept rule turns away: a standalone note
        // is meant to have no anchor, and it is meant to say something.
        // Refusing the record is right; letting it hold the cursor back,
        // or counting the page as empty, is not.
        val anchored = JSONObject(bookNoteRecord(id = "anchored-note", seq = 4))
            .put("locator", JSONObject(LOCATOR))
            .toString()
        val silent = bookNoteRecord(id = "silent-note", seq = 5, body = "")
        server.enqueue(
            json(
                """{"annotations":[$anchored,$silent,${record(id = "mark-1", rev = 1, seq = 6)}],
                    "high_water":9000,"has_more":false}
                """.trimIndent(),
            ),
        )

        sync()

        assertNull(db.annotationDao().byId("anchored-note"))
        assertNull(db.annotationDao().byId("silent-note"))
        // The readable record on the same page still lands.
        assertNotNull(db.annotationDao().byId("mark-1"))
        assertEquals(6, db.remoteServerDao().get()!!.annotationCursorSeq)
    }

    @Test
    fun `a book note keeps the copy it already lives in`() = runTest {
        connect()
        alias(bookUrl = BOOK)
        alias(bookUrl = OTHER_BOOK)
        reconciled()
        reconciled(bookUrl = OTHER_BOOK)
        // A note carries no edition anchor, so nothing in the record
        // says which copy it belongs to. What this device already wrote
        // down does, and alphabetical luck must not overrule it.
        db.annotationDao().upsert(bookNote().copy(bookId = OTHER_BOOK))
        db.annotationSyncDao().upsert(
            syncRow(
                id = "book-note",
                bookId = OTHER_BOOK,
                rev = 1,
                acked = AnnotationWire.fingerprint(bookNote(), WORK),
            ),
        )
        server.enqueue(
            json(
                """{"annotations":[${bookNoteRecord(rev = 2, seq = 8, body = "edited elsewhere")}],
                    "high_water":8,"has_more":false}
                """.trimIndent(),
            ),
        )

        sync()

        val landed = db.annotationDao().byId("book-note")!!
        assertEquals("edited elsewhere", landed.note)
        assertEquals(OTHER_BOOK, landed.bookId)
        assertEquals(OTHER_BOOK, db.annotationSyncDao().get(peer(), "book-note")!!.bookId)
    }

    @Test
    fun `a book note edited on both sides gives way to the server`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(bookNote(body = "mine"))
        db.annotationSyncDao().upsert(syncRow(id = "book-note", rev = 1, acked = "stale"))
        emptyFeed()
        server.enqueue(
            results(
                """{"id":"book-note","status":"conflict",
                    "server":${bookNoteRecord(rev = 4, seq = 12, body = "theirs")}}
                """.trimIndent(),
            ),
        )

        sync()

        val landed = db.annotationDao().byId("book-note")!!
        assertEquals("theirs", landed.note)
        assertEquals(AnnotationKind.BOOK_NOTE.name, landed.kind)
        // Still standalone: the server's word must not anchor it.
        assertEquals("", landed.locatorJson)
        assertEquals(4, db.annotationSyncDao().get(peer(), "book-note")!!.rev)
    }

    @Test
    fun `a tombstone in the feed removes a book note here too`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(bookNote())
        db.annotationSyncDao().upsert(
            syncRow(id = "book-note", rev = 1, acked = AnnotationWire.fingerprint(bookNote(), WORK)),
        )
        server.enqueue(
            json(
                """{"annotations":[{"id":"book-note","rev":2,"seq":9,"deleted":true}],
                    "high_water":9,"has_more":false}
                """.trimIndent(),
            ),
        )

        sync()

        assertNull(db.annotationDao().byId("book-note"))
        assertNull(db.annotationSyncDao().get(peer(), "book-note"))
    }

    @Test
    fun `a mark the server recreated after a sweep is taken, not read as ancient`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        db.annotationSyncDao().upsert(syncRow(rev = 5, seq = 40, acked = fingerprintOf(mark())))
        // The tombstone was swept and the id used again, so the server
        // starts it over at rev 1. Only the seq says which is newer.
        server.enqueue(
            json(
                """{"annotations":[${record(rev = 1, seq = 41, body = "written again")}],
                    "high_water":41,"has_more":false}
                """.trimIndent(),
            ),
        )

        sync()

        assertEquals("written again", db.annotationDao().byId("mark-1")!!.note)
        val row = db.annotationSyncDao().get(peer(), "mark-1")!!
        assertEquals(1, row.rev)
        assertEquals(41, row.seq)
    }

    @Test
    fun `re-pairing keeps what was written while the two were apart`() = runTest {
        connect()
        alias()
        reconciled()
        // No sync row: the account was disconnected and paired again, so
        // nothing here records the two ever having agreed. The server's
        // copy must not silently overwrite the offline edit.
        db.annotationDao().upsert(mark(note = "written offline", updatedAt = MICROS + 10))
        server.enqueue(
            json(
                """{"annotations":[${record(rev = 3, seq = 12, body = "their words")}],
                    "high_water":12,"has_more":false}
                """.trimIndent(),
            ),
        )
        server.enqueue(results("""{"id":"mark-1","status":"applied","rev":4,"seq":13}"""))

        sync()

        assertEquals("written offline", db.annotationDao().byId("mark-1")!!.note)
        val sent = JSONObject(pushBody()).getJSONArray("annotations").getJSONObject(0)
        assertEquals(3, sent.getLong("base_rev"))
        assertEquals("written offline", sent.getString("body"))
    }

    @Test
    fun `an answer to a request that has since been dropped is not written back`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        emptyFeed()
        // A different file takes over the path while the push is in the
        // air, which drops the mark and the agreement together. Putting
        // the agreement back would leave a row with no mark behind it,
        // and the next run would read that as a deletion the reader made
        // and retire a highlight alive on every other device.
        server.enqueue(results("""{"id":"mark-1","status":"applied","rev":1,"seq":7}"""))
        whileInFlight("/v1/annotations") {
            db.annotationDao().deleteById("mark-1")
            db.annotationSyncDao().forgetBook(BOOK)
        }

        sync()

        assertNull(db.annotationSyncDao().get(peer(), "mark-1"))
    }

    @Test
    fun `a work the server no longer holds asks for a new name`() = runTest {
        connect()
        alias()
        db.annotationDao().upsert(mark())
        emptyFeed()
        liveSetIs(MockResponse(code = 404, body = """{"error":"work not found"}"""))

        val outcome = sync()

        // Merged into another work, or split away. Asking again every
        // run would stall this book's marks for good.
        assertNull(db.workIdentityDao().alias(BOOK, peer()))
        assertEquals(mapOf(BOOK to WORK), outcome.reresolve)
        assertEquals(0, pushes())
    }

    @Test
    fun `a batch the server will not take whole is halved rather than repeated`() = runTest {
        connect()
        alias()
        reconciled()
        repeat(4) { db.annotationDao().upsert(mark(id = "mark-$it")) }
        emptyFeed()
        // An administrator set a smaller batch than the default. Sent
        // again unchanged, this would never go through at all.
        server.enqueue(MockResponse(code = 400, body = """{"error":"batch too large"}"""))
        server.enqueue(
            results(
                """{"id":"mark-0","status":"applied","rev":1,"seq":1}""",
                """{"id":"mark-1","status":"applied","rev":1,"seq":2}""",
            ),
        )
        server.enqueue(
            results(
                """{"id":"mark-2","status":"applied","rev":1,"seq":3}""",
                """{"id":"mark-3","status":"applied","rev":1,"seq":4}""",
            ),
        )

        sync()

        val rows = db.annotationSyncDao().forPeer(peer())
        assertEquals(4, rows.count { it.rev == 1L })
        assertEquals(0, rows.count { it.pending })
    }

    @Test
    fun `a single mark the server will not take at any size is not offered for ever`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        emptyFeed()
        server.enqueue(MockResponse(code = 400, body = """{"error":"body too long"}"""))

        sync()

        val row = db.annotationSyncDao().get(peer(), "mark-1")!!
        assertNull(row.pendingKind)
        assertEquals(fingerprintOf(mark()), row.rejectedFingerprint)
    }

    @Test
    fun `a field another device cleared is cleared here too`() = runTest {
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark(note = "a thought"))
        db.annotationSyncDao().upsert(
            syncRow(rev = 1, seq = 1, acked = fingerprintOf(mark(note = "a thought"))),
        )
        val cleared = JSONObject(record(rev = 2, seq = 5))
            .put("color", "")
            .put("body", "")
            .put("progression", JSONObject.NULL)
            .toString()
        server.enqueue(json("""{"annotations":[$cleared],"high_water":5,"has_more":false}"""))

        sync()

        // A value that falls back to what was here before makes a clear
        // made elsewhere invisible, and the two copies then disagree for
        // ever with nothing anywhere reading as unsettled.
        val landed = db.annotationDao().byId("mark-1")!!
        assertNull(landed.tint)
        assertNull(landed.note)
        assertNull(landed.totalProgression)
        assertEquals(0, pushes())
    }

    // -- Fixtures ----------------------------------------------------------

    @Test
    fun `a mark edited twice mid-page lands as its newest state`() = runTest {
        // The server recreates an id whose tombstone was swept at rev 1,
        // so the higher rev in a page can be the older of the two
        // states. Seq is the clock that does not restart.
        connect()
        alias()
        reconciled()
        server.enqueue(
            json(
                """{"annotations":[
                    ${record(rev = 9, seq = 40, body = "before it was swept")},
                    ${record(rev = 1, seq = 41, body = "after it came back")}
                ],"high_water":41,"has_more":false}
                """.trimIndent(),
            ),
        )

        sync()

        assertEquals("after it came back", db.annotationDao().byId("mark-1")!!.note)
        assertEquals(41, db.annotationSyncDao().get(peer(), "mark-1")!!.seq)
    }

    @Test
    fun `a mark edited while the live set was on its way is not offered on a guess`() = runTest {
        // Reconciling agrees a work, but this mark was deliberately not
        // judged: the reader touched it while the answer was in the air,
        // so the absence does not describe it. Pushing it anyway would
        // be a guess about a tombstone that may have been swept, which
        // is the resurrection the phase exists to prevent.
        connect()
        alias()
        db.annotationDao().upsert(mark())
        db.annotationSyncDao().upsert(syncRow(rev = 3, acked = "stale"))
        emptyFeed()
        liveSet()
        whileInFlight("/v1/works/$WORK/annotations") {
            db.annotationDao().upsert(mark(note = "one more thought", updatedAt = MICROS + 20))
        }

        sync()

        assertEquals("one more thought", db.annotationDao().byId("mark-1")!!.note)
        assertEquals(0, pushes())
    }

    @Test
    fun `a refusal does not overwrite words written after it was asked for`() = runTest {
        // The sync row cannot see an edit: a highlight is written to
        // `annotations`. An answer that arrives after the reader has had
        // another turn is about words nobody holds any more.
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark(note = "mine"))
        db.annotationSyncDao().upsert(syncRow(rev = 1, acked = "stale"))
        emptyFeed()
        server.enqueue(
            results(
                """{"id":"mark-1","status":"conflict",
                    "server":${record(id = "mark-1", rev = 4, seq = 12, body = "theirs")}}
                """.trimIndent(),
            ),
        )
        whileInFlight("/v1/annotations") {
            db.annotationDao().upsert(mark(note = "later still", updatedAt = MICROS + 30))
        }

        sync()

        assertEquals("later still", db.annotationDao().byId("mark-1")!!.note)
        // The rev is a fact about the server and is taken; the row reads
        // dirty, so the words the reader holds go next pass.
        val row = db.annotationSyncDao().get(peer(), "mark-1")!!
        assertEquals(4, row.rev)
        assertNull(row.pendingKind)
    }

    // -- Guards ------------------------------------------------------------

    @Test
    fun `a book with something to say is asked about however lately it was settled`() = runTest {
        // The interval is this device's guess at how long a tombstone
        // lasts, and retention is the server's setting — it may be a
        // day. Guessing wrong about a book holding a mark to offer is
        // the resurrection this phase exists to prevent, so a book with
        // something to say is asked every time.
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark(note = "edited offline", updatedAt = MICROS + 5))
        db.annotationSyncDao().upsert(syncRow(rev = 3, acked = "stale"))
        emptyFeed()
        liveSet()

        sync()

        assertTrue(requests().any { it.target == "/v1/works/$WORK/annotations" })
        assertNull(db.annotationDao().byId("mark-1"))
        assertEquals(0, pushes())
    }

    @Test
    fun `a rate-limited feed stops the pass instead of reconciling on`() = runTest {
        // 429 is answered, so it is not "unreachable" — but running on
        // regardless would still spend the reconcile budget against a
        // server that just asked this device to slow down.
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark(note = "edited offline", updatedAt = MICROS + 5))
        db.annotationSyncDao().upsert(syncRow(rev = 3, acked = "stale"))
        server.enqueue(MockResponse(code = 429))

        sync()

        assertTrue(requests().none { it.target == "/v1/works/$WORK/annotations" })
        assertEquals(0, pushes())
    }

    @Test
    fun `a pass that outlives its account stops talking to the server`() = runTest {
        // Refusing to store the answer is too late: the request is the
        // side effect. A device that has been unpaired must not go on
        // telling that server what its reader marked.
        connect()
        alias()
        reconciled()
        db.annotationDao().upsert(mark())
        emptyFeed()
        whileInFlight("/v1/annotations/changes?since=0&limit=500") {
            db.remoteServerDao().delete()
        }

        sync()

        assertEquals(0, pushes())
        assertTrue(requests().none { it.target == "/v1/works/$WORK/annotations" })
    }

    @Test
    fun `a record does not land on a book that changed underneath the pass`() = runTest {
        // A different file took over the path while the feed was on the
        // wire, which clears the name along with the marks. Writing this
        // record against the name the book used to have would anchor
        // somebody else's highlight into text that never held it.
        connect()
        alias()
        reconciled()
        server.enqueue(
            json(
                """{"annotations":[${record(rev = 3, seq = 12)}],
                    "high_water":12,"has_more":false}
                """.trimIndent(),
            ),
        )
        whileInFlight("/v1/annotations/changes?since=0&limit=500") {
            db.workIdentityDao().deleteAliasIfStale(BOOK, peer(), WORK)
        }

        sync()

        assertNull(db.annotationDao().byId("mark-1"))
        // The cursor still moves: the record was seen, and the book it
        // belongs to will ask outright when it next resolves.
        assertEquals(12, db.remoteServerDao().get()!!.annotationCursorSeq)
    }

    private suspend fun sync(book: String? = null): LiseurSyncAnnotations.Outcome {
        val cursor = db.remoteServerDao().get()!!.annotationCursorSeq
        return LiseurSyncAnnotations(
            serverDao = db.remoteServerDao(),
            annotationDao = db.annotationDao(),
            syncDao = db.annotationSyncDao(),
            identityDao = db.workIdentityDao(),
            now = { clock },
        ).sync(
            LiseurSyncAnnotations.Peer(
                baseUrl = "http://127.0.0.1:${server.port}",
                credentials = RemoteCredentials.Bearer("device-secret"),
                peerId = peer(),
                accountKey = peer(),
                cursorSeq = cursor,
            ),
            book = book,
        )
    }

    private suspend fun connect() = db.remoteServerDao().upsert(
        RemoteServer(
            kind = ServerKind.LISEUR_SYNC,
            baseUrl = "http://127.0.0.1:${server.port}",
            username = "ada",
            passwordCipher = null,
            apiKeyCipher = null,
            accountId = null,
            userId = null,
            koboTokenCipher = null,
            canDownload = true,
            addedAt = NOW,
            catalogSyncedAt = null,
            positionSyncedAt = null,
            syncToken = null,
            liseurTokenCipher = CredentialCipher.encrypt("device-secret"),
        ),
    )

    private suspend fun alias(
        bookUrl: String = BOOK,
        workId: String = WORK,
        editionSha: String? = null,
    ) = db.workIdentityDao().upsert(
        WorkAlias(
            bookUrl = bookUrl,
            peerId = peer(),
            workId = workId,
            confidence = WorkAlias.HIGH,
            confirmed = true,
            seeded = true,
            sourceSent = true,
            editionSha = editionSha,
            resolvedAt = NOW,
        ),
    )

    /** Marks a book as recently settled, so a run does not ask again. */
    private suspend fun reconciled(bookUrl: String = BOOK, at: Long = NOW) =
        db.workIdentityDao().markAnnotationsReconciled(bookUrl, peer(), at)

    private fun emptyFeed() = server.enqueue(json("""{"annotations":[],"high_water":0}"""))

    /**
     * Gives the reader a turn while a request is on the wire.
     *
     * A network call is the one moment in a pass when the database can
     * change underneath it, and the answer has to be written down
     * against what is there now rather than against the snapshot that
     * was sent. Run from the server's own thread, so it lands strictly
     * between the request and the response.
     */
    private fun whileInFlight(target: String, change: suspend () -> Unit) {
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.target == target) runBlocking { change() }
                return original.dispatch(request)
            }
        }
    }

    /**
     * Answers a work's live set out of band.
     *
     * Reconciling is not a step a test asks for: it happens whenever a
     * book has a mark to offer, which is most of them. Keeping those
     * answers out of the main queue means a test enqueues what it is
     * actually about, in the order it means, and does not have to know
     * how often the pass decided to ask. A work no test spoke for holds
     * nothing, which is the honest answer for a server that has never
     * been told about it.
     */
    private class LiveSets(val agreed: (String) -> MockResponse) : QueueDispatcher() {
        val queued = ArrayDeque<MockResponse>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val work = LIVE_SET.matchEntire(request.target.orEmpty())?.groupValues?.get(1)
            if (request.method != "GET" || work == null) return super.dispatch(request)
            return queued.removeFirstOrNull() ?: agreed(work)
        }

        private companion object {
            val LIVE_SET = Regex("""/v1/works/([^/]+)/annotations""")
        }
    }

    /**
     * What a server that agrees with this device would say.
     *
     * The neutral answer, for the tests that are about something else:
     * every mark this device believes was acknowledged is still there,
     * at the rev and seq it was acknowledged at — so landing it changes
     * nothing and its presence is not mistaken for a deletion. A test
     * about disagreement says so, with [liveSet] or [liveSetIs].
     */
    private fun agreed(workId: String): MockResponse = runBlocking {
        val records = mutableListOf<String>()
        for (row in db.annotationSyncDao().forWork(peer(), workId).filter { it.rev >= 1 }) {
            records += if (db.annotationDao().byId(row.id)?.kind == AnnotationKind.BOOK_NOTE.name) {
                bookNoteRecord(id = row.id, rev = row.rev, seq = row.seq, workId = workId)
            } else {
                record(id = row.id, rev = row.rev, seq = row.seq, workId = workId)
            }
        }
        val held = records.joinToString(",")
        json("""{"annotations":[$held]}""")
    }

    /** Queues one answer to the next live-set request. */
    private fun liveSet(vararg records: String) =
        liveSetIs(json("""{"annotations":[${records.joinToString(",")}]}"""))

    /** Queues one raw answer to the next live-set request. */
    private fun liveSetIs(response: MockResponse) =
        (server.dispatcher as LiveSets).queued.addLast(response)

    private fun results(vararg entries: String) =
        json("""{"results":[${entries.joinToString(",")}]}""")

    private fun json(body: String) = MockResponse(code = 200, body = body)

    private fun record(
        id: String = "mark-1",
        rev: Long = 1,
        seq: Long = 1,
        workId: String = WORK,
        body: String = "",
        editionSha: String? = null,
    ): String = JSONObject()
        .put("id", id)
        .put("rev", rev)
        .put("seq", seq)
        .put("work_id", workId)
        .put("kind", "highlight")
        .put("locator", JSONObject(LOCATOR))
        .put("excerpt", "a passage")
        .put("body", body)
        .put("color", "yellow")
        .put("progression", 0.25)
        .put("device_id", "other-device")
        .put("client_ts", STAMP)
        .apply { editionSha?.let { put("edition_sha", it) } }
        .toString()

    private fun bookNoteRecord(
        id: String = "book-note",
        rev: Long = 1,
        seq: Long = 1,
        workId: String = WORK,
        body: String = "remember this",
    ): String = JSONObject()
        .put("id", id)
        .put("rev", rev)
        .put("seq", seq)
        .put("work_id", workId)
        .put("kind", "note")
        .put("body", body)
        .put("device_id", "other-device")
        .put("client_ts", STAMP)
        .toString()

    private fun mark(
        id: String = "mark-1",
        note: String? = null,
        updatedAt: Long = MICROS,
    ) = BookAnnotation(
        id = id,
        bookId = BOOK,
        kind = if (note == null) AnnotationKind.HIGHLIGHT.name else AnnotationKind.NOTE.name,
        locatorJson = LOCATOR,
        text = "a passage",
        note = note,
        tint = "YELLOW",
        chapter = "Chapter One",
        position = 12,
        totalProgression = 0.25,
        createdAt = NOW,
        updatedAt = updatedAt,
    )

    private fun bookNote(
        id: String = "book-note",
        body: String = "remember this",
        updatedAt: Long = MICROS,
    ) = BookAnnotation(
        id = id,
        bookId = BOOK,
        kind = AnnotationKind.BOOK_NOTE.name,
        locatorJson = "",
        note = body,
        createdAt = NOW,
        updatedAt = updatedAt,
    )

    private fun fingerprintOf(mark: BookAnnotation) = AnnotationWire.fingerprint(mark, WORK)

    private fun syncRow(
        id: String = "mark-1",
        bookId: String = BOOK,
        workId: String = WORK,
        rev: Long = 0,
        // A row the server has answered has a seq as well as a rev, and
        // freshness is judged by the seq. Defaulting it to the rev keeps
        // every fixture a state the server could actually have left.
        seq: Long = rev,
        acked: String? = null,
        pendingKind: String? = null,
        pendingJson: String? = null,
        pendingRev: Long = 0,
        pendingFingerprint: String? = null,
    ) = AnnotationSync(
        id = id,
        peerId = peer(),
        bookId = bookId,
        workId = workId,
        rev = rev,
        seq = seq,
        ackedFingerprint = acked,
        pendingKind = pendingKind,
        pendingJson = pendingJson,
        pendingRev = pendingRev,
        pendingFingerprint = pendingFingerprint,
    )

    private fun peer() = "liseursync|http://127.0.0.1:${server.port}|ada"

    private fun requests(): List<RecordedRequest> {
        while (seen.size < server.requestCount) seen += server.takeRequest()
        return seen.toList()
    }

    private val seen = mutableListOf<RecordedRequest>()

    private fun pushBodies(): List<String> =
        requests().filter { it.method == "POST" && it.target == "/v1/annotations" }
            .map { it.body!!.utf8() }

    private fun pushBody(): String = pushBodies().last()

    private fun pushes(): Int = pushBodies().size

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val MICROS = 1_709_294_400_123_456L
        const val STAMP = "2024-03-01T12:00:00.123456Z"
        const val BOOK = "content://sd/a-book.epub"
        const val OTHER_BOOK = "content://sd/z-other-copy.epub"
        const val WORK = "w-1"
        const val OTHER_WORK = "w-2"
        const val LOCATOR =
            """{"href":"/c1.xhtml","locations":{"position":12},"title":"Chapter One"}"""
    }
}
