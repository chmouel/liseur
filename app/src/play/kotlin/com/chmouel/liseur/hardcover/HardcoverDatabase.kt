package com.chmouel.liseur.hardcover

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.chmouel.liseur.data.calibre.CredentialCipher
import kotlinx.coroutines.flow.Flow

/**
 * The Hardcover account reading is reported to.
 *
 * Only one row ever exists ([SINGLE_ID]). The token is sealed with the
 * Keystore like every other credential.
 */
@Entity(tableName = "hardcover_account")
data class HardcoverAccount(
    @PrimaryKey val id: Long = SINGLE_ID,
    /** Hardcover's own id for the user; library queries are filtered by it. */
    @ColumnInfo(name = "user_id") val userId: Long,
    val username: String,
    @ColumnInfo(name = "token_cipher") val tokenCipher: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /** False when books go out only when the reader asks. */
    @ColumnInfo(name = "auto_sync", defaultValue = "1") val autoSync: Boolean = true,
    /** Set when Hardcover last refused the token, so the Services page can say so. */
    @ColumnInfo(name = "token_refused", defaultValue = "0") val tokenRefused: Boolean = false,
) {
    /** Null when this Keystore cannot open the token, as after a restore onto another phone. */
    @get:Ignore
    val token: String?
        get() = CredentialCipher.decrypt(tokenCipher)

    companion object {
        const val SINGLE_ID = 1L

        fun seal(token: String): String = CredentialCipher.encrypt(token)
    }
}

/**
 * Which Hardcover edition a book on this device is.
 *
 * Chosen once and remembered: an ISBN names an edition exactly, but a
 * title search is a guess, and guessing again on every push could guess
 * differently. A row with [excluded] set and no edition is a book kept
 * off Hardcover before it was ever matched.
 */
@Entity(tableName = "hardcover_book")
data class HardcoverBookLink(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "hardcover_book_id") val hardcoverBookId: Long? = null,
    @ColumnInfo(name = "edition_id") val editionId: Long? = null,
    @ColumnInfo(name = "edition_title") val editionTitle: String? = null,
    /** The edition's page count, which turns a percentage into a page. */
    @ColumnInfo(name = "edition_pages") val editionPages: Int? = null,
    @ColumnInfo(name = "excluded", defaultValue = "0") val excluded: Boolean = false,
    @ColumnInfo(name = "pushed_at") val pushedAt: Long? = null,
    /** The rating this device last sent, so only that one is ever cleared. */
    @ColumnInfo(name = "pushed_rating") val pushedRating: Double? = null,
    /**
     * Set before a push marks the book read, and cleared once the push
     * ends, so a retry still replaces the date Hardcover stamped.
     */
    @ColumnInfo(name = "dates_pending", defaultValue = "0") val datesPending: Boolean = false,
    /**
     * Set when the reader picked the edition here, until a push has moved
     * the Hardcover entry and its read onto it. An edition found without
     * asking never replaces the one the reader keeps on Hardcover.
     */
    @ColumnInfo(name = "edition_chosen", defaultValue = "0") val editionChosen: Boolean = false,
    /**
     * The Hardcover cover last saved as this book's cover, and the digest
     * of the override it became, so an unchanged cover is not offered again.
     */
    @ColumnInfo(name = "imported_cover_url") val importedCoverUrl: String? = null,
    @ColumnInfo(name = "imported_cover_sha256") val importedCoverSha256: String? = null,
) {
    @get:Ignore
    val matched: Boolean get() = hardcoverBookId != null && editionId != null
}

/**
 * The Hardcover user the [HardcoverBookLink] rows were made for. It
 * outlives a disconnect, so signing in again can tell whether the matches
 * and the books kept off are this account's.
 */
@Entity(tableName = "hardcover_links_owner")
data class HardcoverLinksOwner(
    @PrimaryKey val id: Long = HardcoverAccount.SINGLE_ID,
    @ColumnInfo(name = "user_id") val userId: Long,
)

/**
 * A book with something new to tell Hardcover. One worker drains these
 * one at a time, so a burst of changes never becomes a burst of requests.
 * [queuedAt] lets a push that ran while the book changed again leave the
 * newer request in place.
 */
@Entity(tableName = "hardcover_pending")
data class HardcoverPending(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "queued_at") val queuedAt: Long,
)

/**
 * A book a library transaction is removing, or whose file it is giving to
 * another book. Written before that transaction commits, so a push never
 * uses the old match, even across a restart, until the removal settles.
 */
@Entity(tableName = "hardcover_forgetting")
data class HardcoverForgetting(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
)

@Dao
interface HardcoverDao {
    @Query("SELECT * FROM hardcover_account WHERE id = :id")
    fun observeAccount(id: Long = HardcoverAccount.SINGLE_ID): Flow<HardcoverAccount?>

    @Query("SELECT * FROM hardcover_account WHERE id = :id")
    suspend fun account(id: Long = HardcoverAccount.SINGLE_ID): HardcoverAccount?

    @Upsert
    suspend fun upsertAccount(account: HardcoverAccount)

    @Query("UPDATE hardcover_account SET auto_sync = :autoSync WHERE id = :id")
    suspend fun setAutoSync(autoSync: Boolean, id: Long = HardcoverAccount.SINGLE_ID)

    @Query("UPDATE hardcover_account SET token_refused = :refused WHERE id = :id")
    suspend fun setTokenRefused(refused: Boolean, id: Long = HardcoverAccount.SINGLE_ID)

    @Query("DELETE FROM hardcover_account WHERE id = :id")
    suspend fun deleteAccount(id: Long = HardcoverAccount.SINGLE_ID)

    @Query("SELECT * FROM hardcover_book WHERE book_url = :bookUrl")
    fun observeLink(bookUrl: String): Flow<HardcoverBookLink?>

    @Query("SELECT * FROM hardcover_book WHERE book_url = :bookUrl")
    suspend fun link(bookUrl: String): HardcoverBookLink?

    @Upsert
    suspend fun upsertLink(link: HardcoverBookLink)

    @Query("DELETE FROM hardcover_book WHERE book_url IN (:bookUrls)")
    suspend fun forgetLinks(bookUrls: List<String>)

    @Query(
        "UPDATE hardcover_book SET pushed_at = :at, pushed_rating = :rating, dates_pending = 0, " +
            "edition_chosen = 0 " +
            "WHERE book_url = :bookUrl",
    )
    suspend fun setPushed(bookUrl: String, at: Long, rating: Double?)

    @Query("UPDATE hardcover_book SET pushed_rating = :rating WHERE book_url = :bookUrl")
    suspend fun setPushedRating(bookUrl: String, rating: Double?)

    @Query("UPDATE hardcover_book SET dates_pending = 1 WHERE book_url = :bookUrl")
    suspend fun markDatesPending(bookUrl: String)

    @Query(
        "UPDATE hardcover_book SET imported_cover_url = :url, imported_cover_sha256 = :sha256 " +
            "WHERE book_url = :bookUrl",
    )
    suspend fun setImportedCover(bookUrl: String, url: String, sha256: String)

    @Query("SELECT user_id FROM hardcover_links_owner WHERE id = :id")
    suspend fun linksOwner(id: Long = HardcoverAccount.SINGLE_ID): Long?

    @Upsert
    suspend fun setLinksOwner(owner: HardcoverLinksOwner)

    @Query("DELETE FROM hardcover_book")
    suspend fun deleteLinks()

    @Query("DELETE FROM hardcover_links_owner")
    suspend fun deleteLinksOwner()

    @Upsert
    suspend fun queue(pending: HardcoverPending)

    @Query("SELECT * FROM hardcover_pending ORDER BY queued_at LIMIT 1")
    suspend fun nextPending(): HardcoverPending?

    @Query("DELETE FROM hardcover_pending WHERE book_url = :bookUrl AND queued_at = :queuedAt")
    suspend fun settle(bookUrl: String, queuedAt: Long)

    @Query("DELETE FROM hardcover_pending WHERE book_url IN (:bookUrls)")
    suspend fun forgetPending(bookUrls: List<String>)

    @Query("DELETE FROM hardcover_pending")
    suspend fun clearPending()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markForgetting(rows: List<HardcoverForgetting>)

    @Query("DELETE FROM hardcover_forgetting WHERE book_url IN (:bookUrls)")
    suspend fun unmarkForgetting(bookUrls: List<String>)

    @Query("SELECT EXISTS(SELECT 1 FROM hardcover_forgetting WHERE book_url = :bookUrl)")
    suspend fun isForgetting(bookUrl: String): Boolean

    @Query("SELECT book_url FROM hardcover_forgetting")
    suspend fun forgetting(): List<String>
}

/**
 * Hardcover's own database, apart from `liseur.db` because only the Play
 * build has Hardcover: the F-Droid build's schema never sees these tables.
 */
@Database(
    entities = [
        HardcoverAccount::class,
        HardcoverBookLink::class,
        HardcoverLinksOwner::class,
        HardcoverPending::class,
        HardcoverForgetting::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class HardcoverDatabase : RoomDatabase() {
    abstract fun hardcover(): HardcoverDao

    companion object {
        const val NAME = "hardcover.db"

        fun open(context: Context): HardcoverDatabase =
            Room.databaseBuilder(context, HardcoverDatabase::class.java, NAME).build()
    }
}
