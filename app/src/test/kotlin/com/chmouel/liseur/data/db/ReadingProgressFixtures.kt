package com.chmouel.liseur.data.db

suspend fun ReadingProgressDao.recordLocal(
    bookUrl: String,
    locatorJson: String,
    progression: Double?,
    @Suppress("UNUSED_PARAMETER") readingSpeed: Double?,
    status: String?,
    updatedAt: Long,
) = recordLocal(
    bookUrl = bookUrl,
    locatorJson = locatorJson,
    progression = progression,
    readingSecondsPerPosition = null,
    readingPaceSamples = null,
    readingPaceElapsedMs = null,
    readingPaceEvidence = null,
    status = status,
    updatedAt = updatedAt,
)
