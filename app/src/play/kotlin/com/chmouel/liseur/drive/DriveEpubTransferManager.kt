package com.chmouel.liseur.drive

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.domain.BookFingerprints
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Row displayed in the Play-only "Upload EPUB files" picker UI. */
data class DriveUploadCandidate(
    val bookUrl: String,
    val title: String,
    val author: String?,
    val sizeBytes: Long,
    val sha256: String?,
    val uploadState: String?,
) {
    val isUploaded: Boolean get() = uploadState == DriveUpload.STATE_UPLOADED
    val isPendingOrUploading: Boolean
        get() = uploadState == DriveUpload.STATE_PENDING || uploadState == DriveUpload.STATE_UPLOADING
}

/** Result of an on-demand resumable Drive EPUB download. */
internal sealed interface DriveEpubDownloadOutcome {
    data class Downloaded(val localUri: String, val sha256: String, val downloadedAt: Long) : DriveEpubDownloadOutcome
    data class RetryableFailure(val reason: String) : DriveEpubDownloadOutcome
    data class PermanentFailure(val reason: String) : DriveEpubDownloadOutcome
    data object NeedsReconnect : DriveEpubDownloadOutcome
}

/** Result of draining the user-selected EPUB upload queue. */
internal data class DriveEpubUploadSummary(
    val uploadedCount: Int = 0,
    val failedCount: Int = 0,
    val retryableFailure: Boolean = false,
    val needsReconnect: Boolean = false,
)

/**
 * Manages opt-in resumable EPUB uploads into the canonical `Liseur/` folder and
 * on-demand resumable downloads with SHA-256 verification before moving into place
 * (ADR-0078 & `docs/DRIVE_SYNC.md` §2.1, §4.2).
 */
internal class DriveEpubTransferManager(
    private val context: Context,
    private val database: LiseurDatabase,
    private val store: DriveSyncDatabase,
    private val driveApi: DriveRemoteApi,
    private val workIdentity: DriveWorkIdentity,
    private val catalogSync: DriveCatalogSync,
    private val fingerprintStore: BookFingerprintStore,
    private val acquireToken: suspend (staleToken: String?) -> SilentTokenResult,
    private val onCatalogAvailabilityChanged: suspend () -> Unit = {},
    private val booksDirProvider: () -> File = { File(context.applicationContext.filesDir, "books").apply { mkdirs() } },
    private val openBookStream: (Book) -> InputStream? = { book -> openDefaultStream(context.applicationContext, book) },
    private val measureBookBytes: (Book) -> Long = { book -> measureDefaultBytes(context.applicationContext, book) },
    private val onNeedsReconnect: suspend (permissionId: String, reason: String) -> Unit = { permissionId, reason ->
        store.withTransaction {
            if (store.accounts().active()?.permissionId == permissionId) {
                store.accounts().setState(permissionId, DriveAccount.STATE_NEEDS_RECONNECT)
                store.accounts().recordError(permissionId, reason)
            }
        }
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val books get() = database.bookDao()
    private val identities get() = database.workIdentityDao()
    private val bookRemoval by lazy {
        BookRemoval(
            bookDao = database.bookDao(),
            sessionDao = database.readingSessionDao(),
            peerStateDao = database.syncPeerStateDao(),
            identityDao = database.workIdentityDao(),
            progressDao = database.readingProgressDao(),
            annotationDao = database.annotationDao(),
            annotationSyncDao = database.annotationSyncDao(),
            inTransaction = { work -> database.withTransaction { work() } },
            metadataDao = database.bookMetadataDao(),
        )
    }
    private val accounts get() = store.accounts()
    private val activeDownloads: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    /** Cancels in-flight downloads and waits until each has unwound, so none installs afterwards. */
    suspend fun cancelAndDrain() {
        val jobs = activeDownloads.toList()
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
    }
    private val uploads get() = store.uploads()
    private val driveFiles get() = store.files()

    /** Lists all locally available books eligible for the "Upload EPUB files" picker. */
    suspend fun listUploadCandidates(permissionId: String): List<DriveUploadCandidate> = withContext(Dispatchers.IO) {
        val localBooks = books.allOnce()
            .filter { it.browseServerId == null && it.openableUrl != null }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
        val existingUploads = uploads.forAccount(permissionId)
        val byBookUrl = existingUploads.associateBy { it.bookUrl }
        val bySha = existingUploads.associateBy { it.sha256.lowercase(Locale.ROOT) }

        localBooks.map { book ->
            val fp = identities.fingerprint(book.url)
            val sha = fp?.sha256?.lowercase(Locale.ROOT)
            val upload = sha?.let { bySha[it] } ?: byBookUrl[book.url]
            val size = fp?.fileSize?.takeIf { it > 0L }
                ?: upload?.sizeBytes?.takeIf { it > 0L }
                ?: book.sizeBytes?.takeIf { it > 0L }
                ?: measureBookBytes(book)
            DriveUploadCandidate(
                bookUrl = book.url,
                title = book.title,
                author = book.author,
                sizeBytes = size.coerceAtLeast(0L),
                sha256 = sha,
                uploadState = upload?.state,
            )
        }
    }

    /**
     * Queues the selected local books into `drive_upload` for [permissionId].
     * Already-uploaded editions are preserved without re-uploading.
     */
    suspend fun queueSelectedBooks(
        permissionId: String,
        selectedBookUrls: Set<String>,
    ): Int = withContext(Dispatchers.IO) {
        val now = clock()
        var queued = 0
        for (bookUrl in selectedBookUrls) {
            val book = books.getByUrl(bookUrl) ?: continue
            if (book.openableUrl == null) continue
            val fp = fingerprintStore.of(book) ?: identities.fingerprint(book.url)?.fingerprint ?: continue
            val sha = fp.sha256.lowercase(Locale.ROOT)
            val existing = uploads.get(permissionId, sha)
            if (existing?.state == DriveUpload.STATE_UPLOADED && !existing.fileId.isNullOrBlank()) {
                continue
            }
            uploads.upsert(
                DriveUpload(
                    permissionId = permissionId,
                    sha256 = sha,
                    bookUrl = book.url,
                    sizeBytes = fp.size.coerceAtLeast(0L),
                    state = DriveUpload.STATE_PENDING,
                    sessionUri = existing?.sessionUri,
                    bytesSent = existing?.bytesSent ?: 0L,
                    fileId = null,
                    selectedAt = existing?.selectedAt ?: now,
                    updatedAt = now,
                ),
            )
            queued++
        }
        queued
    }

    /**
     * Uploads all pending/failed user-selected EPUBs into the canonical `Liseur/`
     * folder via resumable upload, publishing catalog media availability in
     * `drive.appdata` ONLY after upload succeeds.
     */
    suspend fun uploadPending(): DriveEpubUploadSummary {
        val tokenResult = acquireToken(null)
        val (initialToken, permissionId) = when (tokenResult) {
            is SilentTokenResult.Token -> tokenResult.accessToken to tokenResult.permissionId
            is SilentTokenResult.NeedsReconnect ->
                return DriveEpubUploadSummary(needsReconnect = true)
            is SilentTokenResult.TransientFailure ->
                return DriveEpubUploadSummary(retryableFailure = true)
            SilentTokenResult.Inactive ->
                return DriveEpubUploadSummary()
        }

        var accessToken = initialToken
        var retriedAuth = false
        while (true) {
            val pending = uploads.pendingForAccount(permissionId)
            if (pending.isEmpty()) return DriveEpubUploadSummary()
            try {
                return uploadPendingWithToken(permissionId, accessToken, pending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveAuthExpiredException) {
                if (retriedAuth) {
                    val reason = e.message ?: DriveAccountController.RECONNECT_REQUIRED_REASON
                    onNeedsReconnect(permissionId, reason)
                    return DriveEpubUploadSummary(needsReconnect = true)
                }
                retriedAuth = true
                when (val refreshed = acquireToken(accessToken)) {
                    // A refreshed token for another account must never carry this account's queue.
                    is SilentTokenResult.Token -> if (refreshed.permissionId == permissionId) {
                        accessToken = refreshed.accessToken
                    } else {
                        return DriveEpubUploadSummary()
                    }
                    is SilentTokenResult.NeedsReconnect ->
                        return DriveEpubUploadSummary(needsReconnect = true)
                    is SilentTokenResult.TransientFailure ->
                        return DriveEpubUploadSummary(retryableFailure = true)
                    SilentTokenResult.Inactive ->
                        return DriveEpubUploadSummary()
                }
            } catch (e: DriveQuotaException) {
                accounts.recordError(permissionId, e.message)
                return DriveEpubUploadSummary(
                    failedCount = pending.size,
                    retryableFailure = !e.isStorageQuota,
                )
            } catch (e: DriveApiException) {
                accounts.recordError(permissionId, e.message)
                return DriveEpubUploadSummary(
                    failedCount = pending.size,
                    retryableFailure = true,
                )
            }
        }
    }

    private suspend fun uploadPendingWithToken(
        permissionId: String,
        accessToken: String,
        pending: List<DriveUpload>,
    ): DriveEpubUploadSummary {
        // Resolve/create the canonical `Liseur/` folder ONLY when user-selected uploads exist.
        val resolution = driveApi.ensureLiseurFolder(accessToken)
        accounts.updateFolderId(permissionId, resolution.canonical.id)
        for (folder in resolution.allFolders) {
            driveFiles.upsert(
                DriveFile(
                    permissionId = permissionId,
                    fileId = folder.id,
                    name = folder.name,
                    kind = DriveFile.KIND_FOLDER,
                    trashed = folder.trashed,
                    createdTime = folder.createdTime,
                ),
            )
        }

        var uploadedCount = 0
        var failedCount = 0
        var anyRetryable = false

        for (item in pending) {
            val sha = item.sha256.lowercase(Locale.ROOT)
            // Deduplicate if an identical non-trashed EPUB already exists in any Liseur/ folder.
            val existingRemote = driveFiles.byKindAndSha256(permissionId, DriveFile.KIND_EPUB, sha).firstOrNull()
            if (existingRemote != null) {
                uploads.markUploaded(permissionId, sha, existingRemote.fileId, clock())
                uploadedCount++
                continue
            }

            val book = books.getByUrl(item.bookUrl)
            if (book == null || book.openableUrl == null) {
                uploads.updateState(
                    permissionId = permissionId,
                    sha256 = sha,
                    state = DriveUpload.STATE_FAILED,
                    updatedAt = clock(),
                )
                failedCount++
                continue
            }

            val totalBytes = item.sizeBytes.takeIf { it > 0L } ?: measureBookBytes(book)
            val alias = workIdentity.resolveBook(permissionId, book, computeFingerprintIfMissing = false)
            val workKey = alias?.workId?.let { workIdentity.canonicalWorkKey(permissionId, it) } ?: "sha:$sha"
            val fileName = DriveCatalogSync.sanitizeEpubFileName(book.stableTitle ?: book.title)

            uploads.updateState(
                permissionId = permissionId,
                sha256 = sha,
                state = DriveUpload.STATE_UPLOADING,
                updatedAt = clock(),
            )

            try {
                val remoteFile = driveApi.uploadResumableFile(
                    accessToken = accessToken,
                    name = fileName,
                    mimeType = EPUB_MIME_TYPE,
                    totalBytes = totalBytes,
                    parents = listOf(resolution.canonical.id),
                    appProperties = DriveWireCodec.epubAppProperties(sha, workKey),
                    existingSessionUri = item.sessionUri,
                    openStream = {
                        openBookStream(book)
                            ?: throw IOException("Could not open local book stream for ${book.url}")
                    },
                    onSessionUri = { sessionUri ->
                        val latest = uploads.get(permissionId, sha)
                        val bytes = if (latest?.sessionUri == sessionUri) latest.bytesSent else 0L
                        uploads.updateProgress(
                            permissionId = permissionId,
                            sha256 = sha,
                            state = DriveUpload.STATE_UPLOADING,
                            sessionUri = sessionUri,
                            bytesSent = bytes,
                            updatedAt = clock(),
                        )
                    },
                    onProgress = { bytesSent, sessionUri ->
                        uploads.updateProgress(
                            permissionId = permissionId,
                            sha256 = sha,
                            state = DriveUpload.STATE_UPLOADING,
                            sessionUri = sessionUri,
                            bytesSent = bytesSent,
                            updatedAt = clock(),
                        )
                    },
                )
                val finishedAt = clock()
                driveFiles.upsert(
                    DriveFile(
                        permissionId = permissionId,
                        fileId = remoteFile.id,
                        name = remoteFile.name.ifBlank { fileName },
                        kind = DriveFile.KIND_EPUB,
                        sha256 = sha,
                        size = remoteFile.size ?: totalBytes,
                        md5 = remoteFile.md5Checksum,
                        trashed = false,
                        createdTime = remoteFile.createdTime,
                    ),
                )
                uploads.markUploaded(permissionId, sha, remoteFile.id, finishedAt)
                uploadedCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveAuthExpiredException) {
                throw e
            } catch (e: DriveQuotaException) {
                // Preserve latest persisted resumable sessionUri and bytesSent across retries.
                uploads.updateState(
                    permissionId = permissionId,
                    sha256 = sha,
                    state = DriveUpload.STATE_FAILED,
                    updatedAt = clock(),
                )
                throw e
            } catch (e: Exception) {
                // Preserve latest persisted resumable sessionUri and bytesSent across retries.
                uploads.updateState(
                    permissionId = permissionId,
                    sha256 = sha,
                    state = DriveUpload.STATE_FAILED,
                    updatedAt = clock(),
                )
                failedCount++
                anyRetryable = true
            }
        }

        // Publish updated catalog media availability in `drive.appdata` ONLY after upload succeeds.
        if (uploadedCount > 0) {
            onCatalogAvailabilityChanged()
        }
        return DriveEpubUploadSummary(
            uploadedCount = uploadedCount,
            failedCount = failedCount,
            retryableFailure = anyRetryable,
        )
    }

    /**
     * Downloads an uploaded EPUB on demand via resumable HTTP Range requests,
     * verifying SHA-256 against the expected edition hash BEFORE moving the
     * file into place.
     */
    suspend fun downloadBook(
        bookUrl: String,
        onProgress: suspend (fraction: Float?) -> Unit = {},
    ): DriveEpubDownloadOutcome {
        val job = currentCoroutineContext()[Job]
        job?.let(activeDownloads::add)
        try {
            val result = downloadBookTracked(bookUrl, onProgress)
            if (result is DriveEpubDownloadOutcome.PermanentFailure || result == DriveEpubDownloadOutcome.NeedsReconnect) {
                books.setDownloadState(bookUrl, DownloadState.FAILED, null)
            }
            return result
        } finally {
            job?.let(activeDownloads::remove)
        }
    }

    private suspend fun downloadBookTracked(
        bookUrl: String,
        onProgress: suspend (fraction: Float?) -> Unit,
    ): DriveEpubDownloadOutcome {
        val tokenResult = acquireToken(null)
        val (initialToken, permissionId) = when (tokenResult) {
            is SilentTokenResult.Token -> tokenResult.accessToken to tokenResult.permissionId
            is SilentTokenResult.NeedsReconnect -> return DriveEpubDownloadOutcome.NeedsReconnect
            is SilentTokenResult.TransientFailure ->
                return DriveEpubDownloadOutcome.RetryableFailure(tokenResult.reason)
            SilentTokenResult.Inactive ->
                return DriveEpubDownloadOutcome.PermanentFailure("Google Drive sync is not active")
        }

        val book = books.getByUrl(bookUrl)
            ?: return DriveEpubDownloadOutcome.PermanentFailure("Unknown book $bookUrl")
        val source = catalogSync.resolveDownloadSource(permissionId, book)
            ?: return DriveEpubDownloadOutcome.PermanentFailure("No uploaded EPUB available in Google Drive")
        val (fileId, expectedSha) = source

        var accessToken = initialToken
        var retriedAuth = false
        while (true) {
            try {
                return downloadWithToken(book, permissionId, fileId, expectedSha, accessToken, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveAuthExpiredException) {
                if (retriedAuth) {
                    val reason = e.message ?: DriveAccountController.RECONNECT_REQUIRED_REASON
                    books.setDownloadState(bookUrl, DownloadState.FAILED, null)
                    onNeedsReconnect(permissionId, reason)
                    return DriveEpubDownloadOutcome.NeedsReconnect
                }
                retriedAuth = true
                when (val refreshed = acquireToken(accessToken)) {
                    is SilentTokenResult.Token -> if (refreshed.permissionId == permissionId) {
                        accessToken = refreshed.accessToken
                    } else {
                        books.setDownloadState(bookUrl, DownloadState.QUEUED, null)
                        return DriveEpubDownloadOutcome.RetryableFailure("Google Drive account changed")
                    }
                    is SilentTokenResult.NeedsReconnect -> return DriveEpubDownloadOutcome.NeedsReconnect
                    is SilentTokenResult.TransientFailure ->
                        return DriveEpubDownloadOutcome.RetryableFailure(refreshed.reason)
                    SilentTokenResult.Inactive ->
                        return DriveEpubDownloadOutcome.PermanentFailure("Google Drive sync is not active")
                }
            } catch (e: DriveNotFoundException) {
                catalogSync.handleDriveEpubRemoved(permissionId, fileId)
                books.setDownloadState(bookUrl, DownloadState.FAILED, null)
                return DriveEpubDownloadOutcome.PermanentFailure("EPUB file was removed from Google Drive")
            } catch (e: DriveQuotaException) {
                books.setDownloadState(bookUrl, DownloadState.QUEUED, null)
                return if (e.isStorageQuota) {
                    DriveEpubDownloadOutcome.PermanentFailure(e.message ?: "Drive quota exceeded")
                } else {
                    DriveEpubDownloadOutcome.RetryableFailure(e.message ?: "Drive rate limit")
                }
            } catch (e: Exception) {
                books.setDownloadState(bookUrl, DownloadState.QUEUED, null)
                return DriveEpubDownloadOutcome.RetryableFailure(e.message ?: "Download failed")
            }
        }
    }

    private suspend fun downloadWithToken(
        book: Book,
        permissionId: String,
        fileId: String,
        expectedSha: String,
        accessToken: String,
        onProgress: suspend (fraction: Float?) -> Unit,
    ): DriveEpubDownloadOutcome {
        val dir = booksDirProvider().apply { mkdirs() }
        val partFile = downloadPartFile(dir, book.url, expectedSha)
        val targetFile = downloadTargetFile(dir, book.url, expectedSha)

        books.setDownloadState(book.url, DownloadState.DOWNLOADING, null)
        onProgress(null)

        val startOffset = if (partFile.isFile) partFile.length().coerceAtLeast(0L) else 0L
        var restarted = false
        withContext(Dispatchers.IO) {
            FileOutputStream(partFile, startOffset > 0L).use { out ->
                driveApi.downloadResumable(
                    accessToken = accessToken,
                    fileId = fileId,
                    destination = out,
                    startOffset = startOffset,
                    onRestartFromZero = {
                        restarted = true
                        out.channel.truncate(0L)
                        out.channel.position(0L)
                    },
                    onProgress = { _ -> },
                )
            }
        }
        if (restarted && !partFile.isFile) {
            books.setDownloadState(book.url, DownloadState.FAILED, null)
            return DriveEpubDownloadOutcome.RetryableFailure("Interrupted restart")
        }

        // Verify SHA-256 strictly BEFORE moving into place.
        val computed = withContext(Dispatchers.IO) {
            FileInputStream(partFile).use(BookFingerprints::of)
        }
        if (!computed.sha256.equals(expectedSha, ignoreCase = true)) {
            partFile.delete()
            books.setDownloadState(book.url, DownloadState.FAILED, null)
            return DriveEpubDownloadOutcome.PermanentFailure(
                "Downloaded EPUB SHA-256 mismatch (expected $expectedSha, got ${computed.sha256})",
            )
        }

        currentCoroutineContext().ensureActive()
        if (accounts.active()?.permissionId != permissionId) {
            books.setDownloadState(book.url, DownloadState.QUEUED, null)
            return DriveEpubDownloadOutcome.RetryableFailure("Google Drive account changed")
        }

        // Move verified file atomically into place.
        withContext(Dispatchers.IO) {
            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
        }

        val localUri = Uri.fromFile(targetFile).toString()
        val downloadedAt = clock()
        val modifiedAt = targetFile.lastModified().takeIf { it > 0 }
        bookRemoval.downloadArrived(book.url, localUri, downloadedAt, modifiedAt)
        identities.upsert(
            BookFingerprintRow(
                bookUrl = book.url,
                sha256 = computed.sha256.lowercase(Locale.ROOT),
                partialMd5 = computed.partialMd5.lowercase(Locale.ROOT),
                fileSize = computed.size,
                fileModifiedAt = modifiedAt,
                computedAt = downloadedAt,
            ),
        )
        onProgress(1f)
        return DriveEpubDownloadOutcome.Downloaded(
            localUri = localUri,
            sha256 = computed.sha256.lowercase(Locale.ROOT),
            downloadedAt = downloadedAt,
        )
    }

    companion object {
        const val EPUB_MIME_TYPE = "application/epub+zip"

        /** Per-book download path so two catalog rows sharing an edition never collide on disk. */
        internal fun downloadTargetFile(dir: File, bookUrl: String, expectedSha: String): File {
            val bookKey = DriveCanonicalJson.sha256Hex(bookUrl.toByteArray(Charsets.UTF_8)).take(16)
            return File(dir, "drive_${bookKey}_${expectedSha.lowercase(Locale.ROOT)}.epub")
        }

        internal fun downloadPartFile(dir: File, bookUrl: String, expectedSha: String): File =
            File(downloadTargetFile(dir, bookUrl, expectedSha).path + ".part")

        private fun openDefaultStream(context: Context, book: Book): InputStream? {
            val openable = book.openableUri() ?: return null
            return if (openable.startsWith("file:")) {
                runCatching { FileInputStream(File(URI(openable))) }.getOrNull()
            } else {
                runCatching { context.contentResolver.openInputStream(openable.toUri()) }.getOrNull()
            }
        }

        private fun measureDefaultBytes(context: Context, book: Book): Long {
            val openable = book.openableUri() ?: return 0L
            return if (openable.startsWith("file:")) {
                runCatching { File(URI(openable)).length() }.getOrDefault(0L)
            } else {
                runCatching {
                    context.contentResolver.openAssetFileDescriptor(openable.toUri(), "r")?.use {
                        it.length.takeIf { len -> len >= 0L }
                    }
                }.getOrNull() ?: 0L
            }
        }
    }
}
