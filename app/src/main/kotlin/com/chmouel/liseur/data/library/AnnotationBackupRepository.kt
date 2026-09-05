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
import com.chmouel.liseur.domain.previewBackupMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What an export would contain, before it is written anywhere. */
data class BackupSummary(val marks: Int, val books: Int)

/**
 * What a picked backup file would do, before it is done.
 *
 * The matching is the same `matchBackedUpBook` an import runs — run
 * here, against the same library, so the preview cannot promise what
 * the import would not deliver.
 */
data class BackupPreview(
    val books: Int,
    val marks: Int,
    /** Books whose marks would land somewhere in this library. */
    val matchedBooks: Int,
    val matchedMarks: Int,
) {
    companion object {
        /** The domain's count, in the repository's words. */
        fun of(m: com.chmouel.liseur.domain.BackupMatch) = BackupPreview(
            books = m.books,
            marks = m.marks,
            matchedBooks = m.matchedBooks,
            matchedMarks = m.matchedMarks,
        )
    }
}

sealed interface Inspection {
    data class Ready(val preview: BackupPreview) : Inspection

    /** Not one of ours, or damaged. */
    data class Unreadable(val reason: String) : Inspection

    data class Failed(val reason: String?) : Inspection
}

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
    private val requestBookSync: (String) -> Unit = {},
) {
    /** What an export would carry, for saying so before asking where. */
    suspend fun exportPreview(): BackupSummary = withContext(Dispatchers.IO) {
        val annotations = annotationDao.all()
        BackupSummary(
            marks = annotations.size,
            books = annotations.map { it.bookId }.distinct().size,
        )
    }

    /**
     * Reads a picked file and says what restoring it would do.
     *
     * Nothing is written. The person has just chosen a file out of a
     * list of other files; the least it can be asked is what is in it
     * and how much of it would land on books that are actually here,
     * before anything is committed to.
     */
    suspend fun inspectBackup(source: Uri): Inspection = withContext(Dispatchers.IO) {
        val text = try {
            context.contentResolver.openInputStream(source)?.use { it.readBytes().decodeToString() }
                ?: return@withContext Inspection.Failed(null)
        } catch (e: java.io.IOException) {
            return@withContext Inspection.Failed(e.message)
        }

        when (val contents = decodeAnnotationBackup(text)) {
            is BackupContents.Unreadable -> Inspection.Unreadable(contents.reason)
            is BackupContents.Readable -> {
                val known = bookDao.allOnce().map { KnownBook(it.url, it.title, it.author) }
                Inspection.Ready(
                    BackupPreview.of(previewBackupMatch(contents, known)),
                )
            }
        }
    }

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
            book.annotations.map { mark ->
                // The backup format records when a mark was made and
                // nothing else, so that is what it changed at. It has to
                // be something: liseur-sync sends this as `client_ts`,
                // and a restored mark left at zero would claim to have
                // been written in 1970 and disagree with every device
                // that already has it.
                mark.copy(
                    bookId = bookId,
                    updatedAt = mark.updatedAt.takeIf { it > 0 } ?: (mark.createdAt * 1000),
                )
            }
        }
        if (incoming.isEmpty()) return@withContext BackupResult.Imported(added = 0, alreadyHere = 0)

        val inserted = annotationDao.insertMissing(incoming)
        val added = inserted.count { it != -1L }
        incoming.zip(inserted)
            .filter { (_, rowId) -> rowId != -1L }
            .map { (mark, _) -> mark.bookId }
            .distinct()
            .forEach(requestBookSync)
        BackupResult.Imported(added = added, alreadyHere = incoming.size - added)
    }
}
