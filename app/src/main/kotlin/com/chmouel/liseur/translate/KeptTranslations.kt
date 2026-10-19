package com.chmouel.liseur.translate

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "kept_translations")
data class KeptTranslation(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    val source: String?,
    val target: String,
    val identity: String,
    val generation: Long,
    val total: Int,
    val done: Int,
    val failed: Int,
    val state: String,
    val reason: String?,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "kept_failures",
    primaryKeys = ["book_url", "key"],
    foreignKeys = [ForeignKey(
        entity = KeptTranslation::class,
        parentColumns = ["book_url"],
        childColumns = ["book_url"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class KeptFailure(@ColumnInfo(name = "book_url") val bookUrl: String, val key: String)

data class KeptLookup(val translation: String? = null, val failed: Boolean = false, val invalidated: Boolean = false)

enum class KeptWriteResult { SAVED, ALREADY_PRESENT, INVALIDATED }

@Dao
interface KeptTranslationDao {
    @Query("SELECT * FROM kept_translations ORDER BY updated_at DESC")
    fun observe(): Flow<List<KeptTranslation>>

    @Query("SELECT * FROM kept_translations ORDER BY updated_at DESC")
    suspend fun all(): List<KeptTranslation>

    @Query("SELECT * FROM kept_translations WHERE book_url = :bookUrl")
    suspend fun find(bookUrl: String): KeptTranslation?

    @Upsert
    suspend fun put(row: KeptTranslation)

    @Query("UPDATE kept_translations SET generation = generation + 1 WHERE book_url IN (:books)")
    suspend fun invalidate(books: List<String>)

    @Query("DELETE FROM kept_translations WHERE book_url IN (:books)")
    suspend fun forget(books: List<String>)

    @Query("SELECT EXISTS(SELECT 1 FROM kept_failures WHERE book_url = :bookUrl AND `key` = :key)")
    suspend fun failed(bookUrl: String, key: String): Boolean

    @Query("SELECT `key` FROM kept_failures WHERE book_url = :bookUrl AND `key` IN (:keys)")
    suspend fun failedKeys(bookUrl: String, keys: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun fail(row: KeptFailure): Long

    @Query("SELECT `key` FROM kept_failures WHERE book_url = :bookUrl AND `key` NOT IN (SELECT `key` FROM translated_sentences WHERE book_url = :bookUrl) ORDER BY `key`")
    suspend fun failures(bookUrl: String): List<String>

    @Query("SELECT `key` FROM kept_failures WHERE book_url = :bookUrl AND `key` > :after AND `key` NOT IN (SELECT `key` FROM translated_sentences WHERE book_url = :bookUrl) ORDER BY `key` LIMIT :limit")
    suspend fun failurePage(bookUrl: String, after: String, limit: Int): List<String>

    @Query("DELETE FROM kept_failures WHERE book_url = :bookUrl")
    suspend fun clearFailures(bookUrl: String)
}
