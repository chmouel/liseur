package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BookOrbitStatusMappingTest {
    @Test
    fun `automatic status sync stays gated until live acceptance`() {
        assertFalse(BookOrbitStatusSync.AUTOMATIC_SYNC_ENABLED)
    }

    @Test
    fun `local states map only explicit finished and unread intent`() {
        assertEquals("read", BookOrbitStatusMapping.toRemote(FinishedOverride.FINISHED))
        assertEquals("unread", BookOrbitStatusMapping.toRemote(FinishedOverride.UNREAD))
        assertNull(BookOrbitStatusMapping.toRemote(FinishedOverride.NONE))
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
        listOf("want_to_read", "on_hold", "rereading", "skimmed", "abandoned", "future_status")
            .forEach { assertNull(BookOrbitStatusMapping.fromRemote(it, "manual", 0.42)) }
        listOf("read", "unread", "reading")
            .forEach { assertNull(BookOrbitStatusMapping.fromRemote(it, "auto", 0.42)) }
    }
}
