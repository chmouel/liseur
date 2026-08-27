package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** What the reader marked: a passage, a thought about it or the book, or a place. */
enum class AnnotationKind { HIGHLIGHT, NOTE, BOOK_NOTE, BOOKMARK }

/**
 * Something the reader added to a book: a highlight, a note on a passage,
 * a note about the book itself, or a bookmark.
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
    /** Empty only for an anchorless [AnnotationKind.BOOK_NOTE]. */
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
    /**
     * When this mark was last changed, in epoch **microseconds**.
     *
     * Sent verbatim as `client_ts` to liseur-sync, which is why it is
     * stored rather than read off the clock at push time: the server
     * recognises a repeated write only when the payload is identical to
     * the byte, so a stamp that moved would turn every interrupted push
     * into a conflict. Microseconds because that is the precision the
     * server compares at.
     */
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0,
)

@Dao
interface BookAnnotationDao {
    @Query("SELECT * FROM annotations WHERE book_id = :bookId ORDER BY total_progression, created_at")
    fun observe(bookId: String): Flow<List<BookAnnotation>>

    @Query("SELECT COUNT(*) FROM annotations WHERE book_id = :bookId")
    suspend fun count(bookId: String): Int

    /** Everything marked anywhere, for a backup. */
    @Query("SELECT * FROM annotations ORDER BY book_id, created_at")
    suspend fun all(): List<BookAnnotation>

    /** One mark, or nothing; the sync pass asks about ids it was told. */
    @Query("SELECT * FROM annotations WHERE id = :id")
    suspend fun byId(id: String): BookAnnotation?

    /** Everything marked in one book, as a list rather than a stream. */
    @Query("SELECT * FROM annotations WHERE book_id = :bookId ORDER BY created_at")
    suspend fun forBook(bookId: String): List<BookAnnotation>

    @Upsert
    suspend fun upsert(annotation: BookAnnotation)

    /**
     * Restores marks, leaving alone any that are already here.
     *
     * Ignoring rather than replacing is what makes importing the same
     * file twice harmless, and what stops a stale backup undoing a note
     * you have since rewritten on this device.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(annotations: List<BookAnnotation>): List<Long>

    @Delete
    suspend fun delete(annotation: BookAnnotation)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Drops every mark on a book, for when the file no longer holds it. */
    @Query("DELETE FROM annotations WHERE book_id = :bookId")
    suspend fun deleteForBook(bookId: String)
}
