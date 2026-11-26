package com.chmouel.liseur.drive

import android.os.Build
import androidx.room.withTransaction
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.data.remote.SyncOutcome
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Result of a `drive.appdata` sync run. */
internal data class DriveSyncRunSummary(
    val outcome: SyncOutcome,
    val appliedDeltas: Int = 0,
    val publishedBatches: Int = 0,
    val pausedBySchemaVersion: Boolean = false,
    /** `domain:key` of records too large for any batch; they stay unpublished until they shrink. */
    val oversizedRecords: List<String> = emptyList(),
    val compacted: Boolean = false,
    val prunedDeltaCount: Int = 0,
    val prunedSnapshotPartCount: Int = 0,
    val compactionError: String? = null,
)

private const val OVERSIZED_REPORT_LIMIT = 5

private data class PublishResult(val batches: Int, val oversized: List<String> = emptyList())

/**
 * Implements `appDataFolder` delta batch transport, `changes.getStartPageToken()`
 * bootstrap enumeration, `changes.list(spaces="appDataFolder,drive")` incremental
 * consumption, expired-token full rescan, schema-version pausing, and `batchId`-
 * idempotent outbox publishing (ADR-0078 & `docs/DRIVE_SYNC.md` §2).
 */
internal class DriveChangeEngine(
    private val store: DriveSyncDatabase,
    private val driveApi: DriveRemoteApi,
    private val workIdentity: DriveWorkIdentity,
    private val catalogSync: DriveCatalogSync,
    private val positionSync: DrivePositionSync,
    private val acquireToken: suspend (staleToken: String?) -> SilentTokenResult,
    private val ensureDeviceId: suspend () -> String,
    private val annotationSync: DriveAnnotationSync? = null,
    private val evaluationSync: DriveEvaluationSync? = null,
    private val metadataSync: DriveMetadataSync? = null,
    private val seriesSync: DriveSeriesSync? = null,
    private val vocabularySync: DriveVocabularySync? = null,
    private val sessionSync: DriveSessionSync? = null,
    private val deviceLabelProvider: () -> String = {
        listOfNotNull(Build.MANUFACTURER?.takeIf { it.isNotBlank() }, Build.MODEL?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .ifBlank { "Android" }
    },
    private val appVersionCodeProvider: () -> Int = { 1 },
    private val onNeedsReconnect: suspend (permissionId: String, reason: String) -> Unit = { permissionId, reason ->
        store.withTransaction {
            if (store.accounts().active()?.permissionId == permissionId) {
                store.accounts().setState(permissionId, DriveAccount.STATE_NEEDS_RECONNECT)
                store.accounts().recordError(permissionId, reason)
            }
        }
    },
    private val compactionDeltaThreshold: Int = DriveCompactor.DEFAULT_DELTA_COUNT_THRESHOLD,
    private val compactionBytesThreshold: Long = DriveCompactor.DEFAULT_DELTA_BYTES_THRESHOLD,
    private val compactionMaxPartBytes: Int = DriveWireCodec.MAX_SNAPSHOT_PART_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val runMutex = Mutex()
    private val activeJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()
    internal val compactor = DriveCompactor(
        store = store,
        driveApi = driveApi,
        deltaCountThreshold = compactionDeltaThreshold,
        deltaBytesThreshold = compactionBytesThreshold,
        maxPartBytes = compactionMaxPartBytes,
        clock = clock,
    )

    private val accounts get() = store.accounts()
    private val devices get() = store.devices()
    private val cursors get() = store.cursors()
    private val files get() = store.files()
    private val outbox get() = store.outbox()
    private val recordStates get() = store.recordStates()
    private val resolutions get() = store.resolutions()
    private val conflicts get() = store.conflicts()

    /**
     * Cancels every in-flight `sync()` run and [withSyncLock] holder (including runs started
     * through the shared position coordinator) and waits until the mutex is released. The
     * caller's own job is never cancelled. Cancellation repeats until the mutex is free so a
     * holder that registers after the first pass cannot leave the caller waiting on it.
     */
    suspend fun cancelAndDrain() {
        val self = currentCoroutineContext()[Job]
        while (true) {
            activeJobs.forEach { if (it !== self) it.cancel() }
            if (runMutex.tryLock()) {
                runMutex.unlock()
                return
            }
            delay(DRAIN_POLL_MS)
        }
    }

    internal val isSyncLockHeld: Boolean get() = runMutex.isLocked

    /**
     * Runs [block] while no sync cycle is running, so local commits never interleave with pulls or compaction.
     * The holder is cancelled by [cancelAndDrain], so it can wait on the account-switch lock without deadlocking a switch.
     */
    suspend fun <T> withSyncLock(block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job]
        val registered = job != null && activeJobs.add(job)
        return try {
            runMutex.withLock { block() }
        } finally {
            if (registered) activeJobs.remove(job)
        }
    }

    /**
     * Runs a full or single-book `drive.appdata` sync cycle:
     * 1. Acquire silent access token (retrying once on 401 before pausing for reconnect).
     * 2. Pull remote changes (bootstrap if `start_page_token == null` or expired, else `changes.list`).
     * 3. Publish dirty local records via frozen `drive_outbox` batches + update manifest.
     */
    suspend fun sync(
        bookUrlFilter: String? = null,
        forceManifestRescan: Boolean = false,
    ): DriveSyncRunSummary {
        val job = currentCoroutineContext()[Job]
        job?.let(activeJobs::add)
        return try {
            runMutex.withLock {
                val initialToken = acquireToken(null)
                val (token, permissionId) = when (initialToken) {
                    is SilentTokenResult.Token -> initialToken.accessToken to initialToken.permissionId
                    is SilentTokenResult.NeedsReconnect ->
                        return@withLock DriveSyncRunSummary(SyncOutcome.Failure(SyncFailure.Unauthorised))
                    is SilentTokenResult.TransientFailure ->
                        return@withLock DriveSyncRunSummary(SyncOutcome.Failure(SyncFailure.Offline))
                    SilentTokenResult.Inactive ->
                        return@withLock DriveSyncRunSummary(SyncOutcome.NotApplicable)
                }

                executeWithAuthRetry(permissionId, token) { activeToken ->
                    syncLocked(
                        permissionId = permissionId,
                        accessToken = activeToken,
                        bookUrlFilter = bookUrlFilter,
                        forceManifestRescan = forceManifestRescan,
                    )
                }
            }
        } finally {
            job?.let(activeJobs::remove)
        }
    }

    /** Advances and persists the monotonic HLC for [permissionId]. */
    suspend fun nextHlc(permissionId: String): String {
        val deviceId = ensureDeviceId()
        val now = clock()
        return store.withTransaction {
            val account = requireNotNull(accounts.get(permissionId)) {
                "Drive account $permissionId missing when advancing HLC"
            }
            val next = DriveHlc.next(
                currentWallMs = account.hlcWallMs,
                currentCounter = account.hlcCounter,
                nowMs = now,
                deviceId = deviceId,
            )
            accounts.updateHlc(permissionId, next.wallMs, next.counter)
            next.format()
        }
    }

    /** Merges a remote HLC string into the local account's persisted clock. */
    private suspend fun observeRemoteHlc(permissionId: String, rawHlc: String?) {
        val parsed = rawHlc?.let(DriveHlc::parse) ?: return
        val deviceId = ensureDeviceId()
        val now = clock()
        store.withTransaction {
            val account = accounts.get(permissionId) ?: return@withTransaction
            val merged = DriveHlc.observe(
                currentWallMs = account.hlcWallMs,
                currentCounter = account.hlcCounter,
                remote = parsed,
                nowMs = now,
                localDeviceId = deviceId,
            )
            accounts.updateHlc(permissionId, merged.wallMs, merged.counter)
        }
    }

    private suspend fun executeWithAuthRetry(
        permissionId: String,
        initialToken: String,
        block: suspend (String) -> DriveSyncRunSummary,
    ): DriveSyncRunSummary {
        var currentToken = initialToken
        var retriedAuth = false
        while (true) {
            try {
                return block(currentToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveAuthExpiredException) {
                if (retriedAuth) {
                    val reason = e.message ?: DriveAccountController.RECONNECT_REQUIRED_REASON
                    onNeedsReconnect(permissionId, reason)
                    return DriveSyncRunSummary(SyncOutcome.Failure(SyncFailure.Unauthorised))
                }
                retriedAuth = true
                when (val refreshed = acquireToken(currentToken)) {
                    is SilentTokenResult.Token -> if (refreshed.permissionId == permissionId) {
                        currentToken = refreshed.accessToken
                    } else {
                        return DriveSyncRunSummary(SyncOutcome.NotApplicable)
                    }
                    is SilentTokenResult.NeedsReconnect ->
                        return DriveSyncRunSummary(SyncOutcome.Failure(SyncFailure.Unauthorised))
                    is SilentTokenResult.TransientFailure ->
                        return DriveSyncRunSummary(SyncOutcome.Failure(SyncFailure.Offline))
                    SilentTokenResult.Inactive ->
                        return DriveSyncRunSummary(SyncOutcome.NotApplicable)
                }
            } catch (e: DriveQuotaException) {
                accounts.recordError(permissionId, e.message)
                val failure = if (e.isStorageQuota) SyncFailure.Forbidden else SyncFailure.Offline
                return DriveSyncRunSummary(SyncOutcome.Failure(failure))
            } catch (e: DriveApiException) {
                accounts.recordError(permissionId, e.message)
                return DriveSyncRunSummary(SyncOutcome.Failure(SyncFailure.Offline))
            } catch (e: IOException) {
                accounts.recordError(permissionId, e.message)
                return DriveSyncRunSummary(SyncOutcome.Failure(SyncFailure.Offline))
            }
        }
    }

    private suspend fun syncLocked(
        permissionId: String,
        accessToken: String,
        bookUrlFilter: String?,
        forceManifestRescan: Boolean,
    ): DriveSyncRunSummary {
        val account = accounts.get(permissionId)
            ?: return DriveSyncRunSummary(SyncOutcome.NotApplicable)
        if (account.state != DriveAccount.STATE_ACTIVE) {
            return DriveSyncRunSummary(SyncOutcome.NotApplicable)
        }
        val localDeviceId = ensureDeviceId()

        // Finish any local cleanup a crash interrupted after a durable catalog tombstone.
        catalogSync.recoverPendingCleanups(permissionId)

        // Ensure local books with fingerprints & metadata have deterministic Drive aliases
        // and eligible local sessions have content-derived remote_session_id BEFORE pulling.
        workIdentity.resolveAllLocalBooks(permissionId)
        sessionSync?.ensureLocalRemoteSessionIds(permissionId)

        // Finish forgets interrupted after their reset was persisted, before pulls can restore the peak.
        positionSync.reapplyPendingResets(permissionId, localDeviceId)

        // Clear any persisted domain/stream pauses whose advertised schema versions
        // are now supported by this build, preserving appliedSeq for replay.
        val unpausedDevices = clearNowSupportedDomainPauses(permissionId)

        // Recover any interrupted `RESOLVING` local conflict resolutions & drain offline EPUB trashes.
        recoverResolvingConflicts(permissionId, localDeviceId, accessToken)
        catalogSync.drainPendingEpubTrashes(permissionId, driveApi, accessToken)

        val needsBootstrapForUnpaused = unpausedDevices.any { devId ->
            files.byDeviceAndKind(permissionId, devId, DriveFile.KIND_DELTA).isEmpty() &&
                files.byDeviceAndKind(permissionId, devId, DriveFile.KIND_MANIFEST).isEmpty()
        }

        // 1. Pull remote state (bootstrap or incremental changes.list).
        val appliedDeltas = if (account.startPageToken.isNullOrBlank() || forceManifestRescan || needsBootstrapForUnpaused) {
            bootstrapPull(permissionId, localDeviceId, accessToken)
        } else {
            try {
                incrementalPull(
                    permissionId = permissionId,
                    localDeviceId = localDeviceId,
                    accessToken = accessToken,
                    startToken = account.startPageToken,
                )
            } catch (_: DriveInvalidPageTokenException) {
                // Expired or invalid pageToken: clear and fall back to idempotent full rescan.
                accounts.updateStartPageToken(permissionId, null)
                bootstrapPull(permissionId, localDeviceId, accessToken)
            }
        }

        if (!isAccountActive(permissionId)) {
            return DriveSyncRunSummary(SyncOutcome.NotApplicable)
        }

        // Catch newly matched or re-keyed books up on remote positions received earlier.
        positionSync.seedUnseededAliases(permissionId, localDeviceId)

        if (!isAccountActive(permissionId)) {
            return DriveSyncRunSummary(SyncOutcome.NotApplicable)
        }

        // Retained history whose work got its book back during this pull (or a previous,
        // interrupted one) replays now, before local diffing can publish over it.
        materializeHeldRecords(permissionId, localDeviceId, accessToken)

        // History kept under a synthetic key follows the work onto the book that now answers for it.
        workIdentity.reattachHiddenHistory(permissionId)

        // 1b. Reconcile any interrupted pending snapshot publication against the remote manifest
        // BEFORE `publishPending` rewrites the manifest, so a crash between remote manifest
        // publication and local state commit is adopted cleanly even if new local edits exist.
        val pendingRecovery = compactor.reconcilePublishedStateIfPending(
            permissionId = permissionId,
            localDeviceId = localDeviceId,
            accessToken = accessToken,
        )

        if (!isAccountActive(permissionId)) {
            return DriveSyncRunSummary(SyncOutcome.NotApplicable)
        }

        // 2. Publish outgoing batches (first draining any frozen in-flight outbox entry).
        val published = publishPending(
            permissionId = permissionId,
            localDeviceId = localDeviceId,
            accessToken = accessToken,
            bookUrlFilter = bookUrlFilter,
        )

        if (!isAccountActive(permissionId)) {
            return DriveSyncRunSummary(SyncOutcome.NotApplicable)
        }

        // 3. Compact per-device snapshot & prune superseded deltas/snapshots on full sync runs.
        val compaction = if (bookUrlFilter == null) {
            compactor.compactIfNeeded(
                permissionId = permissionId,
                localDeviceId = localDeviceId,
                accessToken = accessToken,
                nextHlc = { nextHlc(permissionId) },
                publishManifest = { snap, prevSnap ->
                    publishManifest(
                        permissionId = permissionId,
                        localDeviceId = localDeviceId,
                        accessToken = accessToken,
                        snapshotOverride = snap,
                        previousSnapshotOverride = prevSnap,
                    )
                },
            )
        } else {
            DriveCompactionResult()
        }

        if (!isAccountActive(permissionId)) {
            return DriveSyncRunSummary(SyncOutcome.NotApplicable)
        }

        val paused = cursors.pausedForAccount(permissionId).isNotEmpty()
        val compacted = compaction.compacted || (pendingRecovery?.compacted == true)
        val prunedDeltas = compaction.prunedDeltaCount + (pendingRecovery?.prunedDeltaCount ?: 0)
        val prunedParts = compaction.prunedSnapshotPartCount + (pendingRecovery?.prunedSnapshotPartCount ?: 0)
        if (published.oversized.isNotEmpty()) {
            accounts.recordError(
                permissionId,
                "${published.oversized.size} record(s) are too large to sync and were left unsent: " +
                    published.oversized.take(OVERSIZED_REPORT_LIMIT).joinToString(", ") +
                    ". Shorten or remove the oversized content.",
            )
            return DriveSyncRunSummary(
                outcome = SyncOutcome.Partial(SyncFailure.Forbidden),
                appliedDeltas = appliedDeltas,
                publishedBatches = published.batches,
                pausedBySchemaVersion = paused,
                oversizedRecords = published.oversized,
                compacted = compacted,
                prunedDeltaCount = prunedDeltas,
                prunedSnapshotPartCount = prunedParts,
                compactionError = compaction.failureReason,
            )
        }
        accounts.recordSuccess(permissionId, clock())
        if (compaction.failureReason != null) {
            accounts.recordError(permissionId, compaction.failureReason)
        }
        return DriveSyncRunSummary(
            outcome = SyncOutcome.Success,
            appliedDeltas = appliedDeltas,
            publishedBatches = published.batches,
            pausedBySchemaVersion = paused,
            compacted = compacted,
            prunedDeltaCount = prunedDeltas,
            prunedSnapshotPartCount = prunedParts,
            compactionError = compaction.failureReason,
        )
    }

    private suspend fun isAccountActive(permissionId: String): Boolean =
        accounts.get(permissionId)?.state == DriveAccount.STATE_ACTIVE

    /**
     * First-connection / expired-token bootstrap (ADR-0078):
     * 1. Call `changes.getStartPageToken()` FIRST (no `spaces`).
     * 2. Enumerate `appDataFolder` manifests, snapshots, and deltas.
     * 3. Enumerate app-created `Liseur/` folders and EPUBs in `spaces=drive`.
     * 4. Apply snapshots + contiguous deltas per remote device.
     * 5. Commit `start_page_token` ONLY after local DB writes succeed.
     */
    private suspend fun bootstrapPull(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
    ): Int {
        // Step 1: capture startPageToken BEFORE listing so concurrent writes aren't missed.
        val pendingStartPageToken = driveApi.getStartPageToken(accessToken)

        // Step 2: enumerate appDataFolder files and reconcile pruned deltas/snapshots.
        val appDataFiles = driveApi.listAppDataFiles(accessToken)
        store.withTransaction {
            val presentAppDataIds = appDataFiles.filter { !it.trashed }.map { it.id }.toSet()
            for (remote in appDataFiles) {
                if (remote.trashed) continue
                indexRemoteFile(permissionId, remote)
            }
            for (kind in listOf(DriveFile.KIND_DELTA, DriveFile.KIND_SNAPSHOT)) {
                for (indexed in files.byKind(permissionId, kind)) {
                    if (!indexed.trashed && indexed.fileId !in presentAppDataIds) {
                        files.setTrashed(permissionId, indexed.fileId, true)
                    }
                }
            }
        }

        // Step 3: enumerate Liseur folders (canonical = oldest createdTime, id) & Drive EPUBs.
        val folders = driveApi.listLiseurFolders(accessToken)
        val epubs = driveApi.listDriveEpubFiles(accessToken)
        val missingEpubFileIds = mutableListOf<String>()
        store.withTransaction {
            val presentEpubIds = epubs.filter { !it.trashed }.map { it.id }.toSet()
            folders.forEach { folder ->
                files.upsert(
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
            if (folders.isNotEmpty()) {
                accounts.updateFolderId(permissionId, folders.first().id)
            }
            epubs.forEach { epub ->
                indexRemoteFile(permissionId, epub)
            }
            // Retrying a trashed file completes library updates interrupted after the Drive transaction.
            for (indexed in files.byKind(permissionId, DriveFile.KIND_EPUB)) {
                if (indexed.fileId !in presentEpubIds) {
                    missingEpubFileIds.add(indexed.fileId)
                }
            }
        }
        for (missingFileId in missingEpubFileIds) {
            catalogSync.handleDriveEpubRemoved(permissionId, missingFileId) { nextHlc(permissionId) }
        }

        // Step 4: read remote manifests, snapshots, and apply contiguous deltas.
        val applied = processDiscoveredRemoteDevices(
            permissionId = permissionId,
            localDeviceId = localDeviceId,
            accessToken = accessToken,
        )

        // Step 5: commit startPageToken ONLY after all DB writes succeed.
        accounts.updateStartPageToken(permissionId, pendingStartPageToken)
        return applied
    }

    /**
     * Incremental pull via `changes.list(pageToken, spaces="appDataFolder,drive")`.
     * Commits `newStartPageToken` only after applying discovered changes.
     */
    private suspend fun incrementalPull(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
        startToken: String,
    ): Int {
        var pageToken: String? = startToken
        var newStartPageToken: String? = null

        while (pageToken != null) {
            val page = driveApi.listChanges(accessToken, pageToken)
            for (change in page.changes) {
                val file = change.file
                if (change.removed || file == null || file.trashed) {
                    val existing = files.get(permissionId, change.fileId)
                    if (existing != null) {
                        files.setTrashed(permissionId, change.fileId, true)
                        if (existing.kind == DriveFile.KIND_EPUB) {
                            catalogSync.handleDriveEpubRemoved(permissionId, change.fileId) { nextHlc(permissionId) }
                        }
                    }
                    continue
                }
                val indexed = indexRemoteFile(permissionId, file) ?: continue
                when (indexed.kind) {
                    DriveFile.KIND_FOLDER -> {
                        val allFolders = files.byKind(permissionId, DriveFile.KIND_FOLDER)
                            .filter { !it.trashed }
                            .sortedWith(compareBy({ it.createdTime ?: "" }, { it.fileId }))
                        allFolders.firstOrNull()?.let { canonical ->
                            accounts.updateFolderId(permissionId, canonical.fileId)
                        }
                    }
                }
            }
            pageToken = page.nextPageToken
            if (page.newStartPageToken != null) {
                newStartPageToken = page.newStartPageToken
            }
        }

        val applied = processDiscoveredRemoteDevices(permissionId, localDeviceId, accessToken)

        newStartPageToken?.let { accounts.updateStartPageToken(permissionId, it) }
        return applied
    }

    internal data class SnapshotApplyOutcome(
        val appliedAnyRecord: Boolean = false,
        val advancedAnyCursor: Boolean = false,
        val hasDeferredDomains: Boolean = false,
        val notFound: Boolean = false,
    )

    private suspend fun fetchRemoteManifest(
        permissionId: String,
        remoteDeviceId: String,
        manifestFile: DriveFile,
        accessToken: String,
    ): DriveManifest? {
        val bytes = try {
            driveApi.downloadMedia(accessToken, manifestFile.fileId)
        } catch (_: DriveNotFoundException) {
            files.setTrashed(permissionId, manifestFile.fileId, true)
            return null
        }
        val parsed = runCatching { DriveWireCodec.decodeManifest(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return null
        observeRemoteHlc(permissionId, parsed.updatedHlc)
        devices.upsert(
            DriveDevice(
                permissionId = permissionId,
                deviceId = remoteDeviceId,
                label = parsed.deviceLabel,
                manifestFileId = manifestFile.fileId,
                appVersionCode = parsed.appVersionCode,
                snapshotThroughSeq = parsed.snapshot?.throughSeq ?: 0L,
                lastSeenAt = clock(),
            ),
        )
        when (val check = DriveWireCodec.checkSchema(parsed.schemaVersion, parsed.domains, parsed.deviceLabel)) {
            is DriveSchemaCheck.UnsupportedStream -> {
                pauseDeviceStream(permissionId, remoteDeviceId, check.schemaVersion)
            }
            is DriveSchemaCheck.UnsupportedDomains -> {
                pauseUnsupportedDomains(permissionId, remoteDeviceId, check.unsupported)
                clearStreamPause(permissionId, remoteDeviceId)
            }
            DriveSchemaCheck.Supported -> {
                clearStreamPause(permissionId, remoteDeviceId)
            }
        }
        return parsed
    }

    private suspend fun processDiscoveredRemoteDevices(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
    ): Int {
        var appliedCount = 0

        // Discover remote device IDs from manifests and delta files.
        val manifestsByDevice = files.byKind(permissionId, DriveFile.KIND_MANIFEST)
            .filter { !it.trashed && it.deviceId != null && it.deviceId != localDeviceId }
            .associateBy { it.deviceId!! }
        val deltaDeviceIds = files.byKind(permissionId, DriveFile.KIND_DELTA)
            .filter { !it.trashed && it.deviceId != null && it.deviceId != localDeviceId }
            .mapNotNull { it.deviceId }
            .toSet()
        val remoteDeviceIds = (manifestsByDevice.keys + deltaDeviceIds)
            .sorted()

        val parsedManifests = mutableMapOf<String, DriveManifest>()
        var devicesToVisit = remoteDeviceIds
        var isFirstPass = true

        while (devicesToVisit.isNotEmpty()) {
            val deferredDevices = linkedSetOf<String>()
            var appliedAnyRecordThisPass = false
            var advancedAnyCursorThisPass = false

            for (remoteDeviceId in devicesToVisit) {
                var manifest = parsedManifests[remoteDeviceId]
                val manifestFile = manifestsByDevice[remoteDeviceId]
                if (isFirstPass && manifestFile != null) {
                    val parsed = fetchRemoteManifest(permissionId, remoteDeviceId, manifestFile, accessToken)
                    if (parsed != null) {
                        manifest = parsed
                        parsedManifests[remoteDeviceId] = parsed
                    }
                }

                // Skip downloading snapshot/deltas if the whole device stream is paused due to schemaVersion.
                val streamCursor = cursors.get(permissionId, remoteDeviceId, DriveWireCodec.STREAM_PAUSE_DOMAIN)
                if (streamCursor?.pausedSchemaVersion != null) continue

                // If manifest advertises a snapshot ahead of our cursor, ingest it idempotently.
                // If a snapshot part returns 404 (pruned concurrently), re-read the manifest once and retry.
                var snap = manifest?.snapshot
                if (snap != null && snap.throughSeq > minAppliedSeq(permissionId, remoteDeviceId)) {
                    var snapOutcome = applySnapshot(
                        permissionId = permissionId,
                        localDeviceId = localDeviceId,
                        remoteDeviceId = remoteDeviceId,
                        snapshot = snap,
                        accessToken = accessToken,
                    )
                    if (snapOutcome.notFound && manifestFile != null) {
                        val refreshed = fetchRemoteManifest(permissionId, remoteDeviceId, manifestFile, accessToken)
                        if (refreshed != null) {
                            manifest = refreshed
                            parsedManifests[remoteDeviceId] = refreshed
                            val refreshedSnap = refreshed.snapshot
                            if (refreshedSnap != null &&
                                refreshedSnap != snap &&
                                refreshedSnap.throughSeq > minAppliedSeq(permissionId, remoteDeviceId) &&
                                cursors.get(permissionId, remoteDeviceId, DriveWireCodec.STREAM_PAUSE_DOMAIN)?.pausedSchemaVersion == null
                            ) {
                                snap = refreshedSnap
                                snapOutcome = applySnapshot(
                                    permissionId = permissionId,
                                    localDeviceId = localDeviceId,
                                    remoteDeviceId = remoteDeviceId,
                                    snapshot = refreshedSnap,
                                    accessToken = accessToken,
                                )
                            }
                        }
                    }
                    if (snapOutcome.appliedAnyRecord) appliedAnyRecordThisPass = true
                    if (snapOutcome.advancedAnyCursor) {
                        advancedAnyCursorThisPass = true
                        appliedCount++
                    }
                    if (snapOutcome.hasDeferredDomains) deferredDevices.add(remoteDeviceId)
                }

                // Re-check stream pause in case snapshot triggered it.
                if (cursors.get(permissionId, remoteDeviceId, DriveWireCodec.STREAM_PAUSE_DOMAIN)?.pausedSchemaVersion != null) {
                    continue
                }

                val deltaFiles = files.byDeviceAndKind(permissionId, remoteDeviceId, DriveFile.KIND_DELTA)
                    .filter { !it.trashed && it.seq != null && it.seq > 0L }
                    .sortedBy { it.seq }
                if (deltaFiles.isEmpty()) continue

                var startingCursor = minAppliedSeq(permissionId, remoteDeviceId)
                var expectedNextSeq = startingCursor + 1L
                val deviceDeferredDomains = mutableSetOf<String>()
                var refreshedManifestOnDelta404 = false
                for (deltaFile in deltaFiles) {
                    val seq = deltaFile.seq ?: continue
                    if (seq <= startingCursor) continue
                    // Enforce contiguous seq order: if there is a gap, stop and wait (or fall back to snapshot).
                    if (seq > expectedNextSeq) break

                    val bytes = try {
                        driveApi.downloadMedia(accessToken, deltaFile.fileId)
                    } catch (_: DriveNotFoundException) {
                        // Delta was pruned by the remote device's compaction; re-read its manifest and apply snapshot.
                        files.setTrashed(permissionId, deltaFile.fileId, true)
                        if (!refreshedManifestOnDelta404 && manifestFile != null) {
                            refreshedManifestOnDelta404 = true
                            val refreshed = fetchRemoteManifest(permissionId, remoteDeviceId, manifestFile, accessToken)
                            if (refreshed != null) {
                                manifest = refreshed
                                parsedManifests[remoteDeviceId] = refreshed
                                val refreshedSnap = refreshed.snapshot
                                if (refreshedSnap != null &&
                                    refreshedSnap.throughSeq > minAppliedSeq(permissionId, remoteDeviceId) &&
                                    cursors.get(permissionId, remoteDeviceId, DriveWireCodec.STREAM_PAUSE_DOMAIN)?.pausedSchemaVersion == null
                                ) {
                                    val snapOutcome = applySnapshot(
                                        permissionId = permissionId,
                                        localDeviceId = localDeviceId,
                                        remoteDeviceId = remoteDeviceId,
                                        snapshot = refreshedSnap,
                                        accessToken = accessToken,
                                    )
                                    if (snapOutcome.appliedAnyRecord) appliedAnyRecordThisPass = true
                                    if (snapOutcome.advancedAnyCursor) {
                                        advancedAnyCursorThisPass = true
                                        appliedCount++
                                    }
                                    if (snapOutcome.hasDeferredDomains) deferredDevices.add(remoteDeviceId)
                                    startingCursor = minAppliedSeq(permissionId, remoteDeviceId)
                                    expectedNextSeq = startingCursor + 1L
                                    continue
                                }
                            }
                        }
                        break
                    }
                    val batch = runCatching { DriveWireCodec.decodeDeltaBatch(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                        ?: break
                    observeRemoteHlc(permissionId, batch.createdHlc)

                    val effectiveDomains = buildMap {
                        putAll(batch.domains)
                        for (record in batch.records) {
                            if (record.d !in this) {
                                put(record.d, 1)
                            }
                        }
                    }
                    when (val check = DriveWireCodec.checkSchema(batch.schemaVersion, effectiveDomains, manifest?.deviceLabel)) {
                        is DriveSchemaCheck.UnsupportedStream -> {
                            pauseDeviceStream(permissionId, remoteDeviceId, check.schemaVersion)
                            break
                        }
                        is DriveSchemaCheck.UnsupportedDomains -> {
                            pauseUnsupportedDomains(permissionId, remoteDeviceId, check.unsupported)
                        }
                        DriveSchemaCheck.Supported -> Unit
                    }

                    val deviceCursors = cursors.forDevice(permissionId, remoteDeviceId)
                    val pausedDomains = deviceCursors
                        .filter { it.pausedSchemaVersion != null }
                        .map { it.domain }
                        .toSet()
                    val appliedSeqByDomain = deviceCursors.associate { it.domain to it.appliedSeq }

                    val orderedRecords = batch.records
                        .filter {
                            it.d !in pausedDomains &&
                                it.d !in deviceDeferredDomains &&
                                it.d in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS &&
                                seq > (appliedSeqByDomain[it.d] ?: 0L)
                        }
                        .sortedBy { DriveWireCodec.domainRank(it.d) }

                    for (record in orderedRecords) {
                        if (record.d in deviceDeferredDomains) continue
                        val recordApplied = applySingleRecord(
                            permissionId = permissionId,
                            localDeviceId = localDeviceId,
                            remoteDeviceId = remoteDeviceId,
                            remoteSeq = seq,
                            accessToken = accessToken,
                            record = record,
                            batchObserved = batch.observed,
                        )
                        if (recordApplied) {
                            appliedAnyRecordThisPass = true
                        } else {
                            deviceDeferredDomains.add(record.d)
                        }
                    }

                    // Advance per-domain cursors ONLY for implemented, non-paused, non-deferred domains.
                    val domainsToAdvance = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS.keys
                        .filter {
                            it !in pausedDomains &&
                                it !in deviceDeferredDomains &&
                                it != DriveWireCodec.STREAM_PAUSE_DOMAIN
                        }
                    var batchAdvancedAnyCursor = false
                    store.withTransaction {
                        for (domain in domainsToAdvance) {
                            val existing = cursors.get(permissionId, remoteDeviceId, domain)
                            val prevApplied = existing?.appliedSeq ?: 0L
                            val nextApplied = maxOf(prevApplied, seq)
                            if (nextApplied > prevApplied) {
                                batchAdvancedAnyCursor = true
                            }
                            cursors.upsert(
                                DriveCursor(
                                    permissionId = permissionId,
                                    deviceId = remoteDeviceId,
                                    domain = domain,
                                    appliedSeq = nextApplied,
                                    pausedSchemaVersion = existing?.pausedSchemaVersion,
                                ),
                            )
                        }
                    }
                    if (batchAdvancedAnyCursor) {
                        advancedAnyCursorThisPass = true
                        appliedCount++
                    }
                    expectedNextSeq = seq + 1L
                }
                if (deviceDeferredDomains.isNotEmpty()) {
                    deferredDevices.add(remoteDeviceId)
                }
            }

            if (deferredDevices.isEmpty() || !appliedAnyRecordThisPass || !advancedAnyCursorThisPass) {
                break
            }
            isFirstPass = false
            devicesToVisit = deferredDevices.sorted()
        }
        return appliedCount
    }

    internal suspend fun applySnapshot(
        permissionId: String,
        localDeviceId: String,
        remoteDeviceId: String,
        snapshot: DriveSnapshotRef,
        accessToken: String,
    ): SnapshotApplyOutcome {
        if (snapshot.parts.isEmpty()) return SnapshotApplyOutcome()
        val decodedParts = mutableListOf<DriveSnapshotPart>()
        for (partRef in snapshot.parts) {
            val bytes = try {
                driveApi.downloadMedia(accessToken, partRef.fileId)
            } catch (_: DriveNotFoundException) {
                return SnapshotApplyOutcome(notFound = true)
            }
            val actualSha = DriveCanonicalJson.sha256Hex(bytes)
            if (partRef.sha256.isNotBlank() && !actualSha.equals(partRef.sha256, ignoreCase = true)) {
                return SnapshotApplyOutcome()
            }
            val part = runCatching { DriveWireCodec.decodeSnapshotPart(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                ?: return SnapshotApplyOutcome()
            if (part.partCount > 0 && part.partCount != snapshot.parts.size) {
                return SnapshotApplyOutcome()
            }
            if (part.throughSeq > 0L && snapshot.throughSeq > 0L && part.throughSeq != snapshot.throughSeq) {
                return SnapshotApplyOutcome()
            }
            observeRemoteHlc(permissionId, part.createdHlc)
            val effectiveDomains = buildMap {
                putAll(part.domains)
                for (record in part.records) {
                    if (record.d !in this) {
                        put(record.d, 1)
                    }
                }
            }
            when (val check = DriveWireCodec.checkSchema(part.schemaVersion, effectiveDomains)) {
                is DriveSchemaCheck.UnsupportedStream -> {
                    pauseDeviceStream(permissionId, remoteDeviceId, check.schemaVersion)
                    return SnapshotApplyOutcome()
                }
                is DriveSchemaCheck.UnsupportedDomains -> {
                    pauseUnsupportedDomains(permissionId, remoteDeviceId, check.unsupported)
                }
                DriveSchemaCheck.Supported -> Unit
            }
            decodedParts.add(part)
        }
        val expectedIndices = (0 until snapshot.parts.size).toSet()
        if (decodedParts.map { it.partIndex }.toSet() != expectedIndices) {
            return SnapshotApplyOutcome()
        }
        val orderedParts = decodedParts.sortedBy { it.partIndex }
        val allResolutions = orderedParts.flatMap { it.resolutions }
        val allRecords = orderedParts.flatMap { it.records }

        val deviceCursors = cursors.forDevice(permissionId, remoteDeviceId)
        val pausedDomains = deviceCursors
            .filter { it.pausedSchemaVersion != null }
            .map { it.domain }
            .toSet()
        val appliedSeqByDomain = deviceCursors.associate { it.domain to it.appliedSeq }

        val deferredDomains = mutableSetOf<String>()
        var appliedAnyRecord = false
        val ordered = allRecords
            .filter {
                it.d !in pausedDomains &&
                    it.d in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS &&
                    snapshot.throughSeq > (appliedSeqByDomain[it.d] ?: 0L)
            }
            .sortedBy { DriveWireCodec.domainRank(it.d) }
        val (aliasRecords, otherRecords) = ordered.partition { it.d == DriveWireCodec.DOMAIN_WORK_ALIAS }
        // Per docs/DRIVE_SYNC.md §2.4: ingest ALL snapshot `resolutions` across all parts
        // BEFORE reconciling `records` so `resRefs`-bridged supersession works across compaction.
        // Aliases apply first so work-scoped resolution keys land under their canonical work key.
        val now = clock()
        for (record in aliasRecords) {
            if (record.d in deferredDomains) continue
            if (applySingleRecord(permissionId, localDeviceId, remoteDeviceId, snapshot.throughSeq, accessToken, record)) {
                appliedAnyRecord = true
            } else {
                deferredDomains.add(record.d)
            }
        }
        if (allResolutions.isNotEmpty()) {
            store.withTransaction {
                for (res in allResolutions) {
                    if (res.d in pausedDomains || res.d !in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS) continue
                    resolutions.upsert(
                        DriveResolution(
                            permissionId = permissionId,
                            domain = res.d,
                            recordKey = canonicalResolutionKey(permissionId, res.d, res.k),
                            resolutionFp = res.resolutionFp,
                            resolvedFpsJson = DriveCanonicalJson.encode(res.resolvedFps.distinct().sorted()),
                            outcome = res.outcome,
                            hlc = res.hlc,
                            authorDeviceId = remoteDeviceId,
                            createdAt = now,
                        ),
                    )
                }
            }
        }

        for (record in otherRecords) {
            if (record.d in deferredDomains) continue
            val recordApplied = applySingleRecord(
                permissionId = permissionId,
                localDeviceId = localDeviceId,
                remoteDeviceId = remoteDeviceId,
                remoteSeq = snapshot.throughSeq,
                accessToken = accessToken,
                record = record,
                batchObserved = null,
            )
            if (recordApplied) {
                appliedAnyRecord = true
            } else {
                deferredDomains.add(record.d)
            }
        }

        var advancedAnyCursor = false
        store.withTransaction {
            for (domain in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS.keys) {
                if (domain in pausedDomains || domain in deferredDomains) continue
                val existing = cursors.get(permissionId, remoteDeviceId, domain)
                val prevApplied = existing?.appliedSeq ?: 0L
                val nextApplied = maxOf(prevApplied, snapshot.throughSeq)
                if (nextApplied > prevApplied) {
                    advancedAnyCursor = true
                }
                cursors.upsert(
                    DriveCursor(
                        permissionId = permissionId,
                        deviceId = remoteDeviceId,
                        domain = domain,
                        appliedSeq = nextApplied,
                        pausedSchemaVersion = existing?.pausedSchemaVersion,
                    ),
                )
            }
        }
        return SnapshotApplyOutcome(
            appliedAnyRecord = appliedAnyRecord,
            advancedAnyCursor = advancedAnyCursor,
            hasDeferredDomains = deferredDomains.isNotEmpty(),
        )
    }

    private suspend fun canonicalResolutionKey(permissionId: String, domain: String, key: String): String {
        if (domain != DriveWireCodec.DOMAIN_SERIES &&
            domain != DriveWireCodec.DOMAIN_METADATA &&
            domain != DriveWireCodec.DOMAIN_EVALUATION
        ) {
            return key
        }
        val split = if (domain == DriveWireCodec.DOMAIN_SERIES) key.length else key.lastIndexOf('/')
        if (split <= 0) return key
        return workIdentity.canonicalWorkKey(permissionId, key.substring(0, split)) + key.substring(split)
    }

    private suspend fun applySingleRecord(
        permissionId: String,
        localDeviceId: String,
        remoteDeviceId: String,
        remoteSeq: Long,
        accessToken: String,
        record: DriveWireRecord,
        batchObserved: Map<String, Long>? = null,
        observeHlc: Boolean = true,
        replayingHeld: Boolean = false,
    ): Boolean {
        if (observeHlc) observeRemoteHlc(permissionId, record.hlc)
        // A malformed record is dropped, not deferred: replaying it would never make it valid.
        if (!DriveWireCodec.bodyMatchesKey(record)) return true
        val deferredRetirements = mutableListOf<DriveRecordState>()
        if (record.d in CATALOG_GATED_DOMAINS) {
            val workKey = gatedWorkKey(record)
            if (workKey.isNotEmpty() && workIdentity.awaitsCatalogEntry(permissionId, workKey)) {
                return false
            }
            // A book restored earlier in this run must get its retained version replayed before a
            // version that happens to arrive later, or the newer retained state is replaced unseen.
            // If that replay has to wait (a cover blob that is not downloadable yet), this record
            // waits with it instead of landing on top of the retained version.
            if (!replayingHeld &&
                !materializeHeldBefore(permissionId, localDeviceId, accessToken, record, deferredRetirements)
            ) {
                return false
            }
        }
        val applied = when (record.d) {
            DriveWireCodec.DOMAIN_WORK_ALIAS -> {
                workIdentity.applyRemoteRecord(permissionId, record, remoteDeviceId, remoteSeq) { nextHlc(permissionId) }
                true
            }
            DriveWireCodec.DOMAIN_CATALOG -> {
                catalogSync.applyRemoteRecord(permissionId, record, remoteDeviceId, remoteSeq) { nextHlc(permissionId) }
                true
            }
            DriveWireCodec.DOMAIN_METADATA ->
                metadataSync?.applyRemoteRecord(
                    permissionId = permissionId,
                    accessToken = accessToken,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    remoteSeq = remoteSeq,
                    nextHlc = { nextHlc(permissionId) },
                ) ?: true
            DriveWireCodec.DOMAIN_SERIES ->
                seriesSync?.applyRemoteRecord(
                    permissionId = permissionId,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    remoteSeq = remoteSeq,
                    nextHlc = { nextHlc(permissionId) },
                ) ?: true
            DriveWireCodec.DOMAIN_EVALUATION ->
                evaluationSync?.applyRemoteRecord(
                    permissionId = permissionId,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    remoteSeq = remoteSeq,
                    nextHlc = { nextHlc(permissionId) },
                ) ?: true
            DriveWireCodec.DOMAIN_ANNOTATION ->
                annotationSync?.applyRemoteRecord(
                    permissionId = permissionId,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    remoteSeq = remoteSeq,
                    nextHlc = { nextHlc(permissionId) },
                ) ?: true
            DriveWireCodec.DOMAIN_POSITION -> {
                positionSync.applyRemotePosition(
                    permissionId = permissionId,
                    localDeviceId = localDeviceId,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    remoteSeq = remoteSeq,
                )
                true
            }
            DriveWireCodec.DOMAIN_FURTHEST -> {
                positionSync.applyRemoteFurthest(
                    permissionId = permissionId,
                    localDeviceId = localDeviceId,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    remoteSeq = remoteSeq,
                )
                true
            }
            DriveWireCodec.DOMAIN_VOCAB_WORD -> {
                val effectiveObserved = record.observed ?: batchObserved
                vocabularySync?.applyRemoteWord(
                    permissionId = permissionId,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    remoteSeq = remoteSeq,
                    localDeviceId = localDeviceId,
                    remoteObserved = effectiveObserved,
                    provenanceKnown = batchObserved != null,
                )
                true
            }
            DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE ->
                vocabularySync?.applyRemoteOccurrence(
                    permissionId = permissionId,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    // A snapshot record keeps its own publication seq; throughSeq only drives cursors.
                    remoteSeq = record.seq ?: remoteSeq,
                ) ?: true
            DriveWireCodec.DOMAIN_VOCAB_REVIEW -> {
                vocabularySync?.applyRemoteReview(
                    permissionId = permissionId,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    remoteSeq = record.seq ?: remoteSeq,
                    provenanceKnown = batchObserved != null || record.seq != null,
                )
                true
            }
            DriveWireCodec.DOMAIN_SESSION ->
                sessionSync?.applyRemoteRecord(
                    permissionId = permissionId,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    remoteSeq = remoteSeq,
                ) ?: true
            else -> true
        }
        if (applied && deferredRetirements.isNotEmpty()) {
            retireDeferredAliasHeldRows(permissionId, record, deferredRetirements)
        }
        return applied
    }

    /**
     * Work key a catalog-gated record applies to. Bodyless deletions of keyed domains carry it
     * in the record key, so a reset cannot be acknowledged before the book exists.
     */
    private fun gatedWorkKey(record: DriveWireRecord): String {
        val carriesBody = record.op == DriveRecordState.OP_PUT ||
            record.op == DriveRecordState.OP_RESOLVE && record.outcome == DriveResolution.OUTCOME_PUT
        if (carriesBody) return record.body?.optString("workKey")?.trim().orEmpty()
        return when (record.d) {
            DriveWireCodec.DOMAIN_SERIES -> record.k
            DriveWireCodec.DOMAIN_METADATA, DriveWireCodec.DOMAIN_EVALUATION -> record.k.substringBeforeLast('/', "")
            else -> ""
        }.trim()
    }

    /**
     * Replays every retained (`held`) record whose work has a local book again.
     *
     * A fresh device can receive a catalog tombstone before the records of that work, so a
     * `series`, `evaluation` or `metadata` record is retained instead of dropped (§4). Once the
     * book comes back — a later catalog version, a re-import, or another device restoring it —
     * the retained version is replayed as an ordinary incoming record. Its own row is only the
     * baseline the record was decided against while it was held: the replay compares it with
     * whatever the restored book holds now, so intervening local edits are reconciled instead
     * of being mistaken for agreement. The flag clears only when that apply commits, so an
     * interrupted run retries on the next one even when no fresh traffic arrives.
     */
    internal suspend fun materializeHeldRecords(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
    ) {
        val held = recordStates.heldForAccount(permissionId)
            .filter { state ->
                state.domain in DriveHeldRecords.HELD_DOMAINS &&
                    DriveHeldRecords.workKeyFor(state.domain, state.recordKey).let { workKey ->
                        workKey.isNotEmpty() && workIdentity.hasLocalBookFor(permissionId, workKey)
                    }
            }
        if (held.isEmpty()) return
        val grouped = linkedMapOf<Pair<String, String>, MutableList<DriveRecordState>>()
        for (state in held) {
            val canonicalKey = canonicalResolutionKey(permissionId, state.domain, state.recordKey)
            grouped.getOrPut(state.domain to canonicalKey) { mutableListOf() }.add(state)
        }
        for ((_, group) in grouped) {
            val heads = prepareHeldGroup(permissionId, group)
            for (prepared in heads) {
                // A version whose disagreement is already parked is the reader's to settle;
                // replaying it again would open a second question about the same older value.
                if (heldVersionAlreadyParked(permissionId, prepared)) {
                    attachParkedConflicts(permissionId, prepared)
                    continue
                }
                if (!replayHeldRecord(permissionId, localDeviceId, accessToken, prepared)) continue
                retireHeldRowStoredUnderAnotherKey(permissionId, prepared)
            }
        }
    }

    /**
     * True when an unsettled conflict for this record already covers [state]'s version, either as
     * one of its two sides or inside the lineage of the version that arrived with it.
     */
    private suspend fun heldVersionAlreadyParked(
        permissionId: String,
        state: DriveRecordState,
    ): Boolean {
        val canonicalKey = canonicalResolutionKey(permissionId, state.domain, state.recordKey)
        val parked = conflicts.unsettledForRecord(permissionId, state.domain, canonicalKey) +
            if (canonicalKey != state.recordKey) {
                conflicts.unsettledForRecord(permissionId, state.domain, state.recordKey)
            } else {
                emptyList()
            }
        return parked.any { conflict ->
            // A held version that is the local side still has to be materialized: the parked
            // question is about it. The duplicate risk is the older value that a parked successor
            // already carried, which only shows up on the remote side or in its lineage.
            conflict.localFp != state.versionFp && (
                conflict.remoteFp == state.versionFp ||
                    state.versionFp in DriveWorkIdentity.decodeStringArray(conflict.remoteAncJson) ||
                    state.versionFp in DriveWorkIdentity.decodeStringArray(conflict.remoteResRefsJson)
                )
        }
    }

    /**
     * Replays the retained version of the record [record] names, if one is retained and its work
     * already has a book. Called before that record is decided, so a version that happens to
     * arrive after the book was restored cannot replace the newer retained state unseen.
     */
    private suspend fun materializeHeldBefore(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
        record: DriveWireRecord,
        deferredRetirements: MutableList<DriveRecordState>,
    ): Boolean {
        if (record.d !in DriveHeldRecords.HELD_DOMAINS) return true
        val workKey = gatedWorkKey(record)
        if (workKey.isEmpty() || !workIdentity.hasLocalBookFor(permissionId, workKey)) return true
        val field = if (record.d == DriveWireCodec.DOMAIN_SERIES) {
            null
        } else {
            record.body?.optString("field")?.trim()?.takeIf { it.isNotEmpty() }
                ?: record.k.substringAfterLast('/', "").takeIf { it.isNotEmpty() }
                ?: return true
        }
        val heldRows = workIdentity.equivalentWorkKeys(permissionId, workKey)
            .map { wk -> if (field == null) wk else "$wk/$field" }
            .distinct()
            .mapNotNull { rk -> recordStates.get(permissionId, record.d, rk)?.takeIf { it.held } }
        if (heldRows.isEmpty()) return true
        val canonicalKey = canonicalResolutionKey(permissionId, record.d, heldRows.first().recordKey)
        val heads = prepareHeldGroup(permissionId, heldRows)
        for (prepared in heads) {
            // Whatever happens next, a competition parked while the work had no book belongs to the
            // book it now has, or it would stay hidden and block this record's publication forever.
            attachParkedConflicts(permissionId, prepared)
            // A version that descends from the retained one supersedes it, so it is decided against
            // that baseline directly and nothing has to be replayed first.
            if (prepared.versionFp in record.anc || prepared.versionFp in record.resRefs ||
                prepared.versionFp in record.resolvedFps
            ) {
                if (prepared.recordKey != canonicalKey) {
                    deferredRetirements.add(prepared)
                }
                continue
            }
            if (heldVersionAlreadyParked(permissionId, prepared)) continue
            if (!replayHeldRecord(permissionId, localDeviceId, accessToken, prepared)) return false
            retireHeldRowStoredUnderAnotherKey(permissionId, prepared)
        }
        return true
    }

    /**
     * Migrates a group of equivalent held rows onto the canonical key, deduplicates them, and
     * collapses any held row that is already an ancestor of another held row onto the surviving
     * head(s) so an alias-held ancestor is never replayed over its descendant.
     */
    private suspend fun prepareHeldGroup(
        permissionId: String,
        heldRows: List<DriveRecordState>,
    ): List<DriveRecordState> {
        val deduplicated = linkedMapOf<Pair<String, String>, DriveRecordState>()
        for (held in heldRows) {
            val prepared = prepareHeldStateForCanonicalKey(permissionId, held)
            deduplicated[prepared.recordKey to prepared.versionFp] = prepared
        }
        val preparedRows = deduplicated.values.toList()
        if (preparedRows.size <= 1) return preparedRows
        val combinedAncestors = preparedRows.flatMap { row ->
            DriveWorkIdentity.decodeStringArray(row.ancestorsJson) +
                DriveWorkIdentity.decodeStringArray(row.resRefsJson)
        }.toSet()
        val (superseded, heads) = preparedRows.partition { it.versionFp in combinedAncestors }
        if (superseded.isEmpty() || heads.isEmpty()) return preparedRows
        val canonicalKey = canonicalResolutionKey(permissionId, heads.first().domain, heads.first().recordKey)
        val byFp = preparedRows.associateBy { it.versionFp }
        fun reachableAncestors(start: DriveRecordState): Set<String> {
            val seen = linkedSetOf<String>()
            val stack = ArrayDeque<DriveRecordState>()
            stack.add(start)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                val refs = DriveWorkIdentity.decodeStringArray(cur.ancestorsJson) +
                    DriveWorkIdentity.decodeStringArray(cur.resRefsJson)
                for (ref in refs) {
                    if (seen.add(ref)) {
                        byFp[ref]?.let(stack::add)
                    }
                }
            }
            return seen
        }
        return store.withTransaction {
            val updatedHeads = heads.map { head ->
                val reachable = reachableAncestors(head)
                var merged = head
                for (sup in superseded) {
                    if (sup.versionFp in reachable) {
                        merged = DriveHeldRecords.mergeSupersededLineage(merged, sup)
                            .copy(held = head.held)
                    }
                }
                if (merged != head) {
                    recordStates.upsert(merged)
                }
                merged
            }.toMutableList()
            for (sup in superseded) {
                val current = recordStates.get(permissionId, sup.domain, sup.recordKey)
                if (current != null && current.held && current.versionFp == sup.versionFp) {
                    recordStates.delete(permissionId, sup.domain, sup.recordKey)
                }
            }
            for (i in updatedHeads.indices) {
                val head = updatedHeads[i]
                if (head.recordKey != canonicalKey &&
                    recordStates.get(permissionId, head.domain, canonicalKey) == null
                ) {
                    val moved = head.copy(recordKey = canonicalKey)
                    recordStates.upsert(moved)
                    recordStates.delete(permissionId, head.domain, head.recordKey)
                    updatedHeads[i] = moved
                }
            }
            updatedHeads
        }
    }

    /**
     * Migrates parked conflicts and resolutions (and the held row itself when the canonical key
     * has no state yet or holds an ancestor/descendant) from a pre-merge alias key onto the
     * current canonical record key.
     */
    private suspend fun prepareHeldStateForCanonicalKey(
        permissionId: String,
        state: DriveRecordState,
    ): DriveRecordState {
        val canonicalKey = canonicalResolutionKey(permissionId, state.domain, state.recordKey)
        if (canonicalKey == state.recordKey) {
            return recordStates.get(permissionId, state.domain, canonicalKey) ?: state
        }
        return store.withTransaction {
            val oldResolutions = resolutions.forRecord(permissionId, state.domain, state.recordKey)
            for (res in oldResolutions) {
                resolutions.upsert(res.copy(recordKey = canonicalKey))
            }
            if (oldResolutions.isNotEmpty()) {
                resolutions.deleteForRecord(permissionId, state.domain, state.recordKey)
            }
            migrateConflictsToCanonicalKey(permissionId, state.domain, state.recordKey, canonicalKey)
            val canonicalState = recordStates.get(permissionId, state.domain, canonicalKey)
            when {
                canonicalState == null -> {
                    val moved = state.copy(recordKey = canonicalKey)
                    recordStates.upsert(moved)
                    recordStates.delete(permissionId, state.domain, state.recordKey)
                    moved
                }
                canonicalState.held &&
                    (state.versionFp == canonicalState.versionFp ||
                        isHeldAncestorOrResRef(state.versionFp, canonicalState)) -> {
                    val merged = DriveHeldRecords.mergeSupersededLineage(canonicalState, state)
                        .copy(held = canonicalState.held)
                    recordStates.upsert(merged)
                    recordStates.delete(permissionId, state.domain, state.recordKey)
                    merged
                }
                canonicalState.held && isHeldAncestorOrResRef(canonicalState.versionFp, state) -> {
                    val moved = DriveHeldRecords.mergeSupersededLineage(
                        state.copy(recordKey = canonicalKey),
                        canonicalState,
                    ).copy(held = true)
                    recordStates.upsert(moved)
                    recordStates.delete(permissionId, state.domain, state.recordKey)
                    moved
                }
                else -> state
            }
        }
    }

    private fun isHeldAncestorOrResRef(versionFp: String, descendant: DriveRecordState): Boolean =
        versionFp in DriveWorkIdentity.decodeStringArray(descendant.ancestorsJson) ||
            versionFp in DriveWorkIdentity.decodeStringArray(descendant.resRefsJson)

    private suspend fun migrateConflictsToCanonicalKey(
        permissionId: String,
        domain: String,
        fromRecordKey: String,
        canonicalKey: String,
    ) {
        val oldConflicts = conflicts.forRecord(permissionId, domain, fromRecordKey)
        for (c in oldConflicts) {
            val newId = DriveCanonicalJson.conflictId(domain, canonicalKey, c.localFp, c.remoteFp)
            val existing = conflicts.get(permissionId, newId)
            val keepExisting = existing != null &&
                existing.state != DriveConflict.STATE_OPEN &&
                conflictStateRank(existing.state) >= conflictStateRank(c.state)
            if (keepExisting) {
                if (existing.bookUrl == null && c.bookUrl != null) {
                    conflicts.upsert(existing.copy(bookUrl = c.bookUrl))
                }
            } else {
                conflicts.upsert(
                    c.copy(
                        conflictId = newId,
                        recordKey = canonicalKey,
                        bookUrl = c.bookUrl ?: existing?.bookUrl,
                    ),
                )
            }
            if (newId != c.conflictId) {
                conflicts.delete(permissionId, c.conflictId)
            }
        }
    }

    private fun conflictStateRank(state: String): Int = when (state) {
        DriveConflict.STATE_RESOLVED -> 2
        DriveConflict.STATE_RESOLVING -> 1
        else -> 0
    }

    private suspend fun retireDeferredAliasHeldRows(
        permissionId: String,
        record: DriveWireRecord,
        skippedRows: List<DriveRecordState>,
    ) {
        store.withTransaction {
            for (skipped in skippedRows) {
                val canonicalKey = canonicalResolutionKey(permissionId, skipped.domain, skipped.recordKey)
                val persisted = recordStates.get(permissionId, skipped.domain, canonicalKey)
                if (persisted != null && !persisted.held) {
                    val supersededByPersisted = persisted.versionFp == record.fp ||
                        persisted.versionFp == skipped.versionFp ||
                        skipped.versionFp in DriveWorkIdentity.decodeStringArray(persisted.ancestorsJson) ||
                        skipped.versionFp in DriveWorkIdentity.decodeStringArray(persisted.resRefsJson)
                    if (supersededByPersisted) {
                        recordStates.upsert(DriveHeldRecords.mergeSupersededLineage(persisted, skipped))
                        val current = recordStates.get(permissionId, skipped.domain, skipped.recordKey)
                        if (current != null && current.held && current.versionFp == skipped.versionFp) {
                            recordStates.delete(permissionId, skipped.domain, skipped.recordKey)
                        }
                    }
                }
            }
        }
    }

    /** Applies a retained version to whatever its work now holds locally. */
    private suspend fun replayHeldRecord(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
        state: DriveRecordState,
    ): Boolean {
        attachParkedConflicts(permissionId, state)
        val canonicalKey = canonicalResolutionKey(permissionId, state.domain, state.recordKey)
        val resolution = if (state.versionOp == DriveRecordState.OP_RESOLVE) {
            resolutions.get(permissionId, state.domain, canonicalKey, state.versionFp)
                ?: resolutions.get(permissionId, state.domain, state.recordKey, state.versionFp)
        } else {
            null
        }
        val record = DriveHeldRecords.toWireRecord(
            domain = state.domain,
            state = state,
            outcome = resolution?.outcome,
            resolvedFps = resolution?.let { DriveWorkIdentity.decodeStringArray(it.resolvedFpsJson) }.orEmpty(),
        ) ?: return false
        return applySingleRecord(
            permissionId = permissionId,
            localDeviceId = localDeviceId,
            remoteDeviceId = state.originDeviceId ?: localDeviceId,
            remoteSeq = state.originSeq ?: 0L,
            accessToken = accessToken,
            record = record,
            observeHlc = false,
            replayingHeld = true,
        )
    }

    /** Gives conflicts parked while the work had no book the book it now has. */
    private suspend fun attachParkedConflicts(permissionId: String, state: DriveRecordState) {
        val workKey = DriveHeldRecords.workKeyFor(state.domain, state.recordKey)
        if (workKey.isEmpty()) return
        val canonicalKey = canonicalResolutionKey(permissionId, state.domain, state.recordKey)
        workIdentity.liveBookUrlFor(permissionId, workKey)?.let { bookUrl ->
            conflicts.attachBookUrl(permissionId, state.domain, canonicalKey, bookUrl)
            if (canonicalKey != state.recordKey) {
                conflicts.attachBookUrl(permissionId, state.domain, state.recordKey, bookUrl)
            }
        }
    }

    /**
     * A work-key merge after retention makes the replay store its result under the canonical key,
     * leaving the source row behind. Retire it once the apply committed, or every later run would
     * replay a version that is already history there.
     */
    private suspend fun retireHeldRowStoredUnderAnotherKey(
        permissionId: String,
        state: DriveRecordState,
    ) {
        val after = recordStates.get(permissionId, state.domain, state.recordKey) ?: return
        if (after.held && after.versionFp == state.versionFp) {
            recordStates.delete(permissionId, state.domain, state.recordKey)
        }
    }

    private suspend fun recoverResolvingConflicts(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
    ) {
        val resolving = conflicts.resolvingForAccount(permissionId)
        for (conflict in resolving) {
            when (conflict.domain) {
                DriveWireCodec.DOMAIN_ANNOTATION ->
                    annotationSync?.recoverResolvingConflict(permissionId, conflict, localDeviceId) { nextHlc(permissionId) }
                DriveWireCodec.DOMAIN_EVALUATION ->
                    evaluationSync?.recoverResolvingConflict(permissionId, conflict, localDeviceId) { nextHlc(permissionId) }
                DriveWireCodec.DOMAIN_METADATA ->
                    metadataSync?.recoverResolvingConflict(
                        permissionId = permissionId,
                        conflict = conflict,
                        localDeviceId = localDeviceId,
                        nextHlc = { nextHlc(permissionId) },
                        accessTokenProvider = { accessToken },
                    )
                DriveWireCodec.DOMAIN_SERIES ->
                    seriesSync?.recoverResolvingConflict(permissionId, conflict, localDeviceId) { nextHlc(permissionId) }
            }
        }
    }

    /**
     * Drains any frozen in-flight batch first (idempotent via `batchId`), then
     * builds, freezes, uploads, and acknowledges new dirty Phase 3 batches.
     */
    private suspend fun publishPending(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
        bookUrlFilter: String?,
    ): PublishResult {
        var publishedCount = 0

        // Step 1: drain any frozen in-flight batch from a previous interrupted run,
        // replaying its exact persisted ACK metadata before marking the outbox ACKED.
        while (true) {
            val existingInFlight = outbox.inFlight(permissionId) ?: break
            val fileId = if (existingInFlight.state == DriveOutbox.STATE_UPLOADED && !existingInFlight.fileId.isNullOrBlank()) {
                existingInFlight.fileId
            } else {
                val uploaded = driveApi.uploadAppDataJsonBatch(
                    accessToken = accessToken,
                    name = DriveWireCodec.deltaFileName(localDeviceId, existingInFlight.seq),
                    jsonPayload = existingInFlight.payload.toByteArray(Charsets.UTF_8),
                    appProperties = DriveWireCodec.deltaAppProperties(
                        deviceId = localDeviceId,
                        seq = existingInFlight.seq,
                        batchId = existingInFlight.batchId,
                    ),
                )
                outbox.markUploaded(permissionId, existingInFlight.seq, uploaded.id)
                uploaded.id
            }
            val parsedBatch = runCatching { DriveWireCodec.decodeDeltaBatch(existingInFlight.payload) }.getOrNull()
            val ackMeta = existingInFlight.ackMetadataJson?.let { raw ->
                runCatching { DriveWireCodec.decodeOutboxAckMetadata(raw) }.getOrNull()
            } ?: DriveOutboxAckMetadata()
            val recoveredRecords = parsedBatch?.records.orEmpty()
            // Commit liseur.db acks first (Transaction 1), then drive_sync.db (Transaction 2).
            positionSync.acknowledgePublished(
                permissionId = permissionId,
                records = recoveredRecords,
                positionAcks = ackMeta.positionAcks,
                peakAcks = ackMeta.peakAcks,
            )
            annotationSync?.acknowledgePublished(
                permissionId = permissionId,
                records = recoveredRecords,
                acks = ackMeta.annotationAcks,
            )
            evaluationSync?.acknowledgePublished(
                permissionId = permissionId,
                records = recoveredRecords,
                acks = ackMeta.evaluationAcks,
            )
            metadataSync?.acknowledgePublished(
                permissionId = permissionId,
                records = recoveredRecords,
                acks = ackMeta.metadataAcks,
            )
            vocabularySync?.acknowledgePublished(
                permissionId = permissionId,
                batchSeq = existingInFlight.seq,
                records = recoveredRecords,
                wordAcks = ackMeta.vocabWordAcks,
                reviewAcks = ackMeta.vocabReviewAcks,
                localDeviceId = localDeviceId,
            )
            sessionSync?.acknowledgePublished(
                permissionId = permissionId,
                batchSeq = existingInFlight.seq,
                records = recoveredRecords,
                acks = ackMeta.sessionAcks,
            )
            store.withTransaction {
                if (recoveredRecords.isNotEmpty()) {
                    workIdentity.markPublished(permissionId, recoveredRecords)
                    catalogSync.markPublished(permissionId, recoveredRecords)
                    seriesSync?.markPublished(permissionId, recoveredRecords)
                }
                outbox.markAcked(permissionId, existingInFlight.seq, fileId)
                files.upsert(
                    DriveFile(
                        permissionId = permissionId,
                        fileId = fileId,
                        name = DriveWireCodec.deltaFileName(localDeviceId, existingInFlight.seq),
                        kind = DriveFile.KIND_DELTA,
                        deviceId = localDeviceId,
                        seq = existingInFlight.seq,
                        size = existingInFlight.payload.toByteArray(Charsets.UTF_8).size.toLong(),
                        trashed = false,
                    ),
                )
                val acct = accounts.get(permissionId)
                if (acct != null) {
                    accounts.updateManifest(
                        permissionId = permissionId,
                        fileId = acct.manifestFileId,
                        snapshotThroughSeq = acct.snapshotThroughSeq,
                        deltasSinceSnapshot = acct.deltasSinceSnapshot + 1,
                    )
                }
            }
            publishedCount++
        }

        // Step 2: collect dirty records from Phase 3 + Phase 4 domains in canonical order.
        val aliasRecords = workIdentity.collectDirtyRecords(permissionId)
        val catalogRecords = catalogSync.collectDirtyRecords(permissionId, bookUrlFilter) { nextHlc(permissionId) }
        val metaBundle = metadataSync?.collectDirtyRecords(
            permissionId = permissionId,
            accessToken = accessToken,
            bookUrlFilter = bookUrlFilter,
            nextHlc = { nextHlc(permissionId) },
        ) ?: DriveMetadataOutboundBundle(emptyList(), emptyList())
        val seriesRecords = seriesSync?.collectDirtyRecords(
            permissionId = permissionId,
            bookUrlFilter = bookUrlFilter,
            nextHlc = { nextHlc(permissionId) },
        ).orEmpty()
        val evalBundle = evaluationSync?.collectDirtyRecords(
            permissionId = permissionId,
            bookUrlFilter = bookUrlFilter,
            nextHlc = { nextHlc(permissionId) },
        ) ?: DriveEvaluationOutboundBundle(emptyList(), emptyList())
        val annBundle = annotationSync?.collectDirtyRecords(
            permissionId = permissionId,
            bookUrlFilter = bookUrlFilter,
            nextHlc = { nextHlc(permissionId) },
        ) ?: DriveAnnotationOutboundBundle(emptyList(), emptyList())
        val posBundle = positionSync.collectDirtyRecords(
            permissionId = permissionId,
            deviceId = localDeviceId,
            bookUrlFilter = bookUrlFilter,
            nextHlc = { nextHlc(permissionId) },
        )
        val vocabBundle = if (bookUrlFilter == null) {
            vocabularySync?.collectDirtyRecords(
                permissionId = permissionId,
                localDeviceId = localDeviceId,
                nextHlc = { nextHlc(permissionId) },
            ) ?: DriveVocabularyOutboundBundle(emptyList(), emptyList(), emptyList())
        } else {
            DriveVocabularyOutboundBundle(emptyList(), emptyList(), emptyList())
        }
        val sessionBundle = sessionSync?.collectDirtyRecords(
            permissionId = permissionId,
            localDeviceId = localDeviceId,
            bookUrlFilter = bookUrlFilter,
            nextHlc = { nextHlc(permissionId) },
        ) ?: DriveSessionOutboundBundle(emptyList(), emptyList())

        val allRecords = aliasRecords + catalogRecords + metaBundle.records + seriesRecords +
            evalBundle.records + annBundle.records + posBundle.records +
            vocabBundle.records + sessionBundle.records
        if (allRecords.isEmpty()) {
            if (publishedCount > 0) {
                publishManifest(permissionId, localDeviceId, accessToken)
            }
            return PublishResult(publishedCount)
        }

        // Oversized records are never marked published, so they stay dirty and are retried
        // once they shrink; they must not hold back the records that do fit.
        val chunking = DriveWireCodec.chunkRecordsForBatches(allRecords)
        val oversized = chunking.oversized.mapTo(mutableListOf()) { "${it.d}:${it.k}" }
        for (chunk in chunking.chunks) {
            val chunkKeys = chunk.map { it.d to it.k }.toSet()
            val chunkPosAcks = posBundle.positionAcks.filter { ack ->
                ack.recordKey.isEmpty() || (DriveWireCodec.DOMAIN_POSITION to ack.recordKey) in chunkKeys
            }
            val chunkPeakAcks = posBundle.peakAcks.filter { ack ->
                ack.recordKey.isEmpty() || (DriveWireCodec.DOMAIN_FURTHEST to ack.recordKey) in chunkKeys
            }
            val chunkAnnAcks = annBundle.acks.filter { ack ->
                (DriveWireCodec.DOMAIN_ANNOTATION to ack.id) in chunkKeys
            }
            val chunkEvalAcks = evalBundle.acks.filter { ack ->
                (DriveWireCodec.DOMAIN_EVALUATION to ack.recordKey) in chunkKeys
            }
            val chunkMetaAcks = metaBundle.acks.filter { ack ->
                (DriveWireCodec.DOMAIN_METADATA to ack.recordKey) in chunkKeys
            }
            val chunkVocabWordAcks = vocabBundle.wordAcks.filter { ack ->
                (DriveWireCodec.DOMAIN_VOCAB_WORD to ack.recordKey) in chunkKeys
            }
            val chunkVocabReviewAcks = vocabBundle.reviewAcks.filter { ack ->
                (DriveWireCodec.DOMAIN_VOCAB_REVIEW to ack.reviewId) in chunkKeys
            }
            val chunkSessionAcks = sessionBundle.acks.filter { ack ->
                (DriveWireCodec.DOMAIN_SESSION to ack.remoteSessionId) in chunkKeys
            }
            val ackMetadataJson = if (
                chunkPosAcks.isNotEmpty() || chunkPeakAcks.isNotEmpty() ||
                chunkAnnAcks.isNotEmpty() || chunkEvalAcks.isNotEmpty() || chunkMetaAcks.isNotEmpty() ||
                chunkVocabWordAcks.isNotEmpty() || chunkVocabReviewAcks.isNotEmpty() || chunkSessionAcks.isNotEmpty()
            ) {
                DriveWireCodec.encodeOutboxAckMetadata(
                    DriveOutboxAckMetadata(
                        positionAcks = chunkPosAcks,
                        peakAcks = chunkPeakAcks,
                        annotationAcks = chunkAnnAcks,
                        evaluationAcks = chunkEvalAcks,
                        metadataAcks = chunkMetaAcks,
                        vocabWordAcks = chunkVocabWordAcks,
                        vocabReviewAcks = chunkVocabReviewAcks,
                        sessionAcks = chunkSessionAcks,
                    ),
                )
            } else {
                null
            }
            val createdHlc = nextHlc(permissionId)
            val frozen = store.withTransaction {
                val account = requireNotNull(accounts.get(permissionId))
                val seq = account.nextSeq
                val batchId = DriveCanonicalJson.batchId(localDeviceId, seq)
                val domains = chunk.map { it.d }.distinct().associateWith { 1 }
                val observed = currentObservedVector(permissionId, localDeviceId)
                val batch = DriveDeltaBatch(
                    schemaVersion = DriveWireCodec.SCHEMA_VERSION,
                    batchId = batchId,
                    deviceId = localDeviceId,
                    seq = seq,
                    createdHlc = createdHlc,
                    domains = domains,
                    records = chunk,
                    observed = observed,
                )
                val payload = DriveWireCodec.encodeDeltaBatch(batch)
                if (payload.toByteArray(Charsets.UTF_8).size > DriveWireCodec.MAX_BATCH_BYTES) return@withTransaction null
                val entry = DriveOutbox(
                    permissionId = permissionId,
                    seq = seq,
                    batchId = batchId,
                    payload = payload,
                    state = DriveOutbox.STATE_BUILT,
                    fileId = null,
                    createdAt = clock(),
                    ackMetadataJson = ackMetadataJson,
                )
                accounts.setNextSeq(permissionId, seq + 1L)
                outbox.upsert(entry)
                entry
            }
            if (frozen == null) {
                chunk.mapTo(oversized) { "${it.d}:${it.k}" }
                continue
            }

            val uploaded = driveApi.uploadAppDataJsonBatch(
                accessToken = accessToken,
                name = DriveWireCodec.deltaFileName(localDeviceId, frozen.seq),
                jsonPayload = frozen.payload.toByteArray(Charsets.UTF_8),
                appProperties = DriveWireCodec.deltaAppProperties(
                    deviceId = localDeviceId,
                    seq = frozen.seq,
                    batchId = frozen.batchId,
                ),
            )
            outbox.markUploaded(permissionId, frozen.seq, uploaded.id)

            // Commit liseur.db acks first (Transaction 1), then drive_sync.db (Transaction 2).
            positionSync.acknowledgePublished(
                permissionId = permissionId,
                records = chunk,
                positionAcks = chunkPosAcks,
                peakAcks = chunkPeakAcks,
            )
            annotationSync?.acknowledgePublished(
                permissionId = permissionId,
                records = chunk,
                acks = chunkAnnAcks,
            )
            evaluationSync?.acknowledgePublished(
                permissionId = permissionId,
                records = chunk,
                acks = chunkEvalAcks,
            )
            metadataSync?.acknowledgePublished(
                permissionId = permissionId,
                records = chunk,
                acks = chunkMetaAcks,
            )
            vocabularySync?.acknowledgePublished(
                permissionId = permissionId,
                batchSeq = frozen.seq,
                records = chunk,
                wordAcks = chunkVocabWordAcks,
                reviewAcks = chunkVocabReviewAcks,
                localDeviceId = localDeviceId,
            )
            sessionSync?.acknowledgePublished(
                permissionId = permissionId,
                batchSeq = frozen.seq,
                records = chunk,
                acks = chunkSessionAcks,
            )
            store.withTransaction {
                workIdentity.markPublished(permissionId, chunk)
                catalogSync.markPublished(permissionId, chunk)
                seriesSync?.markPublished(permissionId, chunk)
                outbox.markAcked(permissionId, frozen.seq, uploaded.id)
                files.upsert(
                    DriveFile(
                        permissionId = permissionId,
                        fileId = uploaded.id,
                        name = DriveWireCodec.deltaFileName(localDeviceId, frozen.seq),
                        kind = DriveFile.KIND_DELTA,
                        deviceId = localDeviceId,
                        seq = frozen.seq,
                        size = frozen.payload.toByteArray(Charsets.UTF_8).size.toLong(),
                        md5 = uploaded.md5Checksum,
                        trashed = false,
                        createdTime = uploaded.createdTime,
                    ),
                )
                val acct = accounts.get(permissionId)
                if (acct != null) {
                    accounts.updateManifest(
                        permissionId = permissionId,
                        fileId = acct.manifestFileId,
                        snapshotThroughSeq = acct.snapshotThroughSeq,
                        deltasSinceSnapshot = acct.deltasSinceSnapshot + 1,
                    )
                }
            }
            publishedCount++
        }

        if (publishedCount > 0) {
            publishManifest(permissionId, localDeviceId, accessToken)
        }
        return PublishResult(publishedCount, oversized)
    }

    private suspend fun currentObservedVector(
        permissionId: String,
        localDeviceId: String,
    ): Map<String, Long> {
        val byDevice = cursors.forAccount(permissionId)
            .filter { it.deviceId != localDeviceId && it.domain != DriveWireCodec.STREAM_PAUSE_DOMAIN }
            .groupBy { it.deviceId }
        val allDomains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS.keys
        return buildMap {
            for ((deviceId, list) in byDevice) {
                val byDomain = list.associateBy { it.domain }
                val minSeq = allDomains.minOf { domain -> byDomain[domain]?.appliedSeq ?: 0L }
                if (minSeq > 0L) {
                    put(deviceId, minSeq)
                }
            }
        }
    }

    private suspend fun publishManifest(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
        snapshotOverride: DriveSnapshotRef? = null,
        previousSnapshotOverride: DriveSnapshotRef? = null,
        useCompactionStateDefaults: Boolean = snapshotOverride == null && previousSnapshotOverride == null,
    ): String? {
        val account = accounts.get(permissionId) ?: return null
        val hlc = nextHlc(permissionId)
        val appliedVector = currentObservedVector(permissionId, localDeviceId)
        val folderRef = account.folderId?.let { fid ->
            val file = files.get(permissionId, fid)
            DriveEpubFolderRef(fileId = fid, createdTime = file?.createdTime)
        }
        val (snapshotRef, previousSnapshotRef) = if (useCompactionStateDefaults) {
            val cs = compactor.currentCompactionState(permissionId)
            cs.snapshot to cs.previousSnapshot
        } else {
            snapshotOverride to previousSnapshotOverride
        }
        val manifest = DriveManifest(
            schemaVersion = DriveWireCodec.SCHEMA_VERSION,
            deviceId = localDeviceId,
            deviceLabel = deviceLabelProvider(),
            appVersionCode = appVersionCodeProvider(),
            updatedHlc = hlc,
            snapshot = snapshotRef,
            previousSnapshot = previousSnapshotRef,
            domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
            epubFolder = folderRef,
            appliedVector = appliedVector,
        )
        val bytes = DriveWireCodec.encodeManifest(manifest).toByteArray(Charsets.UTF_8)
        val props = DriveWireCodec.manifestAppProperties(localDeviceId)
        val name = DriveWireCodec.manifestFileName(localDeviceId)

        val existingFileId = account.manifestFileId ?: findExistingOwnManifestFileId(permissionId, localDeviceId, accessToken)
        val resultFile = if (existingFileId != null) {
            try {
                driveApi.updateFileMedia(
                    accessToken = accessToken,
                    fileId = existingFileId,
                    mimeType = "application/json",
                    content = bytes,
                    appProperties = props,
                )
            } catch (_: DriveNotFoundException) {
                driveApi.createAppDataFile(
                    accessToken = accessToken,
                    name = name,
                    mimeType = "application/json",
                    content = bytes,
                    appProperties = props,
                )
            }
        } else {
            driveApi.createAppDataFile(
                accessToken = accessToken,
                name = name,
                mimeType = "application/json",
                content = bytes,
                appProperties = props,
            )
        }
        accounts.updateManifest(
            permissionId = permissionId,
            fileId = resultFile.id,
            snapshotThroughSeq = account.snapshotThroughSeq,
            deltasSinceSnapshot = account.deltasSinceSnapshot,
        )
        return resultFile.id
    }

    private suspend fun findExistingOwnManifestFileId(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
    ): String? {
        files.byDeviceAndKind(permissionId, localDeviceId, DriveFile.KIND_MANIFEST)
            .firstOrNull { !it.trashed }
            ?.let { return it.fileId }
        val escaped = DriveRestClient.escapeDriveQueryLiteral(localDeviceId)
        val q = "appProperties has { key='kind' and value='manifest' } and " +
            "appProperties has { key='deviceId' and value='$escaped' } and trashed = false"
        return driveApi.listAppDataFiles(accessToken, query = q, pageSize = 5)
            .firstOrNull {
                !it.trashed &&
                    it.appProperties["kind"] == DriveFile.KIND_MANIFEST &&
                    it.appProperties["deviceId"] == localDeviceId
            }
            ?.id
    }

    private suspend fun indexRemoteFile(permissionId: String, remote: DriveRemoteFile): DriveFile? {
        val props = remote.appProperties
        val kind = when {
            props["liseurKind"] == DriveFile.KIND_EPUB -> DriveFile.KIND_EPUB
            remote.mimeType == DriveRestClient.FOLDER_MIME_TYPE && remote.name == DriveRestClient.LISEUR_FOLDER_NAME ->
                DriveFile.KIND_FOLDER
            else -> props["kind"]?.takeIf { it.isNotBlank() } ?: return null
        }
        val entry = DriveFile(
            permissionId = permissionId,
            fileId = remote.id,
            name = remote.name,
            kind = kind,
            deviceId = props["deviceId"]?.takeIf { it.isNotBlank() },
            seq = props["seq"]?.toLongOrNull() ?: props["throughSeq"]?.toLongOrNull(),
            sha256 = props["sha256"]?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() },
            size = remote.size,
            md5 = remote.md5Checksum,
            trashed = remote.trashed,
            createdTime = remote.createdTime,
        )
        files.upsert(entry)
        return entry
    }

    private suspend fun pauseDeviceStream(permissionId: String, remoteDeviceId: String, version: Int) {
        val existing = cursors.get(permissionId, remoteDeviceId, DriveWireCodec.STREAM_PAUSE_DOMAIN)
        cursors.upsert(
            DriveCursor(
                permissionId = permissionId,
                deviceId = remoteDeviceId,
                domain = DriveWireCodec.STREAM_PAUSE_DOMAIN,
                appliedSeq = existing?.appliedSeq ?: 0L,
                pausedSchemaVersion = version,
            ),
        )
    }

    private suspend fun clearStreamPause(permissionId: String, remoteDeviceId: String) {
        val existing = cursors.get(permissionId, remoteDeviceId, DriveWireCodec.STREAM_PAUSE_DOMAIN) ?: return
        if (existing.pausedSchemaVersion != null) {
            cursors.upsert(existing.copy(pausedSchemaVersion = null))
        }
    }

    private suspend fun pauseUnsupportedDomains(
        permissionId: String,
        remoteDeviceId: String,
        unsupported: Map<String, Int>,
    ) {
        for ((domain, version) in unsupported) {
            val existing = cursors.get(permissionId, remoteDeviceId, domain)
            cursors.upsert(
                DriveCursor(
                    permissionId = permissionId,
                    deviceId = remoteDeviceId,
                    domain = domain,
                    appliedSeq = existing?.appliedSeq ?: 0L,
                    pausedSchemaVersion = version,
                ),
            )
        }
    }

    private suspend fun clearNowSupportedDomainPauses(permissionId: String): Set<String> {
        val pausedCursors = cursors.pausedForAccount(permissionId)
        if (pausedCursors.isEmpty()) return emptySet()
        val unpausedDevices = linkedSetOf<String>()
        store.withTransaction {
            for (cursor in pausedCursors) {
                val pausedVersion = cursor.pausedSchemaVersion ?: continue
                val nowSupported = if (cursor.domain == DriveWireCodec.STREAM_PAUSE_DOMAIN) {
                    pausedVersion <= DriveWireCodec.SCHEMA_VERSION
                } else {
                    (DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS[cursor.domain] ?: 0) >= pausedVersion
                }
                if (nowSupported) {
                    cursors.upsert(cursor.copy(pausedSchemaVersion = null))
                    unpausedDevices.add(cursor.deviceId)
                }
            }
        }
        return unpausedDevices
    }

    private suspend fun minAppliedSeq(permissionId: String, remoteDeviceId: String): Long {
        // A supported domain without a cursor (never applied, e.g. deferred) counts as 0,
        // so its device keeps being processed until the record finally applies.
        val cursorByDomain = cursors.forDevice(permissionId, remoteDeviceId)
            .filter { it.domain != DriveWireCodec.STREAM_PAUSE_DOMAIN }
            .associateBy { it.domain }
        val activeSupported = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS.keys
            .filter { domain -> cursorByDomain[domain]?.pausedSchemaVersion == null }
        if (activeSupported.isEmpty()) return 0L
        return activeSupported.minOf { domain -> cursorByDomain[domain]?.appliedSeq ?: 0L }
    }

    private companion object {
        const val DRAIN_POLL_MS = 5L

        /** Domains whose records only apply to a local book and wait for its catalog entry. */
        val CATALOG_GATED_DOMAINS = setOf(
            DriveWireCodec.DOMAIN_METADATA,
            DriveWireCodec.DOMAIN_SERIES,
            DriveWireCodec.DOMAIN_EVALUATION,
            DriveWireCodec.DOMAIN_ANNOTATION,
            DriveWireCodec.DOMAIN_POSITION,
        )
    }
}
