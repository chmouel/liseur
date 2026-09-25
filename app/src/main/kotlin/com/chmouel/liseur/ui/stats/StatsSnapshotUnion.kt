package com.chmouel.liseur.ui.stats

import com.chmouel.liseur.data.liseursync.SnapshotTotals
import com.chmouel.liseur.data.liseursync.WorkTotals
import com.chmouel.liseur.data.liseursync.snapshotResidual
import com.chmouel.liseur.domain.ReadingStats
import com.chmouel.liseur.domain.StatsBook
import com.chmouel.liseur.domain.unionMinutes
import com.chmouel.liseur.domain.unionSessions

internal data class UnitedStats(val stats: ReadingStats, val headline: StatsHeadline)

internal fun uniteSnapshot(
    local: ReadingStats,
    knownBooks: Map<String, StatsBook>,
    firstReadAtByUrl: Map<String, Long>,
    snapshot: SnapshotTotals,
): UnitedStats? {
    val residual = snapshotResidual(snapshot) ?: return null
    val total = unionMinutes(
        snapshot.summary.activeMinutes, local.totalMs,
        snapshot.overlapMinutes,
    ) ?: return null
    val sessions = unionSessions(snapshot.summary.sessions, local.sessions, snapshot.overlapSessions)
        ?: return null
    // The legacy renderer adds its local contribution to remote values.
    // Here that contribution is the entire captured input, not upload flags,
    // and remote values contain only reading outside that exact input.
    val captured = local.copy(
        pendingMs = local.totalMs,
        pendingSessions = local.sessions,
        books = local.books.map { it.copy(pendingMs = it.totalMs, pendingSessions = it.sessions) },
        recent = local.recent.map { it.copy(pendingMs = it.totalMs) },
    )
    val merged = ReadingStatsViewModel.mergeDashboard(
        captured, knownBooks, residual.days, WorkTotals(residual.known, residual.elsewhere), firstReadAtByUrl,
    ).copy(totalMs = total, sessions = sessions, streakDays = snapshot.combinedStreak)
    val serverPace = snapshot.summary.progressionPerHour
    return UnitedStats(
        merged,
        StatsHeadline(
            total, sessions, snapshot.combinedStreak,
            progressionPerHour = serverPace,
            serverOnlyPace = serverPace != null,
        ),
    )
}
