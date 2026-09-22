package com.chmouel.liseur.data.bookorbit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.BookOrbitBinding
import com.chmouel.liseur.data.db.BookOrbitBindingState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.ServerKind
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BookOrbitCfiRepositoryTest {
    private lateinit var db: LiseurDatabase
    private lateinit var server: MockWebServer
    private lateinit var account: RemoteServer
    private lateinit var binding: BookOrbitBinding
    private lateinit var repository: BookOrbitCfiRepository

    private fun open(): LiseurDatabase = Room.databaseBuilder(
        ApplicationProvider.getApplicationContext(), LiseurDatabase::class.java, "orbit-cfi-test",
    ).build()

    @Before
    fun setup() = runBlocking {
        CredentialCipher.keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        ApplicationProvider.getApplicationContext<android.app.Application>().deleteDatabase("orbit-cfi-test")
        db = open()
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        account = RemoteServer(
            kind = ServerKind.BOOKORBIT, baseUrl = "http://127.0.0.1:${server.port}",
            username = "reader", passwordCipher = null, apiKeyCipher = null, accountId = "1",
            userId = null, koboTokenCipher = null, canDownload = true, addedAt = 1,
            catalogSyncedAt = null, positionSyncedAt = null, syncToken = null,
            orbitAccessCipher = RemoteServer.seal("access"), orbitRefreshCipher = RemoteServer.seal("refresh"),
            orbitAccessExpires = Long.MAX_VALUE, orbitEpoch = 7,
        )
        binding = BookOrbitBinding(
            accountKey = account.accountKey, bookUrl = "bookorbit:test", bookId = 12, fileId = 34,
            fileFormat = "epub", fileSize = null, fileName = null, revision = 2,
            state = BookOrbitBindingState.DOWNLOADED.name, updatedAt = 1,
        )
        db.remoteServerDao().upsert(account)
        db.bookOrbitBindingDao().write(binding)
        repository = BookOrbitCfiRepository(db)
    }

    @After
    fun cleanup() {
        db.close()
        server.close()
        CredentialCipher.keyForTesting = null
        ApplicationProvider.getApplicationContext<android.app.Application>().deleteDatabase("orbit-cfi-test")
    }

    @Test
    fun `foreign cfi including unsupported syntax survives database reopen unchanged`() = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val raw = "epubcfi(/6/4~3)"
        repository.retain(context, raw)
        db.close()
        db = open()
        repository = BookOrbitCfiRepository(db)

        val retained = repository.load(context)!!
        assertEquals(raw, retained.raw)
        assertNull(retained.parsed)
        assertNotNull(retained.unresolvedReason)
        assertEquals(binding.fileId, retained.fileId)
    }

    @Test
    fun `old contexts cannot write after file revision or connection changes`() = runBlocking {
        val context = repository.capture(binding.bookUrl)
        for (changed in listOf(
            binding.copy(fileId = 99), binding.copy(revision = 3), binding.copy(bookId = 99),
            binding.copy(state = BookOrbitBindingState.MISSING.name),
        )) {
            db.bookOrbitBindingDao().write(changed)
            assertThrows(BookOrbitIdentityChanged::class.java) {
                runBlocking { repository.retain(context, "epubcfi(/6/4)") }
            }
        }
        db.bookOrbitBindingDao().write(binding)
        for (changed in listOf(
            account.copy(accountId = "other"), account.copy(orbitEpoch = 8),
            account.copy(baseUrl = "https://other.invalid"),
        )) {
            db.remoteServerDao().upsert(changed)
            assertThrows(BookOrbitIdentityChanged::class.java) {
                runBlocking { repository.retain(context, "epubcfi(/6/4)") }
            }
        }
        assertNull(db.bookOrbitCfiDao().get(account.accountKey, binding.bookUrl))
    }

    @Test
    fun `an old stored edition stays retained but cannot be loaded as the replacement`() = runBlocking {
        val context = repository.capture(binding.bookUrl)
        repository.retain(context, "epubcfi(/6/4)")
        db.bookOrbitBindingDao().write(binding.copy(fileId = 99, revision = 3))
        val replacement = repository.capture(binding.bookUrl)
        assertThrows(BookOrbitIdentityChanged::class.java) {
            runBlocking { repository.load(replacement) }
        }
        assertEquals(34L, db.bookOrbitCfiDao().get(account.accountKey, binding.bookUrl)!!.fileId)
    }

    @Test
    fun `binding deletion cascades retained source data`() = runBlocking {
        repository.retain(repository.capture(binding.bookUrl), "epubcfi(/6/4)")
        db.bookOrbitBindingDao().clearBook(binding.bookUrl)
        assertNull(db.bookOrbitCfiDao().get(account.accountKey, binding.bookUrl))
    }

    @Test
    fun `info request uses captured file and accepts only well formed shape`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val client = BookOrbitEpubInfoClient(BookOrbitHttp(BookOrbitSession(db.remoteServerDao())), repository)
        server.enqueue(MockResponse(body = INFO))
        val result = client.read(context)
        assertEquals("OPS/package.opf", result.packagePath)
        val request = server.takeRequest()
        assertEquals("/api/v1/epub/12/info?fileId=34", request.url.encodedPath + "?" + request.url.encodedQuery)
        server.enqueue(MockResponse(body = """{"containerPath":"OPS/package.opf","spine":[]}"""))
        assertThrows(RemoteHttpFailure::class.java) { runBlocking { client.read(context) } }
    }

    @Test
    fun `info response cannot escape a connection switch during request`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                runBlocking { db.remoteServerDao().upsert(account.copy(orbitEpoch = 8)) }
                return MockResponse(body = INFO)
            }
        }
        val client = BookOrbitEpubInfoClient(BookOrbitHttp(BookOrbitSession(db.remoteServerDao())), repository)
        assertThrows(BookOrbitIdentityChanged::class.java) { runBlocking { client.read(context) } }
    }

    @Test
    fun `progress reads the selected file and separates unopened from saved zero`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val client = BookOrbitProgressClient(BookOrbitHttp(BookOrbitSession(db.remoteServerDao())), repository)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val unopened = client.read(context)
        assertFalse(unopened.isSaved)
        assertNull(unopened.displayTime)
        assertEquals("/api/v1/books/files/34/progress", server.takeRequest().url.encodedPath)

        server.enqueue(MockResponse(body = fixture("progress-saved.json")))
        val saved = client.read(context)
        assertTrue(saved.isSaved)
        assertEquals(0.0, saved.percentage, 0.0)
        assertEquals("epubcfi(/6/4!/4/2:3)", saved.cfi)
        assertEquals("2026-09-22T10:01:00.000Z", saved.displayTime)
    }

    @Test
    fun `malformed progress never becomes unopened`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val client = BookOrbitProgressClient(BookOrbitHttp(BookOrbitSession(db.remoteServerDao())), repository)
        for (body in listOf(
            """{}""", """{"percentage":0}""", """{"cfi":null,"percentage":-1}""",
            """{"cfi":null,"percentage":101}""", """{"cfi":null,"percentage":"0"}""",
            """{"cfi":null,"percentage":0,"updatedAt":42}""", "<html>error</html>",
        )) {
            server.enqueue(MockResponse(body = body))
            assertThrows(RemoteHttpFailure::class.java) { runBlocking { client.read(context) } }
        }
        server.enqueue(MockResponse(body = fixture("progress-unopened.json").replace(
            "\"pageNumber\":null", "\"pageNumber\":3",
        )))
        assertThrows(RemoteHttpFailure::class.java) { runBlocking { client.read(context) } }
        server.enqueue(MockResponse(body = fixture("progress-saved.json").replace(
            "\"bookFileId\":34", "\"bookFileId\":35",
        )))
        assertThrows(RemoteHttpFailure::class.java) { runBlocking { client.read(context) } }
    }

    @Test
    fun `progress response cannot escape a connection switch`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                runBlocking { db.bookOrbitBindingDao().write(binding.copy(fileId = 35)) }
                return MockResponse(body = fixture("progress-saved.json"))
            }
        }
        val client = BookOrbitProgressClient(BookOrbitHttp(BookOrbitSession(db.remoteServerDao())), repository)
        assertThrows(BookOrbitIdentityChanged::class.java) { runBlocking { client.read(context) } }
    }

    @Test
    fun `progress mutation has one delivery and always needs read-back unless rejected`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val transport = BookOrbitProgressMutationTransport(
            BookOrbitHttp(BookOrbitSession(db.remoteServerDao())), repository,
        )
        val payload = JSONObject("""{"percentage":25,"cfi":"epubcfi(/6/4)"}""")
        server.enqueue(MockResponse(code = 201))
        assertEquals(BookOrbitHttp.MutationResult.ReadBackRequired, transport.send(context, payload))
        assertEquals("/api/v1/books/files/34/progress", server.takeRequest().url.encodedPath)
        server.enqueue(MockResponse(code = 500))
        assertEquals(BookOrbitHttp.MutationResult.Uncertain, transport.send(context, payload))
        server.takeRequest()
        server.enqueue(MockResponse(code = 307, headers = okhttp3.Headers.headersOf(
            "Location", "/api/v1/books/files/99/progress",
        )))
        assertEquals(BookOrbitHttp.MutationResult.Uncertain, transport.send(context, payload))
        server.takeRequest()
        server.enqueue(MockResponse(code = 401))
        assertEquals(BookOrbitHttp.MutationResult.Rejected(401), transport.send(context, payload))
        server.takeRequest()
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `rejected progress bearer is refreshed before a later attempt`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val transport = BookOrbitProgressMutationTransport(
            BookOrbitHttp(BookOrbitSession(db.remoteServerDao())), repository,
        )
        val payload = JSONObject("""{"percentage":25,"cfi":"epubcfi(/6/4)"}""")
        server.enqueue(MockResponse(code = 401))
        assertEquals(BookOrbitHttp.MutationResult.Rejected(401), transport.send(context, payload))
        assertEquals("POST", server.takeRequest().method)
        server.enqueue(MockResponse(body = """
            {"accessToken":"fresh","accessTokenExpiresAt":"2099-01-01T00:00:00.000Z",
             "refreshToken":"rotated","refreshTokenExpiresAt":"2099-01-08T00:00:00.000Z",
             "sessionId":5}
        """.trimIndent()))
        server.enqueue(MockResponse(code = 201))
        assertEquals(BookOrbitHttp.MutationResult.ReadBackRequired, transport.send(context, payload))
        assertEquals("/api/v1/auth/refresh", server.takeRequest().url.encodedPath)
        val second = server.takeRequest()
        assertEquals("/api/v1/books/files/34/progress", second.url.encodedPath)
        assertEquals("fresh", db.remoteServerDao().get()?.orbitAccessCipher?.let(CredentialCipher::decrypt))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `lost mutation response is uncertain and does not replay the POST`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val transport = BookOrbitProgressMutationTransport(
            BookOrbitHttp(BookOrbitSession(db.remoteServerDao())), repository,
        )
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        assertEquals(
            BookOrbitHttp.MutationResult.Uncertain,
            transport.send(context, JSONObject("""{"percentage":25,"cfi":"epubcfi(/6/4)"}""")),
        )
        assertEquals("POST", server.takeRequest().method)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `mutation rejects a changed selected file before delivery`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        db.bookOrbitBindingDao().write(binding.copy(fileId = 35))
        val transport = BookOrbitProgressMutationTransport(
            BookOrbitHttp(BookOrbitSession(db.remoteServerDao())), repository,
        )
        assertThrows(BookOrbitIdentityChanged::class.java) {
            runBlocking { transport.send(context, JSONObject("""{"percentage":25}""")) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `source shaped book progress and eight status values remain distinct`() {
        val rows = JSONArray(fixture("progress-book.json"))
        assertEquals(2, rows.length())
        assertEquals(34L, rows.getJSONObject(0).getLong("fileId"))
        assertEquals(35L, rows.getJSONObject(1).getLong("fileId"))
        assertTrue(rows.getJSONObject(1).isNull("updatedAt"))
        val statuses = listOf(
            "unread", "want_to_read", "reading", "on_hold", "rereading", "read",
            "skimmed", "abandoned", "future_status",
        )
        for (status in statuses) {
            assertEquals(status, BookOrbitBooks.parseStatus(JSONObject("""{"status":"$status"}"""))?.status)
        }
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/bookorbit/$name")).readText()

    companion object {
        const val INFO = """{"containerPath":"OPS/package.opf","rootPath":"OPS/",
          "manifest":[{"id":"one","href":"OPS/one.xhtml","mediaType":"application/xhtml+xml","size":123}],
          "spine":[{"idref":"one","href":"OPS/one.xhtml","mediaType":"application/xhtml+xml","linear":true}],
          "optionalFiles":[],"toc":null,"metadata":{},"coverPath":null}"""
    }
}
