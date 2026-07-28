package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStateMergeTest {

    @Test
    fun `nothing on either side is nothing to do`() {
        assertEquals(SyncDecision.InSync, mergeReadingState(null, null))
    }

    @Test
    fun `a book only read elsewhere is pulled`() {
        val remote = ReadingState(0.4, ReadingStatus.READING, updatedAt = 100)
        assertEquals(SyncDecision.Pull(remote), mergeReadingState(null, remote))
    }

    @Test
    fun `a book only read here is pushed`() {
        val local = ReadingState(0.4, ReadingStatus.READING, updatedAt = 100)
        assertEquals(SyncDecision.Push(local), mergeReadingState(local, null))
    }

    @Test
    fun `the newer side wins`() {
        val local = ReadingState(0.2, ReadingStatus.READING, updatedAt = 100)
        val remote = ReadingState(0.8, ReadingStatus.READING, updatedAt = 200)
        assertEquals(SyncDecision.Pull(remote), mergeReadingState(local, remote))
        val newerLocal = local.copy(updatedAt = 300)
        assertEquals(SyncDecision.Push(newerLocal), mergeReadingState(newerLocal, remote))
    }

    @Test
    fun `rounding to a whole percent does not count as a move`() {
        // calibre-web stores 42.3% as 42%, which must not look like reading.
        val local = ReadingState(0.423, ReadingStatus.READING, updatedAt = 300)
        val remote = ReadingState(0.42, ReadingStatus.READING, updatedAt = 100)
        assertEquals(SyncDecision.InSync, mergeReadingState(local, remote))
    }

    @Test
    fun `a real page turn is not swallowed by the epsilon`() {
        val local = ReadingState(0.5, ReadingStatus.READING, updatedAt = 300)
        val remote = ReadingState(0.42, ReadingStatus.READING, updatedAt = 100)
        assertTrue(mergeReadingState(local, remote) is SyncDecision.Push)
    }

    @Test
    fun `finishing a book counts even at the same page`() {
        val local = ReadingState(0.99, ReadingStatus.FINISHED, updatedAt = 300)
        val remote = ReadingState(0.99, ReadingStatus.READING, updatedAt = 100)
        assertEquals(SyncDecision.Push(local), mergeReadingState(local, remote))
    }

    @Test
    fun `an unread book on both sides is left alone`() {
        val local = ReadingState(null, ReadingStatus.READY_TO_READ, updatedAt = 100)
        val remote = ReadingState(null, ReadingStatus.READY_TO_READ, updatedAt = 200)
        assertEquals(SyncDecision.InSync, mergeReadingState(local, remote))
    }

    @Test
    fun `a position beats no position when it is newer`() {
        val local = ReadingState(null, ReadingStatus.READY_TO_READ, updatedAt = 100)
        val remote = ReadingState(0.3, ReadingStatus.READING, updatedAt = 200)
        assertEquals(SyncDecision.Pull(remote), mergeReadingState(local, remote))
    }

    @Test
    fun `status follows how far through the book you are`() {
        assertEquals(ReadingStatus.READY_TO_READ, ReadingStatus.forProgression(null))
        assertEquals(ReadingStatus.READY_TO_READ, ReadingStatus.forProgression(0.0))
        assertEquals(ReadingStatus.READING, ReadingStatus.forProgression(0.5))
        assertEquals(ReadingStatus.FINISHED, ReadingStatus.forProgression(1.0))
    }

    @Test
    fun `wire names round trip`() {
        for (status in ReadingStatus.entries) {
            assertEquals(status, ReadingStatus.fromWire(status.wireName))
        }
        assertEquals(ReadingStatus.READY_TO_READ, ReadingStatus.fromWire(null))
        assertEquals(ReadingStatus.READY_TO_READ, ReadingStatus.fromWire("Nonsense"))
    }
}
