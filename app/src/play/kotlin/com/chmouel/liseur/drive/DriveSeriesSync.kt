package com.chmouel.liseur.drive

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.domain.seriesKey
import org.json.JSONObject

/**
 * Google Drive sync adapter for reader-authored series membership and shelf ordering
 * (`docs/DRIVE_SYNC.md` §4.4 & ADR-0078).
 *
 * Compatible with `BookDao.setSeriesOverride` / `clearSeriesOverride` and
 * `SeriesOrderDao.renumber` / `clearOrder`. `series_extra` (server-keyed cache)
 * is intentionally excluded per ADR-0078.
 */
internal class DriveSeriesSync(
    private val database: LiseurDatabase,
    private val store: DriveSyncDatabase,
    private val workIdentity: DriveWorkIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val books get() = database.bookDao()
    private val identities get() = database.workIdentityDao()
    private val recordStates get() = store.recordStates()
    private val resolutions get() = store.resolutions()
    private val conflicts get() = store.conflicts()

    suspend fun collectDirtyRecords(
        permissionId: String,
        bookUrlFilter: String? = null,
        nextHlc: suspend () -> String,
    ): List<DriveWireRecord> {
        val records = mutableListOf<DriveWireRecord>()
        val emittedKeys = mutableSetOf<String>()

        // 1. Emit any queued unpublished `op: "resolve"` records first.
        for (state in recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_SERIES)) {
            if (state.versionOp != DriveRecordState.OP_RESOLVE) continue
            if (state.publishedFp == state.versionFp) continue
            // A held version was not minted here: this device never owed it as a publication.
            if (state.held) continue
            val res = resolutions.get(permissionId, DriveWireCodec.DOMAIN_SERIES, state.recordKey, state.versionFp)
                ?: continue
            val workKey = workIdentity.canonicalWorkKey(permissionId, state.recordKey)
            val bookUrl = findBookUrlForWorkKey(permissionId, workKey)
            if (bookUrlFilter != null && bookUrl != null && bookUrl != bookUrlFilter) continue

            val bodyMap = if (res.outcome == DriveResolution.OUTCOME_PUT) {
                state.bodyJson?.let { raw ->
                    runCatching { DriveWireCodec.decodeSeriesBody(JSONObject(raw)) }
                        .getOrNull()
                        ?.let(DriveWireCodec::encodeSeriesBody)
                } ?: continue
            } else {
                null
            }
            records.add(
                DriveWireCodec.resolveRecord(
                    domain = DriveWireCodec.DOMAIN_SERIES,
                    key = state.recordKey,
                    outcome = res.outcome,
                    resolvedFps = DriveWorkIdentity.decodeStringArray(res.resolvedFpsJson),
                    hlc = state.lastHlc,
                    bodyMap = bodyMap,
                    ancestors = DriveWorkIdentity.decodeStringArray(state.ancestorsJson),
                    resRefs = DriveWorkIdentity.decodeStringArray(state.resRefsJson),
                    explicitFp = state.versionFp,
                ),
            )
            emittedKeys.add(state.recordKey)
        }

        // 2. Collect dirty series overrides across eligible books.
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
            if (workKey in emittedKeys) continue
            if (conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_SERIES, workKey).isNotEmpty()) {
                continue
            }

            val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_SERIES, workKey)
            // Never publish over a version retained for a work that has not materialized yet.
            if (existingState?.held == true) continue
            val existingBody = existingState?.bodyJson?.let {
                runCatching { DriveWireCodec.decodeSeriesBody(JSONObject(it)) }.getOrNull()
            }
            val hasOverride = book.seriesOverridden || book.indexOverridden
            val resRefs = DriveWorkIdentity.decodeStringArray(existingState?.resRefsJson)

            if (hasOverride) {
                val candidate = toWireBody(
                    book = book,
                    workKey = workKey,
                    fallbackUpdatedAt = existingBody?.updatedAt ?: clock(),
                )
                // Avoid updatedAt drift if semantic fields match existingBody.
                val effectiveBody = if (existingBody != null && semanticallyEqual(existingBody, candidate)) {
                    candidate.copy(updatedAt = existingBody.updatedAt)
                } else {
                    candidate
                }
                val bodyMap = DriveWireCodec.encodeSeriesBody(effectiveBody)
                val bodyJson = DriveCanonicalJson.encode(bodyMap)
                val contentFp = DriveCanonicalJson.contentFp(bodyMap)

                if (existingState != null && existingState.contentFp == contentFp && existingState.publishedFp == existingState.versionFp) {
                    continue
                }

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
                            domain = DriveWireCodec.DOMAIN_SERIES,
                            recordKey = workKey,
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
                        domain = DriveWireCodec.DOMAIN_SERIES,
                        key = workKey,
                        hlc = hlc,
                        fp = contentFp,
                        bodyMap = bodyMap,
                        ancestors = ancestors,
                        resRefs = resRefs,
                    ),
                )
                emittedKeys.add(workKey)
            } else {
                // Both series_override and index_override are false: emit `del` only if previously live.
                if (existingState == null) continue
                val wasLive = existingState.versionOp == DriveRecordState.OP_PUT ||
                    (existingState.versionOp == DriveRecordState.OP_RESOLVE && existingState.bodyJson != null)
                if (!wasLive && existingState.publishedFp == existingState.versionFp) continue

                val hlc: String
                val delFp: String
                val ancestors: List<String>
                if (existingState.versionOp == DriveRecordState.OP_DEL && existingState.publishedFp != existingState.versionFp) {
                    hlc = existingState.lastHlc.ifBlank { nextHlc() }
                    delFp = existingState.versionFp
                    ancestors = DriveWorkIdentity.decodeStringArray(existingState.ancestorsJson)
                } else {
                    hlc = nextHlc()
                    delFp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_SERIES, workKey, hlc)
                    ancestors = buildList {
                        add(existingState.versionFp)
                        addAll(DriveWorkIdentity.decodeStringArray(existingState.ancestorsJson))
                    }.distinct().take(DriveWireCodec.MAX_ANCESTORS)
                    recordStates.upsert(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_SERIES,
                            recordKey = workKey,
                            versionFp = delFp,
                            versionOp = DriveRecordState.OP_DEL,
                            publishedFp = existingState.publishedFp,
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
                        domain = DriveWireCodec.DOMAIN_SERIES,
                        key = workKey,
                        hlc = hlc,
                        ancestors = ancestors,
                        resRefs = resRefs,
                        explicitFp = delFp,
                    ),
                )
                emittedKeys.add(workKey)
            }
        }

        return records
    }

    /** Marks published `series` records acknowledged in `drive_record_state` and closes `RESOLVING` conflicts. */
    suspend fun markPublished(permissionId: String, records: List<DriveWireRecord>) {
        val now = clock()
        for (record in records) {
            if (record.d != DriveWireCodec.DOMAIN_SERIES) continue
            val state = recordStates.get(permissionId, DriveWireCodec.DOMAIN_SERIES, record.k)
            if (state != null && state.versionFp == record.fp) {
                recordStates.upsert(state.copy(publishedFp = record.fp))
            }
            if (record.op == DriveRecordState.OP_RESOLVE) {
                for (conflict in conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_SERIES, record.k)) {
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

    /** Applies one incoming remote `series` record (`put`, `del`, or `resolve`) via [DriveConflictEngine]. */
    suspend fun applyRemoteRecord(
        permissionId: String,
        record: DriveWireRecord,
        remoteDeviceId: String,
        remoteSeq: Long,
        nextHlc: suspend () -> String,
    ): Boolean {
        val isIncomingDelete = record.op == DriveRecordState.OP_DEL ||
            (record.op == DriveRecordState.OP_RESOLVE && record.outcome == DriveResolution.OUTCOME_DELETE)
        val decodedBody = if (!isIncomingDelete) {
            record.body?.let(DriveWireCodec::decodeSeriesBody) ?: return true
        } else {
            null
        }
        val rawWorkKey = decodedBody?.workKey ?: record.k
        val canonicalWorkKey = workIdentity.canonicalWorkKey(permissionId, rawWorkKey)
        // A catalog tombstone counts as a known catalog entry, so this record is not deferred.
        // Without a local book there is nothing to write to: retain it as held history (§4).
        val book = findBookUrlForWorkKey(permissionId, canonicalWorkKey)?.let { books.getByUrl(it) }
        val bookUrl = book?.url
        val normalizedBody = decodedBody?.copy(workKey = canonicalWorkKey)
        val incomingContentFp = normalizedBody?.let {
            DriveCanonicalJson.contentFp(DriveWireCodec.encodeSeriesBody(it))
        }

        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_SERIES, canonicalWorkKey)
        val existingResolutions = resolutions.forRecord(permissionId, DriveWireCodec.DOMAIN_SERIES, canonicalWorkKey)
        val unsettledConflicts = conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_SERIES, canonicalWorkKey)

        val localView = if (book == null) {
            DriveHeldRecords.localView(existingState)
        } else {
            buildLocalRecordView(
                book = book,
                workKey = canonicalWorkKey,
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
            domain = DriveWireCodec.DOMAIN_SERIES,
            recordKey = canonicalWorkKey,
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

        var staleLocal = false
        // Transaction 1 (`liseur.db`): apply series columns atomically. A held record has no
        // book to write to and only its causal state is retained.
        var appliedContentFp: String? = null
        when (val action = result.dataAction) {
            is ReconciliationDataAction.ApplyPut -> if (book != null) {
                // A parked successor can win over the incoming resolution, so apply the body the engine chose.
                val body = DriveWireCodec.decodeSeriesBody(action.body)?.copy(workKey = canonicalWorkKey) ?: return true
                appliedContentFp = DriveCanonicalJson.contentFp(DriveWireCodec.encodeSeriesBody(body))
                database.withTransaction {
                    if (books.getByUrl(book.url)?.let(::seriesEditState) != seriesEditState(book)) {
                        staleLocal = true
                        return@withTransaction
                    }
                    applySeriesBodyToBook(book.url, body)
                }
            }
            ReconciliationDataAction.ApplyDelete -> if (book != null) {
                database.withTransaction {
                    if (books.getByUrl(book.url)?.let(::seriesEditState) != seriesEditState(book)) {
                        staleLocal = true
                        return@withTransaction
                    }
                    clearSeriesOverridesOnBook(book.url)
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
                var persisted = appliedContentFp?.let { updated.copy(contentFp = it) } ?: updated
                if (book == null) {
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

    private fun seriesEditState(book: Book): List<Any?> = listOf(
        book.seriesOverridden,
        book.userSeriesName,
        book.indexOverridden,
        book.userSeriesIndex,
        book.userSeriesUpdatedAt,
        book.seriesClaimPending,
        book.seriesClaimReset,
    )

    /** Resolves one `series` conflict (or called once per book for a shelf-grouped resolution). */
    suspend fun resolveLocalConflict(
        permissionId: String,
        conflict: DriveConflict,
        choice: DriveConflictChoice,
        localDeviceId: String,
        nextHlc: suspend () -> String,
    ) {
        val now = clock()
        val workKey = workIdentity.canonicalWorkKey(permissionId, conflict.recordKey)
        val bookUrl = conflict.bookUrl ?: findBookUrlForWorkKey(permissionId, workKey) ?: return
        val book = books.getByUrl(bookUrl) ?: return
        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_SERIES, conflict.recordKey)
        val remoteBody = conflict.remoteJson?.let { raw ->
            runCatching { DriveWireCodec.decodeSeriesBody(JSONObject(raw)) }.getOrNull()
        }
        val localHasOverride = book.seriesOverridden || book.indexOverridden
        store.withTransaction {
            if (!localHasOverride &&
                existingState != null &&
                existingState.versionOp == DriveRecordState.OP_DEL &&
                existingState.bodyJson == null
            ) {
                recordStates.upsert(
                    existingState.copy(
                        bodyJson = encodeDeletedPreConflictBaseline(book.userSeriesUpdatedAt),
                    ),
                )
            }
            conflicts.markResolving(permissionId, conflict.conflictId, choice.name)
        }
        val localBody = if (localHasOverride) toWireBody(book, workKey, now) else null

        val outcome: String
        val chosenBody: DriveSeriesBody?
        when (choice) {
            DriveConflictChoice.DELETE -> {
                outcome = DriveResolution.OUTCOME_DELETE
                chosenBody = null
            }
            DriveConflictChoice.KEEP_LOCAL -> {
                if (localBody != null) {
                    outcome = DriveResolution.OUTCOME_PUT
                    chosenBody = localBody
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
                if (localBody != null) {
                    outcome = DriveResolution.OUTCOME_PUT
                    chosenBody = localBody
                } else if (remoteBody != null) {
                    outcome = DriveResolution.OUTCOME_PUT
                    chosenBody = remoteBody.copy(workKey = workKey)
                } else {
                    outcome = DriveResolution.OUTCOME_DELETE
                    chosenBody = null
                }
            }
        }

        val chosenBodyMap = chosenBody?.let(DriveWireCodec::encodeSeriesBody)
        val chosenContentFp = chosenBodyMap?.let(DriveCanonicalJson::contentFp)
        val hlc = nextHlc()

        // Transaction 1 (`liseur.db`): apply chosen series state.
        database.withTransaction {
            if (chosenBody != null) {
                applySeriesBodyToBook(bookUrl, chosenBody)
            } else {
                clearSeriesOverridesOnBook(bookUrl)
            }
        }

        // Transaction 2 (`drive_sync.db`): persist durable resolution & queue `op: "resolve"`.
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
        store.withTransaction {
            resolutions.upsert(resolution)
            recordStates.upsert(state)
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
        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_SERIES, conflict.recordKey)
        if (existingState != null &&
            existingState.versionOp == DriveRecordState.OP_RESOLVE &&
            existingState.versionFp == conflict.resolutionFp
        ) {
            val res = resolutions.get(permissionId, DriveWireCodec.DOMAIN_SERIES, conflict.recordKey, existingState.versionFp)
            if (res != null && existingState.publishedFp == existingState.versionFp) {
                conflicts.markResolved(permissionId, conflict.conflictId, conflict.choice ?: res.outcome, res.resolutionFp, clock())
            }
            return
        }
        val choice = conflict.choice?.let { runCatching { DriveConflictChoice.valueOf(it) }.getOrNull() }
            ?: return

        val workKey = workIdentity.canonicalWorkKey(permissionId, conflict.recordKey)
        val bookUrl = conflict.bookUrl ?: findBookUrlForWorkKey(permissionId, workKey) ?: return
        val book = books.getByUrl(bookUrl) ?: return
        val existingBody = existingState?.bodyJson?.let { raw ->
            runCatching { DriveWireCodec.decodeSeriesBody(JSONObject(raw)) }.getOrNull()
        }
        val remoteBody = conflict.remoteJson?.let { raw ->
            runCatching { DriveWireCodec.decodeSeriesBody(JSONObject(raw)) }.getOrNull()
        }
        val localHasOverride = book.seriesOverridden || book.indexOverridden
        val currentBody = if (localHasOverride) toWireBody(book, workKey, existingBody?.updatedAt ?: clock()) else null
        val localWasLive = existingState != null && (
            existingState.versionOp == DriveRecordState.OP_PUT ||
                (existingState.versionOp == DriveRecordState.OP_RESOLVE && existingState.bodyJson != null)
            )
        val localMatchesPreConflict = if (localWasLive) {
            currentBody != null &&
                existingBody != null &&
                semanticallyEqual(currentBody, existingBody) &&
                currentBody.updatedAt == existingBody.updatedAt
        } else {
            if (localHasOverride) {
                false
            } else {
                val baseline = decodeDeletedPreConflictBaseline(existingState?.bodyJson)
                if (baseline.present) {
                    book.userSeriesUpdatedAt == baseline.userSeriesUpdatedAt
                } else {
                    book.userSeriesUpdatedAt == null || book.userSeriesUpdatedAt <= conflict.openedAt
                }
            }
        }

        if (!localMatchesPreConflict) {
            val (recoveredOutcome, recoveredBody) = when (choice) {
                DriveConflictChoice.DELETE -> DriveResolution.OUTCOME_DELETE to null
                DriveConflictChoice.KEEP_REMOTE -> if (remoteBody != null) {
                    DriveResolution.OUTCOME_PUT to remoteBody.copy(workKey = workKey)
                } else {
                    DriveResolution.OUTCOME_DELETE to null
                }
                DriveConflictChoice.KEEP_LOCAL,
                DriveConflictChoice.KEEP_EDITED,
                -> when {
                    existingBody != null -> DriveResolution.OUTCOME_PUT to existingBody
                    currentBody != null -> DriveResolution.OUTCOME_PUT to currentBody
                    else -> DriveResolution.OUTCOME_DELETE to null
                }
            }
            val now = clock()
            val hlc = nextHlc()
            val chosenBodyMap = recoveredBody?.let(DriveWireCodec::encodeSeriesBody)
            val chosenContentFp = chosenBodyMap?.let(DriveCanonicalJson::contentFp)
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
                domain = DriveWireCodec.DOMAIN_SERIES,
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

        resolveLocalConflict(permissionId, conflict, choice, localDeviceId, nextHlc)
    }

    private suspend fun applySeriesBodyToBook(bookUrl: String, body: DriveSeriesBody) {
        val current = books.getByUrl(bookUrl) ?: return
        val effectiveName = if (body.seriesOverridden) {
            body.userSeriesName
        } else {
            current.catalogSeriesName ?: current.fileSeriesName
        }
        val effectiveIndex = when {
            effectiveName == null -> null
            body.indexOverridden -> body.userSeriesIndex
            body.seriesOverridden -> null
            else -> current.catalogSeriesIndex ?: current.fileSeriesIndex
        }
        books.upsert(
            current.copy(
                seriesOverridden = body.seriesOverridden,
                userSeriesName = if (body.seriesOverridden) body.userSeriesName else null,
                indexOverridden = body.indexOverridden,
                userSeriesIndex = if (body.indexOverridden) body.userSeriesIndex else null,
                userSeriesUpdatedAt = body.updatedAt,
                seriesClaimPending = false,
                seriesClaimReset = false,
                seriesName = effectiveName,
                seriesIndex = effectiveIndex,
                seriesId = if (body.seriesOverridden || body.indexOverridden) null else current.seriesId,
            ),
        )
    }

    private suspend fun clearSeriesOverridesOnBook(bookUrl: String) {
        val current = books.getByUrl(bookUrl) ?: return
        val effectiveName = current.catalogSeriesName ?: current.fileSeriesName
        val effectiveIndex = if (effectiveName == null) {
            null
        } else {
            current.catalogSeriesIndex ?: current.fileSeriesIndex
        }
        books.upsert(
            current.copy(
                seriesOverridden = false,
                userSeriesName = null,
                indexOverridden = false,
                userSeriesIndex = null,
                userSeriesUpdatedAt = clock(),
                seriesClaimPending = false,
                seriesClaimReset = false,
                seriesName = effectiveName,
                seriesIndex = effectiveIndex,
            ),
        )
    }

    private suspend fun buildLocalRecordView(
        book: Book,
        workKey: String,
        existingState: DriveRecordState?,
        existingResolutions: List<DriveResolution>,
        incoming: DriveWireRecord,
        incomingContentFp: String?,
        isIncomingDelete: Boolean,
        nextHlc: suspend () -> String,
        heldReplay: Boolean = false,
    ): LocalRecordView {
        val hasOverride = book.seriesOverridden || book.indexOverridden
        val existingBody = existingState?.bodyJson?.let {
            runCatching { DriveWireCodec.decodeSeriesBody(JSONObject(it)) }.getOrNull()
        }
        val liveBody = if (hasOverride) {
            val raw = toWireBody(book, workKey, existingBody?.updatedAt ?: clock())
            if (existingBody != null && semanticallyEqual(existingBody, raw)) {
                raw.copy(updatedAt = existingBody.updatedAt)
            } else {
                raw
            }
        } else {
            null
        }
        val liveBodyMap = liveBody?.let(DriveWireCodec::encodeSeriesBody)
        val liveContentFp = liveBodyMap?.let(DriveCanonicalJson::contentFp)
        val liveBodyJson = liveBodyMap?.let(DriveCanonicalJson::encode)

        val stateAnc = DriveWorkIdentity.decodeStringArray(existingState?.ancestorsJson)
        val incomingKnownAncestorOrRetired = incoming.fp in stateAnc ||
            existingResolutions.any { incoming.fp in DriveConflictEngine.decodeFps(it.resolvedFpsJson) }

        // Crash recovery for tables without per-peer ack: recomputed contentFp(book) == incomingContentFp -> Agreement.
        val postCrashAgreement = !incomingKnownAncestorOrRetired && (
            (!isIncomingDelete && incomingContentFp != null && liveContentFp == incomingContentFp) ||
                (isIncomingDelete && !hasOverride && existingState != null)
            )

        if (existingState == null) {
            if (!hasOverride) {
                // A series the reader reset is a real recorded deletion, not absent data. Replaying
                // a retained version must see it as a competing edit instead of reinstating it.
                if (heldReplay && (book.userSeriesUpdatedAt ?: 0L) > 0L) {
                    return LocalRecordView(
                        versionFp = DriveCanonicalJson.delFp(
                            DriveWireCodec.DOMAIN_SERIES,
                            workKey,
                            nextHlc(),
                        ),
                        versionOp = DriveRecordState.OP_DEL,
                        isLivePut = false,
                        contentFp = null,
                        bodyJson = null,
                        localRowMatchesContentFp = true,
                    )
                }
                return LocalRecordView(versionFp = null)
            }
            return LocalRecordView(
                versionFp = liveContentFp,
                versionOp = DriveRecordState.OP_PUT,
                isLivePut = true,
                publishedFp = null,
                contentFp = liveContentFp,
                bodyJson = liveBodyJson,
                localRowMatchesContentFp = true,
                postCrashAckMatchesIncoming = postCrashAgreement,
            )
        }

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

        // Local unpublished edit since existingState.
        if (hasOverride && liveContentFp != null && liveContentFp != existingState.contentFp) {
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
                postCrashAckMatchesIncoming = postCrashAgreement && incoming.fp != liveContentFp,
            )
        }

        // Local unpublished clear/reset since existingState was live.
        if (!hasOverride && stateIsLivePut) {
            val hlc = nextHlc()
            val delFp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_SERIES, workKey, hlc)
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
                postCrashAckMatchesIncoming = false,
            )
        }

        val matches = (stateIsLivePut && hasOverride && liveContentFp == existingState.contentFp) ||
            (!stateIsLivePut && !hasOverride)

        return LocalRecordView(
            versionFp = existingState.versionFp,
            versionOp = existingState.versionOp,
            isLivePut = stateIsLivePut,
            publishedFp = existingState.publishedFp,
            contentFp = existingState.contentFp,
            originDeviceId = existingState.originDeviceId,
            originSeq = existingState.originSeq,
            ancestors = stateAnc,
            resRefs = stateResRefs,
            lastHlc = existingState.lastHlc,
            bodyJson = existingState.bodyJson,
            localRowMatchesContentFp = matches,
            postCrashAckMatchesIncoming = postCrashAgreement && existingState.versionFp != incoming.fp,
        )
    }

    internal fun toWireBody(book: Book, workKey: String, fallbackUpdatedAt: Long): DriveSeriesBody {
        val effectiveName = if (book.seriesOverridden) {
            book.userSeriesName
        } else {
            book.seriesName ?: book.catalogSeriesName ?: book.fileSeriesName
        }
        return DriveSeriesBody(
            workKey = workKey,
            seriesOverridden = book.seriesOverridden,
            userSeriesName = if (book.seriesOverridden) book.userSeriesName else null,
            indexOverridden = book.indexOverridden,
            userSeriesIndex = if (book.indexOverridden) book.userSeriesIndex else null,
            seriesKey = seriesKey(effectiveName),
            updatedAt = book.userSeriesUpdatedAt ?: fallbackUpdatedAt,
        )
    }

    private data class DeletedPreConflictBaseline(
        val present: Boolean,
        val userSeriesUpdatedAt: Long?,
    )

    private fun encodeDeletedPreConflictBaseline(userSeriesUpdatedAt: Long?): String =
        JSONObject()
            .put("deleted", true)
            .put("userSeriesUpdatedAt", userSeriesUpdatedAt ?: JSONObject.NULL)
            .toString()

    private fun decodeDeletedPreConflictBaseline(raw: String?): DeletedPreConflictBaseline {
        if (raw.isNullOrBlank()) return DeletedPreConflictBaseline(present = false, userSeriesUpdatedAt = null)
        return runCatching {
            val obj = JSONObject(raw)
            if (!obj.optBoolean("deleted", false)) {
                DeletedPreConflictBaseline(present = false, userSeriesUpdatedAt = null)
            } else {
                val ts = if (obj.isNull("userSeriesUpdatedAt")) null else obj.getLong("userSeriesUpdatedAt")
                DeletedPreConflictBaseline(present = true, userSeriesUpdatedAt = ts)
            }
        }.getOrElse { DeletedPreConflictBaseline(present = false, userSeriesUpdatedAt = null) }
    }

    private fun semanticallyEqual(a: DriveSeriesBody, b: DriveSeriesBody): Boolean =
        a.workKey == b.workKey &&
            a.seriesOverridden == b.seriesOverridden &&
            a.userSeriesName == b.userSeriesName &&
            a.indexOverridden == b.indexOverridden &&
            a.userSeriesIndex == b.userSeriesIndex &&
            a.seriesKey == b.seriesKey

    private suspend fun findBookUrlForWorkKey(permissionId: String, canonicalWorkKey: String): String? =
        workIdentity.liveBookUrlFor(permissionId, canonicalWorkKey)
}
