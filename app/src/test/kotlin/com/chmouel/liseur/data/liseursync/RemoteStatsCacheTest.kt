package com.chmouel.liseur.data.liseursync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteStatsDao
import com.chmouel.liseur.data.db.RemoteStatsDay
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.domain.StatsRange
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
class RemoteStatsCacheTest {
    private lateinit var db: LiseurDatabase
    private lateinit var dao: RemoteStatsDao
    private lateinit var cache: RemoteStatsCache

    private val peer = "liseursync|https://sync|acc-1"
    private val paris = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 9, 24) // Thursday
    private val monday = LocalDate.of(2026, 9, 21)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.remoteStatsDao()
        cache = RemoteStatsCache(dao, db.remoteServerDao())
        runBlocking { db.remoteServerDao().upsert(server) }
    }

    private val server = RemoteServer(
        kind = ServerKind.LISEUR_SYNC, baseUrl = "https://sync",
        username = "reader", passwordCipher = null, apiKeyCipher = null, accountId = "device",
        userId = null, koboTokenCipher = null, canDownload = true, addedAt = 1,
        catalogSyncedAt = null, positionSyncedAt = null, syncToken = null,
        liseurTokenCipher = null, liseurAccountId = "acc-1",
    )

    @After
    fun close() = db.close()

    /** 12 h across the week; 4 h of it, two sittings of one work, were this device's. */
    private fun week() = SnapshotTotals(
        summary = InsightsSummary(activeMinutes = 720.0, sessions = 30, streakDays = 9),
        books = WorkTotals(
            byBookUrl = mapOf("file:///here.epub" to WorkInsights(12, 300.0, null, workId = "w-here")),
            elsewhere = listOf(
                WorkInsights(18, 420.0, null, workId = "w-web", title = "On the web"),
                WorkInsights(0, 0.0, null, workId = "w-idle", title = "Nothing this week"),
            ),
        ),
        days = listOf(
            InsightDay(monday, 180.0),
            InsightDay(monday.plusDays(1), 200.0),
            InsightDay(monday.plusDays(2), 100.0),
            InsightDay(today, 240.0),
        ),
        overlapMinutes = 240.0,
        overlapSessions = 2,
        overlapBooks = mapOf("w-here" to (240.0 to 2)),
        overlapDays = mapOf(today to 240.0),
        combinedStreak = 9,
    )

    @Test
    fun `a week keeps other devices' days, sittings and works without this device's`() = runTest {
        cache.save(peer, paris, today, StatsRange.THIS_WEEK, monday, week())

        val days = dao.days(peer, paris.id).associate { it.date to it.residualMs }
        assertEquals(180 * 60_000L, days[monday.toString()])
        assertEquals(0L, days[today.toString()])
        val window = dao.windows(peer, paris.id).single()
        assertEquals("7d", window.rangeId)
        assertEquals(monday.toString(), window.fromDate)
        assertEquals(today.toString(), window.today)
        assertEquals(28, window.residualSessions)
        assertEquals("w-here\nw-web", window.workIds)
        assertEquals(9, window.combinedStreak)
    }

    @Test
    fun `a year snapshot updates days but names no week or month`() = runTest {
        cache.save(peer, paris, today, StatsRange.THIS_YEAR, today.withDayOfYear(1), week())

        assertEquals(4, dao.days(peer, paris.id).size)
        assertTrue(dao.windows(peer, paris.id).isEmpty())
    }

    @Test
    fun `days saved alone name no window`() = runTest {
        cache.save(peer, paris, today, null, today.minusDays(6), week())
        assertTrue(dao.days(peer, paris.id).isNotEmpty())
        assertTrue(dao.windows(peer, paris.id).isEmpty())
    }

    @Test
    fun `a snapshot for an account no longer connected is not kept`() = runTest {
        db.remoteServerDao().upsert(server.copy(liseurAccountId = "acc-2"))
        cache.save(peer, paris, today, StatsRange.THIS_WEEK, monday, week())
        assertTrue(dao.days(peer, paris.id).isEmpty())
        assertTrue(dao.windows(peer, paris.id).isEmpty())
    }

    @Test
    fun `another timezone and days no widget draws are dropped`() = runTest {
        dao.upsertDays(
            listOf(
                RemoteStatsDay(peer, "2026-09-20", "UTC", 1),
                RemoteStatsDay(peer, "2026-06-01", paris.id, 1),
            ),
        )
        cache.save(peer, paris, today, StatsRange.THIS_WEEK, monday, week())

        assertTrue(dao.days(peer, "UTC").isEmpty())
        assertTrue(dao.days(peer, paris.id).none { it.date == "2026-06-01" })
    }

    @Test
    fun `a snapshot with an overlap larger than the server's total is not kept`() = runTest {
        cache.save(peer, paris, today, StatsRange.THIS_WEEK, monday, week().copy(overlapMinutes = 800.0))

        assertTrue(dao.days(peer, paris.id).isEmpty())
        assertNull(dao.windows(peer, paris.id).firstOrNull())
    }

    @Test
    fun `rekeying replaces what was under the new key and clearing removes it all`() = runTest {
        cache.save(peer, paris, today, StatsRange.THIS_WEEK, monday, week())
        val next = "liseursync|https://sync|acc-2"
        dao.upsertDays(listOf(RemoteStatsDay(next, "2026-01-01", paris.id, 5)))

        dao.rekeyPeer(peer, next)

        assertTrue(dao.days(peer, paris.id).isEmpty())
        assertEquals(4, dao.days(next, paris.id).size)
        assertTrue(dao.days(next, paris.id).none { it.date == "2026-01-01" })
        dao.clearPeer(next)
        assertTrue(dao.days(next, paris.id).isEmpty())
        assertTrue(dao.windows(next, paris.id).isEmpty())
    }
}
