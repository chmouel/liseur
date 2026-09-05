package com.chmouel.liseur.ui.stats

import com.chmouel.liseur.data.liseursync.SnapshotTotals
import com.chmouel.liseur.data.liseursync.WorkInsights
import com.chmouel.liseur.data.liseursync.WorkTotals
import com.chmouel.liseur.data.liseursync.validMinutes
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
    if (!snapshot.summary.activeMinutes.validMinutes() ||
        !snapshot.overlapMinutes.validMinutes() || snapshot.combinedStreak < 0 ||
        snapshot.overlapBooks.values.any { !it.first.validMinutes() || it.second < 0 } ||
        snapshot.overlapDays.values.any { !it.validMinutes() } ||
        snapshot.days.any { !it.activeMinutes.validMinutes() }
    ) return null
    val total = unionMinutes(
        snapshot.summary.activeMinutes, local.totalMs,
        snapshot.overlapMinutes,
    ) ?: return null
    val sessions = unionSessions(snapshot.summary.sessions, local.sessions, snapshot.overlapSessions)
        ?: return null
    fun residual(work: WorkInsights): WorkInsights? {
        if (!work.activeMinutes.validMinutes()) return null
        val overlap = snapshot.overlapBooks[work.workId] ?: (0.0 to 0)
        val millis = unionMinutes(work.activeMinutes, 0, overlap.first)
            ?: return null
        val count = unionSessions(work.sessions, 0, overlap.second) ?: return null
        return work.copy(activeMinutes = millis / 60_000.0, sessions = count)
    }
    val known = snapshot.books.byBookUrl.mapValues { (_, work) -> residual(work) ?: return null }
    val elsewhere = snapshot.books.elsewhere.map { residual(it) ?: return null }
    val days = snapshot.days.map { day ->
        val remaining = unionMinutes(
            day.activeMinutes, 0,
            snapshot.overlapDays[day.date] ?: 0.0,
        ) ?: return null
        day.copy(activeMinutes = remaining / 60_000.0)
    }
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
        captured, knownBooks, days, WorkTotals(known, elsewhere), firstReadAtByUrl,
    ).copy(totalMs = total, sessions = sessions, streakDays = snapshot.combinedStreak)
    return UnitedStats(
        merged,
        StatsHeadline(
            total, sessions, snapshot.combinedStreak,
            snapshot.summary.progressionPerHour ?: local.progressionPerHour,
        ),
    )
}
