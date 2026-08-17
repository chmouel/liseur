package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One book's own answer to whether the screen should stay awake.
 *
 * Keeping the screen on is normally a habit rather than a decision per
 * book, which is why Settings holds the answer for the whole library.
 * But a book read in short sittings and a book read straight through do
 * not want the same thing, so the reader's own switch sets this book
 * apart without touching the rest.
 *
 * A row means the book has been answered for, and it stays answered for:
 * the app-wide setting can change afterwards without moving a book whose
 * switch was flipped by hand. No row means the book follows the app-wide
 * setting, and keeps following it as that setting changes.
 */
@Entity(tableName = "book_screen")
data class BookScreen(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "keep_screen_on") val keepScreenOn: Boolean,
)

/**
 * Whether this book holds the screen awake, given what the app-wide
 * setting says for everything that has not been answered for.
 */
fun BookScreen?.keepsScreenOnWith(global: Boolean): Boolean = this?.keepScreenOn ?: global

@Dao
interface BookScreenDao {
    @Query("SELECT * FROM book_screen WHERE book_url = :bookUrl")
    fun observe(bookUrl: String): Flow<BookScreen?>

    @Upsert
    suspend fun upsert(screen: BookScreen)
}
