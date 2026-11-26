package com.chmouel.liseur.drive

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.BookEvaluation
import com.chmouel.liseur.data.db.EvaluationSync
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.domain.PersonalEvaluation
import org.json.JSONObject

/** Outbound `evaluation` records paired with atomic `evaluation_sync` revision ACK metadata. */
internal data class DriveEvaluationOutboundBundle(
    val records: List<DriveWireRecord>,
    val acks: List<DriveEvaluationAckMeta>,
)

/**
 * Google Drive sync adapter for book ratings & reviews (`docs/DRIVE_SYNC.md` §4.8)
 * using ADR-0078's exact revision-to-fingerprint adapter on `evaluation_sync`.
 */
internal class DriveEvaluationSync(
    private val database: LiseurDatabase,
    private val store: DriveSyncDatabase,
    private val workIdentity: DriveWorkIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val books get() = database.bookDao()
    private val evaluations get() = database.bookEvaluationDao()
    private val identities get() = database.workIdentityDao()
    private val recordStates get() = store.recordStates()
    private val resolutions get() = store.resolutions()
    private val conflicts get() = store.conflicts()

    suspend fun collectDirtyRecords(
        permissionId: String,
        bookUrlFilter: String? = null,
        nextHlc: suspend () -> String,
    ): DriveEvaluationOutboundBundle {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val records = mutableListOf<DriveWireRecord>()
        val acks = mutableListOf<DriveEvaluationAckMeta>()
        val emittedKeys = mutableSetOf<String>()

        // 1. Emit any queued unpublished `op: "resolve"` records first.
        for (state in recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_EVALUATION)) {
            if (state.versionOp != DriveRecordState.OP_RESOLVE) continue
            if (state.publishedFp == state.versionFp) continue
            // A held version was not minted here: this device never owed it as a publication.
            if (state.held) continue
            val res = resolutions.get(permissionId, DriveWireCodec.DOMAIN_EVALUATION, state.recordKey, state.versionFp)
                ?: continue
            val (rawWorkKey, field) = parseRecordKey(state.recordKey) ?: continue
            val workKey = workIdentity.canonicalWorkKey(permissionId, rawWorkKey)
            val bookUrl = findBookUrlForWorkKey(permissionId, workKey)
            if (bookUrlFilter != null && bookUrl != null && bookUrl != bookUrlFilter) continue

            val bodyMap = if (res.outcome == DriveResolution.OUTCOME_PUT) {
                state.bodyJson?.let { raw ->
                    runCatching { DriveWireCodec.decodeEvaluationBody(JSONObject(raw)) }
                        .getOrNull()
                        ?.let(DriveWireCodec::encodeEvaluationBody)
                } ?: continue
            } else {
                null
            }
            val wire = DriveWireCodec.resolveRecord(
                domain = DriveWireCodec.DOMAIN_EVALUATION,
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
            emittedKeys.add(state.recordKey)
            if (bookUrl != null) {
                val targetRev = state.lastSyncedRevision ?: evaluations.get(bookUrl, field)?.revision ?: 0L
                acks.add(
                    DriveEvaluationAckMeta(
                        bookUrl = bookUrl,
                        field = field,
                        workKey = workKey,
                        targetRevision = targetRev,
                        recordKey = state.recordKey,
                        recordFp = state.versionFp,
                        contentFp = state.contentFp,
                        recordOp = DriveRecordState.OP_RESOLVE,
                    ),
                )
            }
        }

        // 2. Collect dirty ratings and reviews for eligible books.
        val eligibleBooks = if (bookUrlFilter != null) {
            listOfNotNull(books.getByUrl(bookUrlFilter))
        } else {
            books.allOnce()
        }.filter { it.browseServerId == null }

        for (book in eligibleBooks) {
            val alias = workIdentity.resolveBook(permissionId, book, computeFingerprintIfMissing = false)
                ?.takeIf { it.usable }
                ?: continue
            val workKey = workIdentity.canonicalWorkKey(permissionId, alias.workId)
            for (field in FIELDS) {
                val recordKey = recordKey(workKey, field)
                if (recordKey in emittedKeys) continue
                if (conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_EVALUATION, recordKey).isNotEmpty()) {
                    continue
                }
                val row = evaluations.get(book.url, field)
                val sync = evaluations.state(peerId, book.url, field)
                // Always run revision-to-fp crash recovery before diffing.
                val existingState = recoverFromRevisionSync(
                    permissionId = permissionId,
                    workKey = workKey,
                    field = field,
                    recordKey = recordKey,
                    row = row,
                    sync = sync,
                    nextHlc = nextHlc,
                )

                if (row == null) continue
                // Never publish over a version retained for a work that has not materialized yet:
                // the deferred apply must reconcile against it, not replace it unseen.
                if (existingState?.held == true) continue
                val needsPublish = row.owed(sync) ||
                    (existingState != null && existingState.versionFp != existingState.publishedFp)
                if (!needsPublish) continue

                val value = row.value
                val resRefs = DriveWorkIdentity.decodeStringArray(existingState?.resRefsJson)
                if (value != null) {
                    if (!PersonalEvaluation.valid(field, value)) continue
                    val body = DriveEvaluationBody(
                        workKey = workKey,
                        field = field,
                        value = value,
                        updatedAt = row.updatedAt,
                    )
                    val bodyMap = DriveWireCodec.encodeEvaluationBody(body)
                    val bodyJson = DriveCanonicalJson.encode(bodyMap)
                    val contentFp = DriveCanonicalJson.contentFp(bodyMap)

                    val hlc: String
                    val ancestors: List<String>
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
                                domain = DriveWireCodec.DOMAIN_EVALUATION,
                                recordKey = recordKey,
                                versionFp = contentFp,
                                versionOp = DriveRecordState.OP_PUT,
                                publishedFp = existingState?.publishedFp,
                                contentFp = contentFp,
                                lastSyncedRevision = existingState?.lastSyncedRevision,
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
                            domain = DriveWireCodec.DOMAIN_EVALUATION,
                            key = recordKey,
                            hlc = hlc,
                            fp = contentFp,
                            bodyMap = bodyMap,
                            ancestors = ancestors,
                            resRefs = resRefs,
                        ),
                    )
                    emittedKeys.add(recordKey)
                    acks.add(
                        DriveEvaluationAckMeta(
                            bookUrl = book.url,
                            field = field,
                            workKey = workKey,
                            targetRevision = row.revision,
                            recordKey = recordKey,
                            recordFp = contentFp,
                            contentFp = contentFp,
                            recordOp = DriveRecordState.OP_PUT,
                        ),
                    )
                } else {
                    // Cleared rating or review -> emit `op: "del"` if previously synced or known.
                    if (sync == null && existingState == null) continue
                    val hlc: String
                    val delFp: String
                    val ancestors: List<String>
                    if (existingState != null && existingState.versionOp == DriveRecordState.OP_DEL && existingState.publishedFp != existingState.versionFp) {
                        hlc = existingState.lastHlc.ifBlank { nextHlc() }
                        delFp = existingState.versionFp
                        ancestors = DriveWorkIdentity.decodeStringArray(existingState.ancestorsJson)
                    } else {
                        hlc = nextHlc()
                        delFp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_EVALUATION, recordKey, hlc)
                        ancestors = buildList {
                            existingState?.versionFp?.let(::add)
                            addAll(DriveWorkIdentity.decodeStringArray(existingState?.ancestorsJson))
                        }.distinct().take(DriveWireCodec.MAX_ANCESTORS)
                        recordStates.upsert(
                            DriveRecordState(
                                permissionId = permissionId,
                                domain = DriveWireCodec.DOMAIN_EVALUATION,
                                recordKey = recordKey,
                                versionFp = delFp,
                                versionOp = DriveRecordState.OP_DEL,
                                publishedFp = existingState?.publishedFp,
                                contentFp = null,
                                lastSyncedRevision = existingState?.lastSyncedRevision,
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
                            domain = DriveWireCodec.DOMAIN_EVALUATION,
                            key = recordKey,
                            hlc = hlc,
                            ancestors = ancestors,
                            resRefs = resRefs,
                            explicitFp = delFp,
                        ),
                    )
                    emittedKeys.add(recordKey)
                    acks.add(
                        DriveEvaluationAckMeta(
                            bookUrl = book.url,
                            field = field,
                            workKey = workKey,
                            targetRevision = row.revision,
                            recordKey = recordKey,
                            recordFp = delFp,
                            contentFp = null,
                            recordOp = DriveRecordState.OP_DEL,
                        ),
                    )
                }
            }
        }

        return DriveEvaluationOutboundBundle(records = records, acks = acks)
    }

    /**
     * Commits outgoing delivery ACKs in strict two-database order:
     * Transaction 1 (`liseur.db` `evaluation_sync`) FIRST, then Transaction 2 (`drive_sync.db`).
     */
    suspend fun acknowledgePublished(
        permissionId: String,
        records: List<DriveWireRecord>,
        acks: List<DriveEvaluationAckMeta>,
    ) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val now = clock()
        if (acks.isNotEmpty()) {
            database.withTransaction {
                for (ack in acks) {
                    val existing = evaluations.state(peerId, ack.bookUrl, ack.field)
                    val ackedRev = maxOf(existing?.ackedRevision ?: 0L, ack.targetRevision)
                    val sentJson = DriveWireCodec.encodeRevisionAckJson(
                        DriveRevisionAckPayload(
                            vfp = ack.recordFp,
                            cfp = ack.contentFp,
                            rev = ack.targetRevision,
                            op = ack.recordOp,
                        ),
                    )
                    evaluations.upsertState(
                        EvaluationSync(
                            peerId = peerId,
                            bookUrl = ack.bookUrl,
                            field = ack.field,
                            workId = ack.workKey,
                            ackedRevision = ackedRev,
                            sentJson = sentJson,
                            sentRevision = ack.targetRevision,
                            polledAt = now,
                        ),
                    )
                }
            }
        }
        val acksByKey = acks.associateBy { it.recordKey }
        store.withTransaction {
            for (record in records) {
                if (record.d != DriveWireCodec.DOMAIN_EVALUATION) continue
                val state = recordStates.get(permissionId, DriveWireCodec.DOMAIN_EVALUATION, record.k)
                val ackMeta = acksByKey[record.k]
                if (state != null && state.versionFp == record.fp) {
                    recordStates.upsert(
                        state.copy(
                            publishedFp = record.fp,
                            lastSyncedRevision = ackMeta?.targetRevision ?: state.lastSyncedRevision,
                        ),
                    )
                }
                if (record.op == DriveRecordState.OP_RESOLVE) {
                    for (conflict in conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_EVALUATION, record.k)) {
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

    /** Applies one incoming remote `evaluation` record via ADR-0078 revision-to-fp adapter & [DriveConflictEngine]. */
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
            record.body?.let(DriveWireCodec::decodeEvaluationBody) ?: return true
        } else {
            null
        }
        val (rawWorkKey, field) = if (decodedBody != null) {
            decodedBody.workKey to decodedBody.field
        } else {
            parseRecordKey(record.k) ?: return true
        }
        if (field !in FIELDS) return true

        val canonicalWorkKey = workIdentity.canonicalWorkKey(permissionId, rawWorkKey)
        val canonicalKey = recordKey(canonicalWorkKey, field)
        // A catalog tombstone counts as a known catalog entry, so this record is not deferred.
        // Without a local book there is nothing to write to: retain it as held history (§4).
        val book = findBookUrlForWorkKey(permissionId, canonicalWorkKey)?.let { books.getByUrl(it) }
        val bookUrl = book?.url
        val incomingContentFp = decodedBody?.let {
            DriveCanonicalJson.contentFp(DriveWireCodec.encodeEvaluationBody(it.copy(workKey = canonicalWorkKey)))
        }

        val row = bookUrl?.let { evaluations.get(it, field) }
        val sync = bookUrl?.let { evaluations.state(peerId, it, field) }

        // Always run revision-to-fp crash recovery BEFORE reconciling incoming record.
        val existingState = recoverFromRevisionSync(
            permissionId = permissionId,
            workKey = canonicalWorkKey,
            field = field,
            recordKey = canonicalKey,
            row = row,
            sync = sync,
            nextHlc = nextHlc,
        )
        val existingResolutions = resolutions.forRecord(permissionId, DriveWireCodec.DOMAIN_EVALUATION, canonicalKey)
        val unsettledConflicts = conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_EVALUATION, canonicalKey)

        val localView = if (bookUrl == null) {
            DriveHeldRecords.localView(existingState)
        } else {
            buildLocalRecordView(
                workKey = canonicalWorkKey,
                field = field,
                recordKey = canonicalKey,
                row = row,
                sync = sync,
                // A held row is retained history, not data in this book, so nothing local matches it.
                existingState = existingState?.takeUnless { it.held },
                existingResolutions = existingResolutions,
                incoming = record,
                incomingContentFp = incomingContentFp,
                isIncomingDelete = isIncomingDelete,
                nextHlc = nextHlc,
                heldReplay = existingState?.held == true,
            )
        }

        val result = DriveConflictEngine.reconcileIncoming(
            permissionId = permissionId,
            domain = DriveWireCodec.DOMAIN_EVALUATION,
            recordKey = canonicalKey,
            bookUrl = bookUrl,
            local = localView,
            existingResolutions = existingResolutions,
            unsettledConflicts = unsettledConflicts,
            incoming = record,
            incomingContentFp = incomingContentFp,
            remoteDeviceId = remoteDeviceId,
            remoteSeq = remoteSeq,
            now = clock(),
        )

        var appliedRev: Long? = null
        var staleLocal = false
        val now = clock()
        // A parked successor can win over the incoming resolution; ack the version actually applied.
        val appliedState = result.updatedRecordState?.takeIf { it.versionFp != record.fp }
        val ackVfp = appliedState?.versionFp ?: record.fp
        val ackOp = appliedState?.versionOp ?: record.op
        var appliedContentFp: String? = null
        // Transaction 1 (`liseur.db`): atomic data + `evaluation_sync` revision-to-fp ack.
        // A held record has no book to write to and only its causal state is retained.
        when (val action = result.dataAction) {
            is ReconciliationDataAction.ApplyPut -> if (bookUrl != null) {
                val body = DriveWireCodec.decodeEvaluationBody(action.body) ?: return true
                val bodyContentFp = DriveCanonicalJson.contentFp(
                    DriveWireCodec.encodeEvaluationBody(body.copy(workKey = canonicalWorkKey)),
                )
                appliedContentFp = bodyContentFp
                database.withTransaction {
                    if (evaluations.get(bookUrl, field)?.revision != row?.revision) {
                        staleLocal = true
                        return@withTransaction
                    }
                    val revApplied = evaluations.nextRevision()
                    val prior = evaluations.get(bookUrl, field)
                    evaluations.upsert(
                        BookEvaluation(
                            bookUrl = bookUrl,
                            field = field,
                            value = body.value,
                            title = book?.title ?: prior?.title ?: "",
                            author = book?.author ?: prior?.author ?: "",
                            revision = revApplied,
                            retiredRevision = prior?.retiredRevision ?: 0L,
                            updatedAt = body.updatedAt,
                        ),
                    )
                    evaluations.upsertState(
                        EvaluationSync(
                            peerId = peerId,
                            bookUrl = bookUrl,
                            field = field,
                            workId = canonicalWorkKey,
                            ackedRevision = revApplied,
                            sentJson = DriveWireCodec.encodeRevisionAckJson(
                                DriveRevisionAckPayload(
                                    vfp = ackVfp,
                                    cfp = bodyContentFp,
                                    rev = revApplied,
                                    op = ackOp,
                                ),
                            ),
                            sentRevision = revApplied,
                            polledAt = now,
                        ),
                    )
                    appliedRev = revApplied
                }
            }
            ReconciliationDataAction.ApplyDelete -> if (bookUrl != null) {
                database.withTransaction {
                    if (evaluations.get(bookUrl, field)?.revision != row?.revision) {
                        staleLocal = true
                        return@withTransaction
                    }
                    val revApplied = evaluations.nextRevision()
                    val prior = evaluations.get(bookUrl, field)
                    evaluations.upsert(
                        BookEvaluation(
                            bookUrl = bookUrl,
                            field = field,
                            value = null,
                            title = book?.title ?: prior?.title ?: "",
                            author = book?.author ?: prior?.author ?: "",
                            revision = revApplied,
                            retiredRevision = prior?.retiredRevision ?: 0L,
                            updatedAt = now,
                        ),
                    )
                    evaluations.upsertState(
                        EvaluationSync(
                            peerId = peerId,
                            bookUrl = bookUrl,
                            field = field,
                            workId = canonicalWorkKey,
                            ackedRevision = revApplied,
                            sentJson = DriveWireCodec.encodeRevisionAckJson(
                                DriveRevisionAckPayload(
                                    vfp = ackVfp,
                                    cfp = null,
                                    rev = revApplied,
                                    op = ackOp,
                                ),
                            ),
                            sentRevision = revApplied,
                            polledAt = now,
                        ),
                    )
                    appliedRev = revApplied
                }
            }
            ReconciliationDataAction.NoDataChange -> Unit
        }

        // Defer: the record replays and reconciles against the reader's newer edit.
        if (staleLocal) return false

        // Transaction 2 (`drive_sync.db`): Step 0 side effects + causal state BEFORE cursor advance.
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
                var persisted = if (appliedRev != null) updated.copy(lastSyncedRevision = appliedRev) else updated
                appliedContentFp?.let { persisted = persisted.copy(contentFp = it) }
                if (bookUrl == null) {
                    persisted = persisted.copy(held = true)
                } else if (existingState?.held == true &&
                    persisted.versionFp == record.fp &&
                    (existingState.versionFp in record.anc ||
                        existingState.versionFp in record.resRefs ||
                        existingState.versionFp in record.resolvedFps)
                ) {
                    persisted = DriveHeldRecords.mergeSupersededLineage(persisted, existingState)
                }
                recordStates.upsert(persisted)
            }
        }
        return true
    }

    /** Applies a reader's conflict choice for an evaluation conflict and queues `op: "resolve"`. */
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

        val (rawWorkKey, field) = parseRecordKey(conflict.recordKey) ?: return
        val workKey = workIdentity.canonicalWorkKey(permissionId, rawWorkKey)
        val bookUrl = conflict.bookUrl ?: findBookUrlForWorkKey(permissionId, workKey) ?: return
        val book = books.getByUrl(bookUrl)
        val row = evaluations.get(bookUrl, field)
        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_EVALUATION, conflict.recordKey)
        val remoteBody = conflict.remoteJson?.let { raw ->
            runCatching { DriveWireCodec.decodeEvaluationBody(JSONObject(raw)) }.getOrNull()
        }

        val outcome: String
        val chosenBody: DriveEvaluationBody?
        when (choice) {
            DriveConflictChoice.DELETE -> {
                outcome = DriveResolution.OUTCOME_DELETE
                chosenBody = null
            }
            DriveConflictChoice.KEEP_LOCAL -> {
                val localVal = row?.value
                if (localVal != null) {
                    outcome = DriveResolution.OUTCOME_PUT
                    chosenBody = DriveEvaluationBody(workKey, field, localVal, row.updatedAt)
                } else {
                    outcome = DriveResolution.OUTCOME_DELETE
                    chosenBody = null
                }
            }
            DriveConflictChoice.KEEP_REMOTE -> {
                if (remoteBody != null) {
                    outcome = DriveResolution.OUTCOME_PUT
                    chosenBody = remoteBody.copy(workKey = workKey)
                } else {
                    outcome = DriveResolution.OUTCOME_DELETE
                    chosenBody = null
                }
            }
            DriveConflictChoice.KEEP_EDITED -> {
                val localVal = row?.value
                if (localVal != null) {
                    outcome = DriveResolution.OUTCOME_PUT
                    chosenBody = DriveEvaluationBody(workKey, field, localVal, row.updatedAt)
                } else if (remoteBody != null) {
                    outcome = DriveResolution.OUTCOME_PUT
                    chosenBody = remoteBody.copy(workKey = workKey)
                } else {
                    outcome = DriveResolution.OUTCOME_DELETE
                    chosenBody = null
                }
            }
        }

        val chosenBodyMap = chosenBody?.let(DriveWireCodec::encodeEvaluationBody)
        val chosenContentFp = chosenBodyMap?.let(DriveCanonicalJson::contentFp)
        val hlc = nextHlc()
        val (resolution, provisionalState) = DriveConflictEngine.buildLocalResolution(
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

        var appliedRev = row?.revision ?: 0L
        // Transaction 1 (`liseur.db`): write chosen evaluation + atomic ack.
        database.withTransaction {
            val revApplied = evaluations.nextRevision()
            val prior = evaluations.get(bookUrl, field)
            evaluations.upsert(
                BookEvaluation(
                    bookUrl = bookUrl,
                    field = field,
                    value = chosenBody?.value,
                    title = book?.title ?: prior?.title ?: "",
                    author = book?.author ?: prior?.author ?: "",
                    revision = revApplied,
                    retiredRevision = prior?.retiredRevision ?: 0L,
                    updatedAt = chosenBody?.updatedAt ?: now,
                ),
            )
            evaluations.upsertState(
                EvaluationSync(
                    peerId = peerId,
                    bookUrl = bookUrl,
                    field = field,
                    workId = workKey,
                    ackedRevision = revApplied,
                    sentJson = DriveWireCodec.encodeRevisionAckJson(
                        DriveRevisionAckPayload(
                            vfp = resolution.resolutionFp,
                            cfp = chosenContentFp,
                            rev = revApplied,
                            op = DriveRecordState.OP_RESOLVE,
                            hlc = hlc,
                            bodyJson = chosenBodyMap?.let(DriveCanonicalJson::encode),
                        ),
                    ),
                    sentRevision = revApplied,
                    polledAt = now,
                ),
            )
            appliedRev = revApplied
        }

        // Transaction 2 (`drive_sync.db`): persist durable resolution & queue `op: "resolve"`.
        store.withTransaction {
            resolutions.upsert(resolution)
            recordStates.upsert(provisionalState.copy(lastSyncedRevision = appliedRev))
            conflicts.upsert(
                conflict.copy(
                    state = DriveConflict.STATE_RESOLVING,
                    choice = choice.name,
                    resolutionFp = resolution.resolutionFp,
                ),
            )
        }
    }

    suspend fun recoverResolvingConflict(
        permissionId: String,
        conflict: DriveConflict,
        localDeviceId: String,
        nextHlc: suspend () -> String,
    ) {
        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_EVALUATION, conflict.recordKey)
        if (existingState != null &&
            existingState.versionOp == DriveRecordState.OP_RESOLVE &&
            existingState.versionFp == conflict.resolutionFp
        ) {
            val res = resolutions.get(permissionId, DriveWireCodec.DOMAIN_EVALUATION, conflict.recordKey, existingState.versionFp)
            if (res != null && existingState.publishedFp == existingState.versionFp) {
                conflicts.markResolved(permissionId, conflict.conflictId, conflict.choice ?: res.outcome, res.resolutionFp, clock())
            }
            return
        }
        val choice = conflict.choice?.let { runCatching { DriveConflictChoice.valueOf(it) }.getOrNull() }
            ?: return

        val (rawWorkKey, field) = parseRecordKey(conflict.recordKey) ?: return
        val workKey = workIdentity.canonicalWorkKey(permissionId, rawWorkKey)
        val bookUrl = conflict.bookUrl ?: findBookUrlForWorkKey(permissionId, workKey) ?: return
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val row = evaluations.get(bookUrl, field)
        val sync = evaluations.state(peerId, bookUrl, field)
        val sentMeta = DriveWireCodec.decodeRevisionAckJson(sync?.sentJson)

        val tx1AlreadyCommitted = sync != null &&
            sentMeta != null &&
            sentMeta.op == DriveRecordState.OP_RESOLVE &&
            (
                existingState == null ||
                    ((existingState.lastSyncedRevision ?: 0L) < sync.ackedRevision && existingState.versionFp != sentMeta.vfp)
                ) &&
            resolutions.get(permissionId, DriveWireCodec.DOMAIN_EVALUATION, conflict.recordKey, sentMeta.vfp) == null

        if (tx1AlreadyCommitted) {
            val remoteBody = conflict.remoteJson?.let { raw ->
                runCatching { DriveWireCodec.decodeEvaluationBody(JSONObject(raw)) }.getOrNull()
            }
            val outcome = if (sentMeta!!.cfp != null) {
                DriveResolution.OUTCOME_PUT
            } else {
                DriveResolution.OUTCOME_DELETE
            }
            val recoveredBody: DriveEvaluationBody? = if (outcome == DriveResolution.OUTCOME_PUT) {
                val candidates = listOfNotNull(
                    sentMeta.bodyJson?.let { decodeBodyJson(it) },
                    row?.takeIf { it.revision == sync!!.ackedRevision && it.value != null }
                        ?.let { DriveEvaluationBody(workKey, field, it.value!!, it.updatedAt) },
                    conflict.remoteJson?.let { decodeBodyJson(it)?.copy(workKey = workKey) },
                )
                // Never publish a body that does not hash to the fingerprint Tx 1 acknowledged.
                candidates.firstOrNull { body ->
                    DriveCanonicalJson.contentFp(DriveWireCodec.encodeEvaluationBody(body)) == sentMeta.cfp
                } ?: return
            } else {
                null
            }
            val finalOutcome = outcome
            val chosenBodyMap = recoveredBody?.let(DriveWireCodec::encodeEvaluationBody)
            val chosenContentFp = chosenBodyMap?.let(DriveCanonicalJson::contentFp) ?: sentMeta.cfp
            val now = clock()
            val hlc = sentMeta.hlc ?: nextHlc()
            val (resolution, state) = DriveConflictEngine.buildLocalResolution(
                permissionId = permissionId,
                conflict = conflict,
                outcome = finalOutcome,
                chosenBodyMap = chosenBodyMap,
                chosenContentFp = if (finalOutcome == DriveResolution.OUTCOME_PUT) chosenContentFp else null,
                existingState = existingState,
                hlc = hlc,
                localDeviceId = localDeviceId,
                lastSyncedRevision = sync!!.ackedRevision,
                now = now,
                explicitResolutionFp = sentMeta.vfp,
            )
            val wire = DriveWireCodec.resolveRecord(
                domain = DriveWireCodec.DOMAIN_EVALUATION,
                key = conflict.recordKey,
                outcome = finalOutcome,
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

        resolveLocalConflict(permissionId, conflict, choice, localDeviceId, nextHlc)
    }

    private fun decodeBodyJson(raw: String): DriveEvaluationBody? =
        runCatching { DriveWireCodec.decodeEvaluationBody(JSONObject(raw)) }.getOrNull()

    /**
     * Exact ADR-0078 revision-to-fingerprint crash recovery for `evaluation_sync`.
     *
     * Runs whenever `drive_record_state` is missing or `last_synced_revision < sync.acked_revision`:
     * - If `row.revision == sync.acked_revision`: recompute `contentFp` from committed `book_evaluation`
     *   row, pair with `sent_json.vfp`, and repair `drive_record_state`.
     * - If `row.revision > sync.acked_revision`: seed `drive_record_state` with `sent_json.vfp` as
     *   causal baseline at `acked_revision` while keeping the newer local edit dirty (`row.owed(sync) == true`).
     */
    internal suspend fun recoverFromRevisionSync(
        permissionId: String,
        workKey: String,
        field: String,
        recordKey: String,
        row: BookEvaluation?,
        sync: EvaluationSync?,
        nextHlc: suspend () -> String,
    ): DriveRecordState? {
        val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_EVALUATION, recordKey)
        if (sync == null || sync.ackedRevision <= 0L) return existing
        if (existing != null && (existing.lastSyncedRevision ?: 0L) >= sync.ackedRevision) return existing

        val sentMeta = DriveWireCodec.decodeRevisionAckJson(sync.sentJson)
        val priorAnc = buildList {
            existing?.versionFp?.let(::add)
            addAll(DriveWorkIdentity.decodeStringArray(existing?.ancestorsJson))
        }

        if (row != null && row.revision == sync.ackedRevision) {
            // Unedited after crash: recompute contentFp directly from committed liseur.db row.
            val rowValue = row.value
            val recomputedBodyMap = rowValue?.takeIf { PersonalEvaluation.valid(field, it) }?.let { v ->
                DriveWireCodec.encodeEvaluationBody(DriveEvaluationBody(workKey, field, v, row.updatedAt))
            }
            val recomputedCfp = recomputedBodyMap?.let(DriveCanonicalJson::contentFp)
            val vfp = sentMeta?.vfp
                ?: recomputedCfp
                ?: DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_EVALUATION, recordKey, nextHlc())
            val vop = sentMeta?.op ?: if (recomputedCfp != null) DriveRecordState.OP_PUT else DriveRecordState.OP_DEL
            val mergedAnc = (priorAnc + listOfNotNull(vfp))
                .distinct()
                .filter { it != vfp }
                .take(DriveWireCodec.MAX_ANCESTORS)
            val repaired = DriveRecordState(
                permissionId = permissionId,
                domain = DriveWireCodec.DOMAIN_EVALUATION,
                recordKey = recordKey,
                versionFp = vfp,
                versionOp = vop,
                publishedFp = vfp,
                contentFp = recomputedCfp,
                lastSyncedRevision = sync.ackedRevision,
                originDeviceId = existing?.originDeviceId?.takeIf { existing.versionFp == vfp },
                originSeq = existing?.originSeq?.takeIf { existing.versionFp == vfp },
                ancestorsJson = DriveCanonicalJson.encode(mergedAnc),
                resRefsJson = existing?.resRefsJson ?: "[]",
                lastHlc = existing?.lastHlc ?: "",
                bodyJson = recomputedBodyMap?.let(DriveCanonicalJson::encode),
            )
            recordStates.upsert(repaired)
            return repaired
        }

        if (row != null && row.revision > sync.ackedRevision && sentMeta != null) {
            // Reader edited again locally after crash before recovery ran:
            // seed causal baseline with sent_json.vfp at ackedRevision while keeping row dirty.
            val mergedAnc = (listOf(sentMeta.vfp) + priorAnc)
                .distinct()
                .filter { it != sentMeta.vfp }
                .take(DriveWireCodec.MAX_ANCESTORS)
            val seeded = DriveRecordState(
                permissionId = permissionId,
                domain = DriveWireCodec.DOMAIN_EVALUATION,
                recordKey = recordKey,
                versionFp = sentMeta.vfp,
                versionOp = sentMeta.op,
                publishedFp = sentMeta.vfp,
                contentFp = sentMeta.cfp,
                lastSyncedRevision = sync.ackedRevision,
                originDeviceId = null,
                originSeq = null,
                ancestorsJson = DriveCanonicalJson.encode(mergedAnc),
                resRefsJson = existing?.resRefsJson ?: "[]",
                lastHlc = existing?.lastHlc ?: "",
                bodyJson = existing?.bodyJson,
            )
            recordStates.upsert(seeded)
            return seeded
        }

        return existing
    }

    private suspend fun buildLocalRecordView(
        workKey: String,
        field: String,
        recordKey: String,
        row: BookEvaluation?,
        sync: EvaluationSync?,
        existingState: DriveRecordState?,
        existingResolutions: List<DriveResolution>,
        incoming: DriveWireRecord,
        incomingContentFp: String?,
        isIncomingDelete: Boolean,
        nextHlc: suspend () -> String,
        heldReplay: Boolean = false,
    ): LocalRecordView {
        val liveBodyMap = row?.value?.takeIf { PersonalEvaluation.valid(field, it) }?.let { v ->
            DriveWireCodec.encodeEvaluationBody(DriveEvaluationBody(workKey, field, v, row.updatedAt))
        }
        val liveContentFp = liveBodyMap?.let(DriveCanonicalJson::contentFp)
        val liveBodyJson = liveBodyMap?.let(DriveCanonicalJson::encode)
        val sentMeta = DriveWireCodec.decodeRevisionAckJson(sync?.sentJson)
        val postCrashAckMatchesIncoming = sentMeta != null && sentMeta.vfp == incoming.fp
        val hasPostCrashLocalEdit = postCrashAckMatchesIncoming && row != null && sync != null && row.revision > sync.ackedRevision

        if (existingState == null) {
            if (row == null) return LocalRecordView(versionFp = null)
            if (liveContentFp != null) {
                return LocalRecordView(
                    versionFp = liveContentFp,
                    versionOp = DriveRecordState.OP_PUT,
                    isLivePut = true,
                    publishedFp = null,
                    contentFp = liveContentFp,
                    bodyJson = liveBodyJson,
                    localRowMatchesContentFp = true,
                    postCrashAckMatchesIncoming = postCrashAckMatchesIncoming,
                    hasPostCrashLocalEdit = hasPostCrashLocalEdit,
                )
            }
            // A rating the reader cleared is a real revisioned deletion, not absent data. Replaying a
            // retained version must see it as a competing edit instead of overwriting the clear.
            if (heldReplay && row.revision > 0L) {
                return LocalRecordView(
                    versionFp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_EVALUATION, recordKey, nextHlc()),
                    versionOp = DriveRecordState.OP_DEL,
                    isLivePut = false,
                    contentFp = null,
                    bodyJson = null,
                    localRowMatchesContentFp = true,
                )
            }
            return LocalRecordView(versionFp = null)
        }

        val stateAnc = DriveWorkIdentity.decodeStringArray(existingState.ancestorsJson)
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

        // Local unpublished edit since last synced revision.
        val localDirty = row != null && row.owed(sync) &&
            (existingState.lastSyncedRevision == null || row.revision > existingState.lastSyncedRevision)
        if (localDirty) {
            if (liveContentFp != null && liveContentFp != existingState.contentFp) {
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
                    lastSyncedRevision = existingState.lastSyncedRevision,
                    originDeviceId = null,
                    originSeq = null,
                    ancestors = editAnc,
                    resRefs = stateResRefs,
                    lastHlc = existingState.lastHlc,
                    bodyJson = liveBodyJson,
                    localRowMatchesContentFp = true,
                    postCrashAckMatchesIncoming = postCrashAckMatchesIncoming,
                    hasPostCrashLocalEdit = hasPostCrashLocalEdit,
                )
            } else if (row.value == null && stateIsLivePut) {
                val hlc = nextHlc()
                val delFp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_EVALUATION, recordKey, hlc)
                val delAnc = (listOf(existingState.versionFp) + stateAnc)
                    .distinct()
                    .take(DriveWireCodec.MAX_ANCESTORS)
                return LocalRecordView(
                    versionFp = delFp,
                    versionOp = DriveRecordState.OP_DEL,
                    isLivePut = false,
                    publishedFp = existingState.publishedFp,
                    contentFp = null,
                    lastSyncedRevision = existingState.lastSyncedRevision,
                    originDeviceId = null,
                    originSeq = null,
                    ancestors = delAnc,
                    resRefs = stateResRefs,
                    lastHlc = hlc,
                    bodyJson = null,
                    localRowMatchesContentFp = true,
                    postCrashAckMatchesIncoming = postCrashAckMatchesIncoming,
                    hasPostCrashLocalEdit = hasPostCrashLocalEdit,
                )
            }
        }

        val matches = (stateIsLivePut && liveContentFp == existingState.contentFp) ||
            (!stateIsLivePut && (row == null || row.value == null))

        return LocalRecordView(
            versionFp = existingState.versionFp,
            versionOp = existingState.versionOp,
            isLivePut = stateIsLivePut,
            publishedFp = existingState.publishedFp,
            contentFp = existingState.contentFp,
            lastSyncedRevision = existingState.lastSyncedRevision,
            originDeviceId = existingState.originDeviceId,
            originSeq = existingState.originSeq,
            ancestors = stateAnc,
            resRefs = stateResRefs,
            lastHlc = existingState.lastHlc,
            bodyJson = existingState.bodyJson,
            localRowMatchesContentFp = matches,
            postCrashAckMatchesIncoming = postCrashAckMatchesIncoming,
            hasPostCrashLocalEdit = hasPostCrashLocalEdit,
        )
    }

    private suspend fun findBookUrlForWorkKey(permissionId: String, canonicalWorkKey: String): String? =
        workIdentity.liveBookUrlFor(permissionId, canonicalWorkKey)

    companion object {
        val FIELDS = listOf(PersonalEvaluation.RATING, PersonalEvaluation.REVIEW)

        fun recordKey(workKey: String, field: String): String = "$workKey/$field"

        fun parseRecordKey(recordKey: String): Pair<String, String>? {
            val idx = recordKey.lastIndexOf('/')
            if (idx <= 0 || idx >= recordKey.lastIndex) return null
            val workKey = recordKey.substring(0, idx)
            val field = recordKey.substring(idx + 1)
            return if (field in FIELDS) workKey to field else null
        }
    }
}
