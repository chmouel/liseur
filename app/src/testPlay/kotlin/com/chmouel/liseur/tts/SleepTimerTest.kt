package com.chmouel.liseur.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {
    @Test
    fun `the minutes left round up until the timer ends`() {
        val timer = SleepTimer.starting(15, now = 1_000)
        assertEquals(15, timer.minutesLeft(1_000))
        assertEquals(15, timer.minutesLeft(1_000 + SleepTimer.MINUTE_MS - 1))
        assertEquals(14, timer.minutesLeft(1_000 + SleepTimer.MINUTE_MS))
        assertEquals(1, timer.minutesLeft(timer.endsAt - 1))
        assertEquals(0, timer.minutesLeft(timer.endsAt))
        assertEquals(0, timer.minutesLeft(timer.endsAt + 5_000))
    }
}
