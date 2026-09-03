package com.chmouel.liseur.data.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener

/**
 * What became of a book the reader picked by hand.
 *
 * Shelving one is not always adding one: the same file can already be
 * on the shelf under another name, and the reader should be told that
 * rather than shown a second copy of a book they already have.
 */
sealed interface ImportResult {
    /** A book that was not here before, now indexed. */
    data class Added(val book: Book) : ImportResult

    /** The library entry this file already has. Nothing was added. */
    data class AlreadyShelved(val book: Book) : ImportResult

    /** The file could not be read as a book at all. */
    data object Failed : ImportResult
}

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
    private val fingerprints: BookFingerprintStore,
) {
    val books: Flow<List<Book>> = bookDao.observeAll()
    val mostRecent: Flow<Book?> = bookDao.observeMostRecent()
    val folders: Flow<List<LibraryFolder>> = folderDao.observeAll()

    /**
     * Held by everything that can put a local book on the shelf.
     *
     * Recognising a file and inserting it are two steps, and a folder
     * scan runs itself on every cold start while the reader is free to
     * pick a file by hand at the same moment. Both would look, both
     * would find nothing, and both would insert — the very duplicate
     * this class now exists to prevent, only harder to reproduce.
     */
    private val importLock = Mutex()

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

    suspend fun removeFolder(url: String) = importLock.withLock {
        folderDao.delete(url)
        bookRemoval.deleteByUrls(bookDao.urlsForSource(url))
    }

    /**
     * Takes a book off the shelf and leaves its file alone.
     *
     * The other way out of the library deletes the file first, which is
     * far too much to ask of someone who only wanted to be rid of a
     * duplicate entry (issue #147).
     *
     * The row is marked, not deleted. The reader's place, their marks,
     * the time they spent, what a server has been told about it and
     * where they filed it by hand all hang off this row, and all of it
     * is theirs whether the entry is showing or not. Marking is also the
     * whole of the removal and the whole of the way back: one write
     * each, so there is no moment where the book is neither on the shelf
     * nor on the list of what was taken off it.
     */
    suspend fun removeFromLibrary(book: Book) {
        bookDao.setHiddenAt(book.url, System.currentTimeMillis())
    }

    /**
     * Puts a hidden book back on the shelf.
     *
     * Nothing is read and nothing is rebuilt: the entry was never taken
     * apart, so showing it again is all there is to do. A file that has
     * gone missing in the meantime is pruned by the next scan of its
     * folder, exactly as it would have been had it never been hidden.
     */
    suspend fun unhide(bookUrl: String) {
        bookDao.setHiddenAt(bookUrl, null)
    }

    /** Every book taken off the shelf, most recently taken off first. */
    val hidden: Flow<List<Book>> = bookDao.observeHidden()

    /** Rescans every library folder, adding new books and pruning deleted files. */
    suspend fun rescanAll() {
        for (folder in folderDao.getAll()) {
            scanFolder(Uri.parse(folder.url))
        }
    }

    /**
     * Adds a single, individually picked book to the library.
     *
     * The file may well be one the library already has. A folder scan
     * writes the URI it built against the tree it was walking, and the
     * single-file picker hands back a bare document URI, so one EPUB in
     * a watched folder has two spellings and matching on the URL alone
     * shelved it twice (issue #147). What it is, rather than what it is
     * called, decides.
     */
    suspend fun importBook(uri: Uri): ImportResult = importLock.withLock {
        val url = uri.toAbsoluteUrl() ?: return@withLock ImportResult.Failed
        alreadyShelved(url)?.let { return@withLock shelveAgainOrReport(it) }
        indexBook(url, source = null)
            ?.let { ImportResult.Added(it) }
            ?: ImportResult.Failed
    }

    /**
     * What to answer about a file the library already has an entry for.
     *
     * Picking a book that was taken off the shelf is asking for it back,
     * so it comes back rather than being reported as already there —
     * under the URL it has always had, which is where its place and its
     * marks are.
     *
     * The caller holds [importLock].
     */
    private suspend fun shelveAgainOrReport(book: Book): ImportResult =
        if (book.hidden) {
            unhide(book.url)
            ImportResult.Added(bookDao.getByUrl(book.url) ?: book)
        } else {
            ImportResult.AlreadyShelved(book)
        }

    /**
     * The library entry that already stands for this file, or null.
     *
     * Three questions, cheapest first, because the last one reads the
     * whole file:
     *
     * 1. The same URL, which is all this used to ask.
     * 2. The same document: authority and document id, which sees
     *    through the two spellings SAF gives one file.
     * 3. The same bytes, for a genuine second copy sitting somewhere
     *    else. Hashing is confined to books that already claim to be
     *    the same work, so the usual answer costs one metadata read and
     *    no hashing at all, and a matching work id on its own is never
     *    enough — two editions of one book are two books.
     */
    private suspend fun alreadyShelved(url: AbsoluteUrl): Book? {
        val urlText = url.toString()
        bookDao.getByUrl(urlText)?.let { return it }

        val shelf = bookDao.allOnce()
        documentIdentity(urlText)?.let { identity ->
            shelf.firstOrNull { it.url != urlText && documentIdentity(it.url) == identity }
                ?.let { return it }
        }
        return sameContentOnShelf(url, shelf)
    }

    private suspend fun sameContentOnShelf(url: AbsoluteUrl, shelf: List<Book>): Book? {
        val workId = workIdOfFile(url) ?: return null
        val candidates = shelf.filter {
            it.workId == workId && it.url != url.toString() && it.openableUrl != null
        }
        if (candidates.isEmpty()) return null

        val sha256 = fingerprints.compute(url.toString())?.sha256 ?: return null
        return candidates.firstOrNull { fingerprints.of(it)?.sha256 == sha256 }
    }

    /** What work a file holds, without shelving it. */
    private suspend fun workIdOfFile(url: AbsoluteUrl): String? {
        val asset = assetRetriever.retrieve(url).getOrElse { return null }
        val publication = publicationOpener.open(asset, allowUserInteraction = false)
            .getOrElse {
                asset.close()
                return null
            }
        return try {
            workIdOf(
                publication.metadata.identifier,
                publication.metadata.title,
                publication.metadata.authors.joinToString(", ") { it.name }.ifBlank { null },
            )
        } finally {
            publication.close()
        }
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
    suspend fun importExternalBook(uri: Uri): Book? = importLock.withLock {
        val incoming = uri.toAbsoluteUrl() ?: return@withLock null
        // A book arriving from a file manager is very often one the
        // library already has under the spelling a folder scan gave it,
        // so ask what the file is and not only what it is called.
        alreadyShelved(incoming)?.let {
            // Sharing in a book that was taken off the shelf asks for it
            // back just as plainly as picking it does.
            if (it.hidden) unhide(it.url)
            return@withLock bookDao.getByUrl(it.url) ?: it
        }

        // A real file is already as permanent as the library is, and a
        // grant that survives is just as good. Either way the book is
        // indexed where it lies rather than copied.
        val keepsWorking = incoming.toString().startsWith("file:") || persistPermission(uri)
        if (keepsWorking) return@withLock indexBook(incoming, source = null)

        val copied = copyIntoLibrary(uri) ?: return@withLock null
        // Content addressing makes this idempotent: the same file opened
        // twice lands on the same path, so the second time finds the row
        // the first one made instead of shelving the book again.
        bookDao.getByUrl(copied.toString())?.let { return@withLock it }
        indexBook(copied, source = null)
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
        importLock.withLock {
            // Walking the folder takes long enough for it to have been
            // removed in the meantime, and shelving the walk's results
            // then would file books under a folder nothing watches any
            // more: never scanned again, and never pruned.
            if (folderDao.getAll().none { it.url == treeUri.toString() }) return@withLock
            shelve(treeUri, found)
        }
    }

    private suspend fun shelve(treeUri: Uri, found: List<ScannedEpub>) {
        val knownUrls = bookDao.urlsForSource(treeUri.toString()).toMutableSet()
        // Everything on the shelf, read once. Looking each file up as it
        // was met meant a query per book per scan, on every cold start.
        val shelf = bookDao.allOnce()
        val knownBooks = shelf.associateBy { it.url }
        // The same file under its other spelling: what a duplicate entry
        // made by an older version looks like.
        val byIdentity = shelf.groupBy { documentIdentity(it.url) }
        val foundUrls = mutableSetOf<String>()
        val duplicates = mutableListOf<String>()

        for (file in found) {
            val url = file.uri.toAbsoluteUrl() ?: continue
            val urlText = url.toString()
            val identity = documentIdentity(urlText)
            foundUrls += urlText
            val aliases = identity
                ?.let { id -> byIdentity[id].orEmpty().filter { it.url != urlText } }
                .orEmpty()
            // A row under the file's other spelling must survive this
            // scan whatever its source says, or adopting it below would
            // hand the book to a row that the pruning then deletes.
            aliases.forEach { foundUrls += it.url }
            val existing = knownBooks[urlText]
            when {
                existing != null -> {
                    // Any other spelling of a file the shelf already has
                    // under this one is a duplicate an older version left.
                    // Not one the reader took off the shelf, though:
                    // that entry is theirs to put back, and deleting it
                    // here would take it off the hidden list too.
                    aliases.filterNot { it.hidden }.forEach { duplicates += it.url }
                    // The path is the same but the file behind it is not, so the
                    // title and cover we cached are no longer the book's.
                    if (file.modifiedAt != null && existing.fileModifiedAt != file.modifiedAt) {
                        reindexBook(url, file.modifiedAt, existing.workId)
                    }
                }
                // The file is on the shelf already, under the name the
                // single-file picker gave it. That row *is* the book —
                // its URL is what the reader's place, marks and time all
                // hang off — so the scan takes it as the entry for this
                // file instead of adding a second one. Shelving the
                // scanned spelling and then tidying up afterwards would
                // only work while the picked entry had nothing on it,
                // which is exactly the entry that has been read.
                aliases.isNotEmpty() -> {
                    val alias = aliases.first()
                    // The entry keeps its URL but joins the folder, so
                    // that it is pruned when the file goes and goes with
                    // the folder when that is removed. A book picked by
                    // hand belongs to nothing and would otherwise be
                    // left behind by both.
                    //
                    // Only when it belongs to nothing. Two watched
                    // folders can hold the same file — one inside the
                    // other, most obviously — and taking the book off
                    // whichever scanned first would let removing that
                    // folder delete a book the other still holds.
                    if (alias.source == null) {
                        bookDao.setSource(alias.url, treeUri.toString())
                    }
                    if (file.modifiedAt != null && alias.fileModifiedAt != file.modifiedAt) {
                        Uri.parse(alias.url).toAbsoluteUrl()
                            ?.let { reindexBook(it, file.modifiedAt, alias.workId) }
                    }
                }
                else -> indexBook(url, source = treeUri.toString(), file.modifiedAt)
            }
        }

        bookRemoval.deleteByUrls((knownUrls - foundUrls).toList())
        bookRemoval.dropUntouchedDuplicates(duplicates)
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
        // The whole thing, not just the write. `publication.cover()`
        // reads an entry out of the archive and decodes it, and this is
        // reached from `importBook`, which the library view model
        // launches in `viewModelScope` — so without this the shelf is
        // drawn on the thread doing the decoding.
        return withContext(Dispatchers.IO) {
            val cover = publication.cover()
                ?: coverNamedCover(publication)
                ?: return@withContext null
            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File(dir, "${sha1Hex(identity)}.jpg")
            runCatching {
                file.outputStream().use { cover.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                file.absolutePath
            }.getOrNull()
        }
    }

    /**
     * The cover of a book that never says it has one.
     *
     * Readium finds a cover by the two routes a publication can declare
     * it: the EPUB 3 `cover-image` property, and the EPUB 2 `<meta
     * name="cover">` pointer at a manifest id. A book that uses neither
     * has no cover as far as the format is concerned, and some do —
     * their artwork is reachable only because the first page of the
     * book happens to be an XHTML page that displays it. That is why
     * such a book looks fine in a reader and turns up blank on a shelf.
     *
     * So when nothing usable comes back, fall back to the near-universal
     * convention and take an image the book called `cover`. "Nothing
     * usable" is the honest description and it is deliberate: a
     * declaration that points at an entry which is missing or will not
     * decode leaves Readium with no cover either, and falling through to
     * the convention is a better answer than a blank tile. liseur-sync's
     * candidate list behaves the same way for the same reason.
     *
     * It is only ever reached once the declared routes have come back
     * empty, because a declaration is a statement and a filename is a
     * guess. Being a guess is also why the match is exact and why the
     * bytes behind it are treated as hostile: see [isNamedCover] and
     * [decodeBounded].
     *
     * One asymmetry with the server is worth naming: liseur-sync also
     * matches a manifest *id* of `cover`, so it resolves
     * `<item id="cover" href="front.jpg"/>` where this does not.
     * Readium's `Link` does not carry the manifest id, so there is
     * nothing here to match on. The filename is the common case by a
     * long way, and disagreeing costs a cover rather than correctness.
     */
    private suspend fun coverNamedCover(publication: Publication): Bitmap? {
        // Both collections, because Readium splits the manifest into the
        // spine and everything else, and an image is occasionally a
        // spine item. Resource order and then spine order: neither is
        // the manifest's own order, but both are fixed, so a book with
        // two candidates always resolves to the same one.
        val link = (publication.resources + publication.readingOrder).firstOrNull {
            it.mediaType?.isBitmap == true && isNamedCover(it.url().filename)
        } ?: return null
        // Off the main thread by the time anything is read or decoded —
        // belt and braces with [saveCover], which already dispatches,
        // because a suspend function that reads and decodes should not
        // depend on its caller to be safe.
        return withContext(Dispatchers.IO) {
            val resource = publication.get(link) ?: return@withContext null
            val bytes = try {
                // Asking the range rather than trusting `length()`, which
                // Readium documents as a hint that "might not reflect the
                // actual bytes length". Out-of-range reads are clamped, so
                // a small entry comes back whole, and one byte past the
                // limit is enough to tell a file at the limit from one
                // over it.
                resource.read(0 until MAX_COVER_BYTES + 1).getOrNull()
            } finally {
                resource.close()
            }
            if (bytes == null || bytes.size > MAX_COVER_BYTES) null else decodeBounded(bytes)
        }
    }

    /**
     * Turns bytes chosen by their name into a bitmap of a sane size.
     *
     * A declared cover was at least pointed at by the book; this one was
     * picked because of what it is called, out of an archive that may
     * have arrived from anywhere. An image header can claim any
     * dimensions it likes, and a few kilobytes of it can ask for
     * gigabytes of pixels, so the header is read on its own first — with
     * `inJustDecodeBounds`, which allocates nothing — and the real
     * decode is subsampled down to something a shelf can use.
     */
    private fun decodeBounded(bytes: ByteArray): Bitmap? = runCatching {
        val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, header)
        val sample = coverSampleSize(header.outWidth, header.outHeight) ?: return@runCatching null
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

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

        /**
         * The most encoded bytes worth reading for a guessed cover.
         *
         * Cover artwork runs to a few hundred kilobytes; this is roomy
         * enough that no real book meets it and small enough that a file
         * named `cover.jpg` to be read cannot cost much.
         */
        const val MAX_COVER_BYTES = 16L * 1024 * 1024
    }
}

/**
 * The most pixels a cover is decoded to.
 *
 * The bound is on area rather than on either edge, because what is
 * being guarded is the allocation: at four bytes a pixel this is 16MB,
 * and an ordinary portrait cover of 1600 by 2400 comes to less, so a
 * real book is decoded whole and only the absurd is brought down.
 */
private const val MAX_COVER_PIXELS = 2048L * 2048

/**
 * How much to subsample an image of [width] by [height], or null when
 * the header did not describe an image at all.
 *
 * `BitmapFactory` rounds `inSampleSize` down to a power of two, so
 * powers of two are what this returns — asking for 3 and silently
 * getting 2 would put the bound somewhere other than where it reads.
 * A header that could not be parsed leaves the dimensions at -1, which
 * is the null: there is nothing to decode and no point trying. A
 * nonsensical bound is the same answer, because there is no sample size
 * that would satisfy it and looking for one does not end.
 */
internal fun coverSampleSize(width: Int, height: Int, max: Long = MAX_COVER_PIXELS): Int? {
    if (width <= 0 || height <= 0 || max <= 0) return null
    var sample = 1
    // Long, and both edges floored at one: a 100000 by 1 image samples
    // its short edge to zero long before its long edge is small enough,
    // and a zero would make the product look acceptable.
    while ((width / sample).coerceAtLeast(1).toLong() *
        (height / sample).coerceAtLeast(1) > max
    ) {
        sample *= 2
    }
    return sample
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

/**
 * Whether a file inside a book is named as its cover.
 *
 * The extension is dropped before comparing, so `cover.jpg`, `cover.png`
 * and a bare `cover` all answer the same, and the comparison ignores
 * case because the convention is written every way round. The match is
 * deliberately exact after that: `cover-page`, `covers` and
 * `frontcover` are not covers, and a book with several images would
 * otherwise be a lottery.
 *
 * Kept out of the repository, and out of Android, so the rule that
 * decides what a reader sees on the shelf can be tested without a
 * device. It mirrors `coverStem` in liseur-sync.
 */
internal fun isNamedCover(filename: String?): Boolean {
    val name = filename ?: return false
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    return stem.equals("cover", ignoreCase = true)
}
