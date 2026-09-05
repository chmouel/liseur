package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** The immutable payload selected before a sitting's first request to this peer. */
@Entity(
    tableName = "session_transmission",
    primaryKeys = ["peer_id", "session_id"],
    indices = [Index("session_id")],
    foreignKeys = [ForeignKey(
        entity = ReadingSession::class,
        parentColumns = ["id"],
        childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class SessionTransmission(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    /** Attempted device until an ACK confirms the current token's stamp; empty means unknown. */
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "payload") val payload: String,
)

@Dao
interface SessionTransmissionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SessionTransmission)

    @Query("SELECT * FROM session_transmission WHERE peer_id = :peerId AND session_id = :sessionId")
    suspend fun get(peerId: String, sessionId: Long): SessionTransmission?

    @Query("SELECT * FROM session_transmission WHERE peer_id = :peerId ORDER BY session_id")
    suspend fun forPeer(peerId: String): List<SessionTransmission>

    @Query("SELECT COUNT(*) FROM session_transmission WHERE peer_id = :peerId")
    suspend fun countForPeer(peerId: String): Int

    @Query("UPDATE session_transmission SET peer_id = :to WHERE peer_id = :from")
    suspend fun rekeyPeer(from: String, to: String)

    @Query("DELETE FROM session_transmission WHERE peer_id = :peerId")
    suspend fun clearPeer(peerId: String)

    /** A complete ACK proves this payload was accepted under the authenticated device. */
    @Query("""
        UPDATE session_transmission SET device_id = :confirmedDevice
        WHERE peer_id = :peerId AND session_id = :sessionId
          AND device_id = :attemptedDevice AND payload = :payload
    """)
    suspend fun confirmDevice(
        peerId: String,
        sessionId: Long,
        attemptedDevice: String,
        payload: String,
        confirmedDevice: String,
    ): Int

    /** Only an explicit atomic unknown-work refusal allows replacing the rejected identity. */
    @Query("""
        UPDATE session_transmission SET payload = :replacement
        WHERE peer_id = :peerId AND session_id = :sessionId AND payload = :rejected
    """)
    suspend fun replaceRejected(peerId: String, sessionId: Long, rejected: String, replacement: String)
}
