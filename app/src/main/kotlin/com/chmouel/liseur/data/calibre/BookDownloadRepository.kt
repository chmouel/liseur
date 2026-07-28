package com.chmouel.liseur.data.calibre

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How far along a book's download is, as a fraction, or null if unknown. */
data class DownloadProgress(val bookUrl: String, val fraction: Float?)

/** What downloaded books are costing in device storage. */
data class StorageUse(val count: Int, val bytes: Long)

/** Starts, watches and undoes book downloads. */
class BookDownloadRepository(
    private val context: Context,
    private val bookDao: BookDao,
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
