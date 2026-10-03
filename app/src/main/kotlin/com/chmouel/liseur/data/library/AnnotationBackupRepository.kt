package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import android.util.JsonWriter
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

internal class AnnotationBackupTooLarge : IOException("Annotation backup exceeds limit")

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
    /** Writes one row at a time so the archive limit also bounds export memory. */
    suspend fun writeContents(file: File, maxBytes: Long): Int = withContext(Dispatchers.IO) {
        var written = 0L
        var count = 0
        var lastBook: String? = null
        var lastId: String? = null
        file.outputStream().use { output ->
            val bounded = object : OutputStream() {
                override fun write(value: Int) {
                    if (++written > maxBytes) throw AnnotationBackupTooLarge()
                    output.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    written += length
                    if (written > maxBytes) throw AnnotationBackupTooLarge()
                    output.write(bytes, offset, length)
                }
            }
            JsonWriter(bounded.writer(Charsets.UTF_8)).use { writer ->
                writer.beginObject().name("format").value(1).name("application").value("liseur")
                    .name("books").beginArray()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val mark = annotationDao.nextForBackup(lastBook, lastId) ?: break
                    if (mark.bookId != lastBook) {
                        if (lastBook != null) writer.endArray().endObject()
                        val book = bookDao.getByUrl(mark.bookId)
                        writer.beginObject().name("book_id").value(mark.bookId)
                        book?.title?.let { writer.name("title").value(it) }
                        book?.author?.let { writer.name("author").value(it) }
                        writer.name("annotations").beginArray()
                    }
                    writer.beginObject().name("id").value(mark.id).name("kind").value(mark.kind)
                        .name("locator").value(mark.locatorJson)
                    mark.text?.let { writer.name("text").value(it) }
                    mark.note?.let { writer.name("note").value(it) }
                    mark.tint?.let { writer.name("tint").value(it) }
                    mark.chapter?.let { writer.name("chapter").value(it) }
                    mark.position?.let { writer.name("position").value(it) }
                    mark.totalProgression?.let { writer.name("progression").value(it) }
                    writer.name("created_at").value(mark.createdAt).name("updated_at").value(mark.updatedAt)
                    mark.noteCreatedAt?.let { writer.name("note_created_at").value(it) }
                    mark.noteUpdatedAt?.let { writer.name("note_updated_at").value(it) }
                    writer.endObject()
                    writer.flush()
                    count++
                    lastBook = mark.bookId
                    lastId = mark.id
                }
                if (lastBook != null) writer.endArray().endObject()
                writer.endArray().endObject()
            }
        }
        count
    }

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
        val books = exportContents().books
        if (books.isEmpty()) return@withContext BackupResult.NothingToExport

        try {
            context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                out.write(encodeAnnotationBackup(books).toByteArray())
            } ?: return@withContext BackupResult.Failed(null)
        } catch (e: java.io.IOException) {
            return@withContext BackupResult.Failed(e.message)
        }
        BackupResult.Exported(books = books.size, annotations = books.sumOf { it.annotations.size })
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

        importContents(contents as BackupContents.Readable)
    }

    internal suspend fun exportContents(): BackupContents.Readable = withContext(Dispatchers.IO) {
        val titles = bookDao.allOnce().associateBy { it.url }
        BackupContents.Readable(
            annotationDao.all().groupBy { it.bookId }.map { (bookId, marks) ->
                BackedUpBook(bookId, titles[bookId]?.title, titles[bookId]?.author, marks)
            },
        )
    }

    internal suspend fun previewContents(contents: BackupContents.Readable): BackupPreview =
        withContext(Dispatchers.IO) {
            val known = bookDao.allOnce().map { KnownBook(it.url, it.title, it.author) }
            BackupPreview.of(previewBackupMatch(contents, known))
        }

    internal suspend fun importContents(contents: BackupContents.Readable): BackupResult.Imported =
        withContext(Dispatchers.IO) {
            val known = bookDao.allOnce().map { KnownBook(it.url, it.title, it.author) }
            val incoming = contents.books.flatMap { book ->
                val bookId = matchBackedUpBook(book, known)
                book.annotations.map { mark ->
                    // Older backups omit the edit timestamp. Use the creation
                    // time in microseconds rather than sending a zero client_ts.
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
