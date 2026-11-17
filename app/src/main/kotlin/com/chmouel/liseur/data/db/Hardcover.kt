package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.chmouel.liseur.data.calibre.CredentialCipher
import kotlinx.coroutines.flow.Flow

/**
 * The Hardcover account reading progress is reported to.
 *
 * A row of its own, like `kosync_peer`: Hardcover is a reading tracker
 * kept *alongside* whatever server the library comes from, and it is
 * just as useful with no server at all. Only one row ever exists
 * ([SINGLE_ID]). The token is sealed with the Keystore like every other
 * credential.
 */
@Entity(tableName = "hardcover_account")
data class HardcoverAccount(
    @PrimaryKey val id: Long = SINGLE_ID,
    /** Hardcover's own id for the user; library queries are filtered by it. */
    @ColumnInfo(name = "user_id") val userId: Long,
    val username: String,
    /** The Keystore-sealed API token. */
    @ColumnInfo(name = "token_cipher") val tokenCipher: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /** False when progress goes out only when the reader asks. */
    @ColumnInfo(name = "auto_sync", defaultValue = "1") val autoSync: Boolean = true,
) {
    /**
     * The token, or null when it cannot be read back: a database
     * restored onto another phone arrives with ciphertext this Keystore
     * cannot open.
     */
    @get:Ignore
    val token: String?
        get() = CredentialCipher.decrypt(tokenCipher)

    companion object {
        const val SINGLE_ID = 1L

        fun seal(token: String): String = CredentialCipher.encrypt(token)
    }
}

/**
 * Which Hardcover edition a book on this device is, and what was last
 * said about it.
 *
 * The match is chosen once and remembered: an ISBN names an edition
 * exactly, but a title search is a guess the reader confirmed, and
 * asking again on every push would be both slow and a chance to guess
 * differently.
 *
 * A row with [excluded] set and no edition is a book the reader has
 * kept off Hardcover before it was ever matched.
 */
@Entity(tableName = "hardcover_book")
data class HardcoverBookLink(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "hardcover_book_id") val hardcoverBookId: Long? = null,
    @ColumnInfo(name = "edition_id") val editionId: Long? = null,
    /** What to show for the match: the edition's title as Hardcover has it. */
    @ColumnInfo(name = "edition_title") val editionTitle: String? = null,
    /** The edition's page count, the bridge from a percentage to a page. */
    @ColumnInfo(name = "edition_pages") val editionPages: Int? = null,
    /** True when the reader keeps this book off Hardcover. */
    @ColumnInfo(name = "excluded", defaultValue = "0") val excluded: Boolean = false,
    /** When a push last went through, for display. */
    @ColumnInfo(name = "pushed_at") val pushedAt: Long? = null,
) {
    @get:Ignore
    val matched: Boolean get() = hardcoverBookId != null && editionId != null
}

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

    @Query("DELETE FROM hardcover_account WHERE id = :id")
    suspend fun deleteAccount(id: Long = HardcoverAccount.SINGLE_ID)

    @Query("SELECT * FROM hardcover_book WHERE book_url = :bookUrl")
    fun observeLink(bookUrl: String): Flow<HardcoverBookLink?>

    @Query("SELECT * FROM hardcover_book WHERE book_url = :bookUrl")
    suspend fun link(bookUrl: String): HardcoverBookLink?

    @Upsert
    suspend fun upsertLink(link: HardcoverBookLink)

    /**
     * Forgets the matches for books that are gone from this device, or
     * whose file was replaced: the URL may come back holding another book.
     */
    @Query("DELETE FROM hardcover_book WHERE book_url IN (:bookUrls)")
    suspend fun forgetBooks(bookUrls: List<String>)

    @Query("UPDATE hardcover_book SET pushed_at = :at WHERE book_url = :bookUrl")
    suspend fun setPushedAt(bookUrl: String, at: Long)

    /**
     * Forgets every match. They name rows in one person's Hardcover
     * library only loosely (book and edition ids are global), but the
     * reader's choices about what to keep off it belong to the account
     * that made them.
     */
    @Query("DELETE FROM hardcover_book")
    suspend fun deleteLinks()
}
