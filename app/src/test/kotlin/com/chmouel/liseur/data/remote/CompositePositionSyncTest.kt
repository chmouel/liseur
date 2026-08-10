package com.chmouel.liseur.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What several sync partners at once add up to.
 *
 * The folding rules are the whole point of this class and none of them
 * are visible from outside: a run that half worked has to keep saying
 * so, or the worker stops retrying and the books that did not settle
 * stay unsettled for good.
 */
class CompositePositionSyncTest {

    private class FakePeer(
        override val peerId: String,
        var outcome: SyncOutcome = SyncOutcome.Success,
        var preview: PreviewOutcome = PreviewOutcome.NotSynced,
        var conflict: SyncPreview? = null,
        var syncable: Boolean = true,
        var who: SyncIdentity? = null,
        var resolveOutcome: ResolveOutcome = ResolveOutcome.Done,
    ) : PeerPositionSync {
        val syncedBooks = mutableListOf<String>()
        var fullRuns = 0
        var refreshes = 0
        var resolved = 0

        override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome {
            fullRuns++
            return outcome
        }

        override suspend fun syncBook(bookUrl: String): SyncOutcome {
            syncedBooks += bookUrl
            return outcome
        }

        override suspend fun canSync(bookUrl: String) = syncable
        override suspend fun previewBook(bookUrl: String) = preview
        override suspend fun preservedConflict(bookUrl: String) = conflict

        override suspend fun takeRemotePosition(bookUrl: String, atRevision: Long): ResolveOutcome {
            resolved++
            return resolveOutcome
        }

        override suspend fun keepLocalPosition(bookUrl: String): ResolveOutcome {
            resolved++
            return resolveOutcome
        }

        override suspend fun refreshUnresolved() {
            refreshes++
        }

        override suspend fun identity() = who
    }

    private fun composite(vararg peers: FakePeer) = CompositePositionSync(peers.toList())

    @Test
    fun `every peer is asked`() = runTest {
        val one = FakePeer("one")
        val two = FakePeer("two")

        assertEquals(SyncOutcome.Success, composite(one, two).syncAll())

        assertEquals(1, one.fullRuns)
        assertEquals(1, two.fullRuns)
    }

    @Test
    fun `one peer failing while another succeeds is partial, not success`() = runTest {
        val good = FakePeer("good")
        val bad = FakePeer("bad", outcome = SyncOutcome.Failure(SyncFailure.Offline))

        val outcome = composite(good, bad).syncAll()

        // Partial is retried; success is not. Calling this a success
        // would leave the offline peer's books unsettled for good.
        assertEquals(SyncOutcome.Partial(SyncFailure.Offline), outcome)
    }

    @Test
    fun `everybody failing is a failure with the first reason`() = runTest {
        val one = FakePeer("one", outcome = SyncOutcome.Failure(SyncFailure.Unauthorised))
        val two = FakePeer("two", outcome = SyncOutcome.Failure(SyncFailure.Offline))

        assertEquals(
            SyncOutcome.Failure(SyncFailure.Unauthorised),
            composite(one, two).syncBook("file:///b"),
        )
    }

    @Test
    fun `a peer with nothing to do does not stop another from succeeding`() = runTest {
        val idle = FakePeer("idle", outcome = SyncOutcome.NotApplicable)
        val busy = FakePeer("busy", outcome = SyncOutcome.Success)

        assertEquals(SyncOutcome.Success, composite(idle, busy).syncAll())
    }

    @Test
    fun `nobody having anything to do is not applicable`() = runTest {
        val one = FakePeer("one", outcome = SyncOutcome.NotApplicable)
        val two = FakePeer("two", outcome = SyncOutcome.NotApplicable)

        assertEquals(SyncOutcome.NotApplicable, composite(one, two).syncAll())
    }

    @Test
    fun `no peers at all is not applicable rather than success`() = runTest {
        assertEquals(SyncOutcome.NotApplicable, CompositePositionSync(emptyList()).syncAll())
    }

    @Test
    fun `a partial peer keeps the run partial`() = runTest {
        val one = FakePeer("one", outcome = SyncOutcome.Partial(SyncFailure.Timeout))

        assertEquals(SyncOutcome.Partial(SyncFailure.Timeout), composite(one).syncAll())
    }

    @Test
    fun `a book syncs if any peer can sync it`() = runTest {
        val cannot = FakePeer("cannot", syncable = false)
        val can = FakePeer("can", syncable = true)

        assertTrue(composite(cannot, can).canSync("file:///b"))
        assertFalse(composite(cannot).canSync("file:///b"))
    }

    @Test
    fun `one unreachable peer does not hide another's answer`() = runTest {
        val broken = FakePeer("broken", preview = PreviewOutcome.Failed(SyncFailure.Offline))
        val answer = SyncPreview(local = 0.1, remote = 0.4, remoteAt = 10)
        val fine = FakePeer("fine", preview = PreviewOutcome.Ready(answer))

        val outcome = composite(broken, fine).previewBook("file:///b")

        assertEquals(PreviewOutcome.Ready(answer), outcome)
    }

    @Test
    fun `a failure is reported when nobody could answer`() = runTest {
        val broken = FakePeer("broken", preview = PreviewOutcome.Failed(SyncFailure.Offline))
        val silent = FakePeer("silent", preview = PreviewOutcome.NotSynced)

        assertEquals(
            PreviewOutcome.Failed(SyncFailure.Offline),
            composite(broken, silent).previewBook("file:///b"),
        )
    }

    @Test
    fun `a choice only reaches the peer whose disagreement it was about`() = runTest {
        val quiet = FakePeer("quiet", conflict = null)
        val asking = FakePeer("asking", conflict = SyncPreview(0.1, 0.4, 10))

        assertEquals(ResolveOutcome.Done, composite(quiet, asking).takeRemotePosition("b", 3))

        assertEquals(0, quiet.resolved)
        assertEquals(1, asking.resolved)
    }

    @Test
    fun `a page turned during the question supersedes the choice`() = runTest {
        val settled = FakePeer("settled", conflict = SyncPreview(0.1, 0.4, 10))
        val stale = FakePeer("stale", conflict = SyncPreview(0.1, 0.4, 10)).apply {
            resolveOutcome = ResolveOutcome.Superseded
        }

        assertEquals(
            ResolveOutcome.Superseded,
            composite(settled, stale).keepLocalPosition("file:///b"),
        )
    }

    @Test
    fun `nobody is asked to act on a disagreement that does not exist`() = runTest {
        val quiet = FakePeer("quiet", conflict = null)

        assertEquals(ResolveOutcome.Done, composite(quiet).keepLocalPosition("file:///b"))
        assertEquals(0, quiet.resolved)
    }

    @Test
    fun `every peer re-counts what it left unsettled`() = runTest {
        val one = FakePeer("one")
        val two = FakePeer("two")

        composite(one, two).refreshUnresolved()

        assertEquals(1, one.refreshes)
        assertEquals(1, two.refreshes)
    }

    @Test
    fun `identity comes from the first peer that knows one`() = runTest {
        val anonymous = FakePeer("anonymous", who = null)
        val known = FakePeer("known", who = SyncIdentity(login = "alice", strandedBooks = 2))

        assertEquals(
            SyncIdentity("alice", 2),
            composite(anonymous, known).identity(),
        )
        assertNull(composite(anonymous).identity())
    }
}
