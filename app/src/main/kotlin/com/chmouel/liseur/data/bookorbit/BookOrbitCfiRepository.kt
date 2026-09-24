package com.chmouel.liseur.data.bookorbit

import android.net.Uri
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.chmouel.liseur.data.db.BookOrbitBinding
import com.chmouel.liseur.data.db.BookOrbitBindingState
import com.chmouel.liseur.data.db.BookOrbitCfiRecord
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.openableUri
import kotlinx.coroutines.Dispatchers
import org.readium.r2.shared.util.toAbsoluteUrl
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class BookOrbitCfiContext(
    val request: BookOrbitRequestContext,
    val bookUrl: String,
    val bookId: Long,
    val fileId: Long,
    val bindingRevision: Long,
) {
    fun matches(binding: BookOrbitBinding?): Boolean =
        binding != null && binding.accountKey == request.accountKey &&
            binding.bookUrl == bookUrl && binding.bookId == bookId &&
            binding.fileId == fileId && binding.revision == bindingRevision &&
            binding.fileFormat.equals("epub", ignoreCase = true) &&
            binding.stateValue in setOf(BookOrbitBindingState.SELECTED, BookOrbitBindingState.DOWNLOADED)
}

data class BookOrbitOpenedEpub internal constructor(
    val context: BookOrbitCfiContext,
    val publication: BookOrbitEpubPackage,
    val file: File,
    val openedUrl: String,
    val length: Long,
    val modifiedAt: Long,
    /**
     * For a book adopted from an upload: the document or file the reader
     * opened, and what it reported when [file] was verified from it.
     * Null for a book downloaded from BookOrbit.
     */
    val source: String? = null,
    val sourceStamp: BookOrbitAdoptedSource.Stamp? = null,
    /** Whether [file] is a private copy of [source] that closing must delete. */
    val spooled: Boolean = false,
)

/** No sync registration: later phases can retain a fetched CFI without adopting it. */
class BookOrbitCfiRepository(
    private val database: LiseurDatabase,
    private val sources: BookOrbitAdoptedSource = BookOrbitAdoptedSource(null),
    /** Where a document opened through a content URI is copied to be parsed. */
    private val spoolDir: File? = null,
) {
    suspend fun openedIfConnected(
        bookUrl: String,
        openedUrl: String,
        fileFor: (String) -> File,
        own: (BookOrbitOpenedEpub) -> Unit = {},
    ): BookOrbitOpenedEpub? {
        val request = database.remoteServerDao().get()?.let(BookOrbitRequestContext::from)
            ?: return null
        if (database.bookOrbitBindingDao().get(request.accountKey, bookUrl) == null) return null
        return openedPackage(capture(bookUrl), openedUrl, fileFor, own)
    }

    suspend fun capture(bookUrl: String): BookOrbitCfiContext = database.withTransaction {
        val server = database.remoteServerDao().get()
        val request = server?.let(BookOrbitRequestContext::from) ?: stale()
        val binding = database.bookOrbitBindingDao().get(request.accountKey, bookUrl) ?: stale()
        BookOrbitCfiContext(request, bookUrl, binding.bookId, binding.fileId ?: stale(), binding.revision)
            .also { if (!it.matches(binding)) stale() }
    }

    suspend fun check(context: BookOrbitCfiContext) = database.withTransaction {
        checkCurrent(context)
    }

    /**
     * Parses only the app-owned download that the reader opened, not an EPUB
     * supplied separately or a catalog's current primary file.
     */
    suspend fun openedPackage(
        context: BookOrbitCfiContext,
        openedUrl: String,
        fileFor: (String) -> File,
        /**
         * Handed a private copy the moment it is verified, before this
         * returns: a caller cancelled on the way back never sees the
         * result, and would otherwise leave the copy behind.
         */
        own: (BookOrbitOpenedEpub) -> Unit = {},
    ): BookOrbitOpenedEpub = withContext(Dispatchers.IO) {
        adoptedSource(context, openedUrl)?.let {
            return@withContext openedAdopted(context, openedUrl, it, own)
        }
        val file = openedFile(context, openedUrl, fileFor)
        val length = file.length()
        val modified = file.lastModified()
        val publication = BookOrbitEpubPackage.parse(file)
        if (openedFile(context, openedUrl, fileFor) != file ||
            file.length() != length || file.lastModified() != modified
        ) stale()
        BookOrbitOpenedEpub(context, publication, file, openedUrl, length, modified)
    }

    suspend fun originalDocument(
        opened: BookOrbitOpenedEpub,
        href: String,
        fileFor: (String) -> File,
    ): org.w3c.dom.Document? = withContext(Dispatchers.IO) {
        if (opened.sourceStamp != null) {
            checkAdopted(opened)
            val document = BookOrbitEpubPackage.spineDocument(opened.file, opened.publication, href)
            checkAdopted(opened)
            return@withContext document
        }
        val current = openedFile(opened.context, opened.openedUrl, fileFor)
        if (current != opened.file || current.length() != opened.length ||
            current.lastModified() != opened.modifiedAt
        ) stale()
        val document = BookOrbitEpubPackage.spineDocument(current, opened.publication, href)
        if (openedFile(opened.context, opened.openedUrl, fileFor) != current ||
            current.length() != opened.length || current.lastModified() != opened.modifiedAt
        ) stale()
        document
    }

    /** Deletes the private copy an opened document was read from, if there is one. */
    fun release(opened: BookOrbitOpenedEpub) {
        if (opened.spooled) opened.file.delete()
    }

    /**
     * This process's copies live in a folder of their own, so the sweep
     * of earlier processes' copies can run alongside a book being opened.
     */
    private val processSpool: File? = spoolDir?.let { File(it, "p-${java.util.UUID.randomUUID()}") }

    /** Clears copies left by a process that ended with a book open. */
    fun sweepSpools() {
        spoolDir?.listFiles()?.filter { it != processSpool }?.forEach { it.deleteRecursively() }
    }

    private data class Adopted(val uri: Uri, val sha256: String, val size: Long?)

    /**
     * Where an uploaded book's bytes are, when [context] is one.
     *
     * Only a binding the upload adoption wrote carries a digest, and the
     * reader must have opened the book's own file: the one the library
     * row opens, still linked to the bound server file.
     */
    private suspend fun adoptedSource(
        context: BookOrbitCfiContext,
        openedUrl: String,
    ): Adopted? = database.withTransaction {
        checkCurrent(context)
        val binding = database.bookOrbitBindingDao().get(context.request.accountKey, context.bookUrl)
            ?: stale()
        val sha256 = binding.localSha256 ?: return@withTransaction null
        val book = database.bookDao().getByUrl(context.bookUrl) ?: stale()
        if (BookOrbitUrl.fileIdOf(book.downloadHref) != context.fileId) stale()
        val openable = book.openableUri() ?: stale()
        // The reader holds the address as Readium spelled it.
        if (sameAddress(openable, openedUrl).not()) stale()
        Adopted(Uri.parse(openable), sha256, binding.fileSize)
    }

    /**
     * Parses an uploaded book from bytes proved to be the server's file.
     *
     * One file per opened publication: a file of the app's own is read
     * where it is, and a document is copied once. The bytes actually
     * parsed are the ones hashed, and every later check compares against
     * this file rather than resolving the source again.
     */
    private suspend fun openedAdopted(
        context: BookOrbitCfiContext,
        openedUrl: String,
        adopted: Adopted,
        own: (BookOrbitOpenedEpub) -> Unit,
    ): BookOrbitOpenedEpub {
        val stamp = sources.stamp(adopted.uri) ?: stale()
        val spooled = adopted.uri.scheme != "file"
        val file = if (spooled) {
            sources.spool(adopted.uri, processSpool ?: stale()) ?: stale()
        } else {
            adopted.uri.path?.let(::File)?.takeIf { it.isFile } ?: stale()
        }
        try {
            val length = file.length()
            val modified = file.lastModified()
            if (adopted.size != null && adopted.size != length) stale()
            if (file.inputStream().use(BookOrbitUploadClient::sha256Of) != adopted.sha256) stale()
            val publication = BookOrbitEpubPackage.parse(file)
            val opened = BookOrbitOpenedEpub(
                context, publication, file, openedUrl, length, modified,
                source = adopted.uri.toString(), sourceStamp = stamp, spooled = spooled,
            )
            checkAdopted(opened)
            own(opened)
            return opened
        } catch (error: Throwable) {
            if (spooled) file.delete()
            throw error
        }
    }

    /**
     * Whether an opened uploaded book is still what was verified.
     *
     * The link, the source's own size and modification time, and the
     * parsed file must all be unchanged. A replacement that keeps all
     * three is not caught here; the push checks the source's digest.
     */
    private suspend fun checkAdopted(opened: BookOrbitOpenedEpub) {
        try {
            val adopted = adoptedSource(opened.context, opened.openedUrl) ?: stale()
            if (adopted.uri.toString() != opened.source) stale()
            if (sources.stamp(adopted.uri) != opened.sourceStamp) stale()
            if (!opened.file.isFile || opened.file.length() != opened.length ||
                opened.file.lastModified() != opened.modifiedAt
            ) stale()
        } catch (error: BookOrbitIdentityChanged) {
            release(opened)
            throw error
        }
    }

    private suspend fun openedFile(
        context: BookOrbitCfiContext,
        openedUrl: String,
        fileFor: (String) -> File,
    ): File = database.withTransaction {
        checkCurrent(context)
        val book = database.bookDao().getByUrl(context.bookUrl) ?: stale()
        if (book.downloadState != DownloadState.DOWNLOADED ||
            BookOrbitUrl.fileIdOf(book.downloadHref) != context.fileId
        ) stale()
        val file = fileFor(book.remoteUuid ?: stale())
        val localUrl = Uri.fromFile(file).toString()
        if (book.localUri != localUrl || openedUrl != localUrl || !file.isFile ||
            database.bookOrbitBindingDao().get(context.request.accountKey, context.bookUrl)
                ?.fileSize?.let { it != file.length() } == true
        ) stale()
        file
    }

    suspend fun retain(context: BookOrbitCfiContext, raw: String): BookOrbitForeignCfi =
        database.withTransaction {
            checkCurrent(context)
            database.bookOrbitCfiDao().write(
                BookOrbitCfiRecord(
                    context.request.accountKey, context.bookUrl, context.bookId,
                    context.fileId, context.bindingRevision, raw,
                ),
            )
            foreign(context, raw)
        }

    suspend fun load(context: BookOrbitCfiContext): BookOrbitForeignCfi? = database.withTransaction {
        checkCurrent(context)
        val row = database.bookOrbitCfiDao().get(context.request.accountKey, context.bookUrl)
            ?: return@withTransaction null
        if (row.bookId != context.bookId || row.fileId != context.fileId ||
            row.bindingRevision != context.bindingRevision
        ) stale()
        foreign(context, row.rawCfi)
    }

    private suspend fun checkCurrent(context: BookOrbitCfiContext) {
        if (!context.request.matches(database.remoteServerDao().get()) ||
            !context.matches(database.bookOrbitBindingDao().get(context.request.accountKey, context.bookUrl))
        ) stale()
    }

    private fun foreign(context: BookOrbitCfiContext, raw: String) = BookOrbitForeignCfi.capture(
        context.request.accountKey, context.bookId, context.fileId, context.bindingRevision, raw,
    )

    private fun sameAddress(first: String, second: String): Boolean =
        first == second ||
            first.toUri().toAbsoluteUrl()?.toString()?.let { it == second.toUri().toAbsoluteUrl()?.toString() } == true

    private fun stale(): Nothing = throw BookOrbitIdentityChanged()
}

class BookOrbitIdentityChanged : IOException("The BookOrbit connection or selected file changed")
