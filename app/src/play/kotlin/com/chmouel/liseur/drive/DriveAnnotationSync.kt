package com.chmouel.liseur.drive

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.AnnotationSync
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.LiseurDatabase
import java.util.Locale
import org.json.JSONObject

/** Outbound `annotation` records paired with atomic `liseur.db` delivery ACK metadata. */
internal data class DriveAnnotationOutboundBundle(
    val records: List<DriveWireRecord>,
    val acks: List<DriveAnnotationAckMeta>,
)

/**
 * Google Drive sync adapter for highlights, passage notes, book notes, and bookmarks
 * (`docs/DRIVE_SYNC.md` §4.7).
 *
 * Preserves annotation IDs, anchors, tints, and ms/µs timestamps; excludes `position`
 * from wire and `contentFp`; excludes `editionSha` from `contentFp`; uses the per-peer
 * `annotation_sync` table (`peer_id = "drive:<permissionId>"`) for atomic data+ack
 * commits and crash recovery.
 */
internal class DriveAnnotationSync(
    private val database: LiseurDatabase,
    private val store: DriveSyncDatabase,
    private val workIdentity: DriveWorkIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val books get() = database.bookDao()
    private val annotations get() = database.annotationDao()
    private val annotationSync get() = database.annotationSyncDao()
    private val identities get() = database.workIdentityDao()
    private val recordStates get() = store.recordStates()
    private val resolutions get() = store.resolutions()
    private val conflicts get() = store.conflicts()

    suspend fun collectDirtyRecords(
        permissionId: String,
        bookUrlFilter: String? = null,
        nextHlc: suspend () -> String,
    ): DriveAnnotationOutboundBundle {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val records = mutableListOf<DriveWireRecord>()
        val acks = mutableListOf<DriveAnnotationAckMeta>()
        val emittedIds = mutableSetOf<String>()

        // 1. Emit any queued unpublished `op: "resolve"` records first.
        val allStates = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_ANNOTATION)
        for (state in allStates) {
            if (state.versionOp != DriveRecordState.OP_RESOLVE) continue
            if (state.publishedFp == state.versionFp) continue
            val res = resolutions.get(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, state.recordKey, state.versionFp)
                ?: continue
            val syncRow = annotationSync.get(peerId, state.recordKey)
            val liveRow = annotations.byId(state.recordKey)
            val bookId = liveRow?.bookId ?: syncRow?.bookId
            if (bookUrlFilter != null && bookId != null && bookId != bookUrlFilter) continue
            // A choice still waiting for recovery has not reached `liseur.db`, so it is not published yet.
            val awaitingLocalCommit = bookId != null &&
                conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, state.recordKey)
                    .any { it.state == DriveConflict.STATE_RESOLVING && it.resolutionFp == state.versionFp }
            if (awaitingLocalCommit) {
                val expectedAck = if (res.outcome == DriveResolution.OUTCOME_DELETE) DELETED_ACK else state.contentFp
                if (syncRow?.ackedFingerprint != expectedAck) continue
            }

            val bodyMap = if (res.outcome == DriveResolution.OUTCOME_PUT) {
                state.bodyJson?.let { raw ->
                    runCatching { DriveWireCodec.decodeAnnotationBody(JSONObject(raw)) }
                        .getOrNull()
                        ?.let(DriveWireCodec::encodeAnnotationBody)
                } ?: continue
            } else {
                null
            }
            val wire = DriveWireCodec.resolveRecord(
                domain = DriveWireCodec.DOMAIN_ANNOTATION,
                key = state.recordKey,
                outcome = res.outcome,
                resolvedFps = DriveWorkIdentity.decodeStringArray(res.resolvedFpsJson),
                hlc = state.lastHlc,
                bodyMap = bodyMap,
                ancestors = DriveWorkIdentity.decodeStringArray(state.ancestorsJson),
                resRefs = DriveWorkIdentity.decodeStringArray(state.resRefsJson),
                explicitFp = state.versionFp,
            )
            records.add(wire)
            emittedIds.add(state.recordKey)
            if (bookId != null) {
                val workId = syncRow?.workId
                    ?: state.bodyJson?.let { runCatching { JSONObject(it).optString("workKey") }.getOrNull() }
                    ?: ""
                val ackFp = if (res.outcome == DriveResolution.OUTCOME_DELETE) {
                    DELETED_ACK
                } else {
                    state.contentFp ?: state.versionFp
                }
                acks.add(
                    DriveAnnotationAckMeta(
                        id = state.recordKey,
                        bookId = bookId,
                        workId = workId,
                        ackedFingerprint = ackFp,
                        recordFp = state.versionFp,
                    ),
                )
            }
        }

        // 2. Collect live annotations for eligible books.
        val eligibleBooks = if (bookUrlFilter != null) {
            listOfNotNull(books.getByUrl(bookUrlFilter))
        } else {
            books.allOnce()
        }.filter { it.browseServerId == null }

        val workByBookUrl = mutableMapOf<String, Pair<String, String?>>()
        for (book in eligibleBooks) {
            val alias = workIdentity.resolveBook(permissionId, book, computeFingerprintIfMissing = false)
                ?.takeIf { it.usable }
                ?: continue
            val canonical = workIdentity.canonicalWorkKey(permissionId, alias.workId)
            val editionSha = identities.fingerprint(book.url)?.sha256?.lowercase(Locale.ROOT)
                ?: alias.editionSha?.lowercase(Locale.ROOT)
            workByBookUrl[book.url] = canonical to editionSha
        }

        for ((bookUrl, workAndSha) in workByBookUrl) {
            val (workKey, editionSha) = workAndSha
            for (ann in annotations.forBook(bookUrl)) {
                if (ann.id in emittedIds) continue
                if (conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, ann.id).isNotEmpty()) {
                    continue
                }
                val body = toWireBody(ann, workKey, editionSha)
                val bodyMap = DriveWireCodec.encodeAnnotationBody(body)
                val bodyJson = DriveCanonicalJson.encode(bodyMap)
                val contentFp = DriveWireCodec.annotationContentFp(body)
                val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, ann.id)
                val syncRow = annotationSync.get(peerId, ann.id)
                if (existingState != null && existingState.publishedFp == existingState.versionFp &&
                    contentFp == baselineContentFp(existingState, workKey)
                ) continue

                // Crash recovery: if liseur.db already acked this exact contentFp before Tx 2 committed.
                if (syncRow?.ackedFingerprint == contentFp &&
                    (existingState == null || existingState.contentFp != contentFp || existingState.publishedFp != existingState.versionFp)
                ) {
                    val repairedFp = if (existingState?.versionOp == DriveRecordState.OP_RESOLVE && existingState.contentFp == contentFp) {
                        existingState.versionFp
                    } else {
                        contentFp
                    }
                    val repairedOp = if (repairedFp == contentFp) DriveRecordState.OP_PUT else DriveRecordState.OP_RESOLVE
                    recordStates.upsert(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_ANNOTATION,
                            recordKey = ann.id,
                            versionFp = repairedFp,
                            versionOp = repairedOp,
                            publishedFp = repairedFp,
                            contentFp = contentFp,
                            originDeviceId = existingState?.originDeviceId?.takeIf { existingState.versionFp == repairedFp },
                            originSeq = existingState?.originSeq?.takeIf { existingState.versionFp == repairedFp },
                            ancestorsJson = existingState?.ancestorsJson ?: "[]",
                            resRefsJson = existingState?.resRefsJson ?: "[]",
                            lastHlc = existingState?.lastHlc ?: "",
                            bodyJson = bodyJson,
                        ),
                    )
                    continue
                }

                val hlc: String
                val ancestors: List<String>
                val resRefs = DriveWorkIdentity.decodeStringArray(existingState?.resRefsJson)
                if (existingState != null && existingState.versionOp == DriveRecordState.OP_PUT && existingState.versionFp == contentFp) {
                    hlc = existingState.lastHlc.ifBlank { nextHlc() }
                    ancestors = DriveWorkIdentity.decodeStringArray(existingState.ancestorsJson)
                } else {
                    hlc = nextHlc()
                    ancestors = buildList {
                        existingState?.versionFp?.takeIf { it != contentFp }?.let(::add)
                        addAll(DriveWorkIdentity.decodeStringArray(existingState?.ancestorsJson))
                    }.distinct().take(DriveWireCodec.MAX_ANCESTORS)
                    recordStates.upsert(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_ANNOTATION,
                            recordKey = ann.id,
                            versionFp = contentFp,
                            versionOp = DriveRecordState.OP_PUT,
                            publishedFp = existingState?.publishedFp,
                            contentFp = contentFp,
                            originDeviceId = null,
                            originSeq = null,
                            ancestorsJson = DriveCanonicalJson.encode(ancestors),
                            resRefsJson = DriveCanonicalJson.encode(resRefs),
                            lastHlc = hlc,
                            bodyJson = bodyJson,
                        ),
                    )
                }

                records.add(
                    DriveWireCodec.putRecordWithFp(
                        domain = DriveWireCodec.DOMAIN_ANNOTATION,
                        key = ann.id,
                        hlc = hlc,
                        fp = contentFp,
                        bodyMap = bodyMap,
                        ancestors = ancestors,
                        resRefs = resRefs,
                    ),
                )
                emittedIds.add(ann.id)
                acks.add(
                    DriveAnnotationAckMeta(
                        id = ann.id,
                        bookId = ann.bookId,
                        workId = workKey,
                        ackedFingerprint = contentFp,
                        recordFp = contentFp,
                    ),
                )
            }
        }

        // 3. Detect local deletions: `annotation_sync` row exists while `annotations` row is absent.
        // Note: `BookRemoval.forget` preserves both `annotations` and `annotation_sync`, and
        // `BookRemoval.contentReplaced` deletes both together, so book removal never emits tombstones.
        val peerSyncRows = if (bookUrlFilter != null) {
            annotationSync.forBook(peerId, bookUrlFilter)
        } else {
            annotationSync.forPeer(peerId)
        }
        for (syncRow in peerSyncRows) {
            if (syncRow.id in emittedIds) continue
            if (conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, syncRow.id).isNotEmpty()) {
                continue
            }
            if (annotations.byId(syncRow.id) != null) continue

            val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, syncRow.id)
            if (syncRow.ackedFingerprint == DELETED_ACK) {
                if (existingState != null && existingState.publishedFp != existingState.versionFp &&
                    (existingState.versionOp == DriveRecordState.OP_DEL || existingState.versionOp == DriveRecordState.OP_RESOLVE)
                ) {
                    recordStates.upsert(existingState.copy(publishedFp = existingState.versionFp))
                }
                continue
            }

            val resRefs = DriveWorkIdentity.decodeStringArray(existingState?.resRefsJson)
            val hlc: String
            val delFp: String
            val ancestors: List<String>
            if (existingState != null && existingState.versionOp == DriveRecordState.OP_DEL && existingState.publishedFp != existingState.versionFp) {
                hlc = existingState.lastHlc.ifBlank { nextHlc() }
                delFp = existingState.versionFp
                ancestors = DriveWorkIdentity.decodeStringArray(existingState.ancestorsJson)
            } else {
                hlc = nextHlc()
                delFp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_ANNOTATION, syncRow.id, hlc)
                ancestors = buildList {
                    existingState?.versionFp?.let(::add)
                    syncRow.ackedFingerprint?.takeIf { it != DELETED_ACK }?.let(::add)
                    addAll(DriveWorkIdentity.decodeStringArray(existingState?.ancestorsJson))
                }.distinct().take(DriveWireCodec.MAX_ANCESTORS)
                recordStates.upsert(
                    DriveRecordState(
                        permissionId = permissionId,
                        domain = DriveWireCodec.DOMAIN_ANNOTATION,
                        recordKey = syncRow.id,
                        versionFp = delFp,
                        versionOp = DriveRecordState.OP_DEL,
                        publishedFp = existingState?.publishedFp,
                        contentFp = null,
                        originDeviceId = null,
                        originSeq = null,
                        ancestorsJson = DriveCanonicalJson.encode(ancestors),
                        resRefsJson = DriveCanonicalJson.encode(resRefs),
                        lastHlc = hlc,
                        bodyJson = null,
                    ),
                )
            }

            records.add(
                DriveWireCodec.delRecord(
                    domain = DriveWireCodec.DOMAIN_ANNOTATION,
                    key = syncRow.id,
                    hlc = hlc,
                    ancestors = ancestors,
                    resRefs = resRefs,
                    explicitFp = delFp,
                ),
            )
            emittedIds.add(syncRow.id)
            acks.add(
                DriveAnnotationAckMeta(
                    id = syncRow.id,
                    bookId = syncRow.bookId,
                    workId = syncRow.workId,
                    ackedFingerprint = DELETED_ACK,
                    recordFp = delFp,
                ),
            )
        }

        return DriveAnnotationOutboundBundle(records = records, acks = acks)
    }

    /**
     * Commits delivery ACKs in strict two-database order:
     * Transaction 1 (`liseur.db` `annotation_sync`) FIRST, then Transaction 2 (`drive_sync.db`).
     */
    suspend fun acknowledgePublished(
        permissionId: String,
        records: List<DriveWireRecord>,
        acks: List<DriveAnnotationAckMeta>,
    ) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        if (acks.isNotEmpty()) {
            database.withTransaction {
                for (ack in acks) {
                    val existing = annotationSync.get(peerId, ack.id)
                    annotationSync.upsert(
                        existing?.copy(
                            bookId = ack.bookId,
                            workId = ack.workId.ifBlank { existing.workId },
                            ackedFingerprint = ack.ackedFingerprint,
                        ) ?: AnnotationSync(
                            id = ack.id,
                            peerId = peerId,
                            bookId = ack.bookId,
                            workId = ack.workId,
                            ackedFingerprint = ack.ackedFingerprint,
                        ),
                    )
                }
            }
        }
        val now = clock()
        store.withTransaction {
            for (record in records) {
                if (record.d != DriveWireCodec.DOMAIN_ANNOTATION) continue
                val state = recordStates.get(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, record.k)
                if (state != null && state.versionFp == record.fp) {
                    recordStates.upsert(state.copy(publishedFp = record.fp))
                }
                if (record.op == DriveRecordState.OP_RESOLVE) {
                    for (conflict in conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, record.k)) {
                        if (conflict.state == DriveConflict.STATE_RESOLVING ||
                            (conflict.localFp in record.resolvedFps && conflict.remoteFp in record.resolvedFps)
                        ) {
                            conflicts.markResolved(
                                permissionId = permissionId,
                                conflictId = conflict.conflictId,
                                choice = conflict.choice ?: record.outcome,
                                resolutionFp = record.fp,
                                resolvedAt = now,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Applies one incoming remote `annotation` record (`put`, `del`, or `resolve`) via [DriveConflictEngine]. */
    suspend fun applyRemoteRecord(
        permissionId: String,
        record: DriveWireRecord,
        remoteDeviceId: String,
        remoteSeq: Long,
        nextHlc: suspend () -> String,
    ): Boolean {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val isIncomingDelete = record.op == DriveRecordState.OP_DEL ||
            (record.op == DriveRecordState.OP_RESOLVE && record.outcome == DriveResolution.OUTCOME_DELETE)
        val decodedBody = if (!isIncomingDelete) {
            record.body?.let(DriveWireCodec::decodeAnnotationBody) ?: return true
        } else {
            null
        }
        val canonicalWorkKey = decodedBody?.workKey?.let { workIdentity.canonicalWorkKey(permissionId, it) }
        val incomingContentFp = decodedBody?.let {
            DriveWireCodec.annotationContentFp(it.copy(workKey = canonicalWorkKey!!))
        }

        val liveRow = annotations.byId(record.k)
        val syncRow = annotationSync.get(peerId, record.k)
        val targetBookUrl = liveRow?.bookId
            ?: syncRow?.bookId
            ?: canonicalWorkKey?.let { findBookUrlForWorkKey(permissionId, it) }
            ?: canonicalWorkKey?.takeIf { workIdentity.hasDeletedCatalogEntry(permissionId, decodedBody.workKey) }
                ?.let(DriveWorkIdentity::driveBookUrl)

        if (!isIncomingDelete && targetBookUrl == null) return false

        val effectiveWorkKey = canonicalWorkKey
            ?: syncRow?.workId
            ?: targetBookUrl?.let { url ->
                identities.alias(url, peerId)?.workId?.let { workIdentity.canonicalWorkKey(permissionId, it) }
            }
            ?: ""

        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, record.k)
        val existingResolutions = resolutions.forRecord(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, record.k)
        val unsettledConflicts = conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, record.k)

        val localView = buildLocalRecordView(
            permissionId = permissionId,
            recordKey = record.k,
            liveRow = liveRow,
            syncRow = syncRow,
            workKey = effectiveWorkKey,
            existingState = existingState,
            existingResolutions = existingResolutions,
            incoming = record,
            incomingContentFp = incomingContentFp,
            isIncomingDelete = isIncomingDelete,
            nextHlc = nextHlc,
        )

        val result = DriveConflictEngine.reconcileIncoming(
            permissionId = permissionId,
            domain = DriveWireCodec.DOMAIN_ANNOTATION,
            recordKey = record.k,
            bookUrl = targetBookUrl,
            local = localView,
            existingResolutions = existingResolutions,
            unsettledConflicts = unsettledConflicts,
            incoming = record,
            incomingContentFp = incomingContentFp,
            remoteDeviceId = remoteDeviceId,
            remoteSeq = remoteSeq,
            now = clock(),
        )

        var staleLocal = false
        var appliedContentFp: String? = null
        // Transaction 1 (`liseur.db`): data + atomic `annotation_sync` ack.
        when (val action = result.dataAction) {
            is ReconciliationDataAction.ApplyPut -> {
                // A parked successor can win over the incoming resolution, so apply the body the engine chose.
                val body = DriveWireCodec.decodeAnnotationBody(action.body) ?: return true
                val bookUrl = targetBookUrl
                    ?: findBookUrlForWorkKey(permissionId, workIdentity.canonicalWorkKey(permissionId, body.workKey))
                    ?: return true
                val bodyContentFp = DriveWireCodec.annotationContentFp(body.copy(workKey = effectiveWorkKey))
                appliedContentFp = bodyContentFp
                database.withTransaction {
                    if (annotations.byId(record.k) != liveRow || annotationSync.get(peerId, record.k) != syncRow) {
                        staleLocal = true
                        return@withTransaction
                    }
                    val existing = annotations.byId(body.id)
                    annotations.upsert(
                        BookAnnotation(
                            id = body.id,
                            bookId = existing?.bookId ?: bookUrl,
                            kind = body.kind,
                            locatorJson = if (body.kind == AnnotationKind.BOOK_NOTE.name) "" else body.locatorJson,
                            text = body.text,
                            note = body.note,
                            tint = body.tint,
                            chapter = body.chapter,
                            position = existing?.position, // local-only rendered page number preserved
                            totalProgression = body.totalProgression,
                            createdAt = body.createdAt,
                            noteCreatedAt = body.noteCreatedAt,
                            noteUpdatedAt = body.noteUpdatedAt,
                            updatedAt = body.updatedAt,
                        ),
                    )
                    annotationSync.upsert(
                        AnnotationSync(
                            id = body.id,
                            peerId = peerId,
                            bookId = existing?.bookId ?: bookUrl,
                            workId = effectiveWorkKey,
                            ackedFingerprint = bodyContentFp,
                        ),
                    )
                }
            }
            ReconciliationDataAction.ApplyDelete -> {
                database.withTransaction {
                    if (annotations.byId(record.k) != liveRow || annotationSync.get(peerId, record.k) != syncRow) {
                        staleLocal = true
                        return@withTransaction
                    }
                    annotations.deleteById(record.k)
                    val bookUrl = targetBookUrl ?: syncRow?.bookId
                    if (bookUrl != null) {
                        annotationSync.upsert(
                            syncRow?.copy(ackedFingerprint = DELETED_ACK)
                                ?: AnnotationSync(
                                    id = record.k,
                                    peerId = peerId,
                                    bookId = bookUrl,
                                    workId = effectiveWorkKey,
                                    ackedFingerprint = DELETED_ACK,
                                ),
                        )
                    }
                }
            }
            ReconciliationDataAction.NoDataChange -> Unit
        }

        // Defer: the record replays and reconciles against the reader's newer edit.
        if (staleLocal) return false

        // Transaction 2 (`drive_sync.db`): commit Step 0 side effects + causal state BEFORE cursor advance.
        store.withTransaction {
            for (res in result.resolutionsToPersist) {
                resolutions.upsert(res)
            }
            for (settlement in result.conflictsToSettle) {
                conflicts.markResolved(
                    permissionId = permissionId,
                    conflictId = settlement.conflictId,
                    choice = settlement.choice,
                    resolutionFp = settlement.resolutionFp,
                    resolvedAt = settlement.resolvedAt,
                )
            }
            result.conflictToOpen?.let { conflicts.upsert(it) }
            result.updatedRecordState?.let { updated ->
                recordStates.upsert(appliedContentFp?.let { updated.copy(contentFp = it) } ?: updated)
            }
        }
        return true
    }

    /**
     * Applies a reader's conflict choice locally (Tx 1 `liseur.db` -> Tx 2 `drive_sync.db`)
     * and queues an `op: "resolve"` record for the next outgoing delta batch.
     */
    suspend fun resolveLocalConflict(
        permissionId: String,
        conflict: DriveConflict,
        choice: DriveConflictChoice,
        localDeviceId: String,
        nextHlc: suspend () -> String,
    ) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val now = clock()
        conflicts.markResolving(permissionId, conflict.conflictId, choice.name)

        val liveRow = annotations.byId(conflict.recordKey)
        val syncRow = annotationSync.get(peerId, conflict.recordKey)
        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, conflict.recordKey)
        val remoteBody = conflict.remoteJson?.let { raw ->
            runCatching { DriveWireCodec.decodeAnnotationBody(JSONObject(raw)) }.getOrNull()
        }
        val targetBookUrl = conflict.bookUrl ?: liveRow?.bookId ?: syncRow?.bookId
            ?: remoteBody?.workKey?.let { findBookUrlForWorkKey(permissionId, workIdentity.canonicalWorkKey(permissionId, it)) }
        val workKey = remoteBody?.workKey?.let { workIdentity.canonicalWorkKey(permissionId, it) }
            ?: syncRow?.workId
            ?: targetBookUrl?.let { url ->
                identities.alias(url, peerId)?.workId?.let { workIdentity.canonicalWorkKey(permissionId, it) }
            }
            ?: ""

        val outcome: String
        val chosenBody: DriveAnnotationBody?
        when (choice) {
            DriveConflictChoice.DELETE -> {
                outcome = DriveResolution.OUTCOME_DELETE
                chosenBody = null
            }
            DriveConflictChoice.KEEP_LOCAL -> {
                if (liveRow != null) {
                    val sha = targetBookUrl?.let { identities.fingerprint(it)?.sha256?.lowercase(Locale.ROOT) }
                    outcome = DriveResolution.OUTCOME_PUT
                    chosenBody = toWireBody(liveRow, workKey, sha)
                } else {
                    outcome = DriveResolution.OUTCOME_DELETE
                    chosenBody = null
                }
            }
            DriveConflictChoice.KEEP_REMOTE -> {
                if (remoteBody != null) {
                    outcome = DriveResolution.OUTCOME_PUT
                    chosenBody = remoteBody.copy(workKey = workKey.ifBlank { remoteBody.workKey })
                } else {
                    outcome = DriveResolution.OUTCOME_DELETE
                    chosenBody = null
                }
            }
            DriveConflictChoice.KEEP_EDITED -> {
                if (liveRow != null) {
                    val sha = targetBookUrl?.let { identities.fingerprint(it)?.sha256?.lowercase(Locale.ROOT) }
                    outcome = DriveResolution.OUTCOME_PUT
                    chosenBody = toWireBody(liveRow, workKey, sha)
                } else if (remoteBody != null) {
                    outcome = DriveResolution.OUTCOME_PUT
                    chosenBody = remoteBody.copy(workKey = workKey.ifBlank { remoteBody.workKey })
                } else {
                    outcome = DriveResolution.OUTCOME_DELETE
                    chosenBody = null
                }
            }
        }

        val chosenBodyMap = chosenBody?.let(DriveWireCodec::encodeAnnotationBody)
        val chosenContentFp = chosenBody?.let(DriveWireCodec::annotationContentFp)
        val hlc = nextHlc()
        val (resolution, state) = DriveConflictEngine.buildLocalResolution(
            permissionId = permissionId,
            conflict = conflict,
            outcome = outcome,
            chosenBodyMap = chosenBodyMap,
            chosenContentFp = chosenContentFp,
            existingState = existingState,
            hlc = hlc,
            localDeviceId = localDeviceId,
            now = now,
        )

        // Persist the exact chosen body with the resolution before touching `liseur.db`, so a crash
        // can later prove what was chosen instead of re-deciding from newer local state.
        store.withTransaction {
            resolutions.upsert(resolution)
            recordStates.upsert(state)
            conflicts.upsert(conflict.copy(state = DriveConflict.STATE_RESOLVING, choice = choice.name, resolutionFp = resolution.resolutionFp))
        }

        commitChosen(peerId, conflict, outcome, chosenBody, chosenContentFp, targetBookUrl, workKey, syncRow)
    }

    /** Applies a persisted chosen outcome to `liseur.db` together with its `annotation_sync` ack. */
    private suspend fun commitChosen(
        peerId: String,
        conflict: DriveConflict,
        outcome: String,
        chosenBody: DriveAnnotationBody?,
        chosenContentFp: String?,
        targetBookUrl: String?,
        workKey: String,
        syncRow: AnnotationSync?,
    ) {
        database.withTransaction {
            if (outcome == DriveResolution.OUTCOME_PUT && chosenBody != null && targetBookUrl != null) {
                val existing = annotations.byId(chosenBody.id)
                annotations.upsert(
                    BookAnnotation(
                        id = chosenBody.id,
                        bookId = existing?.bookId ?: targetBookUrl,
                        kind = chosenBody.kind,
                        locatorJson = if (chosenBody.kind == AnnotationKind.BOOK_NOTE.name) "" else chosenBody.locatorJson,
                        text = chosenBody.text,
                        note = chosenBody.note,
                        tint = chosenBody.tint,
                        chapter = chosenBody.chapter,
                        position = existing?.position,
                        totalProgression = chosenBody.totalProgression,
                        createdAt = chosenBody.createdAt,
                        noteCreatedAt = chosenBody.noteCreatedAt,
                        noteUpdatedAt = chosenBody.noteUpdatedAt,
                        updatedAt = chosenBody.updatedAt,
                    ),
                )
                annotationSync.upsert(
                    AnnotationSync(
                        id = chosenBody.id,
                        peerId = peerId,
                        bookId = existing?.bookId ?: targetBookUrl,
                        workId = workKey,
                        ackedFingerprint = requireNotNull(chosenContentFp),
                    ),
                )
            } else {
                annotations.deleteById(conflict.recordKey)
                if (targetBookUrl != null) {
                    annotationSync.upsert(
                        syncRow?.copy(ackedFingerprint = DELETED_ACK)
                            ?: AnnotationSync(
                                id = conflict.recordKey,
                                peerId = peerId,
                                bookId = targetBookUrl,
                                workId = workKey,
                                ackedFingerprint = DELETED_ACK,
                            ),
                    )
                }
            }
        }
    }

    /** Crash recovery for any `RESOLVING` annotation conflict interrupted between Tx 1 and Tx 2. */
    suspend fun recoverResolvingConflict(
        permissionId: String,
        conflict: DriveConflict,
        localDeviceId: String,
        nextHlc: suspend () -> String,
    ) {
        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, conflict.recordKey)
        val intentState = existingState?.takeIf {
            it.versionOp == DriveRecordState.OP_RESOLVE && it.versionFp == conflict.resolutionFp
        }
        val intentResolution = intentState?.let {
            resolutions.get(permissionId, DriveWireCodec.DOMAIN_ANNOTATION, conflict.recordKey, it.versionFp)
        }
        if (intentState != null && intentResolution == null) return
        if (intentState != null && intentResolution != null && intentState.publishedFp == intentState.versionFp) {
            conflicts.markResolved(
                permissionId,
                conflict.conflictId,
                conflict.choice ?: intentResolution.outcome,
                intentResolution.resolutionFp,
                clock(),
            )
            return
        }
        val choice = conflict.choice?.let { runCatching { DriveConflictChoice.valueOf(it) }.getOrNull() }
            ?: return

        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val liveRow = annotations.byId(conflict.recordKey)
        val syncRow = annotationSync.get(peerId, conflict.recordKey)
        val remoteBody = conflict.remoteJson?.let { raw ->
            runCatching { DriveWireCodec.decodeAnnotationBody(JSONObject(raw)) }.getOrNull()
        }
        val targetBookUrl = conflict.bookUrl ?: liveRow?.bookId ?: syncRow?.bookId
            ?: remoteBody?.workKey?.let { findBookUrlForWorkKey(permissionId, workIdentity.canonicalWorkKey(permissionId, it)) }
        val workKey = remoteBody?.workKey?.let { workIdentity.canonicalWorkKey(permissionId, it) }
            ?: syncRow?.workId
            ?: targetBookUrl?.let { url ->
                identities.alias(url, peerId)?.workId?.let { workIdentity.canonicalWorkKey(permissionId, it) }
            }
            ?: ""

        if (intentState != null && intentResolution != null) {
            // The resolution and chosen body were persisted before Tx 1: finish Tx 1 from them only.
            val chosenBody = if (intentResolution.outcome == DriveResolution.OUTCOME_PUT) {
                intentState.bodyJson?.let { raw ->
                    runCatching { DriveWireCodec.decodeAnnotationBody(JSONObject(raw)) }.getOrNull()
                } ?: return
            } else {
                null
            }
            val chosenContentFp = chosenBody?.let(DriveWireCodec::annotationContentFp)
            if (chosenBody != null && chosenContentFp != intentState.contentFp) return
            val canonicalChosenFp = chosenBody?.let {
                DriveWireCodec.annotationContentFp(it.copy(workKey = workKey.ifBlank { it.workKey }))
            }
            val expectedAck = chosenContentFp ?: DELETED_ACK
            // Tx 1 already committed (any later reader edit stays dirty and publishes as a successor).
            if (syncRow?.ackedFingerprint == expectedAck ||
                (canonicalChosenFp != null && syncRow?.ackedFingerprint == canonicalChosenFp)
            ) {
                return
            }
            if (targetBookUrl == null) return
            // Tx 1 never ran: apply it only while the note is still as it was when the choice was made.
            // Compare against equivalent pre-merge work keys so an alias merge between intent
            // persistence and Tx 1 does not look like a reader edit and leave recovery stuck.
            val candidateWorkKeys = buildSet {
                if (workKey.isNotBlank()) addAll(workIdentity.equivalentWorkKeys(permissionId, workKey))
                chosenBody?.workKey?.takeIf { it.isNotBlank() }?.let(::add)
                syncRow?.workId?.takeIf { it.isNotBlank() }?.let(::add)
                remoteBody?.workKey?.takeIf { it.isNotBlank() }?.let(::add)
            }
            val liveFps = liveRow?.let { row ->
                val sha = identities.fingerprint(row.bookId)?.sha256?.lowercase(Locale.ROOT)
                candidateWorkKeys.map { wk ->
                    DriveWireCodec.annotationContentFp(toWireBody(row, wk, sha))
                }.toSet()
            }.orEmpty()
            val untouched = (chosenContentFp != null && chosenContentFp in liveFps) ||
                conflict.localFp in liveFps ||
                (liveRow == null && chosenBody == null)
            if (!untouched) return
            commitChosen(peerId, conflict, intentResolution.outcome, chosenBody, chosenContentFp, targetBookUrl, workKey, syncRow)
            return
        }

        var tx1AlreadyCommitted = false
        var recoveredOutcome = DriveResolution.OUTCOME_DELETE
        var recoveredBody: DriveAnnotationBody? = null

        when (choice) {
            DriveConflictChoice.KEEP_REMOTE -> {
                if (remoteBody != null) {
                    val normalized = remoteBody.copy(workKey = workKey.ifBlank { remoteBody.workKey })
                    val expectedAck = DriveWireCodec.annotationContentFp(normalized)
                    if (syncRow?.ackedFingerprint == expectedAck) {
                        tx1AlreadyCommitted = true
                        recoveredOutcome = DriveResolution.OUTCOME_PUT
                        recoveredBody = normalized
                    }
                } else if (syncRow?.ackedFingerprint == DELETED_ACK) {
                    tx1AlreadyCommitted = true
                    recoveredOutcome = DriveResolution.OUTCOME_DELETE
                    recoveredBody = null
                }
            }
            DriveConflictChoice.DELETE -> {
                if (syncRow?.ackedFingerprint == DELETED_ACK) {
                    tx1AlreadyCommitted = true
                    recoveredOutcome = DriveResolution.OUTCOME_DELETE
                    recoveredBody = null
                }
            }
            DriveConflictChoice.KEEP_LOCAL,
            DriveConflictChoice.KEEP_EDITED,
            -> {
                val liveContentFp = liveRow?.let { row ->
                    val sha = identities.fingerprint(row.bookId)?.sha256?.lowercase(Locale.ROOT)
                    DriveWireCodec.annotationContentFp(toWireBody(row, workKey, sha))
                }
                val priorContentFp = existingState?.contentFp
                val priorBaselineFp = existingState?.let { baselineContentFp(it, workKey) }
                if (priorContentFp != null &&
                    syncRow?.ackedFingerprint == priorContentFp &&
                    (liveRow == null || (liveContentFp != priorContentFp && liveContentFp != priorBaselineFp))
                ) {
                    val priorBody = existingState.bodyJson?.let { raw ->
                        runCatching { DriveWireCodec.decodeAnnotationBody(JSONObject(raw)) }.getOrNull()
                    }
                    if (priorBody != null) {
                        tx1AlreadyCommitted = true
                        recoveredOutcome = DriveResolution.OUTCOME_PUT
                        recoveredBody = priorBody
                    }
                }
            }
        }

        if (tx1AlreadyCommitted) {
            val now = clock()
            val hlc = nextHlc()
            val chosenBodyMap = recoveredBody?.let(DriveWireCodec::encodeAnnotationBody)
            val chosenContentFp = recoveredBody?.let(DriveWireCodec::annotationContentFp)
            val (resolution, state) = DriveConflictEngine.buildLocalResolution(
                permissionId = permissionId,
                conflict = conflict,
                outcome = recoveredOutcome,
                chosenBodyMap = chosenBodyMap,
                chosenContentFp = chosenContentFp,
                existingState = existingState,
                hlc = hlc,
                localDeviceId = localDeviceId,
                now = now,
            )
            val wire = DriveWireCodec.resolveRecord(
                domain = DriveWireCodec.DOMAIN_ANNOTATION,
                key = conflict.recordKey,
                outcome = recoveredOutcome,
                resolvedFps = DriveWorkIdentity.decodeStringArray(resolution.resolvedFpsJson),
                hlc = hlc,
                bodyMap = chosenBodyMap,
                ancestors = DriveWorkIdentity.decodeStringArray(state.ancestorsJson),
                resRefs = DriveWorkIdentity.decodeStringArray(state.resRefsJson),
                explicitFp = resolution.resolutionFp,
            )
            store.withTransaction {
                if (!DriveConflictEngine.freezeRecoveredResolutionOutbox(
                    store = store,
                    permissionId = permissionId,
                    localDeviceId = localDeviceId,
                    wireRecord = wire,
                    createdHlc = hlc,
                    now = now,
                )) return@withTransaction
                resolutions.upsert(resolution)
                recordStates.upsert(state)
                conflicts.markResolved(
                    permissionId = permissionId,
                    conflictId = conflict.conflictId,
                    choice = choice.name,
                    resolutionFp = resolution.resolutionFp,
                    resolvedAt = now,
                )
            }
            return
        }

        // Without a persisted choice, a note deleted since must not be restored from the remote side.
        if (choice == DriveConflictChoice.KEEP_EDITED &&
            conflict.kind == DriveConflict.KIND_EDIT_EDIT &&
            liveRow == null &&
            remoteBody != null
        ) {
            return
        }

        resolveLocalConflict(permissionId, conflict, choice, localDeviceId, nextHlc)
    }

    private fun baselineContentFp(state: DriveRecordState, workKey: String): String? {
        if (state.contentFp == null) return null
        val body = state.bodyJson?.let { raw ->
            runCatching { DriveWireCodec.decodeAnnotationBody(JSONObject(raw)) }.getOrNull()
        } ?: return state.contentFp
        return DriveWireCodec.annotationContentFp(body.copy(workKey = workKey))
    }

    private suspend fun buildLocalRecordView(
        permissionId: String,
        recordKey: String,
        liveRow: BookAnnotation?,
        syncRow: AnnotationSync?,
        workKey: String,
        existingState: DriveRecordState?,
        existingResolutions: List<DriveResolution>,
        incoming: DriveWireRecord,
        incomingContentFp: String?,
        isIncomingDelete: Boolean,
        nextHlc: suspend () -> String,
    ): LocalRecordView {
        val liveBody = liveRow?.let { row ->
            val sha = identities.fingerprint(row.bookId)?.sha256?.lowercase(Locale.ROOT)
            toWireBody(row, workKey, sha)
        }
        val liveContentFp = liveBody?.let(DriveWireCodec::annotationContentFp)
        val liveBodyJson = liveBody?.let { DriveCanonicalJson.encode(DriveWireCodec.encodeAnnotationBody(it)) }

        val stateAnc = DriveWorkIdentity.decodeStringArray(existingState?.ancestorsJson)
        val incomingKnownAncestorOrRetired = incoming.fp in stateAnc ||
            existingResolutions.any { incoming.fp in DriveConflictEngine.decodeFps(it.resolvedFpsJson) }
        val expectedAckForIncoming = if (isIncomingDelete) DELETED_ACK else incomingContentFp
        val postCrashAckMatchesIncoming = !incomingKnownAncestorOrRetired &&
            expectedAckForIncoming != null &&
            syncRow?.ackedFingerprint == expectedAckForIncoming &&
            (existingState == null || existingState.versionFp != incoming.fp)
        val postCrashLocalEdit = postCrashAckMatchesIncoming && (
            (isIncomingDelete && liveRow != null) ||
                (!isIncomingDelete && liveContentFp != incomingContentFp)
            )

        if (existingState == null) {
            if (liveRow == null && syncRow == null) {
                return LocalRecordView(versionFp = null)
            }
            if (liveRow != null && liveContentFp != null) {
                return LocalRecordView(
                    versionFp = liveContentFp,
                    versionOp = DriveRecordState.OP_PUT,
                    isLivePut = true,
                    publishedFp = null,
                    contentFp = liveContentFp,
                    bodyJson = liveBodyJson,
                    localRowMatchesContentFp = true,
                    postCrashAckMatchesIncoming = postCrashAckMatchesIncoming,
                    hasPostCrashLocalEdit = postCrashLocalEdit,
                )
            }
            // Deleted without record_state (e.g., crash after Tx 1 delete).
            val hlc = nextHlc()
            val delFp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_ANNOTATION, recordKey, hlc)
            return LocalRecordView(
                versionFp = delFp,
                versionOp = DriveRecordState.OP_DEL,
                isLivePut = false,
                publishedFp = null,
                contentFp = null,
                lastHlc = hlc,
                postCrashAckMatchesIncoming = postCrashAckMatchesIncoming,
                hasPostCrashLocalEdit = postCrashLocalEdit,
            )
        }

        val baselineFp = baselineContentFp(existingState, workKey)
        val stateResRefs = DriveWorkIdentity.decodeStringArray(existingState.resRefsJson)
        val stateIsLivePut = when (existingState.versionOp) {
            DriveRecordState.OP_PUT -> true
            DriveRecordState.OP_DEL -> false
            DriveRecordState.OP_RESOLVE -> {
                val res = existingResolutions.find { it.resolutionFp == existingState.versionFp }
                res?.outcome == DriveResolution.OUTCOME_PUT || existingState.bodyJson != null
            }
            else -> existingState.bodyJson != null
        }

        // Detect an unpublished local edit made after `existingState` was persisted.
        if (liveRow != null && liveContentFp != null && liveContentFp != baselineFp) {
            val editAnc = (listOf(existingState.versionFp) + stateAnc)
                .distinct()
                .filter { it != liveContentFp }
                .take(DriveWireCodec.MAX_ANCESTORS)
            return LocalRecordView(
                versionFp = liveContentFp,
                versionOp = DriveRecordState.OP_PUT,
                isLivePut = true,
                publishedFp = existingState.publishedFp,
                contentFp = liveContentFp,
                originDeviceId = null,
                originSeq = null,
                ancestors = editAnc,
                resRefs = stateResRefs,
                lastHlc = existingState.lastHlc,
                bodyJson = liveBodyJson,
                localRowMatchesContentFp = true,
                postCrashAckMatchesIncoming = postCrashAckMatchesIncoming,
                hasPostCrashLocalEdit = postCrashLocalEdit,
            )
        }

        // Detect an unpublished local deletion made after `existingState` was live.
        if (liveRow == null && stateIsLivePut && (!postCrashAckMatchesIncoming || postCrashLocalEdit)) {
            val hlc = nextHlc()
            val delFp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_ANNOTATION, recordKey, hlc)
            val delAnc = (listOf(existingState.versionFp) + stateAnc)
                .distinct()
                .take(DriveWireCodec.MAX_ANCESTORS)
            return LocalRecordView(
                versionFp = delFp,
                versionOp = DriveRecordState.OP_DEL,
                isLivePut = false,
                publishedFp = existingState.publishedFp,
                contentFp = null,
                originDeviceId = null,
                originSeq = null,
                ancestors = delAnc,
                resRefs = stateResRefs,
                lastHlc = hlc,
                bodyJson = null,
                localRowMatchesContentFp = true,
                postCrashAckMatchesIncoming = postCrashAckMatchesIncoming,
                hasPostCrashLocalEdit = postCrashLocalEdit,
            )
        }

        val matches = (stateIsLivePut && liveRow != null && liveContentFp == baselineFp) ||
            (!stateIsLivePut && liveRow == null)

        return LocalRecordView(
            versionFp = existingState.versionFp,
            versionOp = existingState.versionOp,
            isLivePut = stateIsLivePut,
            publishedFp = existingState.publishedFp,
            contentFp = baselineFp,
            originDeviceId = existingState.originDeviceId,
            originSeq = existingState.originSeq,
            ancestors = stateAnc,
            resRefs = stateResRefs,
            lastHlc = existingState.lastHlc,
            bodyJson = existingState.bodyJson,
            localRowMatchesContentFp = matches,
            postCrashAckMatchesIncoming = postCrashAckMatchesIncoming,
            hasPostCrashLocalEdit = postCrashLocalEdit,
        )
    }

    private suspend fun findBookUrlForWorkKey(permissionId: String, canonicalWorkKey: String): String? =
        workIdentity.liveBookUrlFor(permissionId, canonicalWorkKey)

    internal fun toWireBody(
        annotation: BookAnnotation,
        workKey: String,
        editionSha: String?,
    ): DriveAnnotationBody = DriveAnnotationBody(
        id = annotation.id,
        workKey = workKey,
        editionSha = editionSha?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() },
        kind = annotation.kind,
        locatorJson = if (annotation.kind == AnnotationKind.BOOK_NOTE.name) {
            ""
        } else {
            DriveCanonicalJson.canonicalizeJsonString(annotation.locatorJson)
        },
        text = annotation.text,
        note = annotation.note,
        tint = annotation.tint,
        chapter = annotation.chapter,
        totalProgression = annotation.totalProgression,
        createdAt = annotation.createdAt,
        noteCreatedAt = annotation.noteCreatedAt,
        noteUpdatedAt = annotation.noteUpdatedAt,
        updatedAt = annotation.updatedAt,
    )

    companion object {
        const val DELETED_ACK = "DELETED"
    }
}
