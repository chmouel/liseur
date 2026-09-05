package com.chmouel.liseur.sync

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.LiveChanges
import com.chmouel.liseur.data.remote.LiveIdentity
import com.chmouel.liseur.data.remote.LiveRefresh
import com.chmouel.liseur.data.remote.LiveStreamFailure
import com.chmouel.liseur.data.remote.LiveTopic
import com.chmouel.liseur.data.remote.PositionSync
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncIdentity
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSyncConnectorTest {
    @Test
    fun `unauthorised refresh reports auth and stops a still healthy event stream`() = runTest {
        val accounts = MutableStateFlow<RemoteServer?>(account())
        val source = Source().apply { failures = mapOf(LiveTopic.POSITIONS to SyncFailure.Unauthorised) }
        val reported = mutableListOf<SyncFailure>()
        val coordinator = PositionSyncCoordinator(NoSync)
        val connector = LiveSyncConnector(
            backgroundScope, accounts, { source }, coordinator, {},
            reportFailure = { _, failure -> reported += failure },
        )
        connector.foreground()
        runCurrent()
        source.events.emit(setOf(LiveTopic.POSITIONS))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(SyncFailure.Unauthorised), reported)
        assertEquals(1, source.closes)
        assertEquals(1, source.refreshes.size)
        assertTrue(coordinator.hasLiveWork(LiveIdentity.from(accounts.value!!)))
        source.failures = emptyMap()
        accounts.value = accounts.value!!.copy(liseurTokenCipher = "replacement")
        runCurrent()
        source.events.emit(setOf(LiveTopic.POSITIONS))
        runCurrent()
        assertEquals(2, source.refreshes.size)
    }

    @Test
    fun `forbidden topic stays paused on more events but other topics still refresh`() = runTest {
        val accounts = MutableStateFlow<RemoteServer?>(account())
        val source = Source().apply { failures = mapOf(LiveTopic.POSITIONS to SyncFailure.Forbidden) }
        val connector = LiveSyncConnector(
            backgroundScope, accounts, { source }, PositionSyncCoordinator(NoSync), {},
        )
        connector.foreground()
        runCurrent()
        source.events.emit(setOf(LiveTopic.POSITIONS))
        runCurrent()
        source.failures = emptyMap()
        source.events.emit(setOf(LiveTopic.POSITIONS, LiveTopic.INSIGHTS))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(setOf(LiveTopic.POSITIONS), setOf(LiveTopic.INSIGHTS)), source.refreshes)
        connector.background()
        advanceTimeBy(15_000)
        runCurrent()
        connector.foreground()
        runCurrent()
        source.events.emit(setOf(LiveTopic.POSITIONS))
        runCurrent()
        assertEquals(setOf(LiveTopic.POSITIONS), source.refreshes.last())
        assertEquals(3, source.refreshes.size)
    }

    @Test
    fun `transient refresh failures back off instead of polling every fifteen seconds`() = runTest {
        val accounts = MutableStateFlow<RemoteServer?>(account())
        val source = Source().apply { failures = mapOf(LiveTopic.POSITIONS to SyncFailure.Offline) }
        val connector = LiveSyncConnector(
            backgroundScope, accounts, { source }, PositionSyncCoordinator(NoSync), {},
            retryJitter = { 0.0 },
        )
        connector.foreground()
        runCurrent()
        source.events.emit(setOf(LiveTopic.POSITIONS))
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(2, source.refreshes.size)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(2, source.refreshes.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, source.refreshes.size)
    }

    @Test
    fun `terminal stream refusal stops failed refresh retries without paying the topic`() = runTest {
        val accounts = MutableStateFlow<RemoteServer?>(account())
        val source = Source().apply { succeeds = false }
        val coordinator = PositionSyncCoordinator(NoSync)
        val connector = LiveSyncConnector(
            backgroundScope, accounts, { source }, coordinator, {},
        )
        connector.foreground()
        runCurrent()
        source.events.emit(setOf(LiveTopic.POSITIONS))
        runCurrent()
        assertEquals(1, source.refreshes.size)
        source.refusal = LiveStreamFailure(401)
        source.events.emit(emptySet())
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, source.refreshes.size)
        assertEquals(1, source.opens)
        assertTrue(coordinator.hasLiveWork(LiveIdentity.from(accounts.value!!)))
    }

    @Test
    fun `unsupported streams stop until a new foreground session`() = runTest {
        val accounts = MutableStateFlow<RemoteServer?>(account())
        val source = Source().apply { refusal = LiveStreamFailure(404) }
        val connector = LiveSyncConnector(
            backgroundScope, accounts, { source }, PositionSyncCoordinator(NoSync), {},
        )
        connector.foreground()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, source.opens)
        connector.background()
        advanceTimeBy(15_000)
        runCurrent()
        connector.foreground()
        runCurrent()
        assertEquals(2, source.opens)
    }

    @Test
    fun `foreground connects despite fresh sync and cursor writes never reconnect`() = runTest {
        val account = account()
        val accounts = MutableStateFlow<RemoteServer?>(account)
        val source = Source()
        val connector = LiveSyncConnector(
            backgroundScope, accounts, { source }, PositionSyncCoordinator(NoSync), {},
        )
        runCurrent()
        assertEquals(0, source.opens)
        connector.foreground()
        runCurrent()
        assertEquals(1, source.opens)
        accounts.value = account.copy(syncCursorSeq = 9, annotationCursorSeq = 10, positionSyncedAt = 123)
        runCurrent()
        assertEquals(1, source.opens)
        connector.background()
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(0, source.closes)
        connector.foreground()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, source.opens)
        connector.background()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(1, source.closes)
        connector.foreground()
        runCurrent()
        assertEquals(2, source.opens)
    }

    @Test
    fun `credential replacement cancels old stream and discards failed topics`() = runTest {
        val accounts = MutableStateFlow<RemoteServer?>(account())
        val source = Source().apply { succeeds = false }
        val coordinator = PositionSyncCoordinator(NoSync)
        val connector = LiveSyncConnector(backgroundScope, accounts, { source }, coordinator, {})
        connector.foreground()
        runCurrent()
        val old = LiveIdentity.from(accounts.value!!)
        source.events.emit(setOf(LiveTopic.POSITIONS))
        runCurrent()
        assertTrue(coordinator.hasLiveWork(old))
        accounts.value = accounts.value!!.copy(liseurTokenCipher = "replacement")
        runCurrent()
        assertEquals(2, source.opens)
        assertEquals(1, source.closes)
        assertFalse(coordinator.hasLiveWork(old))
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(1, source.refreshes.size)
    }

    @Test
    fun `failed refresh retries without another event and book debt runs outside turn`() = runTest {
        val accounts = MutableStateFlow<RemoteServer?>(account())
        val source = Source().apply { succeeds = false }
        val books = mutableListOf<String>()
        val connector = LiveSyncConnector(
            backgroundScope, accounts, { source }, PositionSyncCoordinator(NoSync), books::add,
        )
        connector.foreground()
        runCurrent()
        source.events.emit(setOf(LiveTopic.ANNOTATIONS))
        runCurrent()
        assertEquals(listOf(setOf(LiveTopic.ANNOTATIONS)), source.refreshes)
        source.succeeds = true
        source.owed = setOf("book")
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(2, source.refreshes.size)
        assertEquals(listOf("book"), books)
    }

    private class Source : LiveChanges {
        var opens = 0
        var closes = 0
        var succeeds = true
        var refusal: LiveStreamFailure? = null
        var owed = emptySet<String>()
        var failures = emptyMap<LiveTopic, SyncFailure>()
        val refreshes = mutableListOf<Set<LiveTopic>>()
        val events = MutableSharedFlow<Set<LiveTopic>>()
        override fun events(server: RemoteServer) = flow {
            opens++
            try {
                refusal?.let { throw it }
                emitAll(events.transform {
                    refusal?.let { failure -> throw failure }
                    emit(it)
                })
                awaitCancellation()
            } finally {
                closes++
            }
        }

        override suspend fun refresh(identity: LiveIdentity, topics: Set<LiveTopic>): LiveRefresh {
            refreshes += topics
            return LiveRefresh(if (succeeds) topics - failures.keys else emptySet(), owed, failures)
        }
    }

    private object NoSync : PositionSync {
        override suspend fun syncAll(snapshot: SyncSnapshot?): SyncOutcome = error("No full sync from events")
        override suspend fun syncBook(bookUrl: String): SyncOutcome = error("No inline book sync")
        override suspend fun canSync(bookUrl: String) = false
        override suspend fun previewBook(bookUrl: String) = PreviewOutcome.NotSynced
        override suspend fun preservedConflict(bookUrl: String, peerId: String?) = null
        override suspend fun takeRemotePosition(
            bookUrl: String,
            atRevision: Long,
            peerId: String?,
            expectedAccountKey: String?,
        ) =
            ResolveOutcome.Done
        override suspend fun keepLocalPosition(bookUrl: String, peerId: String?) = ResolveOutcome.Done
        override suspend fun refreshUnresolved() = Unit
        override suspend fun identity(): SyncIdentity? = null
    }

    private fun account() = RemoteServer(
        kind = ServerKind.LISEUR_SYNC, baseUrl = "http://localhost",
        username = "reader", passwordCipher = null, apiKeyCipher = null,
        accountId = "device", userId = null, koboTokenCipher = null,
        canDownload = true, addedAt = 1, catalogSyncedAt = null,
        positionSyncedAt = System.currentTimeMillis(), syncToken = null,
        liseurTokenCipher = "cipher", liseurAccountId = "account",
    )
}
