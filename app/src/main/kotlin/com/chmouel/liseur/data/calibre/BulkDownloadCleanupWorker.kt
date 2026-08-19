package com.chmouel.liseur.data.calibre

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.DownloadState
import kotlinx.coroutines.flow.first

/**
 * Ends a bulk download and puts the database back where it was.
 *
 * Deliberately not a member of the batch it stops. Cancelling work is
 * asynchronous, and the worker that notices the trouble — a full disk,
 * an account swapped out — is one of the ones being cancelled: it can be
 * torn down between sending the cancellation and waiting for it, leaving
 * every remaining book stuck at `QUEUED` or `DOWNLOADING` with nothing
 * left running to clear them. Standing outside the batch is what makes
 * the wait possible.
 *
 * The order is fixed and the first step is the one that matters:
 *
 *  1. read the membership off the tagged work, while the tag still
 *     resolves — the stored batch record holds no book URLs, and after
 *     process death the work items are the only account of who was in it;
 *  2. cancel the tag, and wait for it;
 *  3. reset the rows that never finished;
 *  4. write the closing counts, which have to outlive WorkManager
 *     pruning the rows they were counted from.
 */
class BulkDownloadCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID) ?: return Result.failure()
        return runCatching { tearDown(batchId) }.getOrElse { failure ->
            // Asked for again rather than given up on. This is now the
            // only thing that can end a batch that was stopped, and a
            // batch that never ends is one the reader cannot dismiss or
            // replace. The likeliest failure is also the least
            // surprising: a batch stopped for want of room tears down
            // with hundreds of database writes, on a device that has
            // just said it is full.
            Log.w(TAG, "bulk download $batchId could not be ended yet", failure)
            Result.retry()
        }
    }

    private suspend fun tearDown(batchId: String): Result {
        val container = applicationContext.container
        val store = container.bulkDownloads
        val batch = store.current()
        if (batch == null || batch.id != batchId || batch.settled) return Result.success()

        val workManager = WorkManager.getInstance(applicationContext)
        val tag = BookDownloadRepository.batchTag(batchId)

        val infos = workManager.getWorkInfosByTagFlow(tag).first()
        val members = infos.mapNotNull { info ->
            info.tags.firstOrNull { it.startsWith(BookDownloadRepository.BOOK_TAG_PREFIX) }
                ?.removePrefix(BookDownloadRepository.BOOK_TAG_PREFIX)
        }
        workManager.cancelAllWorkByTag(tag).await()

        // Counted only once the cancellation has landed. A book whose
        // bytes arrived in the moment between the two would be missing
        // from a tally taken before it, and the summary would credit the
        // run with less than the library actually holds. Membership
        // still comes from the earlier read, which is the one taken
        // while the tag was whole.
        val afterCancel = workManager.getWorkInfosByTagFlow(tag).first().ifEmpty { infos }
        val done = BookDownloadRepository.countDone(afterCancel)
        val failed = BookDownloadRepository.countFailed(afterCancel)

        // Only the ones that never landed. A book whose bytes arrived
        // before the cancellation did is downloaded, and saying
        // otherwise would throw the file away by telling the library it
        // is not there.
        //
        // Read a chunk at a time: `IN (:urls)` binds one SQL variable
        // per book, and before API 31 SQLite refuses more than 999 of
        // them. A batch is routinely larger than that, and the throw
        // would land here, after the cancellation and before the rows
        // were put back — the one window where the books have nothing
        // left running to clear them.
        val bookDao = container.database.bookDao()
        members.chunked(RESET_CHUNK).forEach { chunk ->
            bookDao.getByUrls(chunk)
                .filter { it.downloadState != DownloadState.DOWNLOADED }
                .forEach { bookDao.setDownloadState(it.url, DownloadState.REMOTE, null) }
        }

        // Last, and only once the rows are back: a settled batch is one
        // that has been taken apart, and the rest of the app reads it
        // that way — the summary is offered for dismissal on the
        // strength of it.
        store.settle(batchId, done = done, failed = failed)
        Log.i(TAG, "bulk download $batchId ended: $done done, $failed failed")
        return Result.success()
    }

    companion object {
        private const val TAG = "LiseurBulkCleanup"
        private const val KEY_BATCH_ID = "batch_id"

        /**
         * How many books to look up at once when putting rows back.
         *
         * Well under the 999 bound variables SQLite allows before API
         * 31, with room to spare for however the query is composed.
         */
        private const val RESET_CHUNK = 500

        /**
         * Unique per batch, under `KEEP`: several workers can hit the
         * same wall at the same moment, and one cleanup is the whole
         * point of it being separate.
         */
        fun enqueue(context: Context, batchId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "bulk-download-cleanup:$batchId",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BulkDownloadCleanupWorker>()
                    .setInputData(Data.Builder().putString(KEY_BATCH_ID, batchId).build())
                    .build(),
            )
        }
    }
}
