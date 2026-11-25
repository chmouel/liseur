package com.chmouel.liseur.drive

import androidx.room.withTransaction
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** In-progress snapshot generation persisted before/during multipart upload for crash-safe retry. */
internal data class DrivePendingSnapshot(
    val throughSeq: Long,
    val createdHlc: String,
    val parts: List<DriveSnapshotPartRef> = emptyList(),
)

/**
 * Persisted per-account compaction pointers stored in `drive_record_state(_compaction_state/self)`.
 * The authored-version index lives in per-key `_authored:<domain>` rows to keep every row small.
 */
internal data class DriveCompactionState(
    val snapshot: DriveSnapshotRef? = null,
    val previousSnapshot: DriveSnapshotRef? = null,
    val prunedThroughSeq: Long = 0L,
    val pendingPruneFileIds: List<String> = emptyList(),
    val pendingSnapshot: DrivePendingSnapshot? = null,
    val orphanCleanupNeeded: Boolean = false,
)

internal data class DriveAuthoredEntry(val fp: String, val seq: Long?, val record: DriveWireRecord? = null)

/** Outcome of evaluating and optionally running per-device snapshot compaction. */
internal data class DriveCompactionResult(
    val compacted: Boolean = false,
    val throughSeq: Long = 0L,
    val partsUploaded: Int = 0,
    val prunedDeltaCount: Int = 0,
    val prunedSnapshotPartCount: Int = 0,
    val failureReason: String? = null,
)

/** Thrown when an indivisible single-item snapshot part exceeds [DriveCompactor.maxPartBytes]. */
internal class DriveOversizedSnapshotPartException(
    val actualBytes: Int,
    val maxPartBytes: Int,
    val recordDomain: String?,
    val recordKey: String?,
) : Exception(
    "Snapshot part for ${recordDomain ?: "envelope"}/${recordKey ?: "-"} is $actualBytes bytes, " +
        "exceeding maxPartBytes=$maxPartBytes",
)

/**
 * Per-device `drive.appdata` snapshot compaction and one-generation-lag pruning
 * (ADR-0078 & `docs/DRIVE_SYNC.md` §2.4).
 *
 * Invariants:
 * - Compacts when a device accumulates `>= deltaCountThreshold` (default 20) deltas
 *   or `>= deltaBytesThreshold` (default 2 MiB) of deltas since its last snapshot.
 * - Emits multi-part snapshot JSON files strictly bounded by [maxPartBytes] (default 4 MiB)
 *   covering all live records, durable tombstones (`OP_DEL`), and resolution records
 *   authored by this device across all 12 domains, PLUS the uncapped top-level
 *   `resolutions` array preserving every durable `drive_resolution` row authored by
 *   this device outside the 64-entry `anc` cap, preserving `resRefs` and vocabulary
 *   delete `observed` vectors.
 * - Reconciles against the remote manifest before replacing it, deduplicates/reuses
 *   already-uploaded parts on retry, uploads ALL new snapshot parts (`S_k`) first,
 *   atomically updates the device's manifest pointer (`snapshot = S_k`,
 *   `previousSnapshot = S_{k-1}`), and only then prunes superseded older snapshot parts
 *   (`S_{k-2}`), abandoned orphan snapshot parts for `localDeviceId`, and deltas
 *   (`seq <= T_{k-1}`).
 */
internal class DriveCompactor(
    private val store: DriveSyncDatabase,
    private val driveApi: DriveRemoteApi,
    private val deltaCountThreshold: Int = DEFAULT_DELTA_COUNT_THRESHOLD,
    private val deltaBytesThreshold: Long = DEFAULT_DELTA_BYTES_THRESHOLD,
    private val maxPartBytes: Int = DriveWireCodec.MAX_SNAPSHOT_PART_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(deltaCountThreshold > 0) { "deltaCountThreshold must be positive" }
        require(deltaBytesThreshold > 0L) { "deltaBytesThreshold must be positive" }
        require(maxPartBytes >= MIN_PART_BYTES) { "maxPartBytes must be at least $MIN_PART_BYTES bytes" }
    }

    private val accounts get() = store.accounts()
    private val outbox get() = store.outbox()
    private val files get() = store.files()
    private val recordStates get() = store.recordStates()
    private val resolutions get() = store.resolutions()

    /** Reads the currently persisted snapshot pointers so manifest refreshes preserve them. */
    suspend fun currentCompactionState(permissionId: String): DriveCompactionState {
        val row = recordStates.get(permissionId, COMPACTION_STATE_DOMAIN, COMPACTION_STATE_KEY)
            ?: return DriveCompactionState()
        val raw = row.bodyJson ?: return DriveCompactionState()
        return decodeCompactionState(raw)
    }

    /**
     * Reconciles a pending snapshot publication against the remote manifest BEFORE any
     * manifest rewrite during delta publishing, so a crash between remote manifest
     * publication and local DB commit never gets overwritten by a subsequent delta's
     * manifest refresh.
     */
    suspend fun reconcilePublishedStateIfPending(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
    ): DriveCompactionResult? {
        val state = currentCompactionState(permissionId)
        if (state.pendingSnapshot == null) return null
        val account = accounts.get(permissionId) ?: return null
        val reconciled = reconcileRemoteManifest(
            permissionId = permissionId,
            localDeviceId = localDeviceId,
            accessToken = accessToken,
            state = state,
            knownManifestFileId = account.manifestFileId,
        )
        if (!reconciled.adopted) return null
        val postPrune = pruneSuperseded(
            permissionId = permissionId,
            localDeviceId = localDeviceId,
            accessToken = accessToken,
            state = reconciled.state,
            gcOrphanSnapshots = true,
            prefetchedOwnSnapshotFiles = reconciled.ownSnapshotFiles,
            protectedRemoteSnapshotIds = reconciled.remoteManifestSnapshotIds,
        )
        return DriveCompactionResult(
            compacted = true,
            throughSeq = reconciled.state.snapshot?.throughSeq ?: 0L,
            partsUploaded = 0,
            prunedDeltaCount = postPrune.prunedDeltas,
            prunedSnapshotPartCount = postPrune.prunedSnapshotParts,
        )
    }

    /**
     * Evaluates compaction thresholds for [permissionId] and runs compaction + pruning
     * when triggered. Also reconciles interrupted manifest publications and drains any
     * pending/orphan prunes left over from a prior run.
     */
    suspend fun compactIfNeeded(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
        nextHlc: suspend () -> String,
        publishManifest: suspend (
            snapshot: DriveSnapshotRef?,
            previousSnapshot: DriveSnapshotRef?,
        ) -> String?,
    ): DriveCompactionResult {
        var state = currentCompactionState(permissionId)
        var prunedSnapshotParts = 0
        var prunedDeltas = 0
        var adoptedThroughSeq: Long? = null
        var cachedOwnSnapshotFiles: List<DriveRemoteFile>? = null
        var cachedRemoteManifest: Pair<String, DriveManifest>? = null
        var remoteManifestChecked = false

        val initialAccount = accounts.get(permissionId)
        if (initialAccount != null && state.pendingSnapshot != null) {
            val reconciled = reconcileRemoteManifest(
                permissionId = permissionId,
                localDeviceId = localDeviceId,
                accessToken = accessToken,
                state = state,
                knownManifestFileId = initialAccount.manifestFileId,
            )
            remoteManifestChecked = true
            cachedOwnSnapshotFiles = reconciled.ownSnapshotFiles
            cachedRemoteManifest = reconciled.remoteManifestPair
            state = reconciled.state
            if (reconciled.adopted) {
                adoptedThroughSeq = state.snapshot?.throughSeq
            }
        }

        // Finish any pending prunes left over from an earlier interrupted run.
        val shouldGcInitially = adoptedThroughSeq != null ||
            state.pendingPruneFileIds.isNotEmpty() ||
            (state.orphanCleanupNeeded && initialAccount != null) ||
            (state.previousSnapshot?.throughSeq ?: 0L) > state.prunedThroughSeq
        val initialPrune = pruneSuperseded(
            permissionId = permissionId,
            localDeviceId = localDeviceId,
            accessToken = accessToken,
            state = state,
            gcOrphanSnapshots = shouldGcInitially,
            prefetchedOwnSnapshotFiles = cachedOwnSnapshotFiles,
            protectedRemoteSnapshotIds = snapshotFileIdsOf(cachedRemoteManifest?.second),
        )
        state = initialPrune.state
        prunedSnapshotParts += initialPrune.prunedSnapshotParts
        prunedDeltas += initialPrune.prunedDeltas
        if (initialPrune.prunedSnapshotParts > 0) {
            cachedOwnSnapshotFiles = null
        }

        fun earlyResult(): DriveCompactionResult = DriveCompactionResult(
            compacted = adoptedThroughSeq != null,
            throughSeq = adoptedThroughSeq ?: 0L,
            partsUploaded = 0,
            prunedDeltaCount = prunedDeltas,
            prunedSnapshotPartCount = prunedSnapshotParts,
        )

        var account = accounts.get(permissionId) ?: return earlyResult()
        // Never compact while a delta batch is still in flight.
        if (outbox.inFlight(permissionId) != null) {
            return earlyResult()
        }

        val throughSeq = account.nextSeq - 1L
        if (throughSeq <= account.snapshotThroughSeq || throughSeq <= 0L) {
            return earlyResult()
        }

        suspend fun exceedsThreshold(acct: DriveAccount): Boolean {
            val ackedSinceSnapshot = outbox.ackedAfter(permissionId, acct.snapshotThroughSeq)
                .filter { it.seq <= throughSeq }
            val deltaCountSinceSnapshot = maxOf(acct.deltasSinceSnapshot, ackedSinceSnapshot.size)
            val deltaBytesSinceSnapshot = ackedSinceSnapshot.sumOf {
                it.payload.toByteArray(Charsets.UTF_8).size.toLong()
            }
            return deltaCountSinceSnapshot >= deltaCountThreshold ||
                deltaBytesSinceSnapshot >= deltaBytesThreshold
        }

        if (!exceedsThreshold(account)) {
            return earlyResult()
        }

        // Reconcile against the remote manifest before uploading or replacing it
        // (covers crash between remote manifest publication and local state commit,
        // even if local pendingSnapshot was rolled back or absent).
        if (!remoteManifestChecked) {
            val reconciled = reconcileRemoteManifest(
                permissionId = permissionId,
                localDeviceId = localDeviceId,
                accessToken = accessToken,
                state = state,
                knownManifestFileId = account.manifestFileId,
            )
            cachedOwnSnapshotFiles = reconciled.ownSnapshotFiles
            cachedRemoteManifest = reconciled.remoteManifestPair
            state = reconciled.state
            if (reconciled.adopted) {
                adoptedThroughSeq = state.snapshot?.throughSeq
                val adoptPrune = pruneSuperseded(
                    permissionId = permissionId,
                    localDeviceId = localDeviceId,
                    accessToken = accessToken,
                    state = state,
                    gcOrphanSnapshots = true,
                    prefetchedOwnSnapshotFiles = cachedOwnSnapshotFiles,
                    protectedRemoteSnapshotIds = reconciled.remoteManifestSnapshotIds,
                )
                state = adoptPrune.state
                prunedSnapshotParts += adoptPrune.prunedSnapshotParts
                prunedDeltas += adoptPrune.prunedDeltas
                cachedOwnSnapshotFiles = null
                account = accounts.get(permissionId) ?: return earlyResult()
                if (throughSeq <= account.snapshotThroughSeq || !exceedsThreshold(account)) {
                    return earlyResult()
                }
            }
        }

        // 1. Collect all locally authored records across all 12 domains + authored resolutions.
        val (allStates, allResolutions) = readStatesAndResolutions(permissionId)
        // A key whose current version is unpublished (new, dirty or held by a conflict) would be
        // omitted while the covered sequence advances, losing its last published version.
        if (allStates.any {
                it.domain in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS && it.publishedFp != it.versionFp
            }
        ) {
            return earlyResult()
        }
        val (snapshotRecords, nextAuthoredFps) =
            collectAuthoredRecords(permissionId, localDeviceId, throughSeq, allStates, allResolutions)
        val snapshotResolutions = collectAuthoredResolutions(localDeviceId, allResolutions)

        // Reuse the pending generation's HLC across retries for the same `throughSeq` so
        // serialized part bytes & SHA-256 remain deterministic and already-uploaded parts can be reused.
        val reusablePending = state.pendingSnapshot?.takeIf { it.throughSeq == throughSeq }
        val createdHlc = reusablePending?.createdHlc ?: nextHlc()

        // Build & validate ALL part sizes BEFORE persisting pending state or uploading anything.
        val encodedParts = try {
            buildAndSplitSnapshotParts(
                deviceId = localDeviceId,
                throughSeq = throughSeq,
                createdHlc = createdHlc,
                records = snapshotRecords,
                resolutions = snapshotResolutions,
            )
        } catch (e: DriveOversizedSnapshotPartException) {
            return DriveCompactionResult(
                compacted = false,
                throughSeq = 0L,
                partsUploaded = 0,
                prunedDeltaCount = prunedDeltas,
                prunedSnapshotPartCount = prunedSnapshotParts,
                failureReason = e.message,
            )
        }

        // Move any abandoned pending snapshot parts from an older interrupted throughSeq into pendingPrune.
        val abandonedPendingIds = if (state.pendingSnapshot != null && state.pendingSnapshot.throughSeq != throughSeq) {
            state.pendingSnapshot.parts.map { it.fileId }
        } else {
            emptyList()
        }
        var activePending = reusablePending ?: DrivePendingSnapshot(
            throughSeq = throughSeq,
            createdHlc = createdHlc,
            parts = emptyList(),
        )
        state = state.copy(
            pendingPruneFileIds = (state.pendingPruneFileIds + abandonedPendingIds).distinct(),
            pendingSnapshot = activePending,
        )
        persistCompactionState(permissionId, state, createdHlc)

        // 2. Check existing appDataFolder snapshot parts for localDeviceId and reuse matching parts
        //    on retry; upload only missing parts BEFORE updating the manifest pointer.
        val existingOwnSnapshotFiles = cachedOwnSnapshotFiles ?: listOwnSnapshotFiles(localDeviceId, accessToken)
        val pendingPartsByFileId = activePending.parts.associateBy { it.fileId }
        val uploadedPartRefs = mutableListOf<DriveSnapshotPartRef>()
        val uploadedDriveFiles = mutableListOf<DriveFile>()
        var actuallyUploadedCount = 0

        for (encoded in encodedParts) {
            val name = DriveWireCodec.snapshotPartFileName(localDeviceId, throughSeq, encoded.part.partIndex)
            val expectedSize = encoded.bytes.size.toLong()
            val reusedRemote = findReusableSnapshotPart(
                existingOwnSnapshotFiles = existingOwnSnapshotFiles,
                pendingPartsByFileId = pendingPartsByFileId,
                accessToken = accessToken,
                throughSeq = throughSeq,
                partIndex = encoded.part.partIndex,
                expectedName = name,
                expectedSize = expectedSize,
                expectedSha256 = encoded.sha256,
            )

            val partRemote = if (reusedRemote != null) {
                reusedRemote
            } else {
                val props = DriveWireCodec.snapshotPartAppProperties(
                    deviceId = localDeviceId,
                    throughSeq = throughSeq,
                    partIndex = encoded.part.partIndex,
                    sha256 = encoded.sha256,
                )
                actuallyUploadedCount++
                driveApi.uploadResumableFile(
                    accessToken = accessToken,
                    name = name,
                    mimeType = "application/json",
                    totalBytes = expectedSize,
                    parents = listOf(DriveRestClient.SPACE_APPDATA),
                    appProperties = props,
                    openStream = { ByteArrayInputStream(encoded.bytes) },
                )
            }

            val partRef = DriveSnapshotPartRef(
                name = name,
                fileId = partRemote.id,
                sha256 = encoded.sha256,
                records = encoded.part.records.size,
                resolutions = encoded.part.resolutions.size,
            )
            val driveFile = DriveFile(
                permissionId = permissionId,
                fileId = partRemote.id,
                name = name,
                kind = DriveFile.KIND_SNAPSHOT,
                deviceId = localDeviceId,
                seq = throughSeq,
                sha256 = encoded.sha256,
                size = expectedSize,
                md5 = partRemote.md5Checksum,
                trashed = false,
                createdTime = partRemote.createdTime,
            )
            uploadedPartRefs.add(partRef)
            uploadedDriveFiles.add(driveFile)

            // Persist each completed/reused part incrementally so a mid-multipart failure
            // remembers every already-uploaded part on retry.
            val updatedPendingParts = (activePending.parts.filterNot { it.fileId == partRef.fileId } + partRef)
                .distinctBy { it.fileId }
            activePending = activePending.copy(parts = updatedPendingParts)
            state = state.copy(pendingSnapshot = activePending)
            store.withTransaction {
                files.upsert(driveFile)
                persistCompactionState(permissionId, state, createdHlc)
            }
        }

        val newSnapshot = DriveSnapshotRef(throughSeq = throughSeq, parts = uploadedPartRefs)
        val newPreviousSnapshot = state.snapshot
        val supersededSnapshot = state.previousSnapshot
        val liveSnapshotIds = (newSnapshot.parts + newPreviousSnapshot?.parts.orEmpty())
            .map { it.fileId }
            .toSet()
        val newPendingPruneIds = (
            state.pendingPruneFileIds +
                supersededSnapshot?.parts.orEmpty().map { it.fileId } +
                activePending.parts.map { it.fileId }
            )
            .distinct()
            .filter { it !in liveSnapshotIds }

        // 3. Atomically update manifest pointer on Drive with S_k and S_{k-1}.
        val manifestFileId = publishManifest(newSnapshot, newPreviousSnapshot)

        // 4. Commit updated compaction state & manifest counters in drive_sync.db.
        val updatedState = DriveCompactionState(
            snapshot = newSnapshot,
            previousSnapshot = newPreviousSnapshot,
            prunedThroughSeq = state.prunedThroughSeq,
            pendingPruneFileIds = newPendingPruneIds,
            pendingSnapshot = null,
            orphanCleanupNeeded = state.orphanCleanupNeeded,
        )
        store.withTransaction {
            for (df in uploadedDriveFiles) {
                files.upsert(df)
            }
            val latestAccount = accounts.get(permissionId)
            accounts.updateManifest(
                permissionId = permissionId,
                fileId = manifestFileId ?: latestAccount?.manifestFileId,
                snapshotThroughSeq = throughSeq,
                deltasSinceSnapshot = 0,
            )
            persistCompactionState(permissionId, updatedState, createdHlc)
            persistAuthoredIndex(permissionId, nextAuthoredFps)
        }

        // 5. Prune superseded snapshot parts (S_{k-2}), orphan snapshot parts belonging to
        //    localDeviceId, and deltas (seq <= T_{k-1}). Pruning errors are non-fatal once
        //    S_k and manifest have committed; leftover items stay tracked for retry.
        val postPrune = pruneSuperseded(
            permissionId = permissionId,
            localDeviceId = localDeviceId,
            accessToken = accessToken,
            state = updatedState,
            gcOrphanSnapshots = true,
            protectedRemoteSnapshotIds = liveSnapshotIds,
        )
        prunedSnapshotParts += postPrune.prunedSnapshotParts
        prunedDeltas += postPrune.prunedDeltas

        return DriveCompactionResult(
            compacted = true,
            throughSeq = throughSeq,
            partsUploaded = actuallyUploadedCount,
            prunedDeltaCount = prunedDeltas,
            prunedSnapshotPartCount = prunedSnapshotParts,
        )
    }

    private data class RemoteManifestReconcileOutcome(
        val state: DriveCompactionState,
        val adopted: Boolean,
        val ownSnapshotFiles: List<DriveRemoteFile>,
        val remoteManifestPair: Pair<String, DriveManifest>?,
        val remoteManifestSnapshotIds: Set<String>,
    )

    private suspend fun reconcileRemoteManifest(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
        state: DriveCompactionState,
        knownManifestFileId: String?,
    ): RemoteManifestReconcileOutcome {
        val ownSnapshotFiles = listOwnSnapshotFiles(localDeviceId, accessToken)
        val remoteManifestPair = fetchOwnRemoteManifest(
            permissionId = permissionId,
            localDeviceId = localDeviceId,
            accessToken = accessToken,
            knownManifestFileId = knownManifestFileId,
        )
        val remoteManifest = remoteManifestPair?.second
        val remoteManifestIds = snapshotFileIdsOf(remoteManifest)
        val remoteSnap = remoteManifest?.snapshot
        val localThroughSeq = state.snapshot?.throughSeq ?: 0L

        if (remoteSnap == null || remoteSnap.throughSeq <= localThroughSeq || remoteSnap.parts.isEmpty()) {
            return RemoteManifestReconcileOutcome(
                state = state,
                adopted = false,
                ownSnapshotFiles = ownSnapshotFiles,
                remoteManifestPair = remoteManifestPair,
                remoteManifestSnapshotIds = remoteManifestIds,
            )
        }

        val liveFilesById = ownSnapshotFiles.associateBy { it.id }
        val allPartsPresent = remoteSnap.parts.all { it.fileId in liveFilesById }
        if (!allPartsPresent) {
            return RemoteManifestReconcileOutcome(
                state = state,
                adopted = false,
                ownSnapshotFiles = ownSnapshotFiles,
                remoteManifestPair = remoteManifestPair,
                remoteManifestSnapshotIds = remoteManifestIds,
            )
        }

        // Adopt the already-published remote manifest snapshot into local compaction state.
        val (adoptStates, adoptResolutions) = readStatesAndResolutions(permissionId)
        val (_, refreshedAuthoredFps) =
            collectAuthoredRecords(permissionId, localDeviceId, remoteSnap.throughSeq, adoptStates, adoptResolutions)
        val adoptedPrev = remoteManifest.previousSnapshot
        val liveAdoptedIds = (remoteSnap.parts + adoptedPrev?.parts.orEmpty())
            .map { it.fileId }
            .toSet()
        val newlySupersededIds = (
            state.pendingPruneFileIds +
                state.snapshot?.parts.orEmpty().map { it.fileId } +
                state.previousSnapshot?.parts.orEmpty().map { it.fileId } +
                state.pendingSnapshot?.parts.orEmpty().map { it.fileId }
            )
            .distinct()
            .filter { it !in liveAdoptedIds }

        val adoptedState = DriveCompactionState(
            snapshot = remoteSnap,
            previousSnapshot = adoptedPrev,
            prunedThroughSeq = state.prunedThroughSeq,
            pendingPruneFileIds = newlySupersededIds,
            pendingSnapshot = null,
            orphanCleanupNeeded = state.orphanCleanupNeeded,
        )
        val deltasAfterAdopted = outbox.ackedAfter(permissionId, remoteSnap.throughSeq).size
        val manifestFileId = remoteManifestPair.first

        store.withTransaction {
            for (partRef in remoteSnap.parts) {
                val remoteMeta = liveFilesById[partRef.fileId]
                files.upsert(
                    DriveFile(
                        permissionId = permissionId,
                        fileId = partRef.fileId,
                        name = partRef.name.ifBlank { remoteMeta?.name.orEmpty() },
                        kind = DriveFile.KIND_SNAPSHOT,
                        deviceId = localDeviceId,
                        seq = remoteSnap.throughSeq,
                        sha256 = partRef.sha256.takeIf { it.isNotBlank() },
                        size = remoteMeta?.size,
                        md5 = remoteMeta?.md5Checksum,
                        trashed = false,
                        createdTime = remoteMeta?.createdTime,
                    ),
                )
            }
            accounts.updateManifest(
                permissionId = permissionId,
                fileId = manifestFileId,
                snapshotThroughSeq = remoteSnap.throughSeq,
                deltasSinceSnapshot = deltasAfterAdopted,
            )
            persistCompactionState(permissionId, adoptedState, remoteManifest.updatedHlc)
            persistAuthoredIndex(permissionId, refreshedAuthoredFps)
        }

        return RemoteManifestReconcileOutcome(
            state = adoptedState,
            adopted = true,
            ownSnapshotFiles = ownSnapshotFiles,
            remoteManifestPair = remoteManifestPair,
            remoteManifestSnapshotIds = remoteManifestIds,
        )
    }

    private suspend fun findReusableSnapshotPart(
        existingOwnSnapshotFiles: List<DriveRemoteFile>,
        pendingPartsByFileId: Map<String, DriveSnapshotPartRef>,
        accessToken: String,
        throughSeq: Long,
        partIndex: Int,
        expectedName: String,
        expectedSize: Long,
        expectedSha256: String,
    ): DriveRemoteFile? {
        for (remote in existingOwnSnapshotFiles) {
            val remoteThroughSeq = remote.appProperties["throughSeq"]?.toLongOrNull()
            val remotePartIndex = remote.appProperties["partIndex"]?.toIntOrNull()
            val matchesSlot = (remoteThroughSeq == throughSeq && remotePartIndex == partIndex) ||
                remote.name == expectedName
            if (!matchesSlot) continue
            if (remote.size != null && remote.size != expectedSize) continue

            val knownSha = remote.appProperties["sha256"]?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
                ?: pendingPartsByFileId[remote.id]?.sha256?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            if (knownSha != null) {
                if (knownSha == expectedSha256) return remote
            } else {
                val downloaded = runCatching { driveApi.downloadMedia(accessToken, remote.id) }.getOrNull()
                if (downloaded != null &&
                    downloaded.size.toLong() == expectedSize &&
                    DriveCanonicalJson.sha256Hex(downloaded) == expectedSha256
                ) {
                    return remote
                }
            }
        }
        return null
    }

    private suspend fun listOwnSnapshotFiles(
        localDeviceId: String,
        accessToken: String,
    ): List<DriveRemoteFile> {
        val escaped = DriveRestClient.escapeDriveQueryLiteral(localDeviceId)
        val q = "appProperties has { key='kind' and value='${DriveFile.KIND_SNAPSHOT}' } and " +
            "appProperties has { key='deviceId' and value='$escaped' } and trashed = false"
        return driveApi.listAppDataFiles(accessToken, query = q)
            .filter {
                !it.trashed &&
                    it.appProperties["kind"] == DriveFile.KIND_SNAPSHOT &&
                    it.appProperties["deviceId"] == localDeviceId
            }
            .sortedBy { it.id }
    }

    private suspend fun fetchOwnRemoteManifest(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
        knownManifestFileId: String?,
    ): Pair<String, DriveManifest>? {
        val candidateId = knownManifestFileId
            ?: files.byDeviceAndKind(permissionId, localDeviceId, DriveFile.KIND_MANIFEST)
                .firstOrNull { !it.trashed }
                ?.fileId
            ?: run {
                val escaped = DriveRestClient.escapeDriveQueryLiteral(localDeviceId)
                val q = "appProperties has { key='kind' and value='${DriveFile.KIND_MANIFEST}' } and " +
                    "appProperties has { key='deviceId' and value='$escaped' } and trashed = false"
                driveApi.listAppDataFiles(accessToken, query = q, pageSize = 5)
                    .firstOrNull {
                        !it.trashed &&
                            it.appProperties["kind"] == DriveFile.KIND_MANIFEST &&
                            it.appProperties["deviceId"] == localDeviceId
                    }
                    ?.id
            }
            ?: return null

        val bytes = try {
            driveApi.downloadMedia(accessToken, candidateId)
        } catch (_: DriveNotFoundException) {
            return null
        }
        val manifest = runCatching { DriveWireCodec.decodeManifest(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return null
        if (manifest.deviceId != localDeviceId) return null
        return candidateId to manifest
    }

    private fun snapshotFileIdsOf(manifest: DriveManifest?): Set<String> = buildSet {
        manifest?.snapshot?.parts?.forEach { add(it.fileId) }
        manifest?.previousSnapshot?.parts?.forEach { add(it.fileId) }
    }

    private suspend fun readStatesAndResolutions(permissionId: String): Pair<List<DriveRecordState>, List<DriveResolution>> =
        store.withTransaction {
            recordStates.forAccount(permissionId) to resolutions.forAccount(permissionId)
        }

    internal suspend fun loadAuthoredIndex(permissionId: String): Map<Pair<String, String>, DriveAuthoredEntry> {
        val index = linkedMapOf<Pair<String, String>, DriveAuthoredEntry>()
        for (domain in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS.keys) {
            for (row in recordStates.forDomain(permissionId, AUTHORED_DOMAIN_PREFIX + domain)) {
                val record = row.bodyJson?.let { raw ->
                    runCatching { DriveWireCodec.parseWireRecords(JSONArray().put(JSONObject(raw))).singleOrNull() }.getOrNull()
                }
                index[domain to row.recordKey] = DriveAuthoredEntry(row.versionFp, row.originSeq, record)
            }
        }
        return index
    }

    /** Replaces the index with one small row per key; must run inside a `store` transaction. */
    private suspend fun persistAuthoredIndex(
        permissionId: String,
        index: Map<String, Map<String, DriveAuthoredEntry>>,
    ) {
        for (domain in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS.keys) {
            val internalDomain = AUTHORED_DOMAIN_PREFIX + domain
            recordStates.deleteDomain(permissionId, internalDomain)
            val rows = index[domain].orEmpty().map { (key, entry) ->
                DriveRecordState(
                    permissionId = permissionId,
                    domain = internalDomain,
                    recordKey = key,
                    versionFp = entry.fp,
                    versionOp = DriveRecordState.OP_PUT,
                    originSeq = entry.seq,
                    lastHlc = "",
                    bodyJson = entry.record?.let { DriveCanonicalJson.encode(DriveWireCodec.wireRecordToMap(it)) },
                )
            }
            if (rows.isNotEmpty()) recordStates.upsertAll(rows)
        }
    }

    private suspend fun collectAuthoredRecords(
        permissionId: String,
        localDeviceId: String,
        throughSeq: Long,
        allStates: List<DriveRecordState>,
        allResolutions: List<DriveResolution>,
    ): Pair<List<DriveWireRecord>, Map<String, Map<String, DriveAuthoredEntry>>> {
        val resolutionsByKey = allResolutions
            .associateBy { Triple(it.domain, it.recordKey, it.resolutionFp) }

        // Keep the authored envelope: the current state may have adopted a different remote version.
        val locallyPublished = linkedMapOf<Pair<String, String>, DriveAuthoredEntry>()
        locallyPublished.putAll(loadAuthoredIndex(permissionId).filterValues { it.seq == null || it.seq <= throughSeq })
        for (entry in outbox.ackedUpTo(permissionId, throughSeq)) {
            val batch = runCatching { DriveWireCodec.decodeDeltaBatch(entry.payload) }.getOrNull() ?: continue
            for (rec in batch.records) {
                if (rec.d in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS && rec.fp.isNotBlank()) {
                    val key = rec.d to rec.k
                    if ((locallyPublished[key]?.seq ?: Long.MIN_VALUE) <= entry.seq) {
                        val authored = if (rec.d == DriveWireCodec.DOMAIN_VOCAB_WORD && rec.op == DriveRecordState.OP_DEL) {
                            rec.copy(observed = DriveVocabularySync.mergeBoundaries(rec.observed ?: batch.observed, mapOf(localDeviceId to entry.seq)))
                        } else {
                            rec
                        }
                        locallyPublished[key] = DriveAuthoredEntry(rec.fp, entry.seq, authored)
                    }
                }
            }
        }
        val deleteBoundaries = recordStates.forDomain(permissionId, DriveVocabularySync.DELETE_BOUNDARY_DOMAIN)
            .associate { it.recordKey to parseDeleteBoundary(it.bodyJson) }

        val records = mutableListOf<DriveWireRecord>()
        val nextAuthoredByDomain = linkedMapOf<String, MutableMap<String, DriveAuthoredEntry>>()

        for (state in allStates) {
            if (state.domain !in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS) continue
            if (state.publishedFp.isNullOrBlank() || state.publishedFp != state.versionFp) continue

            val lastAuthored = locallyPublished[state.domain to state.recordKey]
            val published = lastAuthored?.takeIf { it.fp == state.versionFp }
            if (published == null && lastAuthored?.record != null) {
                val boundary = deleteBoundaries[state.recordKey].orEmpty()
                val observed = if (state.domain == DriveWireCodec.DOMAIN_VOCAB_WORD && boundary.isNotEmpty()) {
                    DriveVocabularySync.mergeBoundaries(lastAuthored.record.observed.orEmpty(), boundary)
                } else {
                    lastAuthored.record.observed
                }
                val record = lastAuthored.record.copy(
                    seq = lastAuthored.seq.takeIf { state.domain in SEQ_CARRYING_DOMAINS },
                    observed = observed,
                )
                val deletedWord = state.domain == DriveWireCodec.DOMAIN_VOCAB_WORD &&
                    state.versionOp == DriveRecordState.OP_DEL && record.op == DriveRecordState.OP_PUT
                val readdedCatalog = state.domain == DriveWireCodec.DOMAIN_CATALOG &&
                    state.versionOp == DriveRecordState.OP_PUT && record.op == DriveRecordState.OP_DEL
                val coveredChild = record.body != null &&
                    childCoveredByDelete(state.domain, record.body, localDeviceId, lastAuthored.seq, deleteBoundaries)
                if (!deletedWord && !readdedCatalog && !coveredChild) {
                    records.add(record)
                    nextAuthoredByDomain.getOrPut(state.domain) { linkedMapOf() }[state.recordKey] =
                        lastAuthored.copy(record = record)
                }
                continue
            }
            if (state.originDeviceId != null && published == null) continue
            // originSeq is the local publication seq only while provenance is still local; an
            // agreement may have replaced it with the remote author's.
            val localSeq = (if (state.originDeviceId == null) state.originSeq else null) ?: published?.seq

            val anc = DriveWorkIdentity.decodeStringArray(state.ancestorsJson)
                .distinct()
                .filter { it != state.versionFp }
                .take(DriveWireCodec.MAX_ANCESTORS)
            val resRefs = DriveWorkIdentity.decodeStringArray(state.resRefsJson)
                .distinct()
                .sorted()

            val emitted: DriveWireRecord? = when (state.versionOp) {
                DriveRecordState.OP_PUT -> {
                    val body = state.bodyJson?.let { raw ->
                        runCatching { JSONObject(raw) }.getOrNull()
                    }
                    val coveredChild = body != null &&
                        childCoveredByDelete(state.domain, body, localDeviceId, localSeq, deleteBoundaries)
                    val wordBoundary = if (state.domain == DriveWireCodec.DOMAIN_VOCAB_WORD) {
                        deleteBoundaries[state.recordKey]?.takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }
                    body?.takeUnless { coveredChild }?.let {
                        DriveWireRecord(
                            d = state.domain,
                            k = state.recordKey,
                            op = DriveRecordState.OP_PUT,
                            hlc = state.lastHlc,
                            fp = state.versionFp,
                            anc = anc,
                            resRefs = resRefs,
                            body = it,
                            observed = wordBoundary,
                            seq = localSeq.takeIf { state.domain in SEQ_CARRYING_DOMAINS },
                        )
                    }
                }
                DriveRecordState.OP_DEL -> {
                    val observed = if (state.domain == DriveWireCodec.DOMAIN_VOCAB_WORD) {
                        // The delete also covers its author's own records up to its publication.
                        val covered = DriveVocabularySync.mergeBoundaries(
                            parseVocabDeleteObserved(state.bodyJson).orEmpty(),
                            deleteBoundaries[state.recordKey].orEmpty(),
                        )
                        if (localSeq != null) {
                            DriveVocabularySync.mergeBoundaries(covered, mapOf(localDeviceId to localSeq))
                        } else {
                            covered
                        }.takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }
                    DriveWireRecord(
                        d = state.domain,
                        k = state.recordKey,
                        op = DriveRecordState.OP_DEL,
                        hlc = state.lastHlc,
                        fp = state.versionFp,
                        anc = anc,
                        resRefs = resRefs,
                        body = null,
                        observed = observed,
                    )
                }
                DriveRecordState.OP_RESOLVE -> {
                    val res = resolutionsByKey[Triple(state.domain, state.recordKey, state.versionFp)]
                    if (res != null) {
                        val resolvedFps = DriveWorkIdentity.decodeStringArray(res.resolvedFpsJson)
                            .distinct()
                            .sorted()
                        val body = if (res.outcome == DriveResolution.OUTCOME_PUT) {
                            state.bodyJson?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                        } else {
                            null
                        }
                        if (res.outcome == DriveResolution.OUTCOME_PUT && body == null) {
                            null
                        } else {
                            DriveWireRecord(
                                d = state.domain,
                                k = state.recordKey,
                                op = DriveRecordState.OP_RESOLVE,
                                hlc = res.hlc,
                                fp = state.versionFp,
                                anc = anc,
                                resRefs = (resRefs + state.versionFp).distinct().sorted(),
                                body = body,
                                outcome = res.outcome,
                                resolvedFps = resolvedFps,
                            )
                        }
                    } else {
                        // Defensive fallback if resolution row is absent.
                        val body = state.bodyJson?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                        DriveWireRecord(
                            d = state.domain,
                            k = state.recordKey,
                            op = if (body != null) DriveRecordState.OP_PUT else DriveRecordState.OP_DEL,
                            hlc = state.lastHlc,
                            fp = state.versionFp,
                            anc = anc,
                            resRefs = resRefs,
                            body = body,
                        )
                    }
                }
                else -> null
            }

            if (emitted != null) {
                records.add(emitted)
                nextAuthoredByDomain.getOrPut(state.domain) { linkedMapOf() }[state.recordKey] =
                    DriveAuthoredEntry(state.versionFp, localSeq, published?.record ?: emitted)
            }
        }
        val sortedRecords = records.sortedWith(
            compareBy(
                { DriveWireCodec.domainRank(it.d) },
                { it.d },
                { it.k },
            ),
        )
        return sortedRecords to nextAuthoredByDomain
    }

    private fun parseDeleteBoundary(bodyJson: String?): Map<String, Long> {
        val obj = bodyJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return buildMap {
            for (device in obj.keys()) {
                val seq = obj.optLong(device, 0L)
                if (seq > 0L) put(device, seq)
            }
        }
    }

    private fun childCoveredByDelete(
        domain: String,
        body: JSONObject,
        localDeviceId: String,
        localSeq: Long?,
        deleteBoundaries: Map<String, Map<String, Long>>,
    ): Boolean {
        val wordKey = when (domain) {
            DriveWireCodec.DOMAIN_VOCAB_REVIEW -> DriveWireCodec.decodeVocabReviewBody(body)?.let {
                DriveVocabularySync.wordKey(it.language, it.normalized)
            }
            DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE -> DriveWireCodec.decodeVocabOccurrenceBody(body)?.wordKey
            else -> null
        } ?: return false
        val boundary = deleteBoundaries[wordKey] ?: return false
        return DriveVocabularySync.boundaryCovers(boundary, localDeviceId, localSeq)
    }

    private fun collectAuthoredResolutions(
        localDeviceId: String,
        allResolutions: List<DriveResolution>,
    ): List<DriveSnapshotResolution> =
        allResolutions
            .filter { it.authorDeviceId == localDeviceId && it.domain in DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS }
            .sortedWith(
                compareBy(
                    { DriveWireCodec.domainRank(it.domain) },
                    { it.domain },
                    { it.recordKey },
                    { it.hlc },
                    { it.resolutionFp },
                ),
            )
            .mapNotNull { row ->
                val fps = DriveWorkIdentity.decodeStringArray(row.resolvedFpsJson).distinct().sorted()
                if (fps.isEmpty()) return@mapNotNull null
                DriveSnapshotResolution(
                    d = row.domain,
                    k = row.recordKey,
                    resolutionFp = row.resolutionFp,
                    resolvedFps = fps,
                    outcome = row.outcome,
                    hlc = row.hlc,
                )
            }

    internal data class EncodedSnapshotPart(
        val part: DriveSnapshotPart,
        val bytes: ByteArray,
        val sha256: String,
    )

    internal fun buildAndSplitSnapshotParts(
        deviceId: String,
        throughSeq: Long,
        createdHlc: String,
        records: List<DriveWireRecord>,
        resolutions: List<DriveSnapshotResolution>,
    ): List<EncodedSnapshotPart> {
        val baseEnvelopeBytes = DriveWireCodec.encodeSnapshotPart(
            DriveSnapshotPart(
                schemaVersion = DriveWireCodec.SCHEMA_VERSION,
                deviceId = deviceId,
                throughSeq = throughSeq,
                partIndex = 0,
                partCount = 999,
                createdHlc = createdHlc,
                domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
                records = emptyList(),
                resolutions = emptyList(),
            ),
        ).toByteArray(Charsets.UTF_8).size + 32

        data class PartBucket(
            val bucketResolutions: MutableList<DriveSnapshotResolution> = mutableListOf(),
            val bucketRecords: MutableList<DriveWireRecord> = mutableListOf(),
            var approxBytes: Int = baseEnvelopeBytes,
        ) {
            fun isEmpty(): Boolean = bucketResolutions.isEmpty() && bucketRecords.isEmpty()
            fun itemCount(): Int = bucketResolutions.size + bucketRecords.size
        }

        val buckets = mutableListOf<PartBucket>()
        var current = PartBucket()

        fun ensureCapacity(itemBytes: Int) {
            if (!current.isEmpty() && current.approxBytes + itemBytes > maxPartBytes) {
                buckets.add(current)
                current = PartBucket()
            }
        }

        for (res in resolutions) {
            val size = DriveCanonicalJson.encode(DriveWireCodec.snapshotResolutionToMap(res))
                .toByteArray(Charsets.UTF_8).size + 1
            ensureCapacity(size)
            current.bucketResolutions.add(res)
            current.approxBytes += size
        }

        for (rec in records) {
            val size = DriveCanonicalJson.encode(DriveWireCodec.wireRecordToMap(rec))
                .toByteArray(Charsets.UTF_8).size + 1
            ensureCapacity(size)
            current.bucketRecords.add(rec)
            current.approxBytes += size
        }

        if (!current.isEmpty() || buckets.isEmpty()) {
            buckets.add(current)
        }

        // Verify actual serialized UTF-8 byte sizes; split multi-item buckets that exceed
        // maxPartBytes and reject any indivisible single-item part exceeding maxPartBytes.
        var finalizedBuckets = buckets.toList()
        var needsReencode = true
        while (needsReencode) {
            needsReencode = false
            val nextBuckets = mutableListOf<PartBucket>()
            val count = finalizedBuckets.size
            for ((idx, bucket) in finalizedBuckets.withIndex()) {
                val probe = DriveSnapshotPart(
                    schemaVersion = DriveWireCodec.SCHEMA_VERSION,
                    deviceId = deviceId,
                    throughSeq = throughSeq,
                    partIndex = idx,
                    partCount = count,
                    createdHlc = createdHlc,
                    domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
                    records = bucket.bucketRecords,
                    resolutions = bucket.bucketResolutions,
                )
                val actualBytes = DriveWireCodec.encodeSnapshotPart(probe).toByteArray(Charsets.UTF_8).size
                if (actualBytes > maxPartBytes) {
                    if (bucket.itemCount() <= 1) {
                        val rec = bucket.bucketRecords.firstOrNull()
                        val res = bucket.bucketResolutions.firstOrNull()
                        throw DriveOversizedSnapshotPartException(
                            actualBytes = actualBytes,
                            maxPartBytes = maxPartBytes,
                            recordDomain = rec?.d ?: res?.d,
                            recordKey = rec?.k ?: res?.k,
                        )
                    }
                    needsReencode = true
                    if (bucket.bucketResolutions.isNotEmpty() && bucket.bucketRecords.isNotEmpty()) {
                        nextBuckets.add(PartBucket(bucketResolutions = bucket.bucketResolutions.toMutableList()))
                        nextBuckets.add(PartBucket(bucketRecords = bucket.bucketRecords.toMutableList()))
                    } else if (bucket.bucketResolutions.size > 1) {
                        val mid = bucket.bucketResolutions.size / 2
                        nextBuckets.add(PartBucket(bucketResolutions = bucket.bucketResolutions.subList(0, mid).toMutableList()))
                        nextBuckets.add(
                            PartBucket(
                                bucketResolutions = bucket.bucketResolutions
                                    .subList(mid, bucket.bucketResolutions.size)
                                    .toMutableList(),
                            ),
                        )
                    } else {
                        val mid = bucket.bucketRecords.size / 2
                        nextBuckets.add(PartBucket(bucketRecords = bucket.bucketRecords.subList(0, mid).toMutableList()))
                        nextBuckets.add(
                            PartBucket(
                                bucketRecords = bucket.bucketRecords
                                    .subList(mid, bucket.bucketRecords.size)
                                    .toMutableList(),
                            ),
                        )
                    }
                } else {
                    nextBuckets.add(bucket)
                }
            }
            finalizedBuckets = nextBuckets
        }

        val partCount = finalizedBuckets.size
        return finalizedBuckets.mapIndexed { idx, bucket ->
            val part = DriveSnapshotPart(
                schemaVersion = DriveWireCodec.SCHEMA_VERSION,
                deviceId = deviceId,
                throughSeq = throughSeq,
                partIndex = idx,
                partCount = partCount,
                createdHlc = createdHlc,
                domains = DriveWireCodec.SUPPORTED_DOMAIN_VERSIONS,
                records = bucket.bucketRecords.toList(),
                resolutions = bucket.bucketResolutions.toList(),
            )
            val bytes = DriveWireCodec.encodeSnapshotPart(part).toByteArray(Charsets.UTF_8)
            if (bytes.size > maxPartBytes) {
                val rec = bucket.bucketRecords.firstOrNull()
                val res = bucket.bucketResolutions.firstOrNull()
                throw DriveOversizedSnapshotPartException(
                    actualBytes = bytes.size,
                    maxPartBytes = maxPartBytes,
                    recordDomain = rec?.d ?: res?.d,
                    recordKey = rec?.k ?: res?.k,
                )
            }
            val sha256 = DriveCanonicalJson.sha256Hex(bytes)
            EncodedSnapshotPart(part = part, bytes = bytes, sha256 = sha256)
        }
    }

    private data class PruneOutcome(
        val state: DriveCompactionState,
        val prunedSnapshotParts: Int,
        val prunedDeltas: Int,
    )

    private suspend fun pruneSuperseded(
        permissionId: String,
        localDeviceId: String,
        accessToken: String,
        state: DriveCompactionState,
        gcOrphanSnapshots: Boolean = false,
        prefetchedOwnSnapshotFiles: List<DriveRemoteFile>? = null,
        protectedRemoteSnapshotIds: Set<String> = emptySet(),
    ): PruneOutcome {
        var prunedSnapshotParts = 0
        var prunedDeltas = 0
        var stateChanged = false

        val protectedIds = buildSet {
            state.snapshot?.parts?.forEach { add(it.fileId) }
            state.previousSnapshot?.parts?.forEach { add(it.fileId) }
            state.pendingSnapshot?.parts?.forEach { add(it.fileId) }
            addAll(protectedRemoteSnapshotIds)
        }

        // 1. Delete superseded older snapshot parts (S_{k-2} and any previously deferred part deletes).
        val remainingPendingSnapshotIds = mutableListOf<String>()
        var snapshotDeleteAborted = false
        for (fileId in state.pendingPruneFileIds) {
            if (fileId in protectedIds) {
                stateChanged = true
                continue
            }
            if (snapshotDeleteAborted) {
                remainingPendingSnapshotIds.add(fileId)
                continue
            }
            val deleted = deleteFileSafe(accessToken, fileId)
            if (deleted) {
                files.delete(permissionId, fileId)
                prunedSnapshotParts++
                stateChanged = true
            } else {
                remainingPendingSnapshotIds.add(fileId)
                snapshotDeleteAborted = true
            }
        }

        // 1b. Garbage-collect any abandoned/orphan snapshot part files belonging to localDeviceId
        //     in appDataFolder whose file IDs are NOT referenced by snapshot / previousSnapshot
        //     (or an active pending generation / remote manifest).
        var orphanListingFailed = false
        var orphanCleanupNeeded = state.orphanCleanupNeeded || gcOrphanSnapshots
        if (gcOrphanSnapshots && !snapshotDeleteAborted) {
            val ownSnapshotFiles = prefetchedOwnSnapshotFiles ?: runCatching {
                listOwnSnapshotFiles(localDeviceId, accessToken)
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                orphanListingFailed = true
                emptyList()
            }
            if (!orphanListingFailed) orphanCleanupNeeded = false
            val alreadyTracked = remainingPendingSnapshotIds.toSet()
            for (remote in ownSnapshotFiles) {
                if (remote.id in protectedIds || remote.id in alreadyTracked) continue
                val deleted = deleteFileSafe(accessToken, remote.id)
                if (deleted) {
                    files.delete(permissionId, remote.id)
                    prunedSnapshotParts++
                } else {
                    remainingPendingSnapshotIds.add(remote.id)
                    stateChanged = true
                    break
                }
            }
        }

        // 2. Delete superseded delta batches (`seq <= T_{k-1}`, lagging one snapshot generation).
        val pruneDeltasThroughSeq = state.previousSnapshot?.throughSeq ?: 0L
        var newPrunedThroughSeq = state.prunedThroughSeq
        if (pruneDeltasThroughSeq > 0L) {
            val outboxRows = outbox.ackedUpTo(permissionId, pruneDeltasThroughSeq)
            val indexedDeltaFiles = files.byDeviceAndKind(permissionId, localDeviceId, DriveFile.KIND_DELTA)
                .filter { !it.trashed && it.seq != null && it.seq <= pruneDeltasThroughSeq }

            val fileIdsBySeq = linkedMapOf<Long, MutableSet<String>>()
            for (row in outboxRows) {
                val set = fileIdsBySeq.getOrPut(row.seq) { linkedSetOf() }
                row.fileId?.takeIf { it.isNotBlank() }?.let(set::add)
            }
            for (df in indexedDeltaFiles) {
                val seq = df.seq ?: continue
                fileIdsBySeq.getOrPut(seq) { linkedSetOf() }.add(df.fileId)
            }

            var allDeleted = true
            for ((seq, fids) in fileIdsBySeq.toSortedMap()) {
                var seqAllDeleted = true
                for (fid in fids) {
                    val deleted = deleteFileSafe(accessToken, fid)
                    if (deleted) {
                        files.delete(permissionId, fid)
                        prunedDeltas++
                    } else {
                        seqAllDeleted = false
                        allDeleted = false
                        break
                    }
                }
                if (seqAllDeleted) {
                    outbox.deleteAckedSeq(permissionId, seq)
                } else {
                    break
                }
            }
            if (allDeleted && !orphanListingFailed && pruneDeltasThroughSeq > newPrunedThroughSeq) {
                newPrunedThroughSeq = pruneDeltasThroughSeq
                stateChanged = true
            }
        }

        val updatedState = state.copy(
            prunedThroughSeq = newPrunedThroughSeq,
            pendingPruneFileIds = remainingPendingSnapshotIds.distinct(),
            orphanCleanupNeeded = orphanCleanupNeeded,
        )
        if (stateChanged || orphanCleanupNeeded != state.orphanCleanupNeeded) {
            val existingRow = recordStates.get(permissionId, COMPACTION_STATE_DOMAIN, COMPACTION_STATE_KEY)
            persistCompactionState(
                permissionId = permissionId,
                state = updatedState,
                hlc = existingRow?.lastHlc.orEmpty(),
            )
        }
        return PruneOutcome(
            state = updatedState,
            prunedSnapshotParts = prunedSnapshotParts,
            prunedDeltas = prunedDeltas,
        )
    }

    private suspend fun deleteFileSafe(accessToken: String, fileId: String): Boolean = try {
        driveApi.deleteFile(accessToken, fileId)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: DriveNotFoundException) {
        true
    } catch (_: DriveAuthExpiredException) {
        false
    } catch (_: DriveApiException) {
        false
    } catch (_: IOException) {
        false
    }

    private suspend fun persistCompactionState(
        permissionId: String,
        state: DriveCompactionState,
        hlc: String,
    ) {
        val json = encodeCompactionState(state)
        val fp = DriveCanonicalJson.sha256Hex(json.toByteArray(Charsets.UTF_8))
        recordStates.upsert(
            DriveRecordState(
                permissionId = permissionId,
                domain = COMPACTION_STATE_DOMAIN,
                recordKey = COMPACTION_STATE_KEY,
                versionFp = fp,
                versionOp = DriveRecordState.OP_PUT,
                publishedFp = fp,
                contentFp = fp,
                lastSyncedRevision = state.prunedThroughSeq,
                ancestorsJson = "[]",
                resRefsJson = "[]",
                lastHlc = hlc,
                bodyJson = json,
            ),
        )
    }

    private fun encodeCompactionState(state: DriveCompactionState): String = DriveCanonicalJson.encode(
        mapOf(
            "snapshot" to state.snapshot?.let(DriveWireCodec::snapshotRefToMap),
            "previousSnapshot" to state.previousSnapshot?.let(DriveWireCodec::snapshotRefToMap),
            "prunedThroughSeq" to state.prunedThroughSeq,
            "pendingPruneFileIds" to state.pendingPruneFileIds.distinct().sorted(),
            "pendingSnapshot" to state.pendingSnapshot?.let { pending ->
                mapOf(
                    "throughSeq" to pending.throughSeq,
                    "createdHlc" to pending.createdHlc,
                    "parts" to pending.parts.map { part ->
                        mapOf(
                            "name" to part.name,
                            "fileId" to part.fileId,
                            "sha256" to part.sha256,
                            "records" to part.records,
                            "resolutions" to part.resolutions,
                        )
                    },
                )
            },
            "orphanCleanupNeeded" to state.orphanCleanupNeeded,
        ),
    )

    private fun decodeCompactionState(rawJson: String): DriveCompactionState {
        val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return DriveCompactionState()
        val snapshot = if (obj.isNull("snapshot")) null else obj.optJSONObject("snapshot")?.let(DriveWireCodec::parseSnapshotRef)
        val previousSnapshot = if (obj.isNull("previousSnapshot")) {
            null
        } else {
            obj.optJSONObject("previousSnapshot")?.let(DriveWireCodec::parseSnapshotRef)
        }
        val prunedThroughSeq = obj.optLong("prunedThroughSeq", 0L)
        val pendingArr = obj.optJSONArray("pendingPruneFileIds") ?: JSONArray()
        val pendingIds = buildList(pendingArr.length()) {
            for (i in 0 until pendingArr.length()) {
                val id = pendingArr.optString(i).trim()
                if (id.isNotEmpty()) add(id)
            }
        }
        val pendingSnapshot = if (obj.isNull("pendingSnapshot")) {
            null
        } else {
            obj.optJSONObject("pendingSnapshot")?.let { pendingObj ->
                val seq = pendingObj.optLong("throughSeq", 0L)
                val hlc = pendingObj.optString("createdHlc").trim()
                if (seq > 0L && hlc.isNotEmpty()) {
                    val partsArr = pendingObj.optJSONArray("parts") ?: JSONArray()
                    val parts = buildList(partsArr.length()) {
                        for (i in 0 until partsArr.length()) {
                            val p = partsArr.optJSONObject(i) ?: continue
                            val fileId = p.optString("fileId").trim()
                            if (fileId.isEmpty()) continue
                            add(
                                DriveSnapshotPartRef(
                                    name = p.optString("name"),
                                    fileId = fileId,
                                    sha256 = p.optString("sha256").trim(),
                                    records = p.optInt("records", 0),
                                    resolutions = p.optInt("resolutions", 0),
                                ),
                            )
                        }
                    }
                    DrivePendingSnapshot(throughSeq = seq, createdHlc = hlc, parts = parts)
                } else {
                    null
                }
            }
        }
        return DriveCompactionState(
            snapshot = snapshot,
            previousSnapshot = previousSnapshot,
            prunedThroughSeq = prunedThroughSeq,
            pendingPruneFileIds = pendingIds,
            pendingSnapshot = pendingSnapshot,
            orphanCleanupNeeded = obj.optBoolean("orphanCleanupNeeded", false),
        )
    }

    private fun parseVocabDeleteObserved(bodyJson: String?): Map<String, Long>? {
        if (bodyJson.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(bodyJson) }.getOrNull() ?: return null
        val observedObj = obj.optJSONObject("observed") ?: return null
        return buildMap {
            val keys = observedObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!observedObj.isNull(k)) {
                    put(k, observedObj.optLong(k, 0L))
                }
            }
        }
    }

    companion object {
        /** Domains whose snapshot records keep their publication seq for delete observed vectors. */
        internal val SEQ_CARRYING_DOMAINS = setOf(
            DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE,
            DriveWireCodec.DOMAIN_VOCAB_REVIEW,
        )
        const val DEFAULT_DELTA_COUNT_THRESHOLD = 20
        const val DEFAULT_DELTA_BYTES_THRESHOLD = 2L * 1024L * 1024L
        const val MIN_PART_BYTES = 512

        internal const val COMPACTION_STATE_DOMAIN = "_compaction_state"
        internal const val AUTHORED_DOMAIN_PREFIX = "_authored:"
        internal const val COMPACTION_STATE_KEY = "self"
    }
}
