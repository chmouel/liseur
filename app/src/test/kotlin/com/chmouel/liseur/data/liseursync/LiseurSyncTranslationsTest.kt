package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.translate.PulledTranslation
import com.chmouel.liseur.translate.SavedTranslations
import com.chmouel.liseur.translate.TranslationAccountClear
import com.chmouel.liseur.translate.TranslationCacheDatabase
import java.net.InetAddress
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keeping the sentence translation cache in step (ADR-0052).
 *
 * What must not break: a pulled sentence must never be pushed back as
 * if this phone had translated it, and a watermark must never move
 * without the server's acknowledgement — the first sends another
 * device's words back at it, the second quietly loses every sentence
 * saved below a number nothing earned.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncTranslationsTest {

    private lateinit var server: MockWebServer
    private lateinit var db: LiseurDatabase
    private lateinit var cache: TranslationCacheDatabase
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
        cache = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TranslationCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        clock = NOW
    }

    @After
    fun close() {
        server.close()
        db.close()
        cache.close()
        CredentialCipher.keyForTesting = null
    }

    // -- Pull --------------------------------------------------------------

    @Test
    fun `sentences held by the account arrive and are never pushed back`() = runTest {
        connect()
        alias()
        val store = saved()
        server.enqueue(
            page(
                row(hashed("one"), "un"),
                row(hashed("two"), "deux"),
            ),
        )

        sync(store)

        assertEquals("un", store.forBook(BOOK).get("one").translation)
        assertEquals("deux", store.forBook(BOOK).get("two").translation)
        assertEquals(CURSOR, store.syncState(peer(), BOOK)!!.pullCursor)
        // Nothing was saved here, so nothing is owed back.
        assertEquals(0, pushes())
    }

    @Test
    fun `a sentence this phone already has keeps its own words`() = runTest {
        connect()
        alias()
        val store = saved()
        store.forBook(BOOK).keep("one", "mine")
        server.enqueue(page(row(hashed("one"), "theirs")))
        server.enqueue(ok())

        sync(store)

        assertEquals("mine", store.forBook(BOOK).get("one").translation)
        // The local save is still owed to the server, though.
        assertEquals(1, pushes())
        assertEquals(listOf(hashed("one")), pushedKeys(0))
    }

    @Test
    fun `the pull resumes from the stored cursor`() = runTest {
        connect()
        alias()
        val store = saved()
        // Seeded under the work name the alias carries, as a real run
        // would have left it.
        store.ensureWork(peer(), BOOK, WORK)
        store.landPulled(
            peer(), BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("one"), "un")),
            CURSOR,
        )
        server.enqueue(page(hasMore = false))

        sync(store)

        val asked = server.takeRequest()
        assertEquals(CURSOR, asked.url.queryParameter("after"))
        assertEquals("/v1/works/$WORK/translations", asked.url.encodedPath)
        assertEquals("500", asked.url.queryParameter("limit"))
    }

    @Test
    fun `a pull with more pages follows the cursor page to page`() = runTest {
        connect()
        alias()
        val store = saved()
        server.enqueue(page(row(hashed("one"), "un"), hasMore = true, next = CURSOR_A))
        server.enqueue(page(row(hashed("two"), "deux"), next = CURSOR_B))

        sync(store)

        assertEquals("un", store.forBook(BOOK).get("one").translation)
        assertEquals("deux", store.forBook(BOOK).get("two").translation)
        assertEquals(CURSOR_B, store.syncState(peer(), BOOK)!!.pullCursor)
        // The second page was asked for under the cursor the first one
        // handed out, not the one the run started from.
        server.takeRequest()
        assertEquals(CURSOR_A, server.takeRequest().url.queryParameter("after"))
    }

    @Test
    fun `a disconnect landing mid-pull leaves no peer bookkeeping behind`() = runTest {
        connect()
        alias()
        val store = saved()
        // The page is served as the account goes: the pass has already
        // passed its ownership check, so the landing and the cursor are
        // writes a peer that is gone must not keep.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { db.remoteServerDao().delete() }
                return page(row(hashed("one"), "un"))
            }
        }

        val outcome = sync(store)

        assertEquals(1, outcome.pulled)
        // The sentence landed is this phone's cache now, whoever connected.
        assertEquals("un", store.forBook(BOOK).get("one").translation)
        // The cursor the landing wrote, and the state the pass began
        // under, name a peer nobody answers to any more: gone, here,
        // not at the next run.
        assertNull(store.syncState(peer(), BOOK))
        assertEquals(0, pushes())
    }

    @Test
    fun `a pass from before a rekey cannot take the new key's progress for dead`() = runTest {
        connect()
        alias()
        val store = saved()
        server.enqueue(page())
        sync(store)
        assertEquals(CURSOR, store.syncState(peer(), BOOK)!!.pullCursor)

        // The account is renamed and its bookkeeping carried; the pass
        // that began under the old name runs on after it.
        val bob = peer().replace("|ada", "|bob")
        store.rekeyPeer(peer(), bob)
        connect(username = "bob")

        pass(store).sync(peerFor(), book = BOOK)

        // The new key's progress is intact, and the old key's rows —
        // the originals the rekey copied, and the fresh state the
        // stale pass wrote — are gone.
        assertEquals(CURSOR, store.syncState(bob, BOOK)!!.pullCursor)
        assertNull(store.syncState(peer(), BOOK))
    }

    @Test
    fun `a cursor the server refuses restarts the pull from the beginning`() = runTest {
        connect()
        alias()
        val store = saved()
        // Seeded under the work name the alias carries, as a real run
        // would have left it.
        store.ensureWork(peer(), BOOK, WORK)
        store.landPulled(
            peer(), BOOK, store.pullTicket(BOOK),
            listOf(PulledTranslation(hashed("one"), "un")),
            CURSOR,
        )
        server.enqueue(MockResponse(code = 400, body = """{"error":"after must be a translation cursor"}"""))
        server.enqueue(page(row(hashed("two"), "deux")))

        sync(store)

        // The first ask carried the stored cursor; the restart carried none.
        assertEquals(CURSOR, server.takeRequest().url.queryParameter("after"))
        assertNull(server.takeRequest().url.queryParameter("after"))
        assertEquals("deux", store.forBook(BOOK).get("two").translation)
    }

    @Test
    fun `a work the server has dropped asks for a new name`() = runTest {
        connect()
        alias()
        server.enqueue(MockResponse(code = 404, body = """{"error":"work not found"}"""))

        val outcome = sync(saved())

        assertEquals(mapOf(BOOK to WORK), outcome.reresolve)
        assertNull(db.workIdentityDao().alias(BOOK, peer()))
    }

    // -- Push --------------------------------------------------------------

    @Test
    fun `saved sentences are pushed in batches and not offered again`() = runTest {
        connect()
        alias()
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        store.forBook(BOOK).keep("two", "deux")
        server.enqueue(page())
        server.enqueue(ok())

        sync(store)

        assertEquals(1, pushes())
        assertEquals(setOf(hashed("one"), hashed("two")), pushedKeys(0).toSet())
        val watermark = store.syncState(peer(), BOOK)!!.pushedSavedAt
        assertTrue(watermark > 0)

        server.enqueue(page())
        sync(store)
        // The pull asked again; the push did not: the watermark says the
        // sentences were taken.
        assertEquals(1, pushes())
    }

    @Test
    fun `a watermark moves only on the server's acknowledgement`() = runTest {
        connect()
        alias()
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        // The pull works; the push is lost on the way back.
        server.enqueue(page())
        server.enqueue(MockResponse(code = 500, body = ""))

        val outcome = sync(store)

        assertTrue(outcome.failure != null)
        assertEquals(0, store.syncState(peer(), BOOK)!!.pushedSavedAt)

        server.enqueue(page())
        server.enqueue(ok())
        sync(store)

        // The lost attempt is simply repeated: an identical batch the
        // server writes nothing for.
        assertEquals(2, pushes())
        assertTrue(store.syncState(peer(), BOOK)!!.pushedSavedAt > 0)
    }

    @Test
    fun `a push naming a work the server has dropped asks for a new name`() = runTest {
        connect()
        alias()
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        server.enqueue(page())
        server.enqueue(
            MockResponse(
                code = 400,
                body = """{"error":"translation k: unknown work","code":"unknown_work","item_index":0,"key":"${hashed("one")}","work_id":"$WORK"}""",
            ),
        )

        val outcome = sync(store)

        assertEquals(mapOf(BOOK to WORK), outcome.reresolve)
        // Not acknowledged, not written off: the sentence is offered
        // again once the book has a name the server knows.
        assertEquals(0, store.syncState(peer(), BOOK)!!.pushedSavedAt)
    }

    @Test
    fun `a book re-resolved under a new name is offered its sentences again`() = runTest {
        connect()
        alias()
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        server.enqueue(page())
        server.enqueue(ok())
        sync(store)
        assertEquals(1, pushes())

        // The server disowns the name; the book resolves to another.
        server.enqueue(MockResponse(code = 404, body = """{"error":"work not found"}"""))
        sync(store)

        db.workIdentityDao().upsert(
            WorkAlias(
                bookUrl = BOOK, peerId = peer(), workId = "w-2",
                confidence = WorkAlias.HIGH, confirmed = true, seeded = true,
                sourceSent = true, resolvedAt = NOW,
            ),
        )
        server.enqueue(page())
        server.enqueue(ok())
        sync(store)

        // Pushed once under the old name, offered again under the new.
        assertEquals(2, pushes())
        assertTrue(store.syncState(peer(), BOOK)!!.pushedSavedAt > 0)
        assertEquals("w-2", store.syncState(peer(), BOOK)!!.workId)
    }

    @Test
    fun `a batch the server calls too large is split, not replayed`() = runTest {
        connect()
        alias()
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        store.forBook(BOOK).keep("two", "deux")
        server.enqueue(page())
        // The whole batch, then each half: a proxy's 413 names the
        // request's size, and the answer is a smaller request.
        server.enqueue(MockResponse(code = 413, body = ""))
        server.enqueue(ok())
        server.enqueue(ok())

        val outcome = sync(store)

        assertEquals(null, outcome.failure)
        assertEquals(3, pushes())
        assertTrue(store.syncState(peer(), BOOK)!!.pushedSavedAt > 0)
    }

    @Test
    fun `a single item refused for its shape is written off as sent`() = runTest {
        connect()
        alias()
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        server.enqueue(page())
        server.enqueue(
            MockResponse(
                code = 400,
                body = """{"error":"key must be 64 hex characters","code":"bad_key","item_index":0,"key":"${hashed("one")}"}""",
            ),
        )

        val outcome = sync(store)

        // Nothing here can mend a badly shaped item, so it is set aside
        // rather than offered again every run: not a failure, and the
        // watermark moves past it.
        assertEquals(null, outcome.failure)
        assertTrue(store.syncState(peer(), BOOK)!!.pushedSavedAt > 0)
        assertEquals(0, outcome.pushed)
    }

    @Test
    fun `an unexplained refusal of a single translation is a failure, not a write-off`() = runTest {
        connect()
        alias()
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        server.enqueue(page())
        server.enqueue(
            MockResponse(code = 400, body = """{"error":"no idea what this is","code":"mystery"}"""),
        )

        val outcome = sync(store)

        // Nothing was taken, so nothing is written off as sent: the
        // watermark stays and the run says it failed.
        assertTrue(outcome.failure != null)
        assertEquals(0, store.syncState(peer(), BOOK)!!.pushedSavedAt)
    }

    // -- Older servers -----------------------------------------------------

    @Test
    fun `a plain-text 404 leaves the server alone for the session`() = runTest {
        connect()
        alias()
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        server.enqueue(MockResponse(code = 404, body = "404 page not found\n"))

        val outcome = sync(store)

        // Not a failure: an old server, whose positions and annotations
        // are untouched by this pass asking about translations.
        assertEquals(null, outcome.failure)
        assertEquals(0, outcome.pulled + outcome.pushed)

        sync(store)
        // One ask, once: the second run never reached the network.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a clear queued after a server is known unsupported is settled`() = runTest {
        connect()
        alias()
        val store = saved()
        server.enqueue(MockResponse(code = 404, body = "404 page not found\n"))

        sync(store)
        store.requestAccountClears(listOf(TranslationAccountClear.everyWork(peer())))
        server.enqueue(MockResponse(code = 404, body = "404 page not found\n"))

        val outcome = sync(store)

        assertEquals(null, outcome.failure)
        assertEquals(2, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
        val clear = server.takeRequest()
        assertEquals("DELETE", clear.method)
        assertEquals("/v1/translations", clear.url.encodedPath)
        assertTrue(store.owedClears(peer()).isEmpty())
    }

    // -- Clears (ADR-0050) -------------------------------------------------

    @Test
    fun `an owed clear is sent before the pull and settled`() = runTest {
        connect()
        alias()
        val store = saved()
        store.clearBook(BOOK, listOf(TranslationAccountClear(peer(), WORK)))
        server.enqueue(json("""{"deleted":3}"""))
        server.enqueue(page())

        val outcome = sync(store)

        assertEquals(null, outcome.failure)
        val cleared = server.takeRequest()
        assertEquals("DELETE", cleared.method)
        assertEquals("/v1/works/$WORK/translations", cleared.url.encodedPath)
        assertEquals("GET", server.takeRequest().method)
        assertTrue(store.owedClears(peer()).isEmpty())
    }

    @Test
    fun `the account-wide clear goes before a work's`() = runTest {
        connect()
        alias()
        val store = saved()
        store.requestAccountClears(
            listOf(TranslationAccountClear(peer(), WORK), TranslationAccountClear.everyWork(peer())),
        )
        server.enqueue(json("""{"deleted":5}"""))
        server.enqueue(json("""{"deleted":0}"""))
        server.enqueue(page())

        sync(store)

        assertEquals("/v1/translations", server.takeRequest().url.encodedPath)
        assertEquals("/v1/works/$WORK/translations", server.takeRequest().url.encodedPath)
        assertTrue(store.owedClears(peer()).isEmpty())
    }

    @Test
    fun `a clear the server cannot answer is dropped and the feed still syncs`() = runTest {
        connect()
        alias()
        val store = saved()
        store.requestAccountClears(
            listOf(
                TranslationAccountClear.everyWork(peer()),
                TranslationAccountClear(peer(), WORK),
                TranslationAccountClear(peer(), "w-gone"),
            ),
        )
        // A server from before clears, as Go's mux answers it: no route
        // at all for the account's clear (a plain-text 404), no DELETE on
        // a path it serves (405); and a work it dropped.
        server.enqueue(MockResponse(code = 404, body = "404 page not found\n"))
        server.enqueue(MockResponse(code = 405, body = "Method Not Allowed\n"))
        server.enqueue(MockResponse(code = 404, body = """{"error":"work not found"}"""))
        server.enqueue(page(row(hashed("one"), "un")))

        val outcome = sync(store)

        assertEquals(null, outcome.failure)
        assertTrue(store.owedClears(peer()).isEmpty())
        assertEquals("un", store.forBook(BOOK).get("one").translation)
    }

    @Test
    fun `a plain-text 404 on a clear does not stop the feed for the session`() = runTest {
        connect()
        alias()
        val store = saved()
        store.requestAccountClears(listOf(TranslationAccountClear(peer(), WORK)))
        server.enqueue(MockResponse(code = 404, body = "404 page not found\n"))
        server.enqueue(page(row(hashed("one"), "un")))

        sync(store)

        assertTrue(store.owedClears(peer()).isEmpty())
        assertEquals("un", store.forBook(BOOK).get("one").translation)
    }

    @Test
    fun `a clear that could not be sent stays owed and its work is left alone`() = runTest {
        connect()
        alias()
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        store.requestAccountClears(listOf(TranslationAccountClear(peer(), WORK)))
        server.enqueue(MockResponse(code = 500, body = ""))

        val outcome = sync(store)

        assertTrue(outcome.failure != null)
        assertEquals(listOf(peer() to WORK), store.owedClears(peer()).map { it.peerId to it.workId })
        // No pull to bring the old rows back, no push for them to clear.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 404 that does not say the work is gone leaves the clear owed`() = runTest {
        connect()
        alias()
        val store = saved()
        store.requestAccountClears(listOf(TranslationAccountClear(peer(), WORK)))
        server.enqueue(MockResponse(code = 404, body = """{"error":"user not found"}"""))

        val outcome = sync(store)

        assertTrue(outcome.failure != null)
        assertTrue(store.clearOwed(peer(), WORK))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a non-json 404 keeps the account clear owed`() = runTest {
        connect()
        alias()
        val store = saved()
        store.requestAccountClears(listOf(TranslationAccountClear(peer(), WORK)))
        server.enqueue(MockResponse(code = 404, body = "<html>not found</html>"))

        val outcome = sync(store)

        assertTrue(outcome.failure != null)
        assertTrue(store.clearOwed(peer(), WORK))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a book's account clear does not push its old sentences back`() = runTest {
        connect()
        alias()
        val store = saved()
        store.forBook(BOOK).keep("one", "un")
        store.requestAccountClears(listOf(TranslationAccountClear(peer(), WORK)), BOOK)
        server.enqueue(json("""{"deleted":1}"""))
        server.enqueue(page())

        val outcome = sync(store)

        assertEquals(null, outcome.failure)
        assertEquals("DELETE", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(2, server.requestCount)
        assertEquals("un", store.forBook(BOOK).get("one").translation)
    }

    // -- Helpers -----------------------------------------------------------

    /** Saves a sentence as the reader does: asked for, then kept under the stamp of the asking. */
    private suspend fun com.chmouel.liseur.translate.PageTranslationCache.keep(key: String, value: String) =
        put(key, value, get(key).stamp)

    private fun saved() = SavedTranslations(
        database = cache,
        present = { urls -> urls.filter { it == BOOK } },
        now = { clock },
    )

    // One pass for the whole test, as the container holds one for the
    // whole app: what it learns about a server outlives a single run.
    private var pass: LiseurSyncTranslations? = null

    private suspend fun sync(store: SavedTranslations): LiseurSyncTranslations.Outcome =
        pass(store).sync(peerFor(), book = BOOK)

    private fun peerFor() = LiseurSyncTranslations.Peer(
        baseUrl = "http://127.0.0.1:${server.port}",
        credentials = RemoteCredentials.Bearer("device-secret"),
        peerId = peer(),
        accountKey = peer(),
    )

    private fun pass(store: SavedTranslations): LiseurSyncTranslations =
        pass ?: LiseurSyncTranslations(
            serverDao = db.remoteServerDao(),
            identityDao = db.workIdentityDao(),
            saved = { store },
            now = { clock },
        ).also { pass = it }

    private suspend fun connect(username: String = "ada") = db.remoteServerDao().upsert(
        RemoteServer(
            kind = ServerKind.LISEUR_SYNC,
            baseUrl = "http://127.0.0.1:${server.port}",
            username = username,
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
    ) = db.workIdentityDao().upsert(
        WorkAlias(
            bookUrl = bookUrl,
            peerId = peer(),
            workId = workId,
            confidence = WorkAlias.HIGH,
            confirmed = true,
            seeded = true,
            sourceSent = true,
            resolvedAt = NOW,
        ),
    )

    private fun page(vararg rows: String, hasMore: Boolean = false, next: String = CURSOR) = json(
        """{"translations":[${rows.joinToString(",")}],"next_cursor":"$next","has_more":$hasMore}""",
    )

    private fun ok() = json("""{"status":"applied"}""")

    private fun row(key: String, translation: String): String =
        JSONObject().put("key", key).put("translation", translation).toString()

    private fun json(body: String) = MockResponse(code = 200, body = body)

    private fun hashed(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // What has been taken off the server so far, so a test can ask about
    // a push it already counted without taking the same request twice.
    private var taken = 0
    private val pushed = mutableListOf<JSONObject>()

    private fun drain() {
        while (taken < server.requestCount) {
            val request = server.takeRequest()!!
            taken++
            if (request.method == "POST") pushed += JSONObject(request.body!!.utf8())
        }
    }

    private fun pushes(): Int {
        drain()
        return pushed.size
    }

    private fun pushedKeys(which: Int): List<String> {
        drain()
        return pushed[which].getJSONArray("translations").let { items ->
            (0 until items.length()).map { items.getJSONObject(it).getString("key") }
        }
    }

    private fun peer() = "liseursync|http://127.0.0.1:${server.port}|ada"

    private companion object {
        const val NOW = 1_000L
        const val BOOK = "content://sd/a-book.epub"
        const val WORK = "w-1"
        const val CURSOR = "2026-10-12T10:00:00.000000000Z aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val CURSOR_A = "2026-10-12T10:00:00.000000001Z bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val CURSOR_B = "2026-10-12T10:00:00.000000002Z cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
