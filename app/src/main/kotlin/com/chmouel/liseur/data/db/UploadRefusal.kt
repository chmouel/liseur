package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A server saying it will not take this book, written down.
 *
 * Without this row the app forgets: an upload that fails permanently
 * leaves nothing behind, the prompt is rebuilt from "books that live
 * only on this device" on the next launch, and the reader is asked to
 * send the same book again — and again, and again, with no way to find
 * out why it never arrives. That is the bug this table exists for.
 *
 * Per account, because a refusal is one server's opinion. The same book
 * may be perfectly acceptable to the next one, and a reader who switches
 * accounts should be offered it there.
 *
 * Against the bytes, because it is the bytes that were refused. A
 * refusal suppresses the offer only while [contentSha256] still matches
 * what the file hashes to now, so replacing a book with a better copy
 * makes the app offer it again without anything having to remember to
 * clear this. [contentSha256] is null for a [FILE_UNREADABLE], which
 * never got as far as having bytes to hash and so suppresses nothing —
 * it is here to be shown, and to be cleared.
 */
@Entity(
    tableName = "upload_refusal",
    primaryKeys = ["book_url", "account_key"],
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["url"],
            childColumns = ["book_url"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["account_key", "seen_at"])],
)
data class UploadRefusal(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "refused_at") val refusedAt: Long,
    /** [SERVER_REFUSED], [TOO_LARGE] or [FILE_UNREADABLE]. */
    val kind: String,
    /** What the server said, already bounded and sanitised, or null. */
    val reason: String?,
    /** The digest of the bytes that were sent; null when none were. */
    @ColumnInfo(name = "content_sha256") val contentSha256: String?,
    /** When the reader was told; null until they have been. */
    @ColumnInfo(name = "seen_at") val seenAt: Long? = null,
) {
    companion object {
        /** The server read the file and would not have it. */
        const val SERVER_REFUSED = "server_refused"

        /** The book is bigger than the server takes. */
        const val TOO_LARGE = "too_large"

        /**
         * This device could not read the file to send it. Not the
         * server's verdict at all, and kept separate for that reason: a
         * folder that comes back tomorrow makes the book sendable again,
         * and nothing about the book was ever wrong.
         */
        const val FILE_UNREADABLE = "file_unreadable"
    }
}

@Dao
interface UploadRefusalDao {

    /**
     * The books this account will not take, and the bytes it refused.
     *
     * Joined against the fingerprint so the caller can compare without a
     * second query per book: a refusal whose digest no longer matches
     * the file is spent, and the book is offered again.
     */
    @Query(
        """
        SELECT r.book_url AS bookUrl, r.kind AS kind, r.reason AS reason,
               r.content_sha256 AS refusedSha256, f.sha256 AS currentSha256
        FROM upload_refusal r
        LEFT JOIN book_fingerprint f ON f.book_url = r.book_url
        WHERE r.account_key = :accountKey
        """,
    )
    fun observeFor(accountKey: String): Flow<List<RefusedBytes>>

    @Query("SELECT * FROM upload_refusal WHERE book_url = :bookUrl AND account_key = :accountKey")
    suspend fun get(bookUrl: String, accountKey: String): UploadRefusal?

    /** The refusals this account has not told the reader about yet. */
    @Query(
        """
        SELECT * FROM upload_refusal
        WHERE account_key = :accountKey AND seen_at IS NULL
        ORDER BY refused_at
        """,
    )
    fun observeUnseen(accountKey: String): Flow<List<UploadRefusal>>

    @Upsert
    suspend fun upsert(refusal: UploadRefusal)

    /**
     * Marks exactly the refusal that was shown.
     *
     * Not just the book and the account: a second, different refusal can
     * land while the first is still on screen, and stamping by key alone
     * would mark that one read without anybody having seen it.
     */
    @Query(
        """
        UPDATE upload_refusal SET seen_at = :seenAt
        WHERE book_url = :bookUrl AND account_key = :accountKey
          AND refused_at = :refusedAt AND kind = :kind
        """,
    )
    suspend fun markSeen(
        bookUrl: String,
        accountKey: String,
        refusedAt: Long,
        kind: String,
        seenAt: Long,
    )

    @Query("DELETE FROM upload_refusal WHERE book_url = :bookUrl AND account_key = :accountKey")
    suspend fun clear(bookUrl: String, accountKey: String)

    @Query("DELETE FROM upload_refusal WHERE account_key = :accountKey")
    suspend fun clearAccount(accountKey: String)
}

/** One refusal and the digest the book hashes to now, for comparison. */
data class RefusedBytes(
    val bookUrl: String,
    /** [UploadRefusal.SERVER_REFUSED], [UploadRefusal.TOO_LARGE] or [UploadRefusal.FILE_UNREADABLE]. */
    val kind: String,
    val reason: String?,
    val refusedSha256: String?,
    val currentSha256: String?,
) {
    /**
     * Whether this refusal still describes the file on disk.
     *
     * A digest neither side has is not a match: a book whose bytes are
     * unknown is offered rather than silently withheld, because
     * re-offering a book is a much cheaper mistake than hiding one the
     * reader could have sent.
     */
    val stillApplies: Boolean
        get() = refusedSha256 != null && refusedSha256 == currentSha256

    /**
     * Whether this is still worth telling the reader about.
     *
     * A spent refusal is history: the book is on offer again and saying
     * the server turned it down would be a lie. A [UploadRefusal.FILE_UNREADABLE]
     * has no digest to go spent, and stays until the book goes up or
     * something else happens to it — it is the only explanation the
     * reader will get for a *Send* that quietly did nothing.
     */
    val worthSaying: Boolean
        get() = stillApplies || kind == UploadRefusal.FILE_UNREADABLE
}
