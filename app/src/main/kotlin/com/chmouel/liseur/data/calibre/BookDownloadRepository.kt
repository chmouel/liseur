package com.chmouel.liseur.data.calibre

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.DownloadState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** How far along a book's download is, as a fraction, or null if unknown. */
data class DownloadProgress(val bookUrl: String, val fraction: Float?)

/** What downloaded books are costing in device storage. */
data class StorageUse(val count: Int, val bytes: Long)

/** Starts, watches and undoes book downloads. */
class BookDownloadRepository(
    private val context: Context,
    private val bookDao: BookDao,
    private val deleteClient: CalibreDeleteClient = CalibreDeleteClient(),
) {
    private val workManager get() = WorkManager.getInstance(context)

    val downloaded: Flow<List<Book>> = bookDao.observeDownloaded()

    /** Progress for every download currently running, keyed by book URL. */
    val progress: Flow<Map<String, DownloadProgress>> =
        workManager.getWorkInfosByTagFlow(TAG).map { infos ->
            infos.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                .mapNotNull { info ->
                    val url = info.progress.getString(KEY_BOOK_URL)
                        ?: info.tags.firstOrNull { it.startsWith(BOOK_TAG_PREFIX) }
                            ?.removePrefix(BOOK_TAG_PREFIX)
                        ?: return@mapNotNull null
                    val fraction = info.progress.getFloat(KEY_FRACTION, -1f).takeIf { it >= 0f }
                    url to DownloadProgress(url, fraction)
                }
                .toMap()
        }

    /** How many books are on the device and what they take up, in bytes. */
    val storage: Flow<StorageUse> = downloaded.map { books ->
        val dir = booksDir()
        val bytes = books.sumOf { book ->
            book.remoteUuid?.let { File(dir, "$it.epub").length() } ?: 0L
        }
        StorageUse(count = books.size, bytes = bytes)
    }

    suspend fun enqueue(book: Book) {
        bookDao.setDownloadState(book.url, DownloadState.QUEUED, null)
        workManager.enqueueUniqueWork(
            workName(book.url),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BookDownloadWorker>()
                .setInputData(Data.Builder().putString(KEY_BOOK_URL, book.url).build())
                .addTag(TAG)
                .addTag(BOOK_TAG_PREFIX + book.url)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    suspend fun cancel(book: Book) {
        workManager.cancelUniqueWork(workName(book.url))
        bookDao.setDownloadState(book.url, DownloadState.REMOTE, null)
    }

    /**
     * Deletes the file but keeps the book in the library, so where you
     * were in it, and anything you marked, is still there if you fetch
     * it again.
     */
    suspend fun removeDownload(book: Book) {
        val uuid = book.remoteUuid ?: return
        fileFor(uuid).delete()
        File(booksDir(), "$uuid.epub.part").delete()
        File(booksDir(), "$uuid.epub.etag").delete()
        bookDao.setDownloadState(book.url, DownloadState.REMOTE, null)
    }

    /**
     * Deletes a book from calibre-web and forgets it here.
     *
     * Everything else in the app only ever removes a copy; this is the one
     * action that reaches the server, so it is the only one that can lose
     * the book for good.
     */
    suspend fun deleteFromServer(
        book: Book,
        baseUrl: String,
        credentials: CalibreCredentials,
    ): ServerDeleteResult {
        val remoteId = book.remoteBookId ?: return ServerDeleteResult.Failed(null)
        val result = deleteClient.delete(
            baseUrl = baseUrl,
            username = credentials.username,
            password = credentials.password,
            remoteBookId = remoteId,
        )
        if (result is ServerDeleteResult.Deleted) {
            book.remoteUuid?.let { fileFor(it).delete() }
            bookDao.deleteByUrls(listOf(book.url))
        }
        return result
    }

    /**
     * Removes a book that came from a folder or a single import.
     *
     * The library row only goes if the file really went. A folder scan
     * indexes whatever is on disk, so dropping the row while the file
     * survives makes the book reappear at the next scan, which looks like
     * the app ignoring the request. Returns false when the file stayed;
     * usually that means Liseur was only ever granted read access to the
     * folder, and it has to be added again.
     */
    suspend fun deleteLocalBook(book: Book): Boolean = withContext(Dispatchers.IO) {
        val uri = (book.localUri ?: book.url).toUri()
        if (!deleteFile(uri)) return@withContext false
        bookDao.deleteByUrls(listOf(book.url))
        true
    }

    /** True once the file is not there any more, however that came about. */
    private fun deleteFile(uri: Uri): Boolean = when (uri.scheme) {
        "file" -> {
            val file = uri.path?.let(::File)
            file != null && (!file.exists() || file.delete())
        }
        else -> runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.onFailure {
            Log.w(TAG, "Not allowed to delete $uri", it)
        }.getOrDefault(false)
    }

    fun booksDir(): File = File(context.filesDir, "books").apply { mkdirs() }

    fun fileFor(uuid: String): File = File(booksDir(), "$uuid.epub")

    fun localUriFor(uuid: String): String = Uri.fromFile(fileFor(uuid)).toString()

    companion object {
        const val TAG = "book-download"
        const val KEY_BOOK_URL = "book_url"
        const val KEY_FRACTION = "fraction"
        private const val BOOK_TAG_PREFIX = "book:"

        fun workName(bookUrl: String) = "download:$bookUrl"
    }
}
