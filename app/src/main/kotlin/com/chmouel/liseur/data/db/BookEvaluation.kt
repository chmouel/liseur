package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.chmouel.liseur.domain.PersonalEvaluation
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "book_evaluation", primaryKeys = ["book_url", "field"])
data class BookEvaluation(
    @ColumnInfo(name = "book_url") val bookUrl: String,
    val field: String,
    val value: String?,
    val title: String,
    val author: String,
    val revision: Long = 0,
    @ColumnInfo(name = "retired_revision") val retiredRevision: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    fun owed(state: EvaluationSync?): Boolean = revision > maxOf(retiredRevision, state?.ackedRevision ?: 0)
}

@Entity(tableName = "evaluation_sync", primaryKeys = ["peer_id", "book_url", "field"])
data class EvaluationSync(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "book_url") val bookUrl: String,
    val field: String,
    @ColumnInfo(name = "work_id") val workId: String,
    @ColumnInfo(name = "acked_revision") val ackedRevision: Long = 0,
    @ColumnInfo(name = "sent_json") val sentJson: String? = null,
    @ColumnInfo(name = "sent_revision") val sentRevision: Long? = null,
)

@Entity(tableName = "retained_evaluation")
data class RetainedEvaluation(
    @androidx.room.PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "original_url") val originalUrl: String,
    val identifiers: String,
)

@Dao
interface BookEvaluationDao {
    @Query("SELECT * FROM book_evaluation WHERE book_url = :bookUrl ORDER BY field")
    fun observe(bookUrl: String): Flow<List<BookEvaluation>>

    @Query("SELECT * FROM book_evaluation WHERE book_url = :bookUrl AND field = :field")
    suspend fun get(bookUrl: String, field: String): BookEvaluation?

    @Query("SELECT COUNT(*) FROM book_evaluation WHERE book_url = :bookUrl")
    suspend fun countForBook(bookUrl: String): Int

    @Query("SELECT book_url FROM book_evaluation WHERE field = 'rating' AND value IS NOT NULL")
    fun observeRatedBooks(): Flow<List<String>>

    @Query("SELECT * FROM book_evaluation ORDER BY book_url, field")
    suspend fun all(): List<BookEvaluation>

    @Upsert
    suspend fun upsert(row: BookEvaluation)

    @Query("SELECT * FROM evaluation_sync WHERE peer_id = :peerId")
    suspend fun states(peerId: String): List<EvaluationSync>

    @Query("SELECT * FROM evaluation_sync WHERE peer_id = :peerId AND book_url = :bookUrl AND field = :field")
    suspend fun state(peerId: String, bookUrl: String, field: String): EvaluationSync?

    @Upsert
    suspend fun upsertState(row: EvaluationSync)

    @Query("UPDATE evaluation_sync SET work_id = '' WHERE peer_id = :peerId AND book_url = :bookUrl AND work_id = :workId")
    suspend fun invalidateWork(peerId: String, bookUrl: String, workId: String)

    @Query("SELECT * FROM retained_evaluation ORDER BY book_url")
    suspend fun retained(): List<RetainedEvaluation>

    @Query("SELECT * FROM retained_evaluation WHERE book_url = :bookUrl")
    suspend fun retained(bookUrl: String): RetainedEvaluation?

    @Upsert
    suspend fun upsertRetained(row: RetainedEvaluation)

    @Query("SELECT COUNT(*) + 1 FROM retained_evaluation")
    suspend fun nextRetainedId(): Long

    @Query("SELECT COUNT(*) FROM evaluation_sync WHERE peer_id = :peerId")
    suspend fun countForPeer(peerId: String): Int

    @Query("UPDATE evaluation_sync SET peer_id = :to WHERE peer_id = :from")
    suspend fun rekeyPeer(from: String, to: String)

    @Query("""
        INSERT OR IGNORE INTO evaluation_sync (peer_id, book_url, field, work_id, acked_revision, sent_json, sent_revision)
        SELECT a.peer_id, e.book_url, e.field, a.work_id, 0, NULL, NULL
        FROM book_evaluation e JOIN work_alias a ON a.book_url = e.book_url
        WHERE e.book_url IN (:bookUrls) AND (a.confirmed = 1 OR a.confidence = 'high')
    """)
    suspend fun retainIdentities(bookUrls: List<String>)

    @Query("UPDATE book_evaluation SET book_url = :retained WHERE book_url = :bookUrl")
    suspend fun retainValues(bookUrl: String, retained: String)

    @Query("UPDATE evaluation_sync SET book_url = :retained WHERE book_url = :bookUrl")
    suspend fun retainAgreements(bookUrl: String, retained: String)

    @Transaction
    suspend fun retain(bookUrl: String, identifiers: String) {
        val fields = listOfNotNull(get(bookUrl, PersonalEvaluation.RATING), get(bookUrl, PersonalEvaluation.REVIEW))
        if (fields.isEmpty()) return
        retainIdentities(listOf(bookUrl))
        val retained = "evaluation:" + java.util.UUID.nameUUIDFromBytes("$bookUrl|${nextRetainedId()}".toByteArray(Charsets.UTF_8))
        upsertRetained(RetainedEvaluation(retained, bookUrl, identifiers))
        retainValues(bookUrl, retained)
        retainAgreements(bookUrl, retained)
    }

    @Query("UPDATE book_evaluation SET retired_revision = revision")
    suspend fun retireEdits()

    @Query("DELETE FROM evaluation_sync WHERE peer_id = :peerId")
    suspend fun deletePeer(peerId: String)

    @Transaction
    suspend fun forgetPeer(peerId: String) {
        retireEdits()
        deletePeer(peerId)
    }

    // Retained rows keep the high-water mark when a path is reused
    @Query("SELECT COALESCE(MAX(revision), 0) + 1 FROM book_evaluation")
    suspend fun nextRevision(): Long

    @Transaction
    suspend fun edit(bookUrl: String, field: String, value: String?, title: String, author: String, now: Long) {
        require(PersonalEvaluation.valid(field, value))
        val current = get(bookUrl, field)
        if (current?.value == value && current != null) return
        upsert(BookEvaluation(bookUrl, field, value, title, author, nextRevision(), current?.retiredRevision ?: 0, now))
    }
}
