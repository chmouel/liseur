package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import kotlinx.coroutines.flow.Flow

/**
 * Typography kept for one book alone.
 *
 * Most books want the same settings, so changing the font is normally a
 * change to how everything reads. Some books do not: a verse collection
 * that needs wider margins, a badly typeset scan that only becomes
 * readable a size larger. A row here means that book has been set apart,
 * and it holds the whole set rather than a scattering of exceptions, so
 * the answer to "what does this book look like" is in one place.
 *
 * Only the four settings that belong to the book are here. The reading
 * theme, the brightness and the footer are about the room you are in and
 * the way you like to read, not about the book, so they stay shared.
 */
@Entity(tableName = "book_typography")
data class BookTypography(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "font") val font: String,
    @ColumnInfo(name = "font_size") val fontSize: Double,
    @ColumnInfo(name = "line_height") val lineHeight: Double?,
    @ColumnInfo(name = "page_margins") val pageMargins: Double?,
) {
    companion object {
        /** Sets a book apart starting from how it reads right now. */
        fun from(bookUrl: String, prefs: ReaderPrefs) = BookTypography(
            bookUrl = bookUrl,
            font = prefs.font.id,
            fontSize = prefs.fontSize,
            lineHeight = prefs.lineHeight,
            pageMargins = prefs.pageMargins,
        )
    }
}

/**
 * How this book reads: the shared settings, with a book's own set taking
 * over entirely when it has one.
 */
fun ReaderPrefs.withTypographyOf(own: BookTypography?): ReaderPrefs =
    if (own == null) {
        this
    } else {
        copy(
            font = ReaderFont.fromId(own.font),
            fontSize = own.fontSize,
            lineHeight = own.lineHeight,
            pageMargins = own.pageMargins,
        )
    }

@Dao
interface BookTypographyDao {
    @Query("SELECT * FROM book_typography WHERE book_url = :bookUrl")
    fun observe(bookUrl: String): Flow<BookTypography?>

    @Query("SELECT * FROM book_typography WHERE book_url = :bookUrl")
    suspend fun get(bookUrl: String): BookTypography?

    @Upsert
    suspend fun upsert(typography: BookTypography)

    /** Hands the book back to the shared settings. */
    @Query("DELETE FROM book_typography WHERE book_url = :bookUrl")
    suspend fun clear(bookUrl: String)
}
