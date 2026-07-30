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
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long? = null,
    @ColumnInfo(name = "file_modified_at") val fileModifiedAt: Long? = null,
    /**
     * What the file itself says it is: the EPUB's own identifier, falling
     * back to title and author. Lets a file replaced at the same path be
     * told apart from the same book fetched again.
     */
    @ColumnInfo(name = "work_id") val workId: String? = null,
    /** When the book was marked read, by hand or by reaching the end. */
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    /**
     * When the book was put away: off the shelf, but still here, still
     * synced, and still holding your place. Null while it is on the shelf.
     */
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
    /**
     * How long the server thinks the book is, in its own units. Komga
     * counts pages and the catalog pass already knows the number, so it
     * is kept rather than asked for again. Null for anything the server
     * does not count, and for every local book.
     */
    @ColumnInfo(name = "remote_page_count") val remotePageCount: Int? = null,
) {
    val finished: Boolean get() = finishedAt != null

    val archived: Boolean get() = archivedAt != null

    /** The URL to hand to Readium, or null when the file is not here yet. */
    val openableUrl: String? get() = localUri ?: url.takeIf { downloadState == DownloadState.DOWNLOADED }
}

@Dao
interface BookDao {
    /**
     * Every book, in a settled order.
     *
     * Which order the library actually shows is the reader's choice and
     * is applied in `LibrarySort`, where all four of them live together
     * and can be tested. This one only has to be the same every time so
     * nothing flickers on the way through.
     */
    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE")
    suspend fun allOnce(): List<Book>

    /**
     * The book to carry on with: the one read most recently anywhere,
     * not merely the one opened last on this device. Reading arriving
     * from another phone counts, which is the whole point of syncing it.
     */
    @Query(MOST_RECENT)
    fun observeMostRecent(): Flow<Book?>

    @Query(MOST_RECENT)
    suspend fun mostRecentlyOpened(): Book?

    @Query("SELECT * FROM books WHERE url = :url")
    suspend fun getByUrl(url: String): Book?

    /** Puts a book away, or brings it back to the shelf. */
    @Query("UPDATE books SET archived_at = :archivedAt WHERE url = :url")
    suspend fun setArchived(url: String, archivedAt: Long?)

    @Query("SELECT * FROM books WHERE remote_uuid = :uuid")
    suspend fun getByRemoteUuid(uuid: String): Book?

    @Query("SELECT * FROM books WHERE remote_uuid IS NOT NULL")
    suspend fun allRemote(): List<Book>

    /** Forgets remote books that were never downloaded, e.g. on disconnect. */
    @Query("DELETE FROM books WHERE remote_uuid IS NOT NULL AND local_uri IS NULL")
    suspend fun deleteRemoteNotDownloaded()

    /**
     * Cuts downloaded books loose from the server they came from. The file
     * is yours now, so it stays; what goes is the link that would make it
     * sync against whichever server is connected next.
     */
    @Query(
        "UPDATE books SET remote_uuid = NULL, remote_book_id = NULL, cover_url = NULL, " +
            "download_href = NULL, remote_updated_at = NULL, remote_page_count = NULL " +
            "WHERE remote_uuid IS NOT NULL",
    )
    suspend fun unlinkDownloadedFromRemote()

    /**
     * The same, for named books: one that has gone from the catalog but
     * is still on the device. Keeping the link would leave it syncing
     * against an id the server no longer knows.
     */
    @Query(
        "UPDATE books SET remote_uuid = NULL, remote_book_id = NULL, cover_url = NULL, " +
            "download_href = NULL, remote_updated_at = NULL, remote_page_count = NULL " +
            "WHERE url IN (:urls)",
    )
    suspend fun unlinkFromRemote(urls: List<String>)

    @Query("SELECT url FROM books WHERE source = :source")
    suspend fun urlsForSource(source: String): List<String>

    @Query(
        """
        UPDATE books
        SET download_state = :state, local_uri = :localUri, downloaded_at = :downloadedAt
        WHERE url = :url
        """,
    )
    suspend fun setDownloadState(
        url: String,
        state: DownloadState,
        localUri: String?,
        downloadedAt: Long? = null,
    )

    /** Refreshes what we read out of a file that changed on disk. */
    @Query(
        """
        UPDATE books
        SET title = :title, author = :author, cover_path = :coverPath,
            file_modified_at = :fileModifiedAt, work_id = :workId
        WHERE url = :url
        """,
    )
    suspend fun refreshIndexedFile(
        url: String,
        title: String,
        author: String?,
        coverPath: String?,
        fileModifiedAt: Long?,
        workId: String?,
    )

    /**
     * Forgets that this book was ever opened, for when the file at a path
     * turns out to hold a different book entirely.
     */
    @Query("UPDATE books SET last_opened_at = NULL, finished_at = NULL WHERE url = :url")
    suspend fun forgetReadingHistory(url: String)

    @Query("UPDATE books SET cover_path = :coverPath WHERE url = :url")
    suspend fun setCoverPath(url: String, coverPath: String)

    @Query("SELECT * FROM books WHERE local_uri IS NOT NULL")
    fun observeDownloaded(): Flow<List<Book>>

    @Upsert
    suspend fun upsert(book: Book): Long

    @Query("DELETE FROM books WHERE url IN (:urls)")
    suspend fun deleteByUrls(urls: List<String>)

    @Query("UPDATE books SET finished_at = :at WHERE url = :url")
    suspend fun setFinishedAt(url: String, at: Long?)

    @Query("UPDATE books SET last_opened_at = :at WHERE url = :url")
    suspend fun touchLastOpened(url: String, at: Long)

    companion object {
        const val MOST_RECENT = """
            SELECT books.* FROM books
            LEFT JOIN reading_progress ON reading_progress.book_url = books.url
            WHERE books.archived_at IS NULL
              AND (
                  books.last_opened_at IS NOT NULL
                  OR reading_progress.total_progression IS NOT NULL
              )
            ORDER BY MAX(
                COALESCE(books.last_opened_at, 0),
                COALESCE(reading_progress.updated_at, 0)
            ) DESC
            LIMIT 1
        """
    }
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
