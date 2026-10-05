package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.domain.StatsRange
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Refreshes the offline widget cache independently of a visible dashboard. */
class RemoteStatsRefresh(
    private val source: LiseurSyncSnapshots,
    private val sessions: ReadingSessionDao,
    private val cache: RemoteStatsCache,
    private val canAccess: suspend () -> Boolean = { true },
    private val now: (ZoneId) -> ZonedDateTime = ZonedDateTime::now,
) {
    private val turn = Mutex()

    suspend fun refresh(weekStart: DayOfWeek) = turn.withLock {
        if (!canAccess()) return@withLock
        val context = source.discover() ?: return@withLock
        refreshWindows(context, weekStart)
    }

    internal suspend fun accept(snapshot: CompleteStatsSnapshot, range: StatsRange, weekStart: DayOfWeek) =
        turn.withLock {
            if (!source.isCurrent(snapshot) || now(snapshot.zone).toLocalDate() != snapshot.today) return@withLock
            cache.save(
                snapshot.peer, snapshot.zone, snapshot.today, range,
                range.startDate(snapshot.today, weekStart), snapshot.totals,
            )
            if (canAccess()) refreshWindows(snapshot.context, weekStart, range)
        }

    private suspend fun refreshWindows(
        context: StatisticsContext,
        weekStart: DayOfWeek,
        savedRange: StatsRange? = null,
    ) {
        val today = now(context.capabilities.timezone).toLocalDate()
        val recorded = sessions.allOnce()
        for (range in setOf(StatsRange.THIS_WEEK, StatsRange.THIS_MONTH, StatsRange.THIS_YEAR) - setOfNotNull(savedRange)) {
            val snapshot = source.read(context, recorded, range, today, weekStart, compare = false) ?: continue
            if (!source.isCurrent(snapshot) || now(snapshot.zone).toLocalDate() != today) return
            cache.save(snapshot.peer, snapshot.zone, today, range, range.startDate(today, weekStart), snapshot.totals)
        }
    }
}
