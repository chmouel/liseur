package com.chmouel.liseur.data.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.LibraryFolder
import com.chmouel.liseur.data.db.LibraryFolderDao
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
) {
    val books: Flow<List<Book>> = bookDao.observeAll()
    val mostRecent: Flow<Book?> = bookDao.observeMostRecent()
    val folders: Flow<List<LibraryFolder>> = folderDao.observeAll()

    suspend fun addFolder(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
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
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
        val knownUrls = bookDao.urlsForSource(treeUri.toString()).toMutableSet()
        val foundUrls = mutableSetOf<String>()

        fun walk(dir: DocumentFile): List<DocumentFile> =
            dir.listFiles().flatMap { file ->
                when {
                    file.isDirectory -> walk(file)
                    file.isFile && file.isEpub() -> listOf(file)
                    else -> emptyList()
                }
            }

        for (file in walk(root)) {
            val url = file.uri.toAbsoluteUrl() ?: continue
            foundUrls += url.toString()
            if (url.toString() !in knownUrls && bookDao.getByUrl(url.toString()) == null) {
                indexBook(url, source = treeUri.toString())
            }
        }

        bookDao.deleteByUrls((knownUrls - foundUrls).toList())
    }

    private fun DocumentFile.isEpub(): Boolean =
        type == "application/epub+zip" || name.orEmpty().endsWith(".epub", ignoreCase = true)

    private suspend fun indexBook(url: AbsoluteUrl, source: String?): Book? {
        val asset = assetRetriever.retrieve(url).getOrElse { return null }
        val publication = publicationOpener.open(asset, allowUserInteraction = false)
            .getOrElse {
                asset.close()
                return null
            }
        return try {
            val book = Book(
                url = url.toString(),
                title = publication.metadata.title
                    ?: url.filename?.removeSuffix(".epub")
                    ?: "Untitled",
                author = publication.metadata.authors
                    .joinToString(", ") { it.name }
                    .ifBlank { null },
                coverPath = saveCover(publication, url.toString()),
                source = source,
                addedAt = System.currentTimeMillis(),
                lastOpenedAt = null,
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
}

internal fun sha1Hex(value: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
