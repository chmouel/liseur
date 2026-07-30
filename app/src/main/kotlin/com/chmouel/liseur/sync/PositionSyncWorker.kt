package com.chmouel.liseur.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chmouel.liseur.container
import com.chmouel.liseur.data.remote.SyncOutcome
import java.util.concurrent.TimeUnit

/**
 * Carries reading positions to and from calibre-web in the background,
 * so closing a book on the phone and opening it on another device lands
 * on the same page without anyone pressing anything.
 */
class PositionSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val coordinator = applicationContext.container.positionSync
        val bookUrl = inputData.getString(KEY_BOOK_URL)

        // Closing a book reconciles just that book, which is quick and
        // keeps the common case off the network for longer than it needs.
        val scope = bookUrl?.let { SyncScope.Book(it) } ?: SyncScope.Full

        return when (val outcome = coordinator.request(scope)) {
            // Retry schedules a backed-off run, so it is only for things
            // that might work later. A phone with no calibre-web account
            // has nothing to sync now and will have nothing in an hour,
            // and an account the server will not let sync will still be
            // refused after a backoff — retrying either only spends
            // battery. A partial run leaves the books it could not settle
            // marked as owing the server something, so a retry picks them
            // up when the reason is one that could pass.
            is SyncOutcome.Failure ->
                if (outcome.reason.worthRetrying) Result.retry() else Result.failure()

            is SyncOutcome.Partial ->
                if (outcome.reason.worthRetrying) Result.retry() else Result.failure()

            SyncOutcome.Success, SyncOutcome.NotApplicable -> Result.success()
        }
    }

    companion object {
        const val KEY_BOOK_URL = "book_url"
        private const val FULL_SYNC = "position-sync"
        private const val PERIODIC_SYNC = "position-sync-periodic"

        private val onNetwork = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Reconciles every book. Used at app start and by the pull to refresh. */
        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                FULL_SYNC,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PositionSyncWorker>()
                    .setConstraints(onNetwork)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
        }

        /** Sends one book's position, for the moment it is closed. */
        fun pushBook(context: Context, bookUrl: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$FULL_SYNC:$bookUrl",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PositionSyncWorker>()
                    .setInputData(Data.Builder().putString(KEY_BOOK_URL, bookUrl).build())
                    .setConstraints(onNetwork)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
        }

        /**
         * Keeps positions fresh in the background.
         *
         * Any connection will do. Waiting for wifi was meant to be
         * considerate, but a reading position is a few dozen bytes — less
         * than a single cover thumbnail — and the cost of being frugal was
         * picking up a phone on mobile data and finding the wrong page.
         *
         * `UPDATE` rather than `KEEP`, or every phone that already has the
         * old six-hourly wifi-only job would keep it forever and none of
         * this would reach the people it is for.
         */
        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_SYNC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PositionSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(onNetwork)
                    .build(),
            )
        }
    }
}
