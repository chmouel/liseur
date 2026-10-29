package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction

@Entity(tableName = "book_ai_preferences")
data class BookAiPreferences(
    @PrimaryKey @ColumnInfo(name = "book_url") val bookUrl: String,
    @ColumnInfo(name = "translation_direction") val translationDirection: String = "",
    @ColumnInfo(name = "narration_direction") val narrationDirection: String = "",
)

@Dao
interface BookAiPreferencesDao {
    @Query("SELECT * FROM book_ai_preferences WHERE book_url = :bookUrl")
    suspend fun get(bookUrl: String): BookAiPreferences?

    @Query("DELETE FROM book_ai_preferences WHERE book_url = :bookUrl")
    suspend fun forget(bookUrl: String)

    @Upsert
    suspend fun put(preferences: BookAiPreferences)

    @Transaction
    suspend fun setTranslation(bookUrl: String, direction: String) {
        put((get(bookUrl) ?: BookAiPreferences(bookUrl)).copy(translationDirection = direction))
    }

    @Transaction
    suspend fun setNarration(bookUrl: String, direction: String) {
        put((get(bookUrl) ?: BookAiPreferences(bookUrl)).copy(narrationDirection = direction))
    }
}
