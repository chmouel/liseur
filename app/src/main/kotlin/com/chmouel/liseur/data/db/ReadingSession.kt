package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One stretch of reading: the book, when it began, and how long it
 * lasted.
 *
 * Keyed by the book's permanent identity, the same way positions and
 * annotations are, so a calibre-web download removed and fetched again
 * keeps the hours already put into it.
 *
 * [durationMs] is counted from Android's monotonic clock rather than
 * derived from wall-clock timestamps, so correcting the device clock
 * cannot change a total. [lastCheckpointAt] is the last moment already
 * persisted; an interrupted session is closed there instead of whenever
 * the app next starts, which would invent background time.
 */
@Entity(
    tableName = "reading_sessions",
    indices = [Index("book_url"), Index("started_at")],
)
data class ReadingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    /** Null while the session is still open. */
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    /** The last moment this session was safely persisted. */
    @ColumnInfo(name = "last_checkpoint_at") val lastCheckpointAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
) {
    val isOpen: Boolean get() = endedAt == null
}

@Dao
interface ReadingSessionDao {

    @Insert
    suspend fun insert(session: ReadingSession): Long

    /**
     * Stores the total reached by an open session and moves its checkpoint
     * forward.
     *
     * Taking the maximum makes the write idempotent: retrying the same
     * checkpoint cannot pay for the same minute twice, and a stale write
     * cannot drag a newer total backwards.
     *
     * A closed session is left alone. Once [closeInterruptedSessions] has
     * settled a session at the last page it saw, a checkpoint arriving
     * late must not reopen the question by adding to it.
     */
    @Query(
        """
        UPDATE reading_sessions
        SET duration_ms = MAX(duration_ms, :totalMs),
            last_checkpoint_at = MAX(last_checkpoint_at, :atMillis)
        WHERE id = :id AND ended_at IS NULL
        """,
    )
    suspend fun checkpoint(id: Long, totalMs: Long, atMillis: Long)

    /** Closes a session for good, at a moment reading is known to have happened. */
    @Query(
        """
        UPDATE reading_sessions
        SET duration_ms = MAX(duration_ms, :totalMs),
            last_checkpoint_at = MAX(last_checkpoint_at, :atMillis),
            ended_at = MAX(started_at, last_checkpoint_at, :atMillis)
        WHERE id = :id AND ended_at IS NULL
        """,
    )
    suspend fun finish(id: Long, totalMs: Long, atMillis: Long)

    /**
     * Sessions the app never got to close, oldest first.
     *
     * There should be at most one, but a crash at the wrong moment can
     * leave more, and leaving them open would mean they were never
     * counted at all.
     */
    @Query("SELECT * FROM reading_sessions WHERE ended_at IS NULL ORDER BY started_at")
    suspend fun openSessions(): List<ReadingSession>

    /** Closes every interrupted session at its last safe checkpoint. */
    @Query(
        """
        UPDATE reading_sessions
        SET ended_at = last_checkpoint_at
        WHERE ended_at IS NULL
        """,
    )
    suspend fun closeInterruptedSessions()

    @Query("SELECT * FROM reading_sessions WHERE id = :id")
    suspend fun get(id: Long): ReadingSession?

    /** Every session anywhere, newest first. The dashboard's whole input. */
    @Query("SELECT * FROM reading_sessions ORDER BY started_at DESC")
    fun observeAll(): Flow<List<ReadingSession>>

    @Query("DELETE FROM reading_sessions WHERE book_url = :bookUrl")
    suspend fun deleteForBook(bookUrl: String)

    @Query("DELETE FROM reading_sessions WHERE book_url IN (:bookUrls)")
    suspend fun deleteForBooks(bookUrls: List<String>)
}
