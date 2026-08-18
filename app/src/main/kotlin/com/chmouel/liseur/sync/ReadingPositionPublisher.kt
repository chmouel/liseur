package com.chmouel.liseur.sync

import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.readingStatusFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class PositionUpdate(
    val bookUrl: String,
    val locatorJson: String,
    val progression: Double?,
    val readingSecondsPerPosition: Double?,
    val readingPaceSamples: Int?,
    val readingPaceElapsedMs: Long?,
    val readingPaceEvidence: Double?,
    val updatedAt: Long,
)

/**
 * Persists reader positions in order, outside the reader's lifecycle.
 *
 * A page accepted here survives activity destruction. Network work starts
 * only after the Room write commits, so a sync always sees the revision
 * corresponding to the locator that prompted it.
 */
class ReadingPositionPublisher(
    scope: CoroutineScope,
    private val overrideFor: suspend (String) -> FinishedOverride,
    private val persist: suspend (PositionUpdate, String?) -> Unit,
    private val refreshFinished: suspend (String) -> Unit,
    private val markFinished: suspend (String) -> Unit,
    private val latestSync: LatestPositionSync,
    private val scheduleClose: (String) -> Unit,
    private val onError: (String, Throwable) -> Unit,
) {
    private sealed interface Event {
        data class Position(val update: PositionUpdate) : Event
        data class Complete(val bookUrl: String) : Event
        data class Close(val bookUrl: String) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    init {
        scope.launch {
            for (event in events) {
                when (event) {
                    is Event.Position -> store(event.update)
                    is Event.Complete -> complete(event.bookUrl)
                    is Event.Close -> scheduleClose(event.bookUrl)
                }
            }
        }
    }

    fun publish(update: PositionUpdate): Boolean =
        events.trySend(Event.Position(update)).isSuccess

    /**
     * The reader turned past the last page. Queued behind any position
     * writes already accepted, so the locator that prompted this is on
     * disk before the finished flag is. A second visit is the same
     * event and does not need a second write.
     */
    fun completeBook(bookUrl: String): Boolean =
        events.trySend(Event.Complete(bookUrl)).isSuccess

    fun closeBook(bookUrl: String): Boolean =
        events.trySend(Event.Close(bookUrl)).isSuccess

    private suspend fun store(update: PositionUpdate) {
        val stored = retry {
            val override = overrideFor(update.bookUrl)
            persist(update, readingStatusFor(update.progression, override).wireName)
        }
        if (!stored) {
            _failures.tryEmit(update.bookUrl)
            return
        }

        latestSync.signal(update.bookUrl)

        retry {
            refreshFinished(update.bookUrl)
        }
    }

    private suspend fun complete(bookUrl: String) {
        var wrote = false
        val stored = retry {
            // Already finished is the same visit again: no new revision,
            // and nothing for the other devices to hear.
            if (overrideFor(bookUrl) == FinishedOverride.FINISHED) return@retry
            markFinished(bookUrl)
            wrote = true
        }
        if (!stored) {
            _failures.tryEmit(bookUrl)
            return
        }
        if (wrote) latestSync.signal(bookUrl)
    }

    private suspend fun retry(block: suspend () -> Unit): Boolean {
        repeat(WRITE_ATTEMPTS) { attempt ->
            try {
                block()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError("Could not publish reading position", e)
                if (attempt + 1 < WRITE_ATTEMPTS) delay(WRITE_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private companion object {
        const val WRITE_ATTEMPTS = 2
        const val WRITE_RETRY_DELAY_MS = 100L
    }
}

/**
 * Runs one book sync at a time and remembers only the newest signal per book.
 */
class LatestPositionSync(
    scope: CoroutineScope,
    private val request: suspend (String) -> SyncOutcome,
    private val scheduleRetry: (String) -> Unit,
    private val onError: (String, Throwable) -> Unit,
) {
    private data class Pending(
        var generation: Long = 0,
        var pending: Boolean = false,
        var inFlight: Boolean = false,
    )

    private val lock = Any()
    private val books = linkedMapOf<String, Pending>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in wake) drain()
        }
    }

    fun signal(bookUrl: String) {
        synchronized(lock) {
            val state = books.getOrPut(bookUrl) { Pending() }
            state.generation++
            state.pending = true
        }
        wake.trySend(Unit)
    }

    private suspend fun drain() {
        while (true) {
            val work = synchronized(lock) {
                val entry = books.entries.firstOrNull { it.value.pending && !it.value.inFlight }
                    ?: return
                entry.value.pending = false
                entry.value.inFlight = true
                entry.key to entry.value.generation
            }
            val (bookUrl, generation) = work
            val outcome = try {
                request(bookUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError("Could not synchronize reading position", e)
                SyncOutcome.Failure(com.chmouel.liseur.data.remote.SyncFailure.Offline)
            }

            val retry = outcome is SyncOutcome.Failure && outcome.reason.worthRetrying ||
                outcome is SyncOutcome.Partial && outcome.reason.worthRetrying
            var wakeAgain = false
            synchronized(lock) {
                val state = books[bookUrl] ?: return@synchronized
                state.inFlight = false
                if (retry) {
                    // Everything observed before this boundary is represented
                    // by the dirty row that WorkManager will read.
                    books.remove(bookUrl)
                } else if (state.generation > generation) {
                    state.pending = true
                    wakeAgain = true
                } else {
                    books.remove(bookUrl)
                }
            }
            if (retry) scheduleRetry(bookUrl)
            if (wakeAgain) wake.trySend(Unit)
        }
    }
}
