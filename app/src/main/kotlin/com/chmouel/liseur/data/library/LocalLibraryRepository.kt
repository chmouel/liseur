package com.chmouel.liseur.data.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.provider.DocumentsContract
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.LibraryFolder
import com.chmouel.liseur.data.db.LibraryFolderDao
import com.chmouel.liseur.domain.SeriesMetadata
import com.chmouel.liseur.domain.isSameWork
import com.chmouel.liseur.domain.workIdOf
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener

/**
 * Indexes EPUBs into the library: from SAF folders the user picked
 * (scanned recursively) and from individually opened files.
 */
class LocalLibraryRepository(
    private val context: Context,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
    private val bookDao: BookDao,
    private val folderDao: LibraryFolderDao,
    private val bookRemoval: BookRemoval,
) {
    val books: Flow<List<Book>> = bookDao.observeAll()
    val mostRecent: Flow<Book?> = bookDao.observeMostRecent()
    val folders: Flow<List<LibraryFolder>> = folderDao.observeAll()

    suspend fun addFolder(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                // Write as well as read: without it, deleting a book from
                // the library cannot delete the file it stands for.
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            // An older version of Liseur took read only, and the grant
            // cannot be widened after the fact. Reading still works; the
            // folder has to be added again before deleting will.
            Log.i(TAG, "Only allowed to read this folder", it)
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        folderDao.upsert(LibraryFolder(url = treeUri.toString(), addedAt = System.currentTimeMillis()))
        scanFolder(treeUri)
    }

    suspend fun removeFolder(url: String) {
        folderDao.delete(url)
        bookRemoval.deleteByUrls(bookDao.urlsForSource(url))
    }

    /** Rescans every library folder, adding new books and pruning deleted files. */
    suspend fun rescanAll() {
        for (folder in folderDao.getAll()) {
            scanFolder(Uri.parse(folder.url))
        }
    }

    /** Adds a single, individually picked book to the library. */
    suspend fun importBook(uri: Uri): Book? {
        val url = uri.toAbsoluteUrl() ?: return null
        bookDao.getByUrl(url.toString())?.let { return it }
        return indexBook(url, source = null)
    }

    /**
     * Shelves a book handed over by another app.
     *
     * A book opened from a file manager, a download or an attachment
     * arrives as a bare URI with no library row behind it. Without one
     * it can be read but never seen again: the shelf is built from
     * `books`, and so is every offer to send a book to a server.
     *
     * The URI usually cannot simply be kept. A `VIEW` grant lasts as
     * long as the task that received it and is only persistable when the
     * sender said so, which most file managers do not. Storing it anyway
     * would shelve a book that stops opening at the next reboot, so the
     * bytes are copied in when the grant will not persist.
     *
     * Returns the shelved book, or null when it could not be read at
     * all — in which case the caller should still open what it was
     * given, because failing to file a book is no reason to refuse to
     * show it.
     */
    suspend fun importExternalBook(uri: Uri): Book? {
        val incoming = uri.toAbsoluteUrl() ?: return null
        bookDao.getByUrl(incoming.toString())?.let { return it }

        // A real file is already as permanent as the library is, and a
        // grant that survives is just as good. Either way the book is
        // indexed where it lies rather than copied.
        val keepsWorking = incoming.toString().startsWith("file:") || persistPermission(uri)
        if (keepsWorking) return indexBook(incoming, source = null)

        val copied = copyIntoLibrary(uri) ?: return null
        // Content addressing makes this idempotent: the same file opened
        // twice lands on the same path, so the second time finds the row
        // the first one made instead of shelving the book again.
        bookDao.getByUrl(copied.toString())?.let { return it }
        return indexBook(copied, source = null)
    }

    /**
     * Whether this URI can still be read once the task that granted it
     * is gone. Only the sender can make that possible, so being refused
     * is the ordinary case and not worth a warning.
     */
    private fun persistPermission(uri: Uri): Boolean =
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure {
            Log.i(TAG, "The grant on $uri cannot be persisted; copying the book instead")
        }.isSuccess

    /**
     * Copies a book into the app's own storage, named after the digest
     * of its contents, and returns where it landed.
     *
     * It is spooled to a temporary file first because the name is not
     * known until the last byte has been read. A file that is already
     * there is the same book by definition, so the copy is dropped.
     */
    private suspend fun copyIntoLibrary(uri: Uri): AbsoluteUrl? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val spool = File.createTempFile("import", ".part", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val read = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    spool.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                    true
                } ?: false
            }.getOrElse {
                Log.w(TAG, "Could not read $uri to bring it into the library", it)
                false
            }
            if (!read) return@withContext null

            val target = File(dir, importedFileName(digest.digest()))
            if (target.exists()) {
                spool.delete()
            } else if (!spool.renameTo(target)) {
                Log.w(TAG, "Could not put the imported book at $target")
                return@withContext null
            }
            Uri.fromFile(target).toAbsoluteUrl()
        } finally {
            if (spool.exists()) spool.delete()
        }
    }

    suspend fun markOpened(url: String) {
        bookDao.touchLastOpened(url, System.currentTimeMillis())
    }

    /** Indexes the metadata that only becomes available once a download is on the device. */
    suspend fun indexDownloadedFile(fileUrl: AbsoluteUrl, identity: String) {
        val asset = assetRetriever.retrieve(fileUrl).getOrElse { return }
        val publication = publicationOpener.open(asset, allowUserInteraction = false)
            .getOrElse {
                asset.close()
                return
        }
        try {
            val series = seriesOf(publication)
            bookDao.fillSeriesFromFile(identity, series.name, series.index)
            saveCover(publication, identity)?.let { bookDao.setCoverPath(identity, it) }
        } finally {
            publication.close()
        }
    }

    private suspend fun scanFolder(treeUri: Uri) = withContext(Dispatchers.IO) {
        val found = findEpubs(treeUri) ?: return@withContext
        val knownUrls = bookDao.urlsForSource(treeUri.toString()).toMutableSet()
        // Everything on the shelf, read once. Looking each file up as it
        // was met meant a query per book per scan, on every cold start.
        val knownBooks = bookDao.allOnce().associateBy { it.url }
        val foundUrls = mutableSetOf<String>()

        for (file in found) {
            val url = file.uri.toAbsoluteUrl() ?: continue
            foundUrls += url.toString()
            val existing = knownBooks[url.toString()]
            when {
                existing == null -> indexBook(url, source = treeUri.toString(), file.modifiedAt)
                // The path is the same but the file behind it is not, so the
                // title and cover we cached are no longer the book's.
                file.modifiedAt != null && existing.fileModifiedAt != file.modifiedAt ->
                    reindexBook(url, file.modifiedAt, existing.workId)
            }
        }

        bookRemoval.deleteByUrls((knownUrls - foundUrls).toList())
    }

    private class ScannedEpub(val uri: Uri, val modifiedAt: Long?)

    /**
     * Walks a folder with one query per directory, or null when the
     * folder cannot be read at all.
     *
     * `DocumentFile` answers every question about a file — its name, its
     * type, when it changed — with a ContentResolver round trip of its
     * own, which made scanning cost four or five IPC calls per file.
     * Asking the provider for its children with a projection gets the
     * whole directory in one go.
     *
     * Null rather than empty on failure, because the two mean opposite
     * things to the caller: an empty folder prunes every book that came
     * from it, and a folder that would not open must prune nothing.
     */
    private fun findEpubs(treeUri: Uri): List<ScannedEpub>? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val found = mutableListOf<ScannedEpub>()

        fun walk(documentId: String): Boolean {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val cursor = runCatching {
                context.contentResolver.query(children, projection, null, null, null)
            }.getOrNull() ?: return false
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1).orEmpty()
                    val mime = it.getString(2)
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> walk(id)
                        mime == "application/epub+zip" ||
                            name.endsWith(".epub", ignoreCase = true) -> {
                            val modifiedAt = (if (it.isNull(3)) 0L else it.getLong(3))
                                .takeIf { at -> at > 0 }
                            found += ScannedEpub(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                modifiedAt,
                            )
                        }
                    }
                }
            }
            return true
        }

        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return null
        return if (walk(rootId)) found else null
    }

    /**
     * Re-reads a file that changed on disk.
     *
     * The library row stays, so a book rewritten in place keeps your
     * place in it, everything you marked in it and when you added it.
     * If the file turns out to hold a different book, none of that
     * describes anything that is still there, so it goes.
     */
    private suspend fun reindexBook(
        url: AbsoluteUrl,
        modifiedAt: Long?,
        previousWorkId: String?,
    ) {
        val asset = assetRetriever.retrieve(url).getOrElse { return }
        val publication = publicationOpener.open(asset, allowUserInteraction = false)
            .getOrElse {
                asset.close()
                return
            }
        try {
            val title = publication.metadata.title
                ?: url.filename?.removeSuffix(".epub")
                ?: "Untitled"
            val author = publication.metadata.authors
                .joinToString(", ") { it.name }
                .ifBlank { null }
            val workId = workIdOf(publication.metadata.identifier, title, author)
            val series = seriesOf(publication)
            bookDao.refreshIndexedFile(
                url = url.toString(),
                title = title,
                author = author,
                coverPath = saveCover(publication, url.toString()),
                fileModifiedAt = modifiedAt,
                workId = workId,
                seriesName = series.name,
                seriesIndex = series.index,
            )
            if (!isSameWork(previousWorkId, workId)) {
                Log.i(TAG, "A different book took over a path; starting it fresh")
                bookRemoval.contentReplaced(url.toString())
            }
        } finally {
            publication.close()
        }
    }

    private suspend fun indexBook(
        url: AbsoluteUrl,
        source: String?,
        modifiedAt: Long? = null,
    ): Book? {
        val asset = assetRetriever.retrieve(url).getOrElse { return null }
        val publication = publicationOpener.open(asset, allowUserInteraction = false)
            .getOrElse {
                asset.close()
                return null
            }
        return try {
            val title = publication.metadata.title
                ?: url.filename?.removeSuffix(".epub")
                ?: "Untitled"
            val author = publication.metadata.authors
                .joinToString(", ") { it.name }
                .ifBlank { null }
            val series = seriesOf(publication)
            val book = Book(
                url = url.toString(),
                title = title,
                author = author,
                coverPath = saveCover(publication, url.toString()),
                source = source,
                addedAt = System.currentTimeMillis(),
                lastOpenedAt = null,
                fileModifiedAt = modifiedAt,
                workId = workIdOf(publication.metadata.identifier, title, author),
                seriesName = series.name,
                seriesIndex = series.index,
                fileSeriesName = series.name,
                fileSeriesIndex = series.index,
                seriesChecked = true,
            )
            val id = bookDao.upsert(book)
            book.copy(id = id)
        } finally {
            publication.close()
        }
    }

    /**
     * What the file itself says about its series.
     *
     * Readium has already done the awkward part: EPUB 3 states this with
     * a collection of type `series` and a group position, calibre wrote
     * it for years as two bare `calibre:series` meta tags, and both
     * arrive here as the same list. Which matters, because a library
     * exported from calibre is exactly the library this feature is for.
     *
     * Only the first series is kept. A book can declare several, but the
     * shelf has one place to put it and the first is the one calibre and
     * every EPUB tool writes as the real one.
     */
    private fun seriesOf(publication: Publication): SeriesMetadata {
        val series = publication.metadata.belongsToSeries.firstOrNull()
            ?: return SeriesMetadata.None
        val name = series.name.trim().takeIf { it.isNotEmpty() }
            ?: return SeriesMetadata.None
        return SeriesMetadata(name = name, index = series.position)
    }

    /**
     * Reads the series out of books that were indexed before the library
     * knew what a series was.
     *
     * Nothing else will ever look at them: a file is only re-read when
     * its modification time moves, and these have not been touched since
     * the day they arrived. Without this pass the feature is empty on
     * upgrade for every book already on the shelf, which is all of them.
     *
     * A book that was read is marked as checked whatever the answer, so
     * a shelf of standalone novels is walked once and never again. A
     * book that could not be read is left unchecked and skipped for the
     * rest of the pass: a card that was not mounted, or a provider that
     * was busy, is a reason to ask again later, and marking it would
     * hide the book's series for good over a moment's bad luck.
     *
     * The work is done in batches and the reader is not waiting on it:
     * the shelf is already drawn, and series appear on it as the files
     * are read.
     *
     * It runs until there is nothing left rather than stopping at a
     * ceiling. There is only one caller, in the view model's `init`, and
     * that view model outlives every trip out of the library and back,
     * so a run that gave up early would leave the rest of the shelf
     * without series until the process was killed. The pause between
     * batches is what keeps a long catch-up from holding the disk: the
     * cost of a big library is that it takes a while, not that the app
     * is busy while it does.
     */
    suspend fun backfillSeries(batchSize: Int = BACKFILL_BATCH) = withContext(Dispatchers.IO) {
        // Skipping these locally is what stops the pass from claiming
        // the same unreadable batch over and over: they stay unchecked
        // in the database, so the next run does try them again.
        val unreadable = mutableSetOf<String>()
        while (true) {
            // The DAO cannot know which failures belong only to this
            // run, so read past them before filtering. Otherwise a full
            // first page of unavailable files would be returned forever
            // and hide every readable book after it.
            val batch = bookDao.needingSeriesCheck(batchSize + unreadable.size)
                .asSequence()
                .filter { it.url !in unreadable }
                .take(batchSize)
                .toList()
            if (batch.isEmpty()) return@withContext
            for (book in batch) {
                // Cancellation here is ordinary: the library screen went
                // away. What has been read is written, and the next run
                // carries on from the next unchecked book.
                currentCoroutineContext().ensureActive()
                val fileUrl = book.openableUrl?.let { AbsoluteUrl(it) }
                if (fileUrl == null) {
                    // Nothing here to open. It has been looked at as far
                    // as it can be; a download will index it properly.
                    bookDao.fillSeriesFromFile(book.url, null, null)
                    continue
                }
                val series = readSeries(fileUrl)
                if (series == null) {
                    unreadable += book.url
                    continue
                }
                bookDao.fillSeriesFromFile(book.url, series.name, series.index)
            }
            delay(BACKFILL_PAUSE_MS)
        }
    }

    /**
     * Opens a book only far enough to ask it what series it is in.
     *
     * Null when the file could not be read at all, which is not the same
     * answer as [SeriesMetadata.None] — that one means the book was read
     * and belongs to no series.
     */
    private suspend fun readSeries(url: AbsoluteUrl): SeriesMetadata? {
        val asset = assetRetriever.retrieve(url).getOrElse { return null }
        val publication = publicationOpener.open(asset, allowUserInteraction = false)
            .getOrElse {
                asset.close()
                return null
            }
        return try {
            seriesOf(publication)
        } finally {
            publication.close()
        }
    }

    private suspend fun saveCover(publication: Publication, identity: String): String? {
        val cover = publication.cover() ?: return null
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, "${sha1Hex(identity)}.jpg")
        return withContext(Dispatchers.IO) {
            runCatching {
                file.outputStream().use { cover.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                file.absolutePath
            }.getOrNull()
        }
    }

    private companion object {
        const val TAG = "local-library"

        /** How many books to claim from the database at a time. */
        const val BACKFILL_BATCH = 32

        /**
         * A breath between batches, so that catching up on a library of
         * thousands stays in the background where it belongs instead of
         * competing with the covers the reader is waiting for.
         */
        const val BACKFILL_PAUSE_MS = 250L
    }
}

internal fun sha1Hex(value: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

/**
 * What an imported book is called once it is in the app's own storage.
 *
 * The name is the digest of the bytes, so the same book brought in
 * twice — from a second download, or the same file opened again after
 * the grant on it lapsed — resolves to one file and one shelf entry
 * rather than accumulating copies.
 */
internal fun importedFileName(digest: ByteArray): String =
    digest.joinToString("", postfix = ".epub") { "%02x".format(it) }
