package com.chmouel.liseur.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Where a book's file is, from the library's point of view. */
enum class DownloadState {
    /** Known from the server catalog, not on the device. */
    REMOTE,
    QUEUED,
    DOWNLOADING,
    /** On the device: either a local file or a completed download. */
    DOWNLOADED,
    FAILED,
}

/**
 * A book known to the library.
 *
 * [url] is the book's permanent identity and never changes: the file URL
 * for local books, and `calibre:<uuid>` for books that came from the
 * server. The file itself lives in [localUri], which is filled in when a
 * download completes and cleared when it is removed. Keeping the two
 * apart matters because `reading_progress` is keyed by URL — reusing the
 * file URL as the identity would lose the reader's place every time a
 * book is downloaded or removed.
 *
 * [source] is the tree URL of the library folder a local book was found
 * in, or null for books imported individually.
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
    @ColumnInfo(name = "local_uri") val localUri: String? = null,
    @ColumnInfo(name = "remote_uuid") val remoteUuid: String? = null,
    @ColumnInfo(name = "remote_book_id") val remoteBookId: Int? = null,
    @ColumnInfo(name = "cover_url") val coverUrl: String? = null,
    @ColumnInfo(name = "download_href") val downloadHref: String? = null,
    @ColumnInfo(name = "download_state") val downloadState: DownloadState = DownloadState.DOWNLOADED,
    @ColumnInfo(name = "remote_updated_at") val remoteUpdatedAt: Long? = null,
) {
    /** The URL to hand to Readium, or null when the file is not here yet. */
    val openableUrl: String? get() = localUri ?: url.takeIf { downloadState == DownloadState.DOWNLOADED }
}

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

    @Query("SELECT * FROM books WHERE remote_uuid = :uuid")
    suspend fun getByRemoteUuid(uuid: String): Book?

    @Query("SELECT * FROM books WHERE remote_uuid IS NOT NULL")
    suspend fun allRemote(): List<Book>

    @Query("SELECT url FROM books WHERE source = :source")
    suspend fun urlsForSource(source: String): List<String>

    @Query(
        "UPDATE books SET download_state = :state, local_uri = :localUri WHERE url = :url",
    )
    suspend fun setDownloadState(url: String, state: DownloadState, localUri: String?)

    @Query("UPDATE books SET cover_path = :coverPath WHERE url = :url")
    suspend fun setCoverPath(url: String, coverPath: String)

    @Query("SELECT * FROM books WHERE local_uri IS NOT NULL")
    fun observeDownloaded(): Flow<List<Book>>

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
