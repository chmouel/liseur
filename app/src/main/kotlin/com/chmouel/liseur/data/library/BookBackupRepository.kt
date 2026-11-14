package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import android.util.Log
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.domain.BackedUpBookFile
import com.chmouel.liseur.domain.BookRestorePlan
import com.chmouel.liseur.domain.KnownBook
import com.chmouel.liseur.domain.planBookRestore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BookBackupCandidate(
    val url: String,
    val title: String,
    val author: String?,
    val sizeBytes: Long?,
)

internal class BookBackupTooLarge : IOException("Book backup exceeds limit")

internal data class PreparedBookEntry(
    val path: String,
    val sha256: String,
    val size: Long,
)

internal data class PreparedBooksResult(
    val books: Int = 0,
    val skipped: Int = 0,
    val bookBytes: Long = 0,
    val entries: List<PreparedBookEntry> = emptyList(),
)

internal data class BookRestoreOutcome(
    val aliases: Map<String, String> = emptyMap(),
    val imported: Int = 0,
    val alreadyPresent: Int = 0,
    val failures: Int = 0,
)

class BookBackupRepository(
    private val context: Context,
    private val bookDao: BookDao,
    private val importBook: suspend (File, String) -> ImportResult,
) {
    suspend fun candidates(): List<BookBackupCandidate> = withContext(Dispatchers.IO) {
        val books = bookDao.allOpenable()
            .sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }, { it.url }))
        books.map { book ->
            currentCoroutineContext().ensureActive()
            val size = book.openableUri()?.let { uri -> measuredFileSize(context, Uri.parse(uri)) }
            BookBackupCandidate(
                url = book.url,
                title = book.title,
                author = book.author,
                sizeBytes = size,
            )
        }
    }

    internal suspend fun prepareContents(
        selectedUrls: Set<String>,
        file: File,
        maxIndexBytes: Long,
        maxBookBytes: Long,
        maxBooksTotalBytes: Long,
        maxBookFiles: Int,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): PreparedBooksResult {
        if (selectedUrls.isEmpty()) return PreparedBooksResult()
        val selected = bookDao.allOpenable()
            .filter { it.url in selectedUrls }
            .sortedBy { it.url }
        if (selected.isEmpty()) return PreparedBooksResult(skipped = selectedUrls.size)

        var skipped = (selectedUrls.size - selected.size).coerceAtLeast(0)
        val records = mutableListOf<BackedUpBookFile>()
        val distinctShas = LinkedHashMap<String, Long>()
        var totalBookBytes = 0L
        val totalCount = selected.size.toLong()
        onProgress(0L, totalCount)

        for ((index, book) in selected.withIndex()) {
            currentCoroutineContext().ensureActive()
            val uriString = book.openableUri()
            val hashed = uriString?.let { hashBookUri(Uri.parse(it), maxBookBytes) }
            if (hashed == null) {
                skipped++
                onProgress((index + 1).toLong(), totalCount)
                continue
            }
            val (sha, size) = hashed
            val isNewSha = sha !in distinctShas
            if (isNewSha) {
                if (distinctShas.size + 1 > maxBookFiles) throw BookBackupTooLarge()
                if (totalBookBytes + size > maxBooksTotalBytes) throw BookBackupTooLarge()
                distinctShas[sha] = size
                totalBookBytes += size
            }
            records += BackedUpBookFile(
                bookId = book.url,
                title = book.title,
                author = book.author,
                sha256 = sha,
                size = size,
            )
            onProgress((index + 1).toLong(), totalCount)
        }

        if (records.isEmpty()) {
            return PreparedBooksResult(skipped = skipped)
        }

        val json = JSONObject()
            .put("format", 1)
            .put("application", "liseur")
            .put(
                "books",
                JSONArray().also { array ->
                    for (record in records) {
                        val obj = JSONObject()
                            .put("book_id", record.bookId)
                            .put("title", record.title ?: JSONObject.NULL)
                            .put("author", record.author ?: JSONObject.NULL)
                            .put("sha256", record.sha256)
                            .put("size", record.size)
                        array.put(obj)
                    }
                },
            )
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > maxIndexBytes) throw BookBackupTooLarge()
        file.writeBytes(bytes)

        val entries = distinctShas.map { (sha, size) ->
            PreparedBookEntry(path = "books/$sha.epub", sha256 = sha, size = size)
        }
        return PreparedBooksResult(
            books = records.size,
            skipped = skipped,
            bookBytes = totalBookBytes,
            entries = entries,
        )
    }

    internal suspend fun streamBooksToZip(
        records: List<BackedUpBookFile>,
        zip: ZipOutputStream,
        onBytesWritten: (Long) -> Unit = {},
    ) {
        val writtenShas = HashSet<String>()
        for (record in records) {
            currentCoroutineContext().ensureActive()
            if (!writtenShas.add(record.sha256)) continue
            val book = bookDao.getByUrl(record.bookId) ?: throw IOException("Missing book row for ${record.bookId}")
            val uri = book.openableUri()?.let(Uri::parse) ?: throw IOException("Missing openable URI for ${record.bookId}")
            val entryPath = "books/${record.sha256}.epub"
            zip.putNextEntry(ZipEntry(entryPath))
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    if (written > record.size) throw IOException("Book size grew during export")
                    zip.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    onBytesWritten(count.toLong())
                }
            } ?: throw IOException("Could not open book stream for ${record.bookId}")
            zip.closeEntry()
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (written != record.size || actualSha != record.sha256) {
                throw IOException("Book changed between prepare and save")
            }
        }
    }

    internal suspend fun planRestore(files: List<BackedUpBookFile>): BookRestorePlan {
        val shelf = bookDao.allOnce()
        val known = shelf.map { KnownBook(it.url, it.title, it.author) }
        val copies = existingCopiesMap(shelf)
        return planBookRestore(files, known, copies)
    }

    internal suspend fun restore(
        files: List<BackedUpBookFile>,
        stagedFiles: Map<String, File>,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): BookRestoreOutcome {
        if (files.isEmpty()) return BookRestoreOutcome()
        val plan = planRestore(files)
        val aliases = LinkedHashMap<String, String>(plan.matched)
        var alreadyPresent = plan.matched.size
        var imported = 0
        var failures = 0

        val missingBySha = plan.missing.groupBy { it.sha256 }
        val totalToImport = missingBySha.size.toLong()
        var doneCount = 0L
        if (totalToImport > 0) onProgress(0L, totalToImport)

        for ((sha, recordsForSha) in missingBySha) {
            currentCoroutineContext().ensureActive()
            val entryPath = "books/$sha.epub"
            val staged = stagedFiles[entryPath]
            if (staged == null || !staged.isFile) {
                failures += recordsForSha.size
                recordsForSha.forEach { aliases[it.bookId] = it.bookId }
                doneCount++
                onProgress(doneCount, totalToImport)
                continue
            }
            when (val result = importBook(staged, sha)) {
                is ImportResult.Added -> {
                    imported++
                    alreadyPresent += (recordsForSha.size - 1).coerceAtLeast(0)
                    recordsForSha.forEach { aliases[it.bookId] = result.book.url }
                }
                is ImportResult.AlreadyShelved -> {
                    alreadyPresent += recordsForSha.size
                    recordsForSha.forEach { aliases[it.bookId] = result.book.url }
                }
                ImportResult.Failed -> {
                    failures += recordsForSha.size
                    // The plan already refused every other match for this book, so its data
                    // stays under the archived URL instead of being re-matched by title.
                    recordsForSha.forEach { aliases[it.bookId] = it.bookId }
                }
            }
            doneCount++
            onProgress(doneCount, totalToImport)
        }

        return BookRestoreOutcome(
            aliases = aliases,
            imported = imported,
            alreadyPresent = alreadyPresent,
            failures = failures,
        )
    }

    private fun existingCopiesMap(shelf: List<Book>): Map<String, String> {
        val booksDir = File(context.filesDir, "books")
        val prefix = Uri.fromFile(booksDir).toString().trimEnd('/') + "/"
        val copies = LinkedHashMap<String, String>()
        for (book in shelf) {
            for (candidate in listOfNotNull(book.url, book.localUri)) {
                if (candidate.startsWith(prefix) && candidate.endsWith(".epub")) {
                    val sha = candidate.removePrefix(prefix).removeSuffix(".epub")
                    if (SHA256_REGEX.matches(sha) && sha !in copies) {
                        copies[sha] = book.url
                    }
                }
            }
        }
        return copies
    }

    private suspend fun hashBookUri(uri: Uri, maxBookBytes: Long): Pair<String, Long>? {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val ok = try {
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > maxBookBytes) throw BookBackupTooLarge()
                    digest.update(buffer, 0, count)
                }
                true
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: BookBackupTooLarge) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Skipping unreadable book $uri during backup prepare", e)
            false
        }
        if (!ok) return null
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return sha to size
    }

    private companion object {
        const val TAG = "BookBackup"
        const val BUFFER_SIZE = 32 * 1024
        val SHA256_REGEX = Regex("[0-9a-f]{64}")
    }
}
