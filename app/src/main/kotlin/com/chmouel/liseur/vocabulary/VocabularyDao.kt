package com.chmouel.liseur.vocabulary

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
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "vocabulary_words",
    indices = [Index(value = ["normalized", "language"], unique = true), Index("dueAt")],
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
) {
    internal fun withSearchText(): VocabularyWord =
        copy(searchText = normalizeVocabularySearch("$word\n${definition.orEmpty()}\n$notes"))
}

@Entity(
    tableName = "vocabulary_occurrences",
    foreignKeys = [ForeignKey(
        entity = VocabularyWord::class,
        parentColumns = ["id"],
        childColumns = ["wordId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["wordId", "bookUrl", "locationKey"], unique = true), Index("bookUrl")],
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
)

@Entity(
    tableName = "vocabulary_reviews",
    foreignKeys = [ForeignKey(
        entity = VocabularyWord::class,
        parentColumns = ["id"],
        childColumns = ["wordId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("wordId"), Index("reviewedAt")],
)
data class VocabularyReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val rating: String,
    val reviewedAt: Long,
    val intervalMinutes: Int,
    val dueAt: Long,
    val firstReview: Boolean,
)

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
    @Query("SELECT * FROM vocabulary_words ORDER BY id")
    suspend fun exportWords(): List<VocabularyWord>

    @Query("SELECT * FROM vocabulary_occurrences ORDER BY id")
    suspend fun exportOccurrences(): List<VocabularyOccurrence>

    @Query("SELECT * FROM vocabulary_reviews ORDER BY id")
    suspend fun exportReviews(): List<VocabularyReview>

    @Query("SELECT * FROM vocabulary_reviews WHERE wordId = :id")
    suspend fun reviews(id: Long): List<VocabularyReview>

    @Update
    suspend fun updateOccurrence(occurrence: VocabularyOccurrence)

    @Query("SELECT * FROM vocabulary_words WHERE normalized = :normalized AND language = :language")
    fun observeWord(normalized: String, language: String): Flow<VocabularyWord?>

    @Query("SELECT * FROM vocabulary_words WHERE normalized = :normalized AND language = :language")
    suspend fun findWord(normalized: String, language: String): VocabularyWord?

    @Query("SELECT * FROM vocabulary_words WHERE id = :id")
    fun observeWord(id: Long): Flow<VocabularyWord?>

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

    @Query("""
        SELECT * FROM vocabulary_occurrences WHERE wordId = :id
        ORDER BY (sentence IS NULL OR trim(sentence) = ''), lastSeenAt DESC, id DESC LIMIT 1
    """)
    suspend fun latestOccurrence(id: Long): VocabularyOccurrence?

    @Query("SELECT * FROM vocabulary_occurrences WHERE wordId = :id AND bookUrl = :bookUrl AND locationKey = :key")
    suspend fun occurrence(id: Long, bookUrl: String, key: String): VocabularyOccurrence?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrence(occurrence: VocabularyOccurrence): Long

    @Query("""
        UPDATE vocabulary_occurrences
        SET encounters = encounters + 1, lastSeenAt = :now, bookTitle = :title,
            searchSentence = CASE WHEN sentence IS NULL THEN :searchSentence ELSE searchSentence END,
            sentence = coalesce(sentence, :sentence)
        WHERE id = :id
    """)
    suspend fun recordEncounter(id: Long, now: Long, title: String, sentence: String?, searchSentence: String)

    @Query("UPDATE vocabulary_occurrences SET sentence = :sentence, searchSentence = :searchSentence WHERE id = :id")
    suspend fun editSentence(id: Long, sentence: String, searchSentence: String)

    @Query("""
        SELECT bookUrl, max(bookTitle) AS bookTitle FROM vocabulary_occurrences
        WHERE bookUrl != '' GROUP BY bookUrl ORDER BY bookTitle COLLATE NOCASE, bookUrl
    """)
    fun books(): Flow<List<VocabularyBook>>

    @Query("""
        SELECT count(*) AS total, count(CASE WHEN repetitions = 0 THEN 1 END) AS newWords,
            count(CASE WHEN streak >= 6 THEN 1 END) AS learned
        FROM vocabulary_words w
        WHERE :book IS NULL OR EXISTS (
            SELECT 1 FROM vocabulary_occurrences o WHERE o.wordId = w.id AND o.bookUrl = :book
        )
    """)
    fun statistics(book: String?): Flow<VocabularyStats>

    @Query("""
        SELECT count(*) AS total, count(CASE WHEN firstReview THEN 1 END) AS newWords
        FROM vocabulary_reviews WHERE reviewedAt >= :start AND reviewedAt <= :end
    """)
    suspend fun reviewsBetween(start: Long, end: Long): VocabularyDailyReviews

    @Insert
    suspend fun addReview(review: VocabularyReview): Long

    @Query("DELETE FROM vocabulary_reviews WHERE id = :id")
    suspend fun deleteReview(id: Long)

    @Query("SELECT max(id) FROM vocabulary_reviews WHERE wordId = :wordId")
    suspend fun latestReviewId(wordId: Long): Long?

    @Query("""
        WITH eligible AS (
            SELECT w.id, w.repetitions, w.dueAt FROM vocabulary_words w
            WHERE dueAt <= :now AND (:book IS NULL OR EXISTS (
                SELECT 1 FROM vocabulary_occurrences o WHERE o.wordId = w.id AND o.bookUrl = :book
            ))
            AND (repetitions > 0 OR id IN (
                SELECT candidate.id FROM vocabulary_words candidate
                WHERE candidate.repetitions = 0 AND candidate.dueAt <= :now
                AND (:book IS NULL OR EXISTS (
                    SELECT 1 FROM vocabulary_occurrences o WHERE o.wordId = candidate.id AND o.bookUrl = :book
                ))
                ORDER BY candidate.addedAt, candidate.id LIMIT :newLimit
            ))
            ORDER BY (repetitions = 0), dueAt, id LIMIT :reviewLimit
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
        WHERE (:book IS NULL OR o.bookUrl = :book)
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
