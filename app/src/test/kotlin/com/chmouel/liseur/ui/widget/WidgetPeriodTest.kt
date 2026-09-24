package com.chmouel.liseur.ui.widget

import com.chmouel.liseur.domain.SessionSpan
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
