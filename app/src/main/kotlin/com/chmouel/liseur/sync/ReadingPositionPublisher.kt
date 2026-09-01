package com.chmouel.liseur.sync

import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.readingStatusFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
        data class Barrier(val action: suspend () -> Unit) : Event
        data class Flush(
            val bookUrl: String,
            val answer: CompletableDeferred<Boolean>,
        ) : Event
    }

    /** The two kinds of write that can fail, tracked apart; see [flush]. */
    private enum class Injury { POSITION, COMPLETION }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    /**
     * What did not reach the disk, per book, read and written only by the
     * consumer below — which is what makes [flush] an answer rather than a
     * guess about a race with the failures flow.
     */
    private val outstanding = mutableMapOf<String, MutableSet<Injury>>()

    init {
        scope.launch {
            try {
                for (event in events) {
                    when (event) {
                        is Event.Position -> store(event.update)
                        is Event.Complete -> complete(event.bookUrl)
                        is Event.Close -> scheduleClose(event.bookUrl)
                        is Event.Barrier -> event.action()
                        is Event.Flush -> event.answer.complete(
                            outstanding[event.bookUrl].isNullOrEmpty(),
                        )
                    }
                }
            } finally {
                // Nothing will consume this queue again, so shut the door
                // before draining it: a flush that arrives after the drain
                // would otherwise be accepted by an unlimited channel and
                // waited on for ever.
                events.close()
                while (true) {
                    val left = events.tryReceive().getOrNull() ?: break
                    (left as? Event.Flush)?.answer?.complete(false)
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

    /**
     * Runs [action] once every write accepted before this call has
     * committed.
     *
     * The queue is the order pages were turned in, so anything that must
     * not run ahead of a page still in flight — dropping the open-book
     * fence, for one — takes its place in the same line rather than
     * racing it from another scope.
     */
    fun afterQueuedWrites(action: suspend () -> Unit): Boolean =
        events.trySend(Event.Barrier(action)).isSuccess

    /**
     * Waits for every position event accepted before this call, and says
     * whether they are all on disk.
     *
     * Answered from inside the consumer, so it cannot race the queue the
     * way the [failures] flow can: that flow is emitted and collected
     * independently, and a barrier could finish before a collector had
     * heard about a write that failed. `publish` returning true only ever
     * meant the channel took the event.
     *
     * A page turn that did not persist and a book completion that did not
     * are counted apart. They are different injuries, and a later
     * completion does not repair a locator that never landed any more
     * than a later page turn repairs a completion that did not.
     *
     * False also means "nobody is listening": the consumer closes the
     * queue on its way out and answers what is still in it, and a send
     * refused afterwards answers itself.
     */
    suspend fun flush(bookUrl: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        if (events.trySend(Event.Flush(bookUrl, answer)).isFailure) return false
        return answer.await()
    }

    private suspend fun store(update: PositionUpdate) {
        val stored = retry {
            val override = overrideFor(update.bookUrl)
            persist(update, readingStatusFor(update.progression, override).wireName)
        }
        if (!stored) {
            hurt(update.bookUrl, Injury.POSITION)
            _failures.tryEmit(update.bookUrl)
            return
        }
        healed(update.bookUrl, Injury.POSITION)

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
            hurt(bookUrl, Injury.COMPLETION)
            _failures.tryEmit(bookUrl)
            return
        }
        healed(bookUrl, Injury.COMPLETION)
        if (wrote) latestSync.signal(bookUrl)
    }

    private fun hurt(bookUrl: String, injury: Injury) {
        outstanding.getOrPut(bookUrl) { mutableSetOf() }.add(injury)
    }

    private fun healed(bookUrl: String, injury: Injury) {
        val left = outstanding[bookUrl] ?: return
        left.remove(injury)
        if (left.isEmpty()) outstanding.remove(bookUrl)
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
