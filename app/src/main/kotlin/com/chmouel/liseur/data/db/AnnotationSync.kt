package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert

/**
 * What a liseur-sync server has confirmed about one annotation, and what
 * is currently on its way there.
 *
 * Annotations are the one kind of reading state that is *edited*, so
 * they cannot be derived from a current-value row the way a position
 * can. They are not queued either: this table records what the server
 * acknowledged, and the work owed is the difference between that and
 * the live `annotations` table. A live annotation with no row here is a
 * create; one whose content no longer matches [ackedFingerprint] is an
 * edit; a row here with no annotation left is a deletion.
 *
 * The second half of the row exists because a derived payload cannot be
 * reproduced once the reader edits the row it was derived from. A
 * request whose response was lost would come back a different shape,
 * miss the server's byte-identical retry check, and resolve as a
 * conflict that quietly threw the reader's edit away. So the exact
 * bytes sent are kept in [pendingJson] until the server answers, and
 * replayed verbatim if it never did.
 *
 * Keyed per peer like [WorkAlias]: a rev means nothing on a server that
 * did not issue it.
 */
@Entity(
    tableName = "annotation_sync",
    primaryKeys = ["id", "peer_id"],
    indices = [Index("book_id"), Index("work_id")],
)
data class AnnotationSync(
    val id: String,
    @ColumnInfo(name = "peer_id") val peerId: String,
    /** The book URL the annotation hung on, so a removed book can forget it. */
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "work_id") val workId: String,
    /** The last rev the server confirmed; 0 means it may not know this id. */
    val rev: Long = 0,
    val seq: Long = 0,
    /** Content-only hash of what the server confirmed; null until it has. */
    @ColumnInfo(name = "acked_fingerprint") val ackedFingerprint: String? = null,
    /** [PENDING_WRITE], [PENDING_DELETE], or null when nothing is in flight. */
    @ColumnInfo(name = "pending_kind") val pendingKind: String? = null,
    /** The exact request item sent, verbatim. Never reparsed. */
    @ColumnInfo(name = "pending_json") val pendingJson: String? = null,
    /** The `base_rev` sent with a write, or the `rev` sent with a delete. */
    @ColumnInfo(name = "pending_rev") val pendingRev: Long = 0,
    @ColumnInfo(name = "pending_fingerprint") val pendingFingerprint: String? = null,
    /**
     * Content the server refused for a reason no retry can mend. Kept so
     * the same payload is not offered forever; a later edit changes the
     * fingerprint and tries again.
     */
    @ColumnInfo(name = "rejected_fingerprint") val rejectedFingerprint: String? = null,
    /**
     * When a deferred refusal may be tried again, as epoch millis.
     *
     * A per-work cap and a badly set clock are both real refusals that
     * re-resolving nothing repairs, and retrying immediately only spins.
     */
    @ColumnInfo(name = "retry_not_before") val retryNotBefore: Long = 0,
) {
    /** Whether a request for this row is still unanswered. */
    val pending: Boolean get() = pendingKind != null

    /**
     * Whether this row is still waiting on the very request [sent] made.
     *
     * Asked before an answer is written down, because the reader keeps
     * reading while a call is in the air. In that window the mark can be
     * edited, deleted, or carried off by a file that took over its path
     * — and an answer applied to a snapshot taken before the call would
     * put the edit back the way it was, or recreate an agreement that
     * was deliberately dropped and so post a delete for a mark that is
     * alive on every other device.
     */
    fun sameRequestAs(sent: AnnotationSync): Boolean =
        pendingKind == sent.pendingKind &&
            pendingRev == sent.pendingRev &&
            pendingFingerprint == sent.pendingFingerprint &&
            pendingJson == sent.pendingJson

    companion object {
        const val PENDING_WRITE = "write"
        const val PENDING_DELETE = "delete"
    }
}

@Dao
interface AnnotationSyncDao {
    @Query("SELECT * FROM annotation_sync WHERE peer_id = :peerId")
    suspend fun forPeer(peerId: String): List<AnnotationSync>

    @Query("SELECT * FROM annotation_sync WHERE peer_id = :peerId AND pending_kind IS NOT NULL")
    suspend fun pendingFor(peerId: String): List<AnnotationSync>

    @Query("SELECT * FROM annotation_sync WHERE peer_id = :peerId AND book_id = :bookId")
    suspend fun forBook(peerId: String, bookId: String): List<AnnotationSync>

    @Query("SELECT * FROM annotation_sync WHERE peer_id = :peerId AND work_id = :workId")
    suspend fun forWork(peerId: String, workId: String): List<AnnotationSync>

    @Query("SELECT * FROM annotation_sync WHERE peer_id = :peerId AND id = :id")
    suspend fun get(peerId: String, id: String): AnnotationSync?

    @Upsert
    suspend fun upsert(row: AnnotationSync)

    @Upsert
    suspend fun upsertAll(rows: List<AnnotationSync>)

    @Query("DELETE FROM annotation_sync WHERE peer_id = :peerId AND id = :id")
    suspend fun deleteById(peerId: String, id: String)

    /**
     * Forgets a book's agreements without deleting anything on the
     * server. A book removed from this device is not a reader deleting
     * their highlights, and pushing tombstones because a file went away
     * would empty a library from the one device that lost it.
     */
    @Query("DELETE FROM annotation_sync WHERE book_id = :bookId")
    suspend fun forgetBook(bookId: String)

    /** Forgets everything one server had confirmed. */
    @Query("DELETE FROM annotation_sync WHERE peer_id = :peerId")
    suspend fun forgetPeer(peerId: String)
}
