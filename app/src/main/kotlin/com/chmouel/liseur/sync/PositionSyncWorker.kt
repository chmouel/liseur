package com.chmouel.liseur.sync

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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

        return completedResult(coordinator.request(scope, carryingOn = isBootstrap()))
    }

    /**
     * Whether this run is the follow-up a previous one asked for.
     *
     * Only those count against the cap on consecutive carry-ons, so a
     * reader who refreshes after a long chain has stopped is not held to
     * a budget somebody else's bootstrap spent.
     */
    private fun isBootstrap(): Boolean = inputData.getBoolean(KEY_CARRYING_ON, false)

    companion object {
        internal fun completedResult(outcome: SyncOutcome): Result {
            // The coordinator scheduled the next page. Failing this work would
            // cancel its appended successor, even though the report is accurate.
            if (outcome.continuation) return Result.success()
            val failure = when (outcome) {
                is SyncOutcome.Failure -> outcome.reason
                is SyncOutcome.Partial -> outcome.reason
                else -> return Result.success()
            }
            return if (failure.worthRetrying) Result.retry() else Result.failure()
        }

        const val KEY_BOOK_URL = "book_url"
        private const val KEY_CARRYING_ON = "carrying_on"
        private const val FULL_SYNC = "position-sync"
        private const val PERIODIC_SYNC = "position-sync-periodic"

        /**
         * The follow-up run for a connection still finding its feet.
         *
         * Its own unique name, so that chaining a bootstrap neither
         * disturbs a book's own queued sync nor inherits the exponential
         * backoff that belongs to failures.
         */
        private const val BOOTSTRAP_SYNC = "position-sync-bootstrap"

        /**
         * How long to wait before carrying on with a connection that has
         * more books to name.
         *
         * Long enough not to read as a tight loop against the server,
         * short enough that a reader who has just connected an account
         * watches the shelf fill rather than going away and coming back.
         */
        private const val BOOTSTRAP_DELAY_SECONDS = 15L

        private val onNetwork = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Carries on with a connection that has more books to name.
         *
         * Appended rather than kept, because the run asking for this is
         * very often the previous follow-up: WorkManager would see its
         * own name still running and drop the request, and a library
         * needing three passes would stop after two. Appending queues
         * the next pass behind the one asking for it, and the chain ends
         * when a run stops reporting a shortfall.
         */
        fun continueBootstrap(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                BOOTSTRAP_SYNC,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<PositionSyncWorker>()
                    .setConstraints(onNetwork)
                    .setInputData(Data.Builder().putBoolean(KEY_CARRYING_ON, true).build())
                    .setInitialDelay(BOOTSTRAP_DELAY_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
        }

        /** Sends one book's position, for the moment it is closed. */
        fun pushBook(context: Context, bookUrl: String) {            enqueueBook(context, bookUrl, ExistingWorkPolicy.APPEND_OR_REPLACE, expedited = true)
        }

        /** Retries a failed foreground send without resetting existing backoff. */
        fun retryBook(context: Context, bookUrl: String) {
            enqueueBook(context, bookUrl, ExistingWorkPolicy.KEEP, expedited = false)
        }

        private fun enqueueBook(
            context: Context,
            bookUrl: String,
            policy: ExistingWorkPolicy,
            expedited: Boolean,
        ) {
            val request = OneTimeWorkRequestBuilder<PositionSyncWorker>()
                .setInputData(Data.Builder().putString(KEY_BOOK_URL, bookUrl).build())
                .setConstraints(onNetwork)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            // Request prompt execution for a closed book on API 31+.
            // WorkManager falls back to ordinary work when expedited
            // quota is unavailable. Below that it would need a
            // foreground notification, which a few dozen bytes of
            // position do not justify, so older phones keep the plain job.
            if (expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$FULL_SYNC:$bookUrl",
                policy,
                request.build(),
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
