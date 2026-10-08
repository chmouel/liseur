package com.chmouel.liseur.tts

/** When reading aloud pauses by itself. */
internal sealed interface SleepTimer {
    data object EndOfChapter : SleepTimer

    /** A deadline on the elapsed-realtime clock, which includes deep sleep. */
    data class Timed(val minutes: Int, val endsAt: Long) : SleepTimer {
        /** Whole minutes left, rounded up so the last minute shows 1 rather than 0. */
        fun minutesLeft(now: Long): Int = (((endsAt - now).coerceAtLeast(0) + MINUTE_MS - 1) / MINUTE_MS).toInt()
    }

    companion object {
        val CHOICES = listOf(5, 15, 30, 45, 60)
        const val MINUTE_MS = 60_000L

        fun starting(minutes: Int, now: Long) = Timed(minutes, now + minutes * MINUTE_MS)
    }
}
