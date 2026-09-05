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
    fun `non finite negative and overflowing summary values are refused`() = runTest {
        connect()
        for (minutes in listOf("\"NaN\"", "\"Infinity\"", "-1", "1e300")) {
            server.enqueue(ok("""{"range_days":0,"sessions":1,"total_active_minutes":$minutes}"""))
            assertNull(insights().summary(StatsRange.ALL_TIME, TODAY))
        }
        for (count in listOf("-1", "2147483648", "1.5", "\"NaN\"")) {
            server.enqueue(ok("""{"range_days":0,"sessions":$count,"total_active_minutes":20}"""))
            assertNull(insights().summary(StatsRange.ALL_TIME, TODAY))
        }
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

        val book = insights().allBooks(today = TODAY)!!.byBookUrl[BOOK]!!

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

        assertEquals(
            WorkTotals.Empty,
            insights().allBooks(StatsRange.ALL_TIME, TODAY),
        )

        assertEquals("/v1/insights/works?range=all", server.takeRequest().target)
    }

    @Test
    fun `the headline is asked for the selected span`() = runTest {
        connect()
        server.enqueue(
            ok(
                """{"from":"2026-01-01","to":"2026-08-11",""" +
                    """"total_active_minutes":90,"sessions":4}""",
            ),
        )

        assertEquals(4, insights().summary(StatsRange.THIS_YEAR, TODAY)!!.sessions)
        assertEquals(
            "/v1/insights/summary?from=2026-01-01&to=2026-08-11",
            server.takeRequest().target,
        )
    }

    /**
     * The baseline a period is compared against ends before today, so it
     * cannot be named by a range and a date. It is asked for outright.
     */
    @Test
    fun `a comparison baseline is asked for by its own two dates`() = runTest {
        connect()
        server.enqueue(
            ok(
                """{"from":"2026-07-01","to":"2026-07-11",""" +
                    """"total_active_minutes":45,"sessions":3}""",
            ),
        )

        val baseline = insights().summary(
            from = LocalDate.of(2026, 7, 1),
            to = LocalDate.of(2026, 7, 11),
        )

        assertEquals(3, baseline!!.sessions)
        assertEquals(45.0, baseline.activeMinutes, 1e-9)
        assertEquals(
            "/v1/insights/summary?from=2026-07-01&to=2026-07-11",
            server.takeRequest().target,
        )
    }

    /** And checked like any other, so a wrong month is never compared. */
    @Test
    fun `a baseline about the wrong days is refused`() = runTest {
        connect()
        server.enqueue(
            ok(
                """{"from":"2026-06-01","to":"2026-06-11",""" +
                    """"total_active_minutes":45,"sessions":3}""",
            ),
        )

        assertNull(
            insights().summary(
                from = LocalDate.of(2026, 7, 1),
                to = LocalDate.of(2026, 7, 11),
            ),
        )
    }

    /**
     * A work the server counted and this device has no book for is
     * carried rather than dropped (ADR-0021). Dropping it at the mapping
     * is what left a reader who did a year on a laptop seeing the year
     * in the total and not one book of it in the list.
     */
    @Test
    fun `a work with no local book is kept and named`() = runTest {
        connect()
        alias()
        server.enqueue(
            ok(
                """{"from":"2026-08-10","to":"2026-08-11","works":[""" +
                    """{"work_id":"w-1","sessions":2,"total_active_minutes":10},""" +
                    """{"work_id":"w-2","sessions":5,"total_active_minutes":50,""" +
                    """"title":"Dune","author":"Frank Herbert"}]}""",
            ),
        )

        val totals = insights().allBooks(today = TODAY)!!

        assertEquals(setOf(BOOK), totals.byBookUrl.keys)
        assertEquals(1, totals.elsewhere.size)
        val other = totals.elsewhere.single()
        assertEquals("w-2", other.workId)
        assertEquals("Dune", other.title)
        assertEquals("Frank Herbert", other.author)
        assertEquals(5, other.sessions)
    }

    /**
     * A nameless work is not listed. Its minutes are in the headline
     * either way, and a row the reader cannot identify buys nothing —
     * which is all an older server, or one whose work record has gone,
     * has to offer.
     */
    @Test
    fun `a work the server will not name is not listed`() = runTest {
        connect()
        alias()
        server.enqueue(
            ok(
                """{"from":"2026-08-10","to":"2026-08-11","works":[""" +
                    """{"work_id":"w-9","sessions":5,"total_active_minutes":50}]}""",
            ),
        )

        assertEquals(emptyList<WorkInsights>(), insights().allBooks(today = TODAY)!!.elsewhere)
    }

    /**
     * A device with nothing resolved still asks. Every work is then one
     * it has no book for, which is exactly the reader this is for: the
     * app newly installed beside a laptop that has been reading for a
     * year.
     */
    @Test
    fun `a device with no aliases still asks for the works`() = runTest {
        connect()
        server.enqueue(
            ok(
                """{"from":"2026-08-10","to":"2026-08-11","works":[""" +
                    """{"work_id":"w-2","sessions":5,"total_active_minutes":50,""" +
                    """"title":"Dune"}]}""",
            ),
        )

        val totals = insights().allBooks(today = TODAY)!!

        assertEquals(emptyMap<String, WorkInsights>(), totals.byBookUrl)
        assertEquals("Dune", totals.elsewhere.single().title)
        assertEquals(
            "/v1/insights/works?from=2026-08-10&to=2026-08-11",
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

        assertNull(insights().summary(StatsRange.THIS_MONTH, TODAY))
        assertNull(insights().allBooks(StatsRange.THIS_MONTH, TODAY))
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

        assertNull(insights().summary(StatsRange.THIS_MONTH, TODAY))
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

    /**
     * The scope is read off the token at connect and nowhere else, so an
     * account paired before it was recorded would carry its pessimistic
     * default for good — and the account screen would go on telling a
     * reader whose statistics work that they are refused (ADR-0021). An
     * answer with a body is proof the token may ask; write it down.
     */
    @Test
    fun `a body from the server proves the token may read statistics`() = runTest {
        connect()
        assertEquals(false, db.remoteServerDao().get()!!.canReadInsights)
        server.enqueue(
            ok("""{"from":"2026-08-10","to":"2026-08-11","total_active_minutes":30,"sessions":1}"""),
        )

        insights().summary(today = TODAY)

        assertEquals(true, db.remoteServerDao().get()!!.canReadInsights)
    }

    /** A 403 is the server saying the token may not ask; write that down too. */
    @Test
    fun `a refusal proves the token may not read statistics`() = runTest {
        connect()
        db.remoteServerDao().setCanReadInsights(true, db.remoteServerDao().get()!!.liseurTokenCipher)
        server.enqueue(MockResponse(code = 403, body = """{"error":"insufficient scope"}"""))

        assertNull(insights().summary(today = TODAY))

        assertEquals(false, db.remoteServerDao().get()!!.canReadInsights)
    }

    /**
     * Offline, a server too old, a malformed body: none of these says
     * anything about the token, so none of them may change the record.
     */
    @Test
    fun `a failure that is not a refusal leaves the record alone`() = runTest {
        connect()
        db.remoteServerDao().setCanReadInsights(true, db.remoteServerDao().get()!!.liseurTokenCipher)
        server.enqueue(MockResponse(code = 500, body = ""))
        assertNull(insights().summary(today = TODAY))
        assertEquals(true, db.remoteServerDao().get()!!.canReadInsights)

        // An answer about the wrong span is a body all the same: the
        // token was allowed to ask, the server just did not understand.
        db.remoteServerDao().setCanReadInsights(false, db.remoteServerDao().get()!!.liseurTokenCipher)
        server.enqueue(ok("""{"total_active_minutes":30,"sessions":1}"""))
        assertNull(insights().summary(today = TODAY))
        assertEquals(true, db.remoteServerDao().get()!!.canReadInsights)
    }

    /**
     * The server's place in a book is carried for a work this device has
     * no file for, since nobody else has one (ADR-0021). It is the one
     * figure that lets a book finished on the laptop count as finished
     * here. Nought is no place at all.
     */
    @Test
    fun `a work read elsewhere carries the server's place in it`() = runTest {
        connect()
        server.enqueue(
            ok(
                """{"from":"2026-08-10","to":"2026-08-11","works":[""" +
                    """{"work_id":"w-9","title":"Dune","sessions":3,"total_active_minutes":50,""" +
                    """"current_progression":0.985},""" +
                    """{"work_id":"w-8","title":"Emma","sessions":1,"total_active_minutes":5,""" +
                    """"current_progression":0}]}""",
            ),
        )

        val elsewhere = insights().allBooks(today = TODAY)!!.elsewhere.associateBy { it.workId }

        assertEquals(0.985, elsewhere.getValue("w-9").currentProgression!!, 0.0001)
        assertNull(elsewhere.getValue("w-8").currentProgression)
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
