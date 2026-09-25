package com.chmouel.liseur.ui.widget

import com.chmouel.liseur.data.db.RemoteStatsDay
import com.chmouel.liseur.data.db.RemoteStatsWindow
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.StatsRange
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPeriodTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 24) // Thursday
    private val hour = TimeUnit.HOURS.toMillis(1)

    private fun span(book: String, day: LocalDate, durationMs: Long): SessionSpan {
        val start = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        return SessionSpan(
            bookUrl = book,
            startedAt = start,
            durationMs = durationMs,
            lastReadAt = start + durationMs,
            uploaded = false,
            startProgression = 0.0,
            endProgression = 0.1,
        )
    }

    private fun stats(period: WidgetPeriod, vararg spans: SessionSpan) =
        periodStats(spans.toList(), emptyMap(), zone, today, DayOfWeek.MONDAY, period)

    private val history = arrayOf(
        span("a", today, hour),
        span("b", today, 2 * hour),
        span("a", today.minusDays(1), 3 * hour),
        span("a", today.minusDays(5), 7 * hour), // last Saturday: last week
        span("c", today.minusDays(30), hour), // August
    )

    @Test
    fun `day counts today and charts the last seven days`() {
        val day = stats(WidgetPeriod.DAY, *history)
        assertEquals(3 * hour, day.totalMs)
        assertEquals(2, day.sessions)
        assertEquals(2, day.booksRead)
        assertEquals(7, day.bars.size)
        assertEquals(today.minusDays(6), day.bars.first().date)
        assertEquals(today, day.bars.last().date)
        assertEquals(7 * hour, day.peakMs)
        assertTrue(day.highlightsToday)
        assertEquals(2, day.streakDays)
    }

    @Test
    fun `week runs from the week start and is padded to seven days`() {
        val week = stats(WidgetPeriod.WEEK, *history)
        assertEquals(6 * hour, week.totalMs)
        assertEquals(3, week.sessions)
        assertEquals(LocalDate.of(2026, 9, 21), week.bars.first().date)
        assertEquals(LocalDate.of(2026, 9, 27), week.bars.last().date)
        assertEquals(0L, week.bars.last().totalMs)
        assertFalse(week.highlightsToday)
    }

    @Test
    fun `month has one bar per day of the month`() {
        val month = stats(WidgetPeriod.MONTH, *history)
        assertEquals(13 * hour, month.totalMs)
        assertEquals(30, month.bars.size)
        assertEquals(LocalDate.of(2026, 9, 1), month.bars.first().date)
        assertEquals(LocalDate.of(2026, 9, 30), month.bars.last().date)
    }

    @Test
    fun `the streak counts days before the period`() {
        val spans = (0L..9L).map { span("a", today.minusDays(it), hour) }.toTypedArray()
        assertEquals(10, stats(WidgetPeriod.DAY, *spans).streakDays)
        assertEquals(10, stats(WidgetPeriod.WEEK, *spans).streakDays)
    }

    @Test
    fun `nothing read today still shows the week behind it`() {
        val day = stats(WidgetPeriod.DAY, span("a", today.minusDays(2), hour))
        assertEquals(0L, day.totalMs)
        assertEquals(hour, day.peakMs)
    }

    private val monday = LocalDate.of(2026, 9, 21)

    private fun window(
        range: StatsRange = StatsRange.THIS_WEEK,
        from: LocalDate = monday,
        through: LocalDate = today,
        streak: Int = 0,
    ) = WidgetRemoteWindow(range, from, through, sessions = 5, workIds = setOf("w-a", "w-web"), combinedStreak = streak)

    private val remote = WidgetRemote(
        days = mapOf(
            today.minusDays(5) to 5 * hour, // last Saturday
            monday to 2 * hour,
            today to hour,
        ),
        windows = listOf(window()),
        workIdByUrl = mapOf("a" to "w-a"),
    )

    private fun stats(period: WidgetPeriod, remote: WidgetRemote) =
        periodStats(history.toList(), emptyMap(), zone, today, DayOfWeek.MONDAY, period, remote)

    @Test
    fun `week adds what other devices read and counts a shared book once`() {
        val week = stats(WidgetPeriod.WEEK, remote)
        assertEquals(9 * hour, week.totalMs)
        assertEquals(8, week.sessions)
        // a (also w-a on the server), b, and the book read on the web.
        assertEquals(3, week.booksRead)
        assertEquals(2 * hour, week.bars.first().totalMs)
        assertEquals(4 * hour, week.bars[3].totalMs)
    }

    @Test
    fun `a window proved earlier this week still adds its sittings`() {
        val earlier = remote.copy(windows = listOf(window(through = today.minusDays(1))))
        assertEquals(8, stats(WidgetPeriod.WEEK, earlier).sessions)
    }

    @Test
    fun `last week's window adds minutes by day but no sittings or books`() {
        val stale = remote.copy(windows = listOf(window(from = monday.minusWeeks(1))))
        val week = stats(WidgetPeriod.WEEK, stale)
        assertEquals(9 * hour, week.totalMs)
        assertEquals(3, week.sessions)
        assertEquals(2, week.booksRead)
    }

    @Test
    fun `month adds every covered day of the month and ignores the week's window`() {
        val month = stats(WidgetPeriod.MONTH, remote)
        assertEquals(21 * hour, month.totalMs)
        assertEquals(4, month.sessions)
    }

    @Test
    fun `day adds other devices' minutes to today and the bars but keeps this device's sittings`() {
        val day = stats(WidgetPeriod.DAY, remote)
        assertEquals(4 * hour, day.totalMs)
        assertEquals(2, day.sessions)
        assertEquals(12 * hour, day.bars[1].totalMs)
    }

    @Test
    fun `days after today are never counted`() {
        val ahead = WidgetRemote(days = mapOf(today.plusDays(1) to hour))
        assertEquals(6 * hour, stats(WidgetPeriod.WEEK, ahead).totalMs)
    }

    @Test
    fun `the streak runs across days read on either side`() {
        val both = WidgetRemote(days = mapOf(today.minusDays(2) to hour, monday to hour))
        assertEquals(4, stats(WidgetPeriod.DAY, both).streakDays)
    }

    @Test
    fun `the server's streak is used only on the day it was proved`() {
        val proved = remote.copy(windows = listOf(window(streak = 9)))
        assertEquals(9, stats(WidgetPeriod.WEEK, proved).streakDays)
        val yesterday = remote.copy(windows = listOf(window(through = today.minusDays(1), streak = 9)))
        assertEquals(2, stats(WidgetPeriod.WEEK, yesterday).streakDays)
    }

    @Test
    fun `stored rows map to what the widget adds`() {
        val mapped = widgetRemote(
            days = listOf(
                RemoteStatsDay("k", "2026-09-24", "UTC", hour),
                RemoteStatsDay("k", "not a date", "UTC", hour),
            ),
            windows = listOf(
                RemoteStatsWindow("k", "7d", "2026-09-21", "2026-09-24", "UTC", 5, "w-a\nw-web", 9),
                RemoteStatsWindow("k", "gone", "2026-09-21", "2026-09-24", "UTC", 5, "", 9),
            ),
            aliases = listOf(
                WorkAlias(bookUrl = "a", peerId = "k", workId = "w-a", confidence = "high", resolvedAt = 1),
                WorkAlias(bookUrl = "b", peerId = "k", workId = "w-b", confidence = "low", resolvedAt = 1),
            ),
        )
        assertEquals(mapOf(today to hour), mapped.days)
        assertEquals(listOf(window(streak = 9)), mapped.windows)
        assertEquals(mapOf("a" to "w-a"), mapped.workIdByUrl)
    }

    @Test
    fun `compact durations`() {
        assertEquals(CompactDuration.UnderMinute, compactDuration(59_000))
        assertEquals(CompactDuration.Minutes(45), compactDuration(TimeUnit.MINUTES.toMillis(45)))
        assertEquals(CompactDuration.Hours(1, 0), compactDuration(hour))
        assertEquals(CompactDuration.Hours(7, 20), compactDuration(TimeUnit.MINUTES.toMillis(440)))
    }

    @Test
    fun `hourly refresh runs only while a widget is placed`() {
        assertEquals(PeriodicRefresh.Cancel, periodicRefreshFor(0))
        assertEquals(PeriodicRefresh.Enqueue, periodicRefreshFor(1))
        assertEquals(PeriodicRefresh.Enqueue, periodicRefreshFor(3))
    }

    @Test
    fun `chart rows need the least padding and stay within glance's child limit`() {
        assertEquals(7, barChunkSize(7))
        assertEquals(7, barChunkSize(28))
        assertEquals(6, barChunkSize(29))
        assertEquals(6, barChunkSize(30))
        assertEquals(8, barChunkSize(31))
        for (count in 1..31) {
            val size = barChunkSize(count)
            assertTrue(size in 1..8)
            assertTrue((count + size - 1) / size <= 10)
        }
    }
}
