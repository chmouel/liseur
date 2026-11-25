package com.chmouel.liseur.drive

import org.json.JSONObject

/** Snapshot of the current local state for one mutable record `(domain, recordKey)`. */
internal data class LocalRecordView(
    val versionFp: String?,
    val versionOp: String = DriveRecordState.OP_PUT,
    val publishedFp: String? = null,
    val contentFp: String? = null,
    val isLivePut: Boolean = versionFp != null &&
        (versionOp == DriveRecordState.OP_PUT || (versionOp == DriveRecordState.OP_RESOLVE && contentFp != null)),
    val lastSyncedRevision: Long? = null,
    val originDeviceId: String? = null,
    val originSeq: Long? = null,
    val ancestors: List<String> = emptyList(),
    val resRefs: List<String> = emptyList(),
    val lastHlc: String = "",
    val bodyJson: String? = null,
    val localRowMatchesContentFp: Boolean = true,
    /** True when `liseur.db` already holds the atomic per-peer ack for this incoming record after a crash. */
    val postCrashAckMatchesIncoming: Boolean = false,
    /** True when the reader edited `liseur.db` again after Tx 1 committed `R` and before Tx 2 ran. */
    val hasPostCrashLocalEdit: Boolean = false,
)

/** One conflict settlement produced by Step 0 or competing-resolution arbitration. */
internal data class ConflictSettlement(
    val conflictId: String,
    val choice: String,
    val resolutionFp: String,
    val resolvedAt: Long,
    val coveredLocalFp: String,
)

/** Decision for what to write to `liseur.db` during reconciliation. */
internal sealed interface ReconciliationDataAction {
    /** Apply `R.body` (`op: "put"` or `op: "resolve", outcome: "put"`) to `liseur.db`. */
    data class ApplyPut(val body: JSONObject, val contentFp: String) : ReconciliationDataAction

    /** Apply deletion (`op: "del"` or `op: "resolve", outcome: "delete"`) to `liseur.db`. */
    data object ApplyDelete : ReconciliationDataAction

    /** Keep `liseur.db` untouched (Agreement, Stale, Local-wins competing resolution, or Conflict). */
    data object NoDataChange : ReconciliationDataAction
}

/** High-level classification of how an incoming mutable record was reconciled. */
internal enum class ReconciliationCase {
    UNSEEN,
    AGREEMENT,
    RESOLVE_APPLIED,
    RESOLVE_LOCAL_WON,
    RESOLVE_STALE,
    CAUSAL_SUPERSESSION,
    RESOLUTION_BRIDGED_SUPERSESSION,
    STALE,
    POST_CRASH_LOCAL_EDIT_PRESERVED,
    CONFLICT_EDIT_EDIT,
    CONFLICT_DELETE_EDIT,
}

/**
 * Complete result of running Step 0 + Cases 0–5 on one incoming mutable record.
 *
 * Every Step 0 side effect ([resolutionsToPersist], [conflictsToSettle]) MUST be
 * committed in Transaction 2 (`drive_sync.db`) BEFORE `drive_cursor` advances,
 * regardless of [reconciliationCase].
 */
internal data class ReconciliationResult(
    val reconciliationCase: ReconciliationCase,
    val dataAction: ReconciliationDataAction,
    val updatedRecordState: DriveRecordState?,
    val resolutionsToPersist: List<DriveResolution>,
    val conflictsToSettle: List<ConflictSettlement>,
    val conflictToOpen: DriveConflict? = null,
)

/** Reader choice when resolving a conflict from the Play Settings UI. */
internal enum class DriveConflictChoice {
    KEEP_LOCAL,
    KEEP_REMOTE,
    KEEP_EDITED,
    DELETE,
}

/**
 * Causal reconciliation and conflict-resolution engine for mutable Drive domains
 * (`annotation`, `evaluation`, `metadata`, `series`) per ADR-0078 & `docs/DRIVE_SYNC.md` §3.1–3.2.
 */
internal object DriveConflictEngine {

    /**
     * Reconciles incoming mutable record [incoming] against [local], [existingResolutions],
     * and [unsettledConflicts], running mandatory Step 0 BEFORE Cases 0–5.
     */
    fun reconcileIncoming(
        permissionId: String,
        domain: String,
        recordKey: String,
        bookUrl: String?,
        local: LocalRecordView,
        existingResolutions: List<DriveResolution>,
        unsettledConflicts: List<DriveConflict>,
        incoming: DriveWireRecord,
        incomingContentFp: String?,
        remoteDeviceId: String,
        remoteSeq: Long,
        now: Long,
    ): ReconciliationResult {
        val isIncomingResolve = incoming.op == DriveRecordState.OP_RESOLVE
        val isIncomingDelete = incoming.op == DriveRecordState.OP_DEL ||
            (isIncomingResolve && incoming.outcome == DriveResolution.OUTCOME_DELETE)
        val isIncomingLivePut = incoming.op == DriveRecordState.OP_PUT ||
            (isIncomingResolve && incoming.outcome == DriveResolution.OUTCOME_PUT)

        // --------------------------------------------------------------------
        // STEP 0 — Mandatory resolution & `resRefs`-bridge settlement pre-step
        // Runs BEFORE Cases 0–5 (including Agreement & Stale) and BEFORE cursor advance.
        // --------------------------------------------------------------------
        val resolutionsToPersist = mutableListOf<DriveResolution>()
        val allResolutions = existingResolutions.toMutableList()
        val settlementsByConflictId = linkedMapOf<String, ConflictSettlement>()
        val step0ResRefsToAdd = linkedSetOf<String>()

        var incomingResolution: DriveResolution? = null
        if (isIncomingResolve &&
            (incoming.outcome == DriveResolution.OUTCOME_PUT || incoming.outcome == DriveResolution.OUTCOME_DELETE) &&
            incoming.resolvedFps.isNotEmpty()
        ) {
            val sortedResolved = incoming.resolvedFps.distinct().sorted()
            val res = DriveResolution(
                permissionId = permissionId,
                domain = domain,
                recordKey = recordKey,
                resolutionFp = incoming.fp,
                resolvedFpsJson = DriveCanonicalJson.encode(sortedResolved),
                outcome = incoming.outcome,
                hlc = incoming.hlc,
                authorDeviceId = remoteDeviceId,
                createdAt = now,
            )
            incomingResolution = res
            resolutionsToPersist.add(res)
            if (allResolutions.none { it.resolutionFp == res.resolutionFp }) {
                allResolutions.add(res)
            }
            val resolvedSet = sortedResolved.toSet()
            val winnerForIncoming = winningResolutionFor(allResolutions, resolvedSet)
            step0ResRefsToAdd.addAll(incoming.resRefs)
            if (winnerForIncoming == null || winnerForIncoming.resolutionFp == res.resolutionFp) {
                step0ResRefsToAdd.add(res.resolutionFp)
            }
            for (conflict in unsettledConflicts) {
                val conflictFps = setOf(conflict.localFp, conflict.remoteFp)
                if (conflictFps.all { it in resolvedSet }) {
                    val candidates = allResolutions.filter { candidate ->
                        val covered = decodeFps(candidate.resolvedFpsJson)
                        conflictFps.all { it in covered }
                    }
                    val winner = winningResolutionFor(candidates, conflictFps) ?: res
                    settlementsByConflictId[conflict.conflictId] = ConflictSettlement(
                        conflictId = conflict.conflictId,
                        choice = winner.outcome,
                        resolutionFp = winner.resolutionFp,
                        resolvedAt = now,
                        coveredLocalFp = conflict.localFp,
                    )
                }
            }
        }

        if (incoming.resRefs.isNotEmpty()) {
            val incomingResRefSet = incoming.resRefs.toSet()
            for (res in allResolutions) {
                if (res.resolutionFp !in incomingResRefSet) continue
                val resFps = decodeFps(res.resolvedFpsJson)
                val winner = winningResolutionFor(allResolutions, resFps)
                if (winner?.resolutionFp != res.resolutionFp) continue
                step0ResRefsToAdd.add(res.resolutionFp)
                for (conflict in unsettledConflicts) {
                    val conflictFps = setOf(conflict.localFp, conflict.remoteFp)
                    if (conflictFps.all { it in resFps }) {
                        settlementsByConflictId[conflict.conflictId] = ConflictSettlement(
                            conflictId = conflict.conflictId,
                            choice = res.outcome,
                            resolutionFp = res.resolutionFp,
                            resolvedAt = now,
                            coveredLocalFp = conflict.localFp,
                        )
                    }
                }
            }
        }

        val settlements = settlementsByConflictId.values.toList()
        val remainingOpenConflicts = unsettledConflicts.filter { it.conflictId !in settlementsByConflictId }
        val lFp = local.versionFp
        val incomingRetiredByResolution = allResolutions.any { incoming.fp in decodeFps(it.resolvedFpsJson) }
        val incomingKnownAncestorOrRetired = incoming.fp in local.ancestors || incoming.fp in local.resRefs || incomingRetiredByResolution

        fun applyActionForIncoming(): ReconciliationDataAction = when {
            isIncomingDelete -> ReconciliationDataAction.ApplyDelete
            isIncomingLivePut && incoming.body != null && incomingContentFp != null ->
                ReconciliationDataAction.ApplyPut(incoming.body, incomingContentFp)
            else -> ReconciliationDataAction.NoDataChange
        }

        fun stateApplyingIncoming(
            newOp: String = incoming.op,
            ancList: List<String>,
            resRefsList: Collection<String>,
            keepRevision: Long? = local.lastSyncedRevision,
        ): DriveRecordState {
            val bodyJson = if (isIncomingDelete) null else incoming.body?.let(DriveCanonicalJson::encode)
            val cfp = if (isIncomingDelete) null else incomingContentFp
            return DriveRecordState(
                permissionId = permissionId,
                domain = domain,
                recordKey = recordKey,
                versionFp = incoming.fp,
                versionOp = newOp,
                publishedFp = incoming.fp,
                contentFp = cfp,
                lastSyncedRevision = keepRevision,
                originDeviceId = remoteDeviceId,
                originSeq = remoteSeq,
                ancestorsJson = DriveCanonicalJson.encode(ancList.distinct().filter { it != incoming.fp }.take(DriveWireCodec.MAX_ANCESTORS)),
                resRefsJson = DriveCanonicalJson.encode(resRefsList.distinct().sorted()),
                lastHlc = maxOf(local.lastHlc, incoming.hlc),
                bodyJson = bodyJson,
            )
        }

        fun stateKeepingLocal(
            extraAnc: Collection<String> = emptyList(),
            extraResRefs: Collection<String> = emptyList(),
            // Step 0 refs join L only when L is proven to descend from R (DRIVE_SYNC.md §3.1).
            includeCandidateRefs: Boolean = true,
            overridePublishedFp: String? = local.publishedFp,
            overrideVersionFp: String = requireNotNull(lFp),
            overrideVersionOp: String = local.versionOp,
            overrideLastHlc: String = maxOf(local.lastHlc, incoming.hlc),
            overrideContentFp: String? = local.contentFp,
            overrideBodyJson: String? = local.bodyJson,
            overrideOriginDeviceId: String? = local.originDeviceId,
            overrideOriginSeq: Long? = local.originSeq,
        ): DriveRecordState {
            val mergedAnc = (extraAnc + local.ancestors)
                .distinct()
                .filter { it != overrideVersionFp }
                .take(DriveWireCodec.MAX_ANCESTORS)
            val candidateRefs = if (includeCandidateRefs) step0ResRefsToAdd else emptySet()
            val mergedResRefs = (local.resRefs + candidateRefs + extraResRefs).distinct().sorted()
            return DriveRecordState(
                permissionId = permissionId,
                domain = domain,
                recordKey = recordKey,
                versionFp = overrideVersionFp,
                versionOp = overrideVersionOp,
                publishedFp = overridePublishedFp,
                contentFp = overrideContentFp,
                lastSyncedRevision = local.lastSyncedRevision,
                originDeviceId = overrideOriginDeviceId,
                originSeq = overrideOriginSeq,
                ancestorsJson = DriveCanonicalJson.encode(mergedAnc),
                resRefsJson = DriveCanonicalJson.encode(mergedResRefs),
                lastHlc = overrideLastHlc,
                bodyJson = overrideBodyJson,
            )
        }

        // --------------------------------------------------------------------
        // Post-crash replay where the user already made a newer local edit
        // after Tx 1 committed R and before Tx 2 committed drive_sync.db.
        // Step 0 already persisted drive_resolution & settled covered conflicts.
        // --------------------------------------------------------------------
        if (lFp != null &&
            local.postCrashAckMatchesIncoming &&
            local.hasPostCrashLocalEdit &&
            !incomingRetiredByResolution
        ) {
            val extraResRefs = if (isIncomingResolve) {
                incoming.resRefs + incoming.fp
            } else {
                incoming.resRefs
            }
            return ReconciliationResult(
                reconciliationCase = ReconciliationCase.POST_CRASH_LOCAL_EDIT_PRESERVED,
                dataAction = ReconciliationDataAction.NoDataChange,
                updatedRecordState = stateKeepingLocal(
                    extraAnc = listOf(incoming.fp) + incoming.anc,
                    extraResRefs = extraResRefs,
                    overridePublishedFp = incoming.fp,
                ),
                resolutionsToPersist = resolutionsToPersist,
                conflictsToSettle = settlements,
            )
        }

        // --------------------------------------------------------------------
        // CASE 0 — Unseen (`L == none`)
        // --------------------------------------------------------------------
        if (lFp == null) {
            val initialResRefs = buildSet {
                addAll(incoming.resRefs)
                addAll(step0ResRefsToAdd)
                if (isIncomingResolve) add(incoming.fp)
            }
            return ReconciliationResult(
                reconciliationCase = ReconciliationCase.UNSEEN,
                dataAction = applyActionForIncoming(),
                updatedRecordState = stateApplyingIncoming(
                    ancList = incoming.anc,
                    resRefsList = initialResRefs,
                ),
                resolutionsToPersist = resolutionsToPersist,
                conflictsToSettle = settlements,
            )
        }

        // A successor that carried this resolution in its lineage arrived first and is parked in an
        // open conflict; it outranks R even when R's outcome equals what this device already holds.
        val incomingWinsItsFps = incomingResolution != null &&
            winningResolutionFor(allResolutions, decodeFps(incomingResolution.resolvedFpsJson))
                ?.resolutionFp == incoming.fp
        val successors = if (isIncomingResolve && incomingWinsItsFps) {
            remainingOpenConflicts.filter { incoming.fp in remoteLineage(it) }
        } else {
            emptyList()
        }

        // --------------------------------------------------------------------
        // CASE 1 — Agreement
        // --------------------------------------------------------------------
        val incomingDescendsFromLocal = lFp in incoming.anc || lFp in incoming.resolvedFps ||
            (local.versionOp == DriveRecordState.OP_RESOLVE && lFp in incoming.resRefs)
        val bothDeletions = !local.isLivePut && isIncomingDelete && !incomingKnownAncestorOrRetired && !incomingDescendsFromLocal
        val sameContent = local.versionOp == DriveRecordState.OP_PUT && incoming.op == DriveRecordState.OP_PUT &&
            local.contentFp != null && local.contentFp == incomingContentFp && local.localRowMatchesContentFp &&
            !local.hasPostCrashLocalEdit && !incomingKnownAncestorOrRetired && !incomingDescendsFromLocal
        val postCrashReplayAgreement = local.postCrashAckMatchesIncoming &&
            !local.hasPostCrashLocalEdit &&
            !incomingKnownAncestorOrRetired
        if (incoming.fp == lFp || ((bothDeletions || postCrashReplayAgreement || sameContent) && successors.isEmpty())) {
            val mergedResRefs = buildSet {
                addAll(local.resRefs)
                addAll(incoming.resRefs)
                addAll(step0ResRefsToAdd)
            }
            val (chosenFp, chosenOp, chosenHlc) = when {
                sameContent -> if (compareHlcFp(local.lastHlc, lFp, incoming.hlc, incoming.fp) >= 0) {
                    Triple(lFp, local.versionOp, local.lastHlc)
                } else {
                    Triple(incoming.fp, incoming.op, incoming.hlc)
                }
                isIncomingResolve && bothDeletions && local.versionOp == DriveRecordState.OP_RESOLVE -> {
                    val localResolutionHlc = allResolutions.find { it.resolutionFp == lFp }?.hlc ?: local.lastHlc
                    val localWins = compareHlcFp(localResolutionHlc, lFp, incoming.hlc, incoming.fp) >= 0
                    if (localWins) {
                        Triple(lFp, local.versionOp, local.lastHlc)
                    } else {
                        Triple(incoming.fp, DriveRecordState.OP_RESOLVE, maxOf(local.lastHlc, incoming.hlc))
                    }
                }
                isIncomingResolve -> {
                    Triple(incoming.fp, DriveRecordState.OP_RESOLVE, maxOf(local.lastHlc, incoming.hlc))
                }
                bothDeletions -> {
                    if (local.versionOp == DriveRecordState.OP_RESOLVE) {
                        Triple(lFp, local.versionOp, maxOf(local.lastHlc, incoming.hlc))
                    } else if (incoming.fp > lFp) {
                        Triple(incoming.fp, incoming.op, maxOf(local.lastHlc, incoming.hlc))
                    } else {
                        Triple(lFp, local.versionOp, maxOf(local.lastHlc, incoming.hlc))
                    }
                }
                else -> Triple(incoming.fp, incoming.op, maxOf(local.lastHlc, incoming.hlc))
            }
            val finalResRefs = if (chosenOp == DriveRecordState.OP_RESOLVE) {
                mergedResRefs + chosenFp
            } else {
                mergedResRefs
            }
            val extraAnc = buildList {
                if (lFp != chosenFp) add(lFp)
                if (incoming.fp != chosenFp) add(incoming.fp)
                addAll(incoming.anc)
            }
            val agreedContentFp = when {
                isIncomingDelete || bothDeletions -> null
                postCrashReplayAgreement -> incomingContentFp ?: local.contentFp
                else -> local.contentFp
            }
            val agreedBodyJson = when {
                isIncomingDelete || bothDeletions -> null
                postCrashReplayAgreement -> incoming.body?.let(DriveCanonicalJson::encode) ?: local.bodyJson
                else -> local.bodyJson
            }
            return ReconciliationResult(
                reconciliationCase = ReconciliationCase.AGREEMENT,
                dataAction = ReconciliationDataAction.NoDataChange,
                updatedRecordState = stateKeepingLocal(
                    extraAnc = extraAnc,
                    extraResRefs = finalResRefs,
                    overridePublishedFp = if (chosenFp == incoming.fp) incoming.fp else local.publishedFp,
                    overrideVersionFp = chosenFp,
                    overrideVersionOp = chosenOp,
                    overrideLastHlc = chosenHlc,
                    overrideContentFp = agreedContentFp,
                    overrideBodyJson = agreedBodyJson,
                    overrideOriginDeviceId = if (chosenFp == incoming.fp && lFp != chosenFp) remoteDeviceId else local.originDeviceId,
                    overrideOriginSeq = if (chosenFp == incoming.fp && lFp != chosenFp) remoteSeq else local.originSeq,
                ),
                resolutionsToPersist = resolutionsToPersist,
                conflictsToSettle = settlements,
            )
        }

        // Winning resolution that the incoming `resRefs` prove it descends from and that retired L.
        val incomingResRefSet = incoming.resRefs.toSet()
        val bridgingResolution = if (incomingResRefSet.isNotEmpty()) {
            allResolutions.firstOrNull { res ->
                if (res.resolutionFp !in incomingResRefSet) return@firstOrNull false
                val resFps = decodeFps(res.resolvedFpsJson)
                if (lFp !in resFps && lFp != res.resolutionFp) return@firstOrNull false
                winningResolutionFor(allResolutions, resFps)?.resolutionFp == res.resolutionFp
            }
        } else {
            null
        }

        // --------------------------------------------------------------------
        // CASE 2 — Incoming `op: "resolve"`
        // --------------------------------------------------------------------
        if (isIncomingResolve) {
            // 2a. Already superseded in local ancestry -> Stale (Step 0 side effects still persist).
            val knownViaResRefs = incoming.fp in local.resRefs
            if ((incomingKnownAncestorOrRetired || knownViaResRefs) && successors.isEmpty()) {
                val provenDescent = incoming.fp in local.ancestors || knownViaResRefs
                return ReconciliationResult(
                    reconciliationCase = ReconciliationCase.RESOLVE_STALE,
                    dataAction = ReconciliationDataAction.NoDataChange,
                    updatedRecordState = stateKeepingLocal(
                        extraResRefs = if (provenDescent) incoming.resRefs else emptyList(),
                        includeCandidateRefs = provenDescent,
                    ),
                    resolutionsToPersist = resolutionsToPersist,
                    conflictsToSettle = settlements,
                )
            }

            val incomingResolvedSet = incoming.resolvedFps.toSet()

            // 2-pre. A successor that carried this resolution in its lineage arrived first and is
            // parked in an open conflict. The successor descends from R, so R's own outcome is older.
            if (successors.isNotEmpty()) {
                val tips = successors
                    .filter { c -> successors.none { o ->
                        o.remoteFp != c.remoteFp && (c.remoteFp in remoteLineage(o) ||
                            (o.remoteDeviceId == c.remoteDeviceId && o.remoteSeq != null && c.remoteSeq != null && o.remoteSeq > c.remoteSeq))
                    } }
                    .distinctBy { it.remoteFp }
                val tip = tips.singleOrNull()
                val resolutionCoversLocal = lFp in incomingResolvedSet || lFp in incoming.anc || bridgingResolution != null
                val settledBySuccessor = if (tip != null && (lFp == tip.remoteFp || resolutionCoversLocal) && local.localRowMatchesContentFp) {
                    successors.map { c ->
                        ConflictSettlement(
                            conflictId = c.conflictId,
                            choice = if (tip.remoteJson != null) DriveResolution.OUTCOME_PUT else DriveResolution.OUTCOME_DELETE,
                            resolutionFp = incoming.fp,
                            resolvedAt = now,
                            coveredLocalFp = c.localFp,
                        )
                    }
                } else {
                    emptyList()
                }
                if (tip != null && settledBySuccessor.isNotEmpty()) {
                    val allSettlements = (settlements + settledBySuccessor).associateBy { it.conflictId }.values.toList()
                    val refs = local.resRefs + incoming.resRefs + step0ResRefsToAdd + incoming.fp + decodeList(tip.remoteResRefsJson)
                    if (lFp == tip.remoteFp) {
                        return ReconciliationResult(
                            reconciliationCase = ReconciliationCase.RESOLVE_STALE,
                            dataAction = ReconciliationDataAction.NoDataChange,
                            updatedRecordState = stateKeepingLocal(extraResRefs = refs),
                            resolutionsToPersist = resolutionsToPersist,
                            conflictsToSettle = allSettlements,
                        )
                    }
                    val tipBody = tip.remoteJson?.let(::JSONObject)
                    val tipContentFp = tipBody?.let(DriveCanonicalJson::contentFp)
                    return ReconciliationResult(
                        reconciliationCase = ReconciliationCase.RESOLUTION_BRIDGED_SUPERSESSION,
                        dataAction = if (tipBody != null && tipContentFp != null) {
                            ReconciliationDataAction.ApplyPut(tipBody, tipContentFp)
                        } else {
                            ReconciliationDataAction.ApplyDelete
                        },
                        updatedRecordState = DriveRecordState(
                            permissionId = permissionId,
                            domain = domain,
                            recordKey = recordKey,
                            versionFp = tip.remoteFp,
                            versionOp = tip.remoteOp ?: if (tipBody != null) DriveRecordState.OP_PUT else DriveRecordState.OP_DEL,
                            publishedFp = tip.remoteFp,
                            contentFp = tipContentFp,
                            lastSyncedRevision = local.lastSyncedRevision,
                            originDeviceId = tip.remoteDeviceId,
                            originSeq = tip.remoteSeq,
                            ancestorsJson = DriveCanonicalJson.encode(
                                (listOf(lFp) + decodeList(tip.remoteAncJson) + incoming.fp + local.ancestors)
                                    .distinct()
                                    .filter { it != tip.remoteFp }
                                    .take(DriveWireCodec.MAX_ANCESTORS),
                            ),
                            resRefsJson = DriveCanonicalJson.encode(refs.distinct().sorted()),
                            lastHlc = tip.remoteHlc,
                            bodyJson = tipBody?.let(DriveCanonicalJson::encode),
                        ),
                        resolutionsToPersist = resolutionsToPersist,
                        conflictsToSettle = allSettlements,
                    )
                }
                // Local moved on (or is dirty): never apply R's older outcome over it.
                val rebased = tip?.takeIf {
                    local.localRowMatchesContentFp && lFp != it.remoteFp && lFp !in incomingResolvedSet &&
                        remainingOpenConflicts.none { c -> c.localFp == lFp && c.remoteFp == it.remoteFp }
                }
                if (rebased != null) {
                    return openConflictResult(
                        permissionId = permissionId,
                        domain = domain,
                        recordKey = recordKey,
                        bookUrl = bookUrl,
                        localFp = lFp,
                        localIsLivePut = local.isLivePut,
                        incoming = rebased.toWireRecord(),
                        incomingIsLivePut = rebased.remoteJson != null,
                        remoteDeviceId = rebased.remoteDeviceId,
                        remoteSeq = rebased.remoteSeq ?: remoteSeq,
                        now = now,
                        keptLocalState = stateKeepingLocal(includeCandidateRefs = false),
                        resolutionsToPersist = resolutionsToPersist,
                        settlements = settlements,
                    )
                }
                return ReconciliationResult(
                    reconciliationCase = ReconciliationCase.RESOLVE_STALE,
                    dataAction = ReconciliationDataAction.NoDataChange,
                    updatedRecordState = stateKeepingLocal(includeCandidateRefs = false),
                    resolutionsToPersist = resolutionsToPersist,
                    conflictsToSettle = settlements,
                )
            }

            // 2b. Competing offline resolutions: STRICTLY restricted to when BOTH
            // current local L (`version_op == "resolve"`) AND incoming R (`op == "resolve"`)
            // are competing resolutions with equal `resolvedFps` sets and neither in the other's `anc`.
            // Subsequent local edits (`version_op != "resolve"`) MUST NOT enter this branch.
            if (local.versionOp == DriveRecordState.OP_RESOLVE && local.localRowMatchesContentFp) {
                val localRes = allResolutions.find { it.resolutionFp == lFp }
                if (localRes != null) {
                    val localResolvedSet = decodeFps(localRes.resolvedFpsJson)
                    val sameResolvedSet = localResolvedSet == incomingResolvedSet

                    if (sameResolvedSet && lFp !in incoming.anc && incoming.fp !in local.ancestors) {
                        val incomingWins = compareHlcFp(
                            incoming.hlc,
                            incoming.fp,
                            localRes.hlc,
                            localRes.resolutionFp,
                        ) > 0
                        return if (incomingWins) {
                            val mergedResRefs = local.resRefs + incoming.resRefs + step0ResRefsToAdd + incoming.fp
                            val updatedSettlements = settlements.map { s ->
                                if (s.resolutionFp == localRes.resolutionFp) {
                                    s.copy(
                                        choice = incoming.outcome ?: s.choice,
                                        resolutionFp = incoming.fp,
                                    )
                                } else {
                                    s
                                }
                            }
                            ReconciliationResult(
                                reconciliationCase = ReconciliationCase.RESOLVE_APPLIED,
                                dataAction = applyActionForIncoming(),
                                updatedRecordState = stateApplyingIncoming(
                                    newOp = DriveRecordState.OP_RESOLVE,
                                    ancList = listOf(lFp) + incoming.anc + local.ancestors,
                                    resRefsList = mergedResRefs,
                                ),
                                resolutionsToPersist = resolutionsToPersist,
                                conflictsToSettle = updatedSettlements,
                            )
                        } else {
                            ReconciliationResult(
                                reconciliationCase = ReconciliationCase.RESOLVE_LOCAL_WON,
                                dataAction = ReconciliationDataAction.NoDataChange,
                                updatedRecordState = stateKeepingLocal(
                                    extraAnc = listOf(incoming.fp) + incoming.anc,
                                    extraResRefs = incoming.resRefs,
                                ),
                                resolutionsToPersist = resolutionsToPersist,
                                conflictsToSettle = settlements,
                            )
                        }
                    }
                }
            }

            // 2c. Covered local version (`L in R.resolvedFps` or an open conflict settled by Step 0 covered L)
            // provided local has not moved on to a newer edit outside `R.resolvedFps`.
            val settledConflictCoveredL = settlements.any { it.coveredLocalFp == lFp }
            if ((lFp in incomingResolvedSet || settledConflictCoveredL) && local.localRowMatchesContentFp) {
                val mergedResRefs = local.resRefs + incoming.resRefs + step0ResRefsToAdd + incoming.fp
                return ReconciliationResult(
                    reconciliationCase = ReconciliationCase.RESOLVE_APPLIED,
                    dataAction = applyActionForIncoming(),
                    updatedRecordState = stateApplyingIncoming(
                        newOp = DriveRecordState.OP_RESOLVE,
                        ancList = listOf(lFp) + incoming.anc + local.ancestors,
                        resRefsList = mergedResRefs,
                    ),
                    resolutionsToPersist = resolutionsToPersist,
                    conflictsToSettle = settlements,
                )
            }

            // 2d. Ordinary causal supersession (`L in R.anc`) with no uncovered open conflict.
            if (lFp in incoming.anc && remainingOpenConflicts.isEmpty() && local.localRowMatchesContentFp) {
                val mergedResRefs = local.resRefs + incoming.resRefs + step0ResRefsToAdd + incoming.fp
                return ReconciliationResult(
                    reconciliationCase = ReconciliationCase.RESOLVE_APPLIED,
                    dataAction = applyActionForIncoming(),
                    updatedRecordState = stateApplyingIncoming(
                        newOp = DriveRecordState.OP_RESOLVE,
                        ancList = listOf(lFp) + incoming.anc + local.ancestors,
                        resRefsList = mergedResRefs,
                    ),
                    resolutionsToPersist = resolutionsToPersist,
                    conflictsToSettle = settlements,
                )
            }

            // 2d'. Proven resolution descent: R's resRefs carry the winning resolution that retired L.
            if (bridgingResolution != null && local.localRowMatchesContentFp) {
                val mergedResRefs = local.resRefs + incoming.resRefs + step0ResRefsToAdd +
                    bridgingResolution.resolutionFp + incoming.fp
                return ReconciliationResult(
                    reconciliationCase = ReconciliationCase.RESOLVE_APPLIED,
                    dataAction = applyActionForIncoming(),
                    updatedRecordState = stateApplyingIncoming(
                        newOp = DriveRecordState.OP_RESOLVE,
                        ancList = listOf(lFp) + incoming.anc + local.ancestors,
                        resRefsList = mergedResRefs,
                    ),
                    resolutionsToPersist = resolutionsToPersist,
                    conflictsToSettle = settlements,
                )
            }

            // 2e. Otherwise: local has an independent or subsequent edit (e.g., `fNew`) not covered
            // by `R.resolvedFps` -> open conflict preserving both.
            return openConflictResult(
                permissionId = permissionId,
                domain = domain,
                recordKey = recordKey,
                bookUrl = bookUrl,
                localFp = lFp,
                localIsLivePut = local.isLivePut,
                incoming = incoming,
                incomingIsLivePut = isIncomingLivePut,
                remoteDeviceId = remoteDeviceId,
                remoteSeq = remoteSeq,
                now = now,
                keptLocalState = stateKeepingLocal(includeCandidateRefs = false),
                resolutionsToPersist = resolutionsToPersist,
                settlements = settlements,
            )
        }

        // --------------------------------------------------------------------
        // CASE 3 — Causal supersession (fast-forward silently)
        // --------------------------------------------------------------------
        val localRetiredByResolution = allResolutions.any { lFp in decodeFps(it.resolvedFpsJson) }

        // Branch A: Ordinary causal supersession (strictly requires no open conflict & L not retired).
        val ordinarySupersession = remainingOpenConflicts.isEmpty() &&
            !localRetiredByResolution &&
            local.localRowMatchesContentFp &&
            (
                lFp in incoming.anc ||
                    (local.originDeviceId == remoteDeviceId && (local.originSeq ?: -1L) < remoteSeq)
                )

        if (ordinarySupersession) {
            val mergedResRefs = local.resRefs + incoming.resRefs + step0ResRefsToAdd
            return ReconciliationResult(
                reconciliationCase = ReconciliationCase.CAUSAL_SUPERSESSION,
                dataAction = applyActionForIncoming(),
                updatedRecordState = stateApplyingIncoming(
                    ancList = listOf(lFp) + incoming.anc + local.ancestors,
                    resRefsList = mergedResRefs,
                ),
                resolutionsToPersist = resolutionsToPersist,
                conflictsToSettle = settlements,
            )
        }

        // Branch B: Resolution-bridged supersession (requires winning res with L in res.resolvedFps
        // AND explicit causal proof `res.resolutionFp in R.resRefs` — NEVER `R.hlc >= res.hlc`).
        if (bridgingResolution != null && local.localRowMatchesContentFp) {
            val mergedResRefs = local.resRefs + incoming.resRefs + step0ResRefsToAdd + bridgingResolution.resolutionFp
            return ReconciliationResult(
                reconciliationCase = ReconciliationCase.RESOLUTION_BRIDGED_SUPERSESSION,
                dataAction = applyActionForIncoming(),
                updatedRecordState = stateApplyingIncoming(
                    ancList = listOf(lFp) + incoming.anc + local.ancestors,
                    resRefsList = mergedResRefs,
                ),
                resolutionsToPersist = resolutionsToPersist,
                conflictsToSettle = settlements,
            )
        }

        // --------------------------------------------------------------------
        // CASE 4 — Stale (ignore body)
        // --------------------------------------------------------------------
        if (incomingKnownAncestorOrRetired) {
            return ReconciliationResult(
                reconciliationCase = ReconciliationCase.STALE,
                dataAction = ReconciliationDataAction.NoDataChange,
                // Stale only because a resolution retired R proves nothing about L's descent.
                updatedRecordState = stateKeepingLocal(includeCandidateRefs = incoming.fp in local.ancestors),
                resolutionsToPersist = resolutionsToPersist,
                conflictsToSettle = settlements,
            )
        }

        // --------------------------------------------------------------------
        // CASE 5 — Concurrent divergence (Conflict: EDIT_EDIT or DELETE_EDIT)
        // --------------------------------------------------------------------
        return openConflictResult(
            permissionId = permissionId,
            domain = domain,
            recordKey = recordKey,
            bookUrl = bookUrl,
            localFp = lFp,
            localIsLivePut = local.isLivePut,
            incoming = incoming,
            incomingIsLivePut = isIncomingLivePut,
            remoteDeviceId = remoteDeviceId,
            remoteSeq = remoteSeq,
            now = now,
            keptLocalState = stateKeepingLocal(includeCandidateRefs = false),
            resolutionsToPersist = resolutionsToPersist,
            settlements = settlements,
        )
    }

    /**
     * Finds the winning resolution among all rows in [resolutions] whose `resolvedFps`
     * intersect [targetFps], ordered lexicographically by `(hlc, resolutionFp)`.
     */
    fun winningResolutionFor(
        resolutions: List<DriveResolution>,
        targetFps: Set<String>,
    ): DriveResolution? = resolutions
        .filter { res -> decodeFps(res.resolvedFpsJson).any { it in targetFps } }
        .maxWithOrNull(compareBy<DriveResolution>({ it.hlc }, { it.resolutionFp }))

    /**
     * Builds the durable [DriveResolution] and updated [DriveRecordState] (with `versionOp = "resolve"`,
     * `publishedFp = null`) for a local reader choice closing [conflict].
     */
    fun buildLocalResolution(
        permissionId: String,
        conflict: DriveConflict,
        outcome: String,
        chosenBodyMap: Map<String, Any?>?,
        chosenContentFp: String?,
        existingState: DriveRecordState?,
        hlc: String,
        localDeviceId: String,
        lastSyncedRevision: Long? = existingState?.lastSyncedRevision,
        now: Long,
        explicitResolutionFp: String? = null,
    ): Pair<DriveResolution, DriveRecordState> {
        require(outcome == DriveResolution.OUTCOME_PUT || outcome == DriveResolution.OUTCOME_DELETE)
        val resolvedFps = listOf(conflict.localFp, conflict.remoteFp).distinct().sorted()
        val resolveFp = explicitResolutionFp
            ?: DriveCanonicalJson.resolveFp(outcome, resolvedFps, hlc, chosenBodyMap)
        val existingAnc = DriveWorkIdentity.decodeStringArray(existingState?.ancestorsJson)
        val remoteAnc = DriveWorkIdentity.decodeStringArray(conflict.remoteAncJson)
        val newAnc = (resolvedFps + listOfNotNull(existingState?.versionFp) + existingAnc + remoteAnc)
            .distinct()
            .filter { it != resolveFp }
            .take(DriveWireCodec.MAX_ANCESTORS)
        val existingResRefs = DriveWorkIdentity.decodeStringArray(existingState?.resRefsJson)
        val remoteResRefs = DriveWorkIdentity.decodeStringArray(conflict.remoteResRefsJson)
        val remoteResolution = conflict.remoteFp.takeIf { conflict.remoteOp == DriveRecordState.OP_RESOLVE }
        val newResRefs = (existingResRefs + remoteResRefs + listOfNotNull(remoteResolution) + resolveFp).distinct().sorted()
        val bodyJson = chosenBodyMap?.let(DriveCanonicalJson::encode)

        val resolution = DriveResolution(
            permissionId = permissionId,
            domain = conflict.domain,
            recordKey = conflict.recordKey,
            resolutionFp = resolveFp,
            resolvedFpsJson = DriveCanonicalJson.encode(resolvedFps),
            outcome = outcome,
            hlc = hlc,
            authorDeviceId = localDeviceId,
            createdAt = now,
        )
        val state = DriveRecordState(
            permissionId = permissionId,
            domain = conflict.domain,
            recordKey = conflict.recordKey,
            versionFp = resolveFp,
            versionOp = DriveRecordState.OP_RESOLVE,
            publishedFp = null, // queued for outgoing delta batch
            contentFp = if (outcome == DriveResolution.OUTCOME_PUT) chosenContentFp else null,
            lastSyncedRevision = lastSyncedRevision,
            originDeviceId = null,
            originSeq = null,
            ancestorsJson = DriveCanonicalJson.encode(newAnc),
            resRefsJson = DriveCanonicalJson.encode(newResRefs),
            lastHlc = hlc,
            bodyJson = bodyJson,
        )
        return resolution to state
    }

    /**
     * Freezes a recovered `op: "resolve"` record into `drive_outbox` inside the caller's
     * `drive_sync.db` transaction when crash recovery detects that Tx 1 already committed
     * to `liseur.db` before Tx 2 ran. Leaving `ackMetadataJson = null` ensures draining
     * the outbox does not overwrite any post-Tx1 local edit or ack in `liseur.db`.
     */
    suspend fun freezeRecoveredResolutionOutbox(
        store: DriveSyncDatabase,
        permissionId: String,
        localDeviceId: String,
        wireRecord: DriveWireRecord,
        createdHlc: String,
        now: Long,
    ): Boolean {
        val account = store.accounts().get(permissionId) ?: return false
        val seq = account.nextSeq
        val batchId = DriveCanonicalJson.batchId(localDeviceId, seq)
        val batch = DriveDeltaBatch(
            schemaVersion = DriveWireCodec.SCHEMA_VERSION,
            batchId = batchId,
            deviceId = localDeviceId,
            seq = seq,
            createdHlc = createdHlc,
            domains = mapOf(wireRecord.d to 1),
            records = listOf(wireRecord),
        )
        val payload = DriveWireCodec.encodeDeltaBatch(batch)
        if (payload.toByteArray(Charsets.UTF_8).size > DriveWireCodec.MAX_BATCH_BYTES) return false
        store.accounts().setNextSeq(permissionId, seq + 1L)
        store.outbox().upsert(
            DriveOutbox(
                permissionId = permissionId,
                seq = seq,
                batchId = batchId,
                payload = payload,
                state = DriveOutbox.STATE_BUILT,
                fileId = null,
                createdAt = now,
                ackMetadataJson = null,
            ),
        )
        return true
    }

    private fun decodeList(json: String?): List<String> = DriveWorkIdentity.decodeStringArray(json)

    private fun remoteLineage(conflict: DriveConflict): Set<String> =
        decodeFps(conflict.remoteResRefsJson) + decodeFps(conflict.remoteAncJson)

    private fun DriveConflict.toWireRecord() = DriveWireRecord(
        d = domain,
        k = recordKey,
        op = remoteOp ?: if (remoteJson != null) DriveRecordState.OP_PUT else DriveRecordState.OP_DEL,
        hlc = remoteHlc,
        fp = remoteFp,
        anc = decodeList(remoteAncJson),
        resRefs = decodeList(remoteResRefsJson),
        body = remoteJson?.let(::JSONObject),
    )

    fun decodeFps(jsonArrayString: String?): Set<String> =
        DriveWorkIdentity.decodeStringArray(jsonArrayString).toSet()

    private fun openConflictResult(
        permissionId: String,
        domain: String,
        recordKey: String,
        bookUrl: String?,
        localFp: String,
        localIsLivePut: Boolean,
        incoming: DriveWireRecord,
        incomingIsLivePut: Boolean,
        remoteDeviceId: String,
        remoteSeq: Long,
        now: Long,
        keptLocalState: DriveRecordState,
        resolutionsToPersist: List<DriveResolution>,
        settlements: List<ConflictSettlement>,
    ): ReconciliationResult {
        val kind = if (localIsLivePut && incomingIsLivePut) {
            DriveConflict.KIND_EDIT_EDIT
        } else {
            DriveConflict.KIND_DELETE_EDIT
        }
        val conflictId = DriveCanonicalJson.conflictId(domain, recordKey, localFp, incoming.fp)
        val remoteJson = if (incomingIsLivePut) {
            incoming.body?.let(DriveCanonicalJson::encode)
        } else {
            null
        }
        val conflict = DriveConflict(
            permissionId = permissionId,
            conflictId = conflictId,
            domain = domain,
            recordKey = recordKey,
            bookUrl = bookUrl,
            kind = kind,
            localFp = localFp,
            remoteFp = incoming.fp,
            remoteJson = remoteJson,
            remoteDeviceId = remoteDeviceId,
            remoteHlc = incoming.hlc,
            remoteOp = incoming.op,
            remoteSeq = remoteSeq,
            remoteAncJson = DriveCanonicalJson.encode(incoming.anc),
            remoteResRefsJson = DriveCanonicalJson.encode(incoming.resRefs),
            state = DriveConflict.STATE_OPEN,
            choice = null,
            resolutionFp = null,
            openedAt = now,
            resolvedAt = null,
        )
        return ReconciliationResult(
            reconciliationCase = if (kind == DriveConflict.KIND_EDIT_EDIT) {
                ReconciliationCase.CONFLICT_EDIT_EDIT
            } else {
                ReconciliationCase.CONFLICT_DELETE_EDIT
            },
            dataAction = ReconciliationDataAction.NoDataChange,
            updatedRecordState = keptLocalState,
            resolutionsToPersist = resolutionsToPersist,
            conflictsToSettle = settlements,
            conflictToOpen = conflict,
        )
    }

    private fun compareHlcFp(hlcA: String, fpA: String, hlcB: String, fpB: String): Int {
        val cmp = hlcA.compareTo(hlcB)
        return if (cmp != 0) cmp else fpA.compareTo(fpB)
    }
}
