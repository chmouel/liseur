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

/**
 * The last state both sides agreed on.
 *
 * The whole reason this is stored. Comparing two positions to each other
 * tells you they differ, not who moved — so going back to reread chapter
 * one looks exactly like the other device being at the end. Comparing
 * each side to what they last agreed on tells you which of them actually
 * moved, which is the only sound question to ask. The two clocks
 * involved are not comparable: calibre-web stamps its own time and
 * ignores ours.
 */
data class ReadingBaseline(
    val progression: Double?,
    val status: ReadingStatus,
)

/** What to do once the two sides have been compared. */
sealed interface SyncDecision {
    /** The two sides agree closely enough to leave alone. */
    data object InSync : SyncDecision

    /** The server moved and this device did not: adopt its position. */
    data class Pull(val state: ReadingState) : SyncDecision

    /** This device moved and the server did not: send it. */
    data class Push(val state: ReadingState) : SyncDecision

    /**
     * The server reported a status without a position — someone marking a
     * book read elsewhere without opening it. Worth keeping, but a missing
     * progression is not a progression of zero, so the local position
     * stands.
     */
    data class AdoptStatus(val status: ReadingStatus) : SyncDecision

    /**
     * Both sides moved since they last agreed. Preserve both, choose
     * neither.
     *
     * There is deliberately no automatic winner. Taking the further
     * position would silently throw away a deliberate reread; taking the
     * newer one would trust two clocks that cannot be compared. The
     * remote state stays on disk until someone who knows which device
     * they last held is asked.
     */
    data class Conflict(val local: ReadingState, val remote: ReadingState) : SyncDecision
}

/**
 * Works out what should happen to one book.
 *
 * [localDirty] comes from the revision counters rather than a timestamp,
 * so it survives a clock that jumps backwards and cannot be confused by
 * the server stamping its own time on what it sends back.
 *
 * [localUnreadOverride] is set when the reader deliberately marked the
 * book unread here. That is an act, not an absence, and it outranks a
 * stale `Finished` still sitting on the server.
 */
fun reconcileReadingState(
    local: ReadingState?,
    remote: ReadingState?,
    baseline: ReadingBaseline?,
    localDirty: Boolean,
    localUnreadOverride: Boolean = false,
): SyncDecision {
    if (local == null && remote == null) return SyncDecision.InSync
    if (local == null) return SyncDecision.Pull(checkNotNull(remote))
    if (remote == null) {
        return if (localDirty) SyncDecision.Push(local) else SyncDecision.InSync
    }

    if (sameSpot(local, remote)) return SyncDecision.InSync

    // A deliberate "not read after all" here beats a leftover flag there.
    if (localUnreadOverride && remote.status == ReadingStatus.FINISHED) {
        return SyncDecision.Push(local)
    }

    // Status alone travelled; the position it came with is simply absent.
    if (remote.progression == null) {
        return if (remote.status == local.status) {
            SyncDecision.InSync
        } else {
            SyncDecision.AdoptStatus(remote.status)
        }
    }

    val localMoved = localDirty && movedFrom(baseline, local)
    val remoteMoved = movedFrom(baseline, remote)

    return when {
        !localMoved && remoteMoved -> SyncDecision.Pull(remote)
        localMoved && !remoteMoved -> SyncDecision.Push(local)
        localMoved && remoteMoved -> SyncDecision.Conflict(local, remote)
        else -> SyncDecision.InSync
    }
}

/** Whether a side has genuinely left the place both sides agreed on. */
private fun movedFrom(baseline: ReadingBaseline?, state: ReadingState): Boolean {
    if (baseline == null) return true
    if (baseline.status != state.status) return true
    val agreed = baseline.progression ?: return state.progression != null
    val now = state.progression ?: return true
    return kotlin.math.abs(agreed - now) >= EPSILON
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
 * reasons to speak up are that the server just said something, that it
 * said something earlier which was never settled, or that this device has
 * read on since the two last agreed.
 */
fun needsReconciling(
    reported: Boolean,
    hasPending: Boolean,
    localDirty: Boolean,
): Boolean = reported || hasPending || localDirty
