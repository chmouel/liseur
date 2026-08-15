package com.chmouel.liseur.data.liseursync

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map

/** How one upload ended, told to whoever is holding a screen open. */
sealed interface UploadOutcome {
    /** The server took the file and catalogued it. */
    data object Done : UploadOutcome

    /** The server looked at the file and said no; the local copy stays. */
    data class Refused(val reason: String) : UploadOutcome

    /** The network or the server gave up before an answer existed. */
    data object Unreachable : UploadOutcome
}

/**
 * Starts and watches EPUB uploads to a liseur-sync server.
 *
 * Uploading is always an explicit ask: nothing here runs on its own.
 * The work itself is WorkManager's, so it survives the app being left
 * and comes back on its own when the network does — safe because the
 * upload is bound to an idempotency key the server replays.
 */
class BookUploadRepository(private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    /**
     * The terminal outcome of an upload, once each.
     *
     * Process-local: a result nobody was around to see is not lost — the
     * book row itself says whether it is on the server — so a message
     * here is a nicety, not a record.
     */
    private val mutableOutcomes = MutableSharedFlow<Pair<String, UploadOutcome>>(
        extraBufferCapacity = 8,
    )
    val outcomes: Flow<Pair<String, UploadOutcome>> = mutableOutcomes

    /** Called by the worker when an upload settles. */
    internal suspend fun report(bookUrl: String, outcome: UploadOutcome) {
        mutableOutcomes.emit(bookUrl to outcome)
    }

    /** Enqueues [book] for upload, or does nothing if it is already going. */
    fun enqueue(book: Book) {
        workManager.enqueueUniqueWork(
            workName(book.url),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BookUploadWorker>()
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

    /** Books with an upload running or queued, for dimming the action. */
    val running: Flow<Set<String>> =
        workManager.getWorkInfosByTagFlow(TAG).map { infos ->
            infos.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                .mapNotNull { info ->
                    info.progress.getString(KEY_BOOK_URL)
                        ?: info.tags.firstOrNull { it.startsWith(BOOK_TAG_PREFIX) }
                            ?.removePrefix(BOOK_TAG_PREFIX)
                }
                .toSet()
        }

    companion object {
        const val TAG = "book-upload"
        const val KEY_BOOK_URL = "book_url"
        private const val BOOK_TAG_PREFIX = "upload:"

        fun workName(bookUrl: String) = "upload:$bookUrl"
    }
}
