package com.chmouel.liseur.data.bookorbit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.BookOrbitBinding
import com.chmouel.liseur.data.db.BookOrbitBindingState
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
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
    }

    @After
    fun cleanup() {
        database.close()
        web.close()
        CredentialCipher.keyForTesting = null
        ApplicationProvider.getApplicationContext<android.app.Application>()
            .deleteDatabase("orbit-position-sync-test")
    }

    private fun provider(manual: Boolean = false): BookOrbitPositionSync {
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
        )
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/bookorbit/$name")).readText()

    private suspend fun saveExact(cfi: String) {
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
    fun `normal provider never dials or exposes unresolved choices`(): Unit = runBlocking {
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
}
