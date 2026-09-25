package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.SessionTransmission
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.domain.ComparisonPeriod
import com.chmouel.liseur.domain.DateSpan
import com.chmouel.liseur.domain.unionMinutes
import com.chmouel.liseur.domain.unionSessions
import java.time.LocalDate
import java.time.LocalTime

/** Acknowledgements are bookkeeping, not a change to the captured reading. */
internal fun statsSessions(sessions: List<ReadingSession>): List<ReadingSession> =
    sessions.map { it.copy(uploadedAt = null) }.sortedBy { it.id }

internal data class StatsAlias(val bookUrl: String, val workId: String, val editionSha: String?)

internal fun statsAliases(aliases: List<WorkAlias>): List<StatsAlias> =
    aliases.filter { it.usable }.map { StatsAlias(it.bookUrl, it.workId, it.editionSha) }.sortedBy { it.bookUrl }

internal data class CapturedStatsSessions(
    val sessions: List<ReadingSession>,
    val transmissions: List<SessionTransmission>,
    val transmissionSessionIds: Set<Long>? = null,
) {
    fun matches(sessions: List<ReadingSession>, transmissions: List<SessionTransmission>): Boolean =
        this.sessions == statsSessions(sessions) &&
            this.transmissions == transmissions
                .filter { transmissionSessionIds == null || it.sessionId in transmissionSessionIds }
                .sortedBy { it.sessionId }
}

/** Server contributions in one coherent snapshot, including actual overlap with this device. */
data class SnapshotTotals(
    val summary: InsightsSummary,
    val books: WorkTotals,
    val days: List<InsightDay>,
    val overlapMinutes: Double,
    val overlapSessions: Int,
    val overlapBooks: Map<String, Pair<Double, Int>>,
    val overlapDays: Map<LocalDate, Double>,
    val combinedStreak: Int,
    val comparison: SnapshotComparison? = null,
)

data class SnapshotComparison(
    val period: ComparisonPeriod,
    val current: DateSpan,
    val previous: DateSpan,
    val through: LocalTime,
    val currentMinutes: Double,
    val previousMinutes: Double,
    val overlapCurrentMinutes: Double,
    val overlapPreviousMinutes: Double,
)

/**
 * What a snapshot counted that this device's captured sittings did not.
 *
 * Every figure has the server's measured overlap with the candidates
 * taken out, so adding this device's own sittings on top counts each
 * sitting once. Null when any figure is not a usable number, in which
 * case nothing from the snapshot may be used.
 */
internal data class SnapshotResidual(
    val totalMs: Long,
    val sessions: Int,
    val known: Map<String, WorkInsights>,
    val elsewhere: List<WorkInsights>,
    val days: List<InsightDay>,
    val combinedStreak: Int,
) {
    /** The works with reading of their own in the residual, by server id. */
    val workIds: Set<String>
        get() = (known.values + elsewhere.filter { it.title.isNotEmpty() })
            .filter { it.workId.isNotEmpty() && (it.activeMinutes > 0 || it.sessions > 0) }
            .mapTo(sortedSetOf()) { it.workId }
}

internal fun snapshotResidual(snapshot: SnapshotTotals): SnapshotResidual? {
    if (!snapshot.summary.activeMinutes.validMinutes() ||
        !snapshot.overlapMinutes.validMinutes() || snapshot.combinedStreak < 0 ||
        snapshot.overlapBooks.values.any { !it.first.validMinutes() || it.second < 0 } ||
        snapshot.overlapDays.values.any { !it.validMinutes() } ||
        snapshot.days.any { !it.activeMinutes.validMinutes() }
    ) return null
    val totalMs = unionMinutes(snapshot.summary.activeMinutes, 0, snapshot.overlapMinutes)
        ?: return null
    val sessions = unionSessions(snapshot.summary.sessions, 0, snapshot.overlapSessions)
        ?: return null
    fun residual(work: WorkInsights): WorkInsights? {
        if (!work.activeMinutes.validMinutes()) return null
        val overlap = snapshot.overlapBooks[work.workId] ?: (0.0 to 0)
        val millis = unionMinutes(work.activeMinutes, 0, overlap.first) ?: return null
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
    return SnapshotResidual(totalMs, sessions, known, elsewhere, days, snapshot.combinedStreak)
}
