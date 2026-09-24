package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.BookOrbitBinding
import com.chmouel.liseur.data.db.BookOrbitBindingDao
import com.chmouel.liseur.data.db.BookOrbitBindingState
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteHttp
import com.chmouel.liseur.data.remote.ServerKind
import java.net.InetAddress
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
 * Walking a BookOrbit shelf.
 *
 * The two things worth pinning here are the ones that would otherwise be
 * discovered by a reader losing their place: a book with nothing this
 * app can open is not put on the shelf, and a refresh does not move a
 * book that has already been read to a different file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BookOrbitCatalogClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.close()
        CredentialCipher.keyForTesting = null
    }

    private fun address() = "http://127.0.0.1:${server.port}"

    private fun account() = RemoteServer(
        kind = ServerKind.BOOKORBIT,
        baseUrl = address(),
        username = "reader",
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = "1",
        userId = null,
        koboTokenCipher = null,
        canDownload = true,
        addedAt = 1L,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
        orbitAccessCipher = RemoteServer.seal("access"),
        orbitRefreshCipher = RemoteServer.seal("refresh"),
        orbitAccessExpires = 9_999_999_999L,
        orbitEpoch = 7,
    )

    private fun client(
        serverDao: RemoteServerDao,
        bindings: BookOrbitBindingDao,
        inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    ): BookOrbitCatalogClient {
        val session = BookOrbitSession(serverDao, RemoteHttp(), now = { 1_000L })
        return BookOrbitCatalogClient(
            bindings,
            serverDao,
            BookOrbitHttp(session),
            inTransaction,
        )
    }

    private fun page(vararg cards: String) =
        MockResponse(body = """{"items":[${cards.joinToString(",")}],"total":${cards.size},"page":0,"size":200}""")

    private fun epubCard(fileId: Long, role: String = "primary") =
        """
        {"id":113,"title":"La Horde","authors":["Damasio,Alain"],
         "files":[{"id":351,"format":"jpg","role":"cover","sizeBytes":52679},
                  {"id":$fileId,"format":"epub","role":"$role","sizeBytes":1925282}]}
        """.trimIndent()

    @Test
    fun `an API call does not follow a redirect with the bearer or body`() = runBlocking {
        val dao = FakeServerDao(account())
        val http = BookOrbitHttp(BookOrbitSession(dao, RemoteHttp(), now = { 1_000L }))
        val context = BookOrbitRequestContext.from(account())!!
        repeat(2) {
            server.enqueue(MockResponse(code = 307, headers = okhttp3.Headers.headersOf("Location", "/elsewhere")))
        }

        val get = runCatching { http.getObject(context, "${address()}/api/v1/books/1") }
        val post = runCatching {
            http.postObject(context, "${address()}/api/v1/books/query", org.json.JSONObject().put("q", "x"))
        }

        assertTrue(get.exceptionOrNull() is com.chmouel.liseur.data.remote.RemoteHttpFailure)
        assertTrue(post.exceptionOrNull() is com.chmouel.liseur.data.remote.RemoteHttpFailure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a book with no epub is not put on the shelf`() = runBlocking {
        val dao = FakeServerDao(account())
        server.enqueue(
            page(
                """{"id":1,"title":"An audiobook","authors":[],"files":[{"id":9,"format":"m4b","role":"primary"}]}""",
                epubCard(352),
            ),
        )

        val walk = client(dao, FakeBindingDao()).allBooks(address(), RemoteCredentials.Deferred)

        assertEquals(listOf("La Horde"), walk.snapshot.let { it as BookOrbitCatalogSnapshot }.books.map { it.title })
        assertTrue(walk.complete)
    }

    @Test
    fun `the chosen file is the epub, never the cover`() = runBlocking {
        val dao = FakeServerDao(account())
        server.enqueue(page(epubCard(352)))

        val walk = client(dao, FakeBindingDao()).allBooks(address(), RemoteCredentials.Deferred)
        val book = (walk.snapshot as BookOrbitCatalogSnapshot).books.single()

        assertEquals("/api/v1/books/files/352/download", book.downloadHref)
        assertEquals(
            "/api/v1/books/113/thumbnail#liseur-bookorbit=${BookOrbitScope.remoteId(address(), "1", 113)}",
            book.coverHref,
        )
    }

    @Test
    fun `the choice is written down the first time`() = runBlocking {
        val dao = FakeServerDao(account())
        val bindings = FakeBindingDao()
        server.enqueue(page(epubCard(352)))

        client(dao, bindings).allBooks(address(), RemoteCredentials.Deferred)

        val stored = bindings.rows.values.single()
        assertEquals(352L, stored.fileId)
        assertEquals(dao.row!!.accountKey, stored.accountKey)
        assertEquals("bookorbit:${BookOrbitScope.remoteId(address(), "1", 113)}", stored.bookUrl)
    }

    /**
     * The one that matters: the server makes a different EPUB primary at
     * its next scan. The book keeps the file its reading is in.
     */
    @Test
    fun `a refresh marks a missing binding instead of moving to a different file`() = runBlocking {
        val dao = FakeServerDao(account())
        val bindings = FakeBindingDao()
        server.enqueue(page(epubCard(352)))
        client(dao, bindings).allBooks(address(), RemoteCredentials.Deferred)

        // The server now calls a different file the primary one.
        server.enqueue(page(epubCard(999)))
        val second = client(dao, bindings).allBooks(address(), RemoteCredentials.Deferred)

        val book = (second.snapshot as BookOrbitCatalogSnapshot).books.single()
        assertNull(book.downloadHref)
        assertEquals(
            com.chmouel.liseur.data.db.BookOrbitBindingState.MISSING,
            bindings.rows.values.single().stateValue,
        )
    }

    @Test
    fun `a bound file the server no longer offers is not replaced`() = runBlocking {
        val dao = FakeServerDao(account())
        val bindings = FakeBindingDao()
        server.enqueue(page(epubCard(352)))
        client(dao, bindings).allBooks(address(), RemoteCredentials.Deferred)
        bindings.rows.values.first().let {
            bindings.rows[it.accountKey to it.bookUrl] = it.copy(
                state = com.chmouel.liseur.data.db.BookOrbitBindingState.MISSING.name,
            )
        }

        server.enqueue(page(epubCard(999)))
        val second = client(dao, bindings).allBooks(address(), RemoteCredentials.Deferred)

        val book = (second.snapshot as BookOrbitCatalogSnapshot).books.single()
        assertNull(book.downloadHref)
    }

    @Test
    fun `the query asks for the api root and the shelf page`() = runBlocking {
        val dao = FakeServerDao(account())
        server.enqueue(MockResponse(body = """{"items":[],"total":0,"page":0,"size":200}"""))

        client(dao, FakeBindingDao()).allBooks(address(), RemoteCredentials.Deferred)

        val request = server.takeRequest()
        assertEquals("/api/v1/books/query", request.url.encodedPath)
        assertEquals("Bearer access", request.headers["Authorization"])
        val body = request.body!!.utf8()
        assertTrue(body, body.contains("\"epub\""))
    }

    @Test
    fun `a server that rejects the filter stays unfiltered on later pages`() = runBlocking {
        val dao = FakeServerDao(account())
        server.enqueue(MockResponse(code = 400, body = "{}"))
        server.enqueue(
            MockResponse(
                body = """{"items":[${epubCard(352)}],"total":2,"page":0,"size":1}""",
            ),
        )
        server.enqueue(
            MockResponse(
                body = """
                {"items":[{"id":114,"title":"Second","authors":[],
                  "files":[{"id":353,"format":"epub","role":"primary"}]}],
                 "total":2,"page":1,"size":1}
                """.trimIndent(),
            ),
        )

        val walk = client(dao, FakeBindingDao()).allBooks(address(), RemoteCredentials.Deferred)

        assertTrue(walk.complete)
        assertEquals(2, (walk.snapshot as BookOrbitCatalogSnapshot).books.size)
        val filtered = server.takeRequest().body!!.utf8()
        val firstFallback = server.takeRequest().body!!.utf8()
        val secondFallback = server.takeRequest().body!!.utf8()
        assertTrue(filtered.contains("\"filter\""))
        assertEquals(false, firstFallback.contains("\"filter\""))
        assertEquals(false, secondFallback.contains("\"filter\""))
    }

    @Test
    fun `looking at a search result does not choose its edition`() = runBlocking {
        val dao = FakeServerDao(account())
        val bindings = FakeBindingDao()
        server.enqueue(page(epubCard(352)))

        val results = client(dao, bindings).search(address(), RemoteCredentials.Deferred, "horde")

        assertEquals(1, results.size)
        assertTrue(bindings.rows.isEmpty())
    }

    @Test
    fun `an old catalog page cannot recreate a binding after an account switch`() = runBlocking {
        val dao = FakeServerDao(account())
        val bindings = FakeBindingDao()
        server.enqueue(page(epubCard(352)))
        val old = dao.row!!
        val catalog = client(dao, bindings) { work ->
            // The page was fetched as the old account. Switch before its
            // binding can land, inside the same boundary production uses
            // for its account check and write.
            dao.row = old.copy(accountId = "2", username = "other", orbitEpoch = 8)
            work()
        }

        runCatching { catalog.allBooks(address(), RemoteCredentials.Deferred) }

        assertTrue(bindings.rows.isEmpty())
    }

    private class FakeServerDao(var row: RemoteServer?) : RemoteServerDao {
        override fun observe(id: Long): Flow<RemoteServer?> = flowOf(row)
        override suspend fun get(id: Long): RemoteServer? = row
        override suspend fun upsert(server: RemoteServer) { row = server }
        override suspend fun setKoboTokenCipher(cipher: String?, id: Long) = Unit
        override suspend fun setCatalogSyncedAt(at: Long, id: Long) = Unit
        override suspend fun setPositionSyncedAt(at: Long, id: Long) = Unit
        override suspend fun setSyncToken(token: String?, id: Long) = Unit
        override suspend fun setCanDownload(allowed: Boolean, id: Long) = Unit
        override suspend fun setCanUpload(allowed: Boolean, id: Long) = Unit
        override suspend fun setCanReadInsights(allowed: Boolean, tokenCipher: String?, id: Long): Int = 0
        override suspend fun setSyncCursor(seq: Long, id: Long) = Unit
        override suspend fun setAnnotationCursor(seq: Long, id: Long) = Unit
        override suspend fun setOrbitEpoch(epoch: Long, id: Long) = Unit
        override suspend fun rotateOrbitTokens(
            access: String?,
            refresh: String?,
            expires: Long,
            session: Int?,
            epoch: Long,
            expected: String?,
            id: Long,
        ): Int = 0

        override suspend fun delete(id: Long) { row = null }
    }

    private class FakeBindingDao : BookOrbitBindingDao {
        val rows = mutableMapOf<Pair<String, String>, BookOrbitBinding>()

        override suspend fun get(accountKey: String, bookUrl: String): BookOrbitBinding? =
            rows[accountKey to bookUrl]

        override suspend fun forAccount(accountKey: String): List<BookOrbitBinding> =
            rows.values.filter { it.accountKey == accountKey }

        override suspend fun positionPage(
            accountKey: String,
            afterUrl: String?,
            limit: Int,
        ): List<BookOrbitBinding> = forAccount(accountKey).filter {
            (afterUrl == null || it.bookUrl > afterUrl) && it.fileId != null &&
                it.fileFormat.equals("epub", ignoreCase = true) &&
                it.stateValue in setOf(BookOrbitBindingState.SELECTED, BookOrbitBindingState.DOWNLOADED)
        }.sortedBy { it.bookUrl }.take(limit)

        override suspend fun bookUrls(accountKey: String): List<String> =
            rows.values.filter { it.accountKey == accountKey }.map { it.bookUrl }

        override suspend fun delete(accountKey: String, bookUrl: String) {
            rows.remove(accountKey to bookUrl)
        }

        override suspend fun clearAccount(accountKey: String) {
            rows.entries.removeIf { it.key.first == accountKey }
        }

        override suspend fun clearBook(bookUrl: String) {
            rows.entries.removeIf { it.key.second == bookUrl }
        }

        override suspend fun clearOrphans() = Unit

        override suspend fun write(binding: BookOrbitBinding) {
            rows[binding.accountKey to binding.bookUrl] = binding
        }

        override suspend fun insertMissing(binding: BookOrbitBinding): Long {
            val key = binding.accountKey to binding.bookUrl
            if (rows.containsKey(key)) return -1
            rows[key] = binding
            return 1
        }
    }
}
