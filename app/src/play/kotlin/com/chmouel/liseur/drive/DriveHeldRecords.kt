package com.chmouel.liseur.drive

import org.json.JSONObject

/**
 * Helpers for records retained against a work that has no local book yet
 * (`docs/DRIVE_SYNC.md` §4).
 *
 * A catalog tombstone counts as a known catalog entry, so the record is not
 * deferred: it is decided against the record's own retained baseline and stored
 * with `held = 1` instead of being dropped, keeping its body, lineage,
 * resolutions and any competing version. When the work gains a book again the
 * retained version is replayed as an ordinary incoming record, so the normal
 * reconciliation decides it against whatever the restored book already holds.
 */
internal object DriveHeldRecords {

    /** Domains whose records can be retained for a work with no local book. */
    val HELD_DOMAINS: Set<String> = setOf(
        DriveWireCodec.DOMAIN_SERIES,
        DriveWireCodec.DOMAIN_EVALUATION,
        DriveWireCodec.DOMAIN_METADATA,
    )

    /**
     * The `workKey` a held record belongs to. `series` keys the record by the work
     * itself, while `evaluation` and `metadata` append their field after a slash.
     */
    fun workKeyFor(domain: String, recordKey: String): String = when (domain) {
        DriveWireCodec.DOMAIN_SERIES -> recordKey
        else -> recordKey.substringBeforeLast('/', "")
    }

    /**
     * The retained version as the local side of §3.1.
     *
     * A held row is this record's own history, not data in `liseur.db`, so it is
     * the baseline an incoming version is decided against. It never makes the row
     * look materialized: [LocalRecordView.isLivePut] follows the retained op, and
     * materialization deliberately does not use this view.
     */
    fun localView(state: DriveRecordState?): LocalRecordView {
        if (state == null) return LocalRecordView(versionFp = null)
        val isLivePut = when (state.versionOp) {
            DriveRecordState.OP_PUT -> true
            DriveRecordState.OP_DEL -> false
            else -> state.bodyJson != null
        }
        return LocalRecordView(
            versionFp = state.versionFp,
            versionOp = state.versionOp,
            publishedFp = state.publishedFp,
            contentFp = state.contentFp,
            isLivePut = isLivePut,
            lastSyncedRevision = state.lastSyncedRevision,
            originDeviceId = state.originDeviceId,
            originSeq = state.originSeq,
            ancestors = DriveWorkIdentity.decodeStringArray(state.ancestorsJson),
            resRefs = DriveWorkIdentity.decodeStringArray(state.resRefsJson),
            lastHlc = state.lastHlc,
            bodyJson = state.bodyJson,
            localRowMatchesContentFp = true,
        )
    }

    /**
     * Rebuilds the wire version a held state retained, so materialization replays the
     * exact version instead of a projection of it. [outcome] and [resolvedFps] come
     * from `drive_resolution` for a `resolve` record.
     */
    fun toWireRecord(
        domain: String,
        state: DriveRecordState,
        outcome: String? = null,
        resolvedFps: List<String> = emptyList(),
    ): DriveWireRecord? {
        if (state.versionOp == DriveRecordState.OP_RESOLVE && outcome == null) return null
        val storedBody = state.bodyJson?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
        }
        if (state.versionOp == DriveRecordState.OP_PUT && storedBody == null) return null
        // The retained body still names the work key it was published under; the record key
        // holds the canonical one this device resolved afterwards. Replay it under that key so
        // the record's own key/body agreement check does not drop it.
        val body = storedBody?.let { json ->
            val workKey = workKeyFor(domain, state.recordKey)
            if (workKey.isEmpty() || json.optString("workKey").trim() == workKey) {
                json
            } else {
                JSONObject(json.toString()).put("workKey", workKey)
            }
        }
        return DriveWireRecord(
            d = domain,
            k = state.recordKey,
            op = state.versionOp,
            hlc = state.lastHlc,
            fp = state.versionFp,
            anc = DriveWorkIdentity.decodeStringArray(state.ancestorsJson),
            resRefs = DriveWorkIdentity.decodeStringArray(state.resRefsJson),
            body = body,
            outcome = outcome,
            resolvedFps = resolvedFps,
        )
    }

    /**
     * Carries over a superseded held row's ancestors and resolution refs onto the newly applied
     * successor state so lineage retained while the book was absent is never dropped.
     */
    fun mergeSupersededLineage(
        persisted: DriveRecordState,
        heldState: DriveRecordState,
    ): DriveRecordState {
        val mergedAnc = (DriveWorkIdentity.decodeStringArray(persisted.ancestorsJson) +
            listOf(heldState.versionFp) +
            DriveWorkIdentity.decodeStringArray(heldState.ancestorsJson))
            .distinct()
            .filter { it != persisted.versionFp }
            .take(DriveWireCodec.MAX_ANCESTORS)
        val mergedResRefs = (DriveWorkIdentity.decodeStringArray(persisted.resRefsJson) +
            DriveWorkIdentity.decodeStringArray(heldState.resRefsJson))
            .distinct()
            .sorted()
        return persisted.copy(
            ancestorsJson = DriveCanonicalJson.encode(mergedAnc),
            resRefsJson = DriveCanonicalJson.encode(mergedResRefs),
        )
    }
}
