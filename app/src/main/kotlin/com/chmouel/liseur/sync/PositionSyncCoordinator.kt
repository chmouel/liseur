package com.chmouel.liseur.sync

import com.chmouel.liseur.data.remote.PositionSync
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.data.remote.SyncSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How much of the library a request wants reconciled. */
sealed interface SyncScope {
    /** Everything. */
    data object Full : SyncScope

    /** One book, for when someone is waiting on that book in particular. */
    data class Book(val bookUrl: String) : SyncScope
}

/**
 * Runs one reading-position sync at a time, and lets callers wait for
 * the answer.
 *
 * The old arrangement took a lock and, when it was already held, told
 * the caller there was nothing to do. That was a lie with consequences:
 * pulling to refresh while a background sync happened to be running
 * reported success without having synced anything the reader could see.
 *
 * So no request is ever turned away. A request may **join** work already
 * running, but only if that work started *after* the request was made.
 * A run that began earlier cannot have seen what prompted the request,
 * and telling the caller it is done would be the same lie in a nicer
 * shape. Such a request waits its turn and gets its own run.
 *
 * `Full` never settles for a `Book` run, since it is asking about more.
 * Two identical requests waiting at the same time share one run, because
 * otherwise they would do the same work twice in a row.
 */
class PositionSyncCoordinator(private val sync: PositionSync) {

    /** Re-counts unsettled disagreements without starting a run. */
    suspend fun refreshUnresolved() = sync.refreshUnresolved()

    /** Who positions on this device belong to, or null if nobody. */
    suspend fun identity() = sync.identity()

    /** Held for the duration of a run, so only one happens at a time. */
    private val turn = Mutex()

    /** Guards the bookkeeping below, and is never held across a sync. */
    private val state = Mutex()

    private var inFlight: Running? = null
    private val queued = mutableMapOf<SyncScope, CompletableDeferred<SyncOutcome>>()

    private class Running(
        val scope: SyncScope,
        val startedAt: Long,
        val result: CompletableDeferred<SyncOutcome>,
    )

    /**
     * Reconciles [scope] and waits for the outcome.
     *
     * [requestedAt] is when the thing that prompted this happened — a
     * pull-to-refresh gesture, a book being opened. It is what decides
     * whether a run already under way is allowed to answer.
     *
     * [snapshot] is a catalog walk to reuse rather than repeat. It rides
     * along with this request only: a request that joins a run already
     * going leaves its snapshot behind, since that run is doing its own
     * fetching and is already known to have started late enough to
     * answer honestly.
     */
    suspend fun request(
        scope: SyncScope,
        requestedAt: Long = System.currentTimeMillis(),
        snapshot: SyncSnapshot? = null,
    ): SyncOutcome {
        val joinable = state.withLock {
            inFlight?.takeIf { canSatisfy(it, scope, requestedAt) }?.result
        }
        if (joinable != null) return joinable.await()

        val (slot, isLeader) = state.withLock {
            queued[scope]?.let { return@withLock it to false }
            val fresh = CompletableDeferred<SyncOutcome>()
            queued[scope] = fresh
            fresh to true
        }
        if (isLeader) {
            // Deliberately not run inside the caller. Opening a book bounds
            // how long it will wait here, and a bound is only a bound if
            // giving up actually returns: the run makes blocking network
            // calls that ignore cancellation, so a caller running the work
            // itself stays inside them for the full connect timeout no
            // matter what deadline it set. Detaching leaves the caller with
            // nothing to do but await, which it can stop doing at once.
            //
            // The dispatcher is the caller's, so nothing about where the
            // work happens changes; only the job does, which is what keeps
            // the run alive after the caller has walked away.
            CoroutineScope(currentCoroutineContext() + Job()).launch {
                lead(scope, snapshot, slot)
            }
        }
        return slot.await()
    }

    /** Takes the turn, runs [scope], and hands the answer to everyone waiting. */
    private suspend fun lead(
        scope: SyncScope,
        snapshot: SyncSnapshot?,
        slot: CompletableDeferred<SyncOutcome>,
    ) {
        try {
            turn.withLock {
                state.withLock {
                    queued.remove(scope)
                    inFlight = Running(scope, System.currentTimeMillis(), slot)
                }
                val outcome = try {
                    when (scope) {
                        SyncScope.Full -> sync.syncAll(snapshot)
                        is SyncScope.Book -> sync.syncBook(scope.bookUrl)
                    }
                } catch (e: Throwable) {
                    clearInFlight(slot)
                    slot.completeExceptionally(e)
                    return@withLock
                }
                clearInFlight(slot)
                slot.complete(outcome)
            }
        } catch (e: CancellationException) {
            // Only reachable if the run itself is stopped, which now takes
            // the whole app going away. Do not leave the deferred in queued
            // for a run that will never happen.
            withContext(NonCancellable) {
                state.withLock {
                    if (queued[scope] === slot) queued.remove(scope)
                }
            }
            slot.cancel(e)
            throw e
        }
    }

    /** Whether this book has anywhere to sync to. */
    suspend fun canSync(bookUrl: String): Boolean = sync.canSync(bookUrl)

    /**
     * Asks about one book on purpose, without deciding anything.
     *
     * Takes the same turn an ordinary sync does, so it cannot read a
     * position out from under a run that is halfway through writing one.
     */
    suspend fun preview(bookUrl: String): PreviewOutcome =
        turn.withLock { sync.previewBook(bookUrl) }

    /**
     * Acts on what someone chose after being shown both positions.
     *
     * [atRevision] is the position the choice was made about. Taking the
     * server's position is refused if a page has been turned since, since
     * that page turn is newer than the decision being acted on.
     */
    suspend fun resolve(
        bookUrl: String,
        takeRemote: Boolean,
        atRevision: Long,
    ): ResolveOutcome = turn.withLock {
        if (takeRemote) {
            sync.takeRemotePosition(bookUrl, atRevision)
        } else {
            sync.keepLocalPosition(bookUrl)
        }
    }

    /**
     * The disagreement an ordinary sync preserved rather than resolved,
     * if there is one. Reads what is already on disk and asks the server
     * nothing.
     *
     * Ordinarily it waits for a run in flight, so it cannot read a
     * position out from under one halfway through writing it.
     *
     * [abandonedRun] is for the one caller that has already stopped
     * waiting for that run: opening a book bounds its own sync and
     * carries on without it. Waiting on the turn afterwards would undo
     * that bound entirely — blocking network calls are not interrupted
     * by giving up on them, so a run against a server whose packets go
     * nowhere keeps the turn for as long as the sockets take to expire,
     * and the book stays on its loading screen for all of it. Told the
     * run was abandoned, this takes the turn if it is free and otherwise
     * answers "nothing to settle" at once.
     *
     * That answer is safe: the disagreement stays preserved on disk and
     * is put to the reader on the next open, which is what preserving it
     * was for. It is deliberately not a timeout — a run that is merely
     * slow but working is still worth waiting for, and only its own
     * caller knows whether it gave up on one.
     */
    suspend fun preservedConflict(
        bookUrl: String,
        abandonedRun: Boolean = false,
    ): SyncPreview? {
        if (!abandonedRun) return turn.withLock { sync.preservedConflict(bookUrl) }
        if (!turn.tryLock()) return null
        return try {
            sync.preservedConflict(bookUrl)
        } finally {
            turn.unlock()
        }
    }

    private suspend fun clearInFlight(result: CompletableDeferred<SyncOutcome>) {
        withContext(NonCancellable) {
            state.withLock {
                if (inFlight?.result === result) inFlight = null
            }
        }
    }

    /**
     * Whether a run already under way can answer this request.
     *
     * Only if it began after the request did. A run that started earlier
     * may have read the library before the thing being asked about ever
     * happened.
     */
    private fun canSatisfy(
        current: Running,
        scope: SyncScope,
        requestedAt: Long,
    ): Boolean {
        if (current.startedAt < requestedAt) return false
        return when (scope) {
            SyncScope.Full -> current.scope is SyncScope.Full
            is SyncScope.Book -> when (val running = current.scope) {
                SyncScope.Full -> true
                is SyncScope.Book -> running.bookUrl == scope.bookUrl
            }
        }
    }

}
