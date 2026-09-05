package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.SessionTransmission
import com.chmouel.liseur.data.db.WorkAlias
import java.time.LocalDate

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
)
