package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.chmouel.liseur.reader.progress.ReadingPace

/**
 * Last reading position for a book, keyed by the book's URL
 * (a `content://` or `file://` URL until the library phase lands).
 * The position itself is a serialized Readium Locator.
 *
 * Three groups of columns exist purely so this can be reconciled with a
 * calibre-web server without guessing:
 *
 * - **Revisions.** [localRevision] counts genuinely local writes and is
 *   never set from remote data; [ackedRevision] only ever moves to a
 *   revision the server confirmed it received. A row is *dirty* — has
 *   reading the server has not seen — exactly when the first exceeds the
 *   second. Timestamps cannot do this job: wall clocks move backwards,
 *   and the server stamps its own.
 * - **The baseline** ([agreedProgression], [agreedStatus]). The last
 *   state both sides agreed on. Without it, deliberately going back to
 *   reread is indistinguishable from the other device being further on.
 * - **The pending remote state.** The sync feed is incremental and its
 *   token is destructive: once the token moves past a change the server
 *   will never mention it again. So anything it reports is written here
 *   before the token is committed, and survives process death.
 */
@Entity(tableName = "reading_progress")
data class ReadingProgress(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "locator_json") val locatorJson: String,
    @ColumnInfo(name = "total_progression") val totalProgression: Double?,
    /** Legacy v1 pace in positions per minute. Kept only for schema history. */
    @ColumnInfo(name = "reading_speed") val readingSpeed: Double? = null,
    @ColumnInfo(name = "reading_seconds_per_position")
    val readingSecondsPerPosition: Double? = null,
    @ColumnInfo(name = "reading_pace_samples", defaultValue = "0")
    val readingPaceSamples: Int = 0,
    @ColumnInfo(name = "reading_pace_elapsed_ms", defaultValue = "0")
    val readingPaceElapsedMs: Long = 0,
    @ColumnInfo(name = "reading_pace_evidence", defaultValue = "0")
    val readingPaceEvidence: Double = 0.0,
    /** When this device last touched the position. */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Reading status as calibre-web's Kobo sync understands it. */
    @ColumnInfo(name = "status") val status: String? = null,
    /** When this position was last agreed with the server, if ever. */
    @ColumnInfo(name = "synced_at") val syncedAt: Long? = null,
    @ColumnInfo(name = "local_revision", defaultValue = "0")
    val localRevision: Long = 0,
    @ColumnInfo(name = "acked_revision", defaultValue = "0")
    val ackedRevision: Long = 0,
    @ColumnInfo(name = "agreed_progression") val agreedProgression: Double? = null,
    @ColumnInfo(name = "agreed_status") val agreedStatus: String? = null,
    /** Which account the baseline was agreed with. */
    @ColumnInfo(name = "agreed_account") val agreedAccount: String? = null,
    @ColumnInfo(name = "pending_progression") val pendingProgression: Double? = null,
    @ColumnInfo(name = "pending_status") val pendingStatus: String? = null,
    @ColumnInfo(name = "pending_updated_at") val pendingUpdatedAt: Long? = null,
    /** Which account reported the pending state. */
    @ColumnInfo(name = "pending_account") val pendingAccount: String? = null,
    /** Which account this reading was last agreed with. Provenance. */
    @ColumnInfo(name = "owner_account") val ownerAccount: String? = null,
    /** The server's own timestamp, for display. Never compared with ours. */
    @ColumnInfo(name = "remote_updated_at") val remoteUpdatedAt: Long? = null,
    /**
     * Whether someone has said outright that this book is read or unread,
     * as opposed to its position implying it. Stored as the ordinal of
     * [com.chmouel.liseur.domain.FinishedOverride].
     */
    @ColumnInfo(name = "finished_override", defaultValue = "0")
    val finishedOverride: Int = 0,
) {
    /** True when this device has read on since the server last confirmed. */
    val isDirty: Boolean get() = localRevision > ackedRevision

    /** True when a remote state is waiting to be settled. */
    val hasPending: Boolean get() = pendingAccount != null

    /** What someone has said about this book being read, if anything. */
    val override: com.chmouel.liseur.domain.FinishedOverride
        get() = com.chmouel.liseur.domain.FinishedOverride.fromStored(finishedOverride)

    /** The versioned pace learned for this book, excluding legacy v1 data. */
    val readingPace: ReadingPace
        get() = ReadingPace.of(
            secondsPerPosition = readingSecondsPerPosition,
            samples = readingPaceSamples,
            elapsedMs = readingPaceElapsedMs,
            evidence = readingPaceEvidence,
        )
}

/** When a book was last read, on this device or another one. */
data class BookReadAt(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** How far through a book the reader is, if anything is known. */
data class BookProgression(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "total_progression") val totalProgression: Double?,
)

@Dao
abstract class ReadingProgressDao {
    /**
     * Throws away everything remembered about a book: where you were, the
     * baseline both sides agreed on, and anything the server reported.
     * For when the file at a path turns out to hold a different book, so
     * none of it describes anything that still exists.
     */
    @Query("DELETE FROM reading_progress WHERE book_url = :bookUrl")
    abstract suspend fun forget(bookUrl: String)

    @Query("SELECT * FROM reading_progress WHERE book_url = :bookUrl")
    abstract suspend fun get(bookUrl: String): ReadingProgress?

    @Query("SELECT * FROM reading_progress WHERE book_url = :bookUrl")
    abstract fun observe(bookUrl: String): kotlinx.coroutines.flow.Flow<ReadingProgress?>

    @Query("SELECT total_progression FROM reading_progress WHERE book_url = :bookUrl")
    abstract fun observeTotalProgression(
        bookUrl: String,
    ): kotlinx.coroutines.flow.Flow<Double?>

    @Query("SELECT * FROM reading_progress")
    abstract suspend fun getAll(): List<ReadingProgress>

    /**
     * When each book was last read. The position is written both by
     * turning a page here and by taking one from the server, so this is
     * the last time a book was read anywhere, not just on this device.
     */
    @Query("SELECT book_url, updated_at FROM reading_progress")
    abstract fun observeReadAt(): kotlinx.coroutines.flow.Flow<List<BookReadAt>>

    /**
     * How far through each book the reader is, watched.
     *
     * Only the two columns the statistics need. A whole
     * [ReadingProgress] row per book would carry every sync column with
     * it and make the dashboard rebuild whenever a token moved.
     */
    @Query("SELECT book_url, total_progression FROM reading_progress")
    abstract fun observeProgressions(): kotlinx.coroutines.flow.Flow<List<BookProgression>>

    @Upsert
    abstract suspend fun upsert(progress: ReadingProgress)

    /**
     * Records a position read on this device, bumping the revision in the
     * same statement.
     *
     * Doing it as one statement is the point. Reading the row into Kotlin
     * and writing it back lets two page turns interleave, and lets a
     * stale `synced_at` be carried back over a fresh acknowledgement.
     * Everything to do with the server — the baseline, the pending state,
     * the acknowledgement — is deliberately left alone here.
     */
    @Query(
        """
        INSERT INTO reading_progress (
            book_url, locator_json, total_progression, reading_speed,
            reading_seconds_per_position, reading_pace_samples,
            reading_pace_elapsed_ms, reading_pace_evidence,
            updated_at, status, synced_at, local_revision, acked_revision
        )
        VALUES (:bookUrl, :locatorJson, :progression, NULL,
                :readingSecondsPerPosition, COALESCE(:readingPaceSamples, 0),
                COALESCE(:readingPaceElapsedMs, 0), COALESCE(:readingPaceEvidence, 0),
                :updatedAt, :status, NULL, 1, 0)
        ON CONFLICT(book_url) DO UPDATE SET
            locator_json = :locatorJson,
            total_progression = :progression,
            reading_seconds_per_position =
                COALESCE(:readingSecondsPerPosition, reading_seconds_per_position),
            reading_pace_samples = COALESCE(:readingPaceSamples, reading_pace_samples),
            reading_pace_elapsed_ms =
                COALESCE(:readingPaceElapsedMs, reading_pace_elapsed_ms),
            reading_pace_evidence =
                COALESCE(:readingPaceEvidence, reading_pace_evidence),
            updated_at = :updatedAt,
            status = :status,
            local_revision = local_revision + 1
        """,
    )
    abstract suspend fun recordLocal(
        bookUrl: String,
        locatorJson: String,
        progression: Double?,
        readingSecondsPerPosition: Double?,
        readingPaceSamples: Int?,
        readingPaceElapsedMs: Long?,
        readingPaceEvidence: Double?,
        status: String?,
        updatedAt: Long,
    )

    /**
     * Compatibility overload for callers that do not measure v2 pace.
     *
     * [readingSpeed] is deliberately ignored: it belongs to the biased v1
     * estimator and must not seed the replacement.
     */
    suspend fun recordLocal(
        bookUrl: String,
        locatorJson: String,
        progression: Double?,
        @Suppress("UNUSED_PARAMETER") readingSpeed: Double?,
        status: String?,
        updatedAt: Long,
    ) = recordLocal(
        bookUrl = bookUrl,
        locatorJson = locatorJson,
        progression = progression,
        readingSecondsPerPosition = null,
        readingPaceSamples = null,
        readingPaceElapsedMs = null,
        readingPaceEvidence = null,
        status = status,
        updatedAt = updatedAt,
    )

    // -- The pending remote state, and the feed's durability ---------------

    /**
     * Lands a state the server reported. Called before the sync token is
     * committed, so nothing the server said can be lost by the token
     * moving past it.
     */
    @Query(
        """
        INSERT INTO reading_progress (
            book_url, locator_json, total_progression, updated_at,
            local_revision, acked_revision,
            pending_progression, pending_status, pending_updated_at, pending_account
        )
        VALUES (:bookUrl, '{}', NULL, :now, 0, 0,
                :progression, :status, :remoteUpdatedAt, :account)
        ON CONFLICT(book_url) DO UPDATE SET
            pending_progression = :progression,
            pending_status = :status,
            pending_updated_at = :remoteUpdatedAt,
            pending_account = :account
        """,
    )
    abstract suspend fun persistPending(
        bookUrl: String,
        progression: Double?,
        status: String?,
        remoteUpdatedAt: Long?,
        account: String,
        now: Long,
    )

    /** Every row still holding an unsettled state from this account. */
    @Query("SELECT * FROM reading_progress WHERE pending_account = :account")
    abstract suspend fun pendingFor(account: String): List<ReadingProgress>

    @Query(
        """
        UPDATE reading_progress SET
            pending_progression = NULL, pending_status = NULL,
            pending_updated_at = NULL, pending_account = NULL
        WHERE book_url = :bookUrl
        """,
    )
    abstract suspend fun clearPending(bookUrl: String)

    // -- Settling a book --------------------------------------------------

    /**
     * Takes the server's position, but only if this device has not read on
     * in the meantime.
     *
     * A page turn can land between reconciliation reading the row and this
     * write. Applying anyway would both overwrite that page turn and mark
     * it acknowledged, losing it with no trace. So the write is conditional
     * on the revision that was inspected, and the caller treats a refusal
     * as what it now is: a conflict, with the remote state still on disk.
     *
     * [locatorJson] is the exact place the server was left at, for the
     * servers that keep one. A percentage can only reopen a book roughly
     * where it was; a locator reopens it on the right word. Servers that
     * have nothing so precise to offer pass null, and whatever locator
     * this device already had is left alone rather than replaced with a
     * guess.
     *
     * The revision goes up, and the acknowledgement goes up with it, so
     * this partner still counts as in step while every *other* partner
     * sees new local reading to be told about. A pull that left the
     * counter alone would be invisible to them, and a position that
     * arrived from one server would never reach the next.
     *
     * Returns false when the row moved on and nothing was applied.
     */
    @Transaction
    open suspend fun applyPull(
        bookUrl: String,
        expectedRevision: Long,
        progression: Double,
        status: String,
        account: String,
        remoteUpdatedAt: Long?,
        now: Long,
        locatorJson: String? = null,
    ): Boolean {
        val applied = applyPullIfUnchanged(
            bookUrl = bookUrl,
            expectedRevision = expectedRevision,
            progression = progression,
            status = status,
            account = account,
            remoteUpdatedAt = remoteUpdatedAt,
            now = now,
            locatorJson = locatorJson,
        )
        if (applied > 0) clearPending(bookUrl)
        return applied > 0
    }

    @Query(
        """
        UPDATE reading_progress SET
            total_progression = :progression,
            locator_json = COALESCE(:locatorJson, locator_json),
            status = :status,
            updated_at = :now,
            synced_at = :now,
            remote_updated_at = :remoteUpdatedAt,
            agreed_progression = :progression,
            agreed_status = :status,
            agreed_account = :account,
            owner_account = :account,
            local_revision = local_revision + 1,
            acked_revision = local_revision + 1
        WHERE book_url = :bookUrl AND local_revision = :expectedRevision
        """,
    )
    protected abstract suspend fun applyPullIfUnchanged(
        bookUrl: String,
        expectedRevision: Long,
        progression: Double,
        status: String,
        account: String,
        remoteUpdatedAt: Long?,
        now: Long,
        locatorJson: String?,
    ): Int

    /**
     * Records that the server accepted a position.
     *
     * The baseline becomes exactly what was sent, unconditionally: the
     * server holds it now, whatever has happened here since. The
     * acknowledgement is the conditional half — if a page turn landed
     * while the request was in flight, the revision no longer matches,
     * [ReadingProgress.ackedRevision] stays behind and the row is still
     * dirty for the next run. Two different conditions, deliberately, but
     * one transaction: a half-written row here is a lost position.
     */
    @Transaction
    open suspend fun ackPush(
        bookUrl: String,
        sentRevision: Long,
        progression: Double?,
        status: String,
        account: String,
        now: Long,
    ) {
        setBaseline(bookUrl, progression, status, account, now)
        ackRevision(bookUrl, sentRevision)
        clearPending(bookUrl)
    }

    @Query(
        """
        UPDATE reading_progress SET
            agreed_progression = :progression,
            agreed_status = :status,
            agreed_account = :account,
            owner_account = :account,
            synced_at = :now
        WHERE book_url = :bookUrl
        """,
    )
    protected abstract suspend fun setBaseline(
        bookUrl: String,
        progression: Double?,
        status: String,
        account: String,
        now: Long,
    )

    /**
     * Acknowledges exactly the revision that was sent, and no other. The
     * old unconditional `SET synced_at` could mark a page turn agreed on
     * that the server had never been told about.
     */
    @Query(
        """
        UPDATE reading_progress
        SET acked_revision = :sentRevision
        WHERE book_url = :bookUrl AND local_revision = :sentRevision
        """,
    )
    protected abstract suspend fun ackRevision(bookUrl: String, sentRevision: Long)

    /**
     * Records that both sides already agree, without claiming a push or a
     * pull happened. Establishes the baseline the first time a book is
     * seen by a given account.
     */
    @Transaction
    open suspend fun settleAgreed(
        bookUrl: String,
        inspectedRevision: Long,
        progression: Double?,
        status: String,
        account: String,
        now: Long,
    ) {
        setBaseline(bookUrl, progression, status, account, now)
        // Only the revision that was actually compared can be called
        // settled. A page turned since then was never weighed against
        // the server, so the row stays dirty and is sent next time.
        ackRevision(bookUrl, inspectedRevision)
        if (currentRevision(bookUrl) == inspectedRevision) clearPending(bookUrl)
    }

    @Query("SELECT local_revision FROM reading_progress WHERE book_url = :bookUrl")
    abstract suspend fun currentRevision(bookUrl: String): Long?

    /**
     * Takes a status the server reported on its own, leaving the position
     * alone — someone marked the book read elsewhere without opening it.
     *
     * The revisions are untouched on purpose. Nothing was said about the
     * position, so a position this device has not yet sent is still owed
     * to the server after this.
     *
     * A status said outright on this device is never overwritten: the
     * update stops at a row carrying an override, and the pending state is
     * left in place so the disagreement is still there to be settled.
     */
    @Transaction
    open suspend fun adoptStatus(
        bookUrl: String,
        status: String,
        account: String,
        now: Long,
    ): Boolean {
        if ((overrideFor(bookUrl) ?: 0) != 0) return false
        setStatusOnly(bookUrl, status, account, now)
        clearPending(bookUrl)
        return true
    }

    @Query(
        """
        UPDATE reading_progress SET
            status = :status,
            agreed_status = :status,
            agreed_account = :account,
            owner_account = :account,
            remote_updated_at = :now
        WHERE book_url = :bookUrl
        """,
    )
    protected abstract suspend fun setStatusOnly(
        bookUrl: String,
        status: String,
        account: String,
        now: Long,
    )

    // -- Sync partners other than the catalog server ----------------------

    /**
     * Takes a position a peer reported, as a local move.
     *
     * Two things are different from [applyPull], and both follow from
     * there being more than one partner. Nothing about an agreement is
     * written here — that is a fact about a *pair* and lives in
     * `sync_peer_state`. And `local_revision` goes *up* rather than
     * staying put: reading that arrives from one partner is new to every
     * other partner, and a counter that did not move would leave the
     * others believing they had already seen it.
     *
     * The row is created when the book has never been opened here, which
     * is the ordinary case for a book first read on another device.
     *
     * Returns false when a page was turned here while this was being
     * decided; then nothing is applied and the caller has a conflict
     * rather than a handover.
     */
    @Transaction
    open suspend fun applyPeerPull(
        bookUrl: String,
        expectedRevision: Long,
        progression: Double,
        status: String,
        now: Long,
        locatorJson: String? = null,
    ): Boolean {
        // The row is made to exist first, because a book first read on
        // another device has never been opened here and there is nothing
        // to update. A fresh row starts at revision zero, which is what
        // the caller compares against, so the conditional write below
        // still decides whether it applies.
        startIfMissing(bookUrl, now)
        return applyPeerPullIfUnchanged(
            bookUrl = bookUrl,
            expectedRevision = expectedRevision,
            progression = progression,
            status = status,
            now = now,
            locatorJson = locatorJson,
        ) > 0
    }

    @Query(
        """
        INSERT OR IGNORE INTO reading_progress (
            book_url, locator_json, total_progression, updated_at,
            local_revision, acked_revision
        )
        VALUES (:bookUrl, '{}', NULL, :now, 0, 0)
        """,
    )
    protected abstract suspend fun startIfMissing(bookUrl: String, now: Long)

    @Query(
        """
        UPDATE reading_progress SET
            total_progression = :progression,
            locator_json = COALESCE(:locatorJson, locator_json),
            status = :status,
            updated_at = :now,
            synced_at = :now,
            local_revision = local_revision + 1
        WHERE book_url = :bookUrl AND local_revision = :expectedRevision
        """,
    )
    protected abstract suspend fun applyPeerPullIfUnchanged(
        bookUrl: String,
        expectedRevision: Long,
        progression: Double,
        status: String,
        now: Long,
        locatorJson: String?,
    ): Int

    /**
     * Takes a status a peer reported without a position.
     *
     * As with [adoptStatus], a status said outright on this device wins:
     * marking a book unread here is an act, and a stale "finished" on a
     * server is not.
     */
    @Transaction
    open suspend fun adoptPeerStatus(bookUrl: String, status: String, now: Long): Boolean {
        if ((overrideFor(bookUrl) ?: 0) != 0) return false
        return setPeerStatusOnly(bookUrl, status, now) > 0
    }

    @Query(
        """
        UPDATE reading_progress SET status = :status, remote_updated_at = :now
        WHERE book_url = :bookUrl
        """,
    )
    protected abstract suspend fun setPeerStatusOnly(
        bookUrl: String,
        status: String,
        now: Long,
    ): Int

    // -- What someone said, as opposed to what the position implies -------

    /**
     * Records that a book was marked read, or put back on the pile, by
     * hand.
     *
     * This bumps the revision, because it is a genuine local act that the
     * server has not been told about — marking a book read here should
     * show up on the other device. The row is created if the book has
     * never been opened, so a book can be marked read straight from the
     * library.
     */
    @Query(
        """
        INSERT INTO reading_progress (
            book_url, locator_json, total_progression, updated_at,
            status, finished_override, local_revision, acked_revision
        )
        VALUES (:bookUrl, '{}', :progression, :now, :status, :override, 1, 0)
        ON CONFLICT(book_url) DO UPDATE SET
            status = :status,
            finished_override = :override,
            updated_at = :now,
            local_revision = local_revision + 1
        """,
    )
    abstract suspend fun setFinishedOverride(
        bookUrl: String,
        override: Int,
        status: String,
        progression: Double?,
        now: Long,
    )

    @Query("SELECT finished_override FROM reading_progress WHERE book_url = :bookUrl")
    abstract suspend fun overrideFor(bookUrl: String): Int?

    // -- Account identity -------------------------------------------------

    /**
     * Retires everything derived from an account that is no longer signed
     * in. The reading itself stays, and so does [ReadingProgress.ownerAccount]:
     * that provenance is exactly what the user has to be asked about.
     *
     * A baseline belonging to someone else is worse than no baseline, so
     * it is dropped — the next sync with the new account establishes a
     * fresh one instead of diffing against a stranger's reading. Pending
     * states from the old account go the same way: they must never be
     * applied under, or pushed to, the new one.
     *
     * `acked_revision` is levelled up to `local_revision` so that nothing
     * counts as dirty. Leaving rows dirty would quietly upload the
     * previous user's reading into the new account on the very next sync,
     * which is worse than the bug this fixes.
     */
    @Query(
        """
        UPDATE reading_progress SET
            agreed_progression = NULL, agreed_status = NULL, agreed_account = NULL,
            pending_progression = NULL, pending_status = NULL,
            pending_updated_at = NULL, pending_account = NULL,
            synced_at = NULL,
            acked_revision = local_revision
        """,
    )
    abstract suspend fun retireAccountState()

    /**
     * Marks the given books as having reading the server should be told
     * about, once the user has agreed to hand them to a new account.
     */
    @Query(
        """
        UPDATE reading_progress
        SET acked_revision = local_revision - 1, owner_account = :account
        WHERE book_url IN (:bookUrls) AND local_revision > 0
        """,
    )
    abstract suspend fun markDirtyFor(bookUrls: List<String>, account: String)

    /** Books carrying reading that was done under a different account. */
    @Query(
        """
        SELECT * FROM reading_progress
        WHERE owner_account IS NOT NULL AND owner_account != :account
          AND total_progression IS NOT NULL
        """,
    )
    abstract suspend fun ownedByOther(account: String): List<ReadingProgress>
}
