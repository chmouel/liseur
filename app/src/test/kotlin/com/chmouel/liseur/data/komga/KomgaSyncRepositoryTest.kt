package com.chmouel.liseur.data.komga

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.DeviceIdentityRepository
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncSnapshot
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reconciling reading positions with Komga, against real SQL and a real
 * HTTP server.
 *
 * The decisions themselves are `reconcileReadingState`'s and are tested
 * there. What is tested here is everything around them, which is where
 * a position actually gets lost: whether the right thing is asked for,
 * whether what comes back is understood, and whether what is written
 * down afterwards matches what the server was told.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class KomgaSyncRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var db: LiseurDatabase
    private lateinit var sync: KomgaSyncRepository

    private val bookId = "0B7C3D"
    private val bookUrl = "komga:$bookId"
    private lateinit var account: String

    @Before
    fun open() {
        // Robolectric has no Android Keystore, and how the API key was
        // stored is not what these tests are about.
        CredentialCipher.keyForTesting =
            javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        server = MockWebServer()
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()

        sync = KomgaSyncRepository(
            serverDao = db.remoteServerDao(),
            bookDao = db.bookDao(),
            progressDao = db.readingProgressDao(),
            finishedState = FinishedState(
                bookDao = db.bookDao(),
                progressDao = db.readingProgressDao(),
            ),
            device = DeviceIdentityRepository(context),
        )
    }

    @After
    fun close() {
        db.close()
        server.close()
        CredentialCipher.keyForTesting = null
    }

    private suspend fun connect() {
        val stored = RemoteServer(
            kind = ServerKind.KOMGA,
            baseUrl = "http://127.0.0.1:${server.port}",
            username = "ada",
            passwordCipher = null,
            apiKeyCipher = RemoteServer.seal("secret"),
            accountId = "0R571X",
            userId = null,
            koboTokenCipher = null,
            canDownload = true,
            addedAt = 0,
            catalogSyncedAt = null,
            positionSyncedAt = null,
            syncToken = null,
        )
        db.remoteServerDao().upsert(stored)
        account = stored.accountKey
        db.bookDao().upsert(
            Book(
                url = bookUrl,
                title = "Moby Dick",
                author = "Herman Melville",
                coverPath = null,
                source = null,
                addedAt = 0,
                lastOpenedAt = null,
                downloadState = DownloadState.REMOTE,
                remoteUuid = bookId,
            ),
        )
    }

    /** One book, with the reading progress Komga reports inline. */
    private fun bookJson(page: Int?, completed: Boolean, readDate: String?): String {
        val progress = if (readDate == null) {
            "null"
        } else {
            """{"page":$page,"completed":$completed,"readDate":"$readDate"}"""
        }
        return """
        {
          "id": "$bookId",
          "name": "moby-dick.epub",
          "sizeBytes": 100,
          "lastModified": "2024-01-01T00:00:00Z",
          "media": {"pagesCount": 400},
          "metadata": {"title": "Moby Dick", "authors": [{"name":"Herman Melville","role":"writer"}]},
          "readProgress": $progress
        }
        """.trimIndent()
    }

    private fun locatorJson(href: String, progression: Double, total: Double) = """
        {
          "href": "$href",
          "type": "application/xhtml+xml",
          "locations": {
            "progression": $progression,
            "totalProgression": $total,
            "cssSelector": "#reading-anchor",
            "liseurAnchor": 1
          },
          "text": {"highlight": "remembered word"}
        }
    """.trimIndent()

    private fun json(body: String) = MockResponse(body = body)

    /**
     * What `GET /progression` actually answers: a locator wrapped in an
     * R2Progression, with the timestamp that cannot be trusted for
     * ordering still on it, because that is what a real server sends.
     */
    private fun progressionJson(locator: String) = """
        {
          "modified": "2024-06-01T12:00:00+02:00",
          "device": {"id": "other", "name": "Another reader"},
          "locator": $locator
        }
    """.trimIndent()

    /** Every request the run made, keyed by path, so order does not matter. */
    private fun requests(): List<RecordedRequest> =
        generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }.toList()

    // -- Pulling ----------------------------------------------------------

    @Test
    fun `a position read on another device arrives with the exact place`() = runTest {
        connect()
        server.enqueue(json(bookJson(page = 40, completed = false, readDate = "2024-06-01T10:00:00Z")))
        server.enqueue(json(progressionJson(locatorJson("OEBPS/ch3.xhtml", 0.5, 0.31))))

        assertEquals(SyncOutcome.Success, sync.syncBook(bookUrl))

        val row = requireNotNull(db.readingProgressDao().get(bookUrl))
        assertEquals(0.31, row.totalProgression!!, 1e-9)
        // The whole point of preferring Komga's own API over the Kobo one:
        // the book reopens where the other device was, not at a percentage.
        assertEquals("OEBPS/ch3.xhtml", JSONObject(row.locatorJson).getString("href"))
        assertFalse(row.isDirty)
    }

    // -- Reusing what the refresh already read ---------------------------

    /**
     * A second book on the shelf, so a full sync is a walk of the
     * catalog rather than the one-book request a library of one gets.
     */
    private suspend fun anotherBook() {
        db.bookDao().upsert(
            Book(
                url = "komga:OTHER",
                title = "Another",
                author = null,
                coverPath = null,
                source = null,
                addedAt = 0,
                lastOpenedAt = null,
                downloadState = DownloadState.REMOTE,
                remoteUuid = "OTHER",
            ),
        )
    }

    /**
     * Pulling the shelf down walks Komga's catalog for the books; that
     * same answer carries every book's reading progress, so syncing
     * afterwards must not walk it a second time.
     */
    @Test
    fun `a sync offered the refresh's own listing does not ask for it again`() = runTest {
        connect()
        val snapshot = KomgaCatalogSnapshot(
            listOf(KomgaBooks.parseBook(JSONObject(bookJson(40, false, "2024-06-01T10:00:00Z")))),
        )
        server.enqueue(json(progressionJson(locatorJson("OEBPS/ch3.xhtml", 0.5, 0.31))))

        assertEquals(SyncOutcome.Success, sync.syncAll(SyncSnapshot(account, snapshot)))

        // Only the one book's position: no /books/list at all.
        val paths = requests().map { it.target.substringBefore("?") }
        assertEquals(listOf("/api/v1/books/$bookId/progression"), paths)
        assertEquals(0.31, db.readingProgressDao().get(bookUrl)!!.totalProgression!!, 1e-9)
    }

    /**
     * Reading progress belongs to whoever was signed in when it was
     * read. Someone else's is refused rather than applied.
     */
    @Test
    fun `a listing read for another account is not used`() = runTest {
        connect()
        anotherBook()
        val snapshot = KomgaCatalogSnapshot(
            listOf(KomgaBooks.parseBook(JSONObject(bookJson(40, false, "2024-06-01T10:00:00Z")))),
        )
        server.enqueue(json("""{"content":[],"last":true}"""))

        sync.syncAll(SyncSnapshot("somebody|else|-1", snapshot))

        assertEquals("/api/v1/books/list", requests().first().target.substringBefore("?"))
    }

    /** The hourly worker has no refresh behind it and must still work. */
    @Test
    fun `a sync with nothing offered walks the catalog itself`() = runTest {
        connect()
        anotherBook()
        server.enqueue(json("""{"content":[${bookJson(40, false, "2024-06-01T10:00:00Z")}],"last":true}"""))
        server.enqueue(json(progressionJson(locatorJson("OEBPS/ch3.xhtml", 0.5, 0.31))))

        assertEquals(SyncOutcome.Success, sync.syncAll())

        val paths = requests().map { it.target.substringBefore("?") }
        assertEquals(
            listOf("/api/v1/books/list", "/api/v1/books/$bookId/progression"),
            paths,
        )
    }

    /**
     * A run that did not settle everything must not be written down as
     * having synced: the app decides whether to sync on opening from
     * that timestamp, and a half-finished run would buy an hour's
     * silence it has not earned.
     */
    @Test
    fun `a run that did not finish is not written down as a sync`() = runTest {
        connect()
        server.enqueue(
            json(bookJson(page = 40, completed = false, readDate = "2024-06-01T10:00:00Z")),
        )
        server.enqueue(MockResponse(code = 500))

        val outcome = sync.syncBook(bookUrl)

        assertTrue(outcome is SyncOutcome.Partial)
        assertNull(db.remoteServerDao().get()!!.positionSyncedAt)
    }

    @Test
    fun `a book the server has never been told about is left alone`() = runTest {        connect()
        server.enqueue(json(bookJson(page = null, completed = false, readDate = null)))

        assertEquals(SyncOutcome.Success, sync.syncBook(bookUrl))

        assertNull(db.readingProgressDao().get(bookUrl))
        // Nothing to fetch, so the position endpoint is never touched.
        assertEquals(1, requests().size)
    }

    @Test
    fun `a book marked read in komga without being opened comes back finished`() = runTest {
        connect()
        // Komga's own interface can mark a book read without there ever
        // being a place in it, and then answers the position endpoint
        // with 204 and nothing at all.
        server.enqueue(json(bookJson(page = null, completed = true, readDate = "2024-06-01T10:00:00Z")))
        server.enqueue(MockResponse(code = 204))

        assertEquals(SyncOutcome.Success, sync.syncBook(bookUrl))

        val row = requireNotNull(db.readingProgressDao().get(bookUrl))
        assertEquals("Finished", row.status)
        assertNull(row.totalProgression)
        assertFalse(row.isDirty)
    }

    // -- Pushing ----------------------------------------------------------

    @Test
    fun `marking a book unread here forgets it on the server too`() = runTest {
        connect()
        db.readingProgressDao().recordLocal(
            bookUrl = bookUrl,
            locatorJson = locatorJson("OEBPS/ch20.xhtml", 0.9, 1.0),
            progression = 1.0,
            readingSpeed = null,
            status = "Finished",
            updatedAt = 5_000,
        )
        FinishedState(
            bookDao = db.bookDao(),
            progressDao = db.readingProgressDao(),
        ).setFinished(bookUrl, false)
        server.enqueue(json(bookJson(page = 400, completed = true, readDate = "2024-01-01T00:00:00Z")))
        repeat(3) { server.enqueue(MockResponse(code = 204)) }

        assertEquals(SyncOutcome.Success, sync.syncBook(bookUrl))

        // Komga has no way of being told "not finished": the only way to
        // undo a completion is to forget the progress altogether, which
        // has to happen before the place is sent again.
        val sent = requests()
        val cleared = sent.single { it.method == "DELETE" }
        assertTrue(cleared.target.endsWith("/api/v1/books/$bookId/read-progress"))
        assertTrue(sent.indexOf(cleared) < sent.indexOfFirst { it.method == "PUT" })
        // And it must settle, or the row would stay dirty for ever.
        assertFalse(requireNotNull(db.readingProgressDao().get(bookUrl)).isDirty)
    }

    @Test
    fun `reading done here is sent as a locator the server will take`() = runTest {
        connect()
        db.readingProgressDao().recordLocal(
            bookUrl = bookUrl,
            locatorJson = locatorJson("/OEBPS/ch9.xhtml", 0.25, 0.8),
            progression = 0.8,
            readingSpeed = null,
            status = "Reading",
            updatedAt = 5_000,
        )
        server.enqueue(json(bookJson(page = null, completed = false, readDate = null)))
        server.enqueue(MockResponse(code = 204))

        assertEquals(SyncOutcome.Success, sync.syncBook(bookUrl))

        val sent = requests().last()
        assertEquals("PUT", sent.method)
        assertTrue(sent.target.endsWith("/api/v1/books/$bookId/progression"))
        val body = JSONObject(sent.body!!.utf8())
        // Komga refuses an href that starts with a slash, and refuses one
        // with no progression beside it.
        val locator = body.getJSONObject("locator")
        assertEquals("OEBPS/ch9.xhtml", locator.getString("href"))
        assertEquals(0.25, locator.getJSONObject("locations").getDouble("progression"), 1e-9)

        val row = requireNotNull(db.readingProgressDao().get(bookUrl))
        assertFalse(row.isDirty)
        assertEquals(0.8, row.agreedProgression!!, 1e-9)
    }

    @Test
    fun `a server holding something newer keeps it`() = runTest {
        connect()
        db.readingProgressDao().recordLocal(
            bookUrl = bookUrl,
            locatorJson = locatorJson("OEBPS/ch9.xhtml", 0.25, 0.8),
            progression = 0.8,
            readingSpeed = null,
            status = "Reading",
            updatedAt = 5_000,
        )
        server.enqueue(json(bookJson(page = null, completed = false, readDate = null)))
        server.enqueue(MockResponse(code = 409))

        // A 409 is the server saying it already holds something at least
        // as new. That is not a failure and must not be overwritten, so
        // the run succeeds and the row stays dirty for the next pull.
        assertEquals(SyncOutcome.Success, sync.syncBook(bookUrl))
        assertTrue(requireNotNull(db.readingProgressDao().get(bookUrl)).isDirty)
    }

    @Test
    fun `a place the server cannot find becomes the nearest one it knows`() = runTest {
        connect()
        db.readingProgressDao().recordLocal(
            bookUrl = bookUrl,
            locatorJson = locatorJson("OEBPS/ch9.xhtml", 0.99, 0.8),
            progression = 0.8,
            readingSpeed = null,
            status = "Reading",
            updatedAt = 5_000,
        )
        server.enqueue(json(bookJson(page = null, completed = false, readDate = null)))
        server.enqueue(MockResponse(code = 400, body = "Invalid progression"))
        server.enqueue(
            json(
                """
                {"total": 2, "positions": [
                  {"href":"OEBPS/ch9.xhtml","locations":{"progression":0.5,"totalProgression":0.7}},
                  {"href":"OEBPS/ch9.xhtml","locations":{"progression":0.75,"totalProgression":0.79}}
                ]}
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse(code = 204))

        assertEquals(SyncOutcome.Success, sync.syncBook(bookUrl))

        val retried = requests().last()
        assertEquals("PUT", retried.method)
        // Snapped to the nearest place Komga admits to rather than giving
        // up: there is no page-based fallback to drop to for an EPUB.
        val locations = JSONObject(retried.body!!.utf8())
            .getJSONObject("locator")
            .getJSONObject("locations")
        assertEquals(0.75, locations.getDouble("progression"), 1e-9)
        assertFalse(requireNotNull(db.readingProgressDao().get(bookUrl)).isDirty)
    }

    // -- Disagreeing ------------------------------------------------------

    @Test
    fun `both sides moving is preserved rather than decided`() = runTest {
        connect()
        val progress = db.readingProgressDao()
        progress.recordLocal(
            bookUrl = bookUrl,
            locatorJson = locatorJson("OEBPS/ch2.xhtml", 0.5, 0.2),
            progression = 0.2,
            readingSpeed = null,
            status = "Reading",
            updatedAt = 1_000,
        )
        // Both sides have moved on from a point they once agreed on.
        progress.settleAgreed(bookUrl, 1L, 0.1, "Reading", account, now = 500)
        progress.recordLocal(
            bookUrl = bookUrl,
            locatorJson = locatorJson("OEBPS/ch4.xhtml", 0.5, 0.4),
            progression = 0.4,
            readingSpeed = null,
            status = "Reading",
            updatedAt = 2_000,
        )
        server.enqueue(json(bookJson(page = 90, completed = false, readDate = "2024-06-01T10:00:00Z")))
        server.enqueue(json(progressionJson(locatorJson("OEBPS/ch7.xhtml", 0.5, 0.6))))

        assertEquals(SyncOutcome.Success, sync.syncBook(bookUrl))

        val row = requireNotNull(progress.get(bookUrl))
        // Neither side is thrown away: this is a question for whoever is
        // holding the device, not for a background job.
        assertEquals(0.4, row.totalProgression!!, 1e-9)
        assertEquals(0.6, row.pendingProgression!!, 1e-9)
        assertEquals(0.6, sync.preservedConflict(bookUrl)!!.remote!!, 1e-9)
    }

    @Test
    fun `taking the server's side restores the locator paired before process death`() = runTest {
        connect()
        val progress = db.readingProgressDao()
        progress.recordLocal(
            bookUrl = bookUrl,
            locatorJson = locatorJson("OEBPS/ch2.xhtml", 0.5, 0.2),
            progression = 0.2,
            readingSpeed = null,
            status = "Reading",
            updatedAt = 1_000,
        )
        progress.persistPending(
            bookUrl,
            0.6,
            "Reading",
            4_000,
            account,
            now = 4_000,
            locatorJson = locatorJson("OEBPS/ch7.xhtml", 0.5, 0.6),
        )

        sync.takeRemotePosition(bookUrl, atRevision = requireNotNull(progress.get(bookUrl)).localRevision)

        val row = requireNotNull(progress.get(bookUrl))
        assertEquals(0.6, row.totalProgression!!, 1e-9)
        assertEquals("OEBPS/ch7.xhtml", JSONObject(row.locatorJson).getString("href"))
    }

    @Test
    fun `legacy unmarked locator is treated as approximate`() = runTest {
        connect()
        val progress = db.readingProgressDao()
        val local = locatorJson("OEBPS/ch2.xhtml", 0.5, 0.2)
        progress.recordLocal(bookUrl, local, 0.2, null, "Reading", 1_000)
        progress.persistPending(
            bookUrl = bookUrl,
            progression = 0.6,
            status = "Reading",
            remoteUpdatedAt = 4_000,
            account = account,
            now = 4_000,
            locatorJson = """{
                "href":"OEBPS/ch7.xhtml",
                "type":"application/xhtml+xml",
                "locations":{"totalProgression":0.6},
                "text":{"highlight":"the following position"}
            }""".trimIndent(),
        )

        sync.takeRemotePosition(bookUrl, requireNotNull(progress.get(bookUrl)).localRevision)

        val row = requireNotNull(progress.get(bookUrl))
        assertEquals(0.6, row.totalProgression ?: 0.0, 0.0)
        assertEquals("{}", row.locatorJson)
    }

    // -- Being economical --------------------------------------------------

    @Test
    fun `a server that has not moved is not asked where it is`() = runTest {
        connect()
        val progress = db.readingProgressDao()
        progress.recordLocal(
            bookUrl = bookUrl,
            locatorJson = locatorJson("OEBPS/ch3.xhtml", 0.5, 0.31),
            progression = 0.31,
            readingSpeed = null,
            status = "Reading",
            updatedAt = 1_000,
        )
        // Settled at this exact position, at this exact server timestamp.
        progress.applyPull(
            bookUrl = bookUrl,
            expectedRevision = requireNotNull(progress.get(bookUrl)).localRevision,
            progression = 0.31,
            status = "Reading",
            account = account,
            remoteUpdatedAt = 1_717_236_000_000,
            now = 2_000,
        )
        server.enqueue(json(bookJson(page = 40, completed = false, readDate = "2024-06-01T10:00:00Z")))
        val before = progress.get(bookUrl)

        assertEquals(SyncOutcome.Success, sync.syncBook(bookUrl))

        // The catalog already said the server has not moved since the two
        // sides last agreed, so its position is known and asking for it
        // again would be a request per book to be told the same thing.
        assertEquals(1, requests().size)
        // Nor is anything written back: settling what is already settled
        // has the whole library redraw itself for nothing.
        assertEquals(before, progress.get(bookUrl))
    }
}
