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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
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

    private fun agreement(): BookOrbitPositionAgreementRepository {
        val http = BookOrbitHttp(BookOrbitSession(db.remoteServerDao()))
        return BookOrbitPositionAgreementRepository(
            db, BookOrbitProgressClient(http, repository),
            BookOrbitProgressMutationTransport(http, repository),
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
            ), "Reading",
        )
    }

    private fun saved(cfi: String, percentage: Double = 25.0): String =
        JSONObject(fixture("progress-saved.json")).apply {
            put("cfi", cfi)
            put("percentage", percentage)
        }.toString()

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
            context, changed, original.localRevision, original.locatorJson,
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
    fun `unconditional POST overwrites a competitor arriving after final GET`(): Unit = runBlocking {
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
    fun `reader opening after an explicit choice prevents delivery at final preflight`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        val remote = saved("epubcfi(/6/4!/4/2:9)")
        server.enqueue(MockResponse(body = remote))
        val preview = checkNotNull(agreement().previewConflict(context))
        server.takeRequest()
        server.enqueue(MockResponse(body = remote))
        agreement().prepareKeepLocal(preview)
        server.takeRequest()
        db.readingProgressDao().openBooks.enter(context.bookUrl)
        server.enqueue(MockResponse(body = remote))
        assertThrows(BookOrbitPositionUnresolved::class.java) {
            runBlocking { agreement().send(context) }
        }
        assertEquals("GET", server.takeRequest().method)
        assertEquals(3, server.requestCount)
        assertEquals(BookOrbitAttempt.PREPARED.name, agreement().state(context).attemptState)
        db.readingProgressDao().openBooks.leave(context.bookUrl)
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
    fun `lost POST reads back before allowing a new preflight and never replays`(): Unit = runBlocking {
        val context = repository.capture(binding.bookUrl)
        saveExact(context, "epubcfi(/6/4!/4/2:3)", 10)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreement().prepare(context)
        server.takeRequest()
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        assertEquals(BookOrbitReadBack.SafeToPrepareAgain, agreement().send(context))
        assertEquals("GET", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(4, server.requestCount)
        assertNull(db.bookOrbitPositionAgreementDao().get(context.request.accountKey, context.bookUrl)!!.outgoingBytes)
        server.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        assertEquals(BookOrbitAttempt.PREPARED.name, agreement().prepare(context).attemptState)
        server.takeRequest()
        assertEquals(5, server.requestCount)
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
    }
}
