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
     * When the book was archived: off the shelf, but still here, still
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
     * How big the server says the file is, in bytes. Kept so that
     * "download everything" can say what that will cost before it
     * starts, rather than finding out a book at a time.
     *
     * Null when the server does not say, and for every local book: the
     * figure is the catalog's claim, not a measurement, and is treated
     * as untrusted input wherever it is added up.
     */
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long? = null,
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
    /** The catalog's series name, kept beside the file's for the same reason. */
    @ColumnInfo(name = "catalog_series_name") val catalogSeriesName: String? = null,
    /** The catalog's series index. */
    @ColumnInfo(name = "catalog_series_index") val catalogSeriesIndex: Double? = null,
    /** The watched folder that named this catalog row, for liseur-sync series writes. */
    @ColumnInfo(name = "catalog_folder_id") val catalogFolderId: String? = null,
    /** Whether the catalog series came from the folder, shared layer or personal layer. */
    @ColumnInfo(name = "catalog_series_source") val catalogSeriesSource: String? = null,
    /**
     * The series the reader filed the book under themselves, null when
     * they filed it under none.
     *
     * Only meaningful while [seriesOverridden] is set: the column cannot
     * tell "no series" from "nothing said", and the two are different
     * answers.
     */
    @ColumnInfo(name = "user_series_name") val userSeriesName: String? = null,
    /** Where the reader put it in that series. */
    @ColumnInfo(name = "user_series_index") val userSeriesIndex: Double? = null,
    /**
     * Whether the reader has filed this book under a series by hand.
     *
     * What it buys is durability: every write that comes from a server
     * or from the file checks it first, so a catalog refresh cannot put
     * back a series the reader took the book out of.
     *
     * The name only. Where the book sits *within* the series is
     * [indexOverridden], because reordering a shelf has to move a book
     * without also freezing the name the server gave it.
     */
    @ColumnInfo(name = "series_override") val seriesOverridden: Boolean = false,
    /** When this device last changed its personal series claim. */
    @ColumnInfo(name = "user_series_updated_at") val userSeriesUpdatedAt: Long? = null,
    /** A local personal claim that has not yet been acknowledged by liseur-sync. */
    @ColumnInfo(name = "series_claim_pending") val seriesClaimPending: Boolean = false,
    /** Whether the pending claim removes the personal layer instead of setting it. */
    @ColumnInfo(name = "series_claim_reset") val seriesClaimReset: Boolean = false,
    /** The last personal-layer revision observed from liseur-sync. */
    @ColumnInfo(name = "personal_series_updated_at") val personalSeriesUpdatedAt: Long? = null,
    /**
     * Whether the reader has set this book's place in its series by
     * hand, by typing a number or by dragging the shelf into order.
     *
     * Independent of [seriesOverridden] in both directions: a dragged
     * book keeps the server's series name, and a book filed by hand
     * always sets this too — saying where a book goes and saying nothing
     * about its number both mean the source no longer decides it.
     */
    @ColumnInfo(name = "series_index_override") val indexOverridden: Boolean = false,
) {
    val finished: Boolean get() = finishedAt != null

    val archived: Boolean get() = archivedAt != null

    /**
     * The series id worth asking a server about.
     *
     * A book the reader has refiled keeps the id of the series it came
     * from — that series still exists on the server — but the shelf it
     * is on now is not that series, and fetching its summary would put
     * the wrong blurb under the right name.
     */
    val shelfSeriesId: String? get() = seriesId?.takeIf { !seriesOverridden }

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

    @Query("SELECT * FROM books WHERE url IN (:urls)")
    suspend fun getByUrls(urls: List<String>): List<Book>

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
            "catalog_series_name = NULL, catalog_series_index = NULL, " +
            "catalog_folder_id = NULL, catalog_series_source = NULL, " +
            "series_name = CASE WHEN series_override = 1 THEN user_series_name " +
            "ELSE file_series_name END, " +
            "series_index = CASE " +
            "WHEN series_override = 1 THEN CASE " +
            "WHEN user_series_name IS NOT NULL AND series_index_override = 1 " +
            "THEN user_series_index ELSE NULL END " +
            "WHEN file_series_name IS NULL THEN NULL " +
            "WHEN series_index_override = 1 THEN user_series_index " +
            "ELSE file_series_index END, " +
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
            "catalog_series_name = NULL, catalog_series_index = NULL, " +
            "catalog_folder_id = NULL, catalog_series_source = NULL, " +
            "series_name = CASE WHEN series_override = 1 THEN user_series_name " +
            "ELSE file_series_name END, " +
            "series_index = CASE " +
            "WHEN series_override = 1 THEN CASE " +
            "WHEN user_series_name IS NOT NULL AND series_index_override = 1 " +
            "THEN user_series_index ELSE NULL END " +
            "WHEN file_series_name IS NULL THEN NULL " +
            "WHEN series_index_override = 1 THEN user_series_index " +
            "ELSE file_series_index END, " +
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
                WHEN series_override = 1 THEN user_series_name
                -- The catalog's own answer, not the resolved column: a
                -- resolved series_name that came from this very file
                -- would outrank the file on every later reading of it,
                -- and a corrected OPF could never take.
                ELSE COALESCE(catalog_series_name, :seriesName)
            END,
            series_index = CASE
                -- A number belongs to a series, so the name is resolved
                -- first and an index of any provenance is dropped when
                -- it turns out to name nothing.  This repeats the
                -- series_name ladder above; the two must stay in step.
                WHEN (CASE
                    WHEN series_override = 1 THEN user_series_name
                    ELSE COALESCE(catalog_series_name, :seriesName)
                END) IS NULL THEN NULL
                WHEN series_index_override = 1 THEN user_series_index
                WHEN series_override = 1 THEN NULL
                ELSE COALESCE(catalog_series_index, :seriesIndex)
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
                WHEN series_override = 1 THEN user_series_name
                -- The catalog's own answer, not the resolved column: a
                -- resolved series_name that came from this very file
                -- would outrank the file on every later reading of it,
                -- and a corrected OPF could never take.
                ELSE COALESCE(catalog_series_name, :seriesName)
            END,
            series_index = CASE
                -- A number belongs to a series, so the name is resolved
                -- first and an index of any provenance is dropped when
                -- it turns out to name nothing.  This repeats the
                -- series_name ladder above; the two must stay in step.
                WHEN (CASE
                    WHEN series_override = 1 THEN user_series_name
                    ELSE COALESCE(catalog_series_name, :seriesName)
                END) IS NULL THEN NULL
                WHEN series_index_override = 1 THEN user_series_index
                WHEN series_override = 1 THEN NULL
                ELSE COALESCE(catalog_series_index, :seriesIndex)
            END,
            series_checked = 1
        WHERE url = :url
        """,
    )
    suspend fun fillSeriesFromFile(url: String, seriesName: String?, seriesIndex: Double?)

    /**
     * Files a book where the reader says it goes, or nowhere at all.
     *
     * A null [name] is the "not in a series" answer and is kept as one:
     * `series_override` stays set, so the next catalog refresh finds a
     * decision already made rather than an empty field to fill in.
     *
     * Both flags are set, even when [index] is null. "I filed this book
     * here and said nothing about its number" is an answer too — the
     * book has no number — and leaving the index to the source would let
     * it keep the number its old series gave it and get it back on the
     * next refresh.
     */
    @Query(
        """
        UPDATE books
        SET user_series_name = :name,
            user_series_index = CASE WHEN :name IS NULL THEN NULL ELSE :index END,
            series_override = 1,
            user_series_updated_at = :updatedAt,
            series_claim_pending = 1,
            series_claim_reset = 0,
            series_index_override = 1,
            series_name = :name,
            series_index = CASE WHEN :name IS NULL THEN NULL ELSE :index END,
            series_id = NULL
        WHERE url = :url
        """,
    )
    suspend fun setSeriesOverride(
        url: String,
        name: String?,
        index: Double?,
        updatedAt: Long = System.currentTimeMillis(),
    )

    /**
     * Records the server's own id for the series this book now sits in,
     * and only when the response still answers the mutation that asked.
     *
     * [setSeriesOverride] clears the id rather than keeping the old one,
     * because a book refiled by hand is no longer in the series the last
     * refresh named, and renumbering a shelf sends this id: a stale one
     * would renumber — and quietly re-file into — the previous series.
     * Null until the claim reaches the server, which is the safe way
     * round, since a shelf with no id simply cannot be pushed.
     */
    @Query(
        """
        UPDATE books
        SET series_id = :seriesId,
            series_claim_pending = 0,
            series_claim_reset = 0,
            personal_series_updated_at = :personalSeriesUpdatedAt
        WHERE url = :url
          AND series_claim_pending = 1
          AND user_series_updated_at IS :expectedUserSeriesUpdatedAt
        """,
    )
    suspend fun acknowledgeSeriesClaim(
        url: String,
        expectedUserSeriesUpdatedAt: Long?,
        seriesId: String?,
        personalSeriesUpdatedAt: Long?,
    ): Int

    /** A stale response is useful: it gives the next retry the current precondition. */
    @Query(
        """
        UPDATE books SET personal_series_updated_at = :personalSeriesUpdatedAt
        WHERE url = :url
          AND series_claim_pending = 1
          AND user_series_updated_at IS :expectedUserSeriesUpdatedAt
        """,
    )
    suspend fun updatePendingSeriesClaimRevision(
        url: String,
        expectedUserSeriesUpdatedAt: Long?,
        personalSeriesUpdatedAt: Long?,
    ): Int

    @Query("SELECT * FROM books WHERE series_claim_pending = 1")
    suspend fun pendingSeriesClaims(): List<Book>

    /**
     * Drops every locally pending claim outright, for when there is no
     * liseur-sync account left to answer it.
     *
     * Komga and calibre-web never acknowledge this protocol, so a claim
     * raised while connected to either would otherwise sit pending
     * forever — and a pending claim is what [updateCatalogFields] reads
     * to refuse a catalog refresh, freezing the row's series fields at
     * whatever they were the moment the claim was made.
     */
    @Query(
        """
        UPDATE books SET series_claim_pending = 0, series_claim_reset = 0
        WHERE series_claim_pending = 1
        """,
    )
    suspend fun discardPendingSeriesClaims()

    /**
     * Hands the book back to whoever described it, for when the reader
     * decides the server had it right after all.
     */
    @Query(
        """
        UPDATE books
        SET user_series_name = NULL,
            user_series_index = NULL,
            series_override = 0,
            user_series_updated_at = :updatedAt,
            series_claim_pending = 1,
            series_claim_reset = 1,
            series_index_override = 0,
            series_name = COALESCE(catalog_series_name, file_series_name),
            series_index = CASE
                WHEN COALESCE(catalog_series_name, file_series_name) IS NULL THEN NULL
                ELSE COALESCE(catalog_series_index, file_series_index)
            END
        WHERE url = :url
        """,
    )
    suspend fun clearSeriesOverride(url: String, updatedAt: Long = System.currentTimeMillis())

    /** Drops reader-specific series state when a different work replaces this file. */
    @Query(
        """
        UPDATE books
        SET user_series_name = NULL,
            user_series_index = NULL,
            series_override = 0,
            series_index_override = 0,
            user_series_updated_at = NULL,
            series_claim_pending = 0,
            series_claim_reset = 0,
            personal_series_updated_at = NULL,
            series_id = NULL,
            series_name = COALESCE(catalog_series_name, file_series_name),
            series_index = CASE
                WHEN COALESCE(catalog_series_name, file_series_name) IS NULL THEN NULL
                ELSE COALESCE(catalog_series_index, file_series_index)
            END
        WHERE url = :url
        """,
    )
    suspend fun clearSeriesForReplacedWork(url: String)

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
     * Writes the catalog's fields without putting back stale local state.
     *
     * A liseur-sync catalog also carries the effective personal series
     * claim. Those user-series fields are adopted only while
     * [expectedUserSeriesUpdatedAt] still matches the row that the
     * refresh read. A manual edit made while the request was in flight
     * wins that refresh; the next refresh reconciles from a fresh row.
     * Catalog-owned fields still land either way.
     *
     * The row may also have moved in unrelated ways since it was read —
     * a download finishing, a book being opened or archived — so a
     * full-row write here is never safe.
     */
    @Query(
        """
        UPDATE books
        SET title = :title, author = :author, remote_uuid = :remoteUuid,
            remote_book_id = :remoteBookId, cover_url = :coverUrl,
            download_href = :downloadHref, remote_updated_at = :remoteUpdatedAt,
            remote_page_count = :remotePageCount,
            size_bytes = COALESCE(:sizeBytes, size_bytes),
            catalog_series_name = :catalogSeriesName,
            catalog_series_index = :catalogSeriesIndex,
            catalog_folder_id = :catalogFolderId,
            catalog_series_source = :catalogSeriesSource,
            personal_series_updated_at = CASE
                WHEN series_claim_pending = 0
                    AND user_series_updated_at IS :expectedUserSeriesUpdatedAt
                    THEN :personalSeriesUpdatedAt
                ELSE personal_series_updated_at
            END,
            user_series_name = CASE
                WHEN series_claim_pending = 0
                    AND user_series_updated_at IS :expectedUserSeriesUpdatedAt THEN :userSeriesName
                ELSE user_series_name
            END,
            user_series_index = CASE
                WHEN series_claim_pending = 0
                    AND user_series_updated_at IS :expectedUserSeriesUpdatedAt THEN :userSeriesIndex
                ELSE user_series_index
            END,
            series_override = CASE
                WHEN series_claim_pending = 0
                    AND user_series_updated_at IS :expectedUserSeriesUpdatedAt THEN :seriesOverridden
                ELSE series_override
            END,
            series_index_override = CASE
                WHEN series_claim_pending = 0
                    AND user_series_updated_at IS :expectedUserSeriesUpdatedAt THEN :indexOverridden
                ELSE series_index_override
            END,
            user_series_updated_at = CASE
                WHEN series_claim_pending = 0
                    AND user_series_updated_at IS :expectedUserSeriesUpdatedAt THEN :userSeriesUpdatedAt
                ELSE user_series_updated_at
            END,
            series_name = CASE
                WHEN (CASE
                    WHEN series_claim_pending = 0
                        AND user_series_updated_at IS :expectedUserSeriesUpdatedAt
                        THEN :seriesOverridden
                    ELSE series_override
                END) = 1 THEN CASE
                    WHEN series_claim_pending = 0
                        AND user_series_updated_at IS :expectedUserSeriesUpdatedAt
                        THEN :userSeriesName
                    ELSE user_series_name
                END
                ELSE COALESCE(:catalogSeriesName, file_series_name)
            END,
            series_index = CASE
                -- Same ladder as series_name above, so that a number
                -- never outlives the name it counts within.
                WHEN (CASE
                    WHEN (CASE
                        WHEN series_claim_pending = 0
                            AND user_series_updated_at IS :expectedUserSeriesUpdatedAt
                            THEN :seriesOverridden
                        ELSE series_override
                    END) = 1 THEN CASE
                        WHEN series_claim_pending = 0
                            AND user_series_updated_at IS :expectedUserSeriesUpdatedAt
                            THEN :userSeriesName
                        ELSE user_series_name
                    END
                    ELSE COALESCE(:catalogSeriesName, file_series_name)
                END) IS NULL THEN NULL
                WHEN (CASE
                    WHEN series_claim_pending = 0
                        AND user_series_updated_at IS :expectedUserSeriesUpdatedAt
                        THEN :indexOverridden
                    ELSE series_index_override
                END) = 1 THEN CASE
                    WHEN series_claim_pending = 0
                        AND user_series_updated_at IS :expectedUserSeriesUpdatedAt
                        THEN :userSeriesIndex
                    ELSE user_series_index
                END
                WHEN (CASE
                    WHEN series_claim_pending = 0
                        AND user_series_updated_at IS :expectedUserSeriesUpdatedAt
                        THEN :seriesOverridden
                    ELSE series_override
                END) = 1 THEN NULL
                ELSE COALESCE(:catalogSeriesIndex, file_series_index)
            END,
            series_id = CASE
                WHEN series_claim_pending = 0
                    AND user_series_updated_at IS :expectedUserSeriesUpdatedAt THEN :seriesId
                ELSE series_id
            END
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
        catalogFolderId: String?,
        catalogSeriesSource: String?,
        userSeriesName: String?,
        userSeriesIndex: Double?,
        seriesOverridden: Boolean,
        indexOverridden: Boolean,
        userSeriesUpdatedAt: Long?,
        expectedUserSeriesUpdatedAt: Long?,
        seriesId: String?,
        personalSeriesUpdatedAt: Long? = null,
        sizeBytes: Long? = null,
    )

    @Query("DELETE FROM books WHERE url IN (:urls)")
    suspend fun deleteByUrls(urls: List<String>)

    @Query("SELECT * FROM books WHERE remote_uuid IN (:remoteUuids)")
    suspend fun byRemoteUuids(remoteUuids: List<String>): List<Book>

    /**
     * Gives a local book a server identity, after an upload landed.
     *
     * Only the link itself is written: title, series and the rest are
     * the catalog's to fill in on the next refresh, and the row keeps
     * its URL so reading positions and annotations never move.
     */
    @Query(
        """
        UPDATE books
        SET remote_uuid = :remoteUuid, download_href = :downloadHref,
            cover_url = COALESCE(cover_url, :coverUrl),
            remote_updated_at = :remoteUpdatedAt
        WHERE url = :url
        """,
    )
    suspend fun linkToRemote(
        url: String,
        remoteUuid: String,
        downloadHref: String,
        coverUrl: String?,
        remoteUpdatedAt: Long,
    )

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
