package com.chmouel.liseur.hardcover

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chmouel.liseur.container
import com.chmouel.liseur.data.remote.SyncFailure
import java.util.concurrent.TimeUnit

/**
 * Reports queued books to Hardcover, one after another, once there is a
 * network.
 *
 * One worker for every book rather than one per book: Hardcover allows a
 * burst of ten requests, and marking a shelf of books read at once would
 * otherwise start a push for each of them together. A book queued again
 * while it is being pushed keeps its newer entry and goes once more.
 */
class HardcoverPushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val hardcover = applicationContext.container.readingTracker.hardcover
        while (true) {
            val (pending, outcome) = hardcover.pushNext() ?: return Result.success()
            val reason = (outcome as? HardcoverPushOutcome.Failed)?.reason
            if (reason != null && retryable(reason) && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            // Sent, or failed for good and left until the book changes again.
            hardcover.settle(pending)
            if (runAttemptCount > 0) {
                // The retries spent here were this book's: a fresh run takes the ones behind it.
                enqueue(applicationContext)
                return Result.success()
            }
        }
    }

    private fun retryable(reason: SyncFailure): Boolean =
        reason.worthRetrying || reason == SyncFailure.ServerError(TOO_MANY_REQUESTS)

    companion object {
        private const val WORK = "hardcover-push"
        private const val MAX_ATTEMPTS = 5
        private const val TOO_MANY_REQUESTS = 429

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<HardcoverPushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            // Appended, so a book queued just as a running drain finds nothing left still goes.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}
