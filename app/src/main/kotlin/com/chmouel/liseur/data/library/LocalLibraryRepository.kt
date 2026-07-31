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
import com.chmouel.liseur.data.db.BookAnnotationDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.domain.isSameWork
import com.chmouel.liseur.domain.workIdOf
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
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
    private val progressDao: ReadingProgressDao,
    private val annotationDao: BookAnnotationDao,
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
        bookDao.deleteByUrls(bookDao.urlsForSource(url))
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

    suspend fun markOpened(url: String) {
        bookDao.touchLastOpened(url, System.currentTimeMillis())
    }

    /**
     * Pulls the cover out of a book that has just been downloaded, so the
     * library keeps showing it once the server is out of reach. [identity]
     * is the book's permanent URL, not the file's, so the cover survives
     * the download being removed.
     */
    suspend fun extractCover(fileUrl: AbsoluteUrl, identity: String): String? {
        val asset = assetRetriever.retrieve(fileUrl).getOrElse { return null }
        val publication = publicationOpener.open(asset, allowUserInteraction = false)
            .getOrElse {
                asset.close()
                return null
            }
        return try {
            saveCover(publication, identity)
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

        bookDao.deleteByUrls((knownUrls - foundUrls).toList())
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
            bookDao.refreshIndexedFile(
                url = url.toString(),
                title = title,
                author = author,
                coverPath = saveCover(publication, url.toString()),
                fileModifiedAt = modifiedAt,
                workId = workId,
            )
            if (!isSameWork(previousWorkId, workId)) {
                Log.i(TAG, "A different book took over a path; starting it fresh")
                progressDao.forget(url.toString())
                annotationDao.deleteForBook(url.toString())
                bookDao.forgetReadingHistory(url.toString())
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
            )
            val id = bookDao.upsert(book)
            book.copy(id = id)
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
    }
}

internal fun sha1Hex(value: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
