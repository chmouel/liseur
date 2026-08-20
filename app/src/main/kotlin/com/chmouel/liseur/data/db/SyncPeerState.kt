package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

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
    @ColumnInfo(name = "agreed_progression") val agreedProgression: Double? = null,
    @ColumnInfo(name = "agreed_status") val agreedStatus: String? = null,
    /** Reported by the partner and not yet settled. */
    @ColumnInfo(name = "pending_progression") val pendingProgression: Double? = null,
    /** Locator and edition from the same op as [pendingProgression]. */
    @ColumnInfo(name = "pending_locator_json") val pendingLocatorJson: String? = null,
    @ColumnInfo(name = "pending_edition_sha") val pendingEditionSha: String? = null,
    @ColumnInfo(name = "pending_status") val pendingStatus: String? = null,
    @ColumnInfo(name = "pending_updated_at") val pendingUpdatedAt: Long? = null,
    /** True once a partner has reported something waiting to be settled. */
    @ColumnInfo(name = "has_pending", defaultValue = "0") val hasPending: Boolean = false,
    /** The partner's own timestamp, for display. Never compared with ours. */
    @ColumnInfo(name = "remote_updated_at") val remoteUpdatedAt: Long? = null,
    @ColumnInfo(name = "synced_at") val syncedAt: Long? = null,
) {
    /** True when this device has read on since this partner last confirmed. */
    fun isDirty(localRevision: Long): Boolean = localRevision > ackedRevision
}

@Dao
abstract class SyncPeerStateDao {

    @Query("SELECT * FROM sync_peer_state WHERE book_url = :bookUrl AND peer_id = :peerId")
    abstract suspend fun get(bookUrl: String, peerId: String): SyncPeerState?

    @Query("SELECT * FROM sync_peer_state WHERE peer_id = :peerId")
    abstract suspend fun forPeer(peerId: String): List<SyncPeerState>

    /** Disagreements this partner reported and nobody has settled. */
    @Query("SELECT * FROM sync_peer_state WHERE peer_id = :peerId AND has_pending = 1")
    abstract suspend fun pendingFor(peerId: String): List<SyncPeerState>

    @Query("SELECT COUNT(*) FROM sync_peer_state WHERE peer_id = :peerId AND has_pending = 1")
    abstract suspend fun countPending(peerId: String): Int

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
            pending_updated_at = :remoteUpdatedAt,
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
    ): Int

    @Query(
        """
        INSERT OR IGNORE INTO sync_peer_state (
            book_url, peer_id, acked_revision,
            pending_progression, pending_locator_json, pending_edition_sha,
            pending_status, pending_updated_at,
            has_pending, remote_updated_at
        )
        VALUES (:bookUrl, :peerId, 0, :progression, :locatorJson, :editionSha,
                :status, :remoteUpdatedAt, 1,
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
    ) {
        val updated = updatePending(
            bookUrl = bookUrl,
            peerId = peerId,
            progression = progression,
            status = status,
            remoteUpdatedAt = remoteUpdatedAt,
            locatorJson = locatorJson,
            editionSha = editionSha,
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
            )
        }
    }

    @Query(
        """
        UPDATE sync_peer_state SET
            pending_progression = NULL, pending_locator_json = NULL,
            pending_edition_sha = NULL, pending_status = NULL,
            pending_updated_at = NULL, has_pending = 0
        WHERE book_url = :bookUrl AND peer_id = :peerId
        """,
    )
    abstract suspend fun clearPending(bookUrl: String, peerId: String)

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
            pending_updated_at = NULL,
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

    @Query("DELETE FROM sync_peer_state WHERE book_url IN (:bookUrls)")
    abstract suspend fun forgetBooks(bookUrls: List<String>)

    /**
     * Forgets everything agreed with one partner, for when that account
     * is disconnected. The reading itself is untouched: it stays on this
     * device, it simply no longer has a partner it is in step with.
     */
    @Query("DELETE FROM sync_peer_state WHERE peer_id = :peerId")
    abstract suspend fun forgetPeer(peerId: String)
}
