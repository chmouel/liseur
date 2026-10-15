package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A name or term of one book, written by the reader, and how they want it
 * translated: "Le Chat Botté" as "Puss in Boots", "Madame" kept as it is,
 * or a character's note ("Camille: a woman, the narrator's sister").
 *
 * [term] and the comma-separated [aliases] are looked for in the book's
 * text; [translation] and [note] go to the model with a sentence that
 * uses them. [target] is the language tag the entry translates into, or
 * empty for any. Kept with the reader's highlights in `liseur.db`, so it
 * is backed up, and never synced.
 */
@Entity(tableName = "translation_glossary", indices = [Index("book_url")])
data class GlossaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "book_url") val bookUrl: String,
    val term: String,
    val aliases: String = "",
    val translation: String = "",
    val note: String = "",
    val target: String = "",
    val enabled: Boolean = true,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface GlossaryDao {
    @Query("SELECT * FROM translation_glossary WHERE book_url = :bookUrl ORDER BY term COLLATE NOCASE, id")
    fun observe(bookUrl: String): Flow<List<GlossaryEntry>>

    @Query("SELECT * FROM translation_glossary WHERE book_url = :bookUrl")
    suspend fun all(bookUrl: String): List<GlossaryEntry>

    @Upsert
    suspend fun upsert(entry: GlossaryEntry): Long

    @Query("DELETE FROM translation_glossary WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM translation_glossary WHERE book_url = :bookUrl")
    suspend fun deleteBook(bookUrl: String)
}
