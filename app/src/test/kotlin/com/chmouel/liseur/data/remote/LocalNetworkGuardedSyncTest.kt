package com.chmouel.liseur.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A partner the phone will not let the app reach.
 *
 * The point of guarding each peer rather than the whole run is that a
 * public catalog and a LAN sync server are an ordinary pairing here,
 * and the healthy half has to go on working. The point of guarding at
 * all is that a worker cannot put a permission prompt in front of
 * anybody, so the useful thing it can do is stop spending a timeout
 * every ten minutes.
 */
class LocalNetworkGuardedSyncTest {

    private class FakePeer(
        override val peerId: String,
        var syncable: Boolean = true,
        var outcome: SyncOutcome = SyncOutcome.Success,
        var preview: PreviewOutcome = PreviewOutcome.NotSynced,
        var address: String? = null,
    ) : PeerPositionSync {
        var fullRuns = 0
        val syncedBooks = mutableListOf<String>()
        var previews = 0

        override suspend fun dialledAddress(): String? = address

        override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome {
            fullRuns++
            return outcome
        }

        override suspend fun syncBook(bookUrl: String): SyncOutcome {
            syncedBooks += bookUrl
            return outcome
        }

        override suspend fun canSync(bookUrl: String) = syncable

        override suspend fun previewBook(bookUrl: String): PreviewOutcome {
            previews++
            return preview
        }

        override suspend fun preservedConflict(bookUrl: String, peerId: String?): SyncPreview? = null

        var resolutions = 0

        override suspend fun takeRemotePosition(
            bookUrl: String,
            atRevision: Long,
            peerId: String?,
            expectedAccountKey: String?,
        ): ResolveOutcome {
            resolutions++
            return ResolveOutcome.Done
        }

        override suspend fun keepLocalPosition(bookUrl: String, peerId: String?): ResolveOutcome {
            resolutions++
            return ResolveOutcome.Done
        }

        override suspend fun refreshUnresolved() = Unit

        override suspend fun identity(): SyncIdentity? = null
    }

    private class FakeAccess(private val blocked: Set<String>) : LocalNetworkAccess {
        override val required = true
        override val granted = false
        override suspend fun blocks(url: String?) = url != null && url in blocked
    }

    /**
     * Keeping this device's position writes it to the server, so it is
     * refused rather than left to sit on a timeout the reader is
     * watching. Taking the server's applies an answer an earlier run
     * already wrote down and touches nothing but this phone, so it goes
     * through — the same reason it works on a plane. Neither touches
     * the status line: this is one book being settled, not a run.
     */
    @Test
    fun `sending an answer is refused but accepting one still works`() = runTest {
        val peer = FakePeer(PeerPositionSync.CATALOG)
        val reporting = SyncReporting()
        val guarded = guard(peer, reporting, "http://192.168.1.20:8083")

        val kept = guarded.keepLocalPosition("book", null)
        val taken = guarded.takeRemotePosition("book", 1L, null, null)

        assertEquals(ResolveOutcome.Failed(SyncFailure.LocalNetworkBlocked), kept)
        assertEquals(ResolveOutcome.Done, taken)
        assertEquals(1, peer.resolutions)
        assertEquals(PositionSyncStatus.Idle, reporting.statusOf(PeerPositionSync.CATALOG))
    }

    private fun guard(
        peer: FakePeer,
        reporting: SyncReporting,
        address: String?,
        blocked: Set<String> = setOfNotNull(address),
    ): LocalNetworkGuardedSync {
        peer.address = address
        return LocalNetworkGuardedSync(peer, FakeAccess(blocked), reporting)
    }

    @Test
    fun `a blocked peer is refused rather than dialled`() = runTest {
        val peer = FakePeer(PeerPositionSync.CATALOG)
        val reporting = SyncReporting()
        val guarded = guard(peer, reporting, "http://192.168.1.20:8083")

        val outcome = guarded.syncAll(null)

        assertEquals(SyncOutcome.Failure(SyncFailure.LocalNetworkBlocked), outcome)
        assertEquals(0, peer.fullRuns)
    }

    /**
     * Asking again in ten minutes cannot change the answer, so the
     * worker must not keep asking.
     */
    @Test
    fun `the refusal is not worth retrying`() {
        assertFalse(SyncFailure.LocalNetworkBlocked.worthRetrying)
    }

    @Test
    fun `the failure is filed under the blocked peer's own name`() = runTest {
        val reporting = SyncReporting()
        val guarded = guard(FakePeer(PeerPositionSync.KOSYNC), reporting, "http://192.168.1.9")

        guarded.syncAll(null)

        assertEquals(
            PositionSyncStatus.Failed(SyncFailure.LocalNetworkBlocked),
            reporting.statusOf(PeerPositionSync.KOSYNC),
        )
        assertEquals(PositionSyncStatus.Idle, reporting.statusOf(PeerPositionSync.CATALOG))
    }

    @Test
    fun `a healthy partner beside a blocked one still syncs`() = runTest {
        val reporting = SyncReporting()
        val healthy = FakePeer(PeerPositionSync.KOSYNC)
        val blocked = guard(
            FakePeer(PeerPositionSync.CATALOG),
            reporting,
            "http://192.168.1.20:8083",
        )
        val composite = CompositePositionSync(listOf(blocked, healthy))

        val outcome = composite.syncAll(null)

        assertTrue(outcome is SyncOutcome.Partial)
        assertEquals(1, healthy.fullRuns)
    }

    /** The catalog is first in the list, so the other order is a case too. */
    @Test
    fun `a blocked partner second folds the same way`() = runTest {
        val reporting = SyncReporting()
        val healthy = FakePeer(PeerPositionSync.CATALOG)
        val blocked = guard(FakePeer(PeerPositionSync.KOSYNC), reporting, "http://10.0.0.9")
        val composite = CompositePositionSync(listOf(healthy, blocked))

        val outcome = composite.syncAll(null)

        assertTrue(outcome is SyncOutcome.Partial)
        assertEquals(1, healthy.fullRuns)
    }

    @Test
    fun `a book is refused too, but a preview leaves the status alone`() = runTest {
        val reporting = SyncReporting()
        val peer = FakePeer(PeerPositionSync.CATALOG)
        val guarded = guard(peer, reporting, "http://192.168.1.20:8083")

        assertEquals(
            SyncOutcome.Failure(SyncFailure.LocalNetworkBlocked),
            guarded.syncBook("book://one"),
        )
        assertEquals(
            PreviewOutcome.Failed(SyncFailure.LocalNetworkBlocked),
            guarded.previewBook("book://one"),
        )
        assertEquals(0, peer.previews)
        assertEquals(
            "a preview must not overwrite what the last real run said",
            PositionSyncStatus.Failed(SyncFailure.LocalNetworkBlocked),
            reporting.statusOf(PeerPositionSync.CATALOG),
        )
    }

    /**
     * A stored address that was never going to be dialled must not
     * manufacture a failure for a run that would have skipped it.
     */
    @Test
    fun `a peer with nothing to do passes straight through`() = runTest {
        val reporting = SyncReporting()
        val peer = FakePeer(PeerPositionSync.KOSYNC)
        val guarded = guard(peer, reporting, address = null, blocked = setOf("http://10.0.0.9"))

        assertEquals(SyncOutcome.Success, guarded.syncAll(null))
        assertEquals(1, peer.fullRuns)
        assertEquals(PositionSyncStatus.Idle, reporting.statusOf(PeerPositionSync.KOSYNC))
    }

    /**
     * A book the peer does not carry is not a book the peer declines to
     * dial about: a page turn in a side-loaded book on a catalog
     * account still opens the connection before it finds there is no
     * matching remote book. So the address decides, not the book.
     */
    @Test
    fun `a book this peer does not carry is still refused`() = runTest {
        val reporting = SyncReporting()
        val peer = FakePeer(PeerPositionSync.CATALOG, syncable = false)
        val guarded = guard(peer, reporting, "http://192.168.1.20:8083")

        assertEquals(
            SyncOutcome.Failure(SyncFailure.LocalNetworkBlocked),
            guarded.syncBook("book://one"),
        )
        assertEquals(emptyList<String>(), peer.syncedBooks)
        assertEquals(
            PreviewOutcome.Failed(SyncFailure.LocalNetworkBlocked),
            guarded.previewBook("book://one"),
        )
    }

    @Test
    fun `a public address is dialled as usual`() = runTest {
        val reporting = SyncReporting()
        val peer = FakePeer(PeerPositionSync.CATALOG)
        val guarded = guard(
            peer,
            reporting,
            "https://books.example.com",
            blocked = setOf("http://192.168.1.20:8083"),
        )

        assertEquals(SyncOutcome.Success, guarded.syncAll(null))
        assertEquals(1, peer.fullRuns)
    }
}
