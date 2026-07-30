package com.chmouel.liseur.sync

import com.chmouel.liseur.data.remote.PositionSync
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering rules that decide whether a request may be answered by
 * work already in progress.
 *
 * These matter because getting them wrong is invisible: the wrong answer
 * still reads as "synced", just without having synced anything the
 * reader asked about. That is the exact bug pull-to-refresh once had.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionSyncCoordinatorTest {

    /**
     * A sync that does nothing until it is told to, so a test can hold a
     * run open and make a second request arrive in the middle of it.
     */
    private class FakeSync : PositionSync {
        val started = mutableListOf<SyncScope>()
        var gate: CompletableDeferred<Unit>? = null
        var outcome: SyncOutcome = SyncOutcome.Success
        var thrown: Throwable? = null
        var syncable = true
        var preview: PreviewOutcome = PreviewOutcome.NotSynced
        var conflict: SyncPreview? = null
        val resolved = mutableListOf<Pair<String, Boolean>>()

        override suspend fun syncAll(): SyncOutcome = run(SyncScope.Full)

        override suspend fun syncBook(bookUrl: String): SyncOutcome =
            run(SyncScope.Book(bookUrl))

        private suspend fun run(scope: SyncScope): SyncOutcome {
            started += scope
            gate?.await()
            thrown?.let { throw it }
            return outcome
        }

        override suspend fun canSync(bookUrl: String) = syncable

        override suspend fun previewBook(bookUrl: String) = preview

        override suspend fun preservedConflict(bookUrl: String) = conflict

        override suspend fun takeRemotePosition(
            bookUrl: String,
            atRevision: Long,
        ): ResolveOutcome {
            resolved += bookUrl to true
            return ResolveOutcome.Done
        }

        override suspend fun keepLocalPosition(bookUrl: String): ResolveOutcome {
            resolved += bookUrl to false
            return ResolveOutcome.Done
        }
    }

    @Test
    fun `a request made after the running sync started gets its own run`() = runTest {
        // The bug this whole design exists for: a background sync that
        // began before the reader pulled to refresh cannot possibly have
        // seen what they just did, so answering with it would be a lie.
        val sync = FakeSync()
        val gate = CompletableDeferred<Unit>()
        sync.gate = gate
        val coordinator = PositionSyncCoordinator(sync)

        val first = async { coordinator.request(SyncScope.Full, requestedAt = 0) }
        advanceUntilIdle()
        val second = async { coordinator.request(SyncScope.Full, requestedAt = Long.MAX_VALUE) }
        advanceUntilIdle()

        assertEquals(1, sync.started.size)
        sync.gate = null
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, sync.started.size)
    }

    @Test
    fun `a request the running sync has already seen joins it`() = runTest {
        val sync = FakeSync()
        val gate = CompletableDeferred<Unit>()
        sync.gate = gate
        val coordinator = PositionSyncCoordinator(sync)

        val first = async { coordinator.request(SyncScope.Full, requestedAt = 0) }
        advanceUntilIdle()
        val second = async { coordinator.request(SyncScope.Full, requestedAt = 0) }
        advanceUntilIdle()

        gate.complete(Unit)
        assertEquals(SyncOutcome.Success, first.await())
        assertEquals(SyncOutcome.Success, second.await())
        assertEquals(1, sync.started.size)
    }

    @Test
    fun `two identical requests waiting together share one run`() = runTest {
        val sync = FakeSync()
        val gate = CompletableDeferred<Unit>()
        sync.gate = gate
        val coordinator = PositionSyncCoordinator(sync)

        // One run holds the turn; the two behind it are the same request
        // and must not do the same work twice in a row.
        val running = async { coordinator.request(SyncScope.Full, requestedAt = 0) }
        advanceUntilIdle()
        val a = async { coordinator.request(SyncScope.Book("b"), requestedAt = Long.MAX_VALUE) }
        val b = async { coordinator.request(SyncScope.Book("b"), requestedAt = Long.MAX_VALUE) }
        advanceUntilIdle()

        sync.gate = null
        gate.complete(Unit)
        running.await()
        a.await()
        b.await()

        assertEquals(listOf(SyncScope.Full, SyncScope.Book("b")), sync.started)
    }

    @Test
    fun `a full request will not settle for a book run`() = runTest {
        val sync = FakeSync()
        val gate = CompletableDeferred<Unit>()
        sync.gate = gate
        val coordinator = PositionSyncCoordinator(sync)

        val book = async { coordinator.request(SyncScope.Book("b"), requestedAt = 0) }
        advanceUntilIdle()
        val full = async { coordinator.request(SyncScope.Full, requestedAt = 0) }
        advanceUntilIdle()

        assertEquals(1, sync.started.size)
        sync.gate = null
        gate.complete(Unit)
        book.await()
        full.await()
        assertEquals(listOf(SyncScope.Book("b"), SyncScope.Full), sync.started)
    }

    @Test
    fun `a book request settles for a full run that has seen it`() = runTest {
        val sync = FakeSync()
        val gate = CompletableDeferred<Unit>()
        sync.gate = gate
        val coordinator = PositionSyncCoordinator(sync)

        val full = async { coordinator.request(SyncScope.Full, requestedAt = 0) }
        advanceUntilIdle()
        val book = async { coordinator.request(SyncScope.Book("b"), requestedAt = 0) }
        advanceUntilIdle()

        gate.complete(Unit)
        full.await()
        book.await()
        assertEquals(listOf<SyncScope>(SyncScope.Full), sync.started)
    }

    @Test
    fun `a different book does not settle for another book's run`() = runTest {
        val sync = FakeSync()
        val gate = CompletableDeferred<Unit>()
        sync.gate = gate
        val coordinator = PositionSyncCoordinator(sync)

        val one = async { coordinator.request(SyncScope.Book("one"), requestedAt = 0) }
        advanceUntilIdle()
        val two = async { coordinator.request(SyncScope.Book("two"), requestedAt = 0) }
        advanceUntilIdle()

        sync.gate = null
        gate.complete(Unit)
        one.await()
        two.await()
        assertEquals(listOf(SyncScope.Book("one"), SyncScope.Book("two")), sync.started)
    }

    @Test
    fun `a failed run does not leave the coordinator stuck`() = runTest {
        // A run that throws must clear itself away, or every later
        // request waits on a turn that will never be given up.
        val sync = FakeSync()
        sync.thrown = IllegalStateException("boom")
        val coordinator = PositionSyncCoordinator(sync)

        val failed = runCatching { coordinator.request(SyncScope.Full, requestedAt = 0) }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)

        sync.thrown = null
        assertEquals(SyncOutcome.Success, coordinator.request(SyncScope.Full, requestedAt = 0))
    }

    @Test
    fun `a joined request sees the failure too`() = runTest {
        val sync = FakeSync()
        val gate = CompletableDeferred<Unit>()
        sync.gate = gate
        sync.thrown = IllegalStateException("boom")
        val coordinator = PositionSyncCoordinator(sync)

        val first = async { runCatching { coordinator.request(SyncScope.Full, requestedAt = 0) } }
        advanceUntilIdle()
        val joined = async { runCatching { coordinator.request(SyncScope.Full, requestedAt = 0) } }
        advanceUntilIdle()

        gate.complete(Unit)
        assertTrue(first.await().isFailure)
        assertTrue(joined.await().isFailure)
        assertEquals(1, sync.started.size)
    }

    @Test
    fun `the outcome of a run is handed to everyone joining it`() = runTest {
        val sync = FakeSync()
        val gate = CompletableDeferred<Unit>()
        sync.gate = gate
        val partial = SyncOutcome.Partial(SyncFailure.Offline)
        sync.outcome = partial
        val coordinator = PositionSyncCoordinator(sync)

        val first = async { coordinator.request(SyncScope.Full, requestedAt = 0) }
        advanceUntilIdle()
        val joined = async { coordinator.request(SyncScope.Full, requestedAt = 0) }
        advanceUntilIdle()

        gate.complete(Unit)
        assertSame(partial, first.await())
        assertSame(partial, joined.await())
    }

    @Test
    fun `reading a preserved conflict waits for a run in progress`() = runTest {
        // Reading a position out from under a run that is halfway
        // through writing one would show the reader a torn answer.
        val sync = FakeSync()
        val gate = CompletableDeferred<Unit>()
        sync.gate = gate
        val coordinator = PositionSyncCoordinator(sync)

        val running = async { coordinator.request(SyncScope.Full, requestedAt = 0) }
        advanceUntilIdle()
        val asked = async { coordinator.preservedConflict("b") }
        advanceUntilIdle()
        assertTrue(asked.isActive)

        gate.complete(Unit)
        running.await()
        assertNull(asked.await())
    }

    @Test
    fun `previewing waits for a run in progress`() = runTest {
        val sync = FakeSync()
        val gate = CompletableDeferred<Unit>()
        sync.gate = gate
        val coordinator = PositionSyncCoordinator(sync)

        val running = async { coordinator.request(SyncScope.Full, requestedAt = 0) }
        advanceUntilIdle()
        val asked = async { coordinator.preview("b") }
        advanceUntilIdle()
        assertTrue(asked.isActive)

        gate.complete(Unit)
        running.await()
        assertEquals(PreviewOutcome.NotSynced, asked.await())
    }

    @Test
    fun `resolving sends the choice that was made`() = runTest {
        val sync = FakeSync()
        val coordinator = PositionSyncCoordinator(sync)

        coordinator.resolve("b", takeRemote = true, atRevision = 3)
        coordinator.resolve("b", takeRemote = false, atRevision = 3)

        assertEquals(listOf("b" to true, "b" to false), sync.resolved)
    }

    @Test
    fun `asking whether a book can sync does not take the turn`() = runTest {
        // Opening a book asks this while a sync may well be running, and
        // waiting on the network to answer it would stall the reader.
        val sync = FakeSync()
        val gate = CompletableDeferred<Unit>()
        sync.gate = gate
        val coordinator = PositionSyncCoordinator(sync)

        val running = async { coordinator.request(SyncScope.Full, requestedAt = 0) }
        advanceUntilIdle()

        assertTrue(coordinator.canSync("b"))

        gate.complete(Unit)
        running.await()
    }
}
