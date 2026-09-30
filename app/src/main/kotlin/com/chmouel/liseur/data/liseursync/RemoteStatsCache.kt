package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.RemoteServerDao
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
 * The shared refresher writes only proven snapshots here. The widget adds this device's live sittings on top,
 * which is why only the residual is kept: a sitting read here after the
 * snapshot is then still counted once.
 *
 * A save is checked against the connected account inside the same
 * transaction, so a snapshot that lands after a disconnect or a rekey
 * cannot put rows back under a key that was just cleared.
 */
class RemoteStatsCache(
    private val dao: RemoteStatsDao,
    private val serverDao: RemoteServerDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A null [range] saves the days alone, with no window row. */
    internal suspend fun save(
        accountKey: String,
        zone: ZoneId,
        today: LocalDate,
        range: StatsRange?,
        from: LocalDate?,
        totals: SnapshotTotals,
    ) {
        val residual = snapshotResidual(totals) ?: return
        val refreshedAt = now()
        val oldest = today.minusDays(KEPT_DAYS - 1)
        val days = residual.days
            .filter { it.date in oldest..today }
            .map {
                RemoteStatsDay(
                    accountKey = accountKey,
                    date = it.date.toString(),
                    zone = zone.id,
                    residualMs = (it.activeMinutes * 60_000.0).roundToLong(),
                    refreshedAt = refreshedAt,
                )
            }
        val window = if (range != null && from != null && range in WINDOW_RANGES) {
            RemoteStatsWindow(
                accountKey = accountKey,
                rangeId = range.id,
                fromDate = from.toString(),
                today = today.toString(),
                zone = zone.id,
                residualSessions = residual.sessions,
                workIds = residual.workIds.joinToString("\n"),
                combinedStreak = residual.combinedStreak,
                refreshedAt = refreshedAt,
            )
        } else {
            null
        }
        inTransaction {
            if (serverDao.get()?.accountKey != accountKey) return@inTransaction
            dao.save(accountKey, zone.id, oldest.toString(), days, window)
        }
    }

    companion object {
        /** A month of daily totals, with a margin for rollover and streaks. */
        const val KEPT_DAYS = 62L

        private val WINDOW_RANGES = setOf(StatsRange.THIS_WEEK, StatsRange.THIS_MONTH)
    }
}
