package com.chmouel.liseur.data.bookorbit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookOrbitBinding
import com.chmouel.liseur.data.db.BookOrbitBindingState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteUploadTarget
import com.chmouel.liseur.data.remote.ServerDeleteResult
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.ServerUploadResult
import java.io.File
import java.net.InetAddress
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sending a book to BookOrbit, and deleting one from it.
 *
 * What is pinned is what would otherwise cost a reader a book or a
 * place: an upload resumes rather than restarts, a book the server
 * refuses is refused once rather than sent forever, a file is only
 * linked when it is provably the one sent, and a delete never names a
 * book on somebody else's server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BookOrbitUploadClientTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var db: LiseurDatabase
    private lateinit var account: RemoteServer

    @Before
    fun start() = runBlocking {
        CredentialCipher.keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
        account = RemoteServer(
            kind = ServerKind.BOOKORBIT, baseUrl = "http://127.0.0.1:${server.port}",
            username = "reader", passwordCipher = null, apiKeyCipher = null, accountId = "1",
            userId = null, koboTokenCipher = null, canDownload = true, addedAt = 1,
            catalogSyncedAt = null, positionSyncedAt = null, syncToken = null,
            orbitAccessCipher = RemoteServer.seal("access"), orbitRefreshCipher = RemoteServer.seal("refresh"),
            orbitAccessExpires = Long.MAX_VALUE, orbitEpoch = 7,
        )
        db.remoteServerDao().upsert(account)
    }

    @After
    fun stop() {
        db.close()
        server.close()
        CredentialCipher.keyForTesting = null
    }

    private fun http() = BookOrbitHttp(BookOrbitSession(db.remoteServerDao()))

    private fun uploader() = BookOrbitUploadClient(http(), db.remoteServerDao(), db.bookOrbitBindingDao())

    private fun deleter() = BookOrbitDeleteClient(http(), db.remoteServerDao(), db.bookOrbitBindingDao())

    private fun json(body: String, code: Int = 200) = MockResponse(
        code = code,
        headers = okhttp3.Headers.headersOf("Content-Type", "application/json"),
        body = body,
    )

    private fun capabilities(chunk: Long = 1024, max: Long = 1_000_000, canUpload: Boolean = true) = json(
        """
        {"maxFileSizeBytes":$max,"chunkSizeBytes":$chunk,"supportedFormats":["epub","pdf"],
         "canUploadToLibrary":$canUpload,"canUseBookDock":false,
         "libraries":[{"id":3,"name":"Books","allowedFormats":["epub"],"organizationMode":"book_per_file","folders":[]}]}
        """.trimIndent(),
    )

    private fun session(status: String, received: Long, size: Long, bookId: Long? = null, errorCode: String? = null) = json(
        JSONObject()
            .put("id", SESSION_ID).put("status", status).put("receivedBytes", received)
            .put("sizeBytes", size).put("chunkSizeBytes", 1024)
            .put("bookId", bookId ?: JSONObject.NULL)
            .put("errorCode", errorCode ?: JSONObject.NULL)
            .toString(),
        code = if (status == "receiving" && received == 0L) 201 else 200,
    )

    private fun coded(code: Int, errorCode: String) =
        json("""{"statusCode":$code,"message":"/srv/library/secret path","errorCode":"$errorCode"}""", code)

    private fun book(bytes: ByteArray = "abcdefghij".toByteArray()): File =
        folder.newFile().apply { writeBytes(bytes) }

    private suspend fun send(file: File, bookUrl: String = "file:///books/one.epub") = uploader().upload(
        account.baseUrl, RemoteCredentials.Bearer("unused"), "library:3", file, "Ada - One.epub",
        bookUrl = bookUrl, sha256 = sha256(file), accountKey = account.accountKey,
    )

    private fun sha256(file: File) = file.inputStream().use(BookOrbitUploadClient::sha256Of)

    private fun takeAll(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    @Test
    fun `only libraries that take an EPUB are offered, in a stable order`() = runBlocking {
        server.enqueue(
            json(
                """
                {"maxFileSizeBytes":10,"chunkSizeBytes":10,"canUploadToLibrary":true,"libraries":[
                  {"id":3,"name":"Books","allowedFormats":["EPUB","pdf"],"folders":[]},
                  {"id":1,"name":"Comics","allowedFormats":["cbz"],"folders":[]},
                  {"id":2,"name":"Anything","allowedFormats":[],"folders":[]}]}
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(RemoteUploadTarget("library:2", "Anything"), RemoteUploadTarget("library:3", "Books")),
            uploader().targets(account.baseUrl, RemoteCredentials.Bearer("unused")),
        )
    }

    @Test
    fun `an account without the permission has nowhere to upload`() = runBlocking {
        server.enqueue(capabilities(canUpload = false))

        assertTrue(uploader().targets(account.baseUrl, RemoteCredentials.Bearer("unused")).isEmpty())
    }

    @Test
    fun `a body without a library list is not an answer of none`() = runBlocking {
        server.enqueue(json("""{"canUploadToLibrary":true}"""))

        val thrown = runCatching { uploader().targets(account.baseUrl, RemoteCredentials.Bearer("unused")) }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `a book is sent in chunks and waits for the import before it is linked`() = runBlocking {
        val file = book()
        server.enqueue(capabilities(chunk = 4))
        server.enqueue(session("receiving", 0, 10))
        server.enqueue(session("receiving", 4, 10))
        server.enqueue(session("receiving", 8, 10))
        server.enqueue(session("receiving", 10, 10))
        server.enqueue(session("processing", 10, 10, bookId = 5))

        assertEquals(ServerUploadResult.Pending, send(file))

        val requests = takeAll()
        val create = JSONObject(requests[1].body!!.utf8())
        assertEquals(10, create.getInt("sizeBytes"))
        assertEquals(sha256(file), create.getString("sha256"))
        assertEquals("library", create.getJSONObject("target").getString("kind"))
        assertEquals(3, create.getJSONObject("target").getInt("libraryId"))
        assertTrue(create.getString("idempotencyKey").matches(Regex("^[A-Za-z0-9._:-]{8,100}$")))
        assertEquals(listOf("0", "4", "8"), requests.subList(2, 5).map { it.headers["Upload-Offset"] })
        assertTrue(requests[2].headers["Upload-Checksum"]!!.startsWith("sha256="))
        assertEquals("/api/v1/uploads/$SESSION_ID/complete", requests[5].target)
    }

    @Test
    fun `a completed upload is linked to the one file of its size`() = runBlocking {
        val file = book()
        server.enqueue(capabilities())
        server.enqueue(session("completed", 10, 10, bookId = 5))
        server.enqueue(
            json(
                """{"id":5,"files":[{"id":8,"format":"jpg","role":"cover","sizeBytes":10},
                   {"id":9,"format":"epub","role":"primary","sizeBytes":10}]}""",
            ),
        )

        val result = send(file) as ServerUploadResult.Uploaded

        assertEquals("5", result.remoteBookId)
        assertEquals(BookOrbitScope.remoteId(account.baseUrl, "1", 5), result.remoteUuid)
        assertEquals(BookOrbitUrl.downloadHref(9), result.downloadHref)
        assertEquals(9L, result.fileId)
        assertEquals("/api/v1/books/5", takeAll().last().target)
    }

    @Test
    fun `several files of the right size are told apart by their bytes`() = runBlocking {
        val file = book()
        server.enqueue(capabilities())
        server.enqueue(session("completed", 10, 10, bookId = 5))
        server.enqueue(
            json(
                """{"id":5,"files":[{"id":8,"format":"epub","sizeBytes":10},
                   {"id":9,"format":"epub","sizeBytes":10}]}""",
            ),
        )
        server.enqueue(MockResponse(body = "0123456789"))
        server.enqueue(MockResponse(body = String(file.readBytes(), Charsets.US_ASCII)))

        assertEquals(9L, (send(file) as ServerUploadResult.Uploaded).fileId)
    }

    @Test
    fun `a candidate that could not be read is tried again rather than ruled out`() = runBlocking {
        val file = book()
        server.enqueue(capabilities())
        server.enqueue(session("completed", 10, 10, bookId = 5))
        server.enqueue(
            json(
                """{"id":5,"files":[{"id":8,"format":"epub","sizeBytes":10},
                   {"id":9,"format":"epub","sizeBytes":10}]}""",
            ),
        )
        server.enqueue(MockResponse(body = String(file.readBytes(), Charsets.US_ASCII)))
        server.enqueue(MockResponse(code = 503))

        assertTrue(send(file) is ServerUploadResult.Failed)
    }

    @Test
    fun `an account that may not download leaves a completed upload unlinked`() = runBlocking {
        val file = book()
        server.enqueue(capabilities())
        server.enqueue(session("completed", 10, 10, bookId = 5))
        server.enqueue(
            json(
                """{"id":5,"files":[{"id":8,"format":"epub","sizeBytes":10},
                   {"id":9,"format":"epub","sizeBytes":10}]}""",
            ),
        )
        server.enqueue(MockResponse(code = 403))

        assertEquals(ServerUploadResult.UploadedUnlinked(null), send(file))
    }

    @Test
    fun `an upload whose file cannot be proved is not linked`() = runBlocking {
        val file = book()
        server.enqueue(capabilities())
        server.enqueue(session("completed", 10, 10, bookId = 5))
        server.enqueue(json("""{"id":5,"files":[{"id":9,"format":"epub","sizeBytes":11}]}"""))

        assertEquals(ServerUploadResult.UploadedUnlinked(null), send(file))
    }

    @Test
    fun `an interrupted upload resumes from where the server has got to`() = runBlocking {
        val file = book()
        server.enqueue(capabilities(chunk = 4))
        server.enqueue(session("receiving", 8, 10))
        server.enqueue(session("receiving", 10, 10))
        server.enqueue(session("processing", 10, 10))

        assertEquals(ServerUploadResult.Pending, send(file))

        val chunk = takeAll()[2]
        assertEquals("8", chunk.headers["Upload-Offset"])
        assertTrue(chunk.body!!.utf8().contains("ij"))
    }

    @Test
    fun `a disagreement about the offset is settled by asking the server`() = runBlocking {
        val file = book()
        server.enqueue(capabilities(chunk = 4))
        server.enqueue(session("receiving", 4, 10))
        server.enqueue(coded(409, "UPLOAD_OFFSET_MISMATCH"))
        server.enqueue(session("receiving", 10, 10))
        server.enqueue(session("processing", 10, 10))

        assertEquals(ServerUploadResult.Pending, send(file))

        val requests = takeAll()
        assertEquals("GET", requests[3].method)
        assertEquals("/api/v1/uploads/$SESSION_ID/complete", requests[4].target)
    }

    @Test
    fun `a book already on the server is refused once, with no server path shown`() = runBlocking {
        val file = book()
        server.enqueue(capabilities())
        server.enqueue(session("receiving", 10, 10))
        server.enqueue(coded(409, "UPLOAD_DESTINATION_CONFLICT"))

        assertEquals(ServerUploadResult.Rejected(null), send(file))
    }

    @Test
    fun `a stored refusal is read back as the same answer`() = runBlocking {
        server.enqueue(capabilities())
        server.enqueue(session("failed", 10, 10, errorCode = "UPLOAD_CONTENT_INVALID"))

        assertEquals(ServerUploadResult.Rejected(null), send(book()))
    }

    @Test
    fun `a session that failed on its library is tried again after the libraries are listed`() = runBlocking {
        server.enqueue(capabilities())
        server.enqueue(session("failed", 10, 10, errorCode = "UPLOAD_TARGET_INVALID"))

        assertEquals(ServerUploadResult.Failed("upload target"), send(book()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an expired or unexplained session is left for the next key`() = runBlocking {
        server.enqueue(capabilities())
        server.enqueue(session("expired", 4, 10))
        server.enqueue(session("failed", 10, 10, errorCode = "UPLOAD_IMPORT_FAILED"))
        server.enqueue(session("processing", 10, 10))

        assertEquals(ServerUploadResult.Pending, send(book()))

        val keys = takeAll().drop(1).map { JSONObject(it.body!!.utf8()).getString("idempotencyKey") }
        assertTrue(keys[0].endsWith("-g0"))
        assertTrue(keys[1].endsWith("-g1"))
        assertTrue(keys[2].endsWith("-g2"))
    }

    @Test
    fun `a book larger than the server takes is not sent`() = runBlocking {
        server.enqueue(capabilities(max = 5))

        assertEquals(ServerUploadResult.TooLarge, send(book()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a revoked permission turns the upload off`() = runBlocking {
        server.enqueue(capabilities())
        server.enqueue(json("""{"statusCode":403,"message":"Library upload permission is required"}""", 403))

        assertEquals(ServerUploadResult.NotAllowed, send(book()))
    }

    @Test
    fun `a server error is tried again later`() = runBlocking {
        server.enqueue(capabilities())
        server.enqueue(session("receiving", 0, 10))
        server.enqueue(coded(507, "UPLOAD_STORAGE_FULL"))

        assertTrue(send(book()) is ServerUploadResult.Failed)
    }

    @Test
    fun `a book is never sent as a different account than the one checked`() = runBlocking {
        val file = book()

        val result = uploader().upload(
            account.baseUrl, RemoteCredentials.Bearer("unused"), "library:3", file, "Ada - One.epub",
            bookUrl = "file:///books/one.epub", sha256 = sha256(file), accountKey = "another reader",
        )

        assertTrue(result is ServerUploadResult.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `two entries with the same bytes never share a session`() {
        val first = BookOrbitUploadClient.idempotencyKey("scope", "file:///a.epub", 3, "A.epub", 10, "ab", 0)
        val again = BookOrbitUploadClient.idempotencyKey("scope", "file:///a.epub", 3, "A.epub", 10, "ab", 0)
        val other = BookOrbitUploadClient.idempotencyKey("scope", "file:///b.epub", 3, "A.epub", 10, "ab", 0)
        val next = BookOrbitUploadClient.idempotencyKey("scope", "file:///a.epub", 3, "A.epub", 10, "ab", 1)

        assertEquals(first, again)
        assertNotEquals(first, other)
        assertNotEquals(first, next)
        assertTrue(first.length <= 100)
    }

    @Test
    fun `adoption binds the server file under the local entry with its digest`() = runBlocking {
        val uploaded = ServerUploadResult.Uploaded(
            remoteBookId = "5", alreadyThere = false,
            remoteUuid = BookOrbitScope.remoteId(account.baseUrl, "1", 5),
            downloadHref = BookOrbitUrl.downloadHref(9), fileId = 9, fileSize = 10,
        )

        uploader().adopted("file:///books/one.epub", account.accountKey, uploaded, "AB".repeat(32))
        uploader().adopted("file:///books/two.epub", "somebody else", uploaded, "ab".repeat(32))

        val binding = db.bookOrbitBindingDao().get(account.accountKey, "file:///books/one.epub")!!
        assertEquals(5L, binding.bookId)
        assertEquals(9L, binding.fileId)
        assertEquals(10L, binding.fileSize)
        assertEquals(BookOrbitBindingState.SELECTED.name, binding.state)
        assertEquals("ab".repeat(32), binding.localSha256)
        assertNull(db.bookOrbitBindingDao().get("somebody else", "file:///books/two.epub"))
    }

    @Test
    fun `a delete sends the book id and forgets the binding`() = runBlocking {
        val remoteUuid = BookOrbitScope.remoteId(account.baseUrl, "1", 5)
        server.enqueue(MockResponse(code = 204))

        assertEquals(ServerDeleteResult.Deleted, deleter().delete(account.baseUrl, RemoteCredentials.Bearer("x"), entry(remoteUuid)))

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/books", request.target)
        assertEquals(5, JSONObject(request.body!!.utf8()).getJSONArray("bookIds").getInt(0))

        db.bookOrbitBindingDao().write(binding(5))
        deleter().forgetDeleted("bookorbit:one", account.accountKey)
        assertNull(db.bookOrbitBindingDao().get(account.accountKey, "bookorbit:one"))
    }

    @Test
    fun `a book already gone is deleted and a refusal is not`() = runBlocking {
        val remoteUuid = BookOrbitScope.remoteId(account.baseUrl, "1", 5)
        server.enqueue(json("""{"message":"not found"}""", 404))
        server.enqueue(json("""{"message":"forbidden"}""", 403))

        val credentials = RemoteCredentials.Bearer("x")
        assertEquals(ServerDeleteResult.Deleted, deleter().delete(account.baseUrl, credentials, entry(remoteUuid)))
        assertEquals(ServerDeleteResult.NotAllowed, deleter().delete(account.baseUrl, credentials, entry(remoteUuid)))
    }

    @Test
    fun `a book named for another server or account is never deleted`() = runBlocking {
        val credentials = RemoteCredentials.Bearer("x")
        val elsewhere = BookOrbitScope.remoteId("https://other.example", "1", 5)
        val otherReader = BookOrbitScope.remoteId(account.baseUrl, "2", 5)
        db.bookOrbitBindingDao().write(binding(6))

        for (uuid in listOf(elsewhere, otherReader, "5", null)) {
            assertTrue(deleter().delete(account.baseUrl, credentials, entry(uuid)) is ServerDeleteResult.Failed)
        }
        // The binding says this entry is another book.
        assertTrue(
            deleter().delete(account.baseUrl, credentials, entry(BookOrbitScope.remoteId(account.baseUrl, "1", 5)))
                is ServerDeleteResult.Failed,
        )
        assertEquals(0, server.requestCount)
    }

    private fun entry(remoteUuid: String?) = Book(
        url = "bookorbit:one", title = "One", author = null, coverPath = null, source = null,
        addedAt = 1, lastOpenedAt = null, remoteUuid = remoteUuid,
    )

    private fun binding(bookId: Long) = BookOrbitBinding(
        accountKey = account.accountKey, bookUrl = "bookorbit:one", bookId = bookId, fileId = 9,
        fileFormat = "epub", fileSize = 10, fileName = null, revision = 0,
        state = BookOrbitBindingState.SELECTED.name, updatedAt = 1,
    )

    private companion object {
        const val SESSION_ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
