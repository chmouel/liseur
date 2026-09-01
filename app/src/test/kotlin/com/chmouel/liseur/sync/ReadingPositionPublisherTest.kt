package com.chmouel.liseur.sync

import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.FinishedOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            markFinished = { events += "complete:$it" },
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
    fun `a barrier runs only after every write queued before it`() = runTest {
        val events = mutableListOf<String>()
        val sync = LatestPositionSync(
            scope = backgroundScope,
            request = { SyncOutcome.Success },
            scheduleRetry = {},
            onError = { _, error -> throw error },
        )
        val publisher = ReadingPositionPublisher(
            scope = backgroundScope,
            overrideFor = { FinishedOverride.NONE },
            persist = { update, _ -> events += "write:${update.progression}" },
            refreshFinished = {},
            markFinished = {},
            latestSync = sync,
            scheduleClose = {},
            onError = { _, error -> throw error },
        )

        // The page turned just before the book was closed must be on
        // disk before the barrier acts: the open-book fence is dropped
        // through here, and dropping it ahead of that write would let a
        // background pull slip between the two.
        assertTrue(publisher.publish(update(0.1)))
        assertTrue(publisher.afterQueuedWrites { events += "barrier" })
        assertTrue(publisher.publish(update(0.2)))
        runCurrent()

        assertEquals(listOf("write:0.1", "barrier", "write:0.2"), events)
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
            markFinished = {},
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

    @Test
    fun `completion waits for earlier position writes then syncs`() = runTest {
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
            markFinished = { events += "complete:$it" },
            latestSync = sync,
            scheduleClose = { events += "close:$it" },
            onError = { _, error -> throw error },
        )

        assertTrue(publisher.publish(update(0.99)))
        assertTrue(publisher.completeBook("book"))
        assertTrue(publisher.closeBook("book"))
        runCurrent()

        assertEquals(
            listOf(
                "write:0.99",
                "refresh:book",
                "complete:book",
                "close:book",
                "sync:book",
            ),
            events,
        )
    }

    @Test
    fun `a second completion does not write again`() = runTest {
        var override = FinishedOverride.NONE
        var completions = 0
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
            overrideFor = { override },
            persist = { _, _ -> },
            refreshFinished = {},
            markFinished = {
                completions++
                override = FinishedOverride.FINISHED
            },
            latestSync = sync,
            scheduleClose = {},
            onError = { _, error -> throw error },
        )

        assertTrue(publisher.completeBook("book"))
        assertTrue(publisher.completeBook("book"))
        runCurrent()

        assertEquals(1, completions)
        assertEquals(1, syncs)
    }

    @Test
    fun `an already finished book is not completed again`() = runTest {
        var completions = 0
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
            overrideFor = { FinishedOverride.FINISHED },
            persist = { _, _ -> },
            refreshFinished = {},
            markFinished = { completions++ },
            latestSync = sync,
            scheduleClose = {},
            onError = { _, error -> throw error },
        )

        assertTrue(publisher.completeBook("book"))
        assertTrue(publisher.completeBook("book"))
        runCurrent()

        assertEquals(0, completions)
        assertEquals(0, syncs)
    }

    @Test
    fun `a failing completion is retried and then reported`() = runTest {
        var attempts = 0
        val failures = mutableListOf<String>()
        val sync = LatestPositionSync(
            scope = backgroundScope,
            request = { SyncOutcome.Success },
            scheduleRetry = {},
            onError = { _, _ -> },
        )
        val publisher = ReadingPositionPublisher(
            scope = backgroundScope,
            overrideFor = { FinishedOverride.NONE },
            persist = { _, _ -> },
            refreshFinished = {},
            markFinished = {
                attempts++
                error("flag failed")
            },
            latestSync = sync,
            scheduleClose = {},
            onError = { _, _ -> },
        )
        val job = backgroundScope.launch {
            publisher.failures.collect { failures += it }
        }

        assertTrue(publisher.completeBook("book"))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(2, attempts)
        assertEquals(listOf("book"), failures)
        job.cancel()
    }

    /** A publisher whose writes can be made to fail on demand. */
    private fun TestScope.publisher(
        events: MutableList<String>,
        persistFails: () -> Boolean = { false },
        completionFails: () -> Boolean = { false },
    ): ReadingPositionPublisher {
        val sync = LatestPositionSync(
            scope = backgroundScope,
            request = { SyncOutcome.Success },
            scheduleRetry = {},
            onError = { _, _ -> },
        )
        return ReadingPositionPublisher(
            scope = backgroundScope,
            overrideFor = { FinishedOverride.NONE },
            persist = { update, _ ->
                if (persistFails()) error("write failed")
                events += "write:${update.progression}"
            },
            refreshFinished = {},
            markFinished = {
                if (completionFails()) error("flag failed")
                events += "complete:$it"
            },
            latestSync = sync,
            scheduleClose = {},
            onError = { _, _ -> },
        )
    }

    @Test
    fun `a flush waits for every page accepted before it`() = runTest {
        val events = mutableListOf<String>()
        val publisher = publisher(events)

        publisher.publish(update(0.1))
        publisher.publish(update(0.2))
        val answer = async { publisher.flush("book") }
        advanceUntilIdle()

        // The question the button asks is about a position on disk. A
        // flush that answered before these two landed would let "keep
        // this device's position" send the page before last.
        assertTrue(answer.await())
        assertEquals(listOf("write:0.1", "write:0.2"), events)
    }

    @Test
    fun `a page that never landed is not flushed away`() = runTest {
        val events = mutableListOf<String>()
        var failing = true
        val publisher = publisher(events, persistFails = { failing })

        publisher.publish(update(0.1))
        advanceUntilIdle()

        assertFalse(publisher.flush("book"))

        // A later success of the same kind repairs it: the row now holds
        // a position, and it is a newer one than the failed write.
        failing = false
        publisher.publish(update(0.2))
        advanceUntilIdle()

        assertTrue(publisher.flush("book"))
    }

    @Test
    fun `a completion that never landed is not flushed away either`() = runTest {
        val events = mutableListOf<String>()
        var failing = true
        val publisher = publisher(events, completionFails = { failing })

        publisher.completeBook("book")
        advanceUntilIdle()

        assertFalse(publisher.flush("book"))

        failing = false
        publisher.completeBook("book")
        advanceUntilIdle()

        assertTrue(publisher.flush("book"))
    }

    @Test
    fun `a good completion does not repair a page that never landed`() = runTest {
        val events = mutableListOf<String>()
        val publisher = publisher(events, persistFails = { true })

        publisher.publish(update(0.1))
        publisher.completeBook("book")
        advanceUntilIdle()

        // Two different injuries. The book is marked finished and the
        // locator is still missing, which is exactly the position the
        // button would have offered to send.
        assertFalse(publisher.flush("book"))
    }

    @Test
    fun `a good page does not repair a completion that never landed`() = runTest {
        val events = mutableListOf<String>()
        val publisher = publisher(events, completionFails = { true })

        publisher.completeBook("book")
        publisher.publish(update(0.9))
        advanceUntilIdle()

        assertFalse(publisher.flush("book"))
    }

    @Test
    fun `another book's failure is not this book's problem`() = runTest {
        val events = mutableListOf<String>()
        val publisher = publisher(events, persistFails = { true })

        publisher.publish(update(0.1))
        advanceUntilIdle()

        assertTrue(publisher.flush("other"))
    }

    @Test
    fun `a flush with nobody left to answer it is false`() = runTest {
        val events = mutableListOf<String>()
        val scope = CoroutineScope(coroutineContext + Job())
        val sync = LatestPositionSync(
            scope = backgroundScope,
            request = { SyncOutcome.Success },
            scheduleRetry = {},
            onError = { _, _ -> },
        )
        val publisher = ReadingPositionPublisher(
            scope = scope,
            overrideFor = { FinishedOverride.NONE },
            persist = { update, _ -> events += "write:${update.progression}" },
            refreshFinished = {},
            markFinished = {},
            latestSync = sync,
            scheduleClose = {},
            onError = { _, _ -> },
        )
        publisher.publish(update(0.1))
        advanceUntilIdle()

        scope.cancel()
        advanceUntilIdle()

        // Both the flush still queued when the consumer left and one
        // arriving afterwards. Waiting for ever on a queue nobody reads
        // would hang the button rather than answer it.
        assertFalse(publisher.flush("book"))
        assertFalse(publisher.flush("book"))
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
