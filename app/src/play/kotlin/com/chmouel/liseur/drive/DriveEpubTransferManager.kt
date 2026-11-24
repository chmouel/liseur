package com.chmouel.liseur.drive

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.WorkAlias
import com.chmouel.liseur.data.library.BookFingerprintStore
import com.chmouel.liseur.data.library.BookRemoval
import com.chmouel.liseur.data.library.openableUri
import com.chmouel.liseur.domain.BookFingerprints
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
    private val nextHlc: suspend (permissionId: String) -> String,
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
    private val stagingDirProvider: () -> File = { defaultStagingDir(context) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val afterUploadRequest: suspend () -> Unit = {},
    private val beforeUploadStart: suspend () -> Unit = {},
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
                uploadState = when (upload?.state) {
                    DriveUpload.STATE_CANCELLED -> null
                    DriveUpload.STATE_REQUEUE -> DriveUpload.STATE_PENDING
                    else -> upload?.state
                },
            )
        }
    }

    /**
     * Makes the `drive_upload` queue for [permissionId] match the picker selection:
     * selected local books are queued, and any not-yet-uploaded entry the reader
     * left unchecked is dropped with its staged copy. Already-uploaded editions are
     * preserved without re-uploading.
     */
    suspend fun queueSelectedBooks(
        permissionId: String,
        selectedBookUrls: Set<String>,
    ): Int = withContext(Dispatchers.IO) {
        val now = clock()
        for (upload in uploads.forAccount(permissionId)) {
            if (upload.state == DriveUpload.STATE_UPLOADED || upload.state == DriveUpload.STATE_CANCELLED) continue
            if (upload.bookUrl in selectedBookUrls) continue
            // Every transition is conditional on the state just read, so a concurrent cleanup or
            // upload that moved the row on is never overwritten from this stale copy.
            deselect(permissionId, upload, now)
            discardStaged(permissionId, upload.sha256)
        }
        var queued = 0
        for (bookUrl in selectedBookUrls) {
            val book = books.getByUrl(bookUrl) ?: continue
            if (book.openableUrl == null) continue
            val fp = fingerprintStore.of(book) ?: identities.fingerprint(book.url)?.fingerprint ?: continue
            val sha = fp.sha256.lowercase(Locale.ROOT)
            val peerId = DriveLibrarySync.accountPeerId(permissionId)
            val alias = identities.alias(book.url, peerId)
            if (alias?.confidence == WorkAlias.REJECTED) {
                identities.upsert(alias.copy(confidence = WorkAlias.HIGH, confirmed = true, seeded = false))
            }
            val existing = uploads.get(permissionId, sha)
            if (existing?.state == DriveUpload.STATE_UPLOADED && !existing.fileId.isNullOrBlank()) {
                continue
            }
            // Re-selecting a cancelled edition never adopts its old session or file, which cleanup
            // may already be trashing: cleanup finishes first, then turns the row into a fresh upload.
            if (existing?.state == DriveUpload.STATE_REQUEUE) {
                queued++
                continue
            }
            if (existing?.state == DriveUpload.STATE_CANCELLED) {
                if (uploads.transition(permissionId, sha, existing.state, DriveUpload.STATE_REQUEUE, now) > 0) {
                    queued++
                    continue
                }
                // Cleanup finished meanwhile and removed the row: queue a fresh upload below.
            }
            val reuse = uploads.get(permissionId, sha)?.takeIf {
                it.state == DriveUpload.STATE_PENDING || it.state == DriveUpload.STATE_UPLOADING ||
                    it.state == DriveUpload.STATE_FAILED
            }
            uploads.upsert(
                DriveUpload(
                    permissionId = permissionId,
                    sha256 = sha,
                    bookUrl = book.url,
                    sizeBytes = fp.size.coerceAtLeast(0L),
                    state = DriveUpload.STATE_PENDING,
                    sessionUri = reuse?.sessionUri,
                    bytesSent = reuse?.bytesSent ?: 0L,
                    fileId = null,
                    selectedAt = reuse?.selectedAt ?: now,
                    updatedAt = now,
                ),
            )
            queued++
        }
        queued
    }

    /**
     * Drops or cancels one deselected row. Every step is conditional on the state last read; when a
     * worker moved the row on meanwhile it is re-read, so a session attached after the picker's
     * snapshot is kept as a cleanup obligation instead of being deleted with the row.
     */
    private suspend fun deselect(permissionId: String, observed: DriveUpload, now: Long) {
        var current: DriveUpload? = observed
        while (current != null) {
            when {
                current.state == DriveUpload.STATE_UPLOADED || current.state == DriveUpload.STATE_CANCELLED -> return
                // REQUEUE was deselected again before its cleanup ran: just the cleanup remains. An
                // uploading row's session may already have created the file, so cleanup resolves it.
                current.state == DriveUpload.STATE_REQUEUE || current.state == DriveUpload.STATE_UPLOADING ||
                    current.sessionUri != null || current.fileId != null ->
                    if (uploads.transition(permissionId, current.sha256, current.state, DriveUpload.STATE_CANCELLED, now) > 0) return
                else -> if (uploads.deleteIfIdle(permissionId, current.sha256, current.state) > 0) return
            }
            current = uploads.get(permissionId, current.sha256)
        }
    }

    /**
     * Removes whatever deselected uploads left in Drive: a created file is trashed, a session is
     * asked whether it completed (and its file trashed if so). `clean` is false when a transient
     * failure or a still-running request leaves rows for a later run; `requeued` when a
     * re-selected edition became pending again.
     */
    private suspend fun trashCancelledUploads(permissionId: String, accessToken: String): CleanupResult {
        var clean = true
        var requeued = false
        for (upload in uploads.forAccount(permissionId)) {
            if (upload.state != DriveUpload.STATE_CANCELLED && upload.state != DriveUpload.STATE_REQUEUE) continue
            // A cancelled worker's request may still be running: until it returns, an unfinished
            // session can yet complete, so its obligation stays.
            if (upload.fileId == null && upload.sessionUri in inFlightSessions) {
                clean = false
                continue
            }
            val resolved = try {
                val fileId = upload.fileId ?: upload.sessionUri?.let { session ->
                    when (val status = driveApi.queryResumableUploadOffset(accessToken, session, upload.sizeBytes)) {
                        is ResumableUploadStatus.Completed -> status.file.id
                        // An unfinished or expired session never created a visible file.
                        else -> null
                    }
                }
                if (fileId != null) {
                    try {
                        driveApi.trashFile(accessToken, fileId)
                    } catch (_: DriveNotFoundException) {
                        // Already gone.
                    }
                    driveFiles.setTrashed(permissionId, fileId, true)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveAuthExpiredException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!resolved) {
                clean = false
                continue
            }
            // Conditional on the current state: the reader may have re-selected or deselected
            // the edition while the session was probed or the file trashed.
            if (uploads.deleteIfCancelled(permissionId, upload.sha256) == 0 &&
                uploads.requeueAfterCleanup(permissionId, upload.sha256, clock()) > 0
            ) {
                requeued = true
            }
        }
        return CleanupResult(clean, requeued)
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
        var initialCleanupDone = false
        while (true) {
            if (!isActive(permissionId)) return DriveEpubUploadSummary()
            var pending: List<DriveUpload> = emptyList()
            try {
                if (!initialCleanupDone) {
                    trashCancelledUploads(permissionId, accessToken)
                    initialCleanupDone = true
                }
                pending = uploads.pendingForAccount(permissionId)
                if (pending.isEmpty()) {
                    // Cleanup-only work still asks to be retried until every cancelled copy is gone.
                    val cleanup = trashCancelledUploads(permissionId, accessToken)
                    // Cleanup may have turned a re-selected edition back into pending work: drain it.
                    if (cleanup.requeued) continue
                    return DriveEpubUploadSummary(retryableFailure = !cleanup.clean)
                }
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
            if (!isActive(permissionId)) break
            val sha = item.sha256.lowercase(Locale.ROOT)
            val queuedBeforeStage = currentQueuedUpload(permissionId, sha, item.bookUrl)
            if (queuedBeforeStage == null) {
                discardStaged(permissionId, sha)
                continue
            }
            val book = books.getByUrl(queuedBeforeStage.bookUrl)
            if (book == null || book.openableUrl == null) {
                if (markFailedIfSelected(permissionId, sha, item.bookUrl)) {
                    failedCount++
                } else {
                    discardStaged(permissionId, sha)
                }
                continue
            }

            val currentFingerprint = try {
                withContext(Dispatchers.IO) { openBookStream(book)?.use(BookFingerprints::of) }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            if (currentFingerprint == null) {
                if (markFailedIfSelected(permissionId, sha, item.bookUrl)) failedCount++
                anyRetryable = true
                continue
            }
            if (currentFingerprint.sha256.lowercase(Locale.ROOT) != sha || currentFingerprint.size != item.sizeBytes) {
                val newSha = currentFingerprint.sha256.lowercase(Locale.ROOT)
                val saved = identities.saveFingerprint(
                    book,
                    BookFingerprintRow(book.url, newSha, currentFingerprint.partialMd5, currentFingerprint.size, book.fileModifiedAt, clock()),
                )
                if (!saved) {
                    markFailedIfSelected(permissionId, sha, item.bookUrl)
                } else {
                    store.withTransaction {
                        if (currentQueuedUpload(permissionId, sha, item.bookUrl) == null || !isActive(permissionId)) return@withTransaction
                        val replacement = uploads.get(permissionId, newSha)
                        uploads.delete(permissionId, sha)
                        if (replacement == null) {
                            uploads.upsert(item.copy(sha256 = newSha, sizeBytes = currentFingerprint.size, state = DriveUpload.STATE_PENDING,
                                sessionUri = null, bytesSent = 0L, fileId = null, updatedAt = clock()))
                        }
                    }
                }
                discardStaged(permissionId, sha)
                anyRetryable = true
                continue
            }

            // Deduplicate if an identical non-trashed EPUB already exists in any Liseur/ folder.
            val existingRemote = driveFiles.byKindAndSha256(permissionId, DriveFile.KIND_EPUB, sha).firstOrNull()
            if (existingRemote != null) {
                if (uploads.markUploadedIfSelected(permissionId, sha, existingRemote.fileId, clock()) > 0) {
                    uploadedCount++
                }
                discardStaged(permissionId, sha)
                continue
            }

            val staged = when (val staging = stageUpload(permissionId, sha, book)) {
                is StagingResult.Staged -> staging.file
                StagingResult.Unreadable -> {
                    if (markFailedIfSelected(permissionId, sha, item.bookUrl)) {
                        failedCount++
                        anyRetryable = true
                    } else {
                        discardStaged(permissionId, sha)
                    }
                    continue
                }
                StagingResult.Changed -> {
                    // The file at this book's location is no longer the one the reader chose,
                    // so the selection and any resumable session for it are dropped.
                    if (uploads.deleteIfSelected(permissionId, sha, item.bookUrl) > 0) {
                        failedCount++
                    }
                    discardStaged(permissionId, sha)
                    continue
                }
            }
            if (!isActive(permissionId)) break
            val queuedBeforeUpload = currentQueuedUpload(permissionId, sha, item.bookUrl)
            if (queuedBeforeUpload == null) {
                discardStaged(permissionId, sha)
                continue
            }
            if (!staged.isFile) {
                if (markFailedIfSelected(permissionId, sha, item.bookUrl)) {
                    failedCount++
                    anyRetryable = true
                } else {
                    discardStaged(permissionId, sha)
                }
                continue
            }
            val totalBytes = staged.length()
            val alias = workIdentity.resolveBook(permissionId, book, computeFingerprintIfMissing = false)
            val workKey = alias?.workId?.let { workIdentity.canonicalWorkKey(permissionId, it) } ?: "sha:$sha"
            val fileName = DriveCatalogSync.sanitizeEpubFileName(book.stableTitle ?: book.title)

            beforeUploadStart()
            // A deselection since the checks above keeps its CANCELLED/REQUEUE row; do not start.
            if (uploads.updateState(permissionId, sha, DriveUpload.STATE_UPLOADING, clock()) == 0) {
                discardStaged(permissionId, sha)
                continue
            }

            var activeSession: String? = null
            // A resumed session can complete while its request runs: cleanup must not race it.
            val resumedSession = queuedBeforeUpload.sessionUri
            try {
                resumedSession?.let(inFlightSessions::add)
                val remoteFile = run {
                    driveApi.uploadResumableFile(
                        accessToken = accessToken,
                        name = fileName,
                        mimeType = EPUB_MIME_TYPE,
                        totalBytes = totalBytes,
                        parents = listOf(resolution.canonical.id),
                        appProperties = DriveWireCodec.epubAppProperties(sha, workKey),
                        existingSessionUri = queuedBeforeUpload.sessionUri,
                        openStream = { FileInputStream(staged) },
                        onSessionUri = { sessionUri ->
                            activeSession?.let(inFlightSessions::remove)
                            activeSession = sessionUri
                            inFlightSessions.add(sessionUri)
                            val latest = currentQueuedUpload(permissionId, sha, item.bookUrl)
                                ?: throw UploadDeselectedException()
                            val bytes = if (latest.sessionUri == sessionUri) latest.bytesSent else 0L
                            // Conditional on the row still being selected: a deselection since the read
                            // above keeps its CANCELLED/REQUEUE state and its older session.
                            val attached = uploads.updateProgress(
                                permissionId = permissionId,
                                sha256 = sha,
                                state = DriveUpload.STATE_UPLOADING,
                                sessionUri = sessionUri,
                                bytesSent = bytes,
                                updatedAt = clock(),
                            )
                            if (attached == 0) throw UploadDeselectedException()
                        },
                        onProgress = { bytesSent, sessionUri ->
                            val recorded = uploads.updateProgress(
                                permissionId = permissionId,
                                sha256 = sha,
                                state = DriveUpload.STATE_UPLOADING,
                                sessionUri = sessionUri,
                                bytesSent = bytesSent,
                                updatedAt = clock(),
                            )
                            // Only abort while bytes remain; if the final PUT already created the
                            // remote file, let uploadResumableFile return its ID so we can trash it.
                            if (recorded == 0 && bytesSent < totalBytes) {
                                throw UploadDeselectedException()
                            }
                        },
                    )
                }
                afterUploadRequest()
                // Deselected during the final PUT: record the created file before trying to
                // trash it, so a failed or interrupted cleanup is retried and never advertised.
                suspend fun cleanUpDeselectedCompletion() {
                    // One transaction, so a re-selection cannot slip between reading the state and
                    // recording the file.
                    store.withTransaction {
                        val existing = uploads.get(permissionId, sha)
                        val requeued = existing?.state == DriveUpload.STATE_REQUEUE
                        uploads.upsert(
                            DriveUpload(
                                permissionId = permissionId,
                                sha256 = sha,
                                bookUrl = item.bookUrl,
                                sizeBytes = totalBytes,
                                state = if (requeued) DriveUpload.STATE_REQUEUE else DriveUpload.STATE_CANCELLED,
                                fileId = remoteFile.id,
                                selectedAt = clock(),
                                updatedAt = clock(),
                            ),
                        )
                    }
                    discardStaged(permissionId, sha)
                    trashCancelledUploads(permissionId, accessToken)
                }
                if (currentQueuedUpload(permissionId, sha, item.bookUrl) == null) {
                    cleanUpDeselectedCompletion()
                    continue
                }
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
                // Conditional: the reader may have deselected the edition since the check above, while any
                // still-selected state (a picker reconfirmation resets UPLOADING to PENDING) completes.
                if (uploads.markUploadedIfSelected(permissionId, sha, remoteFile.id, finishedAt) == 0) {
                    driveFiles.setTrashed(permissionId, remoteFile.id, true)
                    cleanUpDeselectedCompletion()
                    continue
                }
                discardStaged(permissionId, sha)
                uploadedCount++
            } catch (_: UploadDeselectedException) {
                discardStaged(permissionId, sha)
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveAuthExpiredException) {
                throw e
            } catch (e: DriveQuotaException) {
                // Preserve latest persisted resumable sessionUri and bytesSent across retries.
                if (markFailedIfSelected(permissionId, sha, item.bookUrl)) {
                } else {
                    discardStaged(permissionId, sha)
                }
                throw e
            } catch (e: Exception) {
                // Preserve latest persisted resumable sessionUri and bytesSent across retries.
                if (markFailedIfSelected(permissionId, sha, item.bookUrl)) {
                    failedCount++
                    anyRetryable = true
                } else {
                    discardStaged(permissionId, sha)
                }
            } finally {
                // Held past local finalization: until the completion is recorded or handed to
                // cleanup, cleanup must not trash the file and requeue the row under it.
                activeSession?.let(inFlightSessions::remove)
                resumedSession?.let(inFlightSessions::remove)
            }
        }

        // Publish updated catalog media availability in `drive.appdata` ONLY after upload succeeds.
        if (uploadedCount > 0) {
            onCatalogAvailabilityChanged()
        }
        // Uploads cancelled during this batch are cleaned up now; a failure asks for a retry.
        val cleanupDone = trashCancelledUploads(permissionId, accessToken)
        return DriveEpubUploadSummary(
            uploadedCount = uploadedCount,
            failedCount = failedCount,
            // Pending work that cleanup re-queued also needs another run.
            retryableFailure = anyRetryable || !cleanupDone.clean || cleanupDone.requeued,
        )
    }

    private suspend fun isActive(permissionId: String): Boolean =
        accounts.get(permissionId)?.state == DriveAccount.STATE_ACTIVE

    private suspend fun currentQueuedUpload(
        permissionId: String,
        sha: String,
        bookUrl: String,
    ): DriveUpload? = uploads.get(permissionId, sha)?.takeIf {
        it.bookUrl == bookUrl &&
            (it.state == DriveUpload.STATE_PENDING || it.state == DriveUpload.STATE_UPLOADING || it.state == DriveUpload.STATE_FAILED)
    }

    private suspend fun markFailedIfSelected(permissionId: String, sha: String, bookUrl: String): Boolean =
        currentQueuedUpload(permissionId, sha, bookUrl) != null &&
            uploads.updateState(permissionId, sha, DriveUpload.STATE_FAILED, clock()) > 0

    private class UploadDeselectedException : Exception()

    private data class CleanupResult(val clean: Boolean, val requeued: Boolean)

    private sealed interface StagingResult {
        data class Staged(val file: File) : StagingResult
        data object Unreadable : StagingResult
        data object Changed : StagingResult
    }

    private fun stagedFile(permissionId: String, sha: String): File =
        Companion.stagedFile(stagingDirProvider(), permissionId, sha)

    /**
     * Copies the selected EPUB into private storage once and checks that the copy still
     * hashes to [sha], so the bytes sent (and any resumed session) are exactly the file
     * the reader chose even if the book's location is later overwritten.
     */
    private suspend fun stageUpload(permissionId: String, sha: String, book: Book): StagingResult =
        withContext(Dispatchers.IO) {
            val target = stagedFile(permissionId, sha)
            if (target.isFile) return@withContext StagingResult.Staged(target)
            target.parentFile?.mkdirs()
            val part = File(target.path + ".part")
            try {
                val input = openBookStream(book) ?: run {
                    part.delete()
                    return@withContext StagingResult.Unreadable
                }
                input.use { src -> FileOutputStream(part).use { out -> src.copyTo(out) } }
                val actual = FileInputStream(part).use(BookFingerprints::of).sha256
                if (!actual.equals(sha, ignoreCase = true)) {
                    part.delete()
                    return@withContext StagingResult.Changed
                }
                if (!part.renameTo(target)) {
                    part.delete()
                    return@withContext StagingResult.Unreadable
                }
                StagingResult.Staged(target)
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (_: Exception) {
                part.delete()
                StagingResult.Unreadable
            }
        }

    /** Drops the private copy kept for a finished or cancelled upload of [sha]. */
    suspend fun discardStaged(permissionId: String, sha: String) {
        Companion.discardStaged(stagingDirProvider(), permissionId, sha)
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
                catalogSync.handleDriveEpubRemoved(permissionId, fileId) { nextHlc(permissionId) }
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
        /** Upload sessions whose HTTP request is still running in this process. */
        private val inFlightSessions: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        const val EPUB_MIME_TYPE = "application/epub+zip"
        private const val STAGING_DIR_NAME = "drive_upload_staging"

        internal fun defaultStagingDir(context: Context): File =
            File(context.applicationContext.noBackupFilesDir, STAGING_DIR_NAME)

        internal fun stagedFile(stagingDir: File, permissionId: String, sha: String): File =
            File(stagingDir, "${permissionId.hashCode().toUInt()}_${sha.lowercase(Locale.ROOT)}.epub")

        internal suspend fun discardStaged(stagingDir: File, permissionId: String, sha: String) {
            withContext(Dispatchers.IO) {
                val file = stagedFile(stagingDir, permissionId, sha)
                file.delete()
                File(file.path + ".part").delete()
            }
        }

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
