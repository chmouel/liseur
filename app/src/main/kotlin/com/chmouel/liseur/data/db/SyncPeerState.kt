package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.chmouel.liseur.domain.ReadingStatusChoice

/**
 * How one book's position stands with one sync partner.
 *
 * `reading_progress` holds a single set of these columns, which was
 * exactly right while there was a single server. It is not right once a
 * book can be shared with both a catalog server and a dedicated sync
 * server: the baseline both sides last agreed on is a fact about a
 * *pair*, and keeping one copy of it would have each partner overwrite
 * the other's, which is how a deliberate reread turns back into "the
 * other device is further on".
 *
 * What stays on `reading_progress` is what describes this device alone:
 * the locator, `local_revision`, and whether someone marked the book
 * read by hand. What lives here is everything that only means anything
 * in relation to a partner.
 *
 * [peerId] identifies the **account**, not the kind of server — the same
 * host signed into as two people is two partners. Reading agreed with
 * one of them is never treated as agreed with the other.
 */
@Entity(
    tableName = "sync_peer_state",
    primaryKeys = ["book_url", "peer_id"],
    indices = [Index("peer_id")],
)
data class SyncPeerState(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "peer_id") val peerId: String,
    /** The highest local revision this partner has confirmed receiving. */
    @ColumnInfo(name = "acked_revision", defaultValue = "0") val ackedRevision: Long = 0,
    /**
     * The highest status revision this partner has confirmed. Kept apart
     * from [ackedRevision] so a stale position update can never stand
     * for a newer explicit choice, or the reverse.
     */
    @ColumnInfo(name = "acked_status_revision", defaultValue = "0")
    val ackedStatusRevision: Long = 0,
    @ColumnInfo(name = "agreed_progression") val agreedProgression: Double? = null,
    @ColumnInfo(name = "agreed_status") val agreedStatus: String? = null,
    /** Reported by the partner and not yet settled. */
    @ColumnInfo(name = "pending_progression") val pendingProgression: Double? = null,
    /** Locator and edition from the same op as [pendingProgression]. */
    @ColumnInfo(name = "pending_locator_json") val pendingLocatorJson: String? = null,
    @ColumnInfo(name = "pending_edition_sha") val pendingEditionSha: String? = null,
    @ColumnInfo(name = "pending_status") val pendingStatus: String? = null,
    /** Coverage that came with [pendingProgression]. */
    @ColumnInfo(name = "pending_coverage") val pendingCoverage: String? = null,
    @ColumnInfo(name = "pending_updated_at") val pendingUpdatedAt: Long? = null,
    @ColumnInfo(name = "pending_seq") val pendingSeq: Long? = null,
    /** True once a partner has reported something waiting to be settled. */
    @ColumnInfo(name = "has_pending", defaultValue = "0") val hasPending: Boolean = false,
    /**
     * The status act a partner reported, waiting to be settled. Kept in
     * its own slot with its own sequence guard: one kind of op must never
     * stand in for the other, or a page and a choice arriving together
     * would lose whichever came first.
     */
    @ColumnInfo(name = "has_pending_status", defaultValue = "0")
    val hasPendingStatus: Boolean = false,
    /** The choice that act states, as `ReadingStatusChoice` spells it. */
    @ColumnInfo(name = "pending_status_choice") val pendingStatusChoice: String? = null,
    @ColumnInfo(name = "pending_status_seq") val pendingStatusSeq: Long? = null,
    @ColumnInfo(name = "pending_status_updated_at") val pendingStatusUpdatedAt: Long? = null,
    /**
     * The exact bytes of a status act this device sent and was never
     * answered about. Replayed until it is acknowledged, so a page turned
     * in the meantime cannot rebuild it into a different payload.
     */
    @ColumnInfo(name = "status_sent_op") val statusSentOp: String? = null,
    @ColumnInfo(name = "status_sent_revision") val statusSentRevision: Long? = null,
    /** The partner's own timestamp, for display. Never compared with ours. */
    @ColumnInfo(name = "remote_updated_at") val remoteUpdatedAt: Long? = null,
    @ColumnInfo(name = "synced_at") val syncedAt: Long? = null,
    @ColumnInfo(name = "pending_hlc") val pendingHlc: String? = null,
    @ColumnInfo(name = "drive_imported_reset_revision") val driveImportedResetRevision: Long? = null,
) {
    /** True when this device has read on since this partner last confirmed. */
    fun isDirty(localRevision: Long): Boolean = localRevision > ackedRevision

    /** True when a status choice has not reached this partner yet. */
    fun owesStatus(statusRevision: Long): Boolean = statusRevision > ackedStatusRevision

    /** The choice a waiting status act states, as a choice rather than a status. */
    fun pendingChoice(): ReadingStatusChoice? =
        if (hasPendingStatus) ReadingStatusChoice.fromWire(pendingStatusChoice) else null

    /** Whether a partner's pending status act still needs this device's answer. */
    val hasPendingStatusAct: Boolean get() = hasPendingStatus && pendingChoice() != null
}

@Dao
abstract class SyncPeerStateDao {

    @Query("SELECT * FROM sync_peer_state WHERE book_url = :bookUrl AND peer_id = :peerId")
    abstract suspend fun get(bookUrl: String, peerId: String): SyncPeerState?

    @Query("SELECT EXISTS(SELECT 1 FROM sync_peer_state WHERE book_url = :bookUrl AND has_pending_status = 1)")
    abstract suspend fun hasPendingStatusForBook(bookUrl: String): Boolean

    @Query("SELECT * FROM sync_peer_state WHERE peer_id = :peerId")
    abstract suspend fun forPeer(peerId: String): List<SyncPeerState>

    @Query(
        """
        SELECT * FROM sync_peer_state WHERE book_url = :bookUrl AND has_pending = 1
          AND pending_progression IS NOT NULL
          AND (peer_id = :account OR substr(peer_id, 1, length(:account) + 1) = :account || '/')
        ORDER BY peer_id
        """,
    )
    abstract suspend fun pendingForDriveBook(bookUrl: String, account: String): List<SyncPeerState>

    /** Disagreements this partner reported and nobody has settled. */
    @Query("SELECT * FROM sync_peer_state WHERE peer_id = :peerId AND has_pending = 1")
    abstract suspend fun pendingFor(peerId: String): List<SyncPeerState>

    @Query("SELECT COUNT(*) FROM sync_peer_state WHERE peer_id = :peerId AND has_pending = 1")
    abstract suspend fun countPending(peerId: String): Int

    /** How much a partner has agreed about one book, settled or not. */
    @Query("SELECT COUNT(*) FROM sync_peer_state WHERE book_url = :bookUrl")
    abstract suspend fun countForBook(bookUrl: String): Int

    @Upsert
    abstract suspend fun upsert(state: SyncPeerState)

    /**
     * Lands something a partner reported, before its cursor is allowed
     * to move past it.
     *
     * Never read-modified-written for the same reason the local position
     * is not: whatever else is in the row is this partner's agreement,
     * and carrying a stale copy of it back over a fresh one would undo an
     * acknowledgement. The update and the insert behind it share one
     * transaction, so together they are still indivisible.
     */
    @Query(
        """
        UPDATE sync_peer_state SET
            pending_progression = :progression,
            pending_locator_json = :locatorJson,
            pending_edition_sha = :editionSha,
            pending_status = :status,
            pending_coverage = :coverage,
            pending_updated_at = :remoteUpdatedAt,
            pending_seq = :seq,
            pending_hlc = :hlc,
            has_pending = 1,
            remote_updated_at = :remoteUpdatedAt
        WHERE book_url = :bookUrl AND peer_id = :peerId
        """,
    )
    abstract suspend fun updatePending(
        bookUrl: String,
        peerId: String,
        progression: Double?,
        status: String?,
        remoteUpdatedAt: Long?,
        locatorJson: String? = null,
        editionSha: String? = null,
        seq: Long? = null,
        coverage: String? = null,
        hlc: String? = null,
    ): Int

    @Query(
        """
        INSERT OR IGNORE INTO sync_peer_state (
            book_url, peer_id, acked_revision,
            pending_progression, pending_locator_json, pending_edition_sha,
            pending_status, pending_coverage, pending_updated_at, pending_seq, pending_hlc,
            has_pending, remote_updated_at
        )
        VALUES (:bookUrl, :peerId, 0, :progression, :locatorJson, :editionSha,
                :status, :coverage, :remoteUpdatedAt, :seq, :hlc, 1,
                :remoteUpdatedAt)
        """,
    )
    abstract suspend fun insertPending(
        bookUrl: String,
        peerId: String,
        progression: Double?,
        status: String?,
        remoteUpdatedAt: Long?,
        locatorJson: String? = null,
        editionSha: String? = null,
        seq: Long? = null,
        coverage: String? = null,
        hlc: String? = null,
    )

    @Transaction
    open suspend fun persistPending(
        bookUrl: String,
        peerId: String,
        progression: Double?,
        status: String?,
        remoteUpdatedAt: Long?,
        locatorJson: String? = null,
        editionSha: String? = null,
        seq: Long? = null,
        coverage: String? = null,
        hlc: String? = null,
    ) {
        val updated = updatePending(
            bookUrl = bookUrl,
            peerId = peerId,
            progression = progression,
            status = status,
            remoteUpdatedAt = remoteUpdatedAt,
            locatorJson = locatorJson,
            editionSha = editionSha,
            seq = seq,
            coverage = coverage,
            hlc = hlc,
        )
        if (updated == 0) {
            insertPending(
                bookUrl = bookUrl,
                peerId = peerId,
                progression = progression,
                status = status,
                remoteUpdatedAt = remoteUpdatedAt,
                locatorJson = locatorJson,
                editionSha = editionSha,
                seq = seq,
                coverage = coverage,
                hlc = hlc,
            )
        }
    }

    @Query(
        """
        UPDATE sync_peer_state SET
            pending_progression = NULL, pending_locator_json = NULL,
            pending_edition_sha = NULL, pending_status = NULL,
            pending_coverage = NULL,
            pending_updated_at = NULL, pending_seq = NULL, pending_hlc = NULL, has_pending = 0
        WHERE book_url = :bookUrl AND peer_id = :peerId
        """,
    )
    abstract suspend fun clearPending(bookUrl: String, peerId: String)

    /**
     * Lands a status act a partner reported, in the status slot alone.
     *
     * The position pending state is a different debt and is left exactly
     * as it was: one op of either kind must not overwrite the other.
     */
    @Query(
        """
        UPDATE sync_peer_state SET
            pending_status_choice = :choice,
            pending_status_seq = :seq,
            pending_status_updated_at = :remoteUpdatedAt,
            has_pending_status = 1,
            remote_updated_at = :remoteUpdatedAt
        WHERE book_url = :bookUrl AND peer_id = :peerId
        """,
    )
    abstract suspend fun updatePendingStatus(
        bookUrl: String,
        peerId: String,
        choice: String,
        remoteUpdatedAt: Long?,
        seq: Long? = null,
    ): Int

    @Query(
        """
        INSERT OR IGNORE INTO sync_peer_state (
            book_url, peer_id, acked_revision,
            has_pending_status, pending_status_choice, pending_status_seq,
            pending_status_updated_at, remote_updated_at
        )
        VALUES (:bookUrl, :peerId, 0, 1, :choice, :seq, :remoteUpdatedAt, :remoteUpdatedAt)
        """,
    )
    abstract suspend fun insertPendingStatus(
        bookUrl: String,
        peerId: String,
        choice: String,
        remoteUpdatedAt: Long?,
        seq: Long? = null,
    )

    @Transaction
    open suspend fun persistPendingStatus(
        bookUrl: String,
        peerId: String,
        choice: String,
        remoteUpdatedAt: Long?,
        seq: Long? = null,
    ) {
        val updated = updatePendingStatus(bookUrl, peerId, choice, remoteUpdatedAt, seq)
        if (updated == 0) insertPendingStatus(bookUrl, peerId, choice, remoteUpdatedAt, seq)
    }

    @Query(
        """
        UPDATE sync_peer_state SET
            pending_status_choice = NULL, pending_status_seq = NULL,
            pending_status_updated_at = NULL, has_pending_status = 0
        WHERE book_url = :bookUrl AND peer_id = :peerId
        """,
    )
    abstract suspend fun clearPendingStatus(bookUrl: String, peerId: String)

    /**
     * Writes down the exact bytes of a status act before they are sent,
     * so an unanswered attempt is replayed rather than rebuilt. Only ever
     * tracks one act, never a queue.
     */
    @Query(
        """
        UPDATE sync_peer_state SET status_sent_op = :sentOp, status_sent_revision = :revision
        WHERE book_url = :bookUrl AND peer_id = :peerId
        """,
    )
    abstract suspend fun updateStatusSent(
        bookUrl: String,
        peerId: String,
        revision: Long,
        sentOp: String?,
    ): Int

    @Query(
        """
        INSERT OR IGNORE INTO sync_peer_state (
            book_url, peer_id, acked_revision, status_sent_op, status_sent_revision, has_pending
        )
        VALUES (:bookUrl, :peerId, 0, :sentOp, :revision, 0)
        """,
    )
    abstract suspend fun insertStatusSent(
        bookUrl: String,
        peerId: String,
        revision: Long,
        sentOp: String?,
    )

    @Transaction
    open suspend fun rememberStatusSent(
        bookUrl: String,
        peerId: String,
        revision: Long,
        sentOp: String?,
    ) {
        if (updateStatusSent(bookUrl, peerId, revision, sentOp) == 0) {
            insertStatusSent(bookUrl, peerId, revision, sentOp)
        }
    }

    @Query(
        """
        UPDATE sync_peer_state SET status_sent_op = NULL, status_sent_revision = NULL
        WHERE book_url = :bookUrl AND peer_id = :peerId
        """,
    )
    abstract suspend fun clearStatusSent(bookUrl: String, peerId: String)

    /**
     * Records a position acknowledgement that is not an agreement about
     * anything: a revision that moved without the reading moving.
     */
    @Query(
        """
        UPDATE sync_peer_state SET acked_revision = :revision
        WHERE book_url = :bookUrl AND peer_id = :peerId AND acked_revision < :revision
        """,
    )
    abstract suspend fun acknowledgePositionRevision(
        bookUrl: String,
        peerId: String,
        revision: Long,
    ): Int

    @Query(
        """
        INSERT OR IGNORE INTO sync_peer_state (book_url, peer_id, acked_revision, has_pending)
        VALUES (:bookUrl, :peerId, :revision, 0)
        """,
    )
    abstract suspend fun insertPositionAcknowledgement(
        bookUrl: String,
        peerId: String,
        revision: Long,
    )

    @Transaction
    open suspend fun settlePositionRevision(bookUrl: String, peerId: String, revision: Long) {
        if (acknowledgePositionRevision(bookUrl, peerId, revision) == 0) {
            insertPositionAcknowledgement(bookUrl, peerId, revision)
        }
    }

    /**
     * Records that this partner holds the status choice at [statusRevision].
     * Only the status side of the row moves: the position is a separate
     * debt and keeps its own acknowledgement. An acknowledgement is also
     * what retires the act's remembered bytes, so it is never sent again.
     */
    @Query(
        """
        UPDATE sync_peer_state SET
            acked_status_revision = :statusRevision,
            status_sent_op = NULL,
            status_sent_revision = NULL
        WHERE book_url = :bookUrl AND peer_id = :peerId
            AND acked_status_revision < :statusRevision
        """,
    )
    abstract suspend fun acknowledgeStatus(
        bookUrl: String,
        peerId: String,
        statusRevision: Long,
    ): Int

    @Query(
        """
        INSERT OR IGNORE INTO sync_peer_state (
            book_url, peer_id, acked_revision, acked_status_revision, has_pending
        )
        VALUES (:bookUrl, :peerId, 0, :statusRevision, 0)
        """,
    )
    abstract suspend fun insertStatusAcknowledgement(
        bookUrl: String,
        peerId: String,
        statusRevision: Long,
    )

    /**
     * [acknowledgeStatus], creating the row when this book has never been
     * weighed against this partner.
     */
    @Transaction
    open suspend fun settleStatus(bookUrl: String, peerId: String, statusRevision: Long) {
        if (acknowledgeStatus(bookUrl, peerId, statusRevision) == 0) {
            insertStatusAcknowledgement(bookUrl, peerId, statusRevision)
        }
    }

    /**
     * Records what this partner and this device now both hold.
     *
     * [ackedRevision] is exactly the revision that was compared, and no
     * other: a page turned since then was never weighed against the
     * partner, so the book stays owed to it rather than being quietly
     * called settled.
     */
    @Query(
        """
        UPDATE sync_peer_state SET
            acked_revision = :ackedRevision,
            agreed_progression = :progression,
            agreed_status = :status,
            pending_progression = NULL,
            pending_locator_json = NULL,
            pending_edition_sha = NULL,
            pending_status = NULL,
            pending_coverage = NULL,
            pending_updated_at = NULL, pending_seq = NULL, pending_hlc = NULL,
            has_pending = 0,
            synced_at = :now
        WHERE book_url = :bookUrl AND peer_id = :peerId
        """,
    )
    abstract suspend fun updateSettled(
        bookUrl: String,
        peerId: String,
        ackedRevision: Long,
        progression: Double?,
        status: String?,
        now: Long,
    ): Int

    @Query(
        """
        INSERT OR IGNORE INTO sync_peer_state (
            book_url, peer_id, acked_revision,
            agreed_progression, agreed_status, has_pending, synced_at
        )
        VALUES (:bookUrl, :peerId, :ackedRevision, :progression, :status, 0, :now)
        """,
    )
    abstract suspend fun insertSettled(
        bookUrl: String,
        peerId: String,
        ackedRevision: Long,
        progression: Double?,
        status: String?,
        now: Long,
    )

    @Transaction
    open suspend fun settle(
        bookUrl: String,
        peerId: String,
        ackedRevision: Long,
        progression: Double?,
        status: String?,
        now: Long,
    ) {
        val updated = updateSettled(
            bookUrl = bookUrl,
            peerId = peerId,
            ackedRevision = ackedRevision,
            progression = progression,
            status = status,
            now = now,
        )
        if (updated == 0) {
            insertSettled(
                bookUrl = bookUrl,
                peerId = peerId,
                ackedRevision = ackedRevision,
                progression = progression,
                status = status,
                now = now,
            )
        }
    }

    @Query("DELETE FROM sync_peer_state WHERE book_url = :bookUrl")
    abstract suspend fun forget(bookUrl: String)

    @Query("UPDATE sync_peer_state SET drive_imported_reset_revision = :revision WHERE book_url = :bookUrl AND peer_id = :peerId")
    abstract suspend fun setDriveImportedResetRevision(bookUrl: String, peerId: String, revision: Long)

    @Query("DELETE FROM sync_peer_state WHERE book_url IN (:bookUrls)")
    abstract suspend fun forgetBooks(bookUrls: List<String>)

    /**
     * Forgets everything agreed with one partner, for when that account
     * is disconnected. The reading itself is untouched: it stays on this
     * device, it simply no longer has a partner it is in step with.
     */
    @Query("DELETE FROM sync_peer_state WHERE peer_id = :peerId")
    abstract suspend fun forgetPeer(peerId: String)

    @Query("SELECT COUNT(*) FROM sync_peer_state WHERE peer_id = :peerId")
    abstract suspend fun countForPeer(peerId: String): Int

    /**
     * Moves one partner's agreements under a new name for the same
     * partner. Only ever called when nothing sits under [to] yet.
     */
    @Query("UPDATE sync_peer_state SET peer_id = :to WHERE peer_id = :from")
    abstract suspend fun rekeyPeer(from: String, to: String)
}
