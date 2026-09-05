package com.chmouel.liseur.data.remote

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

class LiveStreamFailure(
    val code: Int? = null,
    val retryAfter: String? = null,
    val retryMillis: Long? = null,
) :
    IOException("Live stream ended${code?.let { " ($it)" }.orEmpty()}")

/** No reset on HTTP 200: a server that opens then drops is still failing. */
internal class LiveRetry(private val jitter: () -> Double = { Random.nextDouble() }) {
    private var failures = 0

    fun delayMillis(failure: LiveStreamFailure, now: Long = System.currentTimeMillis()): Long? {
        if (failure.code in setOf(401, 403, 404, 501)) return null
        val backoff = (15_000L shl failures).coerceAtMost(300_000L)
        failures = (failures + 1).coerceAtMost(5)
        val retryAfter = if (failure.code == 429) {
            failure.retryAfter?.toLongOrNull()?.coerceIn(0, Long.MAX_VALUE / 1000)?.times(1000)
                ?: runCatching {
                    ZonedDateTime.parse(failure.retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli().minus(now).coerceAtLeast(0)
                }.getOrNull()
        } else null
        return maxOf(
            retryAfter ?: 0,
            failure.retryMillis ?: 0,
            (backoff * (1 + jitter().coerceIn(0.0, 1.0))).toLong(),
        )
    }
}
