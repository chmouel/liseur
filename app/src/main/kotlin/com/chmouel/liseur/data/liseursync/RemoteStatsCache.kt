package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.RemoteStatsDao
import com.chmouel.liseur.data.db.RemoteStatsDay
import com.chmouel.liseur.data.db.RemoteStatsWindow
import com.chmouel.liseur.domain.StatsRange
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * Keeps what the last proven snapshot counted on other devices, so the
 * home-screen widgets can show it without asking the network.
 *
 * Only the dashboard writes here, and only with a snapshot it has
 * already accepted. The widget adds this device's live sittings on top,
 * which is why only the residual is kept: a sitting read here after the
 * snapshot is then still counted once.
 */
class RemoteStatsCache(private val dao: RemoteStatsDao) {
    internal suspend fun save(
        accountKey: String,
        zone: ZoneId,
        today: LocalDate,
        range: StatsRange,
        from: LocalDate?,
        totals: SnapshotTotals,
    ) {
        val residual = snapshotResidual(totals) ?: return
        val oldest = today.minusDays(KEPT_DAYS - 1)
        val days = residual.days
            .filter { it.date in oldest..today }
            .map {
                RemoteStatsDay(
                    accountKey = accountKey,
                    date = it.date.toString(),
                    zone = zone.id,
                    residualMs = (it.activeMinutes * 60_000.0).roundToLong(),
                )
            }
        val window = if (from != null && range in WINDOW_RANGES) {
            RemoteStatsWindow(
                accountKey = accountKey,
                rangeId = range.id,
                fromDate = from.toString(),
                today = today.toString(),
                zone = zone.id,
                residualSessions = residual.sessions,
                workIds = residual.workIds.joinToString("\n"),
                combinedStreak = residual.combinedStreak,
            )
        } else {
            null
        }
        dao.save(accountKey, zone.id, oldest.toString(), days, window)
    }

    companion object {
        /** A month plus the seven-day chart of the day widget, with a margin. */
        const val KEPT_DAYS = 62L

        private val WINDOW_RANGES = setOf(StatsRange.THIS_WEEK, StatsRange.THIS_MONTH)
    }
}
