package com.chmouel.liseur.data.calibre

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import java.net.InetAddress
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class KoboSyncRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var db: LiseurDatabase
    private lateinit var sync: KoboSyncRepository

    private val bookId = "uuid-1"
    private val bookUrl = "calibre:$bookId"

    @Before
    fun open() {
        CredentialCipher.keyForTesting =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()

        sync = KoboSyncRepository(
            serverDao = db.remoteServerDao(),
            bookDao = db.bookDao(),
            progressDao = db.readingProgressDao(),
            finishedState = FinishedState(
                bookDao = db.bookDao(),
                progressDao = db.readingProgressDao(),
            ),
            inTransaction = { work -> db.withTransaction { work() } },
        )
    }

    @After
    fun close() {
        db.close()
        server.close()
        CredentialCipher.keyForTesting = null
    }

    private suspend fun connect(): RemoteServer {
        val stored = RemoteServer(
            kind = ServerKind.CALIBRE,
            baseUrl = "http://127.0.0.1:${server.port}",
            username = "ada",
            passwordCipher = RemoteServer.seal("password"),
            apiKeyCipher = null,
            accountId = null,
            userId = null,
            koboTokenCipher = RemoteServer.seal("kobo-token"),
            canDownload = true,
            addedAt = 1,
            catalogSyncedAt = null,
            positionSyncedAt = null,
            syncToken = "before-restore",
        )
        db.remoteServerDao().upsert(stored)
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
        return stored
    }

    private fun response(body: String, headers: Headers = Headers.headersOf()) =
        MockResponse.Builder().headers(headers).body(body).build()

    @Test
    fun `a feed response from before restoring the same account cannot restore its old state`() = runTest {
        val original = connect()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.target.startsWith("/kobo/kobo-token/v1/library/sync")) {
                    return MockResponse(code = 404)
                }
                runBlocking {
                    val current = db.remoteServerDao().get()!!
                    db.remoteServerDao().upsert(
                        current.copy(
                            addedAt = current.addedAt + 1,
                            positionSyncedAt = null,
                            syncToken = null,
                        ),
                    )
                }
                return response(
                    """[{"ChangedReadingState":{"ReadingState":{"EntitlementId":"$bookId","CurrentBookmark":{"ProgressPercent":75},"StatusInfo":{"Status":"Reading"}}}}]""",
                    Headers.headersOf("x-kobo-synctoken", "after-restore"),
                )
            }
        }

        assertEquals(
            SyncOutcome.Failure(SyncFailure.Unauthorised),
            sync.syncAll(null),
        )

        val restored = db.remoteServerDao().get()!!
        assertEquals(original.accountKey, restored.accountKey)
        assertTrue(restored.addedAt > original.addedAt)
        assertNull(restored.syncToken)
        assertNull(db.readingProgressDao().get(bookUrl))
        assertEquals(1, server.requestCount)
    }
}
