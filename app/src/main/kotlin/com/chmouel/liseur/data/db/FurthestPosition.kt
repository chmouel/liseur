package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** Historical observations never participate in current-position reconciliation. */
@Entity(tableName = "furthest_position", primaryKeys = ["peer_id", "work_id", "edition", "origin"])
data class FurthestPosition(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "work_id") val workId: String,
    val edition: String,
    /** Local retention slot, not a wire origin alias: empty for changes, otherwise a retained op ID. */
    val origin: String,
    val progression: Double,
    val seq: Long,
    val payload: String,
)

/** Acknowledgement of a locally authored peak, not of the current position. */
@Entity(
    tableName = "peak_delivery",
    primaryKeys = ["peer_id", "book_url", "work_id"],
    foreignKeys = [ForeignKey(
        entity = ReadingProgress::class, parentColumns = ["book_url"], childColumns = ["book_url"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("book_url")],
)
data class PeakDelivery(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "work_id") val workId: String,
    val revision: Long,
    val acknowledged: Boolean = false,
)

@Dao
abstract class FurthestPositionDao {
    @Query("SELECT * FROM furthest_position WHERE peer_id = :peer AND work_id = :work")
    abstract suspend fun forWork(peer: String, work: String): List<FurthestPosition>

    @Upsert
    abstract suspend fun put(row: FurthestPosition)

    @Transaction
    open suspend fun observe(row: FurthestPosition) {
        if (!row.progression.isFinite() || row.progression !in 0.0..1.0) return
        val old = forWork(row.peerId, row.workId)
            .find { it.edition == row.edition && it.origin == row.origin }
        if (old == null || row.progression > old.progression ||
            (row.progression == old.progression && row.seq < old.seq)
        ) put(row)
    }

    @Query("SELECT * FROM peak_delivery WHERE peer_id = :peer AND book_url = :book AND work_id = :work")
    abstract suspend fun delivered(peer: String, book: String, work: String): PeakDelivery?

    @Upsert
    abstract suspend fun acknowledge(row: PeakDelivery)

    @Query("DELETE FROM furthest_position WHERE peer_id = :peer")
    abstract suspend fun deleteObservations(peer: String)

    @Query("DELETE FROM furthest_position WHERE peer_id = :peer AND work_id = :work")
    abstract suspend fun deleteWork(peer: String, work: String)

    @Query("DELETE FROM peak_delivery WHERE peer_id = :peer")
    abstract suspend fun deleteDeliveries(peer: String)

    @Transaction
    open suspend fun forgetPeer(peer: String) {
        deleteObservations(peer)
        deleteDeliveries(peer)
    }

    @Query("UPDATE furthest_position SET peer_id = :to WHERE peer_id = :from")
    abstract suspend fun rekeyObservations(from: String, to: String)

    @Query("UPDATE peak_delivery SET peer_id = :to WHERE peer_id = :from")
    abstract suspend fun rekeyDeliveries(from: String, to: String)

    @Transaction
    open suspend fun rekeyPeer(from: String, to: String) {
        rekeyObservations(from, to)
        rekeyDeliveries(from, to)
    }

    @Query("SELECT (SELECT COUNT(*) FROM furthest_position WHERE peer_id = :peer) + (SELECT COUNT(*) FROM peak_delivery WHERE peer_id = :peer)")
    abstract suspend fun countForPeer(peer: String): Int
}
