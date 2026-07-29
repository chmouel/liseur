package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import com.chmouel.liseur.data.db.BookAnnotationDao
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.domain.BackedUpBook
import com.chmouel.liseur.domain.BackupContents
import com.chmouel.liseur.domain.KnownBook
import com.chmouel.liseur.domain.decodeAnnotationBackup
import com.chmouel.liseur.domain.encodeAnnotationBackup
import com.chmouel.liseur.domain.matchBackedUpBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How an export or an import went, in terms worth telling someone. */
sealed interface BackupResult {
    data class Exported(val books: Int, val annotations: Int) : BackupResult

    data class Imported(val added: Int, val alreadyHere: Int) : BackupResult

    data object NothingToExport : BackupResult

    data class Failed(val reason: String?) : BackupResult
}

/**
 * Writing every highlight, note and bookmark to a file, and reading them
 * back on another device.
 *
 * This is the answer to marks not being carried by calibre-web's sync,
 * which exchanges reading positions and nothing else.
 */
class AnnotationBackupRepository(
    private val context: Context,
    private val annotationDao: BookAnnotationDao,
    private val bookDao: BookDao,
) {
    suspend fun exportTo(target: Uri): BackupResult = withContext(Dispatchers.IO) {
        val annotations = annotationDao.all()
        if (annotations.isEmpty()) return@withContext BackupResult.NothingToExport

        val titles = bookDao.allOnce().associateBy { it.url }
        val books = annotations.groupBy { it.bookId }.map { (bookId, marks) ->
            BackedUpBook(
                bookId = bookId,
                title = titles[bookId]?.title,
                author = titles[bookId]?.author,
                annotations = marks,
            )
        }
        try {
            context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                out.write(encodeAnnotationBackup(books).toByteArray())
            } ?: return@withContext BackupResult.Failed(null)
        } catch (e: java.io.IOException) {
            return@withContext BackupResult.Failed(e.message)
        }
        BackupResult.Exported(books = books.size, annotations = annotations.size)
    }

    suspend fun importFrom(source: Uri): BackupResult = withContext(Dispatchers.IO) {
        val text = try {
            context.contentResolver.openInputStream(source)?.use { it.readBytes().decodeToString() }
                ?: return@withContext BackupResult.Failed(null)
        } catch (e: java.io.IOException) {
            return@withContext BackupResult.Failed(e.message)
        }

        val contents = decodeAnnotationBackup(text)
        if (contents is BackupContents.Unreadable) return@withContext BackupResult.Failed(contents.reason)

        val known = bookDao.allOnce().map { KnownBook(it.url, it.title, it.author) }
        val incoming = (contents as BackupContents.Readable).books.flatMap { book ->
            val bookId = matchBackedUpBook(book, known)
            book.annotations.map { it.copy(bookId = bookId) }
        }
        if (incoming.isEmpty()) return@withContext BackupResult.Imported(added = 0, alreadyHere = 0)

        val added = annotationDao.insertMissing(incoming).count { it != -1L }
        BackupResult.Imported(added = added, alreadyHere = incoming.size - added)
    }
}
