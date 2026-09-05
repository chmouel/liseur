package com.chmouel.liseur.domain

import java.time.LocalDate
import kotlin.math.roundToLong

/** Keep the wire's sub-millisecond tolerance before independently rounding either value. */
internal fun unionMinutes(server: Double, localMs: Long, overlap: Double): Long? {
    val limit = Long.MAX_VALUE.toDouble() / 60_000.0
    if (!server.isFinite() || !overlap.isFinite() || server < 0 || overlap < 0 ||
        server >= limit || overlap >= limit
    ) return null
    if (overlap > server && (overlap - server) * 60_000.0 >= 1.0) return null
    return unionMillis(
        (server * 60_000.0).roundToLong(),
        localMs,
        (minOf(overlap, server) * 60_000.0).roundToLong(),
    )
}

/** Subtract what the server actually counted, not the locally measured duration. */
internal fun unionMillis(server: Long, local: Long, overlap: Long): Long? {
    if (server < 0 || local < 0 || overlap < 0 || overlap > server) return null
    val remoteOnly = server - overlap
    if (local > Long.MAX_VALUE - remoteOnly) return null
    return remoteOnly + local
}

internal fun unionSessions(server: Int, local: Int, overlap: Int): Int? {
    if (server < 0 || local < 0 || overlap < 0 || overlap > server) return null
    val sum = server.toLong() - overlap + local
    return sum.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

internal fun activeDayStreak(days: Set<LocalDate>, today: LocalDate): Int {
    var day = if (today in days) today else today.minusDays(1)
    var count = 0
    while (day in days) {
        count++
        day = day.minusDays(1)
    }
    return count
}

/** Nonoverlapping, inclusive requests; a request limit is not a history limit. */
internal fun calendarChunks(from: LocalDate, to: LocalDate, maxDays: Long = 4_000): List<Pair<LocalDate, LocalDate>> {
    require(maxDays > 0)
    if (from > to) return emptyList()
    val chunks = mutableListOf<Pair<LocalDate, LocalDate>>()
    var start = from
    while (start <= to) {
        val end = minOf(start.plusDays(maxDays - 1), to)
        chunks += start to end
        if (end == to) break
        start = end.plusDays(1)
    }
    return chunks
}
