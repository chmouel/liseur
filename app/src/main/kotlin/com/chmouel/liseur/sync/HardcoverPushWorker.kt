package com.chmouel.liseur.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chmouel.liseur.container
import com.chmouel.liseur.data.hardcover.HardcoverPushOutcome
import com.chmouel.liseur.data.remote.SyncFailure
import java.util.concurrent.TimeUnit

/**
 * Reports one book to Hardcover once there is a network to do it over.
 *
 * Queued when a book is closed. One piece of work per book, and a newer
 * close replaces an older one still waiting: the push reads the stored
 * position when it runs, so only the latest request has anything to add.
 * That is also what keeps this far under Hardcover's rate limit, since a
 * push is at most a handful of requests and happens once per sitting.
 */
class HardcoverPushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookUrl = inputData.getString(KEY_BOOK_URL) ?: return Result.failure()
        val outcome = applicationContext.container.hardcover.push(bookUrl, asked = false)
        return when {
            // A spent rate limit (429) refills within the minute, so it
            // is worth another go even though the server did answer.
            outcome is HardcoverPushOutcome.Failed && runAttemptCount < MAX_ATTEMPTS &&
                (outcome.reason.worthRetrying || outcome.reason == SyncFailure.ServerError(429)) ->
                Result.retry()
            outcome is HardcoverPushOutcome.Failed -> Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        private const val KEY_BOOK_URL = "book_url"
        private const val WORK = "hardcover-push"
        private const val MAX_ATTEMPTS = 5

        fun enqueue(context: Context, bookUrl: String) {
            val request = OneTimeWorkRequestBuilder<HardcoverPushWorker>()
                .setInputData(Data.Builder().putString(KEY_BOOK_URL, bookUrl).build())
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("$WORK:$bookUrl", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
