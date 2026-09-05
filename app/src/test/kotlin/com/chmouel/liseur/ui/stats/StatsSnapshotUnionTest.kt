package com.chmouel.liseur.ui.stats

import com.chmouel.liseur.data.liseursync.InsightDay
import com.chmouel.liseur.data.liseursync.InsightsSummary
import com.chmouel.liseur.data.liseursync.SnapshotTotals
import com.chmouel.liseur.data.liseursync.WorkInsights
import com.chmouel.liseur.data.liseursync.WorkTotals
import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.StatsBook
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.domain.readingStats
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StatsSnapshotUnionTest {
    private val today = LocalDate.of(2026, 9, 5)
    private val zone = ZoneId.of("Europe/Paris")
    private val at = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val books = mapOf("book" to StatsBook("book", "A book", null, 0.2, false))

    @Test
    fun `acknowledgement flags cannot change headline book or calendar union`() {
        val snapshot = snapshot(overlapMinutes = 20.0)
        val results = listOf(false, true).map { uploaded ->
            val local = readingStats(
                listOf(SessionSpan("book", at, 20 * 60_000, at, uploaded)),
                books, zone, today, StatsRange.THIS_MONTH,
            )
            uniteSnapshot(local, books, emptyMap(), snapshot)!!
        }
        for (result in results) {
            assertEquals(90 * 60_000L, result.headline.totalMs)
            assertEquals(90 * 60_000L, result.stats.books.single().totalMs)
            assertEquals(90 * 60_000L, result.stats.recent.last().totalMs)
            assertEquals(4, result.headline.sessions)
            assertEquals(11, result.headline.streakDays)
        }
    }

    @Test
    fun `cached server reply retains local reading after upload acknowledgement`() {
        val local = readingStats(
            listOf(SessionSpan("book", at, 20 * 60_000, at, uploaded = true)),
            books, zone, today,
        )
        val result = uniteSnapshot(local, books, emptyMap(), snapshot(overlapMinutes = 0.0))!!
        assertEquals(110 * 60_000L, result.headline.totalMs)
        assertEquals(110 * 60_000L, result.stats.books.single().totalMs)
    }

    @Test
    fun `sparse remote calendar has every day including empty today`() {
        val local = readingStats(emptyList(), emptyMap(), zone, today, StatsRange.THIS_MONTH)
        val snapshot = snapshot(0.0).copy(
            days = listOf(InsightDay(today.minusDays(3), 90.0)),
        )
        val result = uniteSnapshot(local, books, emptyMap(), snapshot)!!
        assertEquals((1..5).map { LocalDate.of(2026, 9, it) }, result.stats.recent.map { it.date })
        assertEquals(0L, result.stats.recent.last().totalMs)
    }

    @Test
    fun `headline work and day normalize tolerated excess before rounding to milliseconds`() {
        val local = readingStats(
            listOf(SessionSpan("book", at, 20 * 60_000, at)), books, zone, today,
        )
        val server = 1000.4999999999998 / 60_000.0
        val overlap = 1000.5000000000001 / 60_000.0
        val snapshot = snapshot(overlap).copy(
            summary = InsightsSummary(server, 4, 10),
            books = WorkTotals(
                mapOf("book" to WorkInsights(4, server, null, at, "work", "A book")), emptyList(),
            ),
            days = listOf(InsightDay(today, server)),
        )
        val result = uniteSnapshot(local, books, emptyMap(), snapshot)
        assertNotNull(result)
        assertEquals(local.totalMs, result!!.headline.totalMs)
        assertEquals(local.totalMs, result.stats.books.single().totalMs)
        assertEquals(local.totalMs, result.stats.recent.last().totalMs)
        assertNull(uniteSnapshot(local, books, emptyMap(), snapshot.copy(overlapSessions = 5)))
    }

    @Test
    fun `combined headline omits pace the snapshot cannot merge`() {
        val local = readingStats(
            listOf(SessionSpan("book", at, 20 * 60_000, at, uploaded = false)),
            books, zone, today,
        )
        val withPace = snapshot(overlapMinutes = 0.0)
            .copy(summary = InsightsSummary(90.0, 4, 10, progressionPerHour = 0.5))
        val result = uniteSnapshot(local, books, emptyMap(), withPace)!!
        assertNull(result.headline.progressionPerHour)
    }

    private fun snapshot(overlapMinutes: Double): SnapshotTotals = SnapshotTotals(
        summary = InsightsSummary(90.0, 4, 10),
        books = WorkTotals(
            mapOf("book" to WorkInsights(4, 90.0, null, at, "work", "A book")), emptyList(),
        ),
        days = listOf(InsightDay(today, 90.0)),
        overlapMinutes = overlapMinutes,
        overlapSessions = if (overlapMinutes > 0) 1 else 0,
        overlapBooks = mapOf("work" to (overlapMinutes to if (overlapMinutes > 0) 1 else 0)),
        overlapDays = mapOf(today to overlapMinutes),
        combinedStreak = 11,
    )
}
