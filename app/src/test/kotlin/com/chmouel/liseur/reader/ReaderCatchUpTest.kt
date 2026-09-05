package com.chmouel.liseur.reader

import com.chmouel.liseur.data.remote.CompositePositionSync
import com.chmouel.liseur.data.remote.PeerPositionSync
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.ResumeConfidence
import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncSnapshot
import com.chmouel.liseur.sync.PositionSyncCoordinator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCatchUpTest {
    private val original = SyncPreview(
        local = 0.2,
        remote = 0.6,
        remoteAt = 123,
        excerpt = "The exact passage",
        confidence = ResumeConfidence.EXACT,
        accountKey = "account-one",
        remoteStatus = "reading",
        remoteLocatorJson = """{"href":"/chapter.xhtml","text":{"highlight":"The exact passage"}}""",
        localRevision = 7,
    )

    private class Peer(override val peerId: String, val answer: SyncPreview) : PeerPositionSync {
        var pending: SyncPreview? = null
        var revision = answer.localRevision ?: 0
        val adoptions = mutableListOf<Long>()

        override suspend fun previewBook(bookUrl: String): PreviewOutcome {
            pending = answer
            return PreviewOutcome.Ready(answer)
        }

        override suspend fun preservedConflict(bookUrl: String, peerId: String?) = pending

        override suspend fun takeRemotePosition(
            bookUrl: String,
            atRevision: Long,
            peerId: String?,
        ): ResolveOutcome {
            adoptions += atRevision
            return if (revision == atRevision) ResolveOutcome.Done else ResolveOutcome.Superseded
        }

        override suspend fun keepLocalPosition(bookUrl: String, peerId: String?) =
            error("Catch-up must adopt, not keep")

        override suspend fun canSync(bookUrl: String) = true
        override suspend fun syncAll(snapshot: SyncSnapshot?) = SyncOutcome.Success
        override suspend fun syncBook(bookUrl: String) = SyncOutcome.Success
        override suspend fun refreshUnresolved() = Unit
        override suspend fun identity() = null
    }

    private suspend fun offer(coordinator: PositionSyncCoordinator): ReaderViewModel.CatchUp {
        val preview = (coordinator.preview("book") as PreviewOutcome.Ready).preview
        return ReaderViewModel.CatchUp(
            progression = preview.remote!!,
            position = 60,
            excerpt = preview.excerpt,
            remoteAt = preview.remoteAt,
            confidence = preview.confidence,
            preview = preview,
        )
    }

    @Test
    fun `acceptance uses the original revision and only the offered peer`() = runTest {
        val offered = Peer("liseur-sync", original)
        val other = Peer("kosync", original.copy(remote = 0.9)).apply { pending = answer }
        val coordinator = PositionSyncCoordinator(CompositePositionSync(listOf(offered, other)))
        val catchUp = offer(coordinator)

        assertEquals(ResolveOutcome.Done, catchUp.resolve("book", coordinator))
        assertEquals(listOf(7L), offered.adoptions)
        assertTrue(other.adoptions.isEmpty())
        assertEquals(original.copy(peerId = offered.peerId), catchUp.preview)
        assertSame(original.remoteLocatorJson, catchUp.preview.remoteLocatorJson)
    }

    @Test
    fun `a page committed after the offer supersedes it`() = runTest {
        val peer = Peer("liseur-sync", original)
        val coordinator = PositionSyncCoordinator(CompositePositionSync(listOf(peer)))
        val catchUp = offer(coordinator)
        peer.revision++

        assertEquals(ResolveOutcome.Superseded, catchUp.resolve("book", coordinator))
        assertEquals(listOf(7L), peer.adoptions)
    }

    @Test
    fun `changed remote identity is refused before adoption`() = runTest {
        val changes = listOf(
            original.copy(accountKey = "another-account"),
            original.copy(remote = 0.8),
            original.copy(remoteAt = 124),
            original.copy(remoteStatus = "finished"),
            original.copy(remoteLocatorJson = """{"href":"/other.xhtml"}"""),
            null,
        )
        for (changed in changes) {
            val peer = Peer("liseur-sync", original)
            val coordinator = PositionSyncCoordinator(CompositePositionSync(listOf(peer)))
            val catchUp = offer(coordinator)
            peer.pending = changed

            assertEquals(ResolveOutcome.Superseded, catchUp.resolve("book", coordinator))
            assertTrue(peer.adoptions.isEmpty())
        }
    }

    @Test
    fun `an offer without a local row keeps its original zero revision`() = runTest {
        val peer = Peer("liseur-sync", original.copy(local = null, localRevision = null))
        val coordinator = PositionSyncCoordinator(CompositePositionSync(listOf(peer)))
        val catchUp = offer(coordinator)
        peer.revision = 1

        assertEquals(ResolveOutcome.Superseded, catchUp.resolve("book", coordinator))
        assertEquals(listOf(0L), peer.adoptions)
    }
}
