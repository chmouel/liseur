package com.chmouel.liseur.ui.widget

import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.StatsRange
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WidgetPeriodTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2024, 3, 1)
    private val hour = 3_600_000L

    private fun session(date: String, duration: Long = hour): SessionSpan {
        val end = Instant.parse(date).toEpochMilli()
        return SessionSpan("book", end - duration, duration, lastReadAt = end)
    }

    @Test
    fun `all ranges include leap day only where it belongs`() {
        val stats = widgetStats(
            listOf(session("2024-01-01T12:00:00Z"), session("2024-02-29T12:00:00Z"), session("2024-03-01T12:00:00Z")),
            zone, today, DayOfWeek.MONDAY,
        )
        assertEquals(listOf(StatsRange.THIS_WEEK, StatsRange.THIS_MONTH, StatsRange.THIS_YEAR), stats.periods.map { it.range })
        assertEquals(listOf(2 * hour, hour, 3 * hour), stats.periods.map { it.totalMs })
    }

    @Test
    fun `week crossing New Year retains last December but year does not`() {
        val stats = widgetStats(
            listOf(session("2024-12-30T12:00:00Z"), session("2025-01-01T12:00:00Z")),
            zone, LocalDate.of(2025, 1, 1), DayOfWeek.MONDAY,
        )
        assertEquals(listOf(2 * hour, hour, hour), stats.periods.map { it.totalMs })
    }

    @Test
    fun `locale week start and account end day determine membership`() {
        val spans = listOf(session("2024-03-04T00:30:00Z", 2 * hour))
        val day = LocalDate.of(2024, 3, 4)
        assertEquals(2 * hour, widgetStats(spans, zone, day, DayOfWeek.MONDAY).periods.first().totalMs)
        assertEquals(0L, widgetStats(spans, ZoneId.of("America/Los_Angeles"), day, DayOfWeek.MONDAY).periods.first().totalMs)
        assertEquals(2 * hour, widgetStats(spans, ZoneId.of("America/Los_Angeles"), day, DayOfWeek.SUNDAY).periods.first().totalMs)
    }

    @Test
    fun `DST and midnight use end day without splitting measured duration`() {
        val spans = listOf(session("2024-03-31T22:30:00Z", 4 * hour))
        val stats = widgetStats(spans, ZoneId.of("Europe/Paris"), LocalDate.of(2024, 4, 1), DayOfWeek.MONDAY)
        assertEquals(listOf(4 * hour, 4 * hour, 4 * hour), stats.periods.map { it.totalMs })
    }

    @Test
    fun `annual residual older than 62 days adds once to local reading`() {
        val day = LocalDate.of(2024, 9, 1)
        val remote = WidgetRemote(days = mapOf(day.withDayOfYear(1) to 5 * hour, day to hour))
        val stats = widgetStats(listOf(session("2024-09-01T12:00:00Z")), zone, day, DayOfWeek.MONDAY, remote)
        assertEquals(listOf(2 * hour, 2 * hour, 7 * hour), stats.periods.map { it.totalMs })
        assertEquals(WidgetScope.LAST_SYNC, stats.scope(0))
    }

    @Test
    fun `footer requires fresh complete coverage of every displayed day`() {
        val now = 9_000_000L
        val days = generateSequence(today.withDayOfYear(1)) { it.plusDays(1) }.takeWhile { it <= today }.toList()
        val remote = WidgetRemote(
            days = days.associateWith { if (it == today) hour else 0 },
            refreshedAtByDay = days.associateWith { now },
        )
        fun summary(data: WidgetRemote) = widgetStats(emptyList(), zone, today, DayOfWeek.MONDAY, data)
        assertNull(summary(remote).scope(now))
        assertNull(summary(remote).scope(now + hour))
        assertEquals(WidgetScope.LAST_SYNC, summary(remote).scope(now + hour + 1))
        assertEquals(WidgetScope.LAST_SYNC, summary(remote.copy(days = remote.days - days.first())).scope(now))
        assertEquals(WidgetScope.LAST_SYNC, summary(remote.copy(refreshedAtByDay = remote.refreshedAtByDay - days.first())).scope(now))
        assertEquals(WidgetScope.LAST_SYNC, summary(remote.copy(refreshedAtByDay = remote.refreshedAtByDay + (days.first() to 0L))).scope(now))
        assertEquals(WidgetScope.THIS_DEVICE, summary(remote.copy(days = days.associateWith { 0L })).scope(now + hour + 1))
        assertNull(summary(remote.copy(days = days.associateWith { 0L })).scope(now))
    }

    @Test
    fun `empty and future reading do not fabricate activity`() {
        val stats = widgetStats(listOf(session("2025-01-01T12:00:00Z")), zone, today, DayOfWeek.MONDAY)
        assertEquals(listOf(0L, 0L, 0L), stats.periods.map { it.totalMs })
        assertEquals(WidgetScope.THIS_DEVICE, stats.scope(0))
    }

    @Test
    fun `compact duration keeps annual hours and explicit zero`() {
        assertEquals(CompactDuration.Minutes(0), compactDuration(0))
        assertEquals(CompactDuration.UnderMinute, compactDuration(59_999))
        assertEquals(CompactDuration.Minutes(1), compactDuration(60_000))
        assertEquals(CompactDuration.Hours(126, 10), compactDuration(126 * hour + 10 * 60_000L))
    }

    @Test
    fun `large fonts use the compact stats layout even at the minimum height`() {
        assertTrue(statsCompactLayout(220f, 2f))
        assertTrue(statsCompactLayout(180f, 1f))
        assertFalse(statsCompactLayout(360f, 1f))
    }

    @Test
    fun `local-only stats have no source footer`() {
        val stats = widgetStats(emptyList(), zone, today, DayOfWeek.MONDAY)
        assertEquals(WidgetScope.THIS_DEVICE, stats.scope(0))
        assertNull(stats.scopeLabelResource(0))
    }
}
