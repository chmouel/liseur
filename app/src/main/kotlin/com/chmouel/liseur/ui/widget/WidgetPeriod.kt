package com.chmouel.liseur.ui.widget

import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.StatsRange
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class WidgetRemote(
    val zone: ZoneId? = null,
    /** Proven other-device residuals; absent days are not equivalent to zero. */
    val days: Map<LocalDate, Long> = emptyMap(),
    val refreshedAtByDay: Map<LocalDate, Long> = emptyMap(),
)

data class PeriodStats(
    val range: StatsRange,
    val totalMs: Long,
    val remoteMs: Long,
    val remoteCovered: Boolean,
    val remoteUpdatedAt: Long?,
)

enum class WidgetScope { LAST_SYNC, THIS_DEVICE }

data class WidgetStats(val periods: List<PeriodStats>) {
    fun scope(now: Long): WidgetScope? = when {
        periods.all { it.remoteCovered && it.remoteUpdatedAt?.let { stamp -> now - stamp in 0..3_600_000L } == true } -> null
        periods.any { it.remoteMs > 0 } -> WidgetScope.LAST_SYNC
        else -> WidgetScope.THIS_DEVICE
    }
}

/** All three ranges use one snapshot and the account-local end day of each sitting. */
fun widgetStats(
    sessions: List<SessionSpan>,
    zone: ZoneId,
    today: LocalDate,
    weekStart: DayOfWeek,
    remote: WidgetRemote? = null,
): WidgetStats {
    val local = sessions.filter { it.durationMs > 0 }
        .groupBy { Instant.ofEpochMilli(it.lastReadAt).atZone(zone).toLocalDate() }
        .mapValues { (_, spans) -> spans.sumOf { it.durationMs } }
    return WidgetStats(listOf(StatsRange.THIS_WEEK, StatsRange.THIS_MONTH, StatsRange.THIS_YEAR).map { range ->
        val from = checkNotNull(range.startDate(today, weekStart))
        val dates = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= today }.toList()
        val remoteMs = dates.sumOf { remote?.days?.get(it) ?: 0L }
        val covered = dates.all { remote?.days?.containsKey(it) == true }
        val stamps = dates.mapNotNull { remote?.refreshedAtByDay?.get(it) }
        PeriodStats(
            range = range,
            totalMs = dates.sumOf { local[it] ?: 0L } + remoteMs,
            remoteMs = remoteMs,
            remoteCovered = covered,
            remoteUpdatedAt = if (stamps.size == dates.size) stamps.minOrNull() else null,
        )
    })
}

sealed interface CompactDuration {
    data object UnderMinute : CompactDuration
    data class Minutes(val minutes: Int) : CompactDuration
    data class Hours(val hours: Long, val minutes: Int) : CompactDuration
}

fun compactDuration(millis: Long): CompactDuration {
    val totalMinutes = millis / 60_000L
    return when {
        millis <= 0 -> CompactDuration.Minutes(0)
        totalMinutes < 1 -> CompactDuration.UnderMinute
        totalMinutes < 60 -> CompactDuration.Minutes(totalMinutes.toInt())
        else -> CompactDuration.Hours(totalMinutes / 60, (totalMinutes % 60).toInt())
    }
}
