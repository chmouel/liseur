package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Last reading position for a book, keyed by the book's URL
 * (a `content://` or `file://` URL until the library phase lands).
 * The position itself is a serialized Readium Locator.
 */
@Entity(tableName = "reading_progress")
data class ReadingProgress(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "locator_json") val locatorJson: String,
    @ColumnInfo(name = "total_progression") val totalProgression: Double?,
    @ColumnInfo(name = "reading_speed") val readingSpeed: Double? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Reading status as calibre-web's Kobo sync understands it. */
    @ColumnInfo(name = "status") val status: String? = null,
    /** When this position was last agreed with the server, if ever. */
    @ColumnInfo(name = "synced_at") val syncedAt: Long? = null,
)

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress WHERE book_url = :bookUrl")
    suspend fun get(bookUrl: String): ReadingProgress?

    @Query("SELECT total_progression FROM reading_progress WHERE book_url = :bookUrl")
    fun observeTotalProgression(bookUrl: String): kotlinx.coroutines.flow.Flow<Double?>

    @Query("SELECT * FROM reading_progress")
    suspend fun getAll(): List<ReadingProgress>

    @Query("UPDATE reading_progress SET synced_at = :syncedAt WHERE book_url = :bookUrl")
    suspend fun markSynced(bookUrl: String, syncedAt: Long)

    @Upsert
    suspend fun upsert(progress: ReadingProgress)
}
