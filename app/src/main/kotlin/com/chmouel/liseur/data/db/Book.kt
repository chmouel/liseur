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
    indices = [
        Index(value = ["url"], unique = true),
        Index(value = ["series_name"]),
    ],
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
    /**
     * The series this book belongs to, spelled the way its source spells
     * it. Which books make up that series is worked out from this name
     * rather than from [seriesId], so a book downloaded from a server
     * and a loose file of the same series end up on one shelf.
     */
    @ColumnInfo(name = "series_name") val seriesName: String? = null,
    /**
     * Where in the series it sits: 1, 2, 7.5. Null when the source names
     * a series but will not say where — a book can belong somewhere
     * without anybody knowing quite where.
     */
    @ColumnInfo(name = "series_index") val seriesIndex: Double? = null,
    /** The EPUB's series name, kept separately from the effective value above. */
    @ColumnInfo(name = "file_series_name") val fileSeriesName: String? = null,
    /** The EPUB's series index, kept so a catalog refresh cannot mistake old catalog data for it. */
    @ColumnInfo(name = "file_series_index") val fileSeriesIndex: Double? = null,
    /**
     * The server's own name for the series, which only Komga has. Used
     * to ask it for what it knows about the series and for nothing else;
     * grouping never touches it.
     */
    @ColumnInfo(name = "series_id") val seriesId: String? = null,
    /**
     * Whether this book's file has been read for series metadata.
     *
     * Set once the file has been looked at, whatever the answer was, so
     * a book that genuinely belongs to no series is not reopened on
     * every launch by the backfill.
     */
    @ColumnInfo(name = "series_checked") val seriesChecked: Boolean = false,
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

    @Query("SELECT * FROM books WHERE remote_uuid IS NOT NULL")
    suspend fun allRemote(): List<Book>

    /** Remote-only books that will disappear when their account does. */
    @Query("SELECT url FROM books WHERE remote_uuid IS NOT NULL AND local_uri IS NULL")
    suspend fun remoteNotDownloadedUrls(): List<String>

    /**
     * Cuts downloaded books loose from the server they came from. The file
     * is yours now, so it stays; what goes is the link that would make it
     * sync against whichever server is connected next.
     */
    @Query(
        "UPDATE books SET remote_uuid = NULL, remote_book_id = NULL, cover_url = NULL, " +
            "download_href = NULL, remote_updated_at = NULL, remote_page_count = NULL, " +
            "series_name = file_series_name, series_index = file_series_index, " +
            "series_id = NULL " +
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
            "download_href = NULL, remote_updated_at = NULL, remote_page_count = NULL, " +
            "series_name = file_series_name, series_index = file_series_index, " +
            "series_id = NULL " +
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
            file_modified_at = :fileModifiedAt, work_id = :workId,
            file_series_name = :seriesName, file_series_index = :seriesIndex,
            series_name = CASE
                WHEN remote_uuid IS NULL THEN :seriesName
                ELSE COALESCE(series_name, :seriesName)
            END,
            series_index = CASE
                WHEN remote_uuid IS NULL THEN :seriesIndex
                ELSE COALESCE(series_index, :seriesIndex)
            END,
            series_checked = 1
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
        seriesName: String?,
        seriesIndex: Double?,
    )

    /**
     * Books whose file is here but has never been read for a series.
     *
     * The library kept before series existed is full of them, and
     * nothing else will ever look: a file is only re-read when its
     * modification time moves, and these have not been touched.
     */
    @Query(
        """
        SELECT * FROM books
        WHERE series_checked = 0
          AND (local_uri IS NOT NULL OR download_state = 'DOWNLOADED')
        ORDER BY last_opened_at DESC, added_at DESC
        LIMIT :limit
        """,
    )
    suspend fun needingSeriesCheck(limit: Int): List<Book>

    /**
     * Records what a file said about its series, or that it said
     * nothing.
     *
     * The catalog's answer is the better one where there is one, so what
     * the file knows is only written into a gap. Marking the book
     * checked either way is the point: it is what stops the backfill
     * asking the same standalone book again tomorrow.
     */
    @Query(
        """
        UPDATE books
        SET file_series_name = :seriesName,
            file_series_index = :seriesIndex,
            series_name = CASE
                WHEN remote_uuid IS NULL THEN :seriesName
                ELSE COALESCE(series_name, :seriesName)
            END,
            series_index = CASE
                WHEN remote_uuid IS NULL THEN :seriesIndex
                ELSE COALESCE(series_index, :seriesIndex)
            END,
            series_checked = 1
        WHERE url = :url
        """,
    )
    suspend fun fillSeriesFromFile(url: String, seriesName: String?, seriesIndex: Double?)

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

    /**
     * A page of the catalog in one go.
     *
     * A row at a time meant a statement, a transaction and an
     * invalidation each, so a library of any size redrew itself once per
     * book while it was being read in.
     */
    @Upsert
    suspend fun upsertAll(books: List<Book>)

    /**
     * Writes only what the catalog owns. The row it lands on may have
     * moved since it was read — a download finishing, a book being
     * opened or archived — and none of that is the catalog's to put
     * back, so a full-row write here is never safe.
     */
    @Query(
        """
        UPDATE books
        SET title = :title, author = :author, remote_uuid = :remoteUuid,
            remote_book_id = :remoteBookId, cover_url = :coverUrl,
            download_href = :downloadHref, remote_updated_at = :remoteUpdatedAt,
            remote_page_count = :remotePageCount,
            series_name = COALESCE(:catalogSeriesName, file_series_name),
            series_index = CASE
                WHEN COALESCE(:catalogSeriesName, file_series_name) IS NULL THEN NULL
                ELSE COALESCE(:catalogSeriesIndex, file_series_index)
            END,
            series_id = :seriesId
        WHERE url = :url
        """,
    )
    suspend fun updateCatalogFields(
        url: String,
        title: String,
        author: String?,
        remoteUuid: String?,
        remoteBookId: Int?,
        coverUrl: String?,
        downloadHref: String?,
        remoteUpdatedAt: Long?,
        remotePageCount: Int?,
        catalogSeriesName: String?,
        catalogSeriesIndex: Double?,
        seriesId: String?,
    )

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
