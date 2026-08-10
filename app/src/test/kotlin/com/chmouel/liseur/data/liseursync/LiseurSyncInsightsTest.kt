package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.SyncAccount
import com.chmouel.liseur.data.db.WorkAlias
import java.net.InetAddress
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
    fun `totals come back with the range the server actually used`() = runTest {
        connect()
        server.enqueue(
            ok("""{"range_days":7,"total_active_minutes":93.5,"sessions":4,"streak_days":3}"""),
        )

        val summary = insights().summary(rangeDays = 30)!!

        // Asked for thirty, told seven. Reporting a week's reading as a
        // month's would be a lie about the reader.
        assertEquals(7, summary.rangeDays)
        assertEquals(93.5, summary.activeMinutes, 0.001)
        assertEquals(3, summary.streakDays)
    }

    @Test
    fun `a server that cannot be reached is silence, not an error`() = runTest {
        connect()
        server.enqueue(MockResponse(code = 503, body = ""))

        assertNull(insights().summary())
    }

    @Test
    fun `a sync token pasted in without a statistics one asks nothing`() = runTest {
        connect(insightsToken = null)

        assertNull(insights().summary())
        // Not even attempted: the token that may sync is refused here by
        // design, and a 403 on the statistics screen would be baffling.
        assertEquals(0, server.requestCount)
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

    private fun insights() = LiseurSyncInsights(
        accountDao = db.syncAccountDao(),
        identityDao = db.workIdentityDao(),
    )

    private suspend fun connect(insightsToken: String? = "read-secret") {
        db.syncAccountDao().upsert(
            SyncAccount(
                baseUrl = "http://127.0.0.1:${server.port}",
                username = "ada",
                tokenCipher = CredentialCipher.encrypt("device-secret"),
                insightsTokenCipher = insightsToken?.let(CredentialCipher::encrypt),
                deviceName = "Test",
                deviceKey = "device-a",
                addedAt = NOW,
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
