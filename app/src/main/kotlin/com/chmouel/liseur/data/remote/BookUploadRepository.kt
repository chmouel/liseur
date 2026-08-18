package com.chmouel.liseur.data.remote

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chmouel.liseur.data.db.Book
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Starts and watches uploads of local books to the connected server. */
class BookUploadRepository(private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    /**
     * The books currently on their way up, by URL.
     *
     * Only whether a book is in flight, not how far along it is: an
     * upload is one request whose progress OkHttp does not report back
     * without wrapping the body, and a spinner that cannot say more is
     * honest as it stands.
     */
    val inFlight: Flow<Set<String>> =
        workManager.getWorkInfosByTagFlow(TAG).map { infos ->
            infos.asSequence()
                .filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                .mapNotNull { info ->
                    info.tags.firstOrNull { it.startsWith(BOOK_TAG_PREFIX) }
                        ?.removePrefix(BOOK_TAG_PREFIX)
                }
                .toSet()
        }

    /**
     * Queues one book for upload.
     *
     * The unique work name is the no-double-upload guarantee: asking
     * twice while the first attempt is still queued keeps the first,
     * rather than sending the same book up alongside itself.
     */
    fun enqueue(book: Book, folderId: String? = null) {
        val input = Data.Builder().putString(KEY_BOOK_URL, book.url)
        folderId?.let { input.putString(KEY_FOLDER_ID, it) }
        workManager.enqueueUniqueWork(
            workName(book.url),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BookUploadWorker>()
                .setInputData(input.build())
                .addTag(TAG)
                .addTag(BOOK_TAG_PREFIX + book.url)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun cancel(bookUrl: String) {
        workManager.cancelUniqueWork(workName(bookUrl))
    }

    companion object {
        const val TAG = "book-upload"
        const val KEY_BOOK_URL = "book_url"
        const val KEY_FOLDER_ID = "folder_id"
        private const val BOOK_TAG_PREFIX = "book:"

        fun workName(bookUrl: String) = "upload:$bookUrl"
    }
}
