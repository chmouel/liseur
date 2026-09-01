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
     *
     * [accountKey] is who asked. WorkManager keeps a job across a
     * disconnect and a sign-in, so without it a book queued for one
     * account can wake up on another and be sent to a stranger's shelf —
     * into [folderId], which was that first account's folder.
     *
     * [manual] is the reader pressing *Send* rather than the app
     * deciding. Then the request must not be swallowed by a job left
     * over from an account they have since left, so it is appended
     * rather than kept: `APPEND_OR_REPLACE` still runs after work that
     * failed or was cancelled, and the stale job ahead of it gives up at
     * its own account check without sending anything. Not `REPLACE` —
     * WorkManager's cancellation is cooperative and a blocking OkHttp
     * call does not answer it, so replacing could start a second upload
     * while the first is still on the wire, which is the one thing the
     * unique name exists to prevent.
     */
    fun enqueue(
        book: Book,
        folderId: String? = null,
        accountKey: String? = null,
        manual: Boolean = false,
    ) {
        val input = Data.Builder().putString(KEY_BOOK_URL, book.url)
        folderId?.let { input.putString(KEY_FOLDER_ID, it) }
        accountKey?.let { input.putString(KEY_ACCOUNT_KEY, it) }
        workManager.enqueueUniqueWork(
            workName(book.url),
            if (manual) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
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
        const val KEY_ACCOUNT_KEY = "account_key"
        private const val BOOK_TAG_PREFIX = "book:"

        fun workName(bookUrl: String) = "upload:$bookUrl"
    }
}
