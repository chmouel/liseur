package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The book address an archive used for a sitting this app restored.
 *
 * One sitting can be recognized under several archived addresses: the same
 * backup restored twice, or two books merged onto one shelf entry. Keeping
 * them apart from `reading_sessions.book_url` is what stops a later restore
 * from adding the sitting again once the book resolves somewhere else.
 */
@Entity(
    tableName = "session_import_source",
    primaryKeys = ["archived_url", "started_at"],
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
data class SessionImportSource(
    @ColumnInfo(name = "archived_url") val archivedUrl: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "session_id") val sessionId: Long,
)

@Dao
interface SessionImportSourceDao {
    @Query("SELECT session_id FROM session_import_source WHERE archived_url = :archivedUrl AND started_at = :startedAt")
    suspend fun sessionIdFor(archivedUrl: String, startedAt: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(source: SessionImportSource)

    /** Hands every archived identity of a row that is about to be deleted to the row that replaces it. */
    @Query("UPDATE session_import_source SET session_id = :sessionId WHERE session_id = :fromSessionId")
    suspend fun reassign(fromSessionId: Long, sessionId: Long)

    @Query("SELECT * FROM session_import_source")
    suspend fun all(): List<SessionImportSource>
}
