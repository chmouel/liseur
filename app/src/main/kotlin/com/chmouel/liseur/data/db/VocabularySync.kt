package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Per-peer sync state for one vocabulary word `(language, normalized)`.
 *
 * `lifecycle` tracks the four-state word lifecycle machine:
 * - [SYNCED]: normal live or tombstoned agreement.
 * - [LOCAL_DELETE_PENDING]: local word was deleted; push must delete on server.
 * - [DELETE_THEN_RESURRECT]: deleted then re-collected locally; push must retire
 *   the old generation first before resurrecting the new incarnation.
 * - [RESURRECT_PENDING]: delete was acknowledged while in [DELETE_THEN_RESURRECT];
 *   pull ignores tombstones with `generation <= this.generation` until the
 *   resurrection UPSERT is acknowledged.
 */
@Entity(
    tableName = "vocabulary_word_sync",
    primaryKeys = ["peer_id", "language", "normalized"],
)
data class VocabularyWordSync(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "language") val language: String,
    @ColumnInfo(name = "normalized") val normalized: String,
    @ColumnInfo(name = "rev", defaultValue = "0") val rev: Long = 0,
    @ColumnInfo(name = "generation", defaultValue = "0") val generation: Long = 0,
    @ColumnInfo(name = "seq", defaultValue = "0") val seq: Long = 0,
    @ColumnInfo(name = "synced_word_id") val syncedWordId: Long? = null,
    @ColumnInfo(name = "lifecycle", defaultValue = "'SYNCED'") val lifecycle: String = SYNCED,
    @ColumnInfo(name = "acked_fingerprint", defaultValue = "''") val ackedFingerprint: String = "",
    @ColumnInfo(name = "acked_base_json") val ackedBaseJson: String? = null,
    @ColumnInfo(name = "pending_json") val pendingJson: String? = null,
    @ColumnInfo(name = "pending_rev") val pendingRev: Long? = null,
    @ColumnInfo(name = "pending_fingerprint") val pendingFingerprint: String? = null,
    @ColumnInfo(name = "pending_base_json") val pendingBaseJson: String? = null,
    @ColumnInfo(name = "pending_intent") val pendingIntent: String? = null,
    @ColumnInfo(name = "pending_word_id") val pendingWordId: Long? = null,
    @ColumnInfo(name = "refused_fingerprint") val refusedFingerprint: String? = null,
    @ColumnInfo(name = "refused_reason") val refusedReason: String? = null,
) {
    companion object {
        const val SYNCED = "SYNCED"
        const val LOCAL_DELETE_PENDING = "LOCAL_DELETE_PENDING"
        const val DELETE_THEN_RESURRECT = "DELETE_THEN_RESURRECT"
        const val RESURRECT_PENDING = "RESURRECT_PENDING"

        const val INTENT_UPSERT = "UPSERT"
        const val INTENT_DELETE = "DELETE"

        const val FINGERPRINT_DELETED = "DELETED"
    }
}

/**
 * Per-peer sync state for one vocabulary review event (`review_id`).
 *
 * Reviews are immutable events bound to one `word_generation`. A row is never
 * retagged to another generation; an undo persists a delete request here.
 */
@Entity(
    tableName = "vocabulary_review_sync",
    primaryKeys = ["peer_id", "review_id"],
    indices = [
        Index(value = ["peer_id", "local_review_id"]),
        Index(value = ["peer_id", "language", "normalized"]),
    ],
)
data class VocabularyReviewSync(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "review_id") val reviewId: String,
    @ColumnInfo(name = "local_review_id") val localReviewId: Long? = null,
    @ColumnInfo(name = "language") val language: String,
    @ColumnInfo(name = "normalized") val normalized: String,
    @ColumnInfo(name = "word_generation", defaultValue = "0") val wordGeneration: Long = 0,
    @ColumnInfo(name = "seq", defaultValue = "0") val seq: Long = 0,
    @ColumnInfo(name = "acked_deleted", defaultValue = "0") val ackedDeleted: Boolean = false,
    @ColumnInfo(name = "pending_json") val pendingJson: String? = null,
    @ColumnInfo(name = "pending_deleted", defaultValue = "0") val pendingDeleted: Boolean = false,
    @ColumnInfo(name = "refused_reason") val refusedReason: String? = null,
)

/**
 * Live reviews pulled before their parent word arrived (e.g. across a page
 * boundary). Attached when the matching `(language, normalized, word_generation)`
 * word lands, or deleted on review/word tombstones.
 */
@Entity(
    tableName = "vocabulary_staged_review",
    primaryKeys = ["peer_id", "review_id"],
    indices = [Index(value = ["peer_id", "language", "normalized"])],
)
data class VocabularyStagedReview(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "review_id") val reviewId: String,
    @ColumnInfo(name = "language") val language: String,
    @ColumnInfo(name = "normalized") val normalized: String,
    @ColumnInfo(name = "word_generation") val wordGeneration: Long,
    @ColumnInfo(name = "rating") val rating: String,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long,
    @ColumnInfo(name = "interval_minutes") val intervalMinutes: Int,
    @ColumnInfo(name = "due_at") val dueAt: Long,
    @ColumnInfo(name = "seq") val seq: Long,
)

@Dao
interface VocabularySyncDao {
    // -- Word sync -------------------------------------------------------

    @Query("SELECT * FROM vocabulary_word_sync WHERE peer_id = :peerId")
    suspend fun allWords(peerId: String): List<VocabularyWordSync>

    @Query(
        "SELECT * FROM vocabulary_word_sync " +
            "WHERE peer_id = :peerId AND language = :language AND normalized = :normalized",
    )
    suspend fun word(peerId: String, language: String, normalized: String): VocabularyWordSync?

    @Query(
        "SELECT * FROM vocabulary_word_sync " +
            "WHERE language = :language AND normalized = :normalized",
    )
    suspend fun wordsForKey(language: String, normalized: String): List<VocabularyWordSync>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWord(row: VocabularyWordSync)

    @Query(
        "DELETE FROM vocabulary_word_sync " +
            "WHERE peer_id = :peerId AND language = :language AND normalized = :normalized",
    )
    suspend fun deleteWord(peerId: String, language: String, normalized: String)

    // -- Review sync -----------------------------------------------------

    @Query("SELECT * FROM vocabulary_review_sync WHERE peer_id = :peerId")
    suspend fun allReviews(peerId: String): List<VocabularyReviewSync>

    @Query("SELECT * FROM vocabulary_review_sync WHERE peer_id = :peerId AND review_id = :reviewId")
    suspend fun review(peerId: String, reviewId: String): VocabularyReviewSync?

    @Query("SELECT * FROM vocabulary_review_sync WHERE review_id = :reviewId")
    suspend fun reviewsForId(reviewId: String): List<VocabularyReviewSync>

    @Query(
        "SELECT * FROM vocabulary_review_sync " +
            "WHERE peer_id = :peerId AND language = :language AND normalized = :normalized",
    )
    suspend fun reviewsForWord(
        peerId: String,
        language: String,
        normalized: String,
    ): List<VocabularyReviewSync>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReview(row: VocabularyReviewSync)

    @Query("DELETE FROM vocabulary_review_sync WHERE peer_id = :peerId AND review_id = :reviewId")
    suspend fun deleteReview(peerId: String, reviewId: String)

    // -- Staged reviews --------------------------------------------------

    @Query(
        "SELECT * FROM vocabulary_staged_review " +
            "WHERE peer_id = :peerId AND language = :language AND normalized = :normalized " +
            "AND word_generation = :generation",
    )
    suspend fun stagedForWord(
        peerId: String,
        language: String,
        normalized: String,
        generation: Long,
    ): List<VocabularyStagedReview>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStaged(row: VocabularyStagedReview)

    @Query("DELETE FROM vocabulary_staged_review WHERE peer_id = :peerId AND review_id = :reviewId")
    suspend fun deleteStaged(peerId: String, reviewId: String)

    @Query(
        "DELETE FROM vocabulary_staged_review " +
            "WHERE peer_id = :peerId AND language = :language AND normalized = :normalized " +
            "AND word_generation <= :maxGeneration",
    )
    suspend fun deleteStagedUpToGeneration(
        peerId: String,
        language: String,
        normalized: String,
        maxGeneration: Long,
    )

    // -- Peer account management (RemoteAccountRepository) ---------------

    @Query(
        "SELECT (SELECT COUNT(*) FROM vocabulary_word_sync WHERE peer_id = :peerId) + " +
            "(SELECT COUNT(*) FROM vocabulary_review_sync WHERE peer_id = :peerId) + " +
            "(SELECT COUNT(*) FROM vocabulary_staged_review WHERE peer_id = :peerId)",
    )
    suspend fun countForPeer(peerId: String): Int

    @Query("UPDATE vocabulary_word_sync SET peer_id = :to WHERE peer_id = :from")
    suspend fun rekeyWordSync(from: String, to: String)

    @Query("UPDATE vocabulary_review_sync SET peer_id = :to WHERE peer_id = :from")
    suspend fun rekeyReviewSync(from: String, to: String)

    @Query("UPDATE vocabulary_staged_review SET peer_id = :to WHERE peer_id = :from")
    suspend fun rekeyStagedReviews(from: String, to: String)

    suspend fun rekeyPeer(from: String, to: String) {
        rekeyWordSync(from, to)
        rekeyReviewSync(from, to)
        rekeyStagedReviews(from, to)
    }

    @Query("DELETE FROM vocabulary_word_sync WHERE peer_id = :peerId")
    suspend fun forgetWordSync(peerId: String)

    @Query("DELETE FROM vocabulary_review_sync WHERE peer_id = :peerId")
    suspend fun forgetReviewSync(peerId: String)

    @Query("DELETE FROM vocabulary_staged_review WHERE peer_id = :peerId")
    suspend fun forgetStagedReviews(peerId: String)

    suspend fun forgetPeer(peerId: String) {
        forgetWordSync(peerId)
        forgetReviewSync(peerId)
        forgetStagedReviews(peerId)
    }
}
