package com.chmouel.liseur.vocabulary

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.chmouel.liseur.domain.VocabularyMerge
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "vocabulary_words",
    indices = [Index(value = ["normalized", "language", "ownerAccount"], unique = true), Index("dueAt")],
)
data class VocabularyWord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val normalized: String,
    val language: String,
    val definition: String?,
    val translationLanguage: String?,
    val notes: String = "",
    val addedAt: Long,
    val dueAt: Long,
    val reviewedAt: Long? = null,
    val streak: Int = 0,
    val intervalMinutes: Int = 0,
    val repetitions: Int = 0,
    val definitionEdited: Boolean = false,
    val searchText: String = normalizeVocabularySearch("$word\n${definition.orEmpty()}\n$notes"),
    @ColumnInfo(defaultValue = "") val ownerAccount: String = "",
    @ColumnInfo(defaultValue = "0") val definitionUpdatedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val notesUpdatedAt: Long = 0,
    val baselineDueAt: Long? = null,
    val baselineReviewedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val baselineStreak: Int = 0,
    @ColumnInfo(defaultValue = "0") val baselineIntervalMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val baselineRepetitions: Int = 0,
) {
    internal fun withSearchText(): VocabularyWord =
        copy(searchText = normalizeVocabularySearch("$word\n${definition.orEmpty()}\n$notes"))

    internal fun baselineOrNull(): VocabularyMerge.Baseline? =
        baselineDueAt?.takeIf { baselineRepetitions > 0 }?.let { due ->
            VocabularyMerge.Baseline(
                dueAt = due,
                reviewedAt = baselineReviewedAt,
                streak = baselineStreak,
                intervalMinutes = baselineIntervalMinutes,
                repetitions = baselineRepetitions,
            )
        }
}

@Entity(
    tableName = "vocabulary_occurrences",
    foreignKeys = [ForeignKey(
        entity = VocabularyWord::class,
        parentColumns = ["id"],
        childColumns = ["wordId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["wordId", "occurrenceKey"], unique = true), Index("bookUrl")],
)
data class VocabularyOccurrence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long = 0,
    val bookUrl: String,
    val bookTitle: String,
    val locationKey: String,
    val locator: String?,
    val sentence: String?,
    val lastSeenAt: Long,
    val encounters: Int = 1,
    val searchSentence: String = normalizeVocabularySearch(sentence.orEmpty()),
    val workId: String? = null,
    @ColumnInfo(defaultValue = "") val occurrenceKey: String =
        VocabularyMerge.provisionalOccurrenceKey(bookUrl, bookTitle, locationKey),
    @ColumnInfo(defaultValue = "0") val sentenceEdited: Boolean = false,
    @ColumnInfo(defaultValue = "0") val sentenceUpdatedAt: Long = 0,
)

@Entity(
    tableName = "vocabulary_reviews",
    foreignKeys = [ForeignKey(
        entity = VocabularyWord::class,
        parentColumns = ["id"],
        childColumns = ["wordId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("wordId"),
        Index("reviewedAt"),
        Index(value = ["wordId", "syncId"], unique = true),
        Index("syncId"),
    ],
)
data class VocabularyReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val rating: String,
    val reviewedAt: Long,
    val intervalMinutes: Int,
    val dueAt: Long,
    val firstReview: Boolean,
    val syncId: String? = null,
)

data class VocabularyReviewActivity(val reviewedAt: Long, val rating: String)

data class VocabularyStats(val total: Int, val newWords: Int, val learned: Int)
data class VocabularyDailyReviews(val total: Int, val newWords: Int)
data class VocabularyDueCard(@Embedded val word: VocabularyWord, val dueCount: Int)

data class VocabularyListItem(
    val wordId: Long,
    val word: String,
    val language: String,
    val repetitions: Int,
    val streak: Int,
    val encounters: Long,
    val groupUrl: String?,
    val groupTitle: String?,
    val definition: String?,
)

data class VocabularyBook(val bookUrl: String, val bookTitle: String)

@Dao
interface VocabularyDao {
    @Query(
        """
        SELECT * FROM vocabulary_words w
        WHERE (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
        ORDER BY w.id
        """,
    )
    suspend fun exportWords(): List<VocabularyWord>

    @Query(
        """
        SELECT o.* FROM vocabulary_occurrences o
        JOIN vocabulary_words w ON w.id = o.wordId
        WHERE (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
        ORDER BY o.id
        """,
    )
    suspend fun exportOccurrences(): List<VocabularyOccurrence>

    @Query(
        """
        SELECT r.* FROM vocabulary_reviews r
        JOIN vocabulary_words w ON w.id = r.wordId
        WHERE (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
        ORDER BY r.id
        """,
    )
    suspend fun exportReviews(): List<VocabularyReview>

    @Query("SELECT * FROM vocabulary_reviews WHERE wordId = :id")
    suspend fun reviews(id: Long): List<VocabularyReview>

    @Query(
        "SELECT r.* FROM vocabulary_reviews r " +
            "JOIN vocabulary_words w ON w.id = r.wordId " +
            "WHERE w.ownerAccount = :ownerAccount AND r.syncId IS NULL",
    )
    suspend fun reviewsWithoutSyncId(ownerAccount: String): List<VocabularyReview>

    @Update
    suspend fun updateOccurrence(occurrence: VocabularyOccurrence)

    @Query(
        """
        SELECT * FROM vocabulary_words w
        WHERE w.normalized = :normalized AND w.language = :language
          AND (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
        ORDER BY (w.ownerAccount = '') ASC, w.id DESC LIMIT 1
        """,
    )
    fun observeWord(normalized: String, language: String): Flow<VocabularyWord?>

    @Query(
        """
        SELECT * FROM vocabulary_words w
        WHERE w.normalized = :normalized AND w.language = :language
          AND (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
        ORDER BY (w.ownerAccount = '') ASC, w.id DESC LIMIT 1
        """,
    )
    suspend fun findWord(normalized: String, language: String): VocabularyWord?

    @Query(
        "SELECT * FROM vocabulary_words " +
            "WHERE normalized = :normalized AND language = :language " +
            "ORDER BY (ownerAccount = '') ASC, id DESC",
    )
    suspend fun findAllWordsByKey(normalized: String, language: String): List<VocabularyWord>

    @Query(
        "SELECT * FROM vocabulary_words " +
            "WHERE normalized = :normalized AND language = :language AND ownerAccount = :ownerAccount",
    )
    suspend fun findWordForOwner(normalized: String, language: String, ownerAccount: String): VocabularyWord?

    @Query("SELECT * FROM vocabulary_words WHERE ownerAccount = :ownerAccount ORDER BY id")
    suspend fun wordsForOwner(ownerAccount: String): List<VocabularyWord>

    @Query("SELECT * FROM vocabulary_words WHERE ownerAccount = '' ORDER BY id")
    suspend fun unownedWords(): List<VocabularyWord>

    @Query("SELECT count(*) FROM vocabulary_words WHERE ownerAccount = :ownerAccount")
    suspend fun countForOwner(ownerAccount: String): Int

    @Query("UPDATE vocabulary_words SET ownerAccount = :to WHERE ownerAccount = :from")
    suspend fun rekeyAccount(from: String, to: String)

    @Query(
        """
        SELECT * FROM vocabulary_words w
        WHERE w.id = :id
          AND (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
        """,
    )
    fun observeWord(id: Long): Flow<VocabularyWord?>

    @Query(
        """
        SELECT * FROM vocabulary_words w
        WHERE w.id = :id
          AND (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
        """,
    )
    suspend fun wordIfVisible(id: Long): VocabularyWord?

    @Query(
        """
        SELECT w.* FROM vocabulary_occurrences o
        JOIN vocabulary_words w ON w.id = o.wordId
        WHERE o.id = :occurrenceId
          AND (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
        """,
    )
    suspend fun occurrenceWordIfVisible(occurrenceId: Long): VocabularyWord?

    @Query("SELECT * FROM vocabulary_words WHERE id = :id")
    suspend fun word(id: Long): VocabularyWord?

    @Insert
    suspend fun insertWord(word: VocabularyWord): Long

    @Update
    suspend fun updateWord(word: VocabularyWord)

    @Query("DELETE FROM vocabulary_words WHERE id = :id")
    suspend fun deleteWord(id: Long)

    @Query("SELECT * FROM vocabulary_occurrences WHERE wordId = :id ORDER BY lastSeenAt DESC, id DESC")
    fun occurrences(id: Long): Flow<List<VocabularyOccurrence>>

    @Query("SELECT * FROM vocabulary_occurrences WHERE wordId = :id ORDER BY lastSeenAt DESC, id DESC")
    suspend fun occurrencesList(id: Long): List<VocabularyOccurrence>

    @Query("""
        SELECT * FROM vocabulary_occurrences WHERE wordId = :id
        ORDER BY (sentence IS NULL OR trim(sentence) = ''), lastSeenAt DESC, id DESC LIMIT 1
    """)
    suspend fun latestOccurrence(id: Long): VocabularyOccurrence?

    @Query("SELECT * FROM vocabulary_occurrences WHERE wordId = :id AND bookUrl = :bookUrl AND locationKey = :key LIMIT 1")
    suspend fun occurrence(id: Long, bookUrl: String, key: String): VocabularyOccurrence?

    @Query("SELECT * FROM vocabulary_occurrences WHERE id = :id")
    suspend fun occurrenceById(id: Long): VocabularyOccurrence?

    @Query("SELECT * FROM vocabulary_occurrences WHERE wordId = :id AND occurrenceKey = :occurrenceKey")
    suspend fun occurrenceByKey(id: Long, occurrenceKey: String): VocabularyOccurrence?

    @Query("DELETE FROM vocabulary_occurrences WHERE id = :id")
    suspend fun deleteOccurrence(id: Long)

    @Query("DELETE FROM vocabulary_occurrences WHERE wordId = :wordId")
    suspend fun deleteOccurrencesForWord(wordId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrence(occurrence: VocabularyOccurrence): Long

    @Query("""
        UPDATE vocabulary_occurrences
        SET encounters = encounters + 1, lastSeenAt = :now, bookTitle = :title,
            searchSentence = CASE WHEN sentence IS NULL AND sentenceEdited = 0 THEN :searchSentence ELSE searchSentence END,
            sentence = CASE WHEN sentenceEdited = 0 THEN coalesce(sentence, :sentence) ELSE sentence END
        WHERE id = :id
    """)
    suspend fun recordEncounter(id: Long, now: Long, title: String, sentence: String?, searchSentence: String)

    @Query(
        "UPDATE vocabulary_occurrences " +
            "SET sentence = :sentence, searchSentence = :searchSentence, " +
            "sentenceEdited = 1, sentenceUpdatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun editSentence(id: Long, sentence: String, searchSentence: String, updatedAt: Long = System.currentTimeMillis())

    @Query("""
        SELECT o.bookUrl, max(o.bookTitle) AS bookTitle FROM vocabulary_occurrences o
        JOIN vocabulary_words w ON w.id = o.wordId
        WHERE o.bookUrl != ''
          AND (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
        GROUP BY o.bookUrl ORDER BY bookTitle COLLATE NOCASE, o.bookUrl
    """)
    fun books(): Flow<List<VocabularyBook>>

    @Query("""
        SELECT count(*) AS total, count(CASE WHEN repetitions = 0 THEN 1 END) AS newWords,
            count(CASE WHEN streak >= 6 THEN 1 END) AS learned
        FROM vocabulary_words w
        WHERE (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
          AND (:book IS NULL OR EXISTS (
            SELECT 1 FROM vocabulary_occurrences o WHERE o.wordId = w.id AND o.bookUrl = :book
        ))
    """)
    fun statistics(book: String?): Flow<VocabularyStats>

    @Query("""
        SELECT count(*) AS total, count(CASE WHEN r.firstReview THEN 1 END) AS newWords
        FROM vocabulary_reviews r
        JOIN vocabulary_words w ON w.id = r.wordId
        WHERE r.reviewedAt >= :start AND r.reviewedAt <= :end
          AND (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
    """)
    suspend fun reviewsBetween(start: Long, end: Long): VocabularyDailyReviews

    @Query("""
        SELECT r.reviewedAt, r.rating FROM vocabulary_reviews r
        JOIN vocabulary_words w ON w.id = r.wordId
        WHERE r.reviewedAt >= :start
          AND (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
          AND (:book IS NULL OR EXISTS (
            SELECT 1 FROM vocabulary_occurrences o WHERE o.wordId = r.wordId AND o.bookUrl = :book
        ))
        ORDER BY r.reviewedAt
    """)
    fun reviewsSince(start: Long, book: String?): Flow<List<VocabularyReviewActivity>>

    @Insert
    suspend fun addReview(review: VocabularyReview): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviewIgnoringConflicts(review: VocabularyReview): Long

    @Update
    suspend fun updateReview(review: VocabularyReview)

    @Query("SELECT * FROM vocabulary_reviews WHERE id = :id")
    suspend fun review(id: Long): VocabularyReview?

    @Query("SELECT * FROM vocabulary_reviews WHERE syncId = :syncId LIMIT 1")
    suspend fun reviewBySyncIdAnyOwner(syncId: String): VocabularyReview?

    @Query("SELECT * FROM vocabulary_reviews WHERE syncId = :syncId")
    suspend fun reviewsBySyncIdAnyOwner(syncId: String): List<VocabularyReview>

    @Query(
        "SELECT r.* FROM vocabulary_reviews r " +
            "JOIN vocabulary_words w ON w.id = r.wordId " +
            "WHERE r.syncId = :syncId AND w.ownerAccount = :ownerAccount",
    )
    suspend fun reviewBySyncId(syncId: String, ownerAccount: String): VocabularyReview?

    @Query("DELETE FROM vocabulary_reviews WHERE id = :id")
    suspend fun deleteReview(id: Long)

    @Query("DELETE FROM vocabulary_reviews WHERE syncId = :syncId")
    suspend fun deleteReviewBySyncId(syncId: String)

    @Query("DELETE FROM vocabulary_reviews WHERE wordId = :wordId")
    suspend fun deleteReviewsForWord(wordId: Long)

    @Query("SELECT max(id) FROM vocabulary_reviews WHERE wordId = :wordId")
    suspend fun latestReviewId(wordId: Long): Long?

    @Query("""
        WITH eligible AS (
            SELECT w.id, w.repetitions, w.dueAt FROM vocabulary_words w
            WHERE w.dueAt <= :now
              AND (w.ownerAccount = ''
                   OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
                   OR w.ownerAccount = (
                       SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                       FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
                   ))
              AND (:book IS NULL OR EXISTS (
                SELECT 1 FROM vocabulary_occurrences o WHERE o.wordId = w.id AND o.bookUrl = :book
            ))
            AND (w.repetitions > 0 OR w.id IN (
                SELECT candidate.id FROM vocabulary_words candidate
                WHERE candidate.repetitions = 0 AND candidate.dueAt <= :now
                  AND (candidate.ownerAccount = ''
                       OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
                       OR candidate.ownerAccount = (
                           SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                           FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
                       ))
                  AND (:book IS NULL OR EXISTS (
                    SELECT 1 FROM vocabulary_occurrences o WHERE o.wordId = candidate.id AND o.bookUrl = :book
                ))
                ORDER BY candidate.addedAt, candidate.id LIMIT :newLimit
            ))
            ORDER BY (w.repetitions = 0), w.dueAt, w.id LIMIT :reviewLimit
        )
        SELECT w.*, (SELECT count(*) FROM eligible) AS dueCount
        FROM vocabulary_words w
        WHERE w.id = (SELECT id FROM eligible ORDER BY (repetitions = 0), dueAt, id LIMIT 1)
    """)
    fun dueCard(now: Long, book: String?, newLimit: Int, reviewLimit: Int): Flow<VocabularyDueCard?>

    @Query("""
        SELECT w.id AS wordId, w.word, w.language, w.repetitions, w.streak,
            substr(w.definition, 1, 180) AS definition,
            coalesce(sum(o.encounters), 0) AS encounters,
            CASE WHEN :grouped THEN o.bookUrl END AS groupUrl,
            CASE WHEN :grouped THEN max(o.bookTitle) END AS groupTitle
        FROM vocabulary_words w LEFT JOIN vocabulary_occurrences o ON o.wordId = w.id
        WHERE (w.ownerAccount = ''
               OR NOT EXISTS (SELECT 1 FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC')
               OR w.ownerAccount = (
                   SELECT 'liseursync|' || s.base_url || '|' || coalesce(s.liseur_account_id, s.account_id, s.username, 'null')
                   FROM remote_server s WHERE s.id = 1 AND s.kind = 'LISEUR_SYNC' LIMIT 1
               ))
          AND (:book IS NULL OR o.bookUrl = :book)
          AND (:search = '' OR instr(w.normalized, :search) > 0 OR instr(w.searchText, :search) > 0
               OR EXISTS (SELECT 1 FROM vocabulary_occurrences context WHERE context.wordId = w.id
                   AND (:book IS NULL OR context.bookUrl = :book)
                   AND instr(context.searchSentence, :search) > 0))
          AND (:status = 0 OR (:status = 1 AND w.repetitions = 0)
               OR (:status = 2 AND w.repetitions > 0 AND w.streak < 6)
               OR (:status = 3 AND w.streak >= 6))
        GROUP BY w.id, groupUrl
        ORDER BY groupTitle COLLATE NOCASE, groupUrl,
                 CASE WHEN :sort = 1 THEN w.normalized END,
                 CASE WHEN :sort = 2 THEN (CASE WHEN w.repetitions = 0 THEN 0 WHEN w.streak < 6 THEN 1 ELSE 2 END) END,
                 CASE WHEN :sort = 3 THEN encounters END DESC,
                 w.addedAt DESC, w.id DESC
    """)
    fun words(search: String, book: String?, status: Int, sort: Int, grouped: Boolean = false): Flow<List<VocabularyListItem>>
}
