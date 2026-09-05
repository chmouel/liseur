package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.ReadingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SessionUploadsTest {
    private val session = ReadingSession(
        id = 7, bookUrl = "file:///book", startedAt = 1_000,
        endedAt = 601_000, lastCheckpointAt = 601_000, durationMs = 1_800_000,
        startProgression = 0.1, endProgression = 0.2, idleMs = 0,
    )

    @Test
    fun `measured duration survives a backward wall clock correction`() {
        val json = SessionUploads.toJson(session, "device", "work", null, measuredTime = true)!!
        assertEquals(1_800_000, json.getLong("active_ms"))
        assertEquals(SyncOps.formatTime(601_000), json.getString("ended_at"))
        assertEquals(0, json.getLong("idle_ms"))
    }

    @Test
    fun `legacy and unnegotiated sessions retain their original wire format`() {
        assertFalse(SessionUploads.toJson(session, "device", "work", null)!!.has("active_ms"))
        val legacy = SessionUploads.toJson(
            session.copy(legacyEvidenceUnknown = true), "device", "work", null, measuredTime = true,
        )!!
        assertFalse(legacy.has("active_ms"))
        assertEquals(SessionUploads.sessionIdFor("device", session.id), legacy.getString("session_id"))
    }

    @Test
    fun `explicit zero is sent rather than omitted`() {
        val json = SessionUploads.toJson(
            session.copy(durationMs = 0), "device", "work", null, measuredTime = true,
        )!!
        assertEquals(0, json.getLong("active_ms"))
    }
}
