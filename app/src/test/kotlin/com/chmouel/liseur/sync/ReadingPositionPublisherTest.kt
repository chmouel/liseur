package com.chmouel.liseur.sync

import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.FinishedOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPositionPublisherTest {

    @Test
    fun `one committed page starts one sync`() = runTest {
        val requested = mutableListOf<String>()
        val sync = LatestPositionSync(
            scope = backgroundScope,
            request = {
                requested += it
                SyncOutcome.Success
            },
            scheduleRetry = {},
            onError = { _, error -> throw error },
        )

        sync.signal("book")
        runCurrent()

        assertEquals(listOf("book"), requested)
    }

    @Test
    fun `pages during a request collapse into one latest follow-up`() = runTest {
        val first = CompletableDeferred<Unit>()
        var requests = 0
        val sync = LatestPositionSync(
            scope = backgroundScope,
            request = {
                requests++
                if (requests == 1) first.await()
                SyncOutcome.Success
            },
            scheduleRetry = {},
            onError = { _, error -> throw error },
        )

        sync.signal("book")
        runCurrent()
        repeat(10) { sync.signal("book") }
        runCurrent()
        assertEquals(1, requests)

        first.complete(Unit)
        runCurrent()

        assertEquals(2, requests)
    }

    @Test
    fun `retryable failure schedules one fallback and a later page retries foreground`() = runTest {
        val failed = CompletableDeferred<Unit>()
        val outcomes = ArrayDeque(
            listOf(
                SyncOutcome.Failure(SyncFailure.Offline),
                SyncOutcome.Success,
            ),
        )
        var requests = 0
        val retries = mutableListOf<String>()
        val sync = LatestPositionSync(
            scope = backgroundScope,
            request = {
                requests++
                if (requests == 1) failed.await()
                outcomes.removeFirst()
            },
            scheduleRetry = { retries += it },
            onError = { _, error -> throw error },
        )

        sync.signal("book")
        runCurrent()
        repeat(4) { sync.signal("book") }
        failed.complete(Unit)
        runCurrent()

        assertEquals(1, requests)
        assertEquals(listOf("book"), retries)

        sync.signal("book")
        runCurrent()
        assertEquals(2, requests)
    }

    @Test
    fun `a failing book does not strand another book`() = runTest {
        val requested = mutableListOf<String>()
        val sync = LatestPositionSync(
            scope = backgroundScope,
            request = {
                requested += it
                if (it == "one") SyncOutcome.Failure(SyncFailure.Timeout)
                else SyncOutcome.Success
            },
            scheduleRetry = {},
            onError = { _, error -> throw error },
        )

        sync.signal("one")
        sync.signal("two")
        runCurrent()

        assertEquals(listOf("one", "two"), requested)
    }

    @Test
    fun `publisher commits before syncing and closes after earlier writes`() = runTest {
        val events = mutableListOf<String>()
        val sync = LatestPositionSync(
            scope = backgroundScope,
            request = {
                events += "sync:$it"
                SyncOutcome.Success
            },
            scheduleRetry = {},
            onError = { _, error -> throw error },
        )
        val publisher = ReadingPositionPublisher(
            scope = backgroundScope,
            overrideFor = { FinishedOverride.NONE },
            persist = { update, _ -> events += "write:${update.progression}" },
            refreshFinished = { events += "refresh:$it" },
            latestSync = sync,
            scheduleClose = { events += "close:$it" },
            onError = { _, error -> throw error },
        )

        assertTrue(publisher.publish(update(0.1)))
        assertTrue(publisher.publish(update(0.2)))
        assertTrue(publisher.closeBook("book"))
        runCurrent()

        assertEquals(
            listOf(
                "write:0.1",
                "refresh:book",
                "write:0.2",
                "refresh:book",
                "close:book",
                "sync:book",
            ),
            events,
        )
    }

    @Test
    fun `finished refresh failure does not repeat the committed write`() = runTest {
        var writes = 0
        var refreshes = 0
        var syncs = 0
        val sync = LatestPositionSync(
            scope = backgroundScope,
            request = {
                syncs++
                SyncOutcome.Success
            },
            scheduleRetry = {},
            onError = { _, error -> throw error },
        )
        val publisher = ReadingPositionPublisher(
            scope = backgroundScope,
            overrideFor = { FinishedOverride.UNREAD },
            persist = { _, status ->
                writes++
                assertEquals("Reading", status)
            },
            refreshFinished = {
                refreshes++
                error("book flag failed")
            },
            latestSync = sync,
            scheduleClose = {},
            onError = { _, _ -> },
        )

        publisher.publish(update(0.5))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, writes)
        assertEquals(2, refreshes)
        assertEquals(1, syncs)
    }

    private fun update(progression: Double) = PositionUpdate(
        bookUrl = "book",
        locatorJson = """{"href":"chapter.xhtml"}""",
        progression = progression,
        readingSecondsPerPosition = null,
        readingPaceSamples = null,
        readingPaceElapsedMs = null,
        readingPaceEvidence = null,
        updatedAt = 1L,
    )
}
