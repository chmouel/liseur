package com.chmouel.liseur.tts

/**
 * Reading aloud pauses at [endsAt], on the elapsed-realtime clock,
 * [minutes] after the timer was set.
 */
internal data class SleepTimer(val minutes: Int, val endsAt: Long) {
    /** Whole minutes left at [now], rounded up so the last minute shows 1 rather than 0. */
    fun minutesLeft(now: Long): Int = (((endsAt - now).coerceAtLeast(0) + MINUTE_MS - 1) / MINUTE_MS).toInt()

    companion object {
        val CHOICES = listOf(5, 15, 30, 45, 60)
        const val MINUTE_MS = 60_000L

        fun starting(minutes: Int, now: Long) = SleepTimer(minutes, now + minutes * MINUTE_MS)
    }
}
