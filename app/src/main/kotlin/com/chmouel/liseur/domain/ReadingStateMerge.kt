package com.chmouel.liseur.domain

/** How far through a book the reader is, as calibre-web's Kobo sync sees it. */
enum class ReadingStatus {
    READY_TO_READ,
    READING,
    FINISHED,
    ;

    /** The spelling the Kobo protocol uses on the wire. */
    val wireName: String
        get() = when (this) {
            READY_TO_READ -> "ReadyToRead"
            READING -> "Reading"
            FINISHED -> "Finished"
        }

    companion object {
        fun fromWire(value: String?): ReadingStatus = when (value) {
            "Finished" -> FINISHED
            "Reading" -> READING
            else -> READY_TO_READ
        }

        /** What a given progression means, so a finished book marks itself. */
        fun forProgression(progression: Double?): ReadingStatus = when {
            progression == null || progression <= 0.0 -> READY_TO_READ
            progression >= FINISHED_AT -> FINISHED
            else -> READING
        }

        private const val FINISHED_AT = 0.99
    }
}

/** Where a book was left, on this device or on the server. */
data class ReadingState(
    val progression: Double?,
    val status: ReadingStatus,
    /** When this position was recorded, in epoch milliseconds. */
    val updatedAt: Long,
)

/** What to do once the two sides have been compared. */
sealed interface SyncDecision {
    /** The two sides agree closely enough to leave alone. */
    data object InSync : SyncDecision

    /** The server is ahead: adopt its position locally. */
    data class Pull(val state: ReadingState) : SyncDecision

    /** This device is ahead: send its position to the server. */
    data class Push(val state: ReadingState) : SyncDecision
}

/**
 * Decides which of two reading positions wins.
 *
 * Newest wins, because the server does no conflict detection and the
 * person reading is the only one who knows which device they last held.
 * Positions closer than [EPSILON] count as the same page, so opening a
 * book on a second device does not bounce a pointless write back.
 */
fun mergeReadingState(local: ReadingState?, remote: ReadingState?): SyncDecision = when {
    local == null && remote == null -> SyncDecision.InSync
    local == null -> SyncDecision.Pull(checkNotNull(remote))
    remote == null -> SyncDecision.Push(local)
    sameSpot(local, remote) -> SyncDecision.InSync
    remote.updatedAt > local.updatedAt -> SyncDecision.Pull(remote)
    else -> SyncDecision.Push(local)
}

private fun sameSpot(local: ReadingState, remote: ReadingState): Boolean {
    if (local.status != remote.status) return false
    val here = local.progression ?: return remote.progression == null
    val there = remote.progression ?: return false
    return kotlin.math.abs(here - there) < EPSILON
}

/**
 * Roughly a page in a novel: small enough that a real move is noticed,
 * wide enough to absorb calibre-web rounding the position to a whole
 * percentage point.
 */
const val EPSILON = 0.005

/**
 * Whether a sync has any reason to touch this book at all.
 *
 * The Kobo sync feed is incremental: with a token in hand, the server
 * sends only what changed since last time. Silence about a book therefore
 * means "nothing new here", not "no position on the server" — so the only
 * reason to speak up is if this device has read on since the two sides
 * last agreed. Without that distinction every book with a position gets
 * pushed again on every sync, forever.
 */
fun needsReconciling(
    reported: ReadingState?,
    localUpdatedAt: Long?,
    lastSyncedAt: Long?,
): Boolean = reported != null ||
    (localUpdatedAt != null && localUpdatedAt > (lastSyncedAt ?: 0L))
