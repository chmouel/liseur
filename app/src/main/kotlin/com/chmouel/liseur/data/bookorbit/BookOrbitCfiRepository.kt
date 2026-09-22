package com.chmouel.liseur.data.bookorbit

import android.net.Uri
import androidx.room.withTransaction
import com.chmouel.liseur.data.db.BookOrbitBinding
import com.chmouel.liseur.data.db.BookOrbitBindingState
import com.chmouel.liseur.data.db.BookOrbitCfiRecord
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import kotlinx.coroutines.Dispatchers
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
)

/** No sync registration: later phases can retain a fetched CFI without adopting it. */
class BookOrbitCfiRepository(private val database: LiseurDatabase) {
    suspend fun openedIfConnected(
        bookUrl: String,
        openedUrl: String,
        fileFor: (String) -> File,
    ): BookOrbitOpenedEpub? {
        val request = database.remoteServerDao().get()?.let(BookOrbitRequestContext::from)
            ?: return null
        if (database.bookOrbitBindingDao().get(request.accountKey, bookUrl) == null) return null
        return openedPackage(capture(bookUrl), openedUrl, fileFor)
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
    ): BookOrbitOpenedEpub = withContext(Dispatchers.IO) {
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

    private fun stale(): Nothing = throw BookOrbitIdentityChanged()
}

class BookOrbitIdentityChanged : IOException("The BookOrbit connection or selected file changed")
