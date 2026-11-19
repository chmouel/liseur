package com.chmouel.liseur.vocabulary

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VocabularyActivityTest {
    private val today = LocalDate.of(2026, 3, 30)
    private val zone = ZoneId.of("Europe/Paris")

    private fun review(day: LocalDate, rating: Recall = Recall.GOOD) = VocabularyReviewActivity(
        day.atStartOfDay(zone).toInstant().toEpochMilli(), rating.name,
    )

    @Test
    fun `empty activity includes fourteen zero days without a recall rate`() {
        val activity = vocabularyActivity(emptyList(), today, zone)
        assertEquals(14, activity.days.size)
        assertEquals(today.minusDays(13), activity.days.first().date)
        assertEquals(today, activity.days.last().date)
        assertEquals(0, activity.days.sumOf { it.reviews })
        assertEquals(0, activity.streak)
        assertNull(activity.recallRate)
        assertFalse(activity.hasReviews)
    }

    @Test
    fun `buckets follow local dates across daylight saving and exclude future days`() {
        val reviews = listOf(
            review(today.minusDays(14)),
            review(today.minusDays(13)),
            review(today.minusDays(1)),
            VocabularyReviewActivity(java.time.Instant.parse("2026-03-29T22:30:00Z").toEpochMilli(), "AGAIN"),
            review(today),
            review(today.plusDays(1)),
        )
        val activity = vocabularyActivity(reviews, today, zone)
        assertEquals(4, activity.days.sumOf { it.reviews })
        assertEquals(2, activity.days.last().reviews)
        assertEquals(2, activity.streak)
    }

    @Test
    fun `recall covers thirty calendar days and includes every rating except Again`() {
        val reviews = listOf(
            review(today.minusDays(30), Recall.AGAIN),
            review(today.minusDays(29), Recall.AGAIN),
            review(today.minusDays(20), Recall.HARD),
            review(today.minusDays(10), Recall.GOOD),
            review(today, Recall.EASY),
            review(today.plusDays(1), Recall.AGAIN),
        )
        assertEquals(0.75f, vocabularyActivity(reviews, today, zone).recallRate!!, 0.001f)
        assertNull(vocabularyActivity(listOf(reviews.first()), today, zone).recallRate)
    }

    @Test
    fun `streak spans history and allows yesterday until today is reviewed`() {
        val reviews = (1L..45L).flatMap { listOf(review(today.minusDays(it)), review(today.minusDays(it))) }
        assertEquals(45, vocabularyActivity(reviews, today, zone).streak)
        assertEquals(46, vocabularyActivity(reviews + review(today), today, zone).streak)
        assertEquals(0, vocabularyActivity(listOf(review(today.minusDays(2))), today, zone).streak)
        assertEquals(1, vocabularyActivity(listOf(review(today), review(today.minusDays(2))), today, zone).streak)
    }
}
