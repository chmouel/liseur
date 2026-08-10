package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import com.chmouel.liseur.domain.BookFingerprint

/**
 * What a book's file hashed to, and which file that was.
 *
 * Kept out of `books` on purpose. This describes the bytes on disk
 * rather than the book, it is expensive to work out and cheap to work
 * out again, and the library scan rewrites whole `books` rows — a column
 * there would be silently dropped the next time a book was re-indexed.
 *
 * [fileModifiedAt] is what makes it safe to trust: it is the timestamp
 * the file had when it was hashed, so a file rewritten underneath us is
 * noticed and hashed again rather than being sent under the old name.
 */
@Entity(tableName = "book_fingerprint")
data class BookFingerprintRow(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "book_url") val bookUrl: String,
    val sha256: String,
    @ColumnInfo(name = "partial_md5") val partialMd5: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "file_modified_at") val fileModifiedAt: Long?,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
) {
    val fingerprint: BookFingerprint
        get() = BookFingerprint(sha256 = sha256, partialMd5 = partialMd5, size = fileSize)
}

/**
 * The name a sync server knows a book by.
 *
 * Per peer, because a work id is the server's own identifier and means
 * nothing on another one, and because the same book can perfectly well
 * be known to two servers under two ids.
 *
 * [confirmed] is the reader's answer to a doubtful match. The server
 * says `confidence: low` when all it had to go on was a title and an
 * author, which is a real guess: two editions of a classic, or two
 * translations, will match each other that way. Reading state is not
 * merged across a low-confidence match until somebody says it is the
 * same book.
 */
@Entity(tableName = "work_alias", primaryKeys = ["book_url", "peer_id"])
data class WorkAlias(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "work_id") val workId: String,
    /** The strongest identifier the alias rests on, for explaining it. */
    @ColumnInfo(name = "confidence") val confidence: String,
    @ColumnInfo(name = "confirmed") val confirmed: Boolean = false,
    /** The file this alias was resolved for; null for file-less books. */
    @ColumnInfo(name = "edition_sha") val editionSha: String? = null,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long,
) {
    /** Whether reading may be exchanged under this name yet. */
    val usable: Boolean get() = confidence != LOW || confirmed

    companion object {
        const val HIGH = "high"
        const val LOW = "low"
    }
}

/**
 * A book whose identifiers the server could not tell apart from another
 * book's.
 *
 * The server changes nothing when this happens and hands back the works
 * it was torn between. Merging them is destructive and irreversible from
 * the phone's point of view, so the row waits here until the reader
 * says which it is.
 */
@Entity(tableName = "work_ambiguity", primaryKeys = ["book_url", "peer_id"])
data class WorkAmbiguity(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "peer_id") val peerId: String,
    /** The candidate work ids, newline-separated in the server's order. */
    @ColumnInfo(name = "work_ids") val workIds: String,
    @ColumnInfo(name = "noticed_at") val noticedAt: Long,
) {
    val candidates: List<String> get() = workIds.split('\n').filter { it.isNotEmpty() }
}

@Dao
interface WorkIdentityDao {

    @Query("SELECT * FROM book_fingerprint WHERE book_url = :bookUrl")
    suspend fun fingerprint(bookUrl: String): BookFingerprintRow?

    @Upsert
    suspend fun upsert(row: BookFingerprintRow)

    @Query("SELECT * FROM work_alias WHERE book_url = :bookUrl AND peer_id = :peerId")
    suspend fun alias(bookUrl: String, peerId: String): WorkAlias?

    @Query("SELECT * FROM work_alias WHERE peer_id = :peerId")
    suspend fun aliasesFor(peerId: String): List<WorkAlias>

    @Upsert
    suspend fun upsert(alias: WorkAlias)

    @Query("UPDATE work_alias SET confirmed = 1 WHERE book_url = :bookUrl AND peer_id = :peerId")
    suspend fun confirm(bookUrl: String, peerId: String)

    @Upsert
    suspend fun upsert(ambiguity: WorkAmbiguity)

    @Query("SELECT * FROM work_ambiguity WHERE peer_id = :peerId")
    suspend fun ambiguitiesFor(peerId: String): List<WorkAmbiguity>

    @Query("SELECT COUNT(*) FROM work_ambiguity WHERE peer_id = :peerId")
    suspend fun ambiguityCount(peerId: String): Int

    @Query("DELETE FROM work_ambiguity WHERE book_url = :bookUrl AND peer_id = :peerId")
    suspend fun clearAmbiguity(bookUrl: String, peerId: String)

    /**
     * Forgets everything about a book that has left the library.
     *
     * The fingerprint goes with it: it describes a file that is no
     * longer there, and keeping it would have the same path hashed under
     * a stale name if something else took it over.
     */
    @Query("DELETE FROM book_fingerprint WHERE book_url IN (:bookUrls)")
    suspend fun forgetFingerprints(bookUrls: List<String>)

    @Query("DELETE FROM work_alias WHERE book_url IN (:bookUrls)")
    suspend fun forgetAliases(bookUrls: List<String>)

    @Query("DELETE FROM work_ambiguity WHERE book_url IN (:bookUrls)")
    suspend fun forgetAmbiguities(bookUrls: List<String>)

    /** Drops everything a peer had named, when that peer goes away. */
    @Query("DELETE FROM work_alias WHERE peer_id = :peerId")
    suspend fun forgetPeerAliases(peerId: String)

    @Query("DELETE FROM work_ambiguity WHERE peer_id = :peerId")
    suspend fun forgetPeerAmbiguities(peerId: String)
}
