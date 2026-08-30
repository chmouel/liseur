package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.calibre.CredentialCipher
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.domain.StatsRange
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
        assertNull(insights().summary(today = TODAY))
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
    fun `a server that understands the span is asked once for it`() = runTest {
        connect()
        server.enqueue(
            ok(
                """{"from":"2026-08-10","to":"2026-08-11","days":[""" +
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
        assertEquals(1, server.requestCount)
        assertEquals(
            "/v1/insights/calendar?from=2026-08-10&to=2026-08-11",
            server.takeRequest().target,
        )
    }

    /**
     * A server too old for `from`/`to` ignores them and answers with a
     * whole calendar year, which is exactly what an obeyed request would
     * look like if nobody checked. The echoed `from` is the check, and
     * without it the span is collected a year at a time instead.
     */
    @Test
    fun `a server that ignores the span is asked year by year`() = runTest {
        connect()
        server.enqueue(ok("""{"year":2025,"days":[{"date":"2025-12-30","minutes":5}]}"""))
        server.enqueue(ok("""{"year":2025,"days":[{"date":"2025-12-31","minutes":11}]}"""))
        server.enqueue(ok("""{"year":2026,"days":[{"date":"2026-01-01","minutes":7}]}"""))

        val days = insights().calendar(
            from = LocalDate.of(2025, 12, 31),
            to = LocalDate.of(2026, 1, 1),
        )!!

        assertEquals(
            listOf(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)),
            days.map { it.date },
        )
        assertEquals(3, server.requestCount)
        server.takeRequest()
        assertEquals("/v1/insights/calendar?year=2025", server.takeRequest().target)
        assertEquals("/v1/insights/calendar?year=2026", server.takeRequest().target)
    }

    @Test
    fun `all book totals are mapped back through usable local aliases`() = runTest {
        connect()
        alias()
        val lastRead = "2026-08-11T16:30:00Z"
        server.enqueue(
            ok(
                """{"from":"2026-08-10","to":"2026-08-11","works":[""" +
                    """{"work_id":"w-1","sessions":8,""" +
                    """"total_active_minutes":106.25,"eta_seconds":3600,""" +
                    """"last_read_at":"$lastRead"}]}""",
            ),
        )

        val book = insights().allBooks(today = TODAY)!![BOOK]!!

        assertEquals(8, book.sessions)
        assertEquals(106.25, book.activeMinutes, 0.001)
        assertEquals("w-1", book.workId)
        assertEquals(Instant.parse(lastRead).toEpochMilli(), book.lastReadAt)
        assertEquals(
            "/v1/insights/works?from=2026-08-10&to=2026-08-11",
            server.takeRequest().target,
        )
    }

    /**
     * The rows below the headline are asked about the days the headline
     * describes. While they were not, the total on top counted thirty
     * days and the list beneath it counted a lifetime, under one label.
     */
    @Test
    fun `the by-book list is asked for the selected span`() = runTest {
        connect()
        alias()
        server.enqueue(ok("""{"range_days":0,"works":[]}"""))

        assertEquals(emptyMap<String, WorkInsights>(), insights().allBooks(StatsRange.ALL_TIME, TODAY))

        assertEquals("/v1/insights/works?range=all", server.takeRequest().target)
    }

    @Test
    fun `the headline is asked for the selected span`() = runTest {
        connect()
        server.enqueue(
            ok(
                """{"from":"2025-08-12","to":"2026-08-11",""" +
                    """"total_active_minutes":90,"sessions":4}""",
            ),
        )

        assertEquals(4, insights().summary(StatsRange.LAST_YEAR, TODAY)!!.sessions)
        assertEquals(
            "/v1/insights/summary?from=2025-08-12&to=2026-08-11",
            server.takeRequest().target,
        )
    }

    /**
     * A server too old to understand a span ignores it and answers about
     * some other one, and the totals give no sign of it: thirty days of
     * reading and ten years of it are both just a number of minutes. So
     * an answer that does not name back the days it counted is refused,
     * and the reader keeps their own figures rather than being shown a
     * lifetime under this month's caption.
     */
    @Test
    fun `an answer about days nobody asked about is refused`() = runTest {
        connect()
        alias()
        server.enqueue(ok("""{"range_days":30,"total_active_minutes":9000,"sessions":400}"""))
        server.enqueue(
            ok("""{"works":[{"work_id":"w-1","sessions":80,"total_active_minutes":9000}]}"""),
        )

        assertNull(insights().summary(StatsRange.LAST_30_DAYS, TODAY))
        assertNull(insights().allBooks(StatsRange.LAST_30_DAYS, TODAY))
    }

    /** Nor is a span the server narrowed on its own account accepted. */
    @Test
    fun `an answer about a different span is refused`() = runTest {
        connect()
        server.enqueue(
            ok(
                """{"from":"2026-08-10","to":"2026-08-11",""" +
                    """"total_active_minutes":90,"sessions":4}""",
            ),
        )

        assertNull(insights().summary(StatsRange.LAST_30_DAYS, TODAY))
    }

    /**
     * A lifetime is asked for by name and checked like any other span.
     *
     * It has no bounds to echo, so the proof is `range_days` being
     * nought. Leaving the span out instead would be worse than useless:
     * the summary answers a request it understands nothing of with its
     * own default of thirty days, which is how a month of reading came
     * to be labelled a lifetime.
     */
    @Test
    fun `a lifetime is asked for by name`() = runTest {
        connect()
        server.enqueue(ok("""{"range_days":0,"total_active_minutes":9000,"sessions":400}"""))

        assertEquals(400, insights().summary(StatsRange.ALL_TIME, TODAY)!!.sessions)
        assertEquals("/v1/insights/summary?range=all", server.takeRequest().target)
    }

    /**
     * A server that answered `range=all` with a horizon of its own — the
     * ten years liseur-sync used to apply — is answering about a span
     * nobody asked for, and one older still does not say what it counted
     * at all. Neither may be captioned as a lifetime.
     */
    @Test
    fun `a bounded answer to a lifetime request is refused`() = runTest {
        connect()
        server.enqueue(ok("""{"range_days":3660,"total_active_minutes":9000,"sessions":400}"""))
        assertNull(insights().summary(StatsRange.ALL_TIME, TODAY))

        server.enqueue(ok("""{"total_active_minutes":9000,"sessions":400}"""))
        assertNull(insights().summary(StatsRange.ALL_TIME, TODAY))
    }

    @Test
    fun `a reading pace is carried through and a missing one is not invented`() = runTest {
        connect()
        val span = """"from":"2026-08-10","to":"2026-08-11""""
        server.enqueue(
            ok("""{$span,"total_active_minutes":90,"sessions":4,"speed_prog_per_hour":0.25}"""),
        )
        server.enqueue(ok("""{$span,"total_active_minutes":90,"sessions":4}"""))

        assertEquals(0.25, insights().summary(today = TODAY)!!.progressionPerHour!!, 0.0001)
        assertNull(insights().summary(today = TODAY)!!.progressionPerHour)
    }

    /** A reader who has read nothing in the span has no figures, not zeroes. */
    @Test
    fun `an empty span is no answer at all`() = runTest {
        connect()
        server.enqueue(
            ok(
                """{"from":"2026-08-10","to":"2026-08-11",""" +
                    """"total_active_minutes":0,"sessions":0}""",
            ),
        )

        assertNull(insights().summary(StatsRange.THIS_WEEK, TODAY))
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
        val TODAY: LocalDate = LocalDate.of(2026, 8, 11)
        const val BOOK = "content://sd/book.epub"
    }
}
