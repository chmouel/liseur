package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert

/**
 * A sitting one sync server said it would never take, written down.
 *
 * The server appends a batch of sessions all or nothing, and when it
 * refuses one it names the item. Before this row existed the whole
 * batch was marked as sent on any such refusal — up to a thousand hours
 * of reading gone because a neighbour was malformed. Now only the named
 * sitting is set aside, here, and the rest go again.
 *
 * Per peer, because the refusal is one server's verdict: an id it holds
 * under a different payload is free on the next server, and a rule it
 * enforces another may not. Keyed by the *local* row rather than the
 * wire id so the upload query can anti-join it before it limits; the
 * wire id is kept for reading the log.
 */
@Entity(
    tableName = "session_refusal",
    primaryKeys = ["peer_id", "session_id"],
    foreignKeys = [
        ForeignKey(
            entity = ReadingSession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
data class SessionRefusal(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "wire_session_id") val wireSessionId: String,
    /** The server's machine-readable reason, or null when it gave prose only. */
    val code: String?,
    @ColumnInfo(name = "refused_at") val refusedAt: Long,
)

@Dao
interface SessionRefusalDao {

    @Upsert
    suspend fun record(refusal: SessionRefusal)

    @Query("SELECT * FROM session_refusal WHERE peer_id = :peerId ORDER BY refused_at")
    suspend fun forPeer(peerId: String): List<SessionRefusal>

    @Query("SELECT COUNT(*) FROM session_refusal WHERE peer_id = :peerId")
    suspend fun countForPeer(peerId: String): Int

    /** Forgets one server's verdicts, when that account is left. */
    @Query("DELETE FROM session_refusal WHERE peer_id = :peerId")
    suspend fun clearPeer(peerId: String)

    /** Renames a peer's rows when its key changes spelling. Only when nothing sits under [to]. */
    @Query("UPDATE session_refusal SET peer_id = :to WHERE peer_id = :from")
    suspend fun rekeyPeer(from: String, to: String)
}
