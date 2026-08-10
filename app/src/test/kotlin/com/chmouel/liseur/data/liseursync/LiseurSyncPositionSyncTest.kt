package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.SyncAccount
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.SyncOutcome
import java.io.File
import java.net.InetAddress
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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

/**
 * Keeping a reader's place in step with a liseur-sync server.
 *
 * The two things that would be unforgivable are covered first: reading
 * that arrived must never be skipped over by a cursor that moved
 * without it, and a book that has never been near a catalog server must
 * still sync, because that is the whole reason this partner exists.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncPositionSyncTest {

    private lateinit var server: MockWebServer
    private lateinit var db: LiseurDatabase
    private lateinit var file: File

    @Before
    fun open() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
        file = File.createTempFile("book", ".epub").apply { writeBytes(ByteArray(4096) { 3 }) }
    }

    @After
    fun close() {
        server.close()
        db.close()
        file.delete()
        CredentialCipher.keyForTesting = null
    }

    @Test
    fun `a book off an SD card syncs, having no catalog server at all`() = runTest {
        // Nothing else in the app would ever sync this book: it came
        // from a folder, not from a server.
        connect()
        db.bookDao().upsert(local())
        db.readingProgressDao().recordLocal(
            bookUrl = LOCAL,
            locatorJson = LOCATOR,
            progression = 0.4,
            readingSpeed = null,
            status = "reading",
            updatedAt = NOW,
        )
        resolved()
        seeded()
        server.enqueue(json("""{"ops":[]}"""))
        accepted()

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        val ops = requests().last { it.target.startsWith("/v1/ops") }
        val sent = JSONObject(ops.body!!.utf8()).getJSONArray("ops").getJSONObject(0)
        assertEquals("w-1", sent.getString("work_id"))
        assertEquals(0.4, sent.getDouble("progression"), 0.0)
        assertEquals("/c1.xhtml", sent.getJSONObject("locator").getString("href"))
        // Settled, so the next run has nothing to say about it.
        assertEquals(1L, db.syncPeerStateDao().get(LOCAL, peer())?.ackedRevision)
    }

    @Test
    fun `an interrupted push is simply repeated, and the server knows it`() = runTest {
        connect()
        db.bookDao().upsert(local())
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        resolved()
        seeded()
        server.enqueue(json("""{"ops":[]}"""))
        // The op reached the server; the answer did not reach us.
        server.enqueue(MockResponse(code = 500, body = ""))

        sync().syncAll(null)
        val first = JSONObject(
            requests().last { it.target.startsWith("/v1/ops") }.body!!.utf8(),
        ).getJSONArray("ops").getJSONObject(0)

        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(
            json("""{"results":[{"op_id":"${first.getString("op_id")}","status":"duplicate"}]}"""),
        )
        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        val second = JSONObject(
            requests().last { it.target.startsWith("/v1/ops") }.body!!.utf8(),
        ).getJSONArray("ops").getJSONObject(0)
        // Byte for byte, or the server would call it a contradiction.
        assertEquals(first.toString(), second.toString())
        assertEquals(1L, db.syncPeerStateDao().get(LOCAL, peer())?.ackedRevision)
    }

    @Test
    fun `the cursor does not move past reading it did not write down`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()
        server.enqueue(
            json(
                """{"ops":[${op(seq = 9, progression = 0.75)}],"has_more":false,"high_water":9}""",
            ),
        )

        sync().syncAll(null)

        assertEquals(9L, db.syncAccountDao().get()?.cursorSeq)
        // A fresh process asks from there, not from the beginning.
        val next = requests().first { it.target.startsWith("/v1/changes") }
        assertTrue(next.target.contains("since=0"))
        assertEquals(0.75, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
    }

    @Test
    fun `a cursor the server has compacted past starts again from a snapshot`() = runTest {
        connect(cursor = 5)
        db.bookDao().upsert(local())
        alias()
        server.enqueue(MockResponse(code = 410, body = """{"error":"resync_required"}"""))
        server.enqueue(
            json("""{"ops":[${op(seq = 40, progression = 0.6)}],"snapshot_seq":42}"""),
        )

        sync().syncAll(null)

        assertEquals(42L, db.syncAccountDao().get()?.cursorSeq)
        assertEquals(0.6, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
    }

    @Test
    fun `this device's own reading coming back around is not applied again`() = runTest {
        connect(deviceId = "d-mine")
        db.bookDao().upsert(local())
        alias()
        server.enqueue(
            json(
                """{"ops":[${op(seq = 3, progression = 0.9, deviceId = "d-mine")}],
                    "high_water":3}
                """.trimIndent(),
            ),
        )

        sync().syncAll(null)

        assertNull(db.readingProgressDao().get(LOCAL))
        assertEquals(3L, db.syncAccountDao().get()?.cursorSeq)
    }

    @Test
    fun `a book named for the first time is asked where it stands`() = runTest {
        // Everything the server knows about it happened before this
        // device had a name for it, so it is all behind the cursor and
        // the delta pull would never mention it.
        connect()
        db.bookDao().upsert(local())
        resolved()
        server.enqueue(json("""{"ops":[${op(seq = 2, progression = 0.33)}]}"""))
        server.enqueue(json("""{"ops":[]}"""))

        sync().syncAll(null)

        assertTrue(requests().any { it.target.startsWith("/v1/works/w-1/positions") })
        assertEquals(0.33, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
    }

    @Test
    fun `both sides having moved is a question, not a decision`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()
        // Agreed at a third of the way, then read on here.
        db.syncPeerStateDao().settle(LOCAL, peer(), 0, 0.3, "reading", NOW)
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.5, null, "reading", NOW)
        server.enqueue(json("""{"ops":[${op(seq = 4, progression = 0.8)}],"high_water":4}"""))

        sync().syncAll(null)

        // Neither side won, and the disagreement is on disk so that a
        // restart does not make it look settled.
        assertEquals(0.5, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
        assertEquals(1, db.syncPeerStateDao().countPending(peer()))
    }

    @Test
    fun `finished reading is sent once, as fractions rather than pages`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()
        val session = closedSession(from = 0.1, to = 0.4)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(json("""{"accepted":1}"""))

        sync().syncAll(null)

        val sent = JSONObject(
            requests().last { it.target.startsWith("/v1/sessions") }.body!!.utf8(),
        ).getJSONArray("sessions").getJSONObject(0)
        assertEquals("w-1", sent.getString("work_id"))
        assertEquals(0.1, sent.getDouble("start_progression"), 0.0001)
        assertEquals(0.4, sent.getDouble("end_progression"), 0.0001)
        // Pages are a property of one rendering on one screen; this
        // device cannot work them out for anybody else.
        assertFalse(sent.has("pages"))
        assertNotNull(db.readingSessionDao().get(session)?.uploadedAt)

        // And the next run has nothing left to say.
        server.enqueue(json("""{"ops":[]}"""))
        sync().syncAll(null)
        assertEquals(1, requests().count { it.target.startsWith("/v1/sessions") })
    }

    @Test
    fun `an hour the server never confirmed is offered again, not lost`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()
        val session = closedSession(from = 0.1, to = 0.4)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(MockResponse(code = 500, body = ""))

        sync().syncAll(null)
        assertNull(db.readingSessionDao().get(session)?.uploadedAt)

        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(json("""{"accepted":1}"""))
        sync().syncAll(null)

        val sessions = requests().filter { it.target.startsWith("/v1/sessions") }
        // Byte for byte, so the server recognises it rather than
        // counting the same hour twice.
        assertEquals(sessions[0].body!!.utf8(), sessions[1].body!!.utf8())
        assertNotNull(db.readingSessionDao().get(session)?.uploadedAt)
    }

    @Test
    fun `a batch the server will never accept stops being offered`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()
        val session = closedSession(from = 0.1, to = 0.4)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(MockResponse(code = 409, body = """{"error":"session_id reused"}"""))

        sync().syncAll(null)

        // Retrying forever would park it at the head of the queue and
        // stop every later session behind it.
        assertNotNull(db.readingSessionDao().get(session)?.uploadedAt)
    }

    @Test
    fun `reading of a book this server has no name for waits`() = runTest {
        connect()
        db.bookDao().upsert(local())
        val session = closedSession(from = 0.1, to = 0.4)
        // No alias, and resolving is refused, so nothing can be said
        // about which book this was.
        server.enqueue(MockResponse(code = 503, body = ""))
        server.enqueue(json("""{"ops":[]}"""))

        sync().syncAll(null)

        assertTrue(requests().none { it.target.startsWith("/v1/sessions") })
        assertNull(db.readingSessionDao().get(session)?.uploadedAt)
    }

    @Test
    fun `nothing is asked of the server when no account is connected`() = runTest {
        db.bookDao().upsert(local())

        assertEquals(SyncOutcome.NotApplicable, sync().syncAll(null))
        assertEquals(0, server.requestCount)
    }

    // -- Scaffolding ------------------------------------------------------

    private fun sync(): LiseurSyncPositionSync {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        return LiseurSyncPositionSync(
            accountDao = db.syncAccountDao(),
            bookDao = db.bookDao(),
            progressDao = db.readingProgressDao(),
            peerStateDao = db.syncPeerStateDao(),
            identityDao = db.workIdentityDao(),
            sessionDao = db.readingSessionDao(),
            works = WorkResolver(
                dao = db.workIdentityDao(),
                fingerprints = BookFingerprintStore(context, db.workIdentityDao()) { NOW },
                now = { NOW },
            ),
            finishedState = FinishedState(db.bookDao(), db.readingProgressDao()),
            now = { NOW },
        )
    }

    private suspend fun connect(cursor: Long = 0, deviceId: String? = null) {
        db.syncAccountDao().upsert(
            SyncAccount(
                baseUrl = "http://127.0.0.1:${server.port}",
                username = "ada",
                tokenCipher = CredentialCipher.encrypt("device-secret"),
                deviceName = "Test",
                deviceId = deviceId,
                deviceKey = "device-a",
                cursorSeq = cursor,
                addedAt = NOW,
            ),
        )
    }

    /** The server is about to be asked what it calls this book. */
    private fun resolved() = server.enqueue(
        MockResponse(
            code = 200,
            body = """{"work_id":"w-1","confidence":"high","created":false}""",
        ),
    )

    /** The one-off "where does this book stand" for a fresh name. */
    private fun seeded() = server.enqueue(json("""{"ops":[]}"""))

    /** The server takes the op — naming it back, as it must. */
    private fun accepted(revision: Long = 1) = server.enqueue(
        json(
            """{"results":[{"op_id":"${SyncOps.opIdFor("device-a", "w-1", revision)}",
                "status":"applied"}]}
            """.trimIndent(),
        ),
    )

    private suspend fun alias() = db.workIdentityDao().upsert(
        com.chmouel.liseur.data.db.WorkAlias(
            bookUrl = LOCAL,
            peerId = peer(),
            workId = "w-1",
            confidence = "high",
            confirmed = true,
            resolvedAt = NOW,
        ),
    )

    private fun op(seq: Long, progression: Double, deviceId: String? = null): String {
        val device = deviceId?.let { ""","device_id":"$it"""" } ?: ""
        return """{"op_id":"o-$seq","work_id":"w-1","seq":$seq,
            "progression":$progression,
            "client_ts":"${SyncOps.formatTime(NOW)}"$device}
        """.trimIndent()
    }

    private fun json(body: String) = MockResponse(code = 200, body = body)

    /** Every request the server has seen, including earlier runs'. */
    private fun requests(): List<RecordedRequest> {
        while (seen.size < server.requestCount) seen += server.takeRequest()
        return seen.toList()
    }

    private val seen = mutableListOf<RecordedRequest>()

    private suspend fun closedSession(from: Double, to: Double): Long {
        val dao = db.readingSessionDao()
        val id = dao.insert(
            com.chmouel.liseur.data.db.ReadingSession(
                bookUrl = LOCAL,
                startedAt = NOW,
                lastCheckpointAt = NOW,
            ),
        )
        dao.checkpoint(id, totalMs = 0, atMillis = NOW, progression = from)
        dao.finish(id, totalMs = 60_000, atMillis = NOW + 60_000, progression = to)
        return id
    }

    private fun peer() = "liseursync|http://127.0.0.1:${server.port}|ada"

    private fun local() = Book(
        url = LOCAL,
        title = "A Memory Called Empire",
        author = "Arkady Martine",
        coverPath = null,
        source = null,
        addedAt = NOW,
        lastOpenedAt = NOW,
        localUri = file.toURI().toString(),
        fileModifiedAt = NOW,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val LOCAL = "content://sd/book.epub"
        const val LOCATOR = """{"href":"/c1.xhtml"}"""
    }
}
