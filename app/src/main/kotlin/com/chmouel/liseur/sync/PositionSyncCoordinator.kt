package com.chmouel.liseur.sync

import com.chmouel.liseur.data.remote.PositionSync
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncPreview
import kotlinx.coroutines.CompletableDeferred
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
     */
    suspend fun request(
        scope: SyncScope,
        requestedAt: Long = System.currentTimeMillis(),
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
        if (!isLeader) return slot.await()

        return turn.withLock {
            state.withLock {
                queued.remove(scope)
                inFlight = Running(scope, System.currentTimeMillis(), slot)
            }
            val outcome = try {
                when (scope) {
                    SyncScope.Full -> sync.syncAll()
                    is SyncScope.Book -> sync.syncBook(scope.bookUrl)
                }
            } catch (e: Throwable) {
                state.withLock { inFlight = null }
                slot.completeExceptionally(e)
                throw e
            }
            state.withLock { inFlight = null }
            slot.complete(outcome)
            outcome
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
     * nothing, so it is safe to call while opening a book.
     */
    suspend fun preservedConflict(bookUrl: String): SyncPreview? =
        turn.withLock { sync.preservedConflict(bookUrl) }

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
