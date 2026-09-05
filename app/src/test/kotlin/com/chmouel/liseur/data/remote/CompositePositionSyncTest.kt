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
        var conflictsRead = 0

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
        override suspend fun preservedConflict(bookUrl: String, peerId: String?): SyncPreview? {
            conflictsRead++
            return conflict
        }

        override suspend fun takeRemotePosition(
            bookUrl: String,
            atRevision: Long,
            peerId: String?,
            expectedAccountKey: String?,
        ): ResolveOutcome {
            resolved++
            return resolveOutcome
        }

        override suspend fun keepLocalPosition(bookUrl: String, peerId: String?): ResolveOutcome {
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

        assertEquals(PreviewOutcome.Ready(answer.copy(peerId = "fine")), outcome)
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
    fun `the peer that answered a preview is named on it`() = runTest {
        val peer = FakePeer(
            "kosync",
            preview = PreviewOutcome.Ready(SyncPreview(local = 0.1, remote = 0.4, remoteAt = 10)),
        )

        val outcome = composite(peer).previewBook("file:///b") as PreviewOutcome.Ready

        // Without this the choice made about kosync's page has no way
        // back to kosync, and would be handed to every partner at once.
        assertEquals("kosync", outcome.preview.peerId)
    }

    @Test
    fun `a peer with no position does not silence one that has`() = runTest {
        val blank = FakePeer("blank", preview = PreviewOutcome.Ready(SyncPreview(0.1, null, null)))
        val knows = FakePeer("knows", preview = PreviewOutcome.Ready(SyncPreview(0.1, 0.4, 10)))

        val outcome = composite(blank, knows).previewBook("file:///b") as PreviewOutcome.Ready

        assertEquals("knows", outcome.preview.peerId)
        assertEquals(0.4, outcome.preview.remote!!, 0.0001)
    }

    @Test
    fun `nobody having a position is still an answer`() = runTest {
        val blank = FakePeer("blank", preview = PreviewOutcome.Ready(SyncPreview(0.1, null, null)))
        val quiet = FakePeer("quiet", preview = PreviewOutcome.NotSynced)

        val outcome = composite(blank, quiet).previewBook("file:///b") as PreviewOutcome.Ready

        // "The server has no position for this book yet" is a thing to
        // say. Turning it into NotSynced would claim the book does not
        // sync at all.
        assertNull(outcome.preview.remote)
        assertEquals("blank", outcome.preview.peerId)
    }

    @Test
    fun `a named choice reaches that peer and no other`() = runTest {
        val catalog = FakePeer("catalog", conflict = SyncPreview(0.1, 0.4, 10))
        val kosync = FakePeer("kosync", conflict = SyncPreview(0.2, 0.9, 20))

        // The dialog was about kosync's position, and kosync's alone —
        // even though the catalog server is holding a disagreement of
        // its own that nobody was shown.
        val outcome = composite(catalog, kosync).takeRemotePosition("b", 3, peerId = "kosync")

        assertEquals(ResolveOutcome.Done, outcome)
        assertEquals(0, catalog.resolved)
        assertEquals(1, kosync.resolved)
    }

    @Test
    fun `a named preserved conflict is read from that peer alone`() = runTest {
        val catalog = FakePeer("catalog", conflict = SyncPreview(0.1, 0.4, 10))
        val kosync = FakePeer("kosync", conflict = SyncPreview(0.2, 0.9, 20))

        val held = composite(catalog, kosync).preservedConflict("b", peerId = "kosync")

        assertEquals(0.9, held!!.remote!!, 0.0001)
        assertEquals("kosync", held.peerId)
        assertEquals(0, catalog.conflictsRead)
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
