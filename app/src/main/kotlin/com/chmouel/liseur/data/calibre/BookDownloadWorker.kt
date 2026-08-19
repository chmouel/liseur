package com.chmouel.liseur.data.calibre

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.DownloadState
import org.readium.r2.shared.util.AbsoluteUrl

/**
 * Downloads one book in the background, so it survives the app being
 * left and comes back on its own if the network drops.
 */
class BookDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.container
        val bookUrl = inputData.getString(BookDownloadRepository.KEY_BOOK_URL)
            ?: return give_up("no book url")
        val batchId = inputData.getString(BookDownloadRepository.KEY_BATCH_ID)

        // A batch that has already hit a wall is being cancelled, and
        // cancellation takes a moment to arrive. Anything starting in
        // that window would run into the same wall, and would do it
        // while the cleanup was counting up what to put back.
        if (batchId != null) {
            val batch = container.bulkDownloads.current()
            if (batch?.id != batchId || batch.stopReason != null || batch.settled) {
                return stopped(bookUrl, "batch $batchId is over")
            }
        }

        val bookDao = container.database.bookDao()
        val book = bookDao.getByUrl(bookUrl) ?: return give_up("unknown book $bookUrl")
        val uuid = book.remoteUuid ?: return give_up("book has no uuid")
        // The URL, the secret and the way to ask for a file all come
        // from the one row that was read. Looking each of them up in
        // turn is how a key typed in just now ends up being handed to
        // the server that was connected a moment ago.
        val server = container.remoteAccount.current() ?: return give_up("no server")
        // Book URLs are not account-qualified, so work queued against one
        // server and run after the reader switched to another would hand
        // the new server a URL belonging to the old one. The catalog
        // refresh guards itself the same way and for the same reason:
        // see RemoteCatalogRepository.forAccount.
        val queuedFor = inputData.getString(BookDownloadRepository.KEY_ACCOUNT_KEY)
        if (queuedFor != null && queuedFor != server.accountKey) {
            batchId?.let { stopBatch(it, BulkStopReason.ACCOUNT_CHANGED) }
            return stopped(bookUrl, "queued for an account that is no longer connected")
        }
        val credentials = server.credentials ?: return give_up("no credentials")
        val files = container.remoteRouter.filesFor(server.kind) ?: return give_up("no file source")

        val request = files.downloadRequest(server.baseUrl, credentials, book)
            ?: run {
                Log.w(TAG, "no download link for $bookUrl")
                return fail(bookUrl)
            }

        bookDao.setDownloadState(bookUrl, DownloadState.DOWNLOADING, null)
        setProgress(progressData(bookUrl, null))

        val downloads = container.bookDownloads
        val outcome = BookDownloader(
            // Bulk work is the only thing that can fill a device on its
            // own, so it is the only thing asked to leave room behind. A
            // single download the reader asked for by name keeps the
            // behaviour it has always had.
            freeSpace = if (batchId != null) downloads::freeBytes else null,
        ).download(
            request = request,
            target = downloads.fileFor(uuid),
        ) { downloaded, total ->
            val fraction = total?.takeIf { it > 0 }?.let { downloaded.toFloat() / it }
            setProgress(progressData(bookUrl, fraction))
        }

        return when (outcome) {
            is DownloadOutcome.Done -> {
                val localUri = downloads.localUriFor(uuid)
                bookDao.setDownloadState(
                    bookUrl,
                    DownloadState.DOWNLOADED,
                    localUri,
                    System.currentTimeMillis(),
                )
                AbsoluteUrl(localUri)?.let { fileUrl ->
                    container.libraryRepository.indexDownloadedFile(fileUrl, bookUrl)
                }
                Result.success()
            }

            is DownloadOutcome.Failed -> when (val reason = outcome.reason) {
                // Worth another go later: the network, not the book.
                is DownloadFailure.Network -> {
                    Log.w(TAG, "download of $bookUrl failed: ${reason.message}")
                    bookDao.setDownloadState(bookUrl, DownloadState.QUEUED, null)
                    Result.retry()
                }
                DownloadFailure.NotAllowed -> {
                    container.database.remoteServerDao().setCanDownload(false)
                    fail(bookUrl)
                }
                DownloadFailure.Gone -> fail(bookUrl)
                // Retrying would only fill the device again, and every
                // book queued behind this one would fill it in turn, so
                // the whole batch stops. On its own, a single download
                // simply fails and says why.
                DownloadFailure.OutOfSpace -> {
                    Log.w(TAG, "no room left for $bookUrl")
                    batchId?.let { stopBatch(it, BulkStopReason.OUT_OF_SPACE) }
                    fail(bookUrl)
                }
            }
        }
    }

    /**
     * Records why the batch is stopping, then hands the stopping itself
     * to a worker that is not in the batch.
     *
     * The reason goes down first, on purpose: cancelling the tag cancels
     * this worker too, and cancelled work has no output data worth
     * reading. Once the reason is stored, losing this worker's own
     * account of things costs nothing.
     */
    private suspend fun stopBatch(batchId: String, reason: BulkStopReason) {
        val container = applicationContext.container
        container.bulkDownloads.recordStopReason(batchId, reason)
        BulkDownloadCleanupWorker.enqueue(applicationContext, batchId)
    }

    /**
     * Steps aside without marking the book as having failed.
     *
     * Nothing was wrong with the book: the batch it belonged to ended,
     * or it was queued for a server that is no longer connected. Leaving
     * it `REMOTE` is what lets it be picked up again next time.
     *
     * Reported as a success carrying a flag rather than as a failure,
     * because a failure is what the summary counts and shows the reader
     * as "couldn't be downloaded". Standing down is neither: the flag is
     * what keeps it out of both columns.
     */
    private suspend fun stopped(bookUrl: String, why: String): Result {
        Log.i(TAG, "download of $bookUrl stood down: $why")
        applicationContext.container.database.bookDao()
            .setDownloadState(bookUrl, DownloadState.REMOTE, null)
        return Result.success(
            Data.Builder()
                .putBoolean(BookDownloadRepository.KEY_STOOD_DOWN, true)
                .build(),
        )
    }

    private fun give_up(why: String): Result {
        Log.w(TAG, "download not started: $why")
        return Result.failure()
    }

    private suspend fun fail(bookUrl: String): Result {
        applicationContext.container.database.bookDao()
            .setDownloadState(bookUrl, DownloadState.FAILED, null)
        return Result.failure()
    }

    private fun progressData(bookUrl: String, fraction: Float?) = Data.Builder()
        .putString(BookDownloadRepository.KEY_BOOK_URL, bookUrl)
        .apply { fraction?.let { putFloat(BookDownloadRepository.KEY_FRACTION, it) } }
        .build()

    private companion object {
        const val TAG = "LiseurDownload"
    }
}
