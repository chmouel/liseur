package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One book's own answer to whether it is read by scrolling.
 *
 * Turning pages or scrolling is mostly a habit, which is why Settings
 * answers it for the whole library. Some books ask for the other one all
 * the same: a technical manual read by hunting through it scrolls, a
 * novel read straight through turns pages. Flipping the switch inside a
 * book sets that book apart without moving the rest.
 *
 * A row means the book has been answered for, and it stays answered for:
 * the app-wide setting can change afterwards without undoing a switch
 * flipped by hand. No row means the book follows the app-wide setting,
 * and keeps following it as that setting changes.
 */
@Entity(tableName = "book_reading_mode")
data class BookReadingMode(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "scroll") val scroll: Boolean,
)

/**
 * Whether this book is read by scrolling, given what the app-wide
 * setting says for everything that has not been answered for.
 */
fun BookReadingMode?.scrollsWith(global: Boolean): Boolean = this?.scroll ?: global

@Dao
interface BookReadingModeDao {
    @Query("SELECT * FROM book_reading_mode WHERE book_url = :bookUrl")
    fun observe(bookUrl: String): Flow<BookReadingMode?>

    @Upsert
    suspend fun upsert(mode: BookReadingMode)
}
