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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val runMutex = Mutex()
    private val activeJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    private val accounts get() = store.accounts()
    private val devices get() = store.devices()
    private val cursors get() = store.cursors()
    private val files get() = store.files()
    private val outbox get() = store.outbox()
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

        // Ensure local books with fingerprints & metadata have deterministic Drive aliases
        // BEFORE applying incoming remote catalog/position records.
        workIdentity.resolveAllLocalBooks(permissionId)

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

        val paused = cursors.pausedForAccount(permissionId).isNotEmpty()
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
            )
        }
        accounts.recordSuccess(permissionId, clock())
        return DriveSyncRunSummary(
            outcome = SyncOutcome.Success,
            appliedDeltas = appliedDeltas,
            publishedBatches = published.batches,
            pausedBySchemaVersion = paused,
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

        // Step 2: enumerate appDataFolder files.
        val appDataFiles = driveApi.listAppDataFiles(accessToken)
        store.withTransaction {
            for (remote in appDataFiles) {
                if (remote.trashed) continue
                indexRemoteFile(permissionId, remote)
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

        for (remoteDeviceId in remoteDeviceIds) {
            val manifestFile = manifestsByDevice[remoteDeviceId]
            var manifest: DriveManifest? = null

            if (manifestFile != null) {
                val bytes = driveApi.downloadMedia(accessToken, manifestFile.fileId)
                val parsed = runCatching { DriveWireCodec.decodeManifest(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                if (parsed != null) {
                    manifest = parsed
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
                            continue
                        }
                        is DriveSchemaCheck.UnsupportedDomains -> {
                            pauseUnsupportedDomains(permissionId, remoteDeviceId, check.unsupported)
                            clearStreamPause(permissionId, remoteDeviceId)
                        }
                        DriveSchemaCheck.Supported -> {
                            clearStreamPause(permissionId, remoteDeviceId)
                        }
                    }

                    // If manifest advertises a snapshot ahead of our cursor, ingest it idempotently.
                    val snap = parsed.snapshot
                    if (snap != null && snap.throughSeq > minAppliedSeq(permissionId, remoteDeviceId)) {
                        applySnapshot(
                            permissionId = permissionId,
                            localDeviceId = localDeviceId,
                            remoteDeviceId = remoteDeviceId,
                            snapshot = snap,
                            accessToken = accessToken,
                        )
                    }
                }
            }

            // Skip downloading deltas if the whole device stream is paused due to schemaVersion.
            val streamCursor = cursors.get(permissionId, remoteDeviceId, DriveWireCodec.STREAM_PAUSE_DOMAIN)
            if (streamCursor?.pausedSchemaVersion != null) continue

            val deltaFiles = files.byDeviceAndKind(permissionId, remoteDeviceId, DriveFile.KIND_DELTA)
                .filter { !it.trashed && it.seq != null && it.seq > 0L }
                .sortedBy { it.seq }
            if (deltaFiles.isEmpty()) continue

            val startingCursor = minAppliedSeq(permissionId, remoteDeviceId)
            var expectedNextSeq = startingCursor + 1L
            val deviceDeferredDomains = mutableSetOf<String>()
            for (deltaFile in deltaFiles) {
                val seq = deltaFile.seq ?: continue
                if (seq <= startingCursor) continue
                // Enforce contiguous seq order: if there is a gap, stop and wait (or fall back to snapshot).
                if (seq > expectedNextSeq) break

                val bytes = driveApi.downloadMedia(accessToken, deltaFile.fileId)
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
                    )
                    if (!recordApplied) {
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
                store.withTransaction {
                    for (domain in domainsToAdvance) {
                        val existing = cursors.get(permissionId, remoteDeviceId, domain)
                        val nextApplied = maxOf(existing?.appliedSeq ?: 0L, seq)
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
                expectedNextSeq = seq + 1L
                appliedCount++
            }
        }
        return appliedCount
    }

    private suspend fun applySnapshot(
        permissionId: String,
        localDeviceId: String,
        remoteDeviceId: String,
        snapshot: DriveSnapshotRef,
        accessToken: String,
    ) {
        val allRecords = mutableListOf<DriveWireRecord>()
        val allResolutions = mutableListOf<DriveSnapshotResolution>()
        for (partRef in snapshot.parts) {
            val bytes = driveApi.downloadMedia(accessToken, partRef.fileId)
            val actualSha = DriveCanonicalJson.sha256Hex(bytes)
            if (partRef.sha256.isNotBlank() && !actualSha.equals(partRef.sha256, ignoreCase = true)) {
                return
            }
            val part = runCatching { DriveWireCodec.decodeSnapshotPart(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                ?: return
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
                    return
                }
                is DriveSchemaCheck.UnsupportedDomains -> {
                    pauseUnsupportedDomains(permissionId, remoteDeviceId, check.unsupported)
                }
                DriveSchemaCheck.Supported -> Unit
            }
            allResolutions.addAll(part.resolutions)
            allRecords.addAll(part.records)
        }

        val deviceCursors = cursors.forDevice(permissionId, remoteDeviceId)
        val pausedDomains = deviceCursors
            .filter { it.pausedSchemaVersion != null }
            .map { it.domain }
            .toSet()
        val appliedSeqByDomain = deviceCursors.associate { it.domain to it.appliedSeq }

        // Per docs/DRIVE_SYNC.md §2.4: ingest ALL snapshot `resolutions` across all parts
        // BEFORE reconciling `records` so `resRefs`-bridged supersession works across compaction.
        val now = clock()
        if (allResolutions.isNotEmpty()) {
            store.withTransaction {
                for (res in allResolutions) {
                    if (res.d in pausedDomains || res.d !in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS) continue
                    resolutions.upsert(
                        DriveResolution(
                            permissionId = permissionId,
                            domain = res.d,
                            recordKey = res.k,
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

        val deferredDomains = mutableSetOf<String>()
        val ordered = allRecords
            .filter {
                it.d !in pausedDomains &&
                    it.d in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS &&
                    snapshot.throughSeq > (appliedSeqByDomain[it.d] ?: 0L)
            }
            .sortedBy { DriveWireCodec.domainRank(it.d) }
        for (record in ordered) {
            if (record.d in deferredDomains) continue
            val recordApplied = applySingleRecord(
                permissionId = permissionId,
                localDeviceId = localDeviceId,
                remoteDeviceId = remoteDeviceId,
                remoteSeq = snapshot.throughSeq,
                accessToken = accessToken,
                record = record,
            )
            if (!recordApplied) {
                deferredDomains.add(record.d)
            }
        }

        store.withTransaction {
            for (domain in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS.keys) {
                if (domain in pausedDomains || domain in deferredDomains) continue
                val existing = cursors.get(permissionId, remoteDeviceId, domain)
                cursors.upsert(
                    DriveCursor(
                        permissionId = permissionId,
                        deviceId = remoteDeviceId,
                        domain = domain,
                        appliedSeq = maxOf(existing?.appliedSeq ?: 0L, snapshot.throughSeq),
                        pausedSchemaVersion = existing?.pausedSchemaVersion,
                    ),
                )
            }
        }
    }

    private suspend fun applySingleRecord(
        permissionId: String,
        localDeviceId: String,
        remoteDeviceId: String,
        remoteSeq: Long,
        accessToken: String,
        record: DriveWireRecord,
    ): Boolean {
        observeRemoteHlc(permissionId, record.hlc)
        // A malformed record is dropped, not deferred: replaying it would never make it valid.
        if (!DriveWireCodec.bodyMatchesKey(record)) return true
        val carriesBody = record.op == DriveRecordState.OP_PUT ||
            record.op == DriveRecordState.OP_RESOLVE && record.outcome == DriveResolution.OUTCOME_PUT
        if (record.d in CATALOG_GATED_DOMAINS && carriesBody) {
            val workKey = record.body?.optString("workKey")?.trim().orEmpty()
            if (workKey.isNotEmpty() && workIdentity.awaitsCatalogEntry(permissionId, workKey)) {
                return false
            }
        }
        return when (record.d) {
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
            DriveWireCodec.DOMAIN_SERIES -> {
                seriesSync?.applyRemoteRecord(
                    permissionId = permissionId,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    remoteSeq = remoteSeq,
                    nextHlc = { nextHlc(permissionId) },
                )
                true
            }
            DriveWireCodec.DOMAIN_EVALUATION -> {
                evaluationSync?.applyRemoteRecord(
                    permissionId = permissionId,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    remoteSeq = remoteSeq,
                    nextHlc = { nextHlc(permissionId) },
                )
                true
            }
            DriveWireCodec.DOMAIN_ANNOTATION -> {
                annotationSync?.applyRemoteRecord(
                    permissionId = permissionId,
                    record = record,
                    remoteDeviceId = remoteDeviceId,
                    remoteSeq = remoteSeq,
                    nextHlc = { nextHlc(permissionId) },
                )
                true
            }
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
            // Unimplemented domains (Phase 5 vocab/sessions) are paused before dispatch so cursors never advance.
            else -> true
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
            store.withTransaction {
                if (recoveredRecords.isNotEmpty()) {
                    workIdentity.markPublished(permissionId, recoveredRecords)
                    catalogSync.markPublished(permissionId, recoveredRecords)
                    seriesSync?.markPublished(permissionId, recoveredRecords)
                }
                outbox.markAcked(permissionId, existingInFlight.seq, fileId)
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

        val allRecords = aliasRecords + catalogRecords + metaBundle.records + seriesRecords +
            evalBundle.records + annBundle.records + posBundle.records
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
            val ackMetadataJson = if (
                chunkPosAcks.isNotEmpty() || chunkPeakAcks.isNotEmpty() ||
                chunkAnnAcks.isNotEmpty() || chunkEvalAcks.isNotEmpty() || chunkMetaAcks.isNotEmpty()
            ) {
                DriveWireCodec.encodeOutboxAckMetadata(
                    DriveOutboxAckMetadata(
                        positionAcks = chunkPosAcks,
                        peakAcks = chunkPeakAcks,
                        annotationAcks = chunkAnnAcks,
                        evaluationAcks = chunkEvalAcks,
                        metadataAcks = chunkMetaAcks,
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
                val batch = DriveDeltaBatch(
                    schemaVersion = DriveWireCodec.SCHEMA_VERSION,
                    batchId = batchId,
                    deviceId = localDeviceId,
                    seq = seq,
                    createdHlc = createdHlc,
                    domains = domains,
                    records = chunk,
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
            store.withTransaction {
                workIdentity.markPublished(permissionId, chunk)
                catalogSync.markPublished(permissionId, chunk)
                seriesSync?.markPublished(permissionId, chunk)
                outbox.markAcked(permissionId, frozen.seq, uploaded.id)
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

    private suspend fun publishManifest(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
    ) {
        val account = accounts.get(permissionId) ?: return
        val hlc = nextHlc(permissionId)
        val appliedVector = cursors.forAccount(permissionId)
            .filter { it.deviceId != localDeviceId && it.domain != DriveWireCodec.STREAM_PAUSE_DOMAIN }
            .groupBy { it.deviceId }
            .mapValues { (_, list) -> list.minOfOrNull { it.appliedSeq } ?: 0L }
        val folderRef = account.folderId?.let { fid ->
            val file = files.get(permissionId, fid)
            DriveEpubFolderRef(fileId = fid, createdTime = file?.createdTime)
        }
        val manifest = DriveManifest(
            schemaVersion = DriveWireCodec.SCHEMA_VERSION,
            deviceId = localDeviceId,
            deviceLabel = deviceLabelProvider(),
            appVersionCode = appVersionCodeProvider(),
            updatedHlc = hlc,
            snapshot = null,
            previousSnapshot = null,
            domains = DriveWireCodec.PHASE4_DOMAINS,
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
            seq = props["seq"]?.toLongOrNull(),
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
