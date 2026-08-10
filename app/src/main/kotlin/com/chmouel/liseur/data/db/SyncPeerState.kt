package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
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
interface SyncPeerStateDao {

    @Query("SELECT * FROM sync_peer_state WHERE book_url = :bookUrl AND peer_id = :peerId")
    suspend fun get(bookUrl: String, peerId: String): SyncPeerState?

    @Query("SELECT * FROM sync_peer_state WHERE peer_id = :peerId")
    suspend fun forPeer(peerId: String): List<SyncPeerState>

    /** Disagreements this partner reported and nobody has settled. */
    @Query("SELECT * FROM sync_peer_state WHERE peer_id = :peerId AND has_pending = 1")
    suspend fun pendingFor(peerId: String): List<SyncPeerState>

    @Upsert
    suspend fun upsert(state: SyncPeerState)

    @Query("DELETE FROM sync_peer_state WHERE book_url = :bookUrl")
    suspend fun forget(bookUrl: String)

    @Query("DELETE FROM sync_peer_state WHERE book_url IN (:bookUrls)")
    suspend fun forgetBooks(bookUrls: List<String>)

    /**
     * Forgets everything agreed with one partner, for when that account
     * is disconnected. The reading itself is untouched: it stays on this
     * device, it simply no longer has a partner it is in step with.
     */
    @Query("DELETE FROM sync_peer_state WHERE peer_id = :peerId")
    suspend fun forgetPeer(peerId: String)
}
