package com.chmouel.liseur.data.bookorbit

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.BookOrbitBinding
import com.chmouel.liseur.data.db.BookOrbitBindingState
import com.chmouel.liseur.data.db.BookOrbitLocalCfi
import com.chmouel.liseur.data.db.BookOrbitPositionAgreement
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.ExactPositionDecision
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.ReadingStatus
import java.net.InetAddress
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BookOrbitStatusSyncTest {
    private lateinit var database: LiseurDatabase
    private lateinit var web: MockWebServer
    private lateinit var account: RemoteServer
    private val bookUrl = "bookorbit:status-test"

    @Before
    fun setup() = runBlocking {
        CredentialCipher.keyForTesting = KeyGenerator.getInstance("AES")
            .apply { init(256) }.generateKey()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.deleteDatabase("orbit-status-sync-test")
        database = Room.databaseBuilder(
            app, LiseurDatabase::class.java, "orbit-status-sync-test",
        ).build()
        web = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        account = RemoteServer(
            kind = ServerKind.BOOKORBIT,
            baseUrl = "http://127.0.0.1:${web.port}",
            username = "reader",
            passwordCipher = null,
            apiKeyCipher = null,
            accountId = "1",
            userId = null,
            koboTokenCipher = null,
            canDownload = true,
            addedAt = 1,
            catalogSyncedAt = null,
            positionSyncedAt = null,
            syncToken = null,
            orbitAccessCipher = RemoteServer.seal("access"),
            orbitRefreshCipher = RemoteServer.seal("refresh"),
            orbitAccessExpires = Long.MAX_VALUE,
            orbitEpoch = 7,
        )
        database.remoteServerDao().upsert(account)
        database.bookOrbitBindingDao().write(
            BookOrbitBinding(
                account.accountKey, bookUrl, 12, 34, "epub", null, null, 2,
                BookOrbitBindingState.DOWNLOADED.name, 1,
            ),
        )
    }

    @After
    fun cleanup() {
        database.close()
        web.close()
        CredentialCipher.keyForTesting = null
        ApplicationProvider.getApplicationContext<android.app.Application>()
            .deleteDatabase("orbit-status-sync-test")
    }

    private fun sync(): BookOrbitStatusSync {
        val cfis = BookOrbitCfiRepository(database)
        val finished = FinishedState(
            database.bookDao(),
            database.readingProgressDao(),
            inTransaction = { work -> database.withTransaction { work() } },
        )
        return BookOrbitStatusSync(
            database,
            BookOrbitStatusClient(
                BookOrbitHttp(BookOrbitSession(database.remoteServerDao())),
                cfis,
            ),
            finished,
        )
    }

    private fun detail(status: String?, source: String? = null): String = JSONObject()
        .put("id", 12)
        .put(
            "readStatus",
            if (status == null) JSONObject.NULL else JSONObject()
                .put("status", status)
                .put("source", source),
        )
        .toString()

    private suspend fun runSync(): SyncOutcome =
        sync().sync(BookOrbitCfiRepository(database).capture(bookUrl))

    @Test
    fun `an uploaded book whose file was replaced sends and takes no status`() = runBlocking {
        val file = java.io.File.createTempFile("replaced", ".epub").apply {
            writeText("not the bytes that went up")
            deleteOnExit()
        }
        database.bookDao().upsert(
            com.chmouel.liseur.data.db.Book(
                url = bookUrl, title = "Up", author = null, coverPath = null, source = null,
                addedAt = 1, lastOpenedAt = null,
                localUri = android.net.Uri.fromFile(file).toString(),
            ),
        )
        database.bookOrbitBindingDao().write(
            database.bookOrbitBindingDao().get(account.accountKey, bookUrl)!!
                .copy(localSha256 = "0".repeat(64)),
        )
        FinishedState(database.bookDao(), database.readingProgressDao())
            .setFinished(bookUrl, true)

        assertEquals(SyncOutcome.Failure(SyncFailure.StatusUnresolved), runSync())
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `local finished intent patches only status and confirms manual readback`() = runBlocking {
        database.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = bookUrl,
                locatorJson = """{"href":"chapter.xhtml","locations":{"progression":0.42}}""",
                totalProgression = 0.42,
                updatedAt = 10,
            ),
        )
        FinishedState(database.bookDao(), database.readingProgressDao())
            .setFinished(bookUrl, true)
        web.enqueue(MockResponse(body = detail("unread", "auto")))
        web.enqueue(MockResponse(code = 200))
        web.enqueue(MockResponse(body = detail("read", "manual")))

        assertEquals(SyncOutcome.Success, runSync())

        val requests = (1..3).map { web.takeRequest() }
        assertEquals(listOf("GET", "PATCH", "GET"), requests.map { it.method })
        val patch = requests[1]
        assertEquals("/api/v1/books/12/status", patch.url.encodedPath)
        assertEquals("""{"status":"read"}""", patch.body!!.utf8())
        assertEquals(
            """{"href":"chapter.xhtml","locations":{"progression":0.42}}""",
            database.readingProgressDao().get(bookUrl)!!.locatorJson,
        )
        assertEquals(0, database.readingProgressDao().get(bookUrl)!!.positionRevision)
        val agreement = database.bookOrbitStatusAgreementDao().get(account.accountKey, bookUrl)!!
        assertEquals("read", agreement.agreedRemoteStatus)
        assertEquals("manual", agreement.agreedRemoteSource)
        assertNull(agreement.outgoingBytes)
    }

    @Test
    fun `remote unread changes status without replacing the local passage`() = runBlocking {
        val locator = """{"href":"chapter.xhtml","locations":{"progression":0.73}}"""
        database.readingProgressDao().upsert(
            ReadingProgress(bookUrl, locator, 0.73, updatedAt = 11),
        )
        web.enqueue(MockResponse(body = detail("unread", "manual")))

        assertEquals(SyncOutcome.Success, runSync())

        val local = database.readingProgressDao().get(bookUrl)!!
        assertEquals(locator, local.locatorJson)
        assertEquals(0.73, local.totalProgression!!, 0.0)
        assertEquals("unread", BookOrbitStatusMapping.toRemote(local.override))
        assertEquals(1, local.statusRevision)
        assertEquals(1, local.localRevision)
        assertEquals(0, local.positionRevision)
        assertEquals("GET", web.takeRequest().method)
        assertEquals(1, web.requestCount)
    }

    @Test
    fun `marking status leaves an agreed exact position settled`() = runBlocking {
        val locator = """{"href":"chapter.xhtml","locations":{"progression":0.42}}"""
        val cfi = "epubcfi(/6/4!/4/2:3)"
        database.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = bookUrl,
                locatorJson = locator,
                totalProgression = 0.42,
                updatedAt = 10,
                localRevision = 5,
                positionRevision = 3,
            ),
        )
        database.bookOrbitLocalCfiDao().write(
            BookOrbitLocalCfi(
                account.accountKey, bookUrl, 12, 34, 2, 3, locator, cfi,
            ),
        )
        database.bookOrbitPositionAgreementDao().write(
            BookOrbitPositionAgreement(
                account.accountKey,
                bookUrl,
                12,
                34,
                2,
                account.orbitEpoch,
                account.baseUrl,
                agreedLocalRevision = 3,
                agreedLocatorJson = locator,
                agreedRemoteCfi = cfi,
                agreedRemotePercentage = 42.0,
                agreedRemoteSaved = true,
            ),
        )
        FinishedState(database.bookDao(), database.readingProgressDao())
            .setFinished(bookUrl, true)

        val cfis = BookOrbitCfiRepository(database)
        val http = BookOrbitHttp(BookOrbitSession(database.remoteServerDao()))
        val progress = BookOrbitProgressClient(http, cfis)
        val repository = BookOrbitPositionAgreementRepository(
            database,
            progress,
            BookOrbitProgressMutationTransport(http, cfis),
        )
        val remote = BookOrbitFileProgress(
            percentage = 42.0,
            cfi = cfi,
            lastReadAt = "2026-09-23T10:00:00.000Z",
            textUpdatedAt = null,
            updatedAt = "2026-09-23T10:00:00.000Z",
        )

        assertEquals(
            ExactPositionDecision.Settled,
            repository.observe(cfis.capture(bookUrl), remote, remoteVerified = true),
        )
        val local = database.readingProgressDao().get(bookUrl)!!
        assertEquals(6L, local.localRevision)
        assertEquals(3L, local.positionRevision)
        assertEquals(3L, database.bookOrbitPositionAgreementDao()
            .get(account.accountKey, bookUrl)?.agreedLocalRevision)
        assertEquals(0, web.requestCount)
    }

    @Test
    fun `unsupported remote values do not flatten local status`() = runBlocking {
        database.readingProgressDao().upsert(
            ReadingProgress(bookUrl, """{"href":"chapter.xhtml"}""", 0.73, updatedAt = 12),
        )
        web.enqueue(MockResponse(body = detail("abandoned", "manual")))

        assertEquals(SyncOutcome.Success, runSync())

        val local = database.readingProgressDao().get(bookUrl)!!
        assertEquals(0, local.finishedOverride)
        assertEquals(0, local.statusRevision)
        assertEquals(0, local.localRevision)
        assertEquals("abandoned",
            database.bookOrbitStatusAgreementDao().get(account.accountKey, bookUrl)?.agreedRemoteStatus)
    }

    @Test
    fun `uncertain status write is recovered by read only and never replayed`() = runBlocking {
        FinishedState(database.bookDao(), database.readingProgressDao())
            .setFinished(bookUrl, false)
        web.enqueue(MockResponse(body = detail("read", "manual")))
        web.enqueue(MockResponse(code = 503))
        web.enqueue(MockResponse(body = detail("read", "manual")))
        assertEquals(SyncOutcome.Failure(SyncFailure.StatusUnresolved), runSync())
        val pending = database.bookOrbitStatusAgreementDao().get(account.accountKey, bookUrl)!!
        assertNotNull(pending.outgoingBytes)
        assertEquals("UNCERTAIN", pending.attemptState)

        web.enqueue(MockResponse(body = detail("read", "manual")))
        assertEquals(SyncOutcome.Failure(SyncFailure.StatusUnresolved), runSync())
        assertEquals(listOf("GET", "PATCH", "GET", "GET"),
            (1..4).map { web.takeRequest().method })
        assertEquals(4, web.requestCount)
        assertNotNull(database.bookOrbitStatusAgreementDao().get(account.accountKey, bookUrl)?.outgoingBytes)
    }

    @Test
    fun `a status choice made during recovery read-back is sent next rather than settled`() = runBlocking {
        val finished = FinishedState(database.bookDao(), database.readingProgressDao())
        finished.setFinished(bookUrl, false)
        web.enqueue(MockResponse(body = detail("read", "manual")))
        web.enqueue(MockResponse(code = 503))
        web.enqueue(MockResponse(body = detail("read", "manual")))
        assertEquals(SyncOutcome.Failure(SyncFailure.StatusUnresolved), runSync())
        val sentRevision = database.readingProgressDao().get(bookUrl)!!.statusRevision
        repeat(3) { web.takeRequest() }

        val bodies = mutableListOf<String>()
        var gets = 0
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "PATCH") {
                    bodies += request.body!!.utf8()
                    return MockResponse(code = 200)
                }
                gets++
                return when (gets) {
                    // The reader marks the book read while the recovery GET is in flight.
                    1 -> {
                        runBlocking { finished.setFinished(bookUrl, true) }
                        MockResponse(body = detail("unread", "manual"))
                    }
                    2 -> MockResponse(body = detail("unread", "manual"))
                    else -> MockResponse(body = detail("read", "manual"))
                }
            }
        }

        assertEquals(SyncOutcome.Success, runSync())
        val recovered = database.bookOrbitStatusAgreementDao().get(account.accountKey, bookUrl)!!
        assertEquals(sentRevision, recovered.agreedLocalStatusRevision)
        assertEquals(FinishedOverride.UNREAD.ordinal, recovered.agreedLocalOverride)
        assertNull(recovered.outgoingBytes)
        assertEquals(emptyList<String>(), bodies)

        assertEquals(SyncOutcome.Success, runSync())
        assertEquals(listOf("""{"status":"read"}"""), bodies)
        val settled = database.bookOrbitStatusAgreementDao().get(account.accountKey, bookUrl)!!
        assertEquals(database.readingProgressDao().get(bookUrl)!!.statusRevision,
            settled.agreedLocalStatusRevision)
        assertEquals(FinishedOverride.FINISHED.ordinal, settled.agreedLocalOverride)
    }

    @Test
    fun `a book owned by another account is left alone`() = runBlocking {
        database.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl, """{"href":"chapter.xhtml"}""", 0.5, updatedAt = 13,
                ownerAccount = "someone-else",
            ),
        )
        FinishedState(database.bookDao(), database.readingProgressDao())
            .setFinished(bookUrl, true)

        assertEquals(SyncOutcome.NotApplicable, runSync())
        assertEquals(0, web.requestCount)
        assertNull(database.bookOrbitStatusAgreementDao().get(account.accountKey, bookUrl))
    }

    @Test
    fun `ownership taken over before the send stops the status write`() = runBlocking {
        database.readingProgressDao().upsert(
            ReadingProgress(bookUrl, """{"href":"chapter.xhtml"}""", 0.5, updatedAt = 14),
        )
        FinishedState(database.bookDao(), database.readingProgressDao())
            .setFinished(bookUrl, true)
        // Another account claims the row the moment the attempt is prepared,
        // after the post-GET check and before the send guard.
        for (event in listOf("INSERT", "UPDATE")) {
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TEMP TRIGGER take_over_on_${event.lowercase()} AFTER $event
                ON book_orbit_status_agreement WHEN NEW.attempt_state = 'PREPARED'
                BEGIN UPDATE reading_progress SET owner_account = 'someone-else'; END
                """,
            )
        }
        web.enqueue(MockResponse(body = detail("unread", "auto")))

        assertEquals(SyncOutcome.Failure(SyncFailure.StaleIdentity), runSync())
        assertEquals("GET", web.takeRequest().method)
        assertEquals(1, web.requestCount)
        assertEquals("PREPARED",
            database.bookOrbitStatusAgreementDao().get(account.accountKey, bookUrl)?.attemptState)
    }

    @Test
    fun `remote status adoption refuses a row another account owns`() = runBlocking {
        database.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl, """{"href":"chapter.xhtml"}""", 0.5, updatedAt = 15,
                ownerAccount = "someone-else",
            ),
        )
        val finished = FinishedState(database.bookDao(), database.readingProgressDao())

        assertEquals(false, finished.adoptRemoteStatus(
            bookUrl, 0, account.accountKey, FinishedOverride.FINISHED, ReadingStatus.FINISHED,
        ))
        val local = database.readingProgressDao().get(bookUrl)!!
        assertEquals(FinishedOverride.NONE.ordinal, local.finishedOverride)
        assertEquals(0L, local.statusRevision)
    }
}
