package com.chmouel.liseur.data.bookorbit

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookOrbitBinding
import com.chmouel.liseur.data.db.BookOrbitBindingState
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.sync.PositionUpdate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BookOrbitCfiRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
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
    fun `opened package must be the selected app-owned download opened by Readium`(): Unit = runBlocking {
        val file = folder.newFile("selected.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, xml) in mapOf(
                "META-INF/container.xml" to
                    """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "OPS/book.opf" to
                    """<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="one" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>""",
                "OPS/chapter.xhtml" to
                    """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Original passage</p></body></html>""",
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
        }
        val uri = Uri.fromFile(file).toString()
        val book = Book(
            url = binding.bookUrl, title = "Test book", author = null, coverPath = null,
            source = null, addedAt = 1, lastOpenedAt = null, localUri = uri,
            remoteUuid = "selected", downloadHref = BookOrbitUrl.downloadHref(34),
            downloadState = DownloadState.DOWNLOADED,
        )
        db.bookDao().upsert(book)
        val stored = db.bookDao().getByUrl(book.url)!!
        db.bookOrbitBindingDao().write(binding.copy(fileSize = file.length()))
        val context = repository.capture(binding.bookUrl)
        val ownedFile: (String) -> java.io.File = { uuid ->
            assertEquals("selected", uuid)
            file
        }
        val opened = repository.openedPackage(context, uri, ownedFile)
        assertEquals("OPS/book.opf", opened.publication.packagePath)
        assertEquals(
            opened.publication,
            repository.openedIfConnected(binding.bookUrl, uri, ownedFile)?.publication,
        )
        assertNull(repository.openedIfConnected("file:///local.epub", uri, ownedFile))
        assertEquals(
            "Original passage",
            repository.originalDocument(opened, "OPS/chapter.xhtml", ownedFile)!!
                .getElementsByTagNameNS("*", "p").item(0).textContent,
        )

        suspend fun rejected(opened: String = uri, fileFor: (String) -> java.io.File = ownedFile) {
            assertThrows(BookOrbitIdentityChanged::class.java) {
                runBlocking { repository.openedPackage(context, opened, fileFor) }
            }
        }
        rejected(opened = "file:///other.epub")
        rejected(fileFor = { folder.newFile("other.epub") })
        db.bookDao().upsert(stored.copy(downloadHref = BookOrbitUrl.downloadHref(35)))
        rejected()
        db.bookDao().upsert(stored)
        db.bookOrbitBindingDao().write(binding.copy(fileSize = file.length() + 1))
        rejected()
        db.bookOrbitBindingDao().write(binding.copy(fileSize = file.length()))
        db.bookDao().upsert(stored.copy(downloadState = DownloadState.REMOTE))
        rejected()
        assertThrows(BookOrbitIdentityChanged::class.java) {
            runBlocking { repository.originalDocument(opened, "OPS/chapter.xhtml", ownedFile) }
        }
    }

    @Test
    fun `an uploaded book in the app's store is read where it is, and only while it is the sent file`(): Unit =
        runBlocking {
            val file = folder.newFile("uploaded.epub").also { writeEpub(it, "Sent passage") }
            val uri = Uri.fromFile(file).toString()
            adopt(uri, file.readBytes())
            val sources = BookOrbitAdoptedSource(ApplicationProvider.getApplicationContext())
            repository = BookOrbitCfiRepository(db, sources, folder.newFolder("spool"))
            val context = repository.capture(binding.bookUrl)
            val noDownload: (String) -> java.io.File = { error("an uploaded book has no download") }

            val opened = repository.openedPackage(context, uri, noDownload)

            assertEquals(file, opened.file)
            assertFalse(opened.spooled)
            assertEquals("Sent passage", passage(repository.originalDocument(opened, "OPS/chapter.xhtml", noDownload)))
            assertThrows(BookOrbitIdentityChanged::class.java) {
                runBlocking { repository.openedPackage(context, "file:///somewhere/else.epub", noDownload) }
            }
            // Different bytes are not the server's file, whatever they are called.
            writeEpub(file, "Other passage")
            assertThrows(BookOrbitIdentityChanged::class.java) {
                runBlocking { repository.openedPackage(context, uri, noDownload) }
            }
            assertTrue(file.isFile)
        }

    @Test
    fun `an uploaded document is copied once, and the copy goes when it stops being the sent file`(): Unit =
        runBlocking {
            val bytes = folder.newFile("source.epub").also { writeEpub(it, "Sent passage") }.readBytes()
            AdoptedDocs.bytes = bytes
            AdoptedDocs.modified = 1_000
            Robolectric.buildContentProvider(AdoptedDocs::class.java).create(ADOPTED_AUTHORITY)
            val uri = "content://$ADOPTED_AUTHORITY/document/book"
            adopt(uri, bytes)
            val spool = folder.newFolder("spool")
            repository = BookOrbitCfiRepository(
                db, BookOrbitAdoptedSource(ApplicationProvider.getApplicationContext()), spool,
            )
            val context = repository.capture(binding.bookUrl)
            val noDownload: (String) -> java.io.File = { error("an uploaded book has no download") }

            val opened = repository.openedPackage(context, uri, noDownload)
            repeat(2) {
                assertEquals("Sent passage", passage(repository.originalDocument(opened, "OPS/chapter.xhtml", noDownload)))
            }

            assertTrue(opened.spooled)
            assertEquals(listOf(opened.file), spool.walkTopDown().filter { it.isFile }.toList())
            // The startup sweep runs alongside opening and must not take
            // this process's copy.
            val earlier = java.io.File(spool, "p-earlier").apply { mkdirs() }
            java.io.File(earlier, "left-behind.epub").writeText("x")
            repository.sweepSpools()
            assertFalse(earlier.exists())
            assertTrue(opened.file.isFile)
            AdoptedDocs.modified = 2_000
            assertThrows(BookOrbitIdentityChanged::class.java) {
                runBlocking { repository.originalDocument(opened, "OPS/chapter.xhtml", noDownload) }
            }
            assertTrue(spool.walkTopDown().none { it.isFile })

            AdoptedDocs.modified = 1_000
            var owned: BookOrbitOpenedEpub? = null
            val again = repository.openedPackage(context, uri, noDownload) { owned = it }
            assertEquals(again, owned)
            repository.release(again)
            assertTrue(spool.walkTopDown().none { it.isFile })
        }

    @Test
    fun `a stored place is not sent for an uploaded file that has been replaced`(): Unit = runBlocking {
        val file = folder.newFile("uploaded.epub").also { writeEpub(it, "Sent passage") }
        val sent = file.readBytes()
        adopt(Uri.fromFile(file).toString(), sent)
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        val modified = file.lastModified()
        // Same size, same time, different bytes: only the digest tells.
        file.writeBytes(sent.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() })
        file.setLastModified(modified)

        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { agreement(BookOrbitAdoptedSource(ApplicationProvider.getApplicationContext())).prepare(context) }
        }
        assertEquals(0, server.requestCount)

        file.writeBytes(sent)
        file.setLastModified(modified)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement(BookOrbitAdoptedSource(ApplicationProvider.getApplicationContext())).prepare(context)
        server.takeRequest()

        // A prepared send resumed in a later process checks again.
        file.writeBytes(sent.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() })
        file.setLastModified(modified)
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { agreement(BookOrbitAdoptedSource(ApplicationProvider.getApplicationContext())).send(context) }
        }
        assertEquals(1, server.requestCount)
        assertEquals(BookOrbitAttempt.PREPARED.name, agreement().state(context).attemptState)
    }

    /** The shelf row and binding an upload adoption leaves behind for [uri]. */
    private suspend fun adopt(uri: String, bytes: ByteArray) {
        db.bookDao().upsert(
            Book(
                url = binding.bookUrl, title = "Uploaded", author = null, coverPath = null,
                source = null, addedAt = 1, lastOpenedAt = null, localUri = uri,
                remoteUuid = BookOrbitScope.remoteId(account.baseUrl, "1", 12),
                downloadHref = BookOrbitUrl.downloadHref(34), downloadState = DownloadState.DOWNLOADED,
            ),
        )
        db.bookOrbitBindingDao().write(
            binding.copy(
                fileSize = bytes.size.toLong(),
                localSha256 = BookOrbitUploadClient.sha256Of(bytes.inputStream()),
            ),
        )
    }

    private fun writeEpub(file: java.io.File, text: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, xml) in mapOf(
                "META-INF/container.xml" to
                    """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "OPS/book.opf" to
                    """<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="one" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>""",
                "OPS/chapter.xhtml" to
                    """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>$text</p></body></html>""",
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun passage(document: org.w3c.dom.Document?): String? =
        document?.getElementsByTagNameNS("*", "p")?.item(0)?.textContent

    @Test
    fun `verified local CFI is paired with one revision and cleared by a later position`() = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val locator = """{"href":"OPS/chapter.xhtml","locations":{"progression":0.3}}"""
        val update = PositionUpdate(
            bookUrl = binding.bookUrl, locatorJson = locator, progression = 0.3,
            readingSecondsPerPosition = null, readingPaceSamples = null,
            readingPaceElapsedMs = null, readingPaceEvidence = null, updatedAt = 10,
        )
        val writer = BookOrbitLocalPositionWriter(db)
        val candidate = BookOrbitLocalCandidate(
            context, "OPS/chapter.xhtml", locator, "epubcfi(/6/2!/4/2/1:3)",
        )
        writer.save(update.copy(bookOrbitCfi = candidate), "Reading")
        val saved = db.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)!!
        val progress = db.readingProgressDao().get(context.bookUrl)!!
        assertEquals(progress.localRevision, saved.localRevision)
        assertEquals(progress.locatorJson, saved.locatorJson)
        assertEquals(candidate.rawCfi, saved.rawCfi)

        writer.save(update.copy(updatedAt = 11), "Reading")
        assertNull(db.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl))
        assertEquals(progress.localRevision + 1, db.readingProgressDao().get(context.bookUrl)!!.localRevision)

        db.bookOrbitBindingDao().write(binding.copy(fileId = 35, revision = 3))
        writer.save(update.copy(updatedAt = 12, bookOrbitCfi = candidate), "Reading")
        assertNull(db.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl))
        assertEquals(progress.localRevision + 2, db.readingProgressDao().get(context.bookUrl)!!.localRevision)

        val next = repository.capture(binding.bookUrl)
        writer.save(update.copy(updatedAt = 13, bookOrbitCfi = candidate.copy(context = next)), "Reading")
        assertNotNull(db.bookOrbitLocalCfiDao().get(next.request.accountKey, next.bookUrl))
        db.bookOrbitLocalCfiDao().clearAccount(next.request.accountKey)
        assertNull(db.bookOrbitLocalCfiDao().get(next.request.accountKey, next.bookUrl))
        writer.save(update.copy(updatedAt = 14, bookOrbitCfi = candidate.copy(context = next)), "Reading")
        db.bookOrbitBindingDao().clearBook(next.bookUrl)
        assertNull(db.bookOrbitLocalCfiDao().get(next.request.accountKey, next.bookUrl))

        db.bookOrbitBindingDao().write(binding.copy(fileId = 35, revision = 3))
        writer.save(update.copy(updatedAt = 15, bookOrbitCfi = candidate.copy(context = next)), "Reading")
        db.readingProgressDao().forget(next.bookUrl)
        assertNull(db.bookOrbitLocalCfiDao().get(next.request.accountKey, next.bookUrl))
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
    fun `retry-after zero cannot replay a progress POST`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val transport = BookOrbitProgressMutationTransport(
            BookOrbitHttp(BookOrbitSession(db.remoteServerDao())), repository,
        )
        server.enqueue(MockResponse(code = 503, headers = okhttp3.Headers.headersOf("Retry-After", "0")))
        server.enqueue(MockResponse(code = 201))
        assertEquals(
            BookOrbitHttp.MutationResult.Uncertain,
            transport.send(context, JSONObject("""{"percentage":25,"cfi":"epubcfi(/6/4)"}""")),
        )
        assertEquals(1, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
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

    private fun agreement(
        sources: BookOrbitAdoptedSource = BookOrbitAdoptedSource(null),
    ): BookOrbitPositionAgreementRepository {
        val http = BookOrbitHttp(BookOrbitSession(db.remoteServerDao()))
        return BookOrbitPositionAgreementRepository(
            db, BookOrbitProgressClient(http, repository),
            BookOrbitProgressMutationTransport(http, repository), sources,
        )
    }

    private fun exchange(): BookOrbitPositionExchange {
        val http = BookOrbitHttp(BookOrbitSession(db.remoteServerDao()))
        val client = BookOrbitProgressClient(http, repository)
        return BookOrbitPositionExchange(
            repository, client,
            BookOrbitPositionAgreementRepository(
                db, client, BookOrbitProgressMutationTransport(http, repository),
            ),
        )
    }

    private suspend fun saveExact(
        context: BookOrbitCfiContext, cfi: String, at: Long, progression: Double = 0.25,
        approximate: BookOrbitApproximateOffer? = null, pull: BookOrbitPullOffer? = null,
        declined: BookOrbitPullOffer? = null,
    ) {
        val locator = """{"href":"OPS/chapter.xhtml","locations":{"progression":$progression}}"""
        BookOrbitLocalPositionWriter(db).save(
            PositionUpdate(
                bookUrl = context.bookUrl, locatorJson = locator, progression = progression,
                readingSecondsPerPosition = null, readingPaceSamples = null,
                readingPaceElapsedMs = null, readingPaceEvidence = null,
                updatedAt = at, bookOrbitCfi = BookOrbitLocalCandidate(
                    context, "OPS/chapter.xhtml", locator, cfi,
                ),
                bookOrbitApproximate = approximate, bookOrbitPull = pull, bookOrbitDeclined = declined,
            ), "Reading",
        )
    }

    private fun saved(cfi: String, percentage: Double = 25.0): String =
        JSONObject(fixture("progress-saved.json")).apply {
            put("cfi", cfi)
            put("percentage", percentage)
        }.toString()

    private fun percentageOnly(percentage: Double): String =
        JSONObject(fixture("progress-saved.json")).apply {
            put("cfi", JSONObject.NULL)
            put("percentage", percentage)
        }.toString()

    private fun progress(body: String) = BookOrbitFileProgress.parse(JSONObject(body))

    private suspend fun saveApproximate(context: BookOrbitCfiContext, at: Long, progression: Double) {
        val locator = """{"href":"OPS/chapter.xhtml","locations":{"progression":$progression}}"""
        BookOrbitLocalPositionWriter(db).save(
            PositionUpdate(
                bookUrl = context.bookUrl, locatorJson = locator, progression = progression,
                readingSecondsPerPosition = null, readingPaceSamples = null,
                readingPaceElapsedMs = null, readingPaceEvidence = null,
                updatedAt = at, bookOrbitCfi = null,
            ), "Reading",
        )
    }

    /** Serves [remote] to every GET; a POST replaces it with what was sent. */
    private fun serveRemote(initial: String): () -> List<String> {
        var remote = initial
        val posts = mutableListOf<String>()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                when (request.method) {
                    "GET" -> MockResponse(body = remote)
                    "POST" -> {
                        val sent = JSONObject(request.body!!.utf8())
                        posts += sent.getString("cfi")
                        remote = saved(sent.getString("cfi"), sent.getDouble("percentage"))
                        MockResponse(code = 201)
                    }
                    else -> error("Unexpected request")
                }
        }
        return { posts.toList() }
    }

    @Test
    fun `fresh book opened at a percentage-only place pushes the first exact move over it`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        val posts = serveRemote(percentageOnly(67.624))
        val offer = agreement().approximateOffer(context, progress(percentageOnly(67.624)))!!
        assertFalse(offer.replacesLocalPlace)
        assertEquals(0.67624, offer.progression, 0.000001)
        assertNull(agreement().approximateOffer(context, progress(saved(cfi, 67.0))))

        saveExact(context, cfi, 10, progression = 0.68)
        assertTrue(agreement().adoptApproximateBaseline(offer))
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(listOf(cfi), posts())
        val agreed = agreement().state(context)
        assertEquals(cfi, agreed.agreedRemoteCfi)
        assertEquals(db.readingProgressDao().get(context.bookUrl)!!.positionRevision, agreed.agreedLocalRevision)
    }

    @Test
    fun `the move that saves the position agrees the opening in the same write`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        val posts = serveRemote(percentageOnly(67.624))
        val offer = agreement().approximateOffer(context, progress(percentageOnly(67.624)))!!
        saveExact(context, cfi, 10, progression = 0.68, approximate = offer)
        val agreed = agreement().state(context)
        assertEquals(true, agreed.agreedRemoteSaved)
        assertNull(agreed.agreedRemoteCfi)
        assertEquals(67.624, agreed.agreedRemotePercentage!!, 0.0)
        assertNull(agreed.agreedLocalRevision)

        // Later moves still carry the offer; it agrees nothing once agreed.
        val next = "epubcfi(/6/4!/4/2:9)"
        saveExact(context, next, 11, progression = 0.69, approximate = offer)
        assertEquals(agreed.agreedRemotePercentage, agreement().state(context).agreedRemotePercentage)
        assertNull(agreement().state(context).agreedLocalRevision)
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(listOf(next), posts())
        val pushed = agreement().state(context)
        saveExact(context, "epubcfi(/6/4!/4/2:12)", 12, progression = 0.7, approximate = offer)
        assertEquals(next, agreement().state(context).agreedRemoteCfi)
        assertEquals(pushed.agreedLocalRevision, agreement().state(context).agreedLocalRevision)
    }

    @Test
    fun `approximate baseline needs the move to have landed`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveApproximate(context, 10, progression = 0.0039)
        val offer = agreement().approximateOffer(context, progress(percentageOnly(67.624)))!!
        assertFalse(agreement().adoptApproximateBaseline(offer))
        assertNull(agreement().state(context).agreedRemoteSaved)
    }

    @Test
    fun `a sync that runs before the baseline does not strand the first move`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        val posts = serveRemote(percentageOnly(67.624))
        val offer = agreement().approximateOffer(context, progress(percentageOnly(67.624)))!!
        saveExact(context, cfi, 10, progression = 0.68)
        assertEquals(BookOrbitPositionExchange.Result.Unresolved, exchange().run(context.bookUrl))
        assertTrue(agreement().adoptApproximateBaseline(offer))
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(listOf(cfi), posts())
    }

    @Test
    fun `a percentage-only place that changed after opening is never overwritten`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val offer = agreement().approximateOffer(context, progress(percentageOnly(67.624)))!!
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10, progression = 0.68)
        assertTrue(agreement().adoptApproximateBaseline(offer))
        val posts = serveRemote(percentageOnly(70.0))
        assertEquals(BookOrbitPositionExchange.Result.Unresolved, exchange().run(context.bookUrl))
        assertEquals(emptyList<String>(), posts())
        assertEquals(67.624, agreement().state(context).agreedRemotePercentage!!, 0.0)
    }

    @Test
    fun `approximate baseline is refused once the server was seen to change`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val offer = agreement().approximateOffer(context, progress(percentageOnly(67.624)))!!
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10, progression = 0.68)
        agreement().observe(context, progress(percentageOnly(70.0)))
        assertFalse(agreement().adoptApproximateBaseline(offer))
        assertNull(agreement().state(context).agreedRemoteSaved)
    }

    @Test
    fun `a POST that never landed over a percentage-only place needs an explicit retry`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val offer = agreement().approximateOffer(context, progress(percentageOnly(67.624)))!!
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10, progression = 0.68)
        assertTrue(agreement().adoptApproximateBaseline(offer))
        repeat(3) { server.enqueue(MockResponse(body = percentageOnly(67.624))) }
        server.enqueue(MockResponse(code = 503, headers = okhttp3.Headers.headersOf("Retry-After", "0")))
        server.enqueue(MockResponse(body = percentageOnly(67.624)))
        exchange().run(context.bookUrl)
        assertEquals(listOf("GET", "GET", "GET", "POST", "GET"),
            (1..5).map { server.takeRequest().method })
        assertEquals(BookOrbitAttempt.RETRY_REQUIRED.name, agreement().state(context).attemptState)
    }

    @Test
    fun `an unmatched local place moves only to a further percentage-only place`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveApproximate(context, 10, progression = 0.0039)
        assertNull(agreement().approximateOffer(context, progress(percentageOnly(0.5))))
        val offer = agreement().approximateOffer(context, progress(percentageOnly(67.624)))!!
        assertTrue(offer.replacesLocalPlace)

        // A place already read here with an exact passage is never moved blind.
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 11, progression = 0.1)
        assertNull(agreement().approximateOffer(context, progress(percentageOnly(67.624))))
    }

    @Test
    fun `an agreed untouched place follows the server to a percentage-only place`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, cfi, 10)
        agreement().observe(context, progress(saved(cfi)))
        assertEquals(true, agreement().state(context).agreedRemoteSaved)
        val offer = agreement().approximateOffer(context, progress(percentageOnly(10.0)))!!
        assertTrue(offer.replacesLocalPlace)

        saveExact(context, "epubcfi(/6/4!/4/2:9)", 11, progression = 0.3)
        assertNull(agreement().approximateOffer(context, progress(percentageOnly(10.0))))
    }

    @Test
    fun `verified remote place is adopted only after closing and without acknowledging status`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        val next = "epubcfi(/6/4!/4/2:9)"
        saveExact(context, first, 10)
        val agreement = agreement()
        assertFalse(agreement.canOfferPull(
            context, BookOrbitFileProgress.parse(JSONObject(saved(next, 70.0))),
        ))
        val baseline = BookOrbitFileProgress.parse(JSONObject(saved(first)))
        val changed = BookOrbitFileProgress.parse(JSONObject(saved(next, 70.0)))
        agreement.observe(context, baseline)
        val original = db.readingProgressDao().get(context.bookUrl)!!
        assertTrue(agreement.canOfferPull(context, changed))
        agreement.observe(context, changed)
        val offer = BookOrbitPullOffer(
            context, changed, original.positionRevision, original.locatorJson,
            """{"href":"OPS/chapter.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.65}}""",
            "OPS/chapter.xhtml",
        )

        db.readingProgressDao().openBooks.enter(context.bookUrl)
        server.enqueue(MockResponse(body = saved(next, 70.0)))
        assertFalse(agreement.adoptVerifiedClosed(offer))
        server.takeRequest()
        assertEquals(original.locatorJson, db.readingProgressDao().get(context.bookUrl)!!.locatorJson)
        db.readingProgressDao().openBooks.leave(context.bookUrl)

        server.enqueue(MockResponse(body = saved(next, 70.0)))
        assertTrue(agreement.adoptVerifiedClosed(offer))
        server.takeRequest()
        val adopted = db.readingProgressDao().get(context.bookUrl)!!
        assertEquals(offer.locatorJson, adopted.locatorJson)
        assertEquals(original.localRevision + 1, adopted.localRevision)
        assertEquals(0.65, adopted.totalProgression!!, 0.000001)
        assertEquals(70.0, agreement.state(context).agreedRemotePercentage!!, 0.0)
        assertEquals(original.status, adopted.status)
        assertEquals(original.ackedRevision, adopted.ackedRevision)
        assertEquals(original.agreedStatus, adopted.agreedStatus)
        assertEquals(next, db.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)!!.rawCfi)
        assertEquals(adopted.localRevision, agreement.state(context).agreedLocalRevision)
        assertEquals(0, server.requestCount - 2)
    }

    @Test
    fun `reading on from an opened server place agrees it and pushes the move`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        val theirs = "epubcfi(/6/4!/4/2:9)"
        saveExact(context, first, 10)
        val agreement = agreement()
        agreement.observe(context, progress(saved(first)))
        val original = db.readingProgressDao().get(context.bookUrl)!!
        val changed = progress(saved(theirs, 70.0))
        assertTrue(agreement.canOfferPull(context, changed))
        agreement.observe(context, changed)
        val offer = BookOrbitPullOffer(
            context, changed, original.positionRevision, original.locatorJson,
            """{"href":"OPS/chapter.xhtml","locations":{"progression":0.7}}""", "OPS/chapter.xhtml",
        )

        val read = "epubcfi(/6/4!/4/2:12)"
        saveExact(context, read, 11, progression = 0.71, pull = offer)
        val agreed = agreement.state(context)
        assertEquals(theirs, agreed.agreedRemoteCfi)
        assertEquals(70.0, agreed.agreedRemotePercentage!!, 0.0)
        assertEquals(original.positionRevision, agreed.agreedLocalRevision)

        val posts = serveRemote(saved(theirs, 70.0))
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(listOf(read), posts())

        // Later moves still carry the offer; once agreed on, it changes nothing.
        saveExact(context, "epubcfi(/6/4!/4/2:15)", 12, progression = 0.72, pull = offer)
        assertEquals(read, agreement.state(context).agreedRemoteCfi)
    }

    @Test
    fun `a fresh book opened at the server's place pushes the first move`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val theirs = "epubcfi(/6/4!/4/2:9)"
        val remote = progress(saved(theirs, 11.5))
        agreement().observe(context, remote)
        val offer = BookOrbitPullOffer(
            context, remote, 0L, "",
            """{"href":"OPS/chapter.xhtml","locations":{"progression":0.1}}""", "OPS/chapter.xhtml",
            fresh = true,
        )
        val read = "epubcfi(/6/4!/4/2:12)"
        saveExact(context, read, 11, progression = 0.14, pull = offer)
        assertEquals(theirs, agreement().state(context).agreedRemoteCfi)
        assertNull(agreement().state(context).agreedLocalRevision)
        val posts = serveRemote(saved(theirs, 11.5))
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(listOf(read), posts())
    }

    @Test
    fun `a fresh opening is refused once something was agreed`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, first, 10)
        agreement().observe(context, progress(saved(first)))
        val remote = progress(saved("epubcfi(/6/4!/4/2:9)", 70.0))
        agreement().observe(context, remote)
        val offer = BookOrbitPullOffer(
            context, remote, 0L, "",
            """{"href":"OPS/chapter.xhtml","locations":{"progression":0.7}}""", "OPS/chapter.xhtml",
            fresh = true,
        )
        saveExact(context, "epubcfi(/6/4!/4/2:12)", 11, progression = 0.71, pull = offer)
        assertEquals(first, agreement().state(context).agreedRemoteCfi)
    }

    @Test
    fun `an opening pull is not agreed once the server moved again`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, first, 10)
        val agreement = agreement()
        agreement.observe(context, progress(saved(first)))
        val original = db.readingProgressDao().get(context.bookUrl)!!
        val changed = progress(saved("epubcfi(/6/4!/4/2:9)", 70.0))
        agreement.observe(context, changed)
        val offer = BookOrbitPullOffer(
            context, changed, original.positionRevision, original.locatorJson,
            """{"href":"OPS/chapter.xhtml","locations":{"progression":0.7}}""", "OPS/chapter.xhtml",
        )
        agreement.observe(context, progress(saved("epubcfi(/6/4!/4/2:20)", 80.0)))
        saveExact(context, "epubcfi(/6/4!/4/2:12)", 11, progression = 0.71, pull = offer)
        assertEquals(first, agreement.state(context).agreedRemoteCfi)
    }

    /** This device read to [first] and pushed it; then [served] was saved elsewhere. */
    private suspend fun movedElsewhere(first: String, served: String): () -> List<String> {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, first, 10)
        agreement().observe(context, progress(saved(first)))
        return serveRemote(saved(served, 70.0))
    }

    private suspend fun catchUp(context: BookOrbitCfiContext, remote: BookOrbitFileProgress): BookOrbitPullOffer {
        val local = db.readingProgressDao().get(context.bookUrl)!!
        return BookOrbitPullOffer(
            context, remote, local.positionRevision, local.locatorJson,
            """{"href":"OPS/chapter.xhtml","type":"application/xhtml+xml","locations":{"progression":0.7,"totalProgression":0.7}}""",
            "OPS/chapter.xhtml",
        )
    }

    @Test
    fun `a place saved elsewhere while reading is offered and adopted in the open reader`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val theirs = "epubcfi(/6/4!/4/2:9)"
        val posts = movedElsewhere("epubcfi(/6/4!/4/2:3)", theirs)
        // This device read on too: both sides moved since the agreement.
        saveExact(context, "epubcfi(/6/4!/4/2:5)", 11, progression = 0.3)
        assertEquals(BookOrbitPositionExchange.Result.Conflict, exchange().run(context.bookUrl))
        assertTrue(agreement().state(context).serverMovedAway())

        val remote = agreement().catchUpOffer(context)!!
        assertEquals(theirs, remote.cfi)
        val offer = catchUp(context, remote)
        db.readingProgressDao().openBooks.enter(context.bookUrl)
        assertTrue(agreement().adoptCaughtUpInReader(offer))
        val adopted = db.readingProgressDao().get(context.bookUrl)!!
        assertEquals(offer.locatorJson, adopted.locatorJson)
        assertEquals(theirs, agreement().state(context).agreedRemoteCfi)
        assertFalse(agreement().state(context).serverMovedAway())
        assertEquals(emptyList<String>(), posts())
    }

    @Test
    fun `a catch-up is refused once this device moved after it was offered`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        movedElsewhere("epubcfi(/6/4!/4/2:3)", "epubcfi(/6/4!/4/2:9)")
        val offer = catchUp(context, agreement().catchUpOffer(context)!!)
        saveExact(context, "epubcfi(/6/4!/4/2:5)", 11, progression = 0.3)
        db.readingProgressDao().openBooks.enter(context.bookUrl)
        assertFalse(agreement().adoptCaughtUpInReader(offer))
        assertEquals(offer.expectedRevision + 1, db.readingProgressDao().get(context.bookUrl)!!.positionRevision)
    }

    @Test
    fun `declining a catch-up lets this device's next move overwrite the server`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val posts = movedElsewhere("epubcfi(/6/4!/4/2:3)", "epubcfi(/6/4!/4/2:9)")
        val offer = catchUp(context, agreement().catchUpOffer(context)!!)
        assertTrue(agreement().declineServerPlace(offer))
        assertFalse(agreement().state(context).serverMovedAway())
        assertNull(agreement().catchUpOffer(context))

        val mine = "epubcfi(/6/4!/4/2:12)"
        saveExact(context, mine, 11, progression = 0.71)
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(listOf(mine), posts())
    }

    @Test
    fun `reading on past a catch-up declines it in the same write`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val posts = movedElsewhere("epubcfi(/6/4!/4/2:3)", "epubcfi(/6/4!/4/2:9)")
        val offer = catchUp(context, agreement().catchUpOffer(context)!!)
        val mine = "epubcfi(/6/4!/4/2:12)"
        saveExact(context, mine, 11, progression = 0.71, declined = offer)
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(listOf(mine), posts())
    }

    @Test
    fun `a decline already applied is harmless when the next move carries it too`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val posts = movedElsewhere("epubcfi(/6/4!/4/2:3)", "epubcfi(/6/4!/4/2:9)")
        val offer = catchUp(context, agreement().catchUpOffer(context)!!)
        assertTrue(agreement().declineServerPlace(offer))
        val mine = "epubcfi(/6/4!/4/2:12)"
        saveExact(context, mine, 11, progression = 0.71, declined = offer)
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(listOf(mine), posts())
    }

    /**
     * This device agreed [first], read on to [mine] and sent it; [theirs]
     * was saved elsewhere between that POST and its read-back.
     */
    private suspend fun overwrittenInFlight(
        context: BookOrbitCfiContext, first: String, mine: String, theirs: String,
        intruder: String = saved(theirs, 70.0),
    ): () -> List<String> {
        saveExact(context, first, 10)
        agreement().observe(context, progress(saved(first)))
        saveExact(context, mine, 11, progression = 0.3)
        var remote = saved(first)
        val posts = mutableListOf<String>()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                when (request.method) {
                    "GET" -> MockResponse(body = remote)
                    "POST" -> {
                        val sent = JSONObject(request.body!!.utf8())
                        posts += sent.getString("cfi")
                        remote = if (posts.size == 1) intruder
                        else saved(sent.getString("cfi"), sent.getDouble("percentage"))
                        MockResponse(code = 201)
                    }
                    else -> error("Unexpected request")
                }
        }
        assertEquals(BookOrbitPositionExchange.Result.Conflict, exchange().run(context.bookUrl))
        assertEquals(BookOrbitAttempt.UNCERTAIN.name, agreement().state(context).attemptState)
        return { posts.toList() }
    }

    @Test
    fun `a place saved elsewhere during this device's send is offered and adopted in the reader`(): Unit =
        runBlocking {
            val context = repository.capture(binding.bookUrl)
            val mine = "epubcfi(/6/4!/4/2:5)"
            val theirs = "epubcfi(/6/4!/4/2:9)"
            val posts = overwrittenInFlight(context, "epubcfi(/6/4!/4/2:3)", mine, theirs)
            // Reading on does not replay the uncertain request.
            assertEquals(BookOrbitPositionExchange.Result.Conflict, exchange().run(context.bookUrl))
            assertTrue(agreement().state(context).serverMovedAway())

            val remote = agreement().catchUpOffer(context)!!
            assertEquals(theirs, remote.cfi)
            val offer = catchUp(context, remote)
            db.readingProgressDao().openBooks.enter(context.bookUrl)
            assertTrue(agreement().adoptCaughtUpInReader(offer))
            val row = agreement().state(context)
            assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name, row.attemptState)
            assertNull(row.outgoingBytes)
            assertEquals(theirs, row.agreedRemoteCfi)
            assertEquals(offer.locatorJson, db.readingProgressDao().get(context.bookUrl)!!.locatorJson)
            assertEquals(listOf(mine), posts())
        }

    @Test
    fun `declining a place saved during this device's send starts a new request`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val mine = "epubcfi(/6/4!/4/2:5)"
        val posts = overwrittenInFlight(context, "epubcfi(/6/4!/4/2:3)", mine, "epubcfi(/6/4!/4/2:9)")
        val uncertain = agreement().state(context)
        val offer = catchUp(context, agreement().catchUpOffer(context)!!)
        assertTrue(agreement().declineServerPlace(offer))
        val declined = agreement().state(context)
        assertNull(declined.attemptState)
        assertNull(declined.outgoingBytes)
        assertFalse(declined.serverMovedAway())

        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(listOf(mine, mine), posts())
        assertEquals(uncertain.attemptGeneration + 1, agreement().state(context).attemptGeneration)
    }

    @Test
    fun `reading on past a place saved during this device's send pushes the new page`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val mine = "epubcfi(/6/4!/4/2:5)"
        val posts = overwrittenInFlight(context, "epubcfi(/6/4!/4/2:3)", mine, "epubcfi(/6/4!/4/2:9)")
        val offer = catchUp(context, agreement().catchUpOffer(context)!!)
        val next = "epubcfi(/6/4!/4/2:7)"
        saveExact(context, next, 12, progression = 0.35, declined = offer)
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(listOf(mine, next), posts())
    }

    @Test
    fun `a place saved during this device's send is not declined once the server moved again`(): Unit =
        runBlocking {
            val context = repository.capture(binding.bookUrl)
            val posts = overwrittenInFlight(
                context, "epubcfi(/6/4!/4/2:3)", "epubcfi(/6/4!/4/2:5)", "epubcfi(/6/4!/4/2:9)",
            )
            val offer = catchUp(context, agreement().catchUpOffer(context)!!)
            serveRemote(saved("epubcfi(/6/4!/4/2:11)", 80.0))
            assertEquals(BookOrbitPositionExchange.Result.Conflict, exchange().run(context.bookUrl))
            assertFalse(agreement().declineServerPlace(offer))
            assertEquals(BookOrbitAttempt.UNCERTAIN.name, agreement().state(context).attemptState)
            db.readingProgressDao().openBooks.enter(context.bookUrl)
            assertFalse(agreement().adoptCaughtUpInReader(offer))
            assertEquals(1, posts().size)
        }

    @Test
    fun `a read-back that could not be made is never offered as someone else's place`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, first, 10)
        agreement().observe(context, progress(saved(first)))
        saveExact(context, "epubcfi(/6/4!/4/2:5)", 11, progression = 0.3)
        server.enqueue(MockResponse(body = saved(first)))
        agreement().prepare(context)
        server.enqueue(MockResponse(body = saved(first)))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(code = 503))
        assertThrows(RemoteHttpFailure::class.java) { runBlocking { agreement().send(context) } }
        val row = agreement().state(context)
        assertEquals(BookOrbitAttempt.UNCERTAIN.name, row.attemptState)
        assertFalse(row.serverMovedAway())
    }

    @Test
    fun `a place saved without a CFI while reading is offered and reading on from it pushes`(): Unit =
        runBlocking {
            val context = repository.capture(binding.bookUrl)
            val first = "epubcfi(/6/4!/4/2:3)"
            saveExact(context, first, 10)
            agreement().observe(context, progress(saved(first)))
            val posts = serveRemote(percentageOnly(70.0))
            saveExact(context, "epubcfi(/6/4!/4/2:5)", 11, progression = 0.3)
            assertEquals(BookOrbitPositionExchange.Result.Unresolved, exchange().run(context.bookUrl))
            assertTrue(agreement().state(context).serverMovedAway())

            val remote = agreement().catchUpOffer(context)!!
            assertNull(remote.cfi)
            val offer = agreement().approximateCatchUp(context, remote)!!
            assertEquals(0.7, offer.progression, 1e-9)
            // Followed, then the reader turns a page from there.
            val next = "epubcfi(/6/4!/4/2:12)"
            saveExact(context, next, 12, progression = 0.71, approximate = offer)
            assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
            assertEquals(listOf(next), posts())
        }

    @Test
    fun `declining a place saved without a CFI lets this device's place overwrite it`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, first, 10)
        agreement().observe(context, progress(saved(first)))
        val posts = serveRemote(percentageOnly(70.0))
        val mine = "epubcfi(/6/4!/4/2:5)"
        saveExact(context, mine, 11, progression = 0.3)
        assertEquals(BookOrbitPositionExchange.Result.Unresolved, exchange().run(context.bookUrl))
        val offer = catchUp(context, agreement().catchUpOffer(context)!!)
        assertTrue(agreement().declineServerPlace(offer))
        assertFalse(agreement().state(context).serverMovedAway())
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(listOf(mine), posts())
    }

    @Test
    fun `a place saved without a CFI never agreed with is offered instead of holding sync`(): Unit =
        runBlocking {
            val context = repository.capture(binding.bookUrl)
            val mine = "epubcfi(/6/4!/4/2:5)"
            saveExact(context, mine, 10, progression = 0.3)
            val posts = serveRemote(percentageOnly(40.0))
            assertEquals(BookOrbitPositionExchange.Result.Unresolved, exchange().run(context.bookUrl))
            assertTrue(agreement().state(context).serverMovedAway())
            val offer = catchUp(context, agreement().catchUpOffer(context)!!)
            saveExact(context, "epubcfi(/6/4!/4/2:7)", 11, progression = 0.35, declined = offer)
            assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
            assertEquals(listOf("epubcfi(/6/4!/4/2:7)"), posts())
        }

    @Test
    fun `a place saved without a CFI during this device's send is offered and followed`(): Unit =
        runBlocking {
            val context = repository.capture(binding.bookUrl)
            val mine = "epubcfi(/6/4!/4/2:5)"
            val posts = overwrittenInFlight(
                context, "epubcfi(/6/4!/4/2:3)", mine, "unused", intruder = percentageOnly(70.0),
            )
            assertTrue(agreement().state(context).serverMovedAway())
            val remote = agreement().catchUpOffer(context)!!
            assertNull(remote.cfi)
            val offer = agreement().approximateCatchUp(context, remote)!!
            val next = "epubcfi(/6/4!/4/2:12)"
            saveExact(context, next, 12, progression = 0.71, approximate = offer)
            val agreed = agreement().state(context)
            assertNull(agreed.attemptState)
            assertNull(agreed.outgoingBytes)
            assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
            assertEquals(listOf(mine, next), posts())
        }

    @Test
    fun `a place saved without a CFI is not agreed once the server moved again`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, first, 10)
        agreement().observe(context, progress(saved(first)))
        serveRemote(percentageOnly(70.0))
        saveExact(context, "epubcfi(/6/4!/4/2:5)", 11, progression = 0.3)
        val offer = agreement().approximateCatchUp(context, agreement().catchUpOffer(context)!!)!!
        val posts = serveRemote(percentageOnly(80.0))
        assertEquals(BookOrbitPositionExchange.Result.Unresolved, exchange().run(context.bookUrl))
        saveExact(context, "epubcfi(/6/4!/4/2:12)", 12, progression = 0.71, approximate = offer)
        assertEquals(BookOrbitPositionExchange.Result.Unresolved, exchange().run(context.bookUrl))
        assertEquals(emptyList<String>(), posts())
        assertTrue(agreement().state(context).serverMovedAway())
    }

    @Test
    fun `a place saved without a CFI cannot be adopted as an exact catch-up`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, first, 10)
        agreement().observe(context, progress(saved(first)))
        serveRemote(percentageOnly(70.0))
        val offer = catchUp(context, agreement().catchUpOffer(context)!!)
        db.readingProgressDao().openBooks.enter(context.bookUrl)
        assertFalse(agreement().adoptCaughtUpInReader(offer))
    }

    @Test
    fun `this device's own sent place is never offered back`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, first, 10)
        agreement().observe(context, progress(saved(first)))
        serveRemote(saved(first))
        saveExact(context, "epubcfi(/6/4!/4/2:5)", 11, progression = 0.3)
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertFalse(agreement().state(context).serverMovedAway())
        assertNull(agreement().catchUpOffer(context))
    }

    @Test
    fun `pull offer keyed on the position revision survives a status change`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        val next = "epubcfi(/6/4!/4/2:9)"
        saveExact(context, first, 10)
        val agreement = agreement()
        agreement.observe(context, BookOrbitFileProgress.parse(JSONObject(saved(first))))
        // Marking the book read moves local_revision on without touching the passage.
        db.readingProgressDao().setFinishedOverride(context.bookUrl, 1, "Finished", null, 20)
        val original = db.readingProgressDao().get(context.bookUrl)!!
        assertTrue(original.localRevision > original.positionRevision)
        val changed = BookOrbitFileProgress.parse(JSONObject(saved(next, 70.0)))
        assertTrue(agreement.canOfferPull(context, changed))
        agreement.observe(context, changed)
        // The reader builds its offer from the position revision, as the
        // adoption guard does; the diverged local_revision must not matter.
        val offer = BookOrbitPullOffer(
            context, changed, original.positionRevision, original.locatorJson,
            """{"href":"OPS/chapter.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.65}}""",
            "OPS/chapter.xhtml",
        )
        server.enqueue(MockResponse(body = saved(next, 70.0)))
        assertTrue(agreement.adoptVerifiedClosed(offer))
        server.takeRequest()
        val adopted = db.readingProgressDao().get(context.bookUrl)!!
        assertEquals(offer.locatorJson, adopted.locatorJson)
        assertEquals(original.positionRevision + 1, adopted.positionRevision)
        assertEquals("Finished", adopted.status)
        assertEquals(1, adopted.finishedOverride)
        assertEquals(original.statusRevision, adopted.statusRevision)
        assertEquals(adopted.positionRevision, agreement.state(context).agreedLocalRevision)
    }

    @Test
    fun `take-remote choice still applies after a status change diverged the counters`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val next = "epubcfi(/6/4!/4/2:9)"
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        db.readingProgressDao().setFinishedOverride(context.bookUrl, 1, "Finished", null, 20)
        val original = db.readingProgressDao().get(context.bookUrl)!!
        assertTrue(original.localRevision > original.positionRevision)
        server.enqueue(MockResponse(body = saved(next, 60.0)))
        val preview = checkNotNull(agreement().previewConflict(context))
        server.takeRequest()
        assertEquals(original.positionRevision, preview.localRevision)
        val offer = BookOrbitPullOffer(
            context, preview.remote, original.positionRevision, original.locatorJson,
            """{"href":"OPS/chapter.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.55}}""",
            "OPS/chapter.xhtml",
        )
        server.enqueue(MockResponse(body = saved(next, 60.0)))
        assertTrue(agreement().adoptChosenVerifiedClosed(preview, offer))
        server.takeRequest()
        val adopted = db.readingProgressDao().get(context.bookUrl)!!
        assertEquals(offer.locatorJson, adopted.locatorJson)
        assertEquals(original.positionRevision + 1, adopted.positionRevision)
        assertEquals(original.statusRevision, adopted.statusRevision)
        assertEquals(1, adopted.finishedOverride)
        assertEquals(next, agreement().state(context).agreedRemoteCfi)
    }

    @Test
    fun `explicit take-remote choice uses supplied offer only after closing`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        val next = "epubcfi(/6/4!/4/2:9)"
        saveExact(context, first, 10)
        val original = db.readingProgressDao().get(context.bookUrl)!!
        db.readingProgressDao().upsert(original.copy(pendingStatus = "Finished", pendingAccount = "peer"))
        server.enqueue(MockResponse(body = saved(next, 60.0)))
        val preview = checkNotNull(agreement().previewConflict(context))
        server.takeRequest()
        val offer = BookOrbitPullOffer(
            context, preview.remote, preview.localRevision, preview.localLocator,
            """{"href":"OPS/chapter.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.55}}""",
            "OPS/chapter.xhtml",
        )
        db.readingProgressDao().openBooks.enter(context.bookUrl)
        server.enqueue(MockResponse(body = saved(next, 60.0)))
        assertFalse(agreement().adoptChosenVerifiedClosed(preview, offer))
        server.takeRequest()
        db.readingProgressDao().openBooks.leave(context.bookUrl)
        server.enqueue(MockResponse(body = saved(next, 60.0)))
        assertTrue(agreement().adoptChosenVerifiedClosed(preview, offer))
        server.takeRequest()
        val adopted = db.readingProgressDao().get(context.bookUrl)!!
        assertEquals(offer.locatorJson, adopted.locatorJson)
        assertEquals(original.localRevision + 1, adopted.localRevision)
        assertEquals("Finished", adopted.pendingStatus)
        assertEquals(original.ackedRevision, adopted.ackedRevision)
        assertEquals(next, agreement().state(context).agreedRemoteCfi)
        assertEquals(next, db.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)!!.rawCfi)
    }

    @Test
    fun `take-remote choice applies only in the reader that holds the book open`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val next = "epubcfi(/6/4!/4/2:9)"
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        val original = db.readingProgressDao().get(context.bookUrl)!!
        server.enqueue(MockResponse(body = saved(next, 60.0)))
        val preview = checkNotNull(agreement().previewConflict(context))
        server.takeRequest()
        val offer = BookOrbitPullOffer(
            context, preview.remote, preview.localRevision, preview.localLocator,
            """{"href":"OPS/chapter.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.55}}""",
            "OPS/chapter.xhtml",
        )
        server.enqueue(MockResponse(body = saved(next, 60.0)))
        assertFalse(agreement().adoptChosenVerifiedInReader(preview, offer))
        server.takeRequest()
        assertEquals(original.localRevision, db.readingProgressDao().get(context.bookUrl)!!.localRevision)
        db.readingProgressDao().openBooks.enter(context.bookUrl)
        server.enqueue(MockResponse(body = saved(next, 60.0)))
        assertTrue(agreement().adoptChosenVerifiedInReader(preview, offer))
        server.takeRequest()
        db.readingProgressDao().openBooks.leave(context.bookUrl)
        val adopted = db.readingProgressDao().get(context.bookUrl)!!
        assertEquals(offer.locatorJson, adopted.locatorJson)
        assertEquals(original.positionRevision + 1, adopted.positionRevision)
        val agreed = agreement().state(context)
        assertEquals(adopted.positionRevision, agreed.agreedLocalRevision)
        assertEquals(next, agreed.agreedRemoteCfi)
        assertEquals(next, db.bookOrbitLocalCfiDao().get(context.request.accountKey, context.bookUrl)!!.rawCfi)
    }

    @Test
    fun `keep-local choice is sent only from the reader that holds the book open`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val localCfi = "epubcfi(/6/4!/4/2:3)"
        val remoteCfi = "epubcfi(/6/4!/4/2:9)"
        saveExact(context, localCfi, 10)
        val local = db.readingProgressDao().get(context.bookUrl)!!
        val choice = exchange()
        server.enqueue(MockResponse(body = saved(remoteCfi, 60.0)))
        val preview = checkNotNull(choice.previewConflict(context.bookUrl))
        server.takeRequest()
        server.enqueue(MockResponse(body = saved(remoteCfi, 60.0)))
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { choice.keepLocal(preview, inReader = true) }
        }
        server.takeRequest()
        assertNull(agreement().state(context).outgoingBytes)
        db.readingProgressDao().openBooks.enter(context.bookUrl)
        server.enqueue(MockResponse(body = saved(remoteCfi, 60.0)))
        server.enqueue(MockResponse(body = saved(remoteCfi, 60.0)))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(body = saved(localCfi, 25.0)))
        assertEquals(BookOrbitPositionExchange.Result.Pushed, choice.keepLocal(preview, inReader = true))
        db.readingProgressDao().openBooks.leave(context.bookUrl)
        val requests = (1..4).map { server.takeRequest() }
        assertEquals(listOf("GET", "GET", "POST", "GET"), requests.map { it.method })
        assertEquals(localCfi, JSONObject(requests[2].body!!.utf8()).getString("cfi"))
        val agreed = agreement().state(context)
        assertEquals(local.positionRevision, agreed.agreedLocalRevision)
        assertEquals(localCfi, agreed.agreedRemoteCfi)
        assertNull(agreed.outgoingBytes)
    }

    @Test
    fun `stale choice cannot adopt a changed remote or local place`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        val next = "epubcfi(/6/4!/4/2:9)"
        server.enqueue(MockResponse(body = saved(next, 60.0)))
        val preview = checkNotNull(agreement().previewConflict(context))
        server.takeRequest()
        val offer = BookOrbitPullOffer(
            context, preview.remote, preview.localRevision, preview.localLocator,
            """{"href":"OPS/chapter.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.55}}""",
            "OPS/chapter.xhtml",
        )
        val newer = JSONObject(saved(next, 60.0))
            .put("lastReadAt", "2026-09-22T11:01:00.000Z").toString()
        server.enqueue(MockResponse(body = newer))
        assertFalse(agreement().adoptChosenVerifiedClosed(preview, offer))
        server.takeRequest()
        assertEquals(preview.localRevision, db.readingProgressDao().get(context.bookUrl)!!.localRevision)
        saveExact(context, "epubcfi(/6/4!/4/2:5)", 11)
        server.enqueue(MockResponse(body = saved(next, 60.0)))
        assertFalse(agreement().adoptChosenVerifiedClosed(preview, offer))
        server.takeRequest()
        assertEquals(preview.localRevision + 1, db.readingProgressDao().get(context.bookUrl)!!.localRevision)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `take-remote choice refuses another owner and a switched selected file`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        val next = "epubcfi(/6/4!/4/2:9)"
        server.enqueue(MockResponse(body = saved(next, 60.0)))
        val preview = checkNotNull(agreement().previewConflict(context))
        server.takeRequest()
        val offer = BookOrbitPullOffer(
            context, preview.remote, preview.localRevision, preview.localLocator,
            """{"href":"OPS/chapter.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.55}}""",
            "OPS/chapter.xhtml",
        )
        val local = db.readingProgressDao().get(context.bookUrl)!!
        db.readingProgressDao().upsert(local.copy(ownerAccount = "another-account"))
        server.enqueue(MockResponse(body = saved(next, 60.0)))
        assertFalse(agreement().adoptChosenVerifiedClosed(preview, offer))
        server.takeRequest()
        db.bookOrbitBindingDao().write(binding.copy(fileId = 35, revision = 3))
        assertThrows(BookOrbitIdentityChanged::class.java) {
            runBlocking { agreement().adoptChosenVerifiedClosed(preview, offer) }
        }
        assertEquals(2, server.requestCount)
        assertEquals(local.localRevision, db.readingProgressDao().get(context.bookUrl)!!.localRevision)
    }

    @Test
    fun `changed remote or local revision cannot overwrite a pending local place`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        val next = "epubcfi(/6/4!/4/2:9)"
        saveExact(context, first, 10)
        val agreement = agreement()
        agreement.observe(context, BookOrbitFileProgress.parse(JSONObject(saved(first))))
        val original = db.readingProgressDao().get(context.bookUrl)!!
        val remote = BookOrbitFileProgress.parse(JSONObject(saved(next, 70.0)))
        agreement.observe(context, remote)
        val offer = BookOrbitPullOffer(
            context, remote, original.localRevision, original.locatorJson,
            """{"href":"OPS/chapter.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.7}}""",
            "OPS/chapter.xhtml",
        )
        server.enqueue(MockResponse(body = saved("epubcfi(/6/4!/4/2:10)", 71.0)))
        assertFalse(agreement.adoptVerifiedClosed(offer))
        server.takeRequest()
        assertEquals(original.locatorJson, db.readingProgressDao().get(context.bookUrl)!!.locatorJson)

        agreement.observe(context, remote)
        db.readingProgressDao().upsert(original.copy(ownerAccount = "other-account"))
        assertFalse(agreement.canOfferPull(context, remote))
        server.enqueue(MockResponse(body = saved(next, 70.0)))
        assertFalse(agreement.adoptVerifiedClosed(offer))
        server.takeRequest()
        db.readingProgressDao().upsert(original)
        saveExact(context, "epubcfi(/6/4!/4/2:5)", 11)
        assertFalse(agreement.canOfferPull(context, remote))
        server.enqueue(MockResponse(body = saved(next, 70.0)))
        assertFalse(agreement.adoptVerifiedClosed(offer))
        server.takeRequest()
        assertEquals(2L, db.readingProgressDao().get(context.bookUrl)!!.localRevision)
        assertEquals(first, agreement.state(context).agreedRemoteCfi)
        assertEquals(3, server.requestCount)

        db.bookOrbitBindingDao().write(binding.copy(fileId = 35, revision = 3))
        assertThrows(BookOrbitIdentityChanged::class.java) {
            runBlocking { agreement.adoptVerifiedClosed(offer) }
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `selected file changing between preparation and delivery prevents POST`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement().prepare(context)
        server.takeRequest()
        val changed = saved("epubcfi(/6/4!/4/2:9)")
        server.enqueue(MockResponse(body = changed))
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { agreement().send(context) }
        }
        assertEquals("GET", server.takeRequest().method)
        assertEquals(2, server.requestCount)
        val row = agreement().state(context)
        assertNull(row.outgoingBytes)
        assertNull(row.attemptState)
        assertEquals("epubcfi(/6/4!/4/2:9)", row.candidateCfi)
    }

    @Test
    fun `reader movement during final preflight cannot deliver old position`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement().prepare(context)
        server.takeRequest()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                runBlocking { saveExact(context, "epubcfi(/6/4!/4/2:4)", 11) }
                return MockResponse(body = fixture("progress-unopened.json"))
            }
        }
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { agreement().send(context) }
        }
        assertEquals("GET", server.takeRequest().method)
        assertEquals(2, server.requestCount)
        assertEquals(BookOrbitAttempt.PREPARED.name, agreement().state(context).attemptState)
        agreement().discardStalePreparation(context)
        assertNull(agreement().state(context).outgoingBytes)
    }

    @Test
    fun `exchange pushes selected exact revision and recovers uncertain POST without replay`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, cfi, 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(code = 503))
        assertThrows(RemoteHttpFailure::class.java) {
            runBlocking { exchange().run(context.bookUrl) }
        }
        assertEquals(listOf("GET", "GET", "GET", "POST", "GET"),
            (1..5).map { server.takeRequest().method })
        assertEquals(BookOrbitAttempt.UNCERTAIN.name, agreement().state(context).attemptState)
        server.enqueue(MockResponse(body = saved(cfi)))
        assertEquals(BookOrbitPositionExchange.Result.Recovered, exchange().run(context.bookUrl))
        assertEquals("GET", server.takeRequest().method)
        assertEquals(6, server.requestCount)
        assertEquals(1L, agreement().state(context).agreedLocalRevision)
    }

    @Test
    fun `server applying POST then dropping response is recovered without another POST`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, cfi, 10)
        var remote = fixture("progress-unopened.json")
        var posts = 0
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                when (request.method) {
                    "GET" -> MockResponse(body = remote)
                    "POST" -> {
                        posts++
                        remote = saved(cfi)
                        MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
                    }
                    else -> error("Unexpected request")
                }
        }
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name, agreement().state(context).attemptState)
        assertEquals(1, posts)
        assertEquals(listOf("GET", "GET", "GET", "POST", "GET"),
            (1..5).map { server.takeRequest().method })
    }

    @Test
    fun `retry-after zero is followed by selected-file read-back rather than another POST`(): Unit =
        runBlocking {
            val context = repository.capture(binding.bookUrl)
            val cfi = "epubcfi(/6/4!/4/2:3)"
            saveExact(context, cfi, 10)
            repeat(3) { server.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
            server.enqueue(MockResponse(code = 503, headers = okhttp3.Headers.headersOf("Retry-After", "0")))
            repeat(2) { server.enqueue(MockResponse(body = saved(cfi, 25.0))) }
            assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
            assertEquals(5, server.requestCount)
            assertEquals(listOf("GET", "GET", "GET", "POST", "GET"),
                (1..5).map { server.takeRequest().method })
            assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name, agreement().state(context).attemptState)
        }

    @Test
    fun `approved last-server-write-wins permits a lower position after a post-preflight competitor`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val localCfi = "epubcfi(/6/4!/4/2:3)"
        val otherCfi = "epubcfi(/6/4!/4/2:9)"
        saveExact(context, localCfi, 10)
        var remote = fixture("progress-unopened.json")
        var reads = 0
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                when (request.method) {
                    "GET" -> {
                        reads++
                        val answer = remote
                        if (reads == 3) remote = saved(otherCfi, 70.0)
                        MockResponse(body = answer)
                    }
                    "POST" -> {
                        remote = saved(localCfi, 25.0)
                        MockResponse(code = 201)
                    }
                    else -> error("Unexpected request")
                }
        }
        assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().run(context.bookUrl))
        assertEquals(4, reads)
        assertEquals(localCfi, BookOrbitFileProgress.parse(org.json.JSONObject(remote)).cfi)
        assertEquals(25.0, BookOrbitFileProgress.parse(org.json.JSONObject(remote)).percentage, 0.0)
        val agreed = agreement().state(context)
        assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name, agreed.attemptState)
        assertEquals(localCfi, agreed.agreedRemoteCfi)
        assertEquals(db.readingProgressDao().get(context.bookUrl)!!.localRevision, agreed.agreedLocalRevision)
        assertEquals(listOf("GET", "GET", "GET", "POST", "GET"),
            (1..5).map { server.takeRequest().method })
    }

    @Test
    fun `exchange retains unknown remote position without POST or status acknowledgement`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        val local = db.readingProgressDao().get(context.bookUrl)!!
        db.readingProgressDao().upsert(local.copy(
            pendingStatus = "Finished", pendingAccount = "another-status-peer",
        ))
        server.enqueue(MockResponse(body = saved("epubcfi(/6/4!/4/2:9)")))
        assertEquals(BookOrbitPositionExchange.Result.Unresolved, exchange().run(context.bookUrl))
        assertEquals("GET", server.takeRequest().method)
        assertEquals(1, server.requestCount)
        assertEquals("epubcfi(/6/4!/4/2:9)", agreement().state(context).candidateCfi)
        assertEquals("Finished", db.readingProgressDao().get(context.bookUrl)!!.pendingStatus)
    }

    @Test
    fun `explicit keep-local choice requires a matching preview and read-back`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val localCfi = "epubcfi(/6/4!/4/2:3)"
        val remoteCfi = "epubcfi(/6/4!/4/2:9)"
        saveExact(context, localCfi, 10)
        val local = db.readingProgressDao().get(context.bookUrl)!!
        db.readingProgressDao().upsert(local.copy(pendingStatus = "Finished", pendingAccount = "peer"))
        val choice = exchange()
        server.enqueue(MockResponse(body = saved(remoteCfi, 60.0)))
        val preview = checkNotNull(choice.previewConflict(context.bookUrl))
        assertEquals("GET", server.takeRequest().method)
        server.enqueue(MockResponse(body = saved(remoteCfi, 60.0)))
        server.enqueue(MockResponse(body = saved(remoteCfi, 60.0)))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(body = saved(localCfi, 25.0)))
        assertEquals(BookOrbitPositionExchange.Result.Pushed, choice.keepLocal(preview))
        val requests = (1..4).map { server.takeRequest() }
        assertEquals(listOf("GET", "GET", "POST", "GET"), requests.map { it.method })
        assertEquals(localCfi, JSONObject(requests[2].body!!.utf8()).getString("cfi"))
        val agreed = agreement().state(context)
        assertEquals(local.localRevision, agreed.agreedLocalRevision)
        assertEquals(localCfi, agreed.agreedRemoteCfi)
        assertNull(agreed.outgoingBytes)
        assertEquals("Finished", db.readingProgressDao().get(context.bookUrl)!!.pendingStatus)
    }

    @Test
    fun `concurrent local and remote movement needs explicit choice`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        val here = "epubcfi(/6/4!/4/2:5)"
        val there = "epubcfi(/6/4!/4/2:9)"
        saveExact(context, first, 10)
        agreement().observe(context, BookOrbitFileProgress.parse(JSONObject(saved(first))))
        saveExact(context, here, 11)
        server.enqueue(MockResponse(body = saved(there, 60.0)))
        assertEquals(BookOrbitPositionExchange.Result.Conflict, exchange().run(context.bookUrl))
        assertEquals("GET", server.takeRequest().method)
        assertEquals(1, server.requestCount)

        val choice = exchange()
        server.enqueue(MockResponse(body = saved(there, 60.0)))
        val preview = checkNotNull(choice.previewConflict(context.bookUrl))
        server.takeRequest()
        server.enqueue(MockResponse(body = saved(there, 60.0)))
        server.enqueue(MockResponse(body = saved(there, 60.0)))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(body = saved(here)))
        assertEquals(BookOrbitPositionExchange.Result.Pushed, choice.keepLocal(preview))
        assertEquals(listOf("GET", "GET", "POST", "GET"),
            (1..4).map { server.takeRequest().method })
        assertEquals(here, agreement().state(context).agreedRemoteCfi)
    }

    @Test
    fun `changed answer or local place supersedes keep-local choice before POST`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        val choice = exchange()
        val remoteCfi = "epubcfi(/6/4!/4/2:9)"
        server.enqueue(MockResponse(body = saved(remoteCfi, 60.0)))
        val preview = checkNotNull(choice.previewConflict(context.bookUrl))
        server.takeRequest()
        server.enqueue(MockResponse(body = saved(remoteCfi, 61.0)))
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { choice.keepLocal(preview) }
        }
        assertEquals("GET", server.takeRequest().method)
        assertEquals(2, server.requestCount)
        assertNull(agreement().state(context).outgoingBytes)

        saveExact(context, "epubcfi(/6/4!/4/2:4)", 11)
        server.enqueue(MockResponse(body = saved(remoteCfi, 60.0)))
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { choice.keepLocal(preview) }
        }
        assertEquals("GET", server.takeRequest().method)
        assertNull(agreement().state(context).outgoingBytes)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `remote changing at final preflight prevents explicit overwrite`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        val remote = saved("epubcfi(/6/4!/4/2:9)", 60.0)
        val choice = exchange()
        server.enqueue(MockResponse(body = remote))
        val preview = checkNotNull(choice.previewConflict(context.bookUrl))
        server.takeRequest()
        server.enqueue(MockResponse(body = remote))
        server.enqueue(MockResponse(body = saved("epubcfi(/6/4!/4/2:10)", 61.0)))
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { choice.keepLocal(preview) }
        }
        assertEquals(listOf("GET", "GET"), (1..2).map { server.takeRequest().method })
        assertEquals(3, server.requestCount)
        assertNull(agreement().state(context).outgoingBytes)
    }

    @Test
    fun `remote reading timestamp changing at final preflight refuses choice`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        val remote = saved("epubcfi(/6/4!/4/2:9)", 60.0)
        val choice = exchange()
        server.enqueue(MockResponse(body = remote))
        val preview = checkNotNull(choice.previewConflict(context.bookUrl))
        server.takeRequest()
        server.enqueue(MockResponse(body = remote))
        val changed = JSONObject(remote).put("lastReadAt", "2026-09-22T11:01:00.000Z").toString()
        server.enqueue(MockResponse(body = changed))
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { choice.keepLocal(preview) }
        }
        assertEquals(listOf("GET", "GET"), (1..2).map { server.takeRequest().method })
        assertEquals(3, server.requestCount)
        assertNull(agreement().state(context).outgoingBytes)
    }

    @Test
    fun `open book and another account cannot authorize keep-local delivery`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        val remote = saved("epubcfi(/6/4!/4/2:9)")
        val choice = exchange()
        server.enqueue(MockResponse(body = remote))
        val preview = checkNotNull(choice.previewConflict(context.bookUrl))
        server.takeRequest()
        db.readingProgressDao().openBooks.enter(context.bookUrl)
        server.enqueue(MockResponse(body = remote))
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { choice.keepLocal(preview) }
        }
        server.takeRequest()
        db.readingProgressDao().openBooks.leave(context.bookUrl)
        val local = db.readingProgressDao().get(context.bookUrl)!!
        db.readingProgressDao().upsert(local.copy(ownerAccount = "another-account"))
        server.enqueue(MockResponse(body = remote))
        assertNull(choice.previewConflict(context.bookUrl))
        server.takeRequest()
        server.enqueue(MockResponse(body = remote))
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { choice.keepLocal(preview) }
        }
        server.takeRequest()
        assertEquals(4, server.requestCount)
        assertNull(agreement().state(context).outgoingBytes)
    }

    @Test
    fun `a book held open does not stop its place being sent`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, cfi, 10, progression = 0.25)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val prepared = agreement().prepare(context)
        server.takeRequest()
        db.readingProgressDao().openBooks.enter(context.bookUrl)
        try {
            server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
            server.enqueue(MockResponse(code = 201))
            server.enqueue(MockResponse(body = saved(cfi, 25.0)))
            assertEquals(BookOrbitReadBack.Agreed, agreement().send(context))
            assertEquals("GET", server.takeRequest().method)
            assertArrayEquals(prepared.outgoingBytes, checkNotNull(server.takeRequest().body).toByteArray())
            assertEquals("GET", server.takeRequest().method)
            val row = agreement().state(context)
            assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name, row.attemptState)
            assertEquals(prepared.sentLocalRevision, row.agreedLocalRevision)
        } finally {
            db.readingProgressDao().openBooks.leave(context.bookUrl)
        }
    }

    @Test
    fun `changed local percentage with unchanged revision cannot send prepared bytes`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement().prepare(context)
        server.takeRequest()
        val local = db.readingProgressDao().get(context.bookUrl)!!
        db.readingProgressDao().upsert(local.copy(totalProgression = 0.5))
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { agreement().send(context) }
        }
        assertEquals(1, server.requestCount)
        assertEquals(BookOrbitAttempt.PREPARED.name, agreement().state(context).attemptState)
        agreement().discardStalePreparation(context)
        assertNull(agreement().state(context).outgoingBytes)
    }

    @Test
    fun `outgoing exchange cannot claim another account's locally owned place`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        val local = db.readingProgressDao().get(context.bookUrl)!!
        db.readingProgressDao().upsert(local.copy(ownerAccount = "another-account"))
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        assertEquals(BookOrbitPositionExchange.Result.Unresolved, exchange().run(context.bookUrl))
        assertEquals("GET", server.takeRequest().method)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { agreement().prepare(context) }
        }
        assertEquals("GET", server.takeRequest().method)
        assertEquals(2, server.requestCount)
        assertNull(agreement().state(context).outgoingBytes)
    }

    @Test
    fun `rejected position POST is never automatically replayed by a later exchange`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        repeat(3) { server.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
        server.enqueue(MockResponse(code = 401))
        assertEquals(BookOrbitPositionExchange.Result.Rejected(401), exchange().run(context.bookUrl))
        assertEquals(listOf("GET", "GET", "GET", "POST"),
            (1..4).map { server.takeRequest().method })
        assertEquals(BookOrbitAttempt.REJECTED.name, agreement().state(context).attemptState)
        assertEquals(BookOrbitPositionExchange.Result.Unresolved, exchange().run(context.bookUrl))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `reader opening recovers pending sent bytes after reopen without POST or local rewrite`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        for (attempt in listOf(
            BookOrbitAttempt.MAY_HAVE_BEEN_SENT, BookOrbitAttempt.UNCERTAIN,
            BookOrbitAttempt.RETRY_REQUIRED,
        )) {
            db.bookOrbitPositionAgreementDao().clearAccount(account.accountKey)
            saveExact(context, cfi, 10)
            server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
            val prepared = agreement().prepare(context)
            assertEquals("GET", server.takeRequest().method)
            db.bookOrbitPositionAgreementDao().write(prepared.copy(attemptState = attempt.name))
            db.close()
            db = open()
            repository = BookOrbitCfiRepository(db)
            saveExact(context, "epubcfi(/6/4!/4/2:7)", 20)
            val local = db.readingProgressDao().get(context.bookUrl)
            server.enqueue(MockResponse(body = saved(cfi, 25.0)))
            assertEquals(BookOrbitReadBack.Agreed, agreement().readBackIfPending(context))
            assertEquals("GET", server.takeRequest().method)
            assertEquals(local, db.readingProgressDao().get(context.bookUrl))
            assertEquals(prepared.sentLocalRevision, agreement().state(context).agreedLocalRevision)
            assertNull(agreement().state(context).outgoingBytes)
            assertNull(agreement().readBackIfPending(context))
        }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `reader opening leaves unsent rejected and unknown attempts untouched`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        assertNull(agreement().readBackIfPending(context))
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val prepared = agreement().prepare(context)
        assertEquals("GET", server.takeRequest().method)
        for (attempt in listOf(
            BookOrbitAttempt.PREPARED.name, BookOrbitAttempt.REJECTED.name, "future-attempt",
        )) {
            val row = prepared.copy(attemptState = attempt)
            db.bookOrbitPositionAgreementDao().write(row)
            assertNull(agreement().readBackIfPending(context))
            val unchanged = agreement().state(context)
            assertEquals(attempt, unchanged.attemptState)
            assertArrayEquals(row.outgoingBytes, unchanged.outgoingBytes)
            assertEquals(row.attemptGeneration, unchanged.attemptGeneration)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `prepared bytes survive reopen and read back acknowledges only sent revision`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, cfi, 10)
        val localBefore = db.readingProgressDao().get(context.bookUrl)!!
        db.readingProgressDao().upsert(localBefore.copy(
            pendingStatus = "Finished", pendingAccount = "another-status-peer",
        ))
        val sync = agreement()
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val prepared = sync.prepare(context)
        assertEquals(BookOrbitAttempt.PREPARED.name, prepared.attemptState)
        assertEquals("GET", server.takeRequest().method)
        val bytes = checkNotNull(prepared.outgoingBytes).clone()
        assertEquals(25.0, JSONObject(String(bytes)).getDouble("percentage"), 0.0)
        assertEquals(cfi, JSONObject(String(bytes)).getString("cfi"))

        db.close()
        db = open()
        repository = BookOrbitCfiRepository(db)
        assertArrayEquals(bytes, db.bookOrbitPositionAgreementDao().get(context.request.accountKey, context.bookUrl)!!.outgoingBytes)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(body = saved(cfi, 25.0)))
        assertEquals(BookOrbitReadBack.Agreed, agreement().send(context))
        assertEquals("GET", server.takeRequest().method)
        val posted = server.takeRequest()
        assertEquals("POST", posted.method)
        assertArrayEquals(bytes, checkNotNull(posted.body).toByteArray())
        assertEquals("GET", server.takeRequest().method)
        val row = db.bookOrbitPositionAgreementDao().get(context.request.accountKey, context.bookUrl)!!
        assertEquals(prepared.sentLocalRevision, row.agreedLocalRevision)
        assertNull(row.outgoingBytes)
        assertEquals("Reading", db.readingProgressDao().get(context.bookUrl)!!.status)
        assertNull(db.readingProgressDao().get(context.bookUrl)!!.agreedStatus)
        assertEquals("Finished", db.readingProgressDao().get(context.bookUrl)!!.pendingStatus)
        assertEquals("another-status-peer", db.readingProgressDao().get(context.bookUrl)!!.pendingAccount)
        assertNull(db.bookOrbitCfiDao().get(context.request.accountKey, context.bookUrl))
    }

    @Test
    fun `POST percentage survives BookOrbit real column round trip exactly`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, cfi, 10, progression = 0.02991799657)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val prepared = agreement().prepare(context)
        server.takeRequest()
        val sent = JSONObject(String(checkNotNull(prepared.outgoingBytes))).getDouble("percentage")
        assertEquals(2.9917996, sent, 0.0)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(body = saved(cfi, 2.9917996)))
        assertEquals(BookOrbitReadBack.Agreed, agreement().send(context))
        server.takeRequest()
        val posted = server.takeRequest()
        assertArrayEquals(prepared.outgoingBytes, checkNotNull(posted.body).toByteArray())
        server.takeRequest()
        assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name, agreement().state(context).attemptState)
    }

    @Test
    fun `lost POST remains blocked until explicit retry even after restart and local movement`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val prepared = agreement().prepare(context)
        server.takeRequest()
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        assertEquals(BookOrbitReadBack.ExplicitRetryRequired, agreement().send(context))
        assertEquals("GET", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(4, server.requestCount)
        assertEquals(BookOrbitAttempt.RETRY_REQUIRED.name, agreement().state(context).attemptState)
        assertArrayEquals(prepared.outgoingBytes, agreement().state(context).outgoingBytes)
        db.close()
        db = open()
        repository = BookOrbitCfiRepository(db)
        saveExact(context, "epubcfi(/6/4!/4/2:7)", 20)
        repeat(2) {
            server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
            assertEquals(BookOrbitPositionExchange.Result.RetryAfterReadBack, exchange().run(context.bookUrl))
            assertEquals("GET", server.takeRequest().method)
            assertArrayEquals(prepared.outgoingBytes, agreement().state(context).outgoingBytes)
        }
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { agreement().prepare(context) }
        }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `blocked requests can only be sent again by a fresh explicit choice`(): Unit = runBlocking {
        val cfi = "epubcfi(/6/4!/4/2:3)"
        for (attempt in listOf(
            BookOrbitAttempt.RETRY_REQUIRED, BookOrbitAttempt.UNCERTAIN,
            BookOrbitAttempt.MAY_HAVE_BEEN_SENT, BookOrbitAttempt.REJECTED,
        )) {
            db.bookOrbitPositionAgreementDao().clearAccount(account.accountKey)
            val context = repository.capture(binding.bookUrl)
            saveExact(context, cfi, 10)
            server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
            val pending = agreement().prepare(context).copy(attemptState = attempt.name)
            db.bookOrbitPositionAgreementDao().write(pending)
            server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
            val preview = checkNotNull(agreement().previewConflict(context))
            assertTrue(preview.retryRequired)
            assertArrayEquals(pending.outgoingBytes, agreement().state(context).outgoingBytes)
            repeat(2) { server.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
            server.enqueue(MockResponse(code = 201))
            server.enqueue(MockResponse(body = saved(cfi)))
            assertEquals(BookOrbitPositionExchange.Result.Pushed, exchange().keepLocal(preview))
            assertEquals(listOf("GET", "GET", "GET", "GET", "POST", "GET"),
                (1..6).map { server.takeRequest().method })
            assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name, agreement().state(context).attemptState)
        }
        assertEquals(24, server.requestCount)
    }

    @Test
    fun `checked take-remote settles uncertain bytes without another POST`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val pending = agreement().prepare(context).copy(attemptState = BookOrbitAttempt.UNCERTAIN.name)
        db.bookOrbitPositionAgreementDao().write(pending)
        val remote = saved("epubcfi(/6/4!/4/2:9)", 70.0)
        server.enqueue(MockResponse(body = remote))
        val preview = checkNotNull(agreement().previewConflict(context))
        val locator = """{"href":"OPS/chapter.xhtml","type":"application/xhtml+xml","locations":{"progression":0.7,"totalProgression":0.7}}"""
        val offer = BookOrbitPullOffer(
            context, preview.remote, preview.localRevision, preview.localLocator, locator, "OPS/chapter.xhtml",
        )
        server.enqueue(MockResponse(body = remote))
        assertTrue(agreement().adoptChosenVerifiedClosed(preview, offer))
        val row = agreement().state(context)
        assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name, row.attemptState)
        assertNull(row.outgoingBytes)
        assertNull(row.sentLocalRevision)
        assertEquals(preview.localRevision + 1, row.agreedLocalRevision)
        assertEquals(locator, db.readingProgressDao().get(context.bookUrl)!!.locatorJson)
        assertEquals("Reading", db.readingProgressDao().get(context.bookUrl)!!.status)
        assertEquals(listOf("GET", "GET", "GET"), (1..3).map { server.takeRequest().method })
    }

    @Test
    fun `changed pending bytes or state supersede retry without discarding evidence`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val pending = agreement().prepare(context).copy(attemptState = BookOrbitAttempt.RETRY_REQUIRED.name)
        db.bookOrbitPositionAgreementDao().write(pending)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val preview = checkNotNull(agreement().previewConflict(context))
        val baseline = agreement().state(context)
        for (changed in listOf(
            baseline.copy(outgoingBytes = checkNotNull(baseline.outgoingBytes) + byteArrayOf(32)),
            baseline.copy(attemptState = BookOrbitAttempt.UNCERTAIN.name),
            baseline.copy(sentLocalRevision = checkNotNull(baseline.sentLocalRevision) + 1),
        )) {
            db.bookOrbitPositionAgreementDao().write(changed)
            server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
            assertThrows(BookOrbitPositionUnresolved::class.java) {
                runBlocking { exchange().keepLocal(preview) }
            }
            assertArrayEquals(changed.outgoingBytes, agreement().state(context).outgoingBytes)
            assertEquals(changed.attemptState, agreement().state(context).attemptState)
        }
        assertEquals(5, server.requestCount)
        repeat(5) { assertEquals("GET", server.takeRequest().method) }
    }

    @Test
    fun `an old choice cannot authorize a second byte-identical uncertain retry`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val pending = agreement().prepare(context).copy(attemptState = BookOrbitAttempt.RETRY_REQUIRED.name)
        db.bookOrbitPositionAgreementDao().write(pending)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val preview = checkNotNull(agreement().previewConflict(context))
        repeat(2) { server.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
        server.enqueue(MockResponse(code = 503))
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        assertEquals(BookOrbitPositionExchange.Result.RetryAfterReadBack, exchange().keepLocal(preview))
        val next = agreement().state(context)
        assertEquals(BookOrbitAttempt.RETRY_REQUIRED.name, next.attemptState)
        assertArrayEquals(pending.outgoingBytes, next.outgoingBytes)
        assertEquals(pending.attemptGeneration + 1, next.attemptGeneration)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { exchange().keepLocal(preview) }
        }
        assertEquals(7, server.requestCount)
        assertEquals(1, (1..7).count { server.takeRequest().method == "POST" })
    }

    @Test
    fun `explicit choice waits for older read-back across repository instances`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, cfi, 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val pending = agreement().prepare(context).copy(attemptState = BookOrbitAttempt.UNCERTAIN.name)
        db.bookOrbitPositionAgreementDao().write(pending)
        server.enqueue(MockResponse(body = saved("epubcfi(/6/4!/4/2:9)")))
        val preview = checkNotNull(agreement().previewConflict(context))
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                return MockResponse(body = saved(cfi))
            }
        }
        val recovery = async { agreement().readBack(context) }
        withTimeout(5_000) { entered.await() }
        val choice = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                agreement().keepLocal(preview)
                false
            } catch (_: BookOrbitPositionUnresolved) {
                true
            }
        }
        try {
            assertFalse(choice.isCompleted)
            assertEquals(3, server.requestCount)
        } finally {
            release.countDown()
        }
        assertEquals(BookOrbitReadBack.Agreed, recovery.await())
        assertTrue(choice.await())
        assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name, agreement().state(context).attemptState)
        assertEquals(4, server.requestCount)
        repeat(4) { assertEquals("GET", server.takeRequest().method) }
    }

    @Test
    fun `different remote anchor at same percentage keeps request and conflict`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement().prepare(context)
        server.takeRequest()
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(body = saved("epubcfi(/6/4!/4/2:4)")))
        assertEquals(BookOrbitReadBack.Conflict, agreement().send(context))
        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
        val row = db.bookOrbitPositionAgreementDao().get(context.request.accountKey, context.bookUrl)!!
        assertEquals(BookOrbitAttempt.UNCERTAIN.name, row.attemptState)
        assertNotNull(row.outgoingBytes)
        assertEquals("epubcfi(/6/4!/4/2:4)", row.candidateCfi)
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { agreement().prepare(context) }
        }
    }

    @Test
    fun `same remote anchor with different percentage cannot acknowledge sent bytes`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, cfi, 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement().prepare(context)
        server.takeRequest()
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(body = saved(cfi, 25.01)))
        assertEquals(BookOrbitReadBack.Conflict, agreement().send(context))
        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
        val row = db.bookOrbitPositionAgreementDao().get(context.request.accountKey, context.bookUrl)!!
        assertNull(row.agreedLocalRevision)
        assertNotNull(row.outgoingBytes)
    }

    @Test
    fun `changed preflight percentage with unchanged CFI does not authorize uncertain retry`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val first = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, first, 10)
        assertEquals(
            com.chmouel.liseur.domain.ExactPositionDecision.Settled,
            agreement().observe(context, BookOrbitFileProgress.parse(JSONObject(saved(first)))),
        )
        saveExact(context, "epubcfi(/6/4!/4/2:4)", 11)
        server.enqueue(MockResponse(body = saved(first)))
        agreement().prepare(context)
        server.takeRequest()
        server.enqueue(MockResponse(body = saved(first)))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(body = saved(first, 26.0)))
        assertEquals(BookOrbitReadBack.Conflict, agreement().send(context))
        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
    }

    @Test
    fun `token renewal does not change binding but a file or connection switch blocks send`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement().prepare(context)
        server.takeRequest()
        db.remoteServerDao().upsert(account.copy(orbitAccessCipher = RemoteServer.seal("renewed")))
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(body = saved("epubcfi(/6/4!/4/2:3)")))
        assertEquals(BookOrbitReadBack.Agreed, agreement().send(context))
        server.takeRequest()
        assertEquals("Bearer renewed", server.takeRequest().headers["Authorization"])
        server.takeRequest()

        saveExact(context, "epubcfi(/6/4!/4/2:5)", 11)
        server.enqueue(MockResponse(body = saved("epubcfi(/6/4!/4/2:3)")))
        agreement().prepare(context)
        server.takeRequest()
        db.bookOrbitBindingDao().write(binding.copy(fileId = 35, revision = 3))
        assertThrows(BookOrbitIdentityChanged::class.java) { runBlocking { agreement().send(context) } }
        db.bookOrbitBindingDao().write(binding)
        db.remoteServerDao().upsert(account.copy(orbitEpoch = 8))
        assertThrows(BookOrbitIdentityChanged::class.java) { runBlocking { agreement().send(context) } }
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `same account reconnect transfers pending read back without reusing old context`(): Unit = runBlocking {
        val oldContext = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(oldContext, cfi, 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement().prepare(oldContext)
        server.takeRequest()
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(code = 503))
        assertThrows(RemoteHttpFailure::class.java) { runBlocking { agreement().send(oldContext) } }
        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
        val updated = account.copy(orbitEpoch = account.orbitEpoch + 1)
        db.remoteServerDao().upsert(updated)
        db.bookOrbitPositionAgreementDao().rebindConnection(
            oldContext.request.accountKey, updated.orbitEpoch, updated.baseUrl,
        )
        val newContext = repository.capture(binding.bookUrl)
        assertThrows(BookOrbitIdentityChanged::class.java) {
            runBlocking { agreement().readBack(oldContext) }
        }
        server.enqueue(MockResponse(body = saved(cfi)))
        assertEquals(BookOrbitReadBack.Agreed, agreement().readBack(newContext))
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `reader moves after prepare before send or during POST`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, cfi, 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement().prepare(context)
        server.takeRequest()
        saveExact(context, "epubcfi(/6/4!/4/2:4)", 11)
        assertThrows(BookOrbitPositionUnresolved::class.java) { runBlocking { agreement().send(context) } }
        assertEquals(1, server.requestCount)

        // Restore a sent revision, then move the reader in the POST handler.
        agreement().discardStalePreparation(context)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement().prepare(context)
        server.takeRequest()
        var posted = false
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                if (request.method == "POST") {
                    runBlocking { saveExact(context, "epubcfi(/6/4!/4/2:6)", 12) }
                    posted = true
                    return MockResponse(code = 201)
                }
                return MockResponse(body = if (posted) saved("epubcfi(/6/4!/4/2:4)")
                    else fixture("progress-unopened.json"))
            }
        }
        assertEquals(BookOrbitReadBack.Agreed, agreement().send(context))
        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
        val row = db.bookOrbitPositionAgreementDao().get(context.request.accountKey, context.bookUrl)!!
        assertEquals(2L, row.agreedLocalRevision)
        assertEquals(3L, db.readingProgressDao().get(context.bookUrl)!!.localRevision)
    }

    @Test
    fun `read back failure preserves bytes and restart recovers without another POST`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(context, cfi, 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val prepared = agreement().prepare(context)
        server.takeRequest()
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(code = 503))
        assertThrows(RemoteHttpFailure::class.java) { runBlocking { agreement().send(context) } }
        assertEquals("GET", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        val uncertain = db.bookOrbitPositionAgreementDao().get(context.request.accountKey, context.bookUrl)!!
        assertEquals(BookOrbitAttempt.UNCERTAIN.name, uncertain.attemptState)
        assertArrayEquals(prepared.outgoingBytes, uncertain.outgoingBytes)
        db.close()
        db = open()
        repository = BookOrbitCfiRepository(db)
        server.enqueue(MockResponse(body = saved(cfi)))
        assertEquals(BookOrbitReadBack.Agreed, agreement().readBack(context))
        assertEquals("GET", server.takeRequest().method)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `account cleanup and binding deletion remove agreements but not local reading`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement().prepare(context)
        server.takeRequest()
        db.bookOrbitPositionAgreementDao().clearAccount(context.request.accountKey)
        assertNull(db.bookOrbitPositionAgreementDao().get(context.request.accountKey, context.bookUrl))
        assertNotNull(db.readingProgressDao().get(context.bookUrl))
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement().prepare(context)
        server.takeRequest()
        db.bookOrbitBindingDao().clearBook(context.bookUrl)
        assertNull(db.bookOrbitPositionAgreementDao().get(context.request.accountKey, context.bookUrl))
        assertNotNull(db.readingProgressDao().get(context.bookUrl))
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
        const val ADOPTED_AUTHORITY = "com.chmouel.liseur.test.adopted"
    }

    /** One document, as a folder provider would serve it. */
    class AdoptedDocs : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            args: Array<out String>?, sort: String?,
        ): android.database.Cursor = android.database.MatrixCursor(
            arrayOf(android.provider.OpenableColumns.SIZE, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED),
        ).apply { addRow(arrayOf<Any>(bytes.size.toLong(), modified)) }

        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
            val file = java.io.File.createTempFile("served", ".epub").apply { writeBytes(bytes); deleteOnExit() }
            return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0

        companion object {
            var bytes = ByteArray(0)
            var modified = 0L
        }
    }
}
