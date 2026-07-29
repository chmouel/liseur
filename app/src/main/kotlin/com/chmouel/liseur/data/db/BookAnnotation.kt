package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** What the reader marked: a passage, a thought about it, or a place. */
enum class AnnotationKind { HIGHLIGHT, NOTE, BOOKMARK }

/**
 * Something the reader added to a book: a highlight, a note on a passage,
 * or a bookmark.
 *
 * Keyed by the book's permanent identity rather than the file it lives in,
 * the same way reading positions are, so annotations survive a calibre-web
 * download being removed and fetched again.
 */
@Entity(
    tableName = "annotations",
    indices = [Index("book_id")],
)
data class BookAnnotation(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "locator_json") val locatorJson: String,
    /** The passage as it reads in the book, for the notebook and export. */
    @ColumnInfo(name = "text") val text: String? = null,
    /** What the reader wrote about the passage. */
    @ColumnInfo(name = "note") val note: String? = null,
    /** Highlight colour, as one of [com.chmouel.liseur.reader.annotations.HighlightTint]. */
    @ColumnInfo(name = "tint") val tint: String? = null,
    /** Chapter title at the time it was made, so the notebook reads well offline. */
    @ColumnInfo(name = "chapter") val chapter: String? = null,
    /** Page number, used to tell whether the current page is bookmarked. */
    @ColumnInfo(name = "position") val position: Int? = null,
    @ColumnInfo(name = "total_progression") val totalProgression: Double? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface BookAnnotationDao {
    @Query("SELECT * FROM annotations WHERE book_id = :bookId ORDER BY total_progression, created_at")
    fun observe(bookId: String): Flow<List<BookAnnotation>>

    @Query("SELECT COUNT(*) FROM annotations WHERE book_id = :bookId")
    suspend fun count(bookId: String): Int

    @Upsert
    suspend fun upsert(annotation: BookAnnotation)

    @Delete
    suspend fun delete(annotation: BookAnnotation)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Drops every mark on a book, for when the file no longer holds it. */
    @Query("DELETE FROM annotations WHERE book_id = :bookId")
    suspend fun deleteForBook(bookId: String)
}
