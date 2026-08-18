package com.chmouel.liseur.data.library

import android.os.SystemClock
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.domain.ReadingSessionClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persists foreground reading time for one book.
 *
 * Lifecycle and locator callbacks can overtake suspendable Room writes.
 * They are therefore captured synchronously and processed by one ordered
 * queue. A pause can never disappear merely because a later resume arrived
 * while an earlier checkpoint was still on its way to disk.
 */
class ReadingSessionRecorder(
    private val dao: ReadingSessionDao,
    private val bookUrl: String,
    scope: CoroutineScope,
    private val awaitRecovery: suspend () -> Unit = { dao.closeInterruptedSessions() },
    private val clock: ReadingSessionClock = ReadingSessionClock(),
    private val wallNow: () -> Long = System::currentTimeMillis,
    private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
    private val checkpointIntervalMs: Long = CHECKPOINT_INTERVAL_MS,
) {

    private data class Moment(val wall: Long, val elapsed: Long)

    private sealed interface Event {
        val at: Moment

        data class Ready(override val at: Moment) : Event
        data class Resumed(override val at: Moment) : Event
        data class Paused(override val at: Moment) : Event
        data class Checkpoint(
            override val at: Moment,
            /** Null for the periodic tick, which turned no page. */
            val progression: Double? = null,
        ) : Event
        data class Close(
            val completed: CompletableDeferred<Unit>,
            override val at: Moment,
        ) : Event
        data class Barrier(
            val reached: CompletableDeferred<Unit>,
            override val at: Moment,
        ) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val accepting = AtomicBoolean(true)


    private var ready = false
    private var foreground = false
    private var sessionId: Long? = null

    /**
     * Where the reader was last seen, for the events that turned no
     * page.
     *
     * A pause, a close and the periodic tick all have to say where the
     * session had got to, and none of them is handed a locator. Only
     * ever touched from the queue's own coroutine, which is also why a
     * page turn carries its progression in the event rather than
     * writing it here: two turns can be submitted before either is
     * processed, and the first checkpoint must record the first page.
     */
    private var latestProgression: Double? = null

    init {
        scope.launch {
            awaitRecovery()
            while (true) {
                val event = nextEvent() ?: break
                if (!process(event)) break
            }
            events.close()
        }
    }

    /** A publication has opened and the reader can actually be shown. */
    fun onReaderReady() {
        enqueue(Event.Ready(moment()))
    }

    /** The reader activity entered the foreground. */
    fun onResumed() {
        enqueue(Event.Resumed(moment()))
    }

    /** The reader activity left the foreground. */
    fun onPaused() {
        enqueue(Event.Paused(moment()))
    }

    /**
     * Persists elapsed time at the moment a page turn is observed, and
     * with it where in the book that page was.
     *
     * Captured here rather than read back from the database when the
     * checkpoint is written: by then the reader may have turned again,
     * and a stretch would be credited to the wrong part of the book.
     */
    fun onPageTurned(progression: Double? = null) {
        enqueue(Event.Checkpoint(moment(), progression))
    }

    /** Settles elapsed time without pretending that the locator changed. */
    fun checkpoint() {
        enqueue(Event.Checkpoint(moment()))
    }

    /**
     * Finishes anything still open and retires this recorder.
     *
     * The returned signal is useful to tests. Production callers may ignore
     * it: the app-owned consumer outlives the ViewModel that asks it to close.
     */
    fun close(): Deferred<Unit> {
        val completed = CompletableDeferred<Unit>()
        if (!accepting.compareAndSet(true, false)) {
            completed.complete(Unit)
            return completed
        }
        if (events.trySend(Event.Close(completed, moment())).isFailure) {
            completed.complete(Unit)
        }
        return completed
    }

    /** Waits until everything already submitted has reached Room. Tests use this. */
    suspend fun awaitIdle() {
        if (!accepting.get()) return
        val reached = CompletableDeferred<Unit>()
        if (events.trySend(Event.Barrier(reached, moment())).isFailure) return
        reached.await()
    }

    private fun moment() = Moment(wall = wallNow(), elapsed = elapsedNow())

    private fun enqueue(event: Event) {
        if (accepting.get()) events.trySend(event)
    }

    /** Adds a time checkpoint even when a reader stays on one long page. */
    private suspend fun nextEvent(): Event? {
        if (sessionId == null) return events.receiveCatching().getOrNull()
        return withTimeoutOrNull(checkpointIntervalMs) {
            events.receiveCatching().getOrNull()
        } ?: Event.Checkpoint(moment())
    }

    /** Returns false after the final close event. */
    private suspend fun process(event: Event): Boolean {
        return when (event) {
            is Event.Ready -> {
                ready = true
                startIfNeeded(event.at)
                true
            }

            is Event.Resumed -> {
                foreground = true
                startIfNeeded(event.at)
                true
            }

            is Event.Paused -> {
                foreground = false
                finish(event.at)
                true
            }

            is Event.Checkpoint -> {
                checkpoint(event.at, event.progression)
                true
            }

            is Event.Barrier -> {
                event.reached.complete(Unit)
                true
            }

            is Event.Close -> {
                finish(event.at)
                event.completed.complete(Unit)
                false
            }
        }
    }

    /** Starts only when both Android and publication opening agree it is readable. */
    private suspend fun startIfNeeded(at: Moment) {
        if (!ready || !foreground || sessionId != null) return
        clock.resume(at.elapsed)
        sessionId = dao.insert(
            ReadingSession(
                bookUrl = bookUrl,
                startedAt = at.wall,
                lastCheckpointAt = at.wall,
            ),
        )
    }

    private suspend fun checkpoint(at: Moment, progression: Double?) {
        if (progression != null) latestProgression = progression
        val id = sessionId ?: return
        dao.checkpoint(
            id = id,
            totalMs = clock.checkpoint(at.elapsed),
            atMillis = at.wall,
            progression = latestProgression,
        )
    }

    private suspend fun finish(at: Moment) {
        val id = sessionId ?: return
        dao.finish(
            id = id,
            totalMs = clock.pause(at.elapsed),
            atMillis = at.wall,
            progression = latestProgression,
        )
        sessionId = null
    }

    companion object {
        const val CHECKPOINT_INTERVAL_MS = 60_000L
    }
}
