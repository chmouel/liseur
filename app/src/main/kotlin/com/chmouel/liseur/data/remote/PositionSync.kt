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
    /** A short quote around the remote anchor, when one travelled with it. */
    val excerpt: String? = null,
    val confidence: ResumeConfidence = ResumeConfidence.APPROXIMATE,
    /** Exact anchor equality, or null when this partner only supplied a percentage. */
    val exactPositionAgreement: Boolean? = null,
    /**
     * Which partner answered. Filled by [CompositePositionSync] alone: a
     * peer has no reason to name itself, and the reason to know is that a
     * choice made about one partner's position must not be applied to
     * another's.
     */
    val peerId: String? = null,
    /**
     * The key this partner files its agreements under — not a login, since
     * the same username on two servers is two different people's reading.
     * What makes an account switch visible to anything holding this
     * preview.
     */
    val accountKey: String? = null,
    /** The status that would be applied along with the position, on the wire. */
    val remoteStatus: String? = null,
    /**
     * The anchor the server sent, where it sent one. Percentage-only
     * partners leave it null, as does a locator that is not eligible —
     * one recorded against a different edition, say.
     */
    val remoteLocatorJson: String? = null,
    /**
     * The revision of the very row [local] was read from.
     *
     * Reading the position here and the revision from a second query
     * leaves room for a queued page turn to commit in between, which
     * would guard a decision about revision N with revision N+1 and quietly
     * overwrite the newer page. Adopting the remote position uses this.
     */
    val localRevision: Long? = null,
    /**
     * Whether the server's answer was written down, so that choosing it
     * later actually does something.
     *
     * False where the newest thing the server knows is this device's own
     * push. That is not another device's reading and is deliberately not
     * preserved as one, so there is nothing to adopt: the server is
     * simply behind on what this device already owes it, and the only
     * true answer is to send it.
     */
    val resolvable: Boolean = true,
) {
    /** True when there is nothing to choose between. */
    val agrees: Boolean
        get() {
            exactPositionAgreement?.let { return it }
            val here = local ?: return remote == null
            val there = remote ?: return false
            return kotlin.math.abs(here - there) < EPSILON
        }

    /**
     * What the server's side *is*, as far as anything can tell it apart
     * from another answer.
     *
     * A progression alone will not do. Two anchors can share a percentage
     * — that is an ordinary reread of the same page from another device —
     * a status can move without the position moving, and an account can be
     * switched while a question sits on screen. Anything that acts on a
     * preview after letting go of the sync turn compares this against what
     * it finds when it takes the turn back, and refuses if they differ.
     *
     * A percentage-only partner is left with peer, account, progression,
     * status and timestamp, which is everything it knows about its own
     * answer.
     */
    fun fingerprint(): SyncFingerprint = SyncFingerprint(
        peerId = peerId,
        accountKey = accountKey,
        progression = remote,
        status = remoteStatus,
        remoteAt = remoteAt,
        locatorJson = remoteLocatorJson,
    )
}

/** The identity of one server-side answer; see [SyncPreview.fingerprint]. */
data class SyncFingerprint(
    val peerId: String?,
    val accountKey: String?,
    val progression: Double?,
    val status: String?,
    val remoteAt: Long?,
    val locatorJson: String?,
) {
    /**
     * Whether [other] is the same answer.
     *
     * Progressions are compared with the same tolerance the rest of sync
     * uses, since a percentage that survived a round trip through a server
     * may come back a hair different without anybody having read anything.
     * Everything else must match outright.
     */
    fun matches(other: SyncFingerprint): Boolean {
        if (peerId != other.peerId) return false
        if (accountKey != other.accountKey) return false
        if (status != other.status) return false
        if (remoteAt != other.remoteAt) return false
        if (locatorJson != other.locatorJson) return false
        val here = progression ?: return other.progression == null
        val there = other.progression ?: return false
        return kotlin.math.abs(here - there) < EPSILON
    }
}

enum class ResumeConfidence { EXACT, APPROXIMATE }

/** What came of acting on a choice between two positions. */
sealed interface ResolveOutcome {
    data object Done : ResolveOutcome

    /**
     * Something moved while the question was sitting on screen — a page
     * turned here, or a background run landing a different answer from
     * the server — so the choice is about a position that is no longer
     * the one it was made about. Nothing was applied; the question is
     * worth asking again.
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
    /**
     * Reconciles every book that has a position on either side.
     *
     * [snapshot] is a catalog walk that has just been done, offered so a
     * provider whose catalog already carries reading progress need not
     * fetch the same listing twice for one gesture. It is a shortcut and
     * never a requirement: without one, or with one from another
     * account, the run asks the server itself.
     */
    suspend fun syncAll(snapshot: SyncSnapshot? = null): SyncOutcome

    /** Reconciles one book, for the moments someone is waiting on it. */
    suspend fun syncBook(bookUrl: String): SyncOutcome

    /** Whether this book has anywhere to sync to, so the action can stay hidden. */
    suspend fun canSync(bookUrl: String): Boolean

    /** Asks the server where it thinks the reader is, without acting on it. */
    suspend fun previewBook(bookUrl: String): PreviewOutcome

    /**
     * A disagreement an earlier run preserved rather than resolved.
     *
     * [peerId] narrows it to one partner. Without it the answer is
     * whichever partner has something preserved, which is right for the
     * automatic paths and wrong for anything acting on a particular
     * answer it was given earlier.
     */
    suspend fun preservedConflict(bookUrl: String, peerId: String? = null): SyncPreview?

    /**
     * Takes the server's position, because somebody said to.
     *
     * [peerId], where given, is the partner the decision was made about;
     * every other partner is left alone. A single-partner implementation
     * has nothing to narrow and ignores it.
     */
    suspend fun takeRemotePosition(
        bookUrl: String,
        atRevision: Long,
        peerId: String? = null,
        expectedAccountKey: String? = null,
    ): ResolveOutcome

    /** Keeps what is on this device, for the partner named by [peerId]. */
    suspend fun keepLocalPosition(bookUrl: String, peerId: String? = null): ResolveOutcome

    /**
     * Re-counts unsettled disagreements from disk, for when the report is
     * shown by a process that has not run a sync itself.
     */
    suspend fun refreshUnresolved()

    /**
     * Who positions on this device belong to, so a book that will not
     * sync can say why rather than simply doing nothing.
     *
     * Positions are bound to the login that produced them. Signing in as
     * somebody else therefore strands the reading done as the old
     * account — deliberately, since uploading it would put one person's
     * reading in another's — but nothing else says so out loud.
     */
    suspend fun identity(): SyncIdentity?
}
