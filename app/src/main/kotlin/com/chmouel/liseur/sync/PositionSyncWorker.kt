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
        val sync = applicationContext.container.koboSync
        val bookUrl = inputData.getString(KEY_BOOK_URL)

        // Closing a book sends just that book, which is quick and keeps
        // the common case off the network for longer than it needs.
        val done = if (bookUrl != null) sync.pushOne(bookUrl) else sync.sync()
        return if (done) Result.success() else Result.retry()
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
         * Keeps positions fresh in the background. Unmetered only, since
         * nobody wants their reading position costing them roaming data.
         */
        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_SYNC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PositionSyncWorker>(6, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .build(),
                    )
                    .build(),
            )
        }
    }
}
