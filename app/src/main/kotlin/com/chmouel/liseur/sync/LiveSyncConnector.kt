package com.chmouel.liseur.sync

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.LiveRetry
import com.chmouel.liseur.data.remote.LiveStreamFailure
import com.chmouel.liseur.data.remote.LiveChanges
import com.chmouel.liseur.data.remote.LiveIdentity
import com.chmouel.liseur.data.remote.LiveTopic
import com.chmouel.liseur.data.remote.SyncFailure
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** One foreground connection; a short trip away does not replay the opening hint. */
class LiveSyncConnector(
    private val scope: CoroutineScope,
    accounts: Flow<RemoteServer?>,
    private val sourceFor: (RemoteServer) -> LiveChanges?,
    private val coordinator: PositionSyncCoordinator,
    private val requestBook: (String) -> Unit,
    private val graceMillis: Long = 15_000,
    private val retryJitter: () -> Double = { Random.nextDouble() },
    private val reportFailure: suspend (LiveIdentity, SyncFailure) -> Unit = { _, _ -> },
) {
    private val active = MutableStateFlow(false)
    private val lifecycleGeneration = AtomicLong(0)
    private var stopping: Job? = null

    init {
        scope.launch {
            combine(active, accounts) { foreground, server -> server.takeIf { foreground } }
                .distinctUntilChangedBy { it?.let(LiveIdentity::from) }
                .collectLatest { server ->
                    val identity = server?.let(LiveIdentity::from)
                    coordinator.liveAccount(identity)
                    if (server != null && identity != null) {
                        sourceFor(server)?.let { connected(server, identity, it) }
                    }
                }
        }
    }

    fun foreground() {
        lifecycleGeneration.incrementAndGet()
        stopping?.cancel()
        active.value = true
    }

    fun background() {
        val generation = lifecycleGeneration.incrementAndGet()
        stopping?.cancel()
        stopping = scope.launch {
            delay(graceMillis)
            if (lifecycleGeneration.get() == generation) active.value = false
        }
    }

    private suspend fun connected(server: RemoteServer, identity: LiveIdentity, source: LiveChanges) =
        coroutineScope {
            val wake = Channel<Unit>(Channel.CONFLATED)
            val stopped = CompletableDeferred<Unit>()
            val refreshing = launch {
                val paused = mutableSetOf<LiveTopic>()
                var retry = LiveRetry(retryJitter)
                for (ignored in wake) {
                    while (coordinator.hasLiveWork(identity, excluding = paused)) {
                        val failed = try {
                            val result = coordinator.refreshLive(identity, excluding = paused, refresh = source::refresh)
                            result.failures.values.firstOrNull {
                                it == SyncFailure.Unauthorised || it == SyncFailure.Forbidden ||
                                    it == SyncFailure.InsecureTransport
                            }?.let { reportFailure(identity, it) }
                            if (SyncFailure.Unauthorised in result.failures.values) {
                                stopped.complete(Unit)
                                return@launch
                            }
                            paused += result.failures.filterValues { !it.worthRetryingLive() }.keys
                            // Never request sync under the coordinator's turn.
                            result.owedBooks.forEach(requestBook)
                            result.failures.values.any { it.worthRetryingLive() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The topic remains owed even if the stream has nothing more to say.
                            true
                        }
                        if (!failed) retry = LiveRetry(retryJitter)
                        if (coordinator.hasLiveWork(identity, excluding = paused)) {
                            delay(if (failed) retry.delayMillis(LiveStreamFailure())!! else 15_000)
                        }
                    }
                }
            }
            val streaming = launch {
                val retry = LiveRetry(retryJitter)
                while (true) {
                    val failure = try {
                        source.events(server).collect { topics ->
                            coordinator.invalidate(identity, topics)
                            wake.trySend(Unit)
                        }
                        LiveStreamFailure()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: LiveStreamFailure) {
                        failure
                    } catch (_: IOException) {
                        LiveStreamFailure()
                    }
                    val wait = retry.delayMillis(failure) ?: run {
                        if (failure.code == 401) reportFailure(identity, SyncFailure.Unauthorised)
                        stopped.complete(Unit)
                        return@launch
                    }
                    delay(wait)
                }
            }
            stopped.await()
            streaming.cancelAndJoin()
            refreshing.cancelAndJoin()
        }

    private fun SyncFailure.worthRetryingLive(): Boolean =
        worthRetrying || (this is SyncFailure.ServerError && code == 429)
}
