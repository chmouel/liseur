package com.chmouel.liseur.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.chmouel.liseur.container
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.calibre.resolveWorkId
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.remote.SyncOutcome
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.readium.r2.shared.util.AbsoluteUrl

/**
 * WorkManager worker for `drive.appdata` reading-state & catalog sync ONLY.
 *
 * Runs on its own unique work names (`drive-state-sync`, `drive-state-sync-periodic`)
 * with `NetworkType.CONNECTED`, completely independent from bulk EPUB transfers
 * (`drive.file`) so a large EPUB upload/download never blocks a position update.
 */
class DriveStateSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val backend = applicationContext.container.librarySync as? DriveLibrarySync
            ?: return Result.success()
        if (!backend.isActive()) return Result.success()
        val bookUrl = inputData.getString(KEY_BOOK_URL)
        val forceRescan = inputData.getBoolean(KEY_FORCE_RESCAN, false)
        val summary = backend.changeEngine.sync(
            bookUrlFilter = bookUrl,
            forceManifestRescan = forceRescan,
        )
        return when (val outcome = summary.outcome) {
            SyncOutcome.Success, SyncOutcome.NotApplicable, SyncOutcome.Incomplete -> Result.success()
            is SyncOutcome.Partial -> if (outcome.reason.worthRetrying) Result.retry() else Result.failure()
            is SyncOutcome.Failure -> if (outcome.reason.worthRetrying) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_BOOK_URL = "drive_book_url"
        const val KEY_FORCE_RESCAN = "drive_force_rescan"
    }
}

/**
 * WorkManager worker for bulk user-selected EPUB uploads (`drive.file`).
 *
 * Uses its own unique work name (`drive-epub-upload`) and defaults to
 * `NetworkType.UNMETERED` unless the reader opts into metered transfers.
 */
class DriveEpubUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val backend = applicationContext.container.librarySync as? DriveLibrarySync
            ?: return Result.success()
        if (!backend.isActive()) return Result.success()
        val summary = backend.epubTransfers.uploadPending()
        return when {
            summary.needsReconnect -> Result.failure()
            summary.retryableFailure -> Result.retry()
            // Per-book failures stay in the queue: a drained run lets an appended request
            // for the next book run instead of being skipped with this one.
            else -> Result.success()
        }
    }
}

/**
 * WorkManager worker for on-demand resumable EPUB downloads (`drive.file`).
 *
 * Uses its own unique work name (`drive-epub-download:<bookUrl>`), verifies
 * SHA-256 before moving the EPUB into place, and indexes file metadata once
 * downloaded.
 */
class DriveEpubDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookUrl = inputData.getString(BookDownloadRepository.KEY_BOOK_URL)
            ?: return Result.failure()
        val backend = applicationContext.container.librarySync as? DriveLibrarySync
        if (backend == null || !backend.isActive()) {
            applicationContext.container.database.bookDao().setDownloadState(bookUrl, DownloadState.FAILED, null)
            return Result.failure()
        }

        return applicationContext.container.bookDownloads.withBookLock(bookUrl) {
            download(backend, bookUrl)
        }
    }

    private suspend fun download(backend: DriveLibrarySync, bookUrl: String): Result {
        val outcome = backend.epubTransfers.downloadBook(bookUrl) { fraction ->
            val builder = Data.Builder().putString(BookDownloadRepository.KEY_BOOK_URL, bookUrl)
            if (fraction != null) builder.putFloat(BookDownloadRepository.KEY_FRACTION, fraction)
            setProgress(builder.build())
        }
        return when (outcome) {
            is DriveEpubDownloadOutcome.Downloaded -> {
                AbsoluteUrl(outcome.localUri)?.let { fileUrl ->
                    runCatching {
                        applicationContext.container.libraryRepository.indexDownloadedFile(
                            fileUrl = fileUrl,
                            identity = bookUrl,
                            downloadedAt = outcome.downloadedAt,
                        )
                    }
                }
                Result.success()
            }
            is DriveEpubDownloadOutcome.RetryableFailure -> Result.retry()
            is DriveEpubDownloadOutcome.PermanentFailure,
            DriveEpubDownloadOutcome.NeedsReconnect,
            -> Result.failure()
        }
    }
}

/**
 * WorkManager scheduling and cancellation helpers keeping `drive.appdata`
 * reading-state sync strictly isolated from `drive.file` bulk EPUB transfers.
 */
internal object DriveSyncWorkers {

    const val STATE_SYNC_WORK_NAME = "drive-state-sync"
    const val STATE_SYNC_PERIODIC_WORK_NAME = "drive-state-sync-periodic"
    const val EPUB_UPLOAD_WORK_NAME = "drive-epub-upload"
    const val EPUB_DOWNLOAD_TAG = "drive-epub-download"

    fun epubDownloadWorkName(bookUrl: String): String = "drive-epub-download:$bookUrl"

    fun buildStateSyncRequest(
        bookUrl: String? = null,
        forceRescan: Boolean = false,
    ): OneTimeWorkRequest {
        val data = Data.Builder().apply {
            bookUrl?.let { putString(DriveStateSyncWorker.KEY_BOOK_URL, it) }
            if (forceRescan) putBoolean(DriveStateSyncWorker.KEY_FORCE_RESCAN, true)
        }.build()
        return OneTimeWorkRequestBuilder<DriveStateSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
    }

    fun buildPeriodicStateSyncRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<DriveStateSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

    fun buildEpubUploadRequest(allowMetered: Boolean): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DriveEpubUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

    fun buildEpubDownloadRequest(
        bookUrl: String,
        allowMetered: Boolean,
    ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<DriveEpubDownloadWorker>()
        .setInputData(
            Data.Builder()
                .putString(BookDownloadRepository.KEY_BOOK_URL, bookUrl)
                .build(),
        )
        .addTag(EPUB_DOWNLOAD_TAG)
        .addTag(BookDownloadRepository.TAG)
        .addTag(BookDownloadRepository.BOOK_TAG_PREFIX + bookUrl)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .build(),
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    fun enqueueStateSync(
        context: Context,
        bookUrl: String? = null,
        forceRescan: Boolean = false,
    ) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val name = bookUrl?.let { "$STATE_SYNC_WORK_NAME:$it" } ?: STATE_SYNC_WORK_NAME
        wm.enqueueUniqueWork(
            name,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            buildStateSyncRequest(bookUrl = bookUrl, forceRescan = forceRescan),
        )
    }

    fun schedulePeriodicStateSync(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            STATE_SYNC_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            buildPeriodicStateSyncRequest(),
        )
    }

    fun enqueueEpubUploads(
        context: Context,
        allowMetered: Boolean,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            EPUB_UPLOAD_WORK_NAME,
            policy,
            buildEpubUploadRequest(allowMetered = allowMetered),
        )
    }

    fun cancelEpubUploads(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(EPUB_UPLOAD_WORK_NAME)
    }

    suspend fun enqueueEpubDownload(
        context: Context,
        bookUrl: String,
        allowMetered: Boolean,
    ): UUID? {
        val wm = WorkManager.getInstance(context.applicationContext)
        val name = epubDownloadWorkName(bookUrl)
        val workInfos = wm.getWorkInfosForUniqueWorkFlow(name)
        val previous = workInfos.first()
        previous.firstOrNull { !it.state.isFinished }?.let { return it.id }
        val request = buildEpubDownloadRequest(bookUrl = bookUrl, allowMetered = allowMetered)
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).await()
        return resolveWorkId(request.id, previous.mapTo(mutableSetOf()) { it.id }, workInfos.first().map { it.id })
    }

    suspend fun cancelEpubDownload(context: Context, bookUrl: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(epubDownloadWorkName(bookUrl))
            .await()
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(STATE_SYNC_WORK_NAME)
        wm.cancelUniqueWork(STATE_SYNC_PERIODIC_WORK_NAME)
        wm.cancelUniqueWork(EPUB_UPLOAD_WORK_NAME)
        wm.cancelAllWorkByTag(EPUB_DOWNLOAD_TAG)
    }
}
