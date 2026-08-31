package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.chmouel.liseur.data.settings.ReadingFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.sanitized
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
        /**
         * Sets a book apart starting from how it reads right now.
         *
         * Records the *raw* font id, which for an imported font may name
         * one whose file is currently missing. Writing the fallback here
         * instead would quietly destroy the choice: set a book apart while
         * its font happens to be gone, and re-importing the file would no
         * longer bring it back.
         *
         * The numbers, unlike the font id, are written already made
         * safe. A row here is a file on a device, and every one of these
         * three is a number `EpubPreferences` will refuse — so a bad one
         * stored now is a crash the next time the book is opened, long
         * after anything could explain it.
         */
        fun from(bookUrl: String, prefs: ReaderPrefs): BookTypography {
            val safe = prefs.sanitized()
            return BookTypography(
                bookUrl = bookUrl,
                font = prefs.font.id,
                fontSize = safe.fontSize,
                lineHeight = safe.lineHeight,
                pageMargins = safe.pageMargins,
            )
        }
    }
}

/**
 * How this book reads: the shared settings, with a book's own set taking
 * over entirely when it has one.
 *
 * Sanitized *after* the merge rather than before it, so the values that
 * are checked are the ones that will actually be used: a book's own row
 * is the newer, less trustworthy source, and checking the shared
 * settings it replaces would prove nothing about it.
 *
 * The fine typography settings are deliberately not among the fields a
 * book can hold. Alignment, hyphenation, weight and the three spacings
 * stay shared even for a book that has been set apart — they are about
 * how the reader reads rather than about the book, and the row would
 * need a migration to carry them.
 */
fun ReaderPrefs.withTypographyOf(own: BookTypography?): ReaderPrefs =
    if (own == null) {
        this
    } else {
        copy(
            font = ReadingFont.fromId(own.font),
            fontSize = own.fontSize,
            lineHeight = own.lineHeight,
            pageMargins = own.pageMargins,
        ).sanitized()
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
