package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * Which BookOrbit file a book's local copy is, or is going to be.
 *
 * BookOrbit says nothing about a book's files that cannot change under
 * it: a new scan can make a different EPUB the primary one, and the
 * order of the array it answers with is not a promise. Liseur, on the
 * other hand, writes reading positions and highlights against whichever
 * file it downloaded, and a refresh that silently moved the book to a
 * different file id would send the old edition's position to the new
 * edition's progress.
 *
 * So the choice is written down once, under the identity of the account
 * and the book, and every later refresh reads it back rather than
 * choosing again. A file that has gone from the server leaves the row
 * as [BookOrbitBindingState.MISSING] rather than being replaced: the
 * reader's place was recorded in a file, and quietly pointing it at a
 * different one is not a repair.
 *
 * Keyed by [accountKey] as well as [bookUrl] because the same book URL
 * can outlive a change of account, and a file id means something only
 * to the installation that issued it.
 */
@Entity(
    tableName = "book_orbit_binding",
    primaryKeys = ["account_key", "book_url"],
)
data class BookOrbitBinding(
    @ColumnInfo(name = "account_key") val accountKey: String,
    /** The permanent identity of the book on this device. */
    @ColumnInfo(name = "book_url") val bookUrl: String,
    /** BookOrbit's own book id, which its personal status routes take. */
    @ColumnInfo(name = "book_id") val bookId: Long,
    /** The file whose bytes, or whose progress, this book is read as. */
    @ColumnInfo(name = "file_id") val fileId: Long?,
    @ColumnInfo(name = "file_format") val fileFormat: String?,
    @ColumnInfo(name = "file_size") val fileSize: Long?,
    @ColumnInfo(name = "file_name") val fileName: String?,
    /** Bumped whenever the chosen file changes, to guard an in-flight read. */
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /**
     * The SHA-256 of the bytes this device uploaded and proved to be
     * [fileId], for a book adopted from an upload rather than downloaded.
     *
     * Written by the upload adoption alone and never by a catalog pass or
     * the fingerprint cache: it is what says the local copy, which keeps
     * its own URL and file, is the server's file byte for byte. Null for
     * every downloaded book, whose bytes are the app's own download.
     */
    @ColumnInfo(name = "local_sha256") val localSha256: String? = null,
) {
    val stateValue: BookOrbitBindingState get() = BookOrbitBindingState.fromStored(state)
}

/** How sure the stored choice is. Stored by name, so the spellings are schema. */
enum class BookOrbitBindingState {
    /** Nothing has been chosen yet. */
    UNBOUND,

    /** A file is chosen but its bytes are not here. */
    SELECTED,

    /** The chosen file's bytes are on the device. */
    DOWNLOADED,

    /** The server no longer offers the chosen file. */
    MISSING,
    ;

    companion object {
        fun fromStored(value: String?): BookOrbitBindingState =
            entries.firstOrNull { it.name == value } ?: UNBOUND
    }
}

@Dao
interface BookOrbitBindingDao {

    @Query("SELECT * FROM book_orbit_binding WHERE account_key = :accountKey AND book_url = :bookUrl")
    suspend fun get(accountKey: String, bookUrl: String): BookOrbitBinding?

    @Query("SELECT * FROM book_orbit_binding WHERE account_key = :accountKey")
    suspend fun forAccount(accountKey: String): List<BookOrbitBinding>

    @Query(
        "SELECT * FROM book_orbit_binding WHERE account_key = :accountKey " +
            "AND (:afterUrl IS NULL OR book_url > :afterUrl) " +
            "AND file_id IS NOT NULL AND LOWER(file_format) = 'epub' " +
            "AND state IN ('SELECTED', 'DOWNLOADED') ORDER BY book_url LIMIT :limit",
    )
    suspend fun positionPage(accountKey: String, afterUrl: String?, limit: Int): List<BookOrbitBinding>

    @Query("SELECT book_url FROM book_orbit_binding WHERE account_key = :accountKey")
    suspend fun bookUrls(accountKey: String): List<String>

    @Query("DELETE FROM book_orbit_binding WHERE account_key = :accountKey AND book_url = :bookUrl")
    suspend fun delete(accountKey: String, bookUrl: String)

    @Query("DELETE FROM book_orbit_binding WHERE account_key = :accountKey")
    suspend fun clearAccount(accountKey: String)

    @Query("DELETE FROM book_orbit_binding WHERE book_url = :bookUrl")
    suspend fun clearBook(bookUrl: String)

    /** Drops bindings whose remote-only book was removed during account cleanup. */
    @Query(
        "DELETE FROM book_orbit_binding WHERE NOT EXISTS " +
            "(SELECT 1 FROM books WHERE books.url = book_orbit_binding.book_url)",
    )
    suspend fun clearOrphans()

    /**
     * Records the file a book is read as, leaving a bound choice alone.
     *
     * The first choice wins. A refresh that found a different primary
     * EPUB must not move the reading that is already attached to this
     * one; changing the file is the reader's decision, made through an
     * explicit action that writes the row itself.
     */
    @Transaction
    open suspend fun bindIfUnbound(binding: BookOrbitBinding): BookOrbitBinding {
        val existing = get(binding.accountKey, binding.bookUrl)
        if (existing != null && existing.stateValue != BookOrbitBindingState.UNBOUND) return existing
        insertMissing(binding)
        return get(binding.accountKey, binding.bookUrl) ?: binding
    }

    /** The first edition chosen wins when two discovery paths overlap. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(binding: BookOrbitBinding): Long

    /**
     * Room's own upsert, compiled to an insert followed by an update.
     *
     * Deliberately not a hand-written SQL upsert: that statement needs a
     * newer SQLite than Liseur's minimum Android ships, and the test that
     * guards against writing one exists precisely for this table.
     */
    @Upsert
    suspend fun write(binding: BookOrbitBinding)
}
