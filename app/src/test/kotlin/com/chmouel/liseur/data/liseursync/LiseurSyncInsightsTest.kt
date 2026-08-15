package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.db.WorkAlias
import java.net.InetAddress
import java.time.Instant
import java.time.LocalDate
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading added up across every device.
 *
 * All of this is decoration on a screen that already works, so the
 * behaviour worth pinning down is what happens when it is unavailable:
 * a reader offline on a train should see their own figures, not a
 * complaint, and an estimate the server declined to make must not be
 * invented on the way to the screen.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class LiseurSyncInsightsTest {

    private lateinit var server: MockWebServer
    private lateinit var db: LiseurDatabase

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
    }

    @After
    fun close() {
        server.close()
        db.close()
        CredentialCipher.keyForTesting = null
    }


    @Test
    fun `no account means no request`() = runTest {
        assertNull(insights().summary())
        assertNull(insights().forBook(BOOK))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an estimate the server declined to make is not invented`() = runTest {
        connect()
        alias()
        server.enqueue(
            ok("""{"sessions":2,"total_active_minutes":40,"eta_seconds":null}"""),
        )

        val book = insights().forBook(BOOK)!!

        assertEquals(2, book.sessions)
        assertNull(book.etaSeconds)
    }

    @Test
    fun `a book this server has no name for is not asked about`() = runTest {
        connect()

        assertNull(insights().forBook(BOOK))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a book with an estimate reports it in seconds`() = runTest {
        connect()
        alias()
        server.enqueue(ok("""{"sessions":3,"eta_seconds":5400.0}"""))

        assertEquals(5400.0, insights().forBook(BOOK)!!.etaSeconds!!, 0.001)
    }

    @Test
    fun `calendar returns only the requested days`() = runTest {
        connect()
        server.enqueue(
            ok(
                """{"year":2026,"days":[""" +
                    """{"date":"2026-08-09","minutes":5},""" +
                    """{"date":"2026-08-10","minutes":23.5}]}""",
            ),
        )

        val days = insights().calendar(
            from = LocalDate.of(2026, 8, 10),
            to = LocalDate.of(2026, 8, 11),
        )!!

        assertEquals(listOf(LocalDate.of(2026, 8, 10)), days.map { it.date })
        assertEquals(23.5, days.single().activeMinutes, 0.001)
        assertEquals("/v1/insights/calendar?year=2026", server.takeRequest().target)
    }

    @Test
    fun `all book totals are mapped back through usable local aliases`() = runTest {
        connect()
        alias()
        val lastRead = "2026-08-11T16:30:00Z"
        server.enqueue(
            ok(
                """{"works":[{"work_id":"w-1","sessions":8,""" +
                    """"total_active_minutes":106.25,"eta_seconds":3600,""" +
                    """"last_read_at":"$lastRead"}]}""",
            ),
        )

        val book = insights().allBooks()!![BOOK]!!

        assertEquals(8, book.sessions)
        assertEquals(106.25, book.activeMinutes, 0.001)
        assertEquals(Instant.parse(lastRead).toEpochMilli(), book.lastReadAt)
        assertEquals("/v1/insights/works", server.takeRequest().target)
    }

    private fun insights() = LiseurSyncInsights(
        serverDao = db.remoteServerDao(),
        identityDao = db.workIdentityDao(),
    )

    private suspend fun connect() {
        db.remoteServerDao().upsert(
            RemoteServer(
                kind = ServerKind.LISEUR_SYNC,
                baseUrl = "http://127.0.0.1:${server.port}",
                username = "ada",
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
    }

    private suspend fun alias() = db.workIdentityDao().upsert(
        WorkAlias(
            bookUrl = BOOK,
            peerId = "liseursync|http://127.0.0.1:${server.port}|ada",
            workId = "w-1",
            confidence = "high",
            confirmed = true,
            resolvedAt = NOW,
        ),
    )

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val BOOK = "content://sd/book.epub"
    }
}
