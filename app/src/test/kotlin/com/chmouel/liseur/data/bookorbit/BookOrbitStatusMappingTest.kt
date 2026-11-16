package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.readingStatusFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookOrbitStatusMappingTest {
    @Test
    fun `automatic status sync is on`() {
        assertTrue(BookOrbitStatusSync.AUTOMATIC_SYNC_ENABLED)
    }

    @Test
    fun `local states map only explicit intent`() {
        assertEquals("read", BookOrbitStatusMapping.toRemote(FinishedOverride.FINISHED))
        assertEquals("unread", BookOrbitStatusMapping.toRemote(FinishedOverride.UNREAD))
        assertEquals("want_to_read", BookOrbitStatusMapping.toRemote(FinishedOverride.WANT_TO_READ))
        assertEquals("reading", BookOrbitStatusMapping.toRemote(FinishedOverride.READING))
        assertEquals("on_hold", BookOrbitStatusMapping.toRemote(FinishedOverride.PAUSED))
        assertEquals("abandoned", BookOrbitStatusMapping.toRemote(FinishedOverride.DID_NOT_FINISH))
        assertNull(BookOrbitStatusMapping.toRemote(FinishedOverride.NONE))
    }

    @Test
    fun `richer manual statuses map back to the choice and its merged status`() {
        mapOf(
            "want_to_read" to FinishedOverride.WANT_TO_READ,
            "on_hold" to FinishedOverride.PAUSED,
            "abandoned" to FinishedOverride.DID_NOT_FINISH,
        ).forEach { (remote, override) ->
            listOf(null, 0.0, 0.42, 0.99).forEach { progression ->
                assertEquals(
                    MappedBookOrbitStatus(readingStatusFor(progression, override), override),
                    BookOrbitStatusMapping.fromRemote(remote, "manual", progression),
                )
            }
            assertEquals(remote, BookOrbitStatusMapping.toRemote(override))
        }
    }

    @Test
    fun `supported remote states retain their meaning`() {
        assertEquals(
            MappedBookOrbitStatus(ReadingStatus.FINISHED, FinishedOverride.FINISHED),
            BookOrbitStatusMapping.fromRemote("read", "manual", null),
        )
        assertEquals(
            MappedBookOrbitStatus(ReadingStatus.READY_TO_READ, FinishedOverride.UNREAD),
            BookOrbitStatusMapping.fromRemote("unread", "manual", 0.73),
        )
        assertEquals(
            MappedBookOrbitStatus(ReadingStatus.READING, FinishedOverride.NONE),
            BookOrbitStatusMapping.fromRemote("reading", "manual", 0.42),
        )
        assertNull(BookOrbitStatusMapping.fromRemote("reading", "manual", null))
        assertNull(BookOrbitStatusMapping.fromRemote("reading", "manual", 0.99))
    }

    @Test
    fun `statuses without a safe local equivalent are preserved remotely`() {
        listOf("rereading", "skimmed", "future_status")
            .forEach { assertNull(BookOrbitStatusMapping.fromRemote(it, "manual", 0.42)) }
        listOf("read", "unread", "reading", "want_to_read", "on_hold", "abandoned")
            .forEach { assertNull(BookOrbitStatusMapping.fromRemote(it, "auto", 0.42)) }
    }
}
