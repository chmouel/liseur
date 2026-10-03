package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.domain.bookExportFileName
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class BookExportProgress(
    val total: Int,
    val exported: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
) {
    val processed: Int get() = exported + skipped + failed
}

sealed interface BookExportResult {
    data class Completed(val counts: BookExportProgress) : BookExportResult
    data object Empty : BookExportResult
    enum class Failure { LIBRARY, FOLDER }
    data class Failed(val reason: Failure) : BookExportResult
}

/** Copies offline EPUBs without changing the library or replacing destination files. */
class BookExportRepository(private val context: Context, private val bookDao: BookDao) {
    suspend fun exportTo(
        tree: Uri,
        onProgress: (BookExportProgress) -> Unit,
    ): BookExportResult = withContext(Dispatchers.IO) {
        val books = try {
            bookDao.allOpenable().sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }, { it.url }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read books for export", e)
            return@withContext BookExportResult.Failed(BookExportResult.Failure.LIBRARY)
        }
        var counts = BookExportProgress(books.size)
        onProgress(counts)
        if (books.isEmpty()) return@withContext BookExportResult.Empty

        val folder: Uri
        val names: MutableSet<String>
        try {
            folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(folder, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) and Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
                    throw IOException("Folder does not support file creation")
                }
            } ?: throw IOException("Folder is unavailable")
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            names = context.contentResolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                    cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)
                ) {
                    throw IOException("Folder listing is incomplete")
                }
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0).lowercase(Locale.ROOT))
                    }
                }.toMutableSet()
            } ?: throw IOException("Could not list folder")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not open export folder", e)
            return@withContext BookExportResult.Failed(BookExportResult.Failure.FOLDER)
        }

        for (book in books) {
            currentCoroutineContext().ensureActive()
            val name = bookExportFileName(book.title, book.author)
            if (!names.add(name.lowercase(Locale.ROOT))) {
                counts = counts.copy(skipped = counts.skipped + 1)
                onProgress(counts)
                continue
            }

            var destination: Uri? = null
            var complete = false
            try {
                val source = book.openableUri()?.let(Uri::parse) ?: throw IOException("Book has no local file")
                context.contentResolver.openInputStream(source)?.use { input ->
                    currentCoroutineContext().ensureActive()
                    val created = DocumentsContract.createDocument(context.contentResolver, folder, "application/epub+zip", name)
                        ?: throw IOException("Could not create EPUB")
                    destination = created
                    context.contentResolver.openOutputStream(created, "w")?.use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    } ?: throw IOException("Could not write EPUB")
                } ?: throw IOException("Could not read EPUB")
                complete = true
                counts = counts.copy(exported = counts.exported + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not export book ${book.url}", e)
                counts = counts.copy(failed = counts.failed + 1)
            } finally {
                if (!complete) {
                    val removed = destination?.let { created ->
                        withContext(NonCancellable) {
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, created) }
                                .onFailure { Log.w(TAG, "Could not remove incomplete export", it) }
                                .getOrDefault(false)
                        }
                    } ?: true
                    if (removed) names.remove(name.lowercase(Locale.ROOT))
                }
            }
            onProgress(counts)
        }
        BookExportResult.Completed(counts)
    }

    private companion object {
        const val TAG = "BookExport"
    }
}
