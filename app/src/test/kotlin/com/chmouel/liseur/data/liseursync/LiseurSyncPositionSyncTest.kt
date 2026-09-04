package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.ResumeConfidence
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import java.io.File
import java.net.InetAddress
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
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

        assertEquals(9L, db.remoteServerDao().get()?.syncCursorSeq)
        // A fresh process asks from there, not from the beginning.
        val next = requests().first { it.target.startsWith("/v1/changes") }
        assertTrue(next.target.contains("since=0"))
        assertEquals(0.75, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
    }

    // -- Records this device cannot read -----------------------------------
    //
    // A server that writes a null progression — as one did, four times
    // in seventeen seconds — must not be able to move this reader, and
    // must not be able to derail the cursor either. The two are
    // separate: the record still has a sequence number even when it has
    // no meaning.

    @Test
    fun `an unreadable record moves the cursor without moving the reader`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()
        server.enqueue(
            json(
                """{"ops":[${op(seq = 9, progression = 0.75)},${spoiled(seq = 10)}],
                    "has_more":false,"high_water":10}
                """.trimIndent(),
            ),
        )

        sync().syncAll(null)

        // Past the spoiled record, so it is never fetched again...
        assertEquals(10L, db.remoteServerDao().get()?.syncCursorSeq)
        // ...and the good position behind it still landed.
        assertEquals(0.75, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
    }

    @Test
    fun `a page of nothing but unreadable records does not leap to high water`() = runTest {
        // high_water is the account's newest sequence across every book,
        // not this page's last. Dropping the records and falling back to
        // it would step over every real position in between.
        connect()
        db.bookDao().upsert(local())
        alias()
        server.enqueue(
            json(
                """{"ops":[${spoiled(seq = 11)},${spoiled(seq = 12)}],
                    "has_more":false,"high_water":9999}
                """.trimIndent(),
            ),
        )

        sync().syncAll(null)

        assertEquals(12L, db.remoteServerDao().get()?.syncCursorSeq)
        assertNull(db.readingProgressDao().get(LOCAL))
    }

    @Test
    fun `an unreadable record does not replace a position already pending`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()
        // Agreed at a third of the way, then read on here, so what the
        // server sends is held as a disagreement rather than adopted.
        db.syncPeerStateDao().settle(LOCAL, peer(), 0, 0.3, "reading", NOW)
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.5, null, "reading", NOW)
        server.enqueue(
            json("""{"ops":[${op(seq = 4, progression = 0.8)}],"has_more":true,"high_water":4}"""),
        )
        server.enqueue(
            json("""{"ops":[${spoiled(seq = 5)}],"has_more":false,"high_water":5}"""),
        )

        sync().syncAll(null)

        assertEquals(5L, db.remoteServerDao().get()?.syncCursorSeq)
        assertEquals(1, db.syncPeerStateDao().countPending(peer()))
        assertEquals(0.5, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
    }

    @Test
    fun `a snapshot ignores what it cannot read and lands what it can`() = runTest {
        connect(cursor = 5)
        db.bookDao().upsert(local())
        alias()
        server.enqueue(MockResponse(code = 410, body = """{"error":"resync_required"}"""))
        server.enqueue(
            json(
                """{"ops":[${spoiled(seq = 41)},${op(seq = 40, progression = 0.6)}],
                    "snapshot_seq":42}
                """.trimIndent(),
            ),
        )

        sync().syncAll(null)

        assertEquals(42L, db.remoteServerDao().get()?.syncCursorSeq)
        assertEquals(0.6, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
    }

    @Test
    fun `a good position is not hidden behind the spoiled ones on top of it`() = runTest {
        // Exactly the shape the account was found in: four unreadable
        // ops sitting on a perfectly good one. Answering "nothing known"
        // there would leave this book unseeded and tell the reader there
        // is nothing to catch up to.
        connect()
        db.bookDao().upsert(local())
        resolved()
        server.enqueue(
            json(
                """{"ops":[${spoiled(seq = 2148)},${spoiled(seq = 2147)},
                    ${op(seq = 2144, progression = 0.33)}]}
                """.trimIndent(),
            ),
        )
        server.enqueue(json("""{"ops":[]}"""))

        sync().syncAll(null)

        assertEquals(0.33, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
    }

    @Test
    fun `a book whose every position is unreadable is left alone`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()
        server.enqueue(json("""{"ops":[${spoiled(seq = 2148)}]}"""))

        assertEquals(PreviewOutcome.NotSynced, sync().previewBook(LOCAL))
        assertNull(db.readingProgressDao().get(LOCAL))
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

        assertEquals(42L, db.remoteServerDao().get()?.syncCursorSeq)
        assertEquals(0.6, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
    }

    @Test
    fun `this device's own reading coming back around is not applied again`() = runTest {
        connect(deviceId = "d-mine")
        db.bookDao().upsert(local())
        alias(deviceId = "d-mine")
        server.enqueue(
            json(
                """{"ops":[${op(seq = 3, progression = 0.9, deviceId = "d-mine")}],
                    "high_water":3}
                """.trimIndent(),
            ),
        )

        sync().syncAll(null)

        assertNull(db.readingProgressDao().get(LOCAL))
        assertEquals(3L, db.remoteServerDao().get()?.syncCursorSeq)
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
        // stop every later session behind it. But it was not sent, so it
        // is not marked as sent: it is set aside for this server.
        assertNull(db.readingSessionDao().get(session)?.uploadedAt)
        assertEquals(listOf(session), db.sessionRefusalDao().forPeer(peer()).map { it.sessionId })
        server.enqueue(json("""{"ops":[]}"""))
        sync().syncAll(null)
        assertEquals(1, requests().count { it.target.startsWith("/v1/sessions") })
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
    fun `a doubtful match confirmed by hand is asked where it stands`() = runTest {
        // The book was matched on title and author alone, sat unusable
        // while the question was open, and has just been confirmed. The
        // other device's reading is behind the cursor by now; only a
        // direct question recovers it.
        connect()
        db.bookDao().upsert(local())
        alias(seeded = false, confidence = "low")
        server.enqueue(json("""{"ops":[${op(seq = 2, progression = 0.44)}]}"""))
        server.enqueue(json("""{"ops":[]}"""))

        sync().syncAll(null)

        assertTrue(requests().any { it.target.startsWith("/v1/works/w-1/positions") })
        assertEquals(0.44, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)

        // Asked once, not every run.
        server.enqueue(json("""{"ops":[]}"""))
        sync().syncAll(null)
        assertEquals(
            1,
            requests().count { it.target.startsWith("/v1/works/w-1/positions") },
        )
    }

    @Test
    fun `the answer a preview fetched is there for the choice that follows`() = runTest {
        // "Sync this book" previews first and resolves second, and the
        // resolve is only ever sent to a peer holding the disagreement
        // on disk. An answer that were merely returned would make the
        // choice that follows a silent no-op.
        connect()
        db.bookDao().upsert(local())
        alias()
        server.enqueue(json("""{"ops":[${op(seq = 7, progression = 0.8)}]}"""))

        val sync = sync()
        val preview = sync.previewBook(LOCAL)
        assertTrue(preview is PreviewOutcome.Ready)
        assertNotNull(sync.preservedConflict(LOCAL))

        // Taking the server's side uses the answer paired on disk.
        assertEquals(ResolveOutcome.Done, sync.takeRemotePosition(LOCAL, 0))
        assertEquals(0.8, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
    }

    @Test
    fun `an offline run says so without waiting on a connection`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()

        assertEquals(
            SyncOutcome.Failure(SyncFailure.Offline),
            sync(online = false).syncAll(null),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `keeping the local position works offline, because nothing is sent`() = runTest {
        // Unlike the other two servers, this one settles a disagreement
        // by clearing it and letting the next run push. That is a local
        // write, and refusing it offline would leave the book asking the
        // same question on every open for the rest of the flight.
        connect()
        db.bookDao().upsert(local())
        alias()
        server.enqueue(json("""{"ops":[${op(seq = 7, progression = 0.8)}]}"""))
        sync().previewBook(LOCAL)
        assertNotNull(sync().preservedConflict(LOCAL))

        assertEquals(ResolveOutcome.Done, sync(online = false).keepLocalPosition(LOCAL))
        assertNull(sync().preservedConflict(LOCAL))
    }

    @Test
    fun `pending locator survives process death and stays paired with its progression`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias(editionSha = "sha-a")
        db.syncPeerStateDao().settle(LOCAL, peer(), 0, 0.2, "reading", NOW)
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.3, null, "reading", NOW)
        val exact = exactLocator("remembered word")
        server.enqueue(
            json(
                """{"ops":[${
                    op(8, 0.8, locatorJson = exact, editionSha = "sha-a")
                }],"high_water":8}""",
            ),
        )

        sync().syncAll(null)
        val pending = requireNotNull(db.syncPeerStateDao().get(LOCAL, peer()))
        assertEquals(exact, pending.pendingLocatorJson)
        assertEquals("sha-a", pending.pendingEditionSha)

        // A new repository instance has no in-memory state and makes no request.
        assertEquals(ResolveOutcome.Done, sync().takeRemotePosition(LOCAL, 1))
        assertEquals(exact, db.readingProgressDao().get(LOCAL)?.locatorJson)
        val settled = requireNotNull(db.syncPeerStateDao().get(LOCAL, peer()))
        assertNull(settled.pendingLocatorJson)
        assertNull(settled.pendingEditionSha)
    }

    @Test
    fun `different exact passages with the same displayed percent are pulled`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias(editionSha = "sha-a")
        val local = exactLocator("local passage", progression = 0.071)
        val remote = exactLocator("remote passage", progression = 0.074)
        db.readingProgressDao().recordLocal(LOCAL, local, 0.071, null, "reading", NOW)
        db.syncPeerStateDao().settle(LOCAL, peer(), 1, 0.071, "reading", NOW)
        server.enqueue(
            json(
                """{"ops":[${
                    op(9, 0.074, locatorJson = remote, editionSha = "sha-a")
                }],"high_water":9}""",
            ),
        )

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        val stored = requireNotNull(db.readingProgressDao().get(LOCAL))
        assertEquals(0.074, stored.totalProgression!!, 0.0)
        assertEquals(remote, stored.locatorJson)
        assertEquals(0, db.syncPeerStateDao().countPending(peer()))
    }

    @Test
    fun `preview does not call different exact passages in step`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias(editionSha = "sha-a")
        db.readingProgressDao().recordLocal(
            LOCAL,
            exactLocator("local passage", progression = 0.071),
            0.071,
            null,
            "reading",
            NOW,
        )
        server.enqueue(
            json(
                """{"ops":[${
                    op(
                        10,
                        0.074,
                        locatorJson = exactLocator("remote passage", progression = 0.074),
                        editionSha = "sha-a",
                    )
                }]}""",
            ),
        )

        val preview = (sync().previewBook(LOCAL) as PreviewOutcome.Ready).preview

        assertFalse(preview.agrees)
        assertEquals(ResumeConfidence.EXACT, preview.confidence)
    }

    @Test
    fun `different edition and legacy locators fall back to progression`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias(editionSha = "sha-local")
        db.syncPeerStateDao().persistPending(
            bookUrl = LOCAL,
            peerId = peer(),
            progression = 0.8,
            status = "reading",
            remoteUpdatedAt = NOW,
            locatorJson = exactLocator("other edition"),
            editionSha = "sha-remote",
        )
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.2, null, "reading", NOW)

        assertEquals(ResolveOutcome.Done, sync().takeRemotePosition(LOCAL, 1))
        assertEquals("{}", db.readingProgressDao().get(LOCAL)?.locatorJson)

        db.syncPeerStateDao().persistPending(
            bookUrl = LOCAL,
            peerId = peer(),
            progression = 0.9,
            status = "reading",
            remoteUpdatedAt = NOW,
            locatorJson = """{"href":"/forward-biased.xhtml","text":{"highlight":"next"}}""",
            editionSha = "sha-local",
        )
        assertEquals(ResolveOutcome.Done, sync().takeRemotePosition(LOCAL, 2))
        assertEquals("{}", db.readingProgressDao().get(LOCAL)?.locatorJson)
    }

    @Test
    fun `snapshot lands only the highest non-local sequence for each work`() = runTest {
        connect(cursor = 5)
        db.bookDao().upsert(local())
        alias()
        server.enqueue(MockResponse(code = 410, body = """{"error":"resync_required"}"""))
        server.enqueue(
            json(
                """{"ops":[${op(40, 0.6)},${op(30, 0.9)}],"snapshot_seq":42}""",
            ),
        )

        sync().syncAll(null)

        assertEquals(0.6, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0)
        assertEquals(42L, db.remoteServerDao().get()?.syncCursorSeq)
    }

    @Test
    fun `nothing is asked of the server when no account is connected`() = runTest {
        db.bookDao().upsert(local())

        assertEquals(SyncOutcome.NotApplicable, sync().syncAll(null))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a deleted work is named afresh and the position retried in the same run`() = runTest {
        // Orphan cleanup removed the work this device had cached; the
        // push is refused with the structured unknown_work answer.
        connect()
        db.bookDao().upsert(local())
        alias(workId = "w-old")
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        server.enqueue(json("""{"ops":[]}"""))
        unknownWork("op_id", SyncOps.opIdFor("device-a", "w-old", 1), "w-old")
        resolved("w-new")
        server.enqueue(json("""{"ops":[]}""")) // the reseed question
        accepted("w-new")

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        val pushes = requests().filter { it.target.startsWith("/v1/ops") }
        assertEquals(2, pushes.size)
        val first = JSONObject(pushes[0].body!!.utf8()).getJSONArray("ops").getJSONObject(0)
        val retried = JSONObject(pushes[1].body!!.utf8()).getJSONArray("ops").getJSONObject(0)
        assertEquals("w-old", first.getString("work_id"))
        // The retry names the fresh work and derives a fresh op id; the
        // reading itself is what it was.
        assertEquals("w-new", retried.getString("work_id"))
        assertEquals(SyncOps.opIdFor("device-a", "w-new", 1), retried.getString("op_id"))
        assertEquals(first.getDouble("progression"), retried.getDouble("progression"), 0.0)
        assertEquals(first.getString("client_ts"), retried.getString("client_ts"))
        // Settled only now, under the new name.
        assertEquals("w-new", db.workIdentityDao().alias(LOCAL, peer())?.workId)
        assertEquals(1L, db.syncPeerStateDao().get(LOCAL, peer())?.ackedRevision)
    }

    @Test
    fun `a session for a deleted work is rebuilt and sent under the new name`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias(workId = "w-old")
        val session = closedSession(from = 0.1, to = 0.4)
        val wireId = SessionUploads.sessionIdFor("device-a", session)
        server.enqueue(json("""{"ops":[]}"""))
        unknownWork("session_id", wireId, "w-old")
        resolved("w-new")
        server.enqueue(json("""{"ops":[]}""")) // the reseed question
        server.enqueue(json("""{"accepted":1}"""))

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        val sent = requests().filter { it.target.startsWith("/v1/sessions") }
        assertEquals(2, sent.size)
        val first = JSONObject(sent[0].body!!.utf8()).getJSONArray("sessions").getJSONObject(0)
        val retried = JSONObject(sent[1].body!!.utf8()).getJSONArray("sessions").getJSONObject(0)
        assertEquals("w-old", first.getString("work_id"))
        assertEquals("w-new", retried.getString("work_id"))
        // The idempotency key derives from the local row, not the work.
        assertEquals(wireId, retried.getString("session_id"))
        assertNotNull(db.readingSessionDao().get(session)?.uploadedAt)
    }

    @Test
    fun `a session stays unuploaded while its refusal is being worked through`() = runTest {
        // The rejection answers the batch as a whole: nothing in it was
        // stored, so nothing in it may be marked done.
        connect()
        db.bookDao().upsert(local())
        alias(workId = "w-old")
        val session = closedSession(from = 0.1, to = 0.4)
        server.enqueue(json("""{"ops":[]}"""))
        unknownWork("session_id", SessionUploads.sessionIdFor("device-a", session), "w-old")
        // Re-resolution cannot reach the server; the session is a fact
        // about the past and waits, no less true next run.
        server.enqueue(MockResponse(code = 503, body = ""))

        val outcome = sync().syncAll(null)

        assertNull(db.readingSessionDao().get(session)?.uploadedAt)
        assertTrue(outcome is SyncOutcome.Failure && outcome.reason.worthRetrying)
    }

    @Test
    fun `a refused sitting is set aside by name and the rest still go`() = runTest {
        // The regression: a 409 for one session used to mark the whole
        // batch as sent, and the other sittings were never heard of again.
        connect()
        db.bookDao().upsert(local())
        alias()
        val good = closedSession(from = 0.1, to = 0.4)
        val bad = closedSession(from = 0.4, to = 0.5)
        val other = closedSession(from = 0.5, to = 0.6)
        val badWire = SessionUploads.sessionIdFor("device-a", bad)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(
            MockResponse(
                code = 409,
                body = """{"error":"session $badWire: session_id reused with a different payload",
                    "code":"id_reused","session_id":"$badWire","item_index":1}
                """.trimIndent(),
            ),
        )
        server.enqueue(json("""{"accepted":2}"""))

        val outcome = sync().syncAll(null)

        val sent = requests().filter { it.target.startsWith("/v1/sessions") }
        assertEquals(2, sent.size)
        val retried = JSONObject(sent[1].body!!.utf8()).getJSONArray("sessions")
        assertEquals(2, retried.length())
        assertNotNull(db.readingSessionDao().get(good)?.uploadedAt)
        assertNotNull(db.readingSessionDao().get(other)?.uploadedAt)
        // Not sent, not pretended to be: set aside for this server only.
        assertNull(db.readingSessionDao().get(bad)?.uploadedAt)
        val refusals = db.sessionRefusalDao().forPeer(peer())
        assertEquals(listOf(bad), refusals.map { it.sessionId })
        assertEquals("id_reused", refusals.single().code)
        // Reported, not swallowed.
        assertTrue(outcome !is SyncOutcome.Success)

        // The next run does not offer it again.
        server.enqueue(json("""{"ops":[]}"""))
        sync().syncAll(null)
        assertEquals(2, requests().count { it.target.startsWith("/v1/sessions") })
    }

    @Test
    fun `a sitting refused for a reason this app does not know stays pending`() = runTest {
        // Naming the item is not proof it is at fault. A code this app
        // has never seen may well be temporary, so the sitting is left
        // where it is rather than set aside for good.
        connect()
        db.bookDao().upsert(local())
        alias()
        val good = closedSession(from = 0.1, to = 0.4)
        val named = closedSession(from = 0.4, to = 0.5)
        val namedWire = SessionUploads.sessionIdFor("device-a", named)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(
            MockResponse(
                code = 400,
                body = """{"error":"session $namedWire: the shelf is full for now",
                    "code":"quota_exhausted","session_id":"$namedWire","item_index":1}
                """.trimIndent(),
            ),
        )

        val outcome = sync().syncAll(null)

        // Nothing sent, nothing set aside, and said so.
        assertEquals(1, requests().count { it.target.startsWith("/v1/sessions") })
        assertTrue(db.sessionRefusalDao().forPeer(peer()).isEmpty())
        assertNull(db.readingSessionDao().get(good)?.uploadedAt)
        assertNull(db.readingSessionDao().get(named)?.uploadedAt)
        assertTrue(outcome !is SyncOutcome.Success)
    }

    @Test
    fun `a refusal that names no item is bisected down to the one it means`() = runTest {
        // An older server refuses in prose. Bisecting finds the sitting
        // without ever marking a good one as sent.
        connect()
        db.bookDao().upsert(local())
        alias()
        val a = closedSession(from = 0.1, to = 0.2)
        val b = closedSession(from = 0.2, to = 0.3)
        val c = closedSession(from = 0.3, to = 0.4)
        server.enqueue(json("""{"ops":[]}"""))
        val prose = MockResponse(code = 400, body = """{"error":"session b: idle_ms out of range"}""")
        server.enqueue(prose) // [a, b, c]
        server.enqueue(json("""{"accepted":1}""")) // [a]
        server.enqueue(prose) // [b, c]
        server.enqueue(prose) // [b]
        server.enqueue(json("""{"accepted":1}""")) // [c]

        sync().syncAll(null)

        assertNotNull(db.readingSessionDao().get(a)?.uploadedAt)
        assertNull(db.readingSessionDao().get(b)?.uploadedAt)
        assertNotNull(db.readingSessionDao().get(c)?.uploadedAt)
        assertEquals(listOf(b), db.sessionRefusalDao().forPeer(peer()).map { it.sessionId })
    }

    @Test
    fun `a body too big is halved rather than refused`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()
        val a = closedSession(from = 0.1, to = 0.2)
        val b = closedSession(from = 0.2, to = 0.3)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(MockResponse(code = 413, body = """{"error":"request body too large"}"""))
        server.enqueue(json("""{"accepted":1}"""))
        server.enqueue(json("""{"accepted":1}"""))

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        assertNotNull(db.readingSessionDao().get(a)?.uploadedAt)
        assertNotNull(db.readingSessionDao().get(b)?.uploadedAt)
        assertTrue(db.sessionRefusalDao().forPeer(peer()).isEmpty())
    }

    @Test
    fun `a code this app does not know leaves the sitting pending`() = runTest {
        // Not proof the sitting is at fault; reported, kept, tried again.
        connect()
        db.bookDao().upsert(local())
        alias()
        val a = closedSession(from = 0.1, to = 0.2)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(
            MockResponse(code = 400, body = """{"error":"something new","code":"a_rule_from_the_future"}"""),
        )

        val outcome = sync().syncAll(null)

        assertNull(db.readingSessionDao().get(a)?.uploadedAt)
        assertTrue(db.sessionRefusalDao().forPeer(peer()).isEmpty())
        assertTrue(outcome !is SyncOutcome.Success)
    }

    @Test
    fun `unnamed books do not stand in front of sittings that can be sent`() = runTest {
        // The head-of-line block: a thousand old sittings of a book the
        // server has no name for used to fill the window and hide newer
        // ones behind them.
        connect()
        db.bookDao().upsert(local())
        db.bookDao().upsert(local().copy(url = LOCAL2, title = "Unnamed"))
        alias()
        repeat(SessionUploads.MAX_BATCH) { closedSession(from = 0.1, to = 0.2, bookUrl = LOCAL2) }
        val sendable = closedSession(from = 0.3, to = 0.4)
        // The server can only guess at the other book, so it has no
        // usable name here and its sittings wait.
        server.enqueue(json("""{"work_id":"w-2","confidence":"low","created":false}"""))
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(json("""{"accepted":1}"""))

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        assertNotNull(db.readingSessionDao().get(sendable)?.uploadedAt)
        val sent = requests().filter { it.target.startsWith("/v1/sessions") }
        assertEquals(1, JSONObject(sent.single().body!!.utf8()).getJSONArray("sessions").length())
    }

    @Test
    fun `two stale books in one batch recover one at a time`() = runTest {
        connect()
        db.bookDao().upsert(local())
        db.bookDao().upsert(local().copy(url = LOCAL2, title = "Another Book"))
        alias(workId = "w-old-1")
        alias(workId = "w-old-2", bookUrl = LOCAL2)
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        db.readingProgressDao().recordLocal(LOCAL2, LOCATOR, 0.7, null, "reading", NOW)

        server.enqueue(json("""{"ops":[]}"""))
        unknownWork("op_id", SyncOps.opIdFor("device-a", "w-old-1", 1), "w-old-1")
        resolved("w-new-1")
        server.enqueue(json("""{"ops":[]}"""))
        // The retry still names the second book's stale work, and the
        // server refuses that one next.
        unknownWork("op_id", SyncOps.opIdFor("device-a", "w-old-2", 1), "w-old-2")
        resolved("w-new-2")
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(
            json(
                """{"results":[
                    {"op_id":"${SyncOps.opIdFor("device-a", "w-new-1", 1)}","status":"applied"},
                    {"op_id":"${SyncOps.opIdFor("device-a", "w-new-2", 1)}","status":"applied"}]}
                """.trimIndent(),
            ),
        )

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        val pushes = requests().filter { it.target.startsWith("/v1/ops") }
        assertEquals(3, pushes.size)
        val finalOps = JSONObject(pushes[2].body!!.utf8()).getJSONArray("ops")
        val workIds = (0 until finalOps.length()).map { finalOps.getJSONObject(it).getString("work_id") }
        assertEquals(setOf("w-new-1", "w-new-2"), workIds.toSet())
        assertEquals(1L, db.syncPeerStateDao().get(LOCAL, peer())?.ackedRevision)
        assertEquals(1L, db.syncPeerStateDao().get(LOCAL2, peer())?.ackedRevision)
    }

    @Test
    fun `a book that comes back unnameable sends nothing and stays dirty`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias(workId = "w-old")
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        server.enqueue(json("""{"ops":[]}"""))
        unknownWork("op_id", SyncOps.opIdFor("device-a", "w-old", 1), "w-old")
        // Only a title-and-author guess this time: the reader has to
        // answer before anything is exchanged under it.
        server.enqueue(
            json("""{"work_id":"w-new","confidence":"low","created":false}"""),
        )

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        assertEquals(1, requests().count { it.target.startsWith("/v1/ops") })
        assertNull(db.syncPeerStateDao().get(LOCAL, peer())?.takeIf { it.ackedRevision > 0 })
        assertEquals(0.4, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
    }

    @Test
    fun `a second deletion in the same run stops asking and stays dirty`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias(workId = "w-old")
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        server.enqueue(json("""{"ops":[]}"""))
        unknownWork("op_id", SyncOps.opIdFor("device-a", "w-old", 1), "w-old")
        resolved("w-new")
        server.enqueue(json("""{"ops":[]}"""))
        // The fresh name was deleted again before the retry landed.
        unknownWork("op_id", SyncOps.opIdFor("device-a", "w-new", 1), "w-new")

        val outcome = sync().syncAll(null)

        assertEquals(
            SyncOutcome.Failure(com.chmouel.liseur.data.remote.SyncFailure.StaleIdentity),
            outcome,
        )
        // The second stale name is forgotten too, and the position is
        // still owed: WorkManager's backoff asks again later.
        assertNull(db.workIdentityDao().alias(LOCAL, peer()))
        assertNull(db.syncPeerStateDao().get(LOCAL, peer())?.takeIf { it.ackedRevision > 0 })
        assertEquals(0.4, db.readingProgressDao().get(LOCAL)?.totalProgression!!, 0.0001)
    }

    @Test
    fun `an ordinary bad request invalidates no name`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias(workId = "w-old")
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(MockResponse(code = 400, body = """{"error":"locator too large"}"""))

        val outcome = sync().syncAll(null)

        // No recovery is even attempted against a refusal that is not
        // the structured unknown_work answer.
        assertTrue(outcome is SyncOutcome.Failure)
        assertEquals("w-old", db.workIdentityDao().alias(LOCAL, peer())?.workId)
        assertEquals(0, requests().count { it.target.startsWith("/v1/works/resolve") })
    }

    @Test
    fun `a locator the server will not take is dropped and the position sent alone`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        val opId = SyncOps.opIdFor("device-a", "w-1", 1)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(
            MockResponse(
                code = 400,
                body = """{"error":"op $opId: locator too large","code":"locator_too_large",
                    "op_id":"$opId","item_index":0,"limit":4096}
                """.trimIndent(),
            ),
        )
        accepted()

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        val pushes = requests().filter { it.target.startsWith("/v1/ops") }
        assertEquals(2, pushes.size)
        val first = JSONObject(pushes[0].body!!.utf8()).getJSONArray("ops").getJSONObject(0)
        val retried = JSONObject(pushes[1].body!!.utf8()).getJSONArray("ops").getJSONObject(0)
        assertTrue(first.has("locator"))
        assertFalse(retried.has("locator"))
        // Same id: the refused batch was never stored, so it is free.
        assertEquals(opId, retried.getString("op_id"))
        assertEquals(1L, db.syncPeerStateDao().get(LOCAL, peer())?.ackedRevision)
    }

    @Test
    fun `a positions batch the server will not take whole is cut up`() = runTest {
        // The server's item limit is configurable and may sit below the
        // 500 this app sends. Before, the identical batch went again
        // every run and neither position ever landed.
        connect()
        db.bookDao().upsert(local())
        db.bookDao().upsert(local().copy(url = LOCAL2, lastOpenedAt = NOW - 1))
        alias()
        alias(workId = "w-2", bookUrl = LOCAL2)
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        db.readingProgressDao().recordLocal(LOCAL2, LOCATOR, 0.5, null, "reading", NOW)
        var pushes = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.target.startsWith("/v1/ops")) return json("""{"ops":[]}""")
                if (++pushes == 1) {
                    return MockResponse(
                        code = 400,
                        body = """{"error":"batch too large","code":"batch_too_large","limit":1}""",
                    )
                }
                return applied(request)
            }
        }

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        val sent = requests().filter { it.target.startsWith("/v1/ops") }
        assertEquals(3, sent.size)
        assertEquals(2, JSONObject(sent[0].body!!.utf8()).getJSONArray("ops").length())
        assertEquals(1, JSONObject(sent[1].body!!.utf8()).getJSONArray("ops").length())
        assertEquals(1, JSONObject(sent[2].body!!.utf8()).getJSONArray("ops").length())
        assertEquals(1L, db.syncPeerStateDao().get(LOCAL, peer())?.ackedRevision)
        assertEquals(1L, db.syncPeerStateDao().get(LOCAL2, peer())?.ackedRevision)
    }

    @Test
    fun `two copies of one book take their own answers, not each other's`() = runTest {
        // Both copies are the same work at the same revision, so they
        // derive the same op id. The server answers by position: the
        // first is stored and the second, carrying a different place,
        // is a conflict. Reading the answers by id gave both of them
        // whichever came last.
        connect()
        db.bookDao().upsert(local())
        db.bookDao().upsert(local().copy(url = LOCAL2, lastOpenedAt = NOW - 1))
        alias()
        alias(bookUrl = LOCAL2)
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        db.readingProgressDao().recordLocal(LOCAL2, LOCATOR, 0.5, null, "reading", NOW)
        val opId = SyncOps.opIdFor("device-a", "w-1", 1)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(
            json(
                """{"results":[
                    {"op_id":"$opId","status":"applied"},
                    {"op_id":"$opId","status":"conflict"}]}
                """.trimIndent(),
            ),
        )

        val outcome = sync().syncAll(null)

        val ops = JSONObject(
            requests().first { it.target.startsWith("/v1/ops") }.body!!.utf8(),
        ).getJSONArray("ops")
        assertEquals(2, ops.length())
        // The premise: one work, one revision, so one op id for both.
        assertEquals(opId, ops.getJSONObject(0).getString("op_id"))
        assertEquals(opId, ops.getJSONObject(1).getString("op_id"))
        // The copy the server took is settled and keeps its revision;
        // the refused one is renamed so it can go again, and neither
        // took the other's answer.
        val settled = listOf(LOCAL, LOCAL2).filter {
            db.syncPeerStateDao().get(it, peer())?.ackedRevision == 1L
        }
        assertEquals(1, settled.size)
        val refused = listOf(LOCAL, LOCAL2).single { it !in settled }
        assertEquals(2L, db.readingProgressDao().currentRevision(refused))
        assertEquals(1L, db.readingProgressDao().currentRevision(settled.single()))
        assertTrue(outcome !is SyncOutcome.Success)
    }

    @Test
    fun `a lone position too big for the server goes without its locator`() = runTest {
        // A body past the byte bound is a 413 with no code to it. The
        // locator is the only part of an op with any size, so it is
        // what goes.
        connect()
        db.bookDao().upsert(local())
        alias()
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        val opId = SyncOps.opIdFor("device-a", "w-1", 1)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(MockResponse(code = 413, body = """{"error":"request body too large"}"""))
        accepted()

        assertEquals(SyncOutcome.Success, sync().syncAll(null))

        val pushes = requests().filter { it.target.startsWith("/v1/ops") }
        assertEquals(2, pushes.size)
        val retried = JSONObject(pushes[1].body!!.utf8()).getJSONArray("ops").getJSONObject(0)
        assertFalse(retried.has("locator"))
        assertEquals(opId, retried.getString("op_id"))
        assertEquals(1L, db.syncPeerStateDao().get(LOCAL, peer())?.ackedRevision)
    }

    @Test
    fun `a position refused even bare is reported rather than asked for ever`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        server.enqueue(json("""{"ops":[]}"""))
        val tooBig = MockResponse(code = 413, body = """{"error":"request body too large"}""")
        server.enqueue(tooBig) // with its locator
        server.enqueue(tooBig) // and without

        assertTrue(sync().syncAll(null) !is SyncOutcome.Success)

        // It stops there rather than going round again: nothing else in
        // an op can be made smaller.
        assertEquals(2, requests().count { it.target.startsWith("/v1/ops") })
        assertNull(db.syncPeerStateDao().get(LOCAL, peer())?.takeIf { it.ackedRevision > 0 })
    }

    @Test
    fun `a conflict renames the position so the next run can get through`() = runTest {
        // The server holds this id from a credential since replaced. The
        // same id will be refused for ever; a fresh revision derives a
        // fresh one for the same reading.
        connect()
        db.bookDao().upsert(local())
        alias()
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        val opId = SyncOps.opIdFor("device-a", "w-1", 1)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(
            json("""{"results":[{"op_id":"$opId","status":"conflict","reason":"op_id reused with a different payload"}]}"""),
        )

        val outcome = sync().syncAll(null)

        // Reported, not swallowed; still dirty, under a new revision.
        assertTrue(outcome !is SyncOutcome.Success)
        assertEquals(2L, db.readingProgressDao().currentRevision(LOCAL))
        assertNull(db.syncPeerStateDao().get(LOCAL, peer())?.takeIf { it.ackedRevision > 0 })

        server.enqueue(json("""{"ops":[]}"""))
        accepted(revision = 2)
        assertEquals(SyncOutcome.Success, sync().syncAll(null))
        val retried = requests().filter { it.target.startsWith("/v1/ops") }[1]
        val op = JSONObject(retried.body!!.utf8()).getJSONArray("ops").getJSONObject(0)
        assertEquals(SyncOps.opIdFor("device-a", "w-1", 2), op.getString("op_id"))
        assertEquals(0.4, op.getDouble("progression"), 0.0)
        assertEquals(2L, db.syncPeerStateDao().get(LOCAL, peer())?.ackedRevision)
    }

    @Test
    fun `a conflict does not touch a revision a page turn already moved`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias()
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        val opId = SyncOps.opIdFor("device-a", "w-1", 1)
        server.enqueue(json("""{"ops":[]}"""))
        server.enqueue(json("""{"results":[{"op_id":"$opId","status":"conflict"}]}"""))
        // The page turns while the request is in the air.
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.target == "/v1/ops") {
                    runBlocking { db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.5, null, "reading", NOW + 1) }
                }
                return original.dispatch(request)
            }
        }

        sync().syncAll(null)

        // Revision 2 is the page turn's; nothing invented a third.
        assertEquals(2L, db.readingProgressDao().currentRevision(LOCAL))
    }

    @Test
    fun `a refusal naming another op or work invalidates nothing`() = runTest {
        connect()
        db.bookDao().upsert(local())
        alias(workId = "w-old")
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.4, null, "reading", NOW)
        server.enqueue(json("""{"ops":[]}"""))
        // The shape is right but the op id is not this batch's: the
        // answer is malformed, not a stale identity.
        unknownWork("op_id", "op-that-was-never-sent", "w-old")

        assertTrue(sync().syncAll(null) is SyncOutcome.Failure)
        assertEquals("w-old", db.workIdentityDao().alias(LOCAL, peer())?.workId)

        // Same for a work id that is not the one the op was sent under.
        server.enqueue(json("""{"ops":[]}"""))
        unknownWork("op_id", SyncOps.opIdFor("device-a", "w-old", 1), "w-elsewhere")

        assertTrue(sync().syncAll(null) is SyncOutcome.Failure)
        assertEquals("w-old", db.workIdentityDao().alias(LOCAL, peer())?.workId)
    }

    @Test
    fun `a server that cannot be reached ends the run instead of asking about every book`() =
        runTest {
            // Nothing is listening, so every connection is refused
            // rather than answered. That is the shape of the failure a
            // server on the far side of a network the device cannot
            // route through produces, only without the wait.
            connect(baseUrl = "http://127.0.0.1:$deadPort")
            db.bookDao().upsert(local())
            db.bookDao().upsert(local().copy(url = LOCAL2, lastOpenedAt = NOW - 1))
            db.bookDao().upsert(local().copy(url = LOCAL3, lastOpenedAt = NOW - 2))

            val outcome = sync().syncAll(null)

            // Each unanswered question costs a whole connect timeout on
            // a real network, and a reader opening a book waits behind
            // every one of them, so the run gives up on the first.
            assertEquals(SyncOutcome.Failure(SyncFailure.Offline), outcome)
            // The pull, the pushes and the sessions were never tried
            // either: they would have gone to the same dead address.
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `a server that answers with a refusal is still asked about the next book`() = runTest {
        connect()
        db.bookDao().upsert(local())
        db.bookDao().upsert(local().copy(url = LOCAL2, lastOpenedAt = NOW - 1))
        db.bookDao().upsert(local().copy(url = LOCAL3, lastOpenedAt = NOW - 2))
        // 408 is a *server* saying it grew tired of waiting. It arrived
        // over a connection that worked, so it says something about the
        // one request and nothing about whether the server is there.
        repeat(3) { server.enqueue(MockResponse(code = 408)) }
        server.enqueue(json("""{"ops":[]}"""))
        repeat(4) { server.enqueue(json("""{}""")) }

        sync().syncAll(null)

        // Every book was asked about, rather than the run stopping at
        // the first refusal.
        val asked = requests().count { it.target.startsWith("/v1/works/resolve") }
        assertTrue("asked $asked times for 3 books", asked >= 3)
        // And it carried on to the rest of its work afterwards.
        assertTrue(requests().any { it.target.startsWith("/v1/changes") })
    }

    @Test
    fun `a refusal the protocol treats as ordinary is not mistaken for silence`() = runTest {
        connect()
        db.bookDao().upsert(local())
        db.bookDao().upsert(local().copy(url = LOCAL2, lastOpenedAt = NOW - 1))
        db.bookDao().upsert(local().copy(url = LOCAL3, lastOpenedAt = NOW - 2))
        // A 409 that names fewer than two works: the server said this
        // book is ambiguous but did not say between what, so the name
        // cannot be settled. It is still an answer, over a connection
        // that plainly worked, and says nothing about the next book.
        repeat(3) { server.enqueue(MockResponse(code = 409, body = """{"works":[]}""")) }
        server.enqueue(json("""{"ops":[]}"""))
        repeat(4) { server.enqueue(json("""{}""")) }

        sync().syncAll(null)

        val asked = requests().count { it.target.startsWith("/v1/works/resolve") }
        assertTrue("asked $asked times for 3 books", asked >= 3)
        assertTrue(requests().any { it.target.startsWith("/v1/changes") })
    }

    @Test
    fun `a server that goes quiet after the naming sends nothing further`() = runTest {
        // Every book is already named, so naming asks nothing and the
        // run reaches the pull with no idea the network has gone. The
        // pull is where it finds out, and it must not then spend a
        // connect timeout on the pushes and another on the sessions.
        connect()
        db.bookDao().upsert(local())
        alias()
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.5, null, "reading", NOW)
        // One page arrives and the next says there is more, so the pull
        // asks again -- and that second request is dropped mid-flight,
        // with nothing coming back that could be read as the server
        // having said anything at all.
        server.enqueue(json("""{"ops":[],"has_more":true,"high_water":1}"""))
        repeat(4) {
            server.enqueue(
                MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build(),
            )
        }

        val outcome = sync().syncAll(null)

        assertEquals(SyncOutcome.Failure(SyncFailure.Offline), outcome)
        // The position this device owes and the session it recorded were
        // both held back: each would have waited out a connect timeout
        // of its own and come back knowing no more than the pull did.
        assertTrue(requests().any { it.target.startsWith(LiseurSyncApi.CHANGES) })
        assertTrue(requests().none { it.target.startsWith(LiseurSyncApi.OPS) })
        assertTrue(requests().none { it.target.startsWith(LiseurSyncApi.SESSIONS) })
    }

    @Test
    fun `a refusal on one book does not hide a network that died later`() = runTest {
        // The server answers the naming with a refusal on one book, which
        // is its own considered answer and not worth coming back for. Then
        // the connection dies with this device's position still unsent.
        // The run has to report the silence, because that is the part
        // worth retrying -- reporting the refusal would say there is
        // nothing to come back for.
        connect()
        db.bookDao().upsert(local())
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.5, null, "reading", NOW)
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"no such work"}""").build())
        repeat(4) {
            server.enqueue(
                MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build(),
            )
        }

        val outcome = sync().syncAll(null)

        assertEquals(SyncOutcome.Failure(SyncFailure.Offline), outcome)
    }

    @Test
    fun `a refusal is reported as what the server said, not as being offline`() = runTest {
        // Nothing here is unreachable: the server answered, and said no.
        // Calling that offline reads wrong to the reader and sends the
        // retry machinery back for something that will be refused again.
        connect()
        db.bookDao().upsert(local())
        db.readingProgressDao().recordLocal(LOCAL, LOCATOR, 0.5, null, "reading", NOW)
        repeat(6) {
            server.enqueue(
                MockResponse.Builder().code(404).body("""{"error":"no such work"}""").build(),
            )
        }

        val outcome = sync().syncAll(null)

        assertEquals(SyncOutcome.Failure(SyncFailure.NotFound), outcome)
    }

    // -- Scaffolding ------------------------------------------------------
    private fun sync(online: Boolean = true): LiseurSyncPositionSync {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        return LiseurSyncPositionSync(
            serverDao = db.remoteServerDao(),
            bookDao = db.bookDao(),
            progressDao = db.readingProgressDao(),
            peerStateDao = db.syncPeerStateDao(),
            identityDao = db.workIdentityDao(),
            sessionDao = db.readingSessionDao(),
            sessionRefusalDao = db.sessionRefusalDao(),
            works = WorkResolver(
                dao = db.workIdentityDao(),
                fingerprints = BookFingerprintStore(context, db.workIdentityDao()) { NOW },
                now = { NOW },
            ),
            deviceKey = { "device-a" },
            finishedState = FinishedState(db.bookDao(), db.readingProgressDao()),
            networkAvailability = { online },
            now = { NOW },
        )
    }

    /** A port nothing is listening on, for a server that is simply not there. */
    private val deadPort: Int by lazy {
        java.net.ServerSocket(0).use { it.localPort }
    }

    private suspend fun connect(
        cursor: Long = 0,
        deviceId: String? = null,
        baseUrl: String = "http://127.0.0.1:${server.port}",
    ) {
        db.remoteServerDao().upsert(
            RemoteServer(
                kind = ServerKind.LISEUR_SYNC,
                baseUrl = baseUrl,
                username = "ada",
                passwordCipher = null,
                apiKeyCipher = null,
                accountId = deviceId,
                userId = null,
                koboTokenCipher = null,
                canDownload = true,
                addedAt = NOW,
                catalogSyncedAt = null,
                positionSyncedAt = null,
                syncToken = null,
                liseurTokenCipher = CredentialCipher.encrypt("device-secret"),
                syncCursorSeq = cursor,
            ),
        )
    }

    /** The server is about to be asked what it calls this book. */
    private fun resolved(workId: String = "w-1") = server.enqueue(
        MockResponse(
            code = 200,
            body = """{"work_id":"$workId","confidence":"high","created":false}""",
        ),
    )

    /**
     * The server no longer holds the work a batch item named.
     *
     * [field] is `op_id` or `session_id` — which item of the batch
     * blamed the deleted work.
     */
    private fun unknownWork(field: String, itemId: String, workId: String) = server.enqueue(
        MockResponse(
            code = 400,
            body = """{"error":"unknown work","code":"unknown_work",
                "work_id":"$workId","$field":"$itemId"}
            """.trimIndent(),
        ),
    )

    /** The one-off "where does this book stand" for a fresh name. */
    private fun seeded() = server.enqueue(json("""{"ops":[]}"""))

    /** The server takes every op of the request, naming each back. */
    private fun applied(request: RecordedRequest): MockResponse {
        val ops = JSONObject(request.body!!.utf8()).getJSONArray("ops")
        val results = (0 until ops.length()).joinToString(",") {
            """{"op_id":"${ops.getJSONObject(it).getString("op_id")}","status":"applied"}"""
        }
        return json("""{"results":[$results]}""")
    }

    /** The server takes the op — naming it back, as it must. */
    private fun accepted(workId: String = "w-1", revision: Long = 1) = server.enqueue(
        json(
            """{"results":[{"op_id":"${SyncOps.opIdFor("device-a", workId, revision)}",
                "status":"applied"}]}
            """.trimIndent(),
        ),
    )

    private suspend fun alias(
        workId: String = "w-1",
        seeded: Boolean = true,
        confidence: String = "high",
        deviceId: String? = null,
        bookUrl: String = LOCAL,
        editionSha: String? = null,
        confirmed: Boolean = true,
    ) =
        db.workIdentityDao().upsert(
            com.chmouel.liseur.data.db.WorkAlias(
                bookUrl = bookUrl,
                peerId = peer(deviceId),
                workId = workId,
                confidence = confidence,
                confirmed = confirmed,
                seeded = seeded,
                editionSha = editionSha,
                resolvedAt = NOW,
            ),
        )

    private fun op(
        seq: Long,
        progression: Double,
        deviceId: String? = null,
        locatorJson: String? = null,
        editionSha: String? = null,
    ): String {
        val device = deviceId?.let { ""","device_id":"$it"""" } ?: ""
        val locator = locatorJson?.let { ""","locator":$it""" } ?: ""
        val edition = editionSha?.let { ""","edition_sha":"$it"""" } ?: ""
        return """{"op_id":"o-$seq","work_id":"w-1","seq":$seq,
            "progression":$progression,
            "client_ts":"${SyncOps.formatTime(NOW)}"$device$locator$edition}
        """.trimIndent()
    }

    private fun json(body: String) = MockResponse(code = 200, body = body)

    /** A record with a sequence number and no position anyone can use. */
    private fun spoiled(seq: Long): String =
        """{"op_id":"o-$seq","work_id":"w-1","seq":$seq,
            "progression":null,
            "client_ts":"${SyncOps.formatTime(NOW)}"}
        """.trimIndent()

    private fun exactLocator(highlight: String, progression: Double = 0.8): String = JSONObject()
        .put("href", "/c1.xhtml")
        .put("type", "application/xhtml+xml")
        .put(
            "locations",
            JSONObject()
                .put("progression", progression)
                .put("totalProgression", progression)
                .put("cssSelector", "#p1")
                .put("liseurAnchor", 1),
        )
        .put("text", JSONObject().put("highlight", highlight))
        .toString()

    /** Every request the server has seen, including earlier runs'. */
    private fun requests(): List<RecordedRequest> {
        while (seen.size < server.requestCount) seen += server.takeRequest()
        return seen.toList()
    }

    private val seen = mutableListOf<RecordedRequest>()

    private suspend fun closedSession(from: Double, to: Double, bookUrl: String = LOCAL): Long {
        val dao = db.readingSessionDao()
        val id = dao.insert(
            com.chmouel.liseur.data.db.ReadingSession(
                bookUrl = bookUrl,
                startedAt = NOW,
                lastCheckpointAt = NOW,
            ),
        )
        dao.checkpoint(id, totalMs = 0, atMillis = NOW, progression = from)
        dao.finish(id, totalMs = 60_000, atMillis = NOW + 60_000, progression = to)
        return id
    }

    private fun peer(deviceId: String? = null) =
        "liseursync|http://127.0.0.1:${server.port}|${deviceId ?: "ada"}"

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
        const val LOCAL2 = "content://sd/other.epub"
        const val LOCAL3 = "content://sd/third.epub"
        const val LOCATOR = """{"href":"/c1.xhtml"}"""
    }
}
