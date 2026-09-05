package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
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
 *
 * [seeded] records that this book, under this name, has been asked
 * where it stands once. Everything the server heard before a book had
 * a usable name here sits behind the cursor, where the ordinary delta
 * pull will never mention it again; the one direct question is how
 * that reading is recovered, and it must survive the run that asked
 * it, or a book confirmed today would wait for the other device to
 * happen to push again.
 */
@Entity(tableName = "work_alias", primaryKeys = ["book_url", "peer_id"])
data class WorkAlias(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "work_id") val workId: String,
    /** The strongest identifier the alias rests on, for explaining it. */
    @ColumnInfo(name = "confidence") val confidence: String,
    @ColumnInfo(name = "confirmed") val confirmed: Boolean = false,
    /** Whether the server has been asked, once, where this book stands. */
    @ColumnInfo(name = "seeded", defaultValue = "0") val seeded: Boolean = false,
    /**
     * Whether the catalog's own id for this book has been registered.
     *
     * Aliases resolved before the `source` identifier existed never
     * told the server which catalog entry the book is, which is the
     * one identifier another device holds before downloading anything.
     * Each such alias re-resolves once to hand it over.
     */
    @ColumnInfo(name = "source_sent", defaultValue = "0") val sourceSent: Boolean = false,
    /** The file this alias was resolved for; null for file-less books. */
    @ColumnInfo(name = "edition_sha") val editionSha: String? = null,
    /**
     * When this work's annotations were last reconciled against the
     * server's live set, as epoch millis; 0 means never.
     *
     * A timestamp rather than a flag because reconciling is not a
     * one-off. The server sweeps a deleted annotation's tombstone after
     * six months, and a device away longer than that would never hear of
     * the deletion from the delta feed — the live set is the only thing
     * that can still tell it. It is also how an annotation made against
     * a book this device downloaded later arrives at all: everything
     * said before the book had a name here sits behind the cursor.
     */
    @ColumnInfo(name = "annotations_reconciled_at", defaultValue = "0")
    val annotationsReconciledAt: Long = 0,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long,
) {
    /** Whether reading may be exchanged under this name yet. */
    val usable: Boolean get() = confirmed || confidence == HIGH

    /** Whether the reader has been asked about this and not yet answered. */
    val awaitingAnswer: Boolean get() = confidence == LOW && !confirmed

    companion object {
        const val HIGH = "high"
        const val LOW = "low"

        /**
         * The reader said these were not the same book.
         *
         * Kept rather than deleted, and kept as a confidence rather than
         * a flag, so that the next run finds an answer here instead of
         * resolving again and asking the same question a second time.
         */
        const val REJECTED = "rejected"
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

    /** Matches the server only guessed at, waiting on the reader. */
    @Query(
        """
        SELECT * FROM work_alias
        WHERE peer_id = :peerId AND confidence = 'low' AND confirmed = 0
        ORDER BY resolved_at
        """,
    )
    fun observeAwaitingAnswer(peerId: String): Flow<List<WorkAlias>>

    /**
     * Records that these were not the same book after all.
     *
     * The row stays: a deleted alias is resolved again on the next run,
     * and the reader is asked the same question for the rest of time.
     */
    @Query(
        """
        UPDATE work_alias SET confidence = 'rejected', confirmed = 0
        WHERE book_url = :bookUrl AND peer_id = :peerId
        """,
    )
    suspend fun reject(bookUrl: String, peerId: String)

    @Query("SELECT COUNT(*) FROM work_ambiguity WHERE peer_id = :peerId")
    fun observeAmbiguityCount(peerId: String): Flow<Int>

    @Query("SELECT * FROM work_alias WHERE peer_id = :peerId")
    suspend fun aliasesFor(peerId: String): List<WorkAlias>

    @Query("SELECT * FROM work_alias")
    fun observeAliases(): Flow<List<WorkAlias>>

    @Upsert
    suspend fun upsert(alias: WorkAlias)

    /**
     * Forgets one cached name, but only while it still is that name.
     *
     * The work id guard is what makes a delayed answer safe: a recovery
     * that already refreshed the alias must not be undone by a slower
     * one deleting whatever now sits under the key.
     */
    @Query(
        """
        DELETE FROM work_alias
        WHERE book_url = :bookUrl AND peer_id = :peerId AND work_id = :staleWorkId
        """,
    )
    suspend fun deleteAliasIfStale(bookUrl: String, peerId: String, staleWorkId: String)

    @Query("UPDATE work_alias SET confirmed = 1 WHERE book_url = :bookUrl AND peer_id = :peerId")
    suspend fun confirm(bookUrl: String, peerId: String)

    @Query("UPDATE work_alias SET seeded = 1 WHERE book_url = :bookUrl AND peer_id = :peerId")
    suspend fun markSeeded(bookUrl: String, peerId: String)

    @Query(
        """
        UPDATE work_alias SET annotations_reconciled_at = :at
        WHERE book_url = :bookUrl AND peer_id = :peerId
        """,
    )
    suspend fun markAnnotationsReconciled(bookUrl: String, peerId: String, at: Long)

    /**
     * Forgets when annotations were reconciled, so the next run asks
     * again. Used when an account is retired: what one server confirmed
     * says nothing about the next.
     */
    @Query("UPDATE work_alias SET annotations_reconciled_at = 0 WHERE peer_id = :peerId")
    suspend fun forgetAnnotationReconciliation(peerId: String)

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

    /** How much a sync server has been persuaded to call one book, settled or not. */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM work_alias WHERE book_url = :bookUrl) +
               (SELECT COUNT(*) FROM work_ambiguity WHERE book_url = :bookUrl)
        """,
    )
    suspend fun namingCountForBook(bookUrl: String): Int

    @Query("DELETE FROM work_ambiguity WHERE book_url IN (:bookUrls)")
    suspend fun forgetAmbiguities(bookUrls: List<String>)

    /** Drops everything a peer had named, when that peer goes away. */
    @Query("DELETE FROM work_alias WHERE peer_id = :peerId")
    suspend fun forgetPeerAliases(peerId: String)

    @Query("DELETE FROM work_ambiguity WHERE peer_id = :peerId")
    suspend fun forgetPeerAmbiguities(peerId: String)

    /** How many names and open questions one peer holds. */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM work_alias WHERE peer_id = :peerId) +
               (SELECT COUNT(*) FROM work_ambiguity WHERE peer_id = :peerId)
        """,
    )
    suspend fun countForPeer(peerId: String): Int

    @Query("UPDATE work_alias SET peer_id = :to WHERE peer_id = :from")
    suspend fun rekeyAliases(from: String, to: String)

    @Query("UPDATE work_ambiguity SET peer_id = :to WHERE peer_id = :from")
    suspend fun rekeyAmbiguities(from: String, to: String)

    /**
     * Moves everything one peer had named under a new spelling of the
     * same peer. Only ever called when nothing sits under [to] yet.
     */
    @Transaction
    suspend fun rekeyPeer(from: String, to: String) {
        rekeyAliases(from, to)
        rekeyAmbiguities(from, to)
    }
}
