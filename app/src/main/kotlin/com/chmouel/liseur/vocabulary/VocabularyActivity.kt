package com.chmouel.liseur.vocabulary

import com.chmouel.liseur.domain.activeDayStreak
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class VocabularyDay(val date: LocalDate, val reviews: Int)
internal data class VocabularyActivity(
    val days: List<VocabularyDay>,
    val streak: Int,
    val recallRate: Float?,
    val hasReviews: Boolean,
)

internal fun vocabularyActivity(
    reviews: List<VocabularyReviewActivity>,
    today: LocalDate,
    zone: ZoneId,
): VocabularyActivity {
    val byDay = reviews.groupBy { Instant.ofEpochMilli(it.reviewedAt).atZone(zone).toLocalDate() }
        .filterKeys { it <= today }
    val recent = byDay.filterKeys { it >= today.minusDays(29) }.values.flatten()
    return VocabularyActivity(
        days = (13L downTo 0L).map { ago ->
            val date = today.minusDays(ago)
            VocabularyDay(date, byDay[date].orEmpty().size)
        },
        streak = activeDayStreak(byDay.keys, today),
        recallRate = recent.takeIf { it.isNotEmpty() }?.let { list ->
            list.count { it.rating != Recall.AGAIN.name }.toFloat() / list.size
        },
        hasReviews = byDay.isNotEmpty(),
    )
}
