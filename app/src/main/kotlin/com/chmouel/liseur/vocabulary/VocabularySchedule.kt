package com.chmouel.liseur.vocabulary

import java.time.Instant
import java.time.ZoneId

enum class Recall { AGAIN, HARD, GOOD, EASY }

internal data class VocabularySchedule(val streak: Int, val intervalMinutes: Int, val dueAt: Long)

internal fun scheduleVocabulary(streak: Int, rating: Recall, now: Long): VocabularySchedule {
    val next = when (rating) {
        Recall.AGAIN -> 0
        Recall.HARD -> streak.coerceIn(0, 14)
        Recall.GOOD -> streak.coerceIn(0, 13) + 1
        Recall.EASY -> streak.coerceIn(0, 12) + 2
    }
    val interval = when (rating) {
        Recall.AGAIN -> 5
        Recall.HARD -> if (next == 0) 10 else (intervalFor(next) / 2).coerceAtLeast(10)
        Recall.GOOD, Recall.EASY -> intervalFor(next)
    }
    return VocabularySchedule(next, interval, now + interval * 60_000L)
}

private fun intervalFor(streak: Int): Int = when (streak) {
    1 -> 30
    2 -> 12 * 60
    3 -> 24 * 60
    4 -> 2 * 24 * 60
    5 -> 4 * 24 * 60
    6 -> 7 * 24 * 60
    7 -> 15 * 24 * 60
    else -> 30 * 24 * 60 * (1 shl (streak - 8).coerceIn(0, 6))
}

internal fun vocabularyDayStart(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
