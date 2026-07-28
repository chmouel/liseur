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
 * A book known to the library. [url] points at the EPUB itself; [source]
 * is the tree URL of the library folder it was found in, or null for
 * books imported individually.
 */
@Entity(
    tableName = "books",
    indices = [Index(value = ["url"], unique = true)],
)
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val author: String?,
    @ColumnInfo(name = "cover_path") val coverPath: String?,
    val source: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long?,
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY title")
    fun observeAll(): Flow<List<Book>>

    @Query(
        "SELECT * FROM books WHERE last_opened_at IS NOT NULL ORDER BY last_opened_at DESC LIMIT 1",
    )
    fun observeMostRecent(): Flow<Book?>

    @Query("SELECT * FROM books WHERE url = :url")
    suspend fun getByUrl(url: String): Book?

    @Query("SELECT url FROM books WHERE source = :source")
    suspend fun urlsForSource(source: String): List<String>

    @Upsert
    suspend fun upsert(book: Book): Long

    @Query("DELETE FROM books WHERE url IN (:urls)")
    suspend fun deleteByUrls(urls: List<String>)

    @Query("UPDATE books SET last_opened_at = :at WHERE url = :url")
    suspend fun touchLastOpened(url: String, at: Long)
}

/** A user-picked SAF folder that is scanned for EPUB files. */
@Entity(tableName = "library_folders")
data class LibraryFolder(
    @PrimaryKey val url: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Dao
interface LibraryFolderDao {
    @Query("SELECT * FROM library_folders ORDER BY added_at")
    suspend fun getAll(): List<LibraryFolder>

    @Query("SELECT * FROM library_folders ORDER BY added_at")
    fun observeAll(): Flow<List<LibraryFolder>>

    @Upsert
    suspend fun upsert(folder: LibraryFolder)

    @Query("DELETE FROM library_folders WHERE url = :url")
    suspend fun delete(url: String)
}
