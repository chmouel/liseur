package com.chmouel.liseur.drive

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.data.db.MetadataOverrideRevision
import com.chmouel.liseur.data.db.MetadataOverrideSync
import java.io.File
import java.util.Locale
import org.json.JSONObject

/** Outbound `metadata` records paired with atomic `metadata_override_sync` revision ACK metadata. */
internal data class DriveMetadataOutboundBundle(
    val records: List<DriveWireRecord>,
    val acks: List<DriveMetadataAckMeta>,
    val warnings: List<String> = emptyList(),
)

/**
 * Google Drive sync adapter for metadata overrides (`title`, `author`, `description`)
 * and content-addressed custom covers (`cover-<sha256>.webp`) across the entire
 * catalog — including `DownloadState.REMOTE` `drive:<workKey>` rows
 * (`docs/DRIVE_SYNC.md` §2.5, §4.3 & ADR-0078).
 */
internal class DriveMetadataSync(
    private val database: LiseurDatabase,
    private val store: DriveSyncDatabase,
    private val workIdentity: DriveWorkIdentity,
    private val coverCodec: DriveCoverCodec,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val books get() = database.bookDao()
    private val overrides get() = database.bookMetadataOverrideDao()
    private val identities get() = database.workIdentityDao()
    private val recordStates get() = store.recordStates()
    private val resolutions get() = store.resolutions()
    private val conflicts get() = store.conflicts()

    suspend fun collectDirtyRecords(
        permissionId: String,
        accessToken: String,
        bookUrlFilter: String? = null,
        nextHlc: suspend () -> String,
    ): DriveMetadataOutboundBundle {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val records = mutableListOf<DriveWireRecord>()
        val acks = mutableListOf<DriveMetadataAckMeta>()
        val warnings = mutableListOf<String>()
        val emittedKeys = mutableSetOf<String>()

        // 1. Emit queued unpublished `op: "resolve"` records first.
        for (state in recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_METADATA)) {
            if (state.versionOp != DriveRecordState.OP_RESOLVE) continue
            if (state.publishedFp == state.versionFp) continue
            // A held version was not minted here: this device never owed it as a publication.
            if (state.held) continue
            val res = resolutions.get(permissionId, DriveWireCodec.DOMAIN_METADATA, state.recordKey, state.versionFp)
                ?: continue
            val (rawWorkKey, field) = parseRecordKey(state.recordKey) ?: continue
            val workKey = workIdentity.canonicalWorkKey(permissionId, rawWorkKey)
            val bookUrl = findBookUrlForWorkKey(permissionId, workKey)
            if (bookUrlFilter != null && bookUrl != null && bookUrl != bookUrlFilter) continue

            val decodedBody = if (res.outcome == DriveResolution.OUTCOME_PUT) {
                state.bodyJson?.let { raw ->
                    runCatching { DriveWireCodec.decodeMetadataBody(JSONObject(raw)) }.getOrNull()
                } ?: continue
            } else {
                null
            }

            // Ensure custom cover blob is uploaded BEFORE emitting a resolve(put) for cover.
            if (decodedBody is DriveMetadataBody.Cover) {
                val localFile = coverCodec.localCoverFile(decodedBody.coverSha256)
                if (localFile.isFile) {
                    val bytes = localFile.readBytes()
                    if (DriveCanonicalJson.sha256Hex(bytes) == decodedBody.coverSha256) {
                        coverCodec.ensureUploaded(
                            permissionId = permissionId,
                            accessToken = accessToken,
                            encoded = DriveCoverEncodeResult.Encoded(
                                webpBytes = bytes,
                                sha256 = decodedBody.coverSha256,
                                width = decodedBody.width,
                                height = decodedBody.height,
                                sizeBytes = bytes.size.toLong(),
                            ),
                        )
                    }
                }
            }

            val bodyMap = decodedBody?.let(DriveWireCodec::encodeMetadataBody)
            val wire = DriveWireCodec.resolveRecord(
                domain = DriveWireCodec.DOMAIN_METADATA,
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
                val targetRev = state.lastSyncedRevision ?: overrides.revision(bookUrl, field)?.revision ?: 0L
                val coverBody = decodedBody as? DriveMetadataBody.Cover
                acks.add(
                    DriveMetadataAckMeta(
                        bookUrl = bookUrl,
                        field = field,
                        workKey = workKey,
                        targetRevision = targetRev,
                        recordKey = state.recordKey,
                        recordFp = state.versionFp,
                        contentFp = state.contentFp,
                        recordOp = DriveRecordState.OP_RESOLVE,
                        remoteCoverSha256 = coverBody?.coverSha256,
                        remoteCoverUpdatedAt = coverBody?.updatedAt ?: 0L,
                    ),
                )
            }
        }

        // 2. Collect dirty overrides across ALL eligible books (including REMOTE `drive:<workKey>` rows).
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

            for (field in MetadataOverrideFields.ALL_FIELDS) {
                val recordKey = recordKey(workKey, field)
                if (recordKey in emittedKeys) continue
                if (conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_METADATA, recordKey).isNotEmpty()) {
                    continue
                }
                val revRow = overrides.revision(book.url, field)
                val syncRow = overrides.state(peerId, book.url, field)
                // Run revision-to-fp crash recovery before diffing.
                val existingState = recoverFromRevisionSync(
                    permissionId = permissionId,
                    book = book,
                    workKey = workKey,
                    field = field,
                    recordKey = recordKey,
                    revRow = revRow,
                    syncRow = syncRow,
                    nextHlc = nextHlc,
                )

                val overrideActive = isOverrideActive(book, field)
                // Never publish over a version retained for a work that has not materialized yet:
                // the deferred apply must reconcile against it, not replace it unseen.
                if (existingState?.held == true) continue
                val isOwedByRevision = revRow?.owed(syncRow) == true
                val isUnpublishedOverride = overrideActive && existingState == null && syncRow == null
                val isUnpublishedState = existingState != null && existingState.versionFp != existingState.publishedFp
                if (!isOwedByRevision && !isUnpublishedOverride && !isUnpublishedState) continue

                val resRefs = DriveWorkIdentity.decodeStringArray(existingState?.resRefsJson)
                val targetRev = revRow?.revision ?: 0L
                val updatedAt = revRow?.updatedAt?.takeIf { it > 0L } ?: clock()

                if (overrideActive) {
                    val body: DriveMetadataBody = if (field == MetadataOverrideFields.COVER) {
                        val coverPath = book.userCoverPath
                        // Deliberate "empty cover" (coverOverridden=1, userCoverPath=null) is local-only.
                        if (coverPath.isNullOrBlank()) continue
                        val file = File(coverPath)
                        if (!file.isFile) continue
                        val rawBytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                        val encoded = when (val enc = coverCodec.encodeToBudget(rawBytes)) {
                            is DriveCoverEncodeResult.Encoded -> enc
                            is DriveCoverEncodeResult.TooLarge -> {
                                warnings.add("Custom cover for \"${book.title}\" exceeds the 512 KiB sync limit (${enc.lastAttemptBytes} bytes)")
                                continue
                            }
                            DriveCoverEncodeResult.InvalidImage -> {
                                warnings.add("Custom cover for \"${book.title}\" could not be decoded")
                                continue
                            }
                        }
                        // Upload content-addressed WebP blob BEFORE emitting metadata delta.
                        coverCodec.ensureUploaded(permissionId, accessToken, encoded)
                        DriveMetadataBody.Cover(
                            workKey = workKey,
                            coverSha256 = encoded.sha256,
                            width = encoded.width,
                            height = encoded.height,
                            sizeBytes = encoded.sizeBytes,
                            updatedAt = updatedAt,
                        )
                    } else {
                        DriveMetadataBody.Text(
                            workKey = workKey,
                            field = field,
                            value = textOverrideValue(book, field),
                            updatedAt = updatedAt,
                        )
                    }

                    val bodyMap = DriveWireCodec.encodeMetadataBody(body)
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
                                domain = DriveWireCodec.DOMAIN_METADATA,
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
                            domain = DriveWireCodec.DOMAIN_METADATA,
                            key = recordKey,
                            hlc = hlc,
                            fp = contentFp,
                            bodyMap = bodyMap,
                            ancestors = ancestors,
                            resRefs = resRefs,
                        ),
                    )
                    emittedKeys.add(recordKey)
                    val coverBody = body as? DriveMetadataBody.Cover
                    acks.add(
                        DriveMetadataAckMeta(
                            bookUrl = book.url,
                            field = field,
                            workKey = workKey,
                            targetRevision = targetRev,
                            recordKey = recordKey,
                            recordFp = contentFp,
                            contentFp = contentFp,
                            recordOp = DriveRecordState.OP_PUT,
                            remoteCoverSha256 = coverBody?.coverSha256,
                            remoteCoverUpdatedAt = coverBody?.updatedAt ?: 0L,
                        ),
                    )
                } else {
                    // Override was reset back to 0 -> publish `op: "del"` if previously synced/known.
                    if (syncRow == null && existingState == null) continue
                    val hlc: String
                    val delFp: String
                    val ancestors: List<String>
                    if (existingState != null && existingState.versionOp == DriveRecordState.OP_DEL && existingState.publishedFp != existingState.versionFp) {
                        hlc = existingState.lastHlc.ifBlank { nextHlc() }
                        delFp = existingState.versionFp
                        ancestors = DriveWorkIdentity.decodeStringArray(existingState.ancestorsJson)
                    } else {
                        hlc = nextHlc()
                        delFp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_METADATA, recordKey, hlc)
                        ancestors = buildList {
                            existingState?.versionFp?.let(::add)
                            addAll(DriveWorkIdentity.decodeStringArray(existingState?.ancestorsJson))
                        }.distinct().take(DriveWireCodec.MAX_ANCESTORS)
                        recordStates.upsert(
                            DriveRecordState(
                                permissionId = permissionId,
                                domain = DriveWireCodec.DOMAIN_METADATA,
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
                            domain = DriveWireCodec.DOMAIN_METADATA,
                            key = recordKey,
                            hlc = hlc,
                            ancestors = ancestors,
                            resRefs = resRefs,
                            explicitFp = delFp,
                        ),
                    )
                    emittedKeys.add(recordKey)
                    acks.add(
                        DriveMetadataAckMeta(
                            bookUrl = book.url,
                            field = field,
                            workKey = workKey,
                            targetRevision = targetRev,
                            recordKey = recordKey,
                            recordFp = delFp,
                            contentFp = null,
                            recordOp = DriveRecordState.OP_DEL,
                        ),
                    )
                }
            }
        }

        return DriveMetadataOutboundBundle(records = records, acks = acks, warnings = warnings)
    }

    /**
     * Commits outgoing delivery ACKs in strict two-database order:
     * Transaction 1 (`liseur.db` `metadata_override_sync`) FIRST, then Transaction 2 (`drive_sync.db`).
     */
    suspend fun acknowledgePublished(
        permissionId: String,
        records: List<DriveWireRecord>,
        acks: List<DriveMetadataAckMeta>,
    ) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val now = clock()
        if (acks.isNotEmpty()) {
            database.withTransaction {
                for (ack in acks) {
                    val existing = overrides.state(peerId, ack.bookUrl, ack.field)
                    val ackedRev = maxOf(existing?.ackedRevision ?: 0L, ack.targetRevision)
                    val sentJson = DriveWireCodec.encodeRevisionAckJson(
                        DriveRevisionAckPayload(
                            vfp = ack.recordFp,
                            cfp = ack.contentFp,
                            rev = ack.targetRevision,
                            op = ack.recordOp,
                        ),
                    )
                    overrides.upsertState(
                        MetadataOverrideSync(
                            peerId = peerId,
                            bookUrl = ack.bookUrl,
                            field = ack.field,
                            workId = ack.workKey,
                            ackedRevision = ackedRev,
                            sentJson = sentJson,
                            sentRevision = ack.targetRevision,
                            polledAt = now,
                            remoteCoverSha256 = ack.remoteCoverSha256 ?: existing?.remoteCoverSha256,
                            remoteCoverUpdatedAt = if (ack.remoteCoverSha256 != null) {
                                ack.remoteCoverUpdatedAt
                            } else {
                                existing?.remoteCoverUpdatedAt ?: 0L
                            },
                            adopted = existing?.adopted ?: false,
                        ),
                    )
                }
            }
        }
        val acksByKey = acks.associateBy { it.recordKey }
        store.withTransaction {
            for (record in records) {
                if (record.d != DriveWireCodec.DOMAIN_METADATA) continue
                val state = recordStates.get(permissionId, DriveWireCodec.DOMAIN_METADATA, record.k)
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
                    for (conflict in conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_METADATA, record.k)) {
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

    /**
     * Applies one incoming remote `metadata` record (text or custom cover) via [DriveConflictEngine].
     *
     * Returns `true` when the record was applied/reconciled/ignored cleanly, or `false` when
     * deferred (e.g. a referenced custom cover blob could not yet be downloaded/verified) so
     * [DriveChangeEngine] does NOT advance the `metadata` domain cursor.
     */
    suspend fun applyRemoteRecord(
        permissionId: String,
        accessToken: String,
        record: DriveWireRecord,
        remoteDeviceId: String,
        remoteSeq: Long,
        nextHlc: suspend () -> String,
    ): Boolean {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val isIncomingDelete = record.op == DriveRecordState.OP_DEL ||
            (record.op == DriveRecordState.OP_RESOLVE && record.outcome == DriveResolution.OUTCOME_DELETE)
        val decodedBody = if (!isIncomingDelete) {
            record.body?.let(DriveWireCodec::decodeMetadataBody) ?: return true
        } else {
            null
        }
        val (rawWorkKey, field) = if (decodedBody != null) {
            decodedBody.workKey to decodedBody.field
        } else {
            parseRecordKey(record.k) ?: return true
        }
        if (field !in MetadataOverrideFields.ALL_FIELDS) return true

        val canonicalWorkKey = workIdentity.canonicalWorkKey(permissionId, rawWorkKey)
        val canonicalKey = recordKey(canonicalWorkKey, field)
        // A catalog tombstone counts as a known catalog entry, so this record is not deferred.
        // Without a local book there is nothing to write to: retain it as held history (§4).
        val book = findBookUrlForWorkKey(permissionId, canonicalWorkKey)?.let { books.getByUrl(it) }
        val bookUrl = book?.url
        val normalizedBody = when (decodedBody) {
            is DriveMetadataBody.Text -> decodedBody.copy(workKey = canonicalWorkKey)
            is DriveMetadataBody.Cover -> decodedBody.copy(workKey = canonicalWorkKey)
            null -> null
        }
        val incomingContentFp = normalizedBody?.let {
            DriveCanonicalJson.contentFp(DriveWireCodec.encodeMetadataBody(it))
        }

        val revRow = bookUrl?.let { overrides.revision(it, field) }
        val syncRow = bookUrl?.let { overrides.state(peerId, it, field) }

        // Always run revision-to-fp crash recovery BEFORE reconciling incoming record.
        val existingState = if (book == null || bookUrl == null) {
            recordStates.get(permissionId, DriveWireCodec.DOMAIN_METADATA, canonicalKey)
        } else {
            recoverFromRevisionSync(
                permissionId = permissionId,
                book = book,
                workKey = canonicalWorkKey,
                field = field,
                recordKey = canonicalKey,
                revRow = revRow,
                syncRow = syncRow,
                nextHlc = nextHlc,
            )
        }
        val existingResolutions = resolutions.forRecord(permissionId, DriveWireCodec.DOMAIN_METADATA, canonicalKey)
        val unsettledConflicts = conflicts.unsettledForRecord(permissionId, DriveWireCodec.DOMAIN_METADATA, canonicalKey)

        val localView = if (book == null || bookUrl == null) {
            DriveHeldRecords.localView(existingState)
        } else {
            buildLocalRecordView(
                book = book,
                workKey = canonicalWorkKey,
                field = field,
                recordKey = canonicalKey,
                revRow = revRow,
                syncRow = syncRow,
                // A held row is retained history, not data in this book, so nothing local matches it.
                existingState = existingState?.takeUnless { it.held },
                existingResolutions = existingResolutions,
                incoming = record,
                nextHlc = nextHlc,
                heldReplay = existingState?.held == true,
            )
        }

        val result = DriveConflictEngine.reconcileIncoming(
            permissionId = permissionId,
            domain = DriveWireCodec.DOMAIN_METADATA,
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

        // A parked successor can win over the incoming resolution, so apply the body the engine chose.
        val appliedBody: DriveMetadataBody? = (result.dataAction as? ReconciliationDataAction.ApplyPut)?.let { put ->
            when (val chosen = DriveWireCodec.decodeMetadataBody(put.body)) {
                is DriveMetadataBody.Text -> chosen.copy(workKey = canonicalWorkKey)
                is DriveMetadataBody.Cover -> chosen.copy(workKey = canonicalWorkKey)
                null -> return true
            }
        }
        val appliedContentFp = appliedBody?.let { DriveCanonicalJson.contentFp(DriveWireCodec.encodeMetadataBody(it)) }
        val appliedState = result.updatedRecordState?.takeIf { it.versionFp != record.fp }
        val ackVfp = appliedState?.versionFp ?: record.fp
        val ackOp = appliedState?.versionOp ?: record.op

        // For custom covers, download & SHA-256 verify BEFORE entering Transaction 1.
        // Retryable network/API errors propagate; null (not yet visible / hash mismatch) defers cursor advancement.
        val downloadedCoverFile: File? = if (bookUrl != null && appliedBody is DriveMetadataBody.Cover) {
            coverCodec.downloadVerified(permissionId, accessToken, appliedBody.coverSha256)
                ?: return false
        } else {
            null
        }

        var appliedRev: Long? = null
        var staleLocal = false
        var coverInstallFailed = false
        val now = clock()

        // Transaction 1 (`liseur.db`): apply override + revision + atomic `metadata_override_sync` ack.
        // A held record has no book to write to and only its causal state is retained.
        when (result.dataAction) {
            is ReconciliationDataAction.ApplyPut -> if (book != null && bookUrl != null) {
                val body = appliedBody ?: return true
                database.withTransaction {
                    // The reader may have edited this field while the cover downloaded.
                    if (localChangedSince(book, revRow, bookUrl, field)) {
                        staleLocal = true
                        return@withTransaction
                    }
                    when (body) {
                        is DriveMetadataBody.Text -> applyTextOverrideToBook(bookUrl, body.field, body.value)
                        is DriveMetadataBody.Cover -> {
                            val file = downloadedCoverFile
                                ?.let { coverCodec.installForBook(bookUrl, body.coverSha256) }
                            if (file == null) {
                                coverInstallFailed = true
                                return@withTransaction
                            }
                            books.setCoverOverride(bookUrl, file.absolutePath, body.coverSha256)
                        }
                    }
                    val revApplied = overrides.nextRevision()
                    val priorRev = overrides.revision(bookUrl, field)
                    overrides.upsertRevision(
                        MetadataOverrideRevision(
                            bookUrl = bookUrl,
                            field = field,
                            revision = revApplied,
                            retiredRevision = priorRev?.retiredRevision ?: 0L,
                            updatedAt = body.updatedAt,
                        ),
                    )
                    val coverBody = body as? DriveMetadataBody.Cover
                    overrides.upsertState(
                        MetadataOverrideSync(
                            peerId = peerId,
                            bookUrl = bookUrl,
                            field = field,
                            workId = canonicalWorkKey,
                            ackedRevision = revApplied,
                            sentJson = DriveWireCodec.encodeRevisionAckJson(
                                DriveRevisionAckPayload(
                                    vfp = ackVfp,
                                    cfp = appliedContentFp,
                                    rev = revApplied,
                                    op = ackOp,
                                ),
                            ),
                            sentRevision = revApplied,
                            polledAt = now,
                            remoteCoverSha256 = coverBody?.coverSha256,
                            remoteCoverUpdatedAt = coverBody?.updatedAt ?: 0L,
                            adopted = true,
                        ),
                    )
                    appliedRev = revApplied
                }
            }
            ReconciliationDataAction.ApplyDelete -> if (book != null && bookUrl != null) {
                database.withTransaction {
                    if (localChangedSince(book, revRow, bookUrl, field)) {
                        staleLocal = true
                        return@withTransaction
                    }
                    clearOverrideOnBook(bookUrl, field)
                    val revApplied = overrides.nextRevision()
                    val priorRev = overrides.revision(bookUrl, field)
                    overrides.upsertRevision(
                        MetadataOverrideRevision(
                            bookUrl = bookUrl,
                            field = field,
                            revision = revApplied,
                            retiredRevision = priorRev?.retiredRevision ?: 0L,
                            updatedAt = now,
                        ),
                    )
                    overrides.upsertState(
                        MetadataOverrideSync(
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
                            remoteCoverSha256 = null,
                            remoteCoverUpdatedAt = 0L,
                            adopted = true,
                        ),
                    )
                    appliedRev = revApplied
                }
            }
            ReconciliationDataAction.NoDataChange -> Unit
        }
        // Defer: the record replays and reconciles against the reader's newer edit.
        if (staleLocal) return false
        // Nothing was applied or acknowledged, so the record replays once the install can succeed.
        if (coverInstallFailed) return false

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

    /**
     * Downloads and verifies the remote cover a KEEP_REMOTE choice on [conflict] needs,
     * so the resolution itself never waits on the network.
     */
    suspend fun prefetchConflictCover(
        permissionId: String,
        conflict: DriveConflict,
        accessTokenProvider: suspend () -> String?,
    ) {
        val remote = conflict.remoteJson
            ?.let { runCatching { DriveWireCodec.decodeMetadataBody(JSONObject(it)) }.getOrNull() }
            as? DriveMetadataBody.Cover ?: return
        if (coverCodec.localCoverFile(remote.coverSha256).isFile) return
        val token = accessTokenProvider() ?: return
        runCatching { coverCodec.downloadVerified(permissionId, token, remote.coverSha256) }
    }

    /** Applies a reader's conflict choice for a metadata override conflict and queues `op: "resolve"`. */
    suspend fun resolveLocalConflict(
        permissionId: String,
        conflict: DriveConflict,
        choice: DriveConflictChoice,
        localDeviceId: String,
        nextHlc: suspend () -> String,
        accessTokenProvider: (suspend () -> String?)? = null,
    ) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val now = clock()
        conflicts.markResolving(permissionId, conflict.conflictId, choice.name)

        val (rawWorkKey, field) = parseRecordKey(conflict.recordKey) ?: return
        val workKey = workIdentity.canonicalWorkKey(permissionId, rawWorkKey)
        val bookUrl = conflict.bookUrl ?: findBookUrlForWorkKey(permissionId, workKey) ?: return
        val book = books.getByUrl(bookUrl) ?: return
        val revRow = overrides.revision(bookUrl, field)
        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_METADATA, conflict.recordKey)
        val remoteBody = conflict.remoteJson?.let { raw ->
            runCatching { DriveWireCodec.decodeMetadataBody(JSONObject(raw)) }.getOrNull()
        }
        val localBody = buildLocalBodyOrNull(book, workKey, field, revRow, existingState)

        val outcome: String
        val chosenBody: DriveMetadataBody?
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
                    chosenBody = when (remoteBody) {
                        is DriveMetadataBody.Text -> remoteBody.copy(workKey = workKey)
                        is DriveMetadataBody.Cover -> remoteBody.copy(workKey = workKey)
                    }
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
                    chosenBody = when (remoteBody) {
                        is DriveMetadataBody.Text -> remoteBody.copy(workKey = workKey)
                        is DriveMetadataBody.Cover -> remoteBody.copy(workKey = workKey)
                    }
                } else {
                    outcome = DriveResolution.OUTCOME_DELETE
                    chosenBody = null
                }
            }
        }

        val remoteCoverFile = if (chosenBody is DriveMetadataBody.Cover) {
            val existingLocal = coverCodec.localCoverFile(chosenBody.coverSha256)
            when {
                existingLocal.isFile -> existingLocal
                // Which side was chosen depends on the body, not the choice: KEEP_EDITED keeps
                // the remote cover when the local side was a reset.
                remoteBody is DriveMetadataBody.Cover &&
                    remoteBody.coverSha256.equals(chosenBody.coverSha256, ignoreCase = true) -> {
                    val token = accessTokenProvider?.invoke()
                    if (token != null) {
                        runCatching { coverCodec.downloadVerified(permissionId, token, chosenBody.coverSha256) }.getOrNull()
                    } else {
                        null
                    }
                }
                // A locally chosen cover only exists as the reader's image: keep its encoded blob
                // so the resolution's publish uploads it.
                else -> storeLocalCover(book, chosenBody.coverSha256)
            }
        } else {
            null
        }
        if (chosenBody is DriveMetadataBody.Cover && remoteCoverFile?.isFile != true) {
            // Never commit a cover choice without its verified image: keep the conflict open to retry.
            conflicts.reopen(permissionId, conflict.conflictId)
            return
        }

        val ownedCoverFile = (chosenBody as? DriveMetadataBody.Cover)
            ?.let { coverCodec.installForBook(bookUrl, it.coverSha256) }
        if (chosenBody is DriveMetadataBody.Cover && ownedCoverFile == null) {
            conflicts.reopen(permissionId, conflict.conflictId)
            return
        }

        val chosenBodyMap = chosenBody?.let(DriveWireCodec::encodeMetadataBody)
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

        var appliedRev = revRow?.revision ?: 0L
        // Transaction 1 (`liseur.db`): apply chosen override + atomic revision ack.
        database.withTransaction {
            when (chosenBody) {
                is DriveMetadataBody.Text -> applyTextOverrideToBook(bookUrl, chosenBody.field, chosenBody.value)
                is DriveMetadataBody.Cover -> {
                    books.setCoverOverride(bookUrl, requireNotNull(ownedCoverFile).absolutePath, chosenBody.coverSha256)
                }
                null -> clearOverrideOnBook(bookUrl, field)
            }
            val revApplied = overrides.nextRevision()
            val priorRev = overrides.revision(bookUrl, field)
            overrides.upsertRevision(
                MetadataOverrideRevision(
                    bookUrl = bookUrl,
                    field = field,
                    revision = revApplied,
                    retiredRevision = priorRev?.retiredRevision ?: 0L,
                    updatedAt = chosenBody?.updatedAt ?: now,
                ),
            )
            val coverBody = chosenBody as? DriveMetadataBody.Cover
            overrides.upsertState(
                MetadataOverrideSync(
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
                        ),
                    ),
                    sentRevision = revApplied,
                    polledAt = now,
                    remoteCoverSha256 = coverBody?.coverSha256,
                    remoteCoverUpdatedAt = coverBody?.updatedAt ?: 0L,
                    adopted = false,
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
        accessTokenProvider: (suspend () -> String?)? = null,
    ) {
        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_METADATA, conflict.recordKey)
        if (existingState != null && existingState.versionOp == DriveRecordState.OP_RESOLVE) {
            val res = resolutions.get(permissionId, DriveWireCodec.DOMAIN_METADATA, conflict.recordKey, existingState.versionFp)
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
        val book = books.getByUrl(bookUrl) ?: return
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val revRow = overrides.revision(bookUrl, field)
        val syncRow = overrides.state(peerId, bookUrl, field)
        val sentMeta = DriveWireCodec.decodeRevisionAckJson(syncRow?.sentJson)

        val tx1AlreadyCommitted = syncRow != null &&
            sentMeta != null &&
            sentMeta.op == DriveRecordState.OP_RESOLVE &&
            (
                existingState == null ||
                    ((existingState.lastSyncedRevision ?: 0L) < syncRow.ackedRevision && existingState.versionFp != sentMeta.vfp)
                ) &&
            resolutions.get(permissionId, DriveWireCodec.DOMAIN_METADATA, conflict.recordKey, sentMeta.vfp) == null

        if (tx1AlreadyCommitted) {
            val remoteBody = conflict.remoteJson?.let { raw ->
                runCatching { DriveWireCodec.decodeMetadataBody(JSONObject(raw)) }.getOrNull()
            }
            val existingBody = existingState?.bodyJson?.let { raw ->
                runCatching { DriveWireCodec.decodeMetadataBody(JSONObject(raw)) }.getOrNull()
            }
            fun normalizeBody(body: DriveMetadataBody?): DriveMetadataBody? = when (body) {
                is DriveMetadataBody.Text -> body.copy(workKey = workKey)
                is DriveMetadataBody.Cover -> body.copy(workKey = workKey)
                null -> null
            }
            val outcome = if (sentMeta!!.cfp != null) {
                DriveResolution.OUTCOME_PUT
            } else {
                DriveResolution.OUTCOME_DELETE
            }
            val recoveredBody: DriveMetadataBody? = if (outcome == DriveResolution.OUTCOME_PUT) {
                when (choice) {
                    DriveConflictChoice.KEEP_REMOTE -> normalizeBody(remoteBody)
                    DriveConflictChoice.KEEP_LOCAL,
                    DriveConflictChoice.KEEP_EDITED,
                    -> {
                        if (revRow != null && revRow.revision == syncRow!!.ackedRevision) {
                            buildLocalBodyOrNull(book, workKey, field, revRow, existingState, syncRow)
                        } else {
                            normalizeBody(existingBody) ?: normalizeBody(remoteBody)
                        }
                    }
                    DriveConflictChoice.DELETE -> null
                }
            } else {
                null
            }
            val finalOutcome = if (outcome == DriveResolution.OUTCOME_PUT && recoveredBody != null) {
                DriveResolution.OUTCOME_PUT
            } else {
                DriveResolution.OUTCOME_DELETE
            }
            if (recoveredBody is DriveMetadataBody.Cover) {
                val token = accessTokenProvider?.invoke()
                val localFile = coverCodec.localCoverFile(recoveredBody.coverSha256)
                if (token != null && localFile.isFile) {
                    val bytes = runCatching { localFile.readBytes() }.getOrNull()
                    if (bytes != null && DriveCanonicalJson.sha256Hex(bytes) == recoveredBody.coverSha256) {
                        coverCodec.ensureUploaded(
                            permissionId = permissionId,
                            accessToken = token,
                            encoded = DriveCoverEncodeResult.Encoded(
                                webpBytes = bytes,
                                sha256 = recoveredBody.coverSha256,
                                width = recoveredBody.width,
                                height = recoveredBody.height,
                                sizeBytes = bytes.size.toLong(),
                            ),
                        )
                    }
                }
            }
            val chosenBodyMap = recoveredBody?.let(DriveWireCodec::encodeMetadataBody)
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
                lastSyncedRevision = syncRow!!.ackedRevision,
                now = now,
                explicitResolutionFp = sentMeta.vfp,
            )
            val wire = DriveWireCodec.resolveRecord(
                domain = DriveWireCodec.DOMAIN_METADATA,
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

        resolveLocalConflict(permissionId, conflict, choice, localDeviceId, nextHlc, accessTokenProvider)
    }

    /**
     * Exact ADR-0078 revision-to-fingerprint crash recovery for `metadata_override_sync`.
     *
     * Runs whenever `drive_record_state` is missing or `last_synced_revision < sync.acked_revision`.
     */
    internal suspend fun recoverFromRevisionSync(
        permissionId: String,
        book: Book,
        workKey: String,
        field: String,
        recordKey: String,
        revRow: MetadataOverrideRevision?,
        syncRow: MetadataOverrideSync?,
        nextHlc: suspend () -> String,
    ): DriveRecordState? {
        val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_METADATA, recordKey)
        if (syncRow == null || syncRow.ackedRevision <= 0L) return existing
        if (existing != null && (existing.lastSyncedRevision ?: 0L) >= syncRow.ackedRevision) return existing

        val sentMeta = DriveWireCodec.decodeRevisionAckJson(syncRow.sentJson)
        val priorAnc = buildList {
            existing?.versionFp?.let(::add)
            addAll(DriveWorkIdentity.decodeStringArray(existing?.ancestorsJson))
        }

        if (revRow != null && revRow.revision == syncRow.ackedRevision) {
            val localBody = buildLocalBodyOrNull(book, workKey, field, revRow, existing, syncRow)
            val bodyMap = localBody?.let(DriveWireCodec::encodeMetadataBody)
            val recomputedCfp = bodyMap?.let(DriveCanonicalJson::contentFp) ?: sentMeta?.cfp
            val vfp = sentMeta?.vfp
                ?: recomputedCfp
                ?: DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_METADATA, recordKey, nextHlc())
            val vop = sentMeta?.op ?: if (recomputedCfp != null) DriveRecordState.OP_PUT else DriveRecordState.OP_DEL
            val mergedAnc = (priorAnc + listOfNotNull(vfp))
                .distinct()
                .filter { it != vfp }
                .take(DriveWireCodec.MAX_ANCESTORS)
            val repaired = DriveRecordState(
                permissionId = permissionId,
                domain = DriveWireCodec.DOMAIN_METADATA,
                recordKey = recordKey,
                versionFp = vfp,
                versionOp = vop,
                publishedFp = vfp,
                contentFp = recomputedCfp,
                lastSyncedRevision = syncRow.ackedRevision,
                originDeviceId = existing?.originDeviceId?.takeIf { existing.versionFp == vfp },
                originSeq = existing?.originSeq?.takeIf { existing.versionFp == vfp },
                ancestorsJson = DriveCanonicalJson.encode(mergedAnc),
                resRefsJson = existing?.resRefsJson ?: "[]",
                lastHlc = existing?.lastHlc ?: "",
                bodyJson = bodyMap?.let(DriveCanonicalJson::encode) ?: existing?.bodyJson,
            )
            recordStates.upsert(repaired)
            return repaired
        }

        if (revRow != null && revRow.revision > syncRow.ackedRevision && sentMeta != null) {
            val mergedAnc = (listOf(sentMeta.vfp) + priorAnc)
                .distinct()
                .filter { it != sentMeta.vfp }
                .take(DriveWireCodec.MAX_ANCESTORS)
            val seeded = DriveRecordState(
                permissionId = permissionId,
                domain = DriveWireCodec.DOMAIN_METADATA,
                recordKey = recordKey,
                versionFp = sentMeta.vfp,
                versionOp = sentMeta.op,
                publishedFp = sentMeta.vfp,
                contentFp = sentMeta.cfp,
                lastSyncedRevision = syncRow.ackedRevision,
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
        book: Book,
        workKey: String,
        field: String,
        recordKey: String,
        revRow: MetadataOverrideRevision?,
        syncRow: MetadataOverrideSync?,
        existingState: DriveRecordState?,
        existingResolutions: List<DriveResolution>,
        incoming: DriveWireRecord,
        nextHlc: suspend () -> String,
        heldReplay: Boolean = false,
    ): LocalRecordView {
        val overrideActive = isOverrideActive(book, field)
        val liveBody = buildLocalBodyOrNull(book, workKey, field, revRow, existingState, syncRow)
        val liveBodyMap = liveBody?.let(DriveWireCodec::encodeMetadataBody)
        val liveContentFp = liveBodyMap?.let(DriveCanonicalJson::contentFp)
        val liveBodyJson = liveBodyMap?.let(DriveCanonicalJson::encode)

        val sentMeta = DriveWireCodec.decodeRevisionAckJson(syncRow?.sentJson)
        val postCrashAckMatchesIncoming = sentMeta != null && sentMeta.vfp == incoming.fp
        val hasPostCrashLocalEdit = postCrashAckMatchesIncoming && revRow != null && syncRow != null && revRow.revision > syncRow.ackedRevision

        if (existingState == null) {
            if (!overrideActive) {
                // An override the reader removed is a real revisioned deletion, not absent data.
                // Replaying a retained version must see it as a competing edit instead of
                // overwriting the removal.
                if (heldReplay && revRow != null && revRow.revision > 0L) {
                    return LocalRecordView(
                        versionFp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_METADATA, recordKey, nextHlc()),
                        versionOp = DriveRecordState.OP_DEL,
                        isLivePut = false,
                        contentFp = null,
                        bodyJson = null,
                        localRowMatchesContentFp = true,
                    )
                }
                return LocalRecordView(versionFp = null)
            }
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

        val localDirty = (revRow?.owed(syncRow) == true &&
            (existingState.lastSyncedRevision == null || revRow.revision > existingState.lastSyncedRevision)) ||
            (overrideActive && liveContentFp != null && liveContentFp != existingState.contentFp) ||
            (!overrideActive && stateIsLivePut && !postCrashAckMatchesIncoming)

        if (localDirty) {
            if (overrideActive && liveContentFp != null && liveContentFp != existingState.contentFp) {
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
            } else if (!overrideActive && stateIsLivePut) {
                val hlc = nextHlc()
                val delFp = DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_METADATA, recordKey, hlc)
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

        val matches = (stateIsLivePut && overrideActive && (liveContentFp == null || liveContentFp == existingState.contentFp)) ||
            (!stateIsLivePut && !overrideActive)

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

    private fun buildLocalBodyOrNull(
        book: Book,
        workKey: String,
        field: String,
        revRow: MetadataOverrideRevision?,
        existingState: DriveRecordState?,
        syncRow: MetadataOverrideSync? = null,
    ): DriveMetadataBody? {
        if (!isOverrideActive(book, field)) return null
        val updatedAt = revRow?.updatedAt?.takeIf { it > 0L }
            ?: existingState?.bodyJson?.let { runCatching { JSONObject(it).optLong("updatedAt", 0L) }.getOrNull()?.takeIf { t -> t > 0L } }
            ?: clock()
        if (field != MetadataOverrideFields.COVER) {
            return DriveMetadataBody.Text(
                workKey = workKey,
                field = field,
                value = textOverrideValue(book, field),
                updatedAt = updatedAt,
            )
        }
        val coverPath = book.userCoverPath ?: return null
        val file = File(coverPath)
        if (!file.isFile) return null
        // Reuse existing encoded cover metadata when the cached cover SHA still matches.
        val existingCover = existingState?.bodyJson?.let {
            runCatching { DriveWireCodec.decodeMetadataBody(JSONObject(it)) as? DriveMetadataBody.Cover }.getOrNull()
        }
        val localSha = book.userCoverSha256?.lowercase(Locale.ROOT)
        val coverAdvancedPastAck = revRow != null && revRow.revision > (syncRow?.ackedRevision ?: 0L)
        if (existingCover != null && localSha != null &&
            (
                localSha == existingCover.coverSha256 ||
                    (!coverAdvancedPastAck && syncRow?.remoteCoverSha256 == existingCover.coverSha256)
                )
        ) {
            return existingCover.copy(workKey = workKey, updatedAt = updatedAt)
        }
        val rawBytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return when (val enc = coverCodec.encodeToBudget(rawBytes)) {
            is DriveCoverEncodeResult.Encoded -> DriveMetadataBody.Cover(
                workKey = workKey,
                coverSha256 = enc.sha256,
                width = enc.width,
                height = enc.height,
                sizeBytes = enc.sizeBytes,
                updatedAt = updatedAt,
            )
            else -> null
        }
    }

    private fun storeLocalCover(book: Book, expectedSha: String): File? {
        val raw = book.userCoverPath?.let(::File)?.takeIf { it.isFile }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
            ?: return null
        val encoded = coverCodec.encodeToBudget(raw) as? DriveCoverEncodeResult.Encoded ?: return null
        if (!encoded.sha256.equals(expectedSha, ignoreCase = true)) return null
        return coverCodec.storeLocal(encoded)
    }

    /** True when [field]'s override on [bookUrl] moved on from the [before] / [revBefore] snapshot. */
    private suspend fun localChangedSince(
        before: Book,
        revBefore: MetadataOverrideRevision?,
        bookUrl: String,
        field: String,
    ): Boolean {
        if (overrides.revision(bookUrl, field)?.revision != revBefore?.revision) return true
        val now = books.getByUrl(bookUrl) ?: return true
        return when (field) {
            MetadataOverrideFields.TITLE ->
                now.titleOverridden != before.titleOverridden || now.titleOverridden && now.title != before.title
            MetadataOverrideFields.AUTHOR ->
                now.authorOverridden != before.authorOverridden || now.authorOverridden && now.author != before.author
            MetadataOverrideFields.DESCRIPTION ->
                now.descriptionOverridden != before.descriptionOverridden || now.userDescription != before.userDescription
            MetadataOverrideFields.COVER ->
                now.coverOverridden != before.coverOverridden || now.userCoverPath != before.userCoverPath
            else -> false
        }
    }

    private suspend fun applyTextOverrideToBook(bookUrl: String, field: String, value: String) {
        when (field) {
            MetadataOverrideFields.TITLE -> books.setTitleOverride(bookUrl, value)
            MetadataOverrideFields.AUTHOR -> books.setAuthorOverride(bookUrl, value)
            MetadataOverrideFields.DESCRIPTION -> books.setDescriptionOverride(bookUrl, value)
        }
    }

    private suspend fun clearOverrideOnBook(bookUrl: String, field: String) {
        when (field) {
            MetadataOverrideFields.TITLE -> books.clearTitleOverride(bookUrl)
            MetadataOverrideFields.AUTHOR -> books.clearAuthorOverride(bookUrl)
            MetadataOverrideFields.DESCRIPTION -> books.clearDescriptionOverride(bookUrl)
            MetadataOverrideFields.COVER -> books.clearCoverOverride(bookUrl)
        }
    }

    private fun isOverrideActive(book: Book, field: String): Boolean = when (field) {
        MetadataOverrideFields.TITLE -> book.titleOverridden
        MetadataOverrideFields.AUTHOR -> book.authorOverridden
        MetadataOverrideFields.DESCRIPTION -> book.descriptionOverridden
        MetadataOverrideFields.COVER -> book.coverOverridden && !book.userCoverPath.isNullOrBlank()
        else -> false
    }

    private fun textOverrideValue(book: Book, field: String): String = when (field) {
        MetadataOverrideFields.TITLE -> book.userTitle.orEmpty()
        MetadataOverrideFields.AUTHOR -> book.userAuthor.orEmpty()
        MetadataOverrideFields.DESCRIPTION -> book.userDescription.orEmpty()
        else -> ""
    }

    private suspend fun findBookUrlForWorkKey(permissionId: String, canonicalWorkKey: String): String? =
        workIdentity.liveBookUrlFor(permissionId, canonicalWorkKey)

    companion object {
        fun recordKey(workKey: String, field: String): String = "$workKey/$field"

        fun parseRecordKey(recordKey: String): Pair<String, String>? {
            val idx = recordKey.lastIndexOf('/')
            if (idx <= 0 || idx >= recordKey.lastIndex) return null
            val workKey = recordKey.substring(0, idx)
            val field = recordKey.substring(idx + 1)
            return if (field in MetadataOverrideFields.ALL_FIELDS) workKey to field else null
        }
    }
}
