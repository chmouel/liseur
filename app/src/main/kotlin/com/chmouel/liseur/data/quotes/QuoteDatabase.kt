package com.chmouel.liseur.data.quotes

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A line found in the part of a book already read, kept to quote on Home. */
@Entity(tableName = "book_quote", primaryKeys = ["book_url", "ord"])
data class QuoteRow(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    val ord: Int,
    val text: String,
    @ColumnInfo(name = "locator_json") val locatorJson: String,
    /** Where the line's paragraph ends, so a book read back from further on hides it again. */
    @ColumnInfo(name = "total_progression") val totalProgression: Double,
    val chapter: String?,
    val score: Double,
    /**
     * The work these words were walked from, carried on the line itself.
     *
     * The scan row says it too, but the two are read at different
     * moments: a line emitted before a replacement and a scan record
     * written after it would otherwise be taken for each other, and the
     * old book's words shown under the new title.
     */
    @ColumnInfo(name = "work_id") val workId: String? = null,
)

/** How far a book has been looked through for lines, and by which scorer. */
@Entity(tableName = "book_quote_scan")
data class QuoteScanRow(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "scanned_through") val scannedThrough: Double,
    val version: Int,
    /** False for a book whose text cannot be walked, so it is not opened again. */
    val usable: Boolean,
    @ColumnInfo(name = "scanned_at") val scannedAt: Long,
    /**
     * The work the lines were walked from, as `books.work_id` spelled it.
     *
     * A replaced file keeps its URL, so the URL alone cannot say whether
     * these lines are still this book's words. Null when the file could
     * not name itself either time.
     */
    @ColumnInfo(name = "work_id") val workId: String? = null,
)

@Dao
abstract class QuoteDao {
    @Query("SELECT * FROM book_quote")
    abstract fun observeAll(): Flow<List<QuoteRow>>

    @Query("SELECT * FROM book_quote_scan")
    abstract suspend fun scans(): List<QuoteScanRow>

    @Query("SELECT book_url FROM book_quote_scan")
    abstract suspend fun books(): List<String>

    @Insert
    protected abstract suspend fun insert(rows: List<QuoteRow>)

    @Upsert
    protected abstract suspend fun upsert(scan: QuoteScanRow)

    @Query("DELETE FROM book_quote WHERE book_url = :bookUrl")
    protected abstract suspend fun clear(bookUrl: String)

    @Query("DELETE FROM book_quote WHERE book_url IN (:urls)")
    protected abstract suspend fun forgetQuotes(urls: List<String>)

    @Query("DELETE FROM book_quote_scan WHERE book_url IN (:urls)")
    protected abstract suspend fun forgetScans(urls: List<String>)

    @Transaction
    open suspend fun replace(scan: QuoteScanRow, rows: List<QuoteRow>) {
        clear(scan.bookUrl)
        insert(rows)
        upsert(scan)
    }

    @Transaction
    open suspend fun forget(urls: List<String>) {
        forgetQuotes(urls)
        forgetScans(urls)
    }
}

/**
 * Kept apart from `liseur.db`: everything here is worked out again from
 * the books, so it stays out of backups and sync and needs no migration
 * when the scorer changes.
 */
@Database(entities = [QuoteRow::class, QuoteScanRow::class], version = 3, exportSchema = true)
abstract class QuoteDatabase : RoomDatabase() {
    abstract fun quotes(): QuoteDao

    companion object {
        const val NAME = "quotes.db"
    }
}
