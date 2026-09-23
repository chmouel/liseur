package com.chmouel.liseur.data.bookorbit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookOrbitBinding
import com.chmouel.liseur.data.db.BookOrbitBindingState
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.sync.PositionUpdate
import java.net.InetAddress
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.Dispatcher
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BookOrbitPositionSyncTest {
    private lateinit var database: LiseurDatabase
    private lateinit var web: MockWebServer
    private lateinit var account: RemoteServer
    private val bookUrl = "bookorbit:selected"

    @Before
    fun setup() = runBlocking {
        CredentialCipher.keyForTesting = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.deleteDatabase("orbit-position-sync-test")
        database = Room.databaseBuilder(app, LiseurDatabase::class.java, "orbit-position-sync-test").build()
        web = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        account = RemoteServer(
            kind = ServerKind.BOOKORBIT, baseUrl = "http://127.0.0.1:${web.port}",
            username = "reader", passwordCipher = null, apiKeyCipher = null, accountId = "1",
            userId = null, koboTokenCipher = null, canDownload = true, addedAt = 1,
            catalogSyncedAt = null, positionSyncedAt = null, syncToken = null,
            orbitAccessCipher = RemoteServer.seal("access"),
            orbitRefreshCipher = RemoteServer.seal("refresh"),
            orbitAccessExpires = Long.MAX_VALUE, orbitEpoch = 7,
        )
        database.remoteServerDao().upsert(account)
        database.bookOrbitBindingDao().write(BookOrbitBinding(
            account.accountKey, bookUrl, 12, 34, "epub", null, null, 2,
            BookOrbitBindingState.DOWNLOADED.name, 1,
        ))
        link(bookUrl)
    }

    /** Catalog rows are what keep a binding in the account walk. */
    private suspend fun link(url: String) {
        database.bookDao().upsert(Book(
            url = url, title = url, author = null, coverPath = null, source = null,
            addedAt = 1, lastOpenedAt = null, localUri = null, remoteUuid = url,
            downloadState = DownloadState.REMOTE,
        ))
    }

    @After
    fun cleanup() {
        database.close()
        web.close()
        CredentialCipher.keyForTesting = null
        ApplicationProvider.getApplicationContext<android.app.Application>()
            .deleteDatabase("orbit-position-sync-test")
    }

    private fun provider(
        manual: Boolean = false,
        accountSync: Boolean = false,
        automaticPush: Boolean = false,
        syncStatuses: Boolean = false,
    ): BookOrbitPositionSync {
        val cfis = BookOrbitCfiRepository(database)
        val http = BookOrbitHttp(BookOrbitSession(database.remoteServerDao()))
        val progress = BookOrbitProgressClient(http, cfis)
        return BookOrbitPositionSync(
            database,
            BookOrbitPositionExchange(
                cfis, progress, BookOrbitPositionAgreementRepository(
                    database, progress, BookOrbitProgressMutationTransport(http, cfis),
                ),
            ),
            manualBookSync = manual,
            accountSync = accountSync,
            automaticPush = automaticPush,
            statusSync = if (syncStatuses) {
                BookOrbitStatusSync(
                    database,
                    BookOrbitStatusClient(http, cfis),
                    com.chmouel.liseur.data.library.FinishedState(
                        database.bookDao(), database.readingProgressDao(),
                    ),
                )
            } else null,
        )
    }

    @Test
    fun `account sync reads book status without reading or posting file progress`() = runBlocking {
        web.enqueue(
            MockResponse(
                body = """{"id":12,"readStatus":{"status":"reading","source":"auto"}}""",
            ),
        )

        assertEquals(
            SyncOutcome.Success,
            provider(accountSync = true, automaticPush = true, syncStatuses = true).syncBook(bookUrl),
        )

        val request = web.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/books/12", request.url.encodedPath)
        assertEquals(1, web.requestCount)
        assertEquals(
            "reading",
            database.bookOrbitStatusAgreementDao().get(account.accountKey, bookUrl)?.agreedRemoteStatus,
        )
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/bookorbit/$name")).readText()

    private suspend fun saveExact(cfi: String, bookUrl: String = this.bookUrl) {
        val context = BookOrbitCfiRepository(database).capture(bookUrl)
        val locator = """{"href":"OPS/chapter.xhtml","locations":{"progression":0.25}}"""
        BookOrbitLocalPositionWriter(database).save(
            PositionUpdate(
                bookUrl = bookUrl, locatorJson = locator, progression = 0.25,
                readingSecondsPerPosition = null, readingPaceSamples = null,
                readingPaceElapsedMs = null, readingPaceEvidence = null,
                updatedAt = 10, bookOrbitCfi = BookOrbitLocalCandidate(
                    context, "OPS/chapter.xhtml", locator, cfi,
                ),
            ), "Reading",
        )
    }

    @Test
    fun `unconfigured provider never dials or exposes unresolved choices`(): Unit = runBlocking {
        val provider = provider()
        assertNull(provider.dialledAddress())
        assertFalse(provider.canSync(bookUrl))
        assertEquals(SyncOutcome.NotApplicable, provider.syncAll())
        assertEquals(SyncOutcome.NotApplicable, provider.syncBook(bookUrl))
        assertEquals(PreviewOutcome.NotSynced, provider.previewBook(bookUrl))
        assertNull(provider.preservedConflict(bookUrl))
        assertEquals(ResolveOutcome.Superseded, provider.takeRemotePosition(bookUrl, 0))
        assertEquals(ResolveOutcome.Superseded, provider.keepLocalPosition(bookUrl))
        provider.refreshUnresolved()
        assertNull(provider.identity())
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `manual exchange refuses unverified remote CFI without posting`(): Unit = runBlocking {
        saveExact("epubcfi(/6/4!/4/2:3)")
        val provider = provider(manual = true)
        val remote = JSONObject(fixture("progress-saved.json"))
            .put("cfi", "epubcfi(/6/4!/4/2:9)").toString()
        web.enqueue(MockResponse(body = remote))
        assertEquals(
            SyncOutcome.Failure(SyncFailure.PositionUnresolved),
            provider.syncBook(bookUrl),
        )
        assertFalse(SyncFailure.PositionUnresolved.worthRetrying)
        assertEquals("GET", web.takeRequest().method)
        assertEquals(1, web.requestCount)
        assertEquals("epubcfi(/6/4!/4/2:9)",
            database.bookOrbitPositionAgreementDao().get(account.accountKey, bookUrl)?.candidateCfi)
        assertFalse(provider.canSync(bookUrl))
    }

    @Test
    fun `manual exchange never sends another account's local position`(): Unit = runBlocking {
        saveExact("epubcfi(/6/4!/4/2:3)")
        val local = database.readingProgressDao().get(bookUrl)!!
        database.readingProgressDao().upsert(local.copy(ownerAccount = "different-account"))
        assertEquals(SyncOutcome.NotApplicable, provider(manual = true).syncBook(bookUrl))
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `manual exchange recovers uncertain delivery by GET without replaying POST`(): Unit = runBlocking {
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(cfi)
        val provider = provider(manual = true)
        repeat(3) { web.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
        web.enqueue(MockResponse(code = 201))
        web.enqueue(MockResponse(code = 503))
        assertEquals(SyncOutcome.Failure(SyncFailure.ServerError(503)), provider.syncBook(bookUrl))
        val context = BookOrbitCfiRepository(database).capture(bookUrl)
        assertEquals(BookOrbitAttempt.UNCERTAIN.name,
            database.bookOrbitPositionAgreementDao().get(context.request.accountKey, bookUrl)?.attemptState)
        val confirmed = JSONObject(fixture("progress-saved.json"))
            .put("cfi", cfi).put("percentage", 25.0).toString()
        web.enqueue(MockResponse(body = confirmed))
        assertEquals(SyncOutcome.Success, provider.syncBook(bookUrl))
        assertEquals(listOf("GET", "GET", "GET", "POST", "GET", "GET"),
            (1..6).map { web.takeRequest().method })
        assertEquals(6, web.requestCount)
        assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name,
            database.bookOrbitPositionAgreementDao().get(context.request.accountKey, bookUrl)?.attemptState)
    }

    @Test
    fun `rejected delivery remains blocked after another manual attempt`(): Unit = runBlocking {
        saveExact("epubcfi(/6/4!/4/2:3)")
        val provider = provider(manual = true)
        repeat(3) { web.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
        web.enqueue(MockResponse(code = 401))
        assertEquals(SyncOutcome.Failure(SyncFailure.Unauthorised), provider.syncBook(bookUrl))
        assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved), provider.syncBook(bookUrl))
        assertEquals(listOf("GET", "GET", "GET", "POST"),
            (1..4).map { web.takeRequest().method })
        assertEquals(4, web.requestCount)
        assertEquals(BookOrbitAttempt.REJECTED.name,
            database.bookOrbitPositionAgreementDao().get(account.accountKey, bookUrl)?.attemptState)
    }

    @Test
    fun `single book opt-in does not opt into an account walk`(): Unit = runBlocking {
        assertEquals(SyncOutcome.NotApplicable, provider(manual = true).syncAll())
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `account observation never prepares or posts a new local position`(): Unit = runBlocking {
        saveExact("epubcfi(/6/4!/4/2:3)")
        val provider = provider(accountSync = true)
        repeat(3) { web.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
        assertEquals(account.baseUrl, provider.dialledAddress())
        assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved), provider.syncAll())
        assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved), provider.syncBook(bookUrl))
        assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved),
            provider(manual = true, accountSync = true).syncAll())
        val agreement = database.bookOrbitPositionAgreementDao().get(account.accountKey, bookUrl)!!
        assertNull(agreement.outgoingBytes)
        assertNull(agreement.attemptState)
        assertEquals(listOf("GET", "GET", "GET"), (1..3).map { web.takeRequest().method })
        assertEquals(3, web.requestCount)
        assertFalse(provider.canSync(bookUrl))
        assertEquals(PreviewOutcome.NotSynced, provider.previewBook(bookUrl))
        assertEquals(ResolveOutcome.Superseded, provider.takeRemotePosition(bookUrl, 0))
        assertEquals(ResolveOutcome.Superseded, provider.keepLocalPosition(bookUrl))
    }

    private suspend fun addBinding(url: String, fileId: Long = 35) {
        val binding = database.bookOrbitBindingDao().get(account.accountKey, bookUrl)!!
        database.bookOrbitBindingDao().write(binding.copy(bookUrl = url, fileId = fileId))
        link(url)
    }

    @Test
    fun `automatic account push acknowledges exact bytes and keeps generic choices disabled`(): Unit = runBlocking {
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(cfi)
        repeat(3) { web.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
        web.enqueue(MockResponse(code = 201))
        web.enqueue(MockResponse(body = JSONObject(fixture("progress-saved.json"))
            .put("cfi", cfi).put("percentage", 25.0).toString()))
        val sync = provider(accountSync = true, automaticPush = true)
        assertEquals(SyncOutcome.Success, sync.syncAll())
        assertEquals(listOf("GET", "GET", "GET", "POST", "GET"),
            (1..5).map { web.takeRequest().method })
        val row = database.bookOrbitPositionAgreementDao().get(account.accountKey, bookUrl)!!
        assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name, row.attemptState)
        assertEquals(cfi, row.agreedRemoteCfi)
        assertNotNull(database.remoteServerDao().get()!!.positionSyncedAt)
        assertFalse(sync.canSync(bookUrl))
        assertEquals(PreviewOutcome.NotSynced, sync.previewBook(bookUrl))
        assertEquals(ResolveOutcome.Superseded, sync.takeRemotePosition(bookUrl, 0))
        assertEquals(ResolveOutcome.Superseded, sync.keepLocalPosition(bookUrl))
    }

    @Test
    fun `automatic book worker pushes without manual opt in`(): Unit = runBlocking {
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(cfi)
        repeat(3) { web.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
        web.enqueue(MockResponse(code = 201))
        web.enqueue(MockResponse(body = JSONObject(fixture("progress-saved.json"))
            .put("cfi", cfi).put("percentage", 25.0).toString()))
        assertEquals(SyncOutcome.Success,
            provider(accountSync = true, automaticPush = true).syncBook(bookUrl))
        assertEquals(listOf("GET", "GET", "GET", "POST", "GET"),
            (1..5).map { web.takeRequest().method })
    }

    @Test
    fun `automatic traversal skips unread catalog entries without network requests`(): Unit = runBlocking {
        assertEquals(SyncOutcome.NotApplicable,
            provider(accountSync = true, automaticPush = true).syncAll())
        assertEquals(0, web.requestCount)
        assertNull(database.remoteServerDao().get()!!.positionSyncedAt)
    }

    @Test
    fun `automatic traversal skips books that left the catalog`(): Unit = runBlocking {
        addBinding("bookorbit:deleted", 36)
        addBinding("bookorbit:unlinked", 37)
        saveExact("epubcfi(/6/4!/4/2:3)", "bookorbit:deleted")
        saveExact("epubcfi(/6/4!/4/2:3)", "bookorbit:unlinked")
        database.bookDao().deleteByUrls(listOf("bookorbit:deleted"))
        database.bookDao().unlinkFromRemote(listOf("bookorbit:unlinked"))
        val sync = provider(accountSync = true, automaticPush = true)

        assertEquals(SyncOutcome.NotApplicable, sync.syncAll())
        assertEquals(SyncOutcome.NotApplicable, sync.syncBook("bookorbit:unlinked"))
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `automatic sync skips a status-only row without reading progress`(): Unit = runBlocking {
        database.readingProgressDao().insertFinishedOverride(
            bookUrl, 1, com.chmouel.liseur.domain.ReadingStatus.FINISHED.name, null, 1,
        )
        val sync = provider(accountSync = true, automaticPush = true)

        assertEquals(SyncOutcome.NotApplicable, sync.syncAll())
        assertEquals(SyncOutcome.NotApplicable, sync.syncBook(bookUrl))
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `automatic second page pushes after first page conflict across database reopen`(): Unit = runBlocking {
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(cfi)
        repeat(BookOrbitPositionSync.PAGE_SIZE) { index ->
            addBinding("bookorbit:z${index.toString().padStart(2, '0')}", 100L + index)
        }
        saveExact(cfi, "bookorbit:z19")
        web.enqueue(MockResponse(body = JSONObject(fixture("progress-saved.json"))
            .put("cfi", "epubcfi(/6/4!/4/2:9)").toString()))
        assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved, continuation = true),
            provider(accountSync = true, automaticPush = true).syncAll())
        assertEquals(1, web.requestCount)
        database.close()
        database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
            LiseurDatabase::class.java, "orbit-position-sync-test",
        ).build()
        repeat(3) { web.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
        web.enqueue(MockResponse(code = 201))
        web.enqueue(MockResponse(body = JSONObject(fixture("progress-saved.json"))
            .put("bookFileId", 119).put("cfi", cfi).put("percentage", 25.0).toString()))
        assertEquals(SyncOutcome.Partial(SyncFailure.PositionUnresolved),
            provider(accountSync = true, automaticPush = true).syncAll(null, carryingOn = true))
        assertEquals(listOf("GET", "GET", "GET", "GET", "POST", "GET"),
            (1..6).map { web.takeRequest().method })
        assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name,
            database.bookOrbitPositionAgreementDao().get(account.accountKey, "bookorbit:z19")?.attemptState)
        assertNull(database.remoteServerDao().get()!!.positionSyncedAt)
    }

    @Test
    fun `automatic runs cannot replay unconfirmed request after local movement`(): Unit = runBlocking {
        saveExact("epubcfi(/6/4!/4/2:3)")
        repeat(3) { web.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
        web.enqueue(MockResponse(code = 201))
        web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved),
            provider(accountSync = true, automaticPush = true).syncAll())
        val pending = database.bookOrbitPositionAgreementDao().get(account.accountKey, bookUrl)!!
        assertEquals(BookOrbitAttempt.RETRY_REQUIRED.name, pending.attemptState)
        saveExact("epubcfi(/6/4!/4/2:7)")
        repeat(2) {
            web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
            assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved),
                provider(accountSync = true, automaticPush = true).syncAll())
        }
        assertEquals(listOf("GET", "GET", "GET", "POST", "GET", "GET", "GET"),
            (1..7).map { web.takeRequest().method })
        assertArrayEquals(pending.outgoingBytes,
            database.bookOrbitPositionAgreementDao().get(account.accountKey, bookUrl)?.outgoingBytes)
    }

    @Test
    fun `automatic runs leave rejected and unknown attempts blocked`(): Unit = runBlocking {
        saveExact("epubcfi(/6/4!/4/2:3)")
        repeat(3) { web.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
        web.enqueue(MockResponse(code = 403))
        val sync = provider(accountSync = true, automaticPush = true)
        assertEquals(SyncOutcome.Failure(SyncFailure.Forbidden), sync.syncBook(bookUrl))
        val dao = database.bookOrbitPositionAgreementDao()
        val rejected = dao.get(account.accountKey, bookUrl)!!
        assertEquals(BookOrbitAttempt.REJECTED.name, rejected.attemptState)
        for (state in listOf(BookOrbitAttempt.REJECTED.name, "future-attempt-state")) {
            dao.write(rejected.copy(attemptState = state))
            assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved), sync.syncAll())
            assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved), sync.syncBook(bookUrl))
            val retained = dao.get(account.accountKey, bookUrl)!!
            assertEquals(state, retained.attemptState)
            assertArrayEquals(rejected.outgoingBytes, retained.outgoingBytes)
            assertEquals(rejected.attemptGeneration, retained.attemptGeneration)
        }
        assertEquals(listOf("GET", "GET", "GET", "POST"),
            (1..4).map { web.takeRequest().method })
        assertEquals(4, web.requestCount)
    }

    @Test
    fun `account walk is bounded and continues past unresolved books`(): Unit = runBlocking {
        val cfi = "epubcfi(/6/4!/4/2:3)"
        saveExact(cfi)
        repeat(BookOrbitPositionSync.PAGE_SIZE) { index ->
            val url = "bookorbit:z${index.toString().padStart(2, '0')}"
            addBinding(url, 100L + index)
            saveExact(cfi, url)
        }
        repeat(BookOrbitPositionSync.PAGE_SIZE + 1) {
            web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        }
        val provider = provider(accountSync = true)
        assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved, continuation = true), provider.syncAll())
        assertEquals(BookOrbitPositionSync.PAGE_SIZE, web.requestCount)
        assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved),
            provider(accountSync = true).syncAll(null, carryingOn = true))
        assertEquals(BookOrbitPositionSync.PAGE_SIZE + 1, web.requestCount)
        repeat(web.requestCount) { assertEquals("GET", web.takeRequest().method) }
    }

    @Test
    fun `settled pages report more work and wrap after the last page`(): Unit = runBlocking {
        repeat(BookOrbitPositionSync.PAGE_SIZE) { index -> addBinding("bookorbit:z$index") }
        repeat(BookOrbitPositionSync.PAGE_SIZE * 2 + 1) {
            web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        }
        val provider = provider(accountSync = true)
        assertEquals(SyncOutcome.Incomplete, provider.syncAll())
        assertEquals(BookOrbitPositionSync.PAGE_SIZE, web.requestCount)
        assertEquals(SyncOutcome.Success, provider.syncAll())
        assertEquals(BookOrbitPositionSync.PAGE_SIZE + 1, web.requestCount)
        assertEquals(SyncOutcome.Incomplete, provider.syncAll())
        assertEquals(BookOrbitPositionSync.PAGE_SIZE * 2 + 1, web.requestCount)
    }

    @Test
    fun `a settled later page cannot hide an earlier conflict`(): Unit = runBlocking {
        saveExact("epubcfi(/6/4!/4/2:3)")
        repeat(BookOrbitPositionSync.PAGE_SIZE) { index -> addBinding("bookorbit:z$index") }
        repeat(BookOrbitPositionSync.PAGE_SIZE + 1) {
            web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        }
        val provider = provider(accountSync = true)
        assertEquals(SyncOutcome.Partial(SyncFailure.PositionUnresolved, continuation = true), provider.syncAll())
        database.close()
        database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
            LiseurDatabase::class.java, "orbit-position-sync-test",
        ).build()
        val reconstructed = provider(accountSync = true)
        assertEquals(SyncOutcome.Partial(SyncFailure.PositionUnresolved),
            reconstructed.syncAll(null, carryingOn = true))
        assertEquals(SyncOutcome.Partial(SyncFailure.PositionUnresolved),
            provider(accountSync = true).syncAll(null, carryingOn = true))
        assertEquals(BookOrbitPositionSync.PAGE_SIZE + 1, web.requestCount)
    }

    @Test
    fun `connection epoch change restarts paging and refuses old agreements`(): Unit = runBlocking {
        repeat(BookOrbitPositionSync.PAGE_SIZE) { index -> addBinding("bookorbit:z$index") }
        repeat(BookOrbitPositionSync.PAGE_SIZE) {
            web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        }
        val provider = provider(accountSync = true)
        assertEquals(SyncOutcome.Incomplete, provider.syncAll())
        database.remoteServerDao().upsert(account.copy(orbitEpoch = 8))
        assertEquals(SyncOutcome.Failure(SyncFailure.StaleIdentity), provider.syncAll())
        assertEquals(BookOrbitPositionSync.PAGE_SIZE, web.requestCount)
    }

    @Test
    fun `another account starts before the previous accounts cursor`(): Unit = runBlocking {
        repeat(BookOrbitPositionSync.PAGE_SIZE) { index -> addBinding("bookorbit:z$index") }
        repeat(BookOrbitPositionSync.PAGE_SIZE + 1) {
            web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        }
        val provider = provider(accountSync = true)
        assertEquals(SyncOutcome.Incomplete, provider.syncAll())
        val other = account.copy(accountId = "2", orbitEpoch = 8)
        val binding = database.bookOrbitBindingDao().get(account.accountKey, bookUrl)!!
        database.bookOrbitBindingDao().write(binding.copy(accountKey = other.accountKey))
        database.remoteServerDao().upsert(other)
        assertEquals(SyncOutcome.Success, provider.syncAll())
        assertEquals(BookOrbitPositionSync.PAGE_SIZE + 1, web.requestCount)
        assertNotNull(database.bookOrbitPositionAgreementDao().get(other.accountKey, bookUrl))
    }

    @Test
    fun `mixed page reports unresolved rather than success`(): Unit = runBlocking {
        saveExact("epubcfi(/6/4!/4/2:3)")
        addBinding("bookorbit:z")
        repeat(2) { web.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
        assertEquals(
            SyncOutcome.Partial(SyncFailure.PositionUnresolved),
            provider(accountSync = true).syncAll(),
        )
        assertFalse(SyncFailure.PositionUnresolved.worthRetrying)
        assertEquals(2, web.requestCount)
    }

    @Test
    fun `account walk excludes invalid bindings and foreign local ownership`(): Unit = runBlocking {
        saveExact("epubcfi(/6/4!/4/2:3)")
        val local = database.readingProgressDao().get(bookUrl)!!
        database.readingProgressDao().upsert(local.copy(ownerAccount = "foreign"))
        val binding = database.bookOrbitBindingDao().get(account.accountKey, bookUrl)!!
        listOf(
            binding.copy(bookUrl = "missing", state = BookOrbitBindingState.MISSING.name),
            binding.copy(bookUrl = "unbound", fileId = null),
            binding.copy(bookUrl = "pdf", fileFormat = "pdf"),
            binding.copy(bookUrl = "foreign", accountKey = "foreign"),
        ).forEach { database.bookOrbitBindingDao().write(it) }
        assertEquals(SyncOutcome.NotApplicable, provider(accountSync = true).syncAll())
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `changed connection aborts page before another file is read`(): Unit = runBlocking {
        addBinding("bookorbit:z")
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { database.remoteServerDao().upsert(account.copy(orbitEpoch = 8)) }
                return MockResponse(body = fixture("progress-unopened.json"))
            }
        }
        assertEquals(
            SyncOutcome.Failure(SyncFailure.StaleIdentity),
            provider(accountSync = true).syncAll(),
        )
        assertEquals(1, web.requestCount)
    }

    @Test
    fun `changed later binding is not silently recaptured`(): Unit = runBlocking {
        val url = "bookorbit:z"
        addBinding(url)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking {
                    val binding = database.bookOrbitBindingDao().get(account.accountKey, url)!!
                    database.bookOrbitBindingDao().write(binding.copy(fileId = 999, revision = 3))
                }
                return MockResponse(body = fixture("progress-unopened.json"))
            }
        }
        assertEquals(
            SyncOutcome.Partial(SyncFailure.PositionUnresolved),
            provider(accountSync = true).syncAll(),
        )
        assertEquals(1, web.requestCount)
    }

    @Test
    fun `transient failure keeps page at failed file`(): Unit = runBlocking {
        addBinding("bookorbit:z")
        web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        web.enqueue(MockResponse(code = 503))
        val provider = provider(accountSync = true)
        assertEquals(SyncOutcome.Partial(SyncFailure.ServerError(503)), provider.syncAll())
        web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        assertEquals(SyncOutcome.Success, provider(accountSync = true).syncAll())
        val paths = (1..3).map { web.takeRequest().url.encodedPath }
        assertNotEquals(paths[0], paths[1])
        assertEquals(paths[1], paths[2])
        assertEquals(3, web.requestCount)
    }

    @Test
    fun `new bindings cannot extend a captured traversal or restart a completed continuation`(): Unit = runBlocking {
        repeat(BookOrbitPositionSync.PAGE_SIZE) { index -> addBinding("bookorbit:z$index") }
        repeat(BookOrbitPositionSync.PAGE_SIZE + 1) {
            web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        }
        assertEquals(SyncOutcome.Incomplete, provider(accountSync = true).syncAll())
        addBinding("bookorbit:aaaa", 999)
        addBinding("bookorbit:zzzz", 1000)
        assertEquals(SyncOutcome.Success, provider(accountSync = true).syncAll(null, carryingOn = true))
        assertEquals(SyncOutcome.Success, provider(accountSync = true).syncAll(null, carryingOn = true))
        assertEquals(BookOrbitPositionSync.PAGE_SIZE + 1, web.requestCount)
        assertNull(database.bookOrbitPositionAgreementDao().get(account.accountKey, "bookorbit:aaaa"))
        assertNull(database.bookOrbitPositionAgreementDao().get(account.accountKey, "bookorbit:zzzz"))
    }

    @Test
    fun `a transient retry preserves earlier unresolved reports after reconstruction`(): Unit = runBlocking {
        saveExact("epubcfi(/6/4!/4/2:3)")
        addBinding("bookorbit:z")
        web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        web.enqueue(MockResponse(code = 503))
        assertEquals(SyncOutcome.Failure(SyncFailure.ServerError(503)),
            provider(accountSync = true).syncAll())
        web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        assertEquals(SyncOutcome.Partial(SyncFailure.PositionUnresolved),
            provider(accountSync = true).syncAll())
        val paths = (1..3).map { web.takeRequest().url.encodedPath }
        assertNotEquals(paths[0], paths[1])
        assertEquals(paths[1], paths[2])
    }

    @Test
    fun `cancelled observation leaves the failed item for a reconstructed provider`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.complete(Unit)
                runBlocking { response.await() }
                return MockResponse(body = fixture("progress-unopened.json"))
            }
        }
        val running = async { provider(accountSync = true).syncAll() }
        withTimeout(10_000) { started.await() }
        running.cancel()
        response.complete(Unit)
        withTimeout(10_000) { running.join() }
        assertTrue(running.isCancelled)
        val traversal = database.bookOrbitPositionTraversalDao().get(account.accountKey)!!
        assertNull(traversal.afterUrl)
        assertFalse(traversal.finished)
        assertEquals(SyncOutcome.Success, provider(accountSync = true).syncAll(null, carryingOn = true))
        assertEquals(2, web.requestCount)
        assertEquals(web.takeRequest().url.encodedPath, web.takeRequest().url.encodedPath)
    }

    @Test
    fun `stale follow up cannot start a replacement connection traversal`(): Unit = runBlocking {
        repeat(BookOrbitPositionSync.PAGE_SIZE) { index -> addBinding("bookorbit:z$index") }
        repeat(BookOrbitPositionSync.PAGE_SIZE) {
            web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        }
        assertEquals(SyncOutcome.Incomplete, provider(accountSync = true).syncAll())
        database.remoteServerDao().upsert(account.copy(orbitEpoch = 8))
        assertEquals(SyncOutcome.NotApplicable,
            provider(accountSync = true).syncAll(null, carryingOn = true))
        assertEquals(BookOrbitPositionSync.PAGE_SIZE, web.requestCount)
    }

    @Test
    fun `removed snapshot member is reported without starving later bindings`(): Unit = runBlocking {
        repeat(BookOrbitPositionSync.PAGE_SIZE + 1) { index ->
            addBinding("bookorbit:z${index.toString().padStart(2, '0')}")
        }
        repeat(BookOrbitPositionSync.PAGE_SIZE + 1) {
            web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        }
        assertEquals(SyncOutcome.Incomplete, provider(accountSync = true).syncAll())
        database.bookOrbitBindingDao().delete(account.accountKey, "bookorbit:z19")
        assertEquals(SyncOutcome.Partial(SyncFailure.PositionUnresolved),
            provider(accountSync = true).syncAll(null, carryingOn = true))
        assertNotNull(database.bookOrbitPositionAgreementDao().get(account.accountKey, "bookorbit:z20"))
        assertEquals(BookOrbitPositionSync.PAGE_SIZE + 1, web.requestCount)
    }

    @Test
    fun `account walk recovers durable uncertain send after provider recreation without POST`(): Unit =
        runBlocking {
            val cfi = "epubcfi(/6/4!/4/2:3)"
            saveExact(cfi)
            repeat(3) { web.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
            web.enqueue(MockResponse(code = 201))
            web.enqueue(MockResponse(code = 503))
            assertEquals(SyncOutcome.Failure(SyncFailure.ServerError(503)),
                provider(manual = true).syncBook(bookUrl))
            val pending = database.bookOrbitPositionAgreementDao().get(account.accountKey, bookUrl)!!
            assertEquals(BookOrbitAttempt.UNCERTAIN.name, pending.attemptState)
            web.enqueue(MockResponse(body = JSONObject(fixture("progress-saved.json"))
                .put("cfi", cfi).put("percentage", 25.0).toString()))
            assertEquals(SyncOutcome.Success, provider(accountSync = true).syncAll())
            assertEquals(listOf("GET", "GET", "GET", "POST", "GET", "GET"),
                (1..6).map { web.takeRequest().method })
            assertEquals(6, web.requestCount)
        }

    @Test
    fun `possibly sent request survives database reopen and account run only reads it back`(): Unit =
        runBlocking {
            val cfi = "epubcfi(/6/4!/4/2:3)"
            saveExact(cfi)
            val cfis = BookOrbitCfiRepository(database)
            val context = cfis.capture(bookUrl)
            val http = BookOrbitHttp(BookOrbitSession(database.remoteServerDao()))
            val progress = BookOrbitProgressClient(http, cfis)
            val agreements = BookOrbitPositionAgreementRepository(
                database, progress, BookOrbitProgressMutationTransport(http, cfis),
            )
            web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
            val prepared = agreements.prepare(context)
            database.bookOrbitPositionAgreementDao().write(
                prepared.copy(attemptState = BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name),
            )
            database.close()
            database = Room.databaseBuilder(
                ApplicationProvider.getApplicationContext<android.app.Application>(),
                LiseurDatabase::class.java, "orbit-position-sync-test",
            ).build()
            val pending = database.bookOrbitPositionAgreementDao().get(account.accountKey, bookUrl)!!
            assertEquals(BookOrbitAttempt.MAY_HAVE_BEEN_SENT.name, pending.attemptState)
            assertArrayEquals(prepared.outgoingBytes, pending.outgoingBytes)
            web.enqueue(MockResponse(body = JSONObject(fixture("progress-saved.json"))
                .put("cfi", cfi).put("percentage", 25.0).toString()))
            assertEquals(SyncOutcome.Success, provider(accountSync = true).syncAll())
            assertEquals(2, web.requestCount)
            assertEquals(listOf("GET", "GET"), (1..2).map { web.takeRequest().method })
            assertEquals(BookOrbitAttempt.ACKNOWLEDGED.name,
                database.bookOrbitPositionAgreementDao().get(account.accountKey, bookUrl)?.attemptState)
        }

    @Test
    fun `account walk leaves exact prepared bytes unsent`(): Unit = runBlocking {
        saveExact("epubcfi(/6/4!/4/2:3)")
        val cfis = BookOrbitCfiRepository(database)
        val context = cfis.capture(bookUrl)
        val http = BookOrbitHttp(BookOrbitSession(database.remoteServerDao()))
        val progress = BookOrbitProgressClient(http, cfis)
        val agreements = BookOrbitPositionAgreementRepository(
            database, progress, BookOrbitProgressMutationTransport(http, cfis),
        )
        web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        agreements.observe(context, progress.read(context))
        web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
        val prepared = agreements.prepare(context)
        assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved),
            provider(manual = true, accountSync = true).syncAll())
        val after = agreements.state(context)
        assertEquals(BookOrbitAttempt.PREPARED.name, after.attemptState)
        assertArrayEquals(prepared.outgoingBytes, after.outgoingBytes)
        assertEquals(2, web.requestCount)
    }

    @Test
    fun `unchanged read-back never becomes a new account POST on later runs`(): Unit = runBlocking {
        saveExact("epubcfi(/6/4!/4/2:3)")
        repeat(3) { web.enqueue(MockResponse(body = fixture("progress-unopened.json"))) }
        web.enqueue(MockResponse(code = 201))
        web.enqueue(MockResponse(code = 503))
        assertEquals(SyncOutcome.Failure(SyncFailure.ServerError(503)),
            provider(manual = true).syncBook(bookUrl))
        val provider = provider(accountSync = true)
        repeat(2) {
            web.enqueue(MockResponse(body = fixture("progress-unopened.json")))
            assertEquals(SyncOutcome.Failure(SyncFailure.PositionUnresolved), provider.syncAll())
        }
        assertEquals(listOf("GET", "GET", "GET", "POST", "GET", "GET", "GET"),
            (1..7).map { web.takeRequest().method })
        assertEquals(7, web.requestCount)
    }
}
