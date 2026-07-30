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

        val bookDao = container.database.bookDao()
        val book = bookDao.getByUrl(bookUrl) ?: return give_up("unknown book $bookUrl")
        val uuid = book.remoteUuid ?: return give_up("book has no uuid")
        val server = container.calibreAccount.current() ?: return give_up("no server")
        val credentials = container.calibreAccount.credentials() ?: return give_up("no credentials")

        val href = book.downloadHref
            ?: book.remoteBookId?.let { "/opds/download/$it/epub/" }
            ?: run {
                Log.w(TAG, "no download link for $bookUrl")
                return fail(bookUrl)
            }

        bookDao.setDownloadState(bookUrl, DownloadState.DOWNLOADING, null)
        setProgress(progressData(bookUrl, null))

        val downloads = container.bookDownloads
        val outcome = BookDownloader().download(
            url = CalibreUrl.resolve(server.baseUrl, href),
            credentials = credentials,
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
                    container.libraryRepository.extractCover(fileUrl, bookUrl)?.let { cover ->
                        bookDao.setCoverPath(bookUrl, cover)
                    }
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
            }
        }
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
