package com.chmouel.liseur.data.remote

import com.chmouel.liseur.domain.EPSILON

/** How the last attempt to sync reading positions went. */
sealed interface PositionSyncStatus {
    data object Idle : PositionSyncStatus
    data object Syncing : PositionSyncStatus
    data class Synced(val at: Long) : PositionSyncStatus

    /** It did not work, and this is why. */
    data class Failed(val reason: SyncFailure) : PositionSyncStatus

    /** The server cannot sync positions, so they stay on this device. */
    data object Unavailable : PositionSyncStatus
}

/**
 * What came of asking to sync.
 *
 * Worth keeping apart, because only some of these are worth trying
 * again: a phone with no account will still have none in ten minutes,
 * and scheduling a backed-off retry for it just burns battery.
 */
sealed interface SyncOutcome {
    /** Positions were exchanged, or both sides already agreed. */
    data object Success : SyncOutcome

    /**
     * Some books settled and some did not. The ones that did not are
     * still marked as having reading the server has not seen, so the next
     * run picks them up. Reported apart from success so a retry is
     * scheduled and the settings screen does not claim all is well. The
     * reason is the first thing that went wrong.
     */
    data class Partial(val reason: SyncFailure) : SyncOutcome

    /** Nothing to do and nothing wrong: no account, no sync, nothing to send. */
    data object NotApplicable : SyncOutcome

    /**
     * It did not work. The reason is kept because it decides what happens
     * next: being offline is worth trying again, an account that is not
     * allowed to sync is not.
     */
    data class Failure(val reason: SyncFailure) : SyncOutcome
}

/** Which way a single book's position went during a run. */
enum class SyncMove { PULLED, PUSHED, UNRESOLVED }

/**
 * What the last exchange actually did, so "synced" can be more than a
 * timestamp. Counts are of books, and [unresolved] is read from disk
 * rather than remembered, so it stays true after a restart.
 */
data class SyncReport(
    val at: Long? = null,
    val pulled: Int = 0,
    val pushed: Int = 0,
    val unresolved: Int = 0,
)

/** Who the reading positions on this device belong to. */
data class SyncIdentity(
    /** The login positions are exchanged as. */
    val login: String,
    /**
     * Books holding reading done while signed in as somebody else. Those
     * positions stay on this device and are never sent anywhere, so
     * saying how many there are is the only way to explain the silence.
     */
    val strandedBooks: Int,
)

/** Where each side thinks the reader is, fetched but not yet acted on. */
data class SyncPreview(
    val local: Double?,
    val remote: Double?,
    /** The server's own timestamp for its position, for display only. */
    val remoteAt: Long?,
) {
    /** True when there is nothing to choose between. */
    val agrees: Boolean
        get() {
            val here = local ?: return remote == null
            val there = remote ?: return false
            return kotlin.math.abs(here - there) < EPSILON
        }
}

/** What came of acting on a choice between two positions. */
sealed interface ResolveOutcome {
    data object Done : ResolveOutcome

    /**
     * A page was turned here while the question was sitting on screen, so
     * the choice is about a position that is no longer the current one.
     * Nothing was applied; the question is worth asking again.
     */
    data object Superseded : ResolveOutcome
    data class Failed(val reason: SyncFailure) : ResolveOutcome
}

/** What came of asking about one book on purpose. */
sealed interface PreviewOutcome {
    data class Ready(val preview: SyncPreview) : PreviewOutcome

    /** This book does not sync: no account, no position sync, or not a server book. */
    data object NotSynced : PreviewOutcome
    data class Failed(val reason: SyncFailure) : PreviewOutcome
}

/**
 * Keeping reading positions in step with a server.
 *
 * Named separately from any one implementation so that
 * `PositionSyncCoordinator`'s ordering rules — the trickiest concurrency
 * in the app — can be tested without a database, a server, or a device,
 * and so a second kind of server is a second implementation rather than
 * a second set of rules.
 */
interface PositionSync {
    /** Reconciles every book that has a position on either side. */
    suspend fun syncAll(): SyncOutcome

    /** Reconciles one book, for the moments someone is waiting on it. */
    suspend fun syncBook(bookUrl: String): SyncOutcome

    /** Whether this book has anywhere to sync to, so the action can stay hidden. */
    suspend fun canSync(bookUrl: String): Boolean

    /** Asks the server where it thinks the reader is, without acting on it. */
    suspend fun previewBook(bookUrl: String): PreviewOutcome

    /** A disagreement an earlier run preserved rather than resolved. */
    suspend fun preservedConflict(bookUrl: String): SyncPreview?

    suspend fun takeRemotePosition(bookUrl: String, atRevision: Long): ResolveOutcome
    suspend fun keepLocalPosition(bookUrl: String): ResolveOutcome
}
