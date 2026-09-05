package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.SessionTransmission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedStatsSessionsTest {
    private val sitting = ReadingSession(
        id = 1, bookUrl = "book", startedAt = 1, endedAt = 2, lastCheckpointAt = 2, durationMs = 1,
    )
    private val evidence = SessionTransmission("peer", 1, "device", "{}")
    private val captured = CapturedStatsSessions(statsSessions(listOf(sitting)), listOf(evidence))

    @Test
    fun `acknowledgement alone keeps captured proof valid`() {
        assertTrue(captured.matches(listOf(sitting.copy(uploadedAt = 4)), listOf(evidence)))
    }

    @Test
    fun `membership content and transmission changes invalidate proof`() {
        assertFalse(captured.matches(listOf(sitting.copy(durationMs = 2)), listOf(evidence)))
        assertFalse(captured.matches(emptyList(), listOf(evidence)))
        assertFalse(captured.matches(listOf(sitting, sitting.copy(id = 2)), listOf(evidence)))
        assertFalse(captured.matches(listOf(sitting), listOf(evidence.copy(deviceId = "other"))))
        assertFalse(captured.matches(listOf(sitting), listOf(evidence.copy(payload = "{\"active_ms\":1}"))))
    }
}
