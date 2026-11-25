package com.chmouel.liseur.drive

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.VocabularyReviewSync
import com.chmouel.liseur.data.db.VocabularyStagedReview
import com.chmouel.liseur.data.db.VocabularyWordSync
import com.chmouel.liseur.domain.VocabularyMerge
import com.chmouel.liseur.vocabulary.VocabularyOccurrence
import com.chmouel.liseur.vocabulary.VocabularyReview
import com.chmouel.liseur.vocabulary.VocabularyWord
import com.chmouel.liseur.vocabulary.normalizeVocabularySearch
import java.util.UUID
import org.json.JSONObject

internal data class DriveVocabularyOutboundBundle(
    val records: List<DriveWireRecord>,
    val wordAcks: List<DriveVocabWordAckMeta>,
    val reviewAcks: List<DriveVocabReviewAckMeta>,
)

/**
 * Vocabulary words, occurrences, and event-ID-deduplicated review history sync
 * for Google Drive (`docs/DRIVE_SYNC.md` §4.9–4.11 & ADR-0078).
 *
 * Key invariants:
 * - Live SRS fields (`dueAt`, `reviewedAt`, `streak`, `intervalMinutes`, `repetitions`)
 *   are NEVER sent on the wire and are always derived locally via
 *   [VocabularyMerge.reconcileSchedule] from `(addedAt, mergedBaseline, liveReviews)`.
 * - Legacy pre-event-log `baseline` (`VocabularyWord.baselineOrNull()`) IS synced and
 *   merged via [VocabularyMerge.mergeBaseline] so migrated schedules match across devices.
 * - Words and occurrences auto-merge via [VocabularyMerge.mergeWordMetadata] and
 *   [VocabularyMerge.mergeOccurrences]; word delete on untouched copy deletes silently,
 *   while word delete vs. concurrent edit or review resolves as add-wins silently.
 * - Reviews dedupe by `syncId`; review `del` (undo) is a final tombstone.
 */
internal class DriveVocabularySync(
    private val database: LiseurDatabase,
    private val store: DriveSyncDatabase,
    private val workIdentity: DriveWorkIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val vocabularyDao get() = database.vocabularyDao()
    private val vocabSyncDao get() = database.vocabularySyncDao()
    private val identities get() = database.workIdentityDao()
    private val books get() = database.bookDao()
    private val recordStates get() = store.recordStates()

    /**
     * Collects dirty `vocabWord`, `vocabOccurrence`, and `vocabReview` records
     * for outbound batch publication.
     */
    suspend fun collectDirtyRecords(
        permissionId: String,
        localDeviceId: String = "",
        nextHlc: suspend () -> String,
    ): DriveVocabularyOutboundBundle {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)

        // Step 1: coalesce any duplicate visible rows for the same (language, normalized)
        // (e.g., an unowned row + retired liseur-sync row) and backfill deterministic syncIds.
        coalesceDuplicateVisibleWordsAndBackfillReviewIds(permissionId)

        // Step 2: crash recovery if Tx1 (liseur.db) committed before Tx2 (drive_sync.db).
        recoverFromCommittedSyncAcks(permissionId, peerId)

        attachStagedReviewsForLocalWords(permissionId, peerId)

        val visibleWords = vocabularyDao.exportWords()
        val wordsByKey = linkedMapOf<Pair<String, String>, VocabularyWord>()
        for (w in visibleWords) {
            wordsByKey.putIfAbsent(w.language to w.normalized, w)
        }
        val wordsById = visibleWords.associateBy { it.id }

        val aliasByBookUrl = identities.aliasesFor(peerId)
            .filter { it.usable }
            .associateBy { it.bookUrl }

        val allWordSyncs = vocabSyncDao.allWords(peerId)
            .associateBy { it.language to it.normalized }
        val allWordStates = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_VOCAB_WORD)
            .associateBy { it.recordKey }
        val allOccStates = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE)
            .associateBy { it.recordKey }
        val allReviewStates = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_VOCAB_REVIEW)
            .associateBy { it.recordKey }

        val wordRecords = mutableListOf<DriveWireRecord>()
        val occRecords = mutableListOf<DriveWireRecord>()
        val reviewRecords = mutableListOf<DriveWireRecord>()
        val wordAcks = mutableListOf<DriveVocabWordAckMeta>()
        val reviewAcks = mutableListOf<DriveVocabReviewAckMeta>()

        // -- 1. Live words (`vocabWord` puts) --------------------------------
        for ((keyPair, word) in wordsByKey) {
            val (language, normalized) = keyPair
            val recordKey = wordKey(language, normalized)
            val body = word.toWireBody()
            val bodyMap = DriveWireCodec.encodeVocabWordBody(body)
            val contentFp = DriveCanonicalJson.contentFp(bodyMap)
            val state = allWordStates[recordKey]
            val sync = allWordSyncs[keyPair]

            val dirty = state == null ||
                state.versionOp != DriveRecordState.OP_PUT ||
                state.publishedFp != state.versionFp ||
                state.contentFp != contentFp ||
                sync?.ackedFingerprint != contentFp

            if (dirty) {
                val hlc = nextHlc()
                val ancestors = buildAncestors(state, sync?.ackedFingerprint, exclude = contentFp)
                val record = DriveWireCodec.putRecord(
                    domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
                    key = recordKey,
                    hlc = hlc,
                    bodyMap = bodyMap,
                    ancestors = ancestors,
                )
                wordRecords.add(record)
                wordAcks.add(
                    DriveVocabWordAckMeta(
                        language = language,
                        normalized = normalized,
                        wordId = word.id,
                        recordKey = recordKey,
                        recordFp = record.fp,
                        baseJson = DriveCanonicalJson.encode(bodyMap),
                        isDelete = false,
                    ),
                )
            }
        }

        // -- 2. Deleted words (`vocabWord` dels) -----------------------------
        val candidateDeletedKeys = linkedSetOf<Pair<String, String>>()
        for ((pair, sync) in allWordSyncs) {
            if (pair !in wordsByKey &&
                sync.ackedFingerprint.isNotEmpty() &&
                sync.ackedFingerprint != VocabularyWordSync.FINGERPRINT_DELETED
            ) {
                candidateDeletedKeys.add(pair)
            }
        }
        for ((recordKey, state) in allWordStates) {
            val pair = parseWordKey(recordKey) ?: continue
            if (pair !in wordsByKey) {
                val unpublishedDel = state.versionOp == DriveRecordState.OP_DEL && state.publishedFp != state.versionFp
                if (state.versionOp == DriveRecordState.OP_PUT || unpublishedDel) {
                    candidateDeletedKeys.add(pair)
                }
            }
        }

        val deleteObservedVector = if (candidateDeletedKeys.isNotEmpty()) {
            currentVocabObservedVector(permissionId, localDeviceId)
        } else {
            emptyMap()
        }
        for (pair in candidateDeletedKeys) {
            val (language, normalized) = pair
            val recordKey = wordKey(language, normalized)
            val state = allWordStates[recordKey]
            val sync = allWordSyncs[pair]
            val reuseUnpublishedDel = state != null &&
                state.versionOp == DriveRecordState.OP_DEL &&
                state.publishedFp != state.versionFp
            val hlc = if (reuseUnpublishedDel) state!!.lastHlc else nextHlc()
            val delFp = if (reuseUnpublishedDel) state!!.versionFp else DriveCanonicalJson.delFp(
                DriveWireCodec.DOMAIN_VOCAB_WORD,
                recordKey,
                hlc,
            )
            val ancestors = buildAncestors(state, sync?.ackedFingerprint, exclude = delFp)
            val record = DriveWireCodec.delRecord(
                domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
                key = recordKey,
                hlc = hlc,
                ancestors = ancestors,
                explicitFp = delFp,
                observed = observedExcludingStagedReviews(
                    peerId = peerId,
                    language = language,
                    normalized = normalized,
                    localDeviceId = localDeviceId,
                    observed = deleteObservedVector,
                    reviewStates = allReviewStates,
                ),
            )
            wordRecords.add(record)
            wordAcks.add(
                DriveVocabWordAckMeta(
                    language = language,
                    normalized = normalized,
                    wordId = null,
                    recordKey = recordKey,
                    recordFp = delFp,
                    baseJson = null,
                    isDelete = true,
                ),
            )
        }

        // -- 3. Occurrences (`vocabOccurrence` puts) -------------------------
        val allOccurrences = vocabularyDao.exportOccurrences()
        val emittedOccKeys = mutableSetOf<String>()
        for (rawOcc in allOccurrences) {
            val word = wordsById[rawOcc.wordId] ?: continue
            val wKey = wordKey(word.language, word.normalized)
            val resolvedWorkKey = resolveOccurrenceWorkKey(permissionId, rawOcc, aliasByBookUrl)
            val occ = upgradeOccurrenceKeyIfNeeded(rawOcc, resolvedWorkKey) ?: continue
            val occRecordKey = occurrenceRecordKey(wKey, occ.occurrenceKey)
            if (!emittedOccKeys.add(occRecordKey)) continue

            val body = occ.toWireBody(wKey, resolvedWorkKey)
            val bodyMap = DriveWireCodec.encodeVocabOccurrenceBody(body)
            val contentFp = DriveCanonicalJson.contentFp(bodyMap)
            val state = allOccStates[occRecordKey]
            if (state == null || state.versionOp != DriveRecordState.OP_PUT ||
                state.publishedFp != state.versionFp || state.contentFp != contentFp
            ) {
                val hlc = nextHlc()
                val ancestors = buildAncestors(state, null, exclude = contentFp)
                occRecords.add(
                    DriveWireCodec.putRecord(
                        domain = DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE,
                        key = occRecordKey,
                        hlc = hlc,
                        bodyMap = bodyMap,
                        ancestors = ancestors,
                    ),
                )
            }
        }

        // -- 4. Reviews (`vocabReview` puts & final undo `del`s) -------------
        val allReviewSyncs = vocabSyncDao.allReviews(peerId).associateBy { it.reviewId }
        val liveReviews = vocabularyDao.exportReviews()
        val candidateReviewPuts = mutableListOf<Pair<VocabularyReview, DriveVocabReviewAckMeta>>()
        val candidateReviewRecords = mutableListOf<DriveWireRecord>()
        for (review in liveReviews) {
            val word = wordsById[review.wordId] ?: continue
            val sid = review.syncId?.takeIf { it.isNotBlank() } ?: continue

            val rSync = allReviewSyncs[sid]
            if (rSync != null && (rSync.ackedDeleted || rSync.pendingDeleted)) continue
            val rState = allReviewStates[sid]
            if (rState?.versionOp == DriveRecordState.OP_DEL) continue

            val alreadySynced = rSync != null &&
                rSync.localReviewId != null &&
                rState != null &&
                rState.versionOp == DriveRecordState.OP_PUT &&
                rState.publishedFp != null
            if (alreadySynced) continue

            val wKey = wordKey(word.language, word.normalized)
            val body = DriveVocabReviewBody(
                syncId = sid,
                wordKey = wKey,
                language = word.language,
                normalized = word.normalized,
                rating = review.rating,
                reviewedAt = review.reviewedAt,
                intervalMinutes = review.intervalMinutes,
                dueAt = review.dueAt,
            )
            val bodyMap = DriveWireCodec.encodeVocabReviewBody(body)
            val hlc = nextHlc()
            val record = DriveWireCodec.putRecord(
                domain = DriveWireCodec.DOMAIN_VOCAB_REVIEW,
                key = sid,
                hlc = hlc,
                bodyMap = bodyMap,
            )
            candidateReviewPuts.add(
                review to DriveVocabReviewAckMeta(
                    reviewId = sid,
                    localReviewId = review.id,
                    language = word.language,
                    normalized = word.normalized,
                    recordFp = record.fp,
                    isDelete = false,
                ),
            )
            candidateReviewRecords.add(record)
        }

        // Record per-peer sync intent before publishing a review put, so an undo while the put is
        // built but not yet acknowledged still reaches `pendingDeleted`. A review undone since
        // it was read is dropped from this batch.
        if (candidateReviewPuts.isNotEmpty()) {
            database.withTransaction {
                for ((i, candidate) in candidateReviewPuts.withIndex()) {
                    val (review, ack) = candidate
                    if (vocabularyDao.review(review.id)?.syncId != ack.reviewId) continue
                    val current = vocabSyncDao.review(peerId, ack.reviewId)
                    if (current?.ackedDeleted == true || current?.pendingDeleted == true) continue
                    if (current == null) {
                        vocabSyncDao.upsertReview(
                            VocabularyReviewSync(
                                peerId = peerId,
                                reviewId = ack.reviewId,
                                language = ack.language,
                                normalized = ack.normalized,
                            ),
                        )
                    }
                    reviewRecords.add(candidateReviewRecords[i])
                    reviewAcks.add(ack)
                }
            }
        }

        // Final review undo tombstones (`op: "del"`): only explicit undos (`pendingDeleted`).
        // A review missing because its word was deleted (cascade) is never an undo.
        for (rSync in allReviewSyncs.values) {
            if (rSync.ackedDeleted || !rSync.pendingDeleted) continue

            val rState = allReviewStates[rSync.reviewId]
            val reuseUnpublishedDel = rState != null &&
                rState.versionOp == DriveRecordState.OP_DEL &&
                rState.publishedFp != rState.versionFp
            val hlc = if (reuseUnpublishedDel) rState!!.lastHlc else nextHlc()
            val delFp = if (reuseUnpublishedDel) rState!!.versionFp else DriveCanonicalJson.delFp(
                DriveWireCodec.DOMAIN_VOCAB_REVIEW,
                rSync.reviewId,
                hlc,
            )
            val record = DriveWireCodec.delRecord(
                domain = DriveWireCodec.DOMAIN_VOCAB_REVIEW,
                key = rSync.reviewId,
                hlc = hlc,
                explicitFp = delFp,
            )
            reviewRecords.add(record)
            reviewAcks.add(
                DriveVocabReviewAckMeta(
                    reviewId = rSync.reviewId,
                    localReviewId = null,
                    language = rSync.language,
                    normalized = rSync.normalized,
                    recordFp = delFp,
                    isDelete = true,
                ),
            )
        }

        return DriveVocabularyOutboundBundle(
            records = wordRecords + occRecords + reviewRecords,
            wordAcks = wordAcks,
            reviewAcks = reviewAcks,
        )
    }

    /**
     * Commits outbound delivery acknowledgements in two-DB order:
     * Transaction 1 (`liseur.db`: `vocabulary_word_sync`, `vocabulary_review_sync`) first,
     * then Transaction 2 (`drive_sync.db`: `drive_record_state`).
     */
    suspend fun acknowledgePublished(
        permissionId: String,
        batchSeq: Long,
        records: List<DriveWireRecord>,
        wordAcks: List<DriveVocabWordAckMeta>,
        reviewAcks: List<DriveVocabReviewAckMeta>,
        localDeviceId: String? = null,
    ) {
        val vocabRecords = records.filter {
            it.d == DriveWireCodec.DOMAIN_VOCAB_WORD ||
                it.d == DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE ||
                it.d == DriveWireCodec.DOMAIN_VOCAB_REVIEW
        }
        if (vocabRecords.isEmpty() && wordAcks.isEmpty() && reviewAcks.isEmpty()) return
        val peerId = DriveLibrarySync.accountPeerId(permissionId)

        // Published deletes durably record what they observed BEFORE the liseur.db acks, so a crash
        // between the two transactions cannot lose the boundary.
        val deleteBoundaries = mutableMapOf<String, Map<String, Long>>()
        for (ack in wordAcks) {
            if (!ack.isDelete) continue
            val observed = records.firstOrNull {
                it.d == DriveWireCodec.DOMAIN_VOCAB_WORD && it.k == ack.recordKey && it.op == DriveRecordState.OP_DEL
            }?.observed.orEmpty()
            // The delete also covers this device's own records up to this batch.
            val covered = if (localDeviceId != null) observed + (localDeviceId to batchSeq) else observed
            if (covered.isNotEmpty()) deleteBoundaries[ack.recordKey] = covered
        }
        if (deleteBoundaries.isNotEmpty()) {
            store.withTransaction {
                for ((key, observed) in deleteBoundaries) {
                    writeDeleteBoundary(permissionId, key, mergeBoundaries(readDeleteBoundary(permissionId, key), observed))
                }
            }
        }

        // Transaction 1: liseur.db per-peer sync acks.
        if (wordAcks.isNotEmpty() || reviewAcks.isNotEmpty()) {
            database.withTransaction {
                for (ack in wordAcks) {
                    val existingSync = vocabSyncDao.word(peerId, ack.language, ack.normalized)
                    if (ack.isDelete) {
                        val currentWord = vocabularyDao.findWord(ack.normalized, ack.language)
                        dropCoveredStagedReviews(
                            permissionId,
                            peerId,
                            ack.language,
                            ack.normalized,
                            readDeleteBoundary(permissionId, ack.recordKey),
                        )
                        if (currentWord == null) {
                            vocabSyncDao.upsertWord(
                                (existingSync ?: VocabularyWordSync(peerId, ack.language, ack.normalized)).copy(
                                    syncedWordId = null,
                                    lifecycle = VocabularyWordSync.SYNCED,
                                    ackedFingerprint = VocabularyWordSync.FINGERPRINT_DELETED,
                                    ackedBaseJson = null,
                                ),
                            )
                        }
                    } else {
                        val currentWord = vocabularyDao.findWord(ack.normalized, ack.language)
                        if (currentWord != null) {
                            vocabSyncDao.upsertWord(
                                (existingSync ?: VocabularyWordSync(peerId, ack.language, ack.normalized)).copy(
                                    syncedWordId = currentWord.id,
                                    lifecycle = VocabularyWordSync.SYNCED,
                                    ackedFingerprint = DriveCanonicalJson.contentFp(JSONObject(requireNotNull(ack.baseJson))),
                                    ackedBaseJson = ack.baseJson,
                                ),
                            )
                        }
                    }
                }
                for (ack in reviewAcks) {
                    val existingSync = vocabSyncDao.review(peerId, ack.reviewId)
                    if (ack.isDelete) {
                        vocabSyncDao.upsertReview(
                            (existingSync ?: VocabularyReviewSync(
                                peerId = peerId,
                                reviewId = ack.reviewId,
                                language = ack.language,
                                normalized = ack.normalized,
                            )).copy(
                                localReviewId = null,
                                ackedDeleted = true,
                                pendingDeleted = false,
                                pendingJson = null,
                            ),
                        )
                    } else {
                        if (existingSync?.ackedDeleted == true || existingSync?.pendingDeleted == true) continue
                        val live = vocabularyDao.reviewBySyncIdAnyOwner(ack.reviewId)
                        vocabSyncDao.upsertReview(
                            (existingSync ?: VocabularyReviewSync(
                                peerId = peerId,
                                reviewId = ack.reviewId,
                                language = ack.language,
                                normalized = ack.normalized,
                            )).copy(
                                localReviewId = live?.id ?: ack.localReviewId,
                                ackedDeleted = false,
                                pendingDeleted = false,
                            ),
                        )
                    }
                }
            }
        }

        // Transaction 2: drive_sync.db causal + delivery state.
        if (vocabRecords.isNotEmpty()) {
            store.withTransaction {
                for (record in vocabRecords) {
                    val existing = recordStates.get(permissionId, record.d, record.k)
                    val mergedAnc = mergeAncestors(
                        record.anc,
                        existing?.ancestorsList().orEmpty(),
                        exclude = record.fp,
                    )
                    val bodyStr = when {
                        record.body != null -> DriveCanonicalJson.encode(record.body)
                        record.d == DriveWireCodec.DOMAIN_VOCAB_WORD &&
                            record.op == DriveRecordState.OP_DEL &&
                            record.observed != null ->
                            DriveCanonicalJson.encode(mapOf("observed" to record.observed))
                        else -> null
                    }
                    recordStates.upsert(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = record.d,
                            recordKey = record.k,
                            versionFp = record.fp,
                            versionOp = record.op,
                            publishedFp = record.fp,
                            contentFp = if (record.op == DriveRecordState.OP_PUT) record.body?.let(DriveCanonicalJson::contentFp) else null,
                            lastSyncedRevision = existing?.lastSyncedRevision,
                            originDeviceId = null,
                            originSeq = if (record.d == DriveWireCodec.DOMAIN_VOCAB_REVIEW ||
                                record.d == DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE
                            ) {
                                batchSeq
                            } else {
                                null
                            },
                            ancestorsJson = DriveCanonicalJson.encode(mergedAnc),
                            resRefsJson = "[]",
                            lastHlc = maxOf(existing?.lastHlc.orEmpty(), record.hlc),
                            bodyJson = bodyStr,
                        ),
                    )
                    if (record.d == DriveWireCodec.DOMAIN_VOCAB_WORD && record.op == DriveRecordState.OP_DEL) {
                        val occPrefix = "${record.k}/"
                        val wordKeys = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_VOCAB_WORD)
                            .map { it.recordKey }
                        for (occState in recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE)) {
                            if (!occState.recordKey.startsWith(occPrefix)) continue
                            val ownerWordKey = occState.bodyJson
                                ?.let { raw -> runCatching { JSONObject(raw).optString("wordKey") }.getOrNull() }
                                ?.takeIf { it.isNotEmpty() }
                                ?: wordKeys
                                    .filter { occState.recordKey.startsWith("$it/") }
                                    .maxByOrNull { it.length }
                            if (ownerWordKey == null || ownerWordKey == record.k) {
                                recordStates.delete(permissionId, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE, occState.recordKey)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Applies an incoming remote `vocabWord` record (`put` or `del`). */
    suspend fun applyRemoteWord(
        permissionId: String,
        record: DriveWireRecord,
        remoteDeviceId: String,
        remoteSeq: Long,
        localDeviceId: String? = null,
        remoteObserved: Map<String, Long>? = null,
        provenanceKnown: Boolean = true,
    ) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        when (record.op) {
            DriveRecordState.OP_PUT -> {
                val bodyJson = record.body ?: return
                val remoteBody = DriveWireCodec.decodeVocabWordBody(bodyJson) ?: return
                val recordKey = wordKey(remoteBody.language, remoteBody.normalized)
                // A snapshot put carries the delete boundary its author holds, restored before any
                // staged review attaches so an earlier delete's coverage survives pruning.
                record.observed?.takeIf { it.isNotEmpty() }?.let { carried ->
                    val boundary = mergeBoundaries(readDeleteBoundary(permissionId, recordKey), carried)
                    store.withTransaction { writeDeleteBoundary(permissionId, recordKey, boundary) }
                    database.withTransaction {
                        dropCoveredStagedReviews(
                            permissionId, peerId, remoteBody.language, remoteBody.normalized, boundary,
                        )
                    }
                }
                val remoteBodyMap = DriveWireCodec.encodeVocabWordBody(remoteBody)
                val remoteCanonicalJson = DriveCanonicalJson.encode(remoteBodyMap)
                val remoteContentFp = DriveCanonicalJson.contentFp(remoteBodyMap)
                val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_VOCAB_WORD, recordKey)
                val existingAnc = existingState?.ancestorsList().orEmpty()

                // Stale remote put predating our current version (including a causal local delete),
                // unless the incoming put explicitly supersedes our current version with a newer HLC
                // (e.g. add-wins resurrection with unchanged word metadata after a delete).
                if (existingState != null &&
                    record.fp in existingAnc &&
                    !(existingState.versionFp in record.anc && record.hlc > existingState.lastHlc)
                ) {
                    return
                }

                var converged = true
                var resultFp = record.fp
                var resultContentFp = remoteContentFp
                var resultBodyJson = remoteCanonicalJson

                // Transaction 1: liseur.db word insert/merge + staged reviews + schedule reconcile + ack.
                database.withTransaction {
                    val localRows = vocabularyDao.findAllWordsByKey(remoteBody.normalized, remoteBody.language)
                    val localWord = localRows.firstOrNull()
                    val sync = vocabSyncDao.word(peerId, remoteBody.language, remoteBody.normalized)

                    if (localWord == null) {
                        // If we deleted this exact version locally and have not yet published our `del`,
                        // do not let a snapshot replay of the deleted version resurrect it.
                        // The same holds after a crash between this record's Tx1 and Tx2: the receipt
                        // already acknowledges it, so the word's absence is a later local delete that
                        // collection will publish (with this version in its ancestry).
                        val deletedAfterApply = existingState != null &&
                            existingState.versionOp == DriveRecordState.OP_PUT &&
                            existingState.versionFp == record.fp ||
                            sync?.ackedFingerprint == remoteContentFp
                        if (deletedAfterApply) {
                            return@withTransaction
                        }
                        val baseline = remoteBody.baseline
                        val inserted = VocabularyWord(
                            word = remoteBody.word,
                            normalized = remoteBody.normalized,
                            language = remoteBody.language,
                            definition = remoteBody.definition,
                            translationLanguage = remoteBody.translationLanguage,
                            notes = remoteBody.notes,
                            addedAt = remoteBody.addedAt,
                            dueAt = baseline?.dueAt ?: remoteBody.addedAt,
                            reviewedAt = baseline?.reviewedAt,
                            streak = baseline?.streak ?: 0,
                            intervalMinutes = baseline?.intervalMinutes ?: 0,
                            repetitions = baseline?.repetitions ?: 0,
                            definitionEdited = remoteBody.definitionEdited,
                            ownerAccount = "",
                            definitionUpdatedAt = remoteBody.definitionUpdatedAt,
                            notesUpdatedAt = remoteBody.notesUpdatedAt,
                            baselineDueAt = baseline?.dueAt,
                            baselineReviewedAt = baseline?.reviewedAt,
                            baselineStreak = baseline?.streak ?: 0,
                            baselineIntervalMinutes = baseline?.intervalMinutes ?: 0,
                            baselineRepetitions = baseline?.repetitions ?: 0,
                        ).withSearchText()
                        val wordId = vocabularyDao.insertWord(inserted)
                        attachStagedReviews(peerId, remoteBody.language, remoteBody.normalized, wordId)
                        reconcileWordSchedule(wordId)
                        vocabSyncDao.upsertWord(
                            (sync ?: VocabularyWordSync(peerId, remoteBody.language, remoteBody.normalized)).copy(
                                syncedWordId = wordId,
                                lifecycle = VocabularyWordSync.SYNCED,
                                ackedFingerprint = remoteContentFp,
                                ackedBaseJson = remoteCanonicalJson,
                            ),
                        )
                    } else {
                        val localBody = localWord.toWireBody()
                        val localContentFp = DriveCanonicalJson.contentFp(DriveWireCodec.encodeVocabWordBody(localBody))
                        if (localContentFp == remoteContentFp) {
                            attachStagedReviews(peerId, remoteBody.language, remoteBody.normalized, localWord.id)
                            reconcileWordSchedule(localWord.id)
                            vocabSyncDao.upsertWord(
                                (sync ?: VocabularyWordSync(peerId, remoteBody.language, remoteBody.normalized)).copy(
                                    syncedWordId = localWord.id,
                                    lifecycle = VocabularyWordSync.SYNCED,
                                    ackedFingerprint = remoteContentFp,
                                    ackedBaseJson = remoteCanonicalJson,
                                ),
                            )
                        } else {
                            // Only use a stored base when it is a true causal ancestor of `record`.
                            // For concurrent simultaneous uploads (`versionFp !in record.anc`), pass
                            // `base = null` so `VocabularyMerge` merges field-by-field by timestamps
                            // instead of mistaking our own unmerged upload for the common ancestor.
                            val baseMetadata = when {
                                sync != null &&
                                    sync.ackedFingerprint.isNotBlank() &&
                                    sync.ackedFingerprint in record.anc ->
                                    parseBaseWordMetadata(sync.ackedBaseJson)
                                existingState != null &&
                                    existingState.versionOp == DriveRecordState.OP_PUT &&
                                    existingState.versionFp in record.anc ->
                                    parseBaseWordMetadata(existingState.bodyJson)
                                else -> null
                            }
                            val mergedMeta = VocabularyMerge.mergeWordMetadata(
                                base = baseMetadata,
                                local = localWord.toMergeMetadata(),
                                remote = remoteBody.toMergeMetadata(),
                            )
                            val updatedWord = localWord.applyingMetadata(mergedMeta).withSearchText()
                            vocabularyDao.updateWord(updatedWord)
                            attachStagedReviews(peerId, remoteBody.language, remoteBody.normalized, updatedWord.id)
                            reconcileWordSchedule(updatedWord.id)

                            val mergedBodyMap = DriveWireCodec.encodeVocabWordBody(updatedWord.toWireBody())
                            val mergedContentFp = DriveCanonicalJson.contentFp(mergedBodyMap)
                            val mergedJson = DriveCanonicalJson.encode(mergedBodyMap)
                            converged = (mergedContentFp == remoteContentFp)
                            resultFp = if (converged) record.fp else mergedContentFp
                            resultContentFp = mergedContentFp
                            resultBodyJson = mergedJson

                            vocabSyncDao.upsertWord(
                                (sync ?: VocabularyWordSync(peerId, remoteBody.language, remoteBody.normalized)).copy(
                                    syncedWordId = updatedWord.id,
                                    lifecycle = VocabularyWordSync.SYNCED,
                                    ackedFingerprint = if (converged) remoteContentFp else "",
                                    ackedBaseJson = remoteCanonicalJson,
                                ),
                            )
                        }
                    }
                }

                // Transaction 2: drive_sync.db causal state.
                store.withTransaction {
                    val prev = recordStates.get(permissionId, DriveWireCodec.DOMAIN_VOCAB_WORD, recordKey)
                    val mergedAnc = mergeAncestors(
                        if (converged) record.anc else listOf(record.fp) + record.anc,
                        listOfNotNull(prev?.versionFp) + prev?.ancestorsList().orEmpty(),
                        exclude = resultFp,
                    )
                    // Do not overwrite local authorship when this device already authored and published
                    // the exact converged content (otherwise both converged devices drop it on compaction).
                    val preserveLocalOrigin = converged &&
                        prev != null &&
                        prev.originDeviceId == null &&
                        prev.versionOp == DriveRecordState.OP_PUT &&
                        prev.contentFp == resultContentFp &&
                        prev.publishedFp == prev.versionFp
                    recordStates.upsert(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
                            recordKey = recordKey,
                            versionFp = resultFp,
                            versionOp = DriveRecordState.OP_PUT,
                            publishedFp = if (converged) resultFp else null,
                            contentFp = resultContentFp,
                            originDeviceId = when {
                                !converged || preserveLocalOrigin -> null
                                else -> remoteDeviceId
                            },
                            originSeq = when {
                                !converged -> null
                                preserveLocalOrigin -> prev?.originSeq
                                else -> remoteSeq
                            },
                            ancestorsJson = DriveCanonicalJson.encode(mergedAnc),
                            resRefsJson = "[]",
                            lastHlc = maxOf(prev?.lastHlc.orEmpty(), record.hlc),
                            bodyJson = resultBodyJson,
                        ),
                    )
                }
            }

            DriveRecordState.OP_DEL -> {
                val (language, normalized) = parseWordKey(record.k) ?: return
                val recordKey = wordKey(language, normalized)
                val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_VOCAB_WORD, recordKey)
                val existingAnc = existingState?.ancestorsList().orEmpty()

                // Stale delete already superseded by an add-wins or resurrection.
                if (existingState != null && record.fp in existingAnc) {
                    return
                }

                val appliedBoundary = mergeBoundaries(
                    readDeleteBoundary(permissionId, recordKey),
                    deleteBoundaryVector(remoteDeviceId, remoteSeq, remoteObserved, provenanceKnown),
                )
                val localWords = vocabularyDao.findAllWordsByKey(normalized, language)
                if (localWords.isEmpty()) {
                    // Both devices agree the word is deleted.
                    database.withTransaction {
                        dropCoveredStagedReviews(permissionId, peerId, language, normalized, appliedBoundary)
                        val sync = vocabSyncDao.word(peerId, language, normalized)
                        vocabSyncDao.upsertWord(
                            (sync ?: VocabularyWordSync(peerId, language, normalized)).copy(
                                syncedWordId = null,
                                lifecycle = VocabularyWordSync.SYNCED,
                                ackedFingerprint = VocabularyWordSync.FINGERPRINT_DELETED,
                                ackedBaseJson = null,
                            ),
                        )
                    }
                    store.withTransaction {
                        writeDeleteBoundary(permissionId, recordKey, appliedBoundary)
                        val adoptIncoming = existingState == null ||
                            existingState.versionOp != DriveRecordState.OP_DEL ||
                            compareValuesBy(
                                record.hlc to record.fp,
                                existingState.lastHlc to existingState.versionFp,
                                { it.first },
                                { it.second },
                            ) > 0
                        val chosenFp = if (adoptIncoming) record.fp else existingState!!.versionFp
                        val chosenHlc = maxOf(existingState?.lastHlc.orEmpty(), record.hlc)
                        val mergedAnc = mergeAncestors(
                            record.anc,
                            listOfNotNull(existingState?.versionFp) + existingAnc,
                            exclude = chosenFp,
                        )
                        recordStates.upsert(
                            DriveRecordState(
                                permissionId = permissionId,
                                domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
                                recordKey = recordKey,
                                versionFp = chosenFp,
                                versionOp = DriveRecordState.OP_DEL,
                                publishedFp = chosenFp,
                                contentFp = null,
                                originDeviceId = if (adoptIncoming) remoteDeviceId else existingState?.originDeviceId,
                                originSeq = if (adoptIncoming) remoteSeq else existingState?.originSeq,
                                ancestorsJson = DriveCanonicalJson.encode(mergedAnc),
                                resRefsJson = "[]",
                                lastHlc = chosenHlc,
                                bodyJson = if (adoptIncoming) null else existingState?.bodyJson,
                            ),
                        )
                    }
                    return
                }

                // Local word exists: check whether it is strictly untouched or has a concurrent
                // local edit, new/edited occurrence, or concurrent live review (add-wins).
                val causallySuperseded = existingState != null &&
                    existingState.versionOp == DriveRecordState.OP_PUT &&
                    existingState.versionFp in record.anc
                val allReviewStates = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_VOCAB_REVIEW)
                    .associateBy { it.recordKey }
                val allOccStates = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE)
                    .associateBy { it.recordKey }

                // Whether the deleting device had seen a record authored by `origin` at `seq`:
                // its own records up to this batch, anyone else's per its observed vector.
                fun observedByDeleter(originDeviceId: String?, seq: Long?): Boolean {
                    val authoredSeq = seq ?: return false
                    val origin = originDeviceId ?: localDeviceId ?: return false
                    if (origin == remoteDeviceId) return authoredSeq <= remoteSeq
                    val seen = remoteObserved?.get(origin) ?: return false
                    return seen >= authoredSeq
                }

                // Evaluated against liseur.db inside the deleting transaction, so an edit or review
                // committed meanwhile is seen and turns the delete into add-wins.
                suspend fun untouchedLocally(words: List<VocabularyWord>): Boolean {
                    if (!causallySuperseded || words.isEmpty()) return false
                    val currentBodyMap = DriveWireCodec.encodeVocabWordBody(words.first().toWireBody())
                    if (existingState?.contentFp != DriveCanonicalJson.contentFp(currentBodyMap)) return false

                    val localReviews = words.flatMap { vocabularyDao.reviews(it.id) }
                    val syncedReviewIds = vocabSyncDao.reviewsForWord(peerId, language, normalized)
                        .filter { !it.ackedDeleted && !it.pendingDeleted && it.localReviewId != null }
                        .map { it.reviewId }
                        .toSet()
                    val hasUnobservedLiveReview = localReviews.any { review ->
                        val sid = review.syncId
                        if (sid == null || sid !in syncedReviewIds) {
                            true
                        } else {
                            val rState = allReviewStates[sid]
                            rState == null || !observedByDeleter(rState.originDeviceId, rState.originSeq)
                        }
                    }
                    if (hasUnobservedLiveReview) return false

                    val localOccs = words.flatMap { vocabularyDao.occurrencesList(it.id) }
                    val aliasByBookUrl = identities.aliasesFor(peerId)
                        .filter { it.usable }
                        .associateBy { it.bookUrl }
                    return localOccs.none { occ ->
                        val resolvedWorkKey = resolveOccurrenceWorkKey(permissionId, occ, aliasByBookUrl)
                        val occKey = if (resolvedWorkKey != null) {
                            VocabularyMerge.canonicalOccurrenceKey(resolvedWorkKey, occ.locationKey)
                        } else {
                            occ.occurrenceKey
                        }
                        val occBodyMap = DriveWireCodec.encodeVocabOccurrenceBody(
                            occ.copy(occurrenceKey = occKey).toWireBody(recordKey, resolvedWorkKey),
                        )
                        val occFp = DriveCanonicalJson.contentFp(occBodyMap)
                        val occState = allOccStates[occurrenceRecordKey(recordKey, occKey)]
                        occState == null ||
                            occState.publishedFp != occState.versionFp ||
                            occState.contentFp != occFp ||
                            !observedByDeleter(occState.originDeviceId, occState.originSeq)
                    }
                }

                var isUntouched = false
                database.withTransaction {
                    val words = vocabularyDao.findAllWordsByKey(normalized, language)
                    if (!untouchedLocally(words)) return@withTransaction
                    isUntouched = true
                    // Silent delete on untouched local copy.
                    for (w in words) {
                        vocabularyDao.deleteWord(w.id)
                    }
                    dropCoveredStagedReviews(permissionId, peerId, language, normalized, appliedBoundary)
                    val sync = vocabSyncDao.word(peerId, language, normalized)
                    vocabSyncDao.upsertWord(
                        (sync ?: VocabularyWordSync(peerId, language, normalized)).copy(
                            syncedWordId = null,
                            lifecycle = VocabularyWordSync.SYNCED,
                            ackedFingerprint = VocabularyWordSync.FINGERPRINT_DELETED,
                            ackedBaseJson = null,
                        ),
                    )
                }

                if (isUntouched) {
                    store.withTransaction {
                        writeDeleteBoundary(permissionId, recordKey, appliedBoundary)
                        val mergedAnc = mergeAncestors(
                            record.anc,
                            listOfNotNull(existingState?.versionFp) + existingAnc,
                            exclude = record.fp,
                        )
                        recordStates.upsert(
                            DriveRecordState(
                                permissionId = permissionId,
                                domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
                                recordKey = recordKey,
                                versionFp = record.fp,
                                versionOp = DriveRecordState.OP_DEL,
                                publishedFp = record.fp,
                                contentFp = null,
                                originDeviceId = remoteDeviceId,
                                originSeq = remoteSeq,
                                ancestorsJson = DriveCanonicalJson.encode(mergedAnc),
                                resRefsJson = "[]",
                                lastHlc = maxOf(existingState?.lastHlc.orEmpty(), record.hlc),
                                bodyJson = null,
                            ),
                        )
                        val occPrefix = "$recordKey/"
                        for (occState in recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE)) {
                            if (occState.recordKey.startsWith(occPrefix)) {
                                recordStates.delete(permissionId, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE, occState.recordKey)
                            }
                        }
                    }
                } else {
                    // Add-wins silently! Keep local word, occurrences, and live reviews, and force
                    // republish with `record.fp` (the delFp) in `anc` so the deleter resurrects cleanly.
                    val currentWord = vocabularyDao.findAllWordsByKey(normalized, language).firstOrNull()
                        ?: localWords.first()
                    val currentBodyMap = DriveWireCodec.encodeVocabWordBody(currentWord.toWireBody())
                    val currentContentFp = DriveCanonicalJson.contentFp(currentBodyMap)
                    database.withTransaction {
                        val sync = vocabSyncDao.word(peerId, language, normalized)
                        if (sync != null) {
                            vocabSyncDao.upsertWord(sync.copy(ackedFingerprint = ""))
                        }
                        // Re-mark live reviews for this word as needing publish so the deleter
                        // recovers cascaded live reviews idempotently (never touching undone tombstones).
                        for (rSync in vocabSyncDao.reviewsForWord(peerId, language, normalized)) {
                            if (!rSync.ackedDeleted && !rSync.pendingDeleted) {
                                vocabSyncDao.upsertReview(rSync.copy(localReviewId = null))
                            }
                        }
                    }
                    store.withTransaction {
                        val mergedAnc = mergeAncestors(
                            listOf(record.fp) + record.anc,
                            listOfNotNull(existingState?.versionFp) + existingAnc,
                            exclude = currentContentFp,
                        )
                        recordStates.upsert(
                            DriveRecordState(
                                permissionId = permissionId,
                                domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
                                recordKey = recordKey,
                                versionFp = currentContentFp,
                                versionOp = DriveRecordState.OP_PUT,
                                publishedFp = null, // dirty -> republish with delFp in anc!
                                contentFp = currentContentFp,
                                originDeviceId = null,
                                originSeq = null,
                                ancestorsJson = DriveCanonicalJson.encode(mergedAnc),
                                resRefsJson = "[]",
                                lastHlc = maxOf(existingState?.lastHlc.orEmpty(), record.hlc),
                                bodyJson = DriveCanonicalJson.encode(currentBodyMap),
                            ),
                        )
                        // Also mark occurrences of this word dirty so deleter regains context sentences.
                        val occPrefix = "$recordKey/"
                        for (occState in recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE)) {
                            if (occState.recordKey.startsWith(occPrefix)) {
                                recordStates.upsert(occState.copy(publishedFp = null))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Applies an incoming remote `vocabOccurrence` record (`put`).
     *
     * Returns `true` when the occurrence was applied, ignored as stale/malformed, or dropped
     * because its parent word has been durably deleted; returns `false` (deferred) when the
     * parent word is not yet present locally so [DriveChangeEngine] retains the occurrence
     * cursor and replays once the word batch arrives.
     */
    suspend fun applyRemoteOccurrence(
        permissionId: String,
        record: DriveWireRecord,
        remoteDeviceId: String,
        remoteSeq: Long,
    ): Boolean {
        if (record.op != DriveRecordState.OP_PUT) return true
        val bodyJson = record.body ?: return true
        val remoteBody = DriveWireCodec.decodeVocabOccurrenceBody(bodyJson) ?: return true
        val (language, normalized) = parseWordKey(remoteBody.wordKey) ?: return true

        val canonicalWorkKey = remoteBody.workKey?.let { workIdentity.canonicalWorkKey(permissionId, it) }
        val canonicalOccKey = if (canonicalWorkKey != null) {
            VocabularyMerge.canonicalOccurrenceKey(canonicalWorkKey, remoteBody.locationKey)
        } else {
            remoteBody.occurrenceKey
        }
        val normalizedBody = remoteBody.copy(
            workKey = canonicalWorkKey,
            occurrenceKey = canonicalOccKey,
        )
        val recordKey = occurrenceRecordKey(remoteBody.wordKey, canonicalOccKey)
        val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE, recordKey)
        if (existingState != null &&
            record.fp in existingState.ancestorsList() &&
            !(existingState.versionFp in record.anc && record.hlc > existingState.lastHlc)
        ) {
            return true
        }

        val resolvedBookUrl = canonicalWorkKey?.let { findBookUrlForWorkKey(permissionId, it) }
            ?: canonicalWorkKey?.let(DriveWorkIdentity::driveBookUrl)
            ?: ""

        var wordExists = false
        var mergedOcc: VocabularyMerge.Occurrence? = null
        val remoteMergeOcc = normalizedBody.toMergeOccurrence()
        val baseMergeOcc = if (
            existingState != null &&
            existingState.versionOp == DriveRecordState.OP_PUT &&
            existingState.versionFp in record.anc
        ) {
            existingState.bodyJson?.let(::parseBaseOccurrence)
        } else {
            null
        }

        // Transaction 1: liseur.db occurrence merge.
        database.withTransaction {
            val localWord = vocabularyDao.findWord(normalized, language) ?: return@withTransaction
            wordExists = true
            val localOccs = vocabularyDao.occurrencesList(localWord.id)
            val mergedList = VocabularyMerge.mergeOccurrences(
                base = listOfNotNull(baseMergeOcc),
                local = localOccs.map { it.toMergeOccurrence() },
                remote = listOf(remoteMergeOcc),
            )
            val localBookByKey = localOccs.associate { it.occurrenceKey to it.bookUrl }
            val localIdByKey = localOccs.associate { it.occurrenceKey to it.id }
            val localBookByWorkLoc = localOccs
                .filter { !it.workId.isNullOrBlank() && it.bookUrl.isNotEmpty() }
                .associate { (it.workId!! to VocabularyMerge.wireLocationKey(it.locationKey)) to it.bookUrl }

            vocabularyDao.deleteOccurrencesForWord(localWord.id)
            for (item in mergedList) {
                val bookUrl = localBookByKey[item.occurrenceKey]?.takeIf { it.isNotEmpty() }
                    ?: item.workId?.let { wid -> localBookByWorkLoc[wid to VocabularyMerge.wireLocationKey(item.locationKey)] }
                    ?: if (item.occurrenceKey == canonicalOccKey) resolvedBookUrl else ""
                vocabularyDao.insertOccurrence(
                    VocabularyOccurrence(
                        id = localIdByKey[item.occurrenceKey] ?: 0,
                        wordId = localWord.id,
                        bookUrl = bookUrl,
                        bookTitle = item.bookTitle,
                        locationKey = item.locationKey,
                        locator = item.locator,
                        sentence = item.sentence,
                        lastSeenAt = item.lastSeenAt,
                        encounters = item.encounters.coerceAtLeast(1),
                        searchSentence = normalizeVocabularySearch(item.sentence.orEmpty()),
                        workId = item.workId,
                        occurrenceKey = item.occurrenceKey,
                        sentenceEdited = item.sentenceEdited,
                        sentenceUpdatedAt = item.sentenceUpdatedAt,
                    ),
                )
            }
            mergedOcc = mergedList.firstOrNull { it.occurrenceKey == canonicalOccKey } ?: remoteMergeOcc
        }

        if (!wordExists) {
            val peerId = DriveLibrarySync.accountPeerId(permissionId)
            val canonicalWordKey = wordKey(language, normalized)
            val wordState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_VOCAB_WORD, canonicalWordKey)
            val wordSync = vocabSyncDao.word(peerId, language, normalized)
            return wordState?.versionOp == DriveRecordState.OP_DEL ||
                wordSync?.ackedFingerprint == VocabularyWordSync.FINGERPRINT_DELETED
        }

        val finalOcc = mergedOcc ?: remoteMergeOcc
        val remoteBodyMap = DriveWireCodec.encodeVocabOccurrenceBody(normalizedBody)
        val remoteContentFp = DriveCanonicalJson.contentFp(remoteBodyMap)
        val mergedBody = DriveVocabOccurrenceBody(
            wordKey = normalizedBody.wordKey,
            occurrenceKey = finalOcc.occurrenceKey,
            workKey = finalOcc.workId,
            bookTitle = finalOcc.bookTitle,
            locationKey = VocabularyMerge.wireLocationKey(finalOcc.locationKey),
            locator = finalOcc.locator,
            sentence = finalOcc.sentence,
            sentenceEdited = finalOcc.sentenceEdited,
            sentenceUpdatedAt = finalOcc.sentenceUpdatedAt,
            lastSeenAt = finalOcc.lastSeenAt,
            encounters = finalOcc.encounters,
        )
        val mergedBodyMap = DriveWireCodec.encodeVocabOccurrenceBody(mergedBody)
        val mergedContentFp = DriveCanonicalJson.contentFp(mergedBodyMap)
        val converged = mergedContentFp == remoteContentFp
        val resultFp = if (converged) record.fp else mergedContentFp

        // Transaction 2: drive_sync.db occurrence state.
        store.withTransaction {
            val prev = recordStates.get(permissionId, DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE, recordKey)
            val mergedAnc = mergeAncestors(
                if (converged) record.anc else listOf(record.fp) + record.anc,
                listOfNotNull(prev?.versionFp) + prev?.ancestorsList().orEmpty(),
                exclude = resultFp,
            )
            // Do not overwrite local authorship on content agreement with a locally authored occurrence.
            val preserveLocalOrigin = converged &&
                prev != null &&
                prev.originDeviceId == null &&
                prev.versionOp == DriveRecordState.OP_PUT &&
                prev.contentFp == mergedContentFp &&
                prev.publishedFp == prev.versionFp
            recordStates.upsert(
                DriveRecordState(
                    permissionId = permissionId,
                    domain = DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE,
                    recordKey = recordKey,
                    versionFp = resultFp,
                    versionOp = DriveRecordState.OP_PUT,
                    publishedFp = if (converged) resultFp else null,
                    contentFp = mergedContentFp,
                    originDeviceId = when {
                        !converged || preserveLocalOrigin -> null
                        else -> remoteDeviceId
                    },
                    originSeq = when {
                        !converged -> null
                        preserveLocalOrigin -> prev?.originSeq
                        else -> remoteSeq
                    },
                    ancestorsJson = DriveCanonicalJson.encode(mergedAnc),
                    resRefsJson = "[]",
                    lastHlc = maxOf(prev?.lastHlc.orEmpty(), record.hlc),
                    bodyJson = DriveCanonicalJson.encode(mergedBodyMap),
                ),
            )
        }
        return true
    }

    /** Applies an incoming remote `vocabReview` record (`put` or final undo `del`). */
    suspend fun applyRemoteReview(
        permissionId: String,
        record: DriveWireRecord,
        remoteDeviceId: String,
        remoteSeq: Long,
        provenanceKnown: Boolean = true,
    ) {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        when (record.op) {
            DriveRecordState.OP_DEL -> {
                val syncId = record.k
                // Transaction 1: final review undo tombstone.
                database.withTransaction {
                    vocabSyncDao.deleteStaged(peerId, syncId)
                    var language = ""
                    var normalized = ""
                    // The unique index is (wordId, syncId): unowned and retired-account copies of
                    // one word can each hold the undone event.
                    val copies = vocabularyDao.reviewsBySyncIdAnyOwner(syncId)
                    for (copy in copies) {
                        val word = vocabularyDao.word(copy.wordId)
                        if (language.isEmpty() && word != null) {
                            language = word.language
                            normalized = word.normalized
                        }
                        vocabularyDao.deleteReview(copy.id)
                    }
                    for (wordId in copies.mapTo(linkedSetOf()) { it.wordId }) reconcileWordSchedule(wordId)
                    val existingSync = vocabSyncDao.review(peerId, syncId)
                    vocabSyncDao.upsertReview(
                        (existingSync ?: VocabularyReviewSync(
                            peerId = peerId,
                            reviewId = syncId,
                            language = language,
                            normalized = normalized,
                        )).copy(
                            localReviewId = null,
                            ackedDeleted = true,
                            pendingDeleted = false,
                            pendingJson = null,
                        ),
                    )
                }
                // Transaction 2: record final review tombstone in drive_sync.db.
                store.withTransaction {
                    val prev = recordStates.get(permissionId, DriveWireCodec.DOMAIN_VOCAB_REVIEW, syncId)
                    val preserveLocalOrigin = prev != null &&
                        prev.originDeviceId == null &&
                        prev.versionOp == DriveRecordState.OP_DEL &&
                        prev.publishedFp == prev.versionFp
                    recordStates.upsert(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_VOCAB_REVIEW,
                            recordKey = syncId,
                            versionFp = if (preserveLocalOrigin) prev!!.versionFp else record.fp,
                            versionOp = DriveRecordState.OP_DEL,
                            publishedFp = if (preserveLocalOrigin) prev!!.versionFp else record.fp,
                            contentFp = null,
                            originDeviceId = if (preserveLocalOrigin) null else remoteDeviceId,
                            originSeq = if (preserveLocalOrigin) prev?.originSeq else remoteSeq,
                            ancestorsJson = "[]",
                            resRefsJson = "[]",
                            lastHlc = maxOf(prev?.lastHlc.orEmpty(), record.hlc),
                            bodyJson = null,
                        ),
                    )
                }
            }

            DriveRecordState.OP_PUT -> {
                val bodyJson = record.body ?: return
                val remoteBody = DriveWireCodec.decodeVocabReviewBody(bodyJson) ?: return
                val syncId = remoteBody.syncId

                // Final undo tombstone check in drive_sync.db before touching liseur.db.
                val existingState = recordStates.get(permissionId, DriveWireCodec.DOMAIN_VOCAB_REVIEW, syncId)
                if (existingState?.versionOp == DriveRecordState.OP_DEL) {
                    return
                }

                // A put the word's delete already covered must not come back (replay, snapshot,
                // out-of-order stream); one the deleter had not observed stays.
                val wordRecordKey = wordKey(remoteBody.language, remoteBody.normalized)
                if (provenanceKnown &&
                    boundaryCovers(readDeleteBoundary(permissionId, wordRecordKey), remoteDeviceId, remoteSeq)
                ) {
                    vocabSyncDao.deleteStaged(peerId, syncId)
                    return
                }

                var ignoredAsUndone = false
                // Transaction 1: idempotent insert + schedule derivation (or stage if word hasn't arrived yet).
                database.withTransaction {
                    val existingSync = vocabSyncDao.review(peerId, syncId)
                    if (existingSync != null && (existingSync.ackedDeleted || existingSync.pendingDeleted)) {
                        vocabSyncDao.deleteStaged(peerId, syncId)
                        ignoredAsUndone = true
                        return@withTransaction
                    }

                    val localWord = vocabularyDao.findWord(remoteBody.normalized, remoteBody.language)
                    if (localWord == null) {
                        // Word has not arrived yet (e.g. out-of-order batch): stage review for attachment.
                        vocabSyncDao.upsertStaged(
                            VocabularyStagedReview(
                                peerId = peerId,
                                reviewId = syncId,
                                language = remoteBody.language,
                                normalized = remoteBody.normalized,
                                wordGeneration = DRIVE_STAGED_GENERATION,
                                rating = remoteBody.rating,
                                reviewedAt = remoteBody.reviewedAt,
                                intervalMinutes = remoteBody.intervalMinutes,
                                dueAt = remoteBody.dueAt,
                                seq = remoteSeq,
                            ),
                        )
                        return@withTransaction
                    }

                    val existingReview = vocabularyDao.reviewBySyncIdAnyOwner(syncId)
                    val localReviewId = if (existingReview != null) {
                        existingReview.id
                    } else {
                        val insertedId = vocabularyDao.insertReviewIgnoringConflicts(
                            VocabularyReview(
                                wordId = localWord.id,
                                rating = remoteBody.rating,
                                reviewedAt = remoteBody.reviewedAt,
                                intervalMinutes = remoteBody.intervalMinutes,
                                dueAt = remoteBody.dueAt,
                                firstReview = false,
                                syncId = syncId,
                            ),
                        )
                        reconcileWordSchedule(localWord.id)
                        if (insertedId > 0L) insertedId else vocabularyDao.reviewBySyncIdAnyOwner(syncId)?.id
                    }

                    vocabSyncDao.deleteStaged(peerId, syncId)
                    vocabSyncDao.upsertReview(
                        VocabularyReviewSync(
                            peerId = peerId,
                            reviewId = syncId,
                            localReviewId = localReviewId,
                            language = remoteBody.language,
                            normalized = remoteBody.normalized,
                            wordGeneration = DRIVE_STAGED_GENERATION,
                            seq = remoteSeq,
                            ackedDeleted = false,
                            pendingJson = null,
                            pendingDeleted = false,
                        ),
                    )
                }

                if (ignoredAsUndone) return

                // Transaction 2: record review state in drive_sync.db.
                val canonicalBody = DriveCanonicalJson.encode(DriveWireCodec.encodeVocabReviewBody(remoteBody))
                store.withTransaction {
                    val prev = recordStates.get(permissionId, DriveWireCodec.DOMAIN_VOCAB_REVIEW, syncId)
                    if (prev?.versionOp == DriveRecordState.OP_DEL) return@withTransaction
                    val preserveLocalOrigin = prev != null &&
                        prev.originDeviceId == null &&
                        prev.versionOp == DriveRecordState.OP_PUT &&
                        prev.versionFp == record.fp &&
                        prev.publishedFp == prev.versionFp
                    recordStates.upsert(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_VOCAB_REVIEW,
                            recordKey = syncId,
                            versionFp = record.fp,
                            versionOp = DriveRecordState.OP_PUT,
                            publishedFp = record.fp,
                            contentFp = record.fp,
                            originDeviceId = if (preserveLocalOrigin) null else remoteDeviceId,
                            originSeq = if (preserveLocalOrigin) prev?.originSeq else if (provenanceKnown) remoteSeq else null,
                            ancestorsJson = "[]",
                            resRefsJson = "[]",
                            lastHlc = maxOf(prev?.lastHlc.orEmpty(), record.hlc),
                            bodyJson = canonicalBody,
                        ),
                    )
                }
            }
        }
    }

    // -- Internal helpers ----------------------------------------------------

    private suspend fun coalesceDuplicateVisibleWordsAndBackfillReviewIds(permissionId: String) {
        val visibleWords = vocabularyDao.exportWords()
        val grouped = visibleWords.groupBy { it.language to it.normalized }
        val hasDuplicates = grouped.values.any { it.size > 1 }
        val reviewsMissingSyncId = vocabularyDao.exportReviews().filter { it.syncId.isNullOrBlank() }
        if (!hasDuplicates && reviewsMissingSyncId.isEmpty()) return

        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val aliasByBookUrl = identities.aliasesFor(peerId)
            .filter { it.usable }
            .associateBy { it.bookUrl }

        val undoneReviewIds = recordStates.forDomain(permissionId, DriveWireCodec.DOMAIN_VOCAB_REVIEW)
            .filter { it.versionOp == DriveRecordState.OP_DEL }
            .mapTo(mutableSetOf()) { it.recordKey }
        vocabSyncDao.allReviews(peerId)
            .filter { it.ackedDeleted || it.pendingDeleted }
            .mapTo(undoneReviewIds) { it.reviewId }

        database.withTransaction {
            // 1. Backfill deterministic legacy syncIds BEFORE coalescing duplicate word rows
            // so matching legacy reviews across an unowned row and a retired-account duplicate
            // resolve to the same event ID and collapse cleanly (docs/DRIVE_SYNC.md §4.11).
            val wordsById = vocabularyDao.exportWords().associateBy { it.id }
            val existingSyncIdsByWord = mutableMapOf<Long, MutableSet<String>>()
            val reconciledWordIds = mutableSetOf<Long>()
            val allReviews = vocabularyDao.exportReviews()
            for (review in allReviews) {
                val sid = review.syncId?.takeIf { it.isNotBlank() }
                if (sid != null) {
                    existingSyncIdsByWord.getOrPut(review.wordId) { mutableSetOf() }.add(sid)
                }
            }
            for (review in allReviews.sortedBy { it.id }) {
                if (!review.syncId.isNullOrBlank()) continue
                val word = wordsById[review.wordId] ?: continue
                val usedForWord = existingSyncIdsByWord.getOrPut(review.wordId) { mutableSetOf() }
                val deterministicId = deterministicLegacyReviewId(
                    language = word.language,
                    normalized = word.normalized,
                    reviewedAt = review.reviewedAt,
                    rating = review.rating,
                )
                if (deterministicId in undoneReviewIds) {
                    vocabularyDao.deleteReview(review.id)
                    reconciledWordIds.add(review.wordId)
                } else if (usedForWord.add(deterministicId)) {
                    vocabularyDao.updateReview(review.copy(syncId = deterministicId))
                } else {
                    vocabularyDao.deleteReview(review.id)
                    reconciledWordIds.add(review.wordId)
                }
            }

            // 2. Coalesce duplicate visible words sharing (language, normalized) into one canonical row.
            val currentGrouped = vocabularyDao.exportWords().groupBy { it.language to it.normalized }
            for ((_, group) in currentGrouped) {
                if (group.size <= 1) continue
                // Prefer unowned (`ownerAccount == ""`) as survivor, else oldest id.
                val sorted = group.sortedWith(compareBy({ it.ownerAccount.isNotEmpty() }, { it.id }))
                val primary = sorted.first()
                var mergedMeta = primary.toMergeMetadata()
                val primaryOccs = vocabularyDao.occurrencesList(primary.id)
                var mergedOccs = primaryOccs.map { it.toMergeOccurrence() }
                val primaryIdByKey = primaryOccs.associate { it.occurrenceKey to it.id }
                val localBookByKey = linkedMapOf<String, String>()
                for (o in primaryOccs) {
                    if (o.bookUrl.isNotEmpty()) localBookByKey[o.occurrenceKey] = o.bookUrl
                }
                val existingReviews = vocabularyDao.reviews(primary.id)
                val knownSyncIds = existingReviews.mapNotNull { it.syncId?.takeIf(String::isNotBlank) }.toMutableSet()

                for (dup in sorted.drop(1)) {
                    mergedMeta = VocabularyMerge.mergeWordMetadata(
                        base = null,
                        local = mergedMeta,
                        remote = dup.toMergeMetadata(),
                    )
                    val dupOccs = vocabularyDao.occurrencesList(dup.id)
                    for (o in dupOccs) {
                        if (o.bookUrl.isNotEmpty()) localBookByKey.putIfAbsent(o.occurrenceKey, o.bookUrl)
                    }
                    mergedOccs = VocabularyMerge.mergeOccurrences(
                        base = emptyList(),
                        local = mergedOccs,
                        remote = dupOccs.map { it.toMergeOccurrence() },
                    )
                    for (r in vocabularyDao.reviews(dup.id)) {
                        val sid = r.syncId?.takeIf { it.isNotBlank() }
                            ?: deterministicLegacyReviewId(dup.language, dup.normalized, r.reviewedAt, r.rating)
                        if (knownSyncIds.add(sid)) {
                            vocabularyDao.insertReviewIgnoringConflicts(
                                r.copy(id = 0, wordId = primary.id, syncId = sid),
                            )
                        }
                    }
                    vocabularyDao.deleteWord(dup.id)
                    reconciledWordIds.remove(dup.id)
                }

                val updatedPrimary = primary.applyingMetadata(mergedMeta).withSearchText()
                vocabularyDao.updateWord(updatedPrimary)
                vocabularyDao.deleteOccurrencesForWord(primary.id)
                for (occ in mergedOccs) {
                    val bookUrl = localBookByKey[occ.occurrenceKey].orEmpty()
                    val resolvedWorkKey = occ.workId ?: aliasByBookUrl[bookUrl]?.workId
                    vocabularyDao.insertOccurrence(
                        VocabularyOccurrence(
                            id = primaryIdByKey[occ.occurrenceKey] ?: 0,
                            wordId = primary.id,
                            bookUrl = bookUrl,
                            bookTitle = occ.bookTitle,
                            locationKey = occ.locationKey,
                            locator = occ.locator,
                            sentence = occ.sentence,
                            lastSeenAt = occ.lastSeenAt,
                            encounters = occ.encounters.coerceAtLeast(1),
                            searchSentence = normalizeVocabularySearch(occ.sentence.orEmpty()),
                            workId = resolvedWorkKey,
                            occurrenceKey = occ.occurrenceKey,
                            sentenceEdited = occ.sentenceEdited,
                            sentenceUpdatedAt = occ.sentenceUpdatedAt,
                        ),
                    )
                }
                reconcileWordSchedule(primary.id)
                reconciledWordIds.remove(primary.id)
            }

            // Reconcile any non-duplicate word rows where colliding legacy reviews were collapsed.
            for (wordId in reconciledWordIds) {
                if (vocabularyDao.word(wordId) != null) {
                    reconcileWordSchedule(wordId)
                }
            }
        }
    }

    /**
     * Remote reviews that arrived while the word was absent are still staged: this device never
     * saw them live, so the deletion must not claim to have observed them (add-wins).
     */
    private suspend fun observedExcludingStagedReviews(
        peerId: String,
        language: String,
        normalized: String,
        localDeviceId: String,
        observed: Map<String, Long>,
        reviewStates: Map<String, DriveRecordState>,
    ): Map<String, Long> {
        val staged = vocabSyncDao.stagedForWord(peerId, language, normalized, DRIVE_STAGED_GENERATION)
        if (staged.isEmpty()) return observed
        val capped = observed.toMutableMap()
        for (item in staged) {
            val state = reviewStates[item.reviewId] ?: continue
            if (state.versionOp != DriveRecordState.OP_PUT) continue
            val origin = state.originDeviceId ?: continue
            val authoredSeq = state.originSeq ?: continue
            if (origin == localDeviceId) continue
            val seen = capped[origin] ?: continue
            if (seen < authoredSeq) continue
            if (authoredSeq <= 1L) capped.remove(origin) else capped[origin] = authoredSeq - 1L
        }
        return capped
    }

    private suspend fun currentVocabObservedVector(
        permissionId: String,
        localDeviceId: String,
    ): Map<String, Long> {
        val vocabDomains = setOf(
            DriveWireCodec.DOMAIN_VOCAB_WORD,
            DriveWireCodec.DOMAIN_VOCAB_OCCURRENCE,
            DriveWireCodec.DOMAIN_VOCAB_REVIEW,
        )
        val byDevice = store.cursors().forAccount(permissionId)
            .filter { it.deviceId != localDeviceId && it.domain in vocabDomains }
            .groupBy { it.deviceId }
        return buildMap {
            for ((deviceId, list) in byDevice) {
                val byDomain = list.associateBy { it.domain }
                val minSeq = vocabDomains.minOf { domain -> byDomain[domain]?.appliedSeq ?: 0L }
                if (minSeq > 0L) {
                    put(deviceId, minSeq)
                }
            }
        }
    }

    private suspend fun recoverFromCommittedSyncAcks(permissionId: String, peerId: String) {
        val wordSyncs = vocabSyncDao.allWords(peerId)
        val reviewSyncs = vocabSyncDao.allReviews(peerId)
        if (wordSyncs.isEmpty() && reviewSyncs.isEmpty()) return

        val visibleWords = vocabularyDao.exportWords().associateBy { it.language to it.normalized }
        val wordUpdates = mutableListOf<DriveRecordState>()
        val reviewUpdates = mutableListOf<DriveRecordState>()

        for (sync in wordSyncs) {
            if (sync.ackedFingerprint.isBlank()) continue
            val recordKey = wordKey(sync.language, sync.normalized)
            val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_VOCAB_WORD, recordKey)
            val localWord = visibleWords[sync.language to sync.normalized]

            if (sync.ackedFingerprint == VocabularyWordSync.FINGERPRINT_DELETED) {
                if (localWord == null && (existing == null || existing.versionOp != DriveRecordState.OP_DEL || existing.publishedFp != existing.versionFp)) {
                    val fp = existing?.versionFp?.takeIf { existing.versionOp == DriveRecordState.OP_DEL }
                        ?: DriveCanonicalJson.delFp(DriveWireCodec.DOMAIN_VOCAB_WORD, recordKey, existing?.lastHlc.orEmpty())
                    wordUpdates.add(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
                            recordKey = recordKey,
                            versionFp = fp,
                            versionOp = DriveRecordState.OP_DEL,
                            publishedFp = fp,
                            contentFp = null,
                            ancestorsJson = existing?.ancestorsJson ?: "[]",
                            resRefsJson = "[]",
                            lastHlc = existing?.lastHlc.orEmpty(),
                            bodyJson = existing?.takeIf { it.versionOp == DriveRecordState.OP_DEL }?.bodyJson,
                        ),
                    )
                }
            } else if (localWord != null) {
                val bodyMap = DriveWireCodec.encodeVocabWordBody(localWord.toWireBody())
                val currentFp = DriveCanonicalJson.contentFp(bodyMap)
                if (currentFp != sync.ackedFingerprint &&
                    (existing == null || existing.contentFp != sync.ackedFingerprint)
                ) {
                    val mergedAnc = mergeAncestors(
                        listOf(sync.ackedFingerprint),
                        existing?.ancestorsList().orEmpty(),
                        exclude = currentFp,
                    )
                    wordUpdates.add(
                        DriveRecordState(
                            permissionId = permissionId,
                            domain = DriveWireCodec.DOMAIN_VOCAB_WORD,
                            recordKey = recordKey,
                            versionFp = currentFp,
                            versionOp = DriveRecordState.OP_PUT,
                            publishedFp = null,
                            contentFp = currentFp,
                            ancestorsJson = DriveCanonicalJson.encode(mergedAnc),
                            resRefsJson = "[]",
                            lastHlc = existing?.lastHlc.orEmpty(),
                            bodyJson = DriveCanonicalJson.encode(bodyMap),
                        ),
                    )
                }
            }
        }

        for (rSync in reviewSyncs) {
            if (!rSync.ackedDeleted) continue
            val existing = recordStates.get(permissionId, DriveWireCodec.DOMAIN_VOCAB_REVIEW, rSync.reviewId)
            if (existing == null || existing.versionOp != DriveRecordState.OP_DEL) {
                val delFp = DriveCanonicalJson.delFp(
                    DriveWireCodec.DOMAIN_VOCAB_REVIEW,
                    rSync.reviewId,
                    existing?.lastHlc.orEmpty(),
                )
                reviewUpdates.add(
                    DriveRecordState(
                        permissionId = permissionId,
                        domain = DriveWireCodec.DOMAIN_VOCAB_REVIEW,
                        recordKey = rSync.reviewId,
                        versionFp = delFp,
                        versionOp = DriveRecordState.OP_DEL,
                        publishedFp = delFp,
                        contentFp = null,
                        ancestorsJson = "[]",
                        resRefsJson = "[]",
                        lastHlc = existing?.lastHlc.orEmpty(),
                        bodyJson = null,
                    ),
                )
            }
        }

        if (wordUpdates.isNotEmpty() || reviewUpdates.isNotEmpty()) {
            store.withTransaction {
                for (u in wordUpdates) recordStates.upsert(u)
                for (u in reviewUpdates) recordStates.upsert(u)
            }
        }
    }

    /**
     * A review can arrive while its word is deleted and the review cursor then moves on; once the
     * word is re-collected locally nothing else attaches it. Reviews a delete already covered stay dropped.
     */
    private suspend fun attachStagedReviewsForLocalWords(permissionId: String, peerId: String) {
        for (word in vocabularyDao.exportWords()) {
            if (vocabSyncDao.stagedForWord(peerId, word.language, word.normalized, DRIVE_STAGED_GENERATION).isEmpty()) {
                continue
            }
            val boundary = readDeleteBoundary(permissionId, wordKey(word.language, word.normalized))
            database.withTransaction {
                dropCoveredStagedReviews(permissionId, peerId, word.language, word.normalized, boundary)
                if (attachStagedReviews(peerId, word.language, word.normalized, word.id)) {
                    reconcileWordSchedule(word.id)
                }
            }
        }
    }

    private suspend fun attachStagedReviews(
        peerId: String,
        language: String,
        normalized: String,
        wordId: Long,
    ): Boolean {
        val staged = vocabSyncDao.stagedForWord(peerId, language, normalized, DRIVE_STAGED_GENERATION)
        if (staged.isEmpty()) return false
        var attached = false
        for (item in staged) {
            val existingSync = vocabSyncDao.review(peerId, item.reviewId)
            if (existingSync != null && (existingSync.ackedDeleted || existingSync.pendingDeleted)) {
                vocabSyncDao.deleteStaged(peerId, item.reviewId)
                continue
            }
            val existingReview = vocabularyDao.reviewBySyncIdAnyOwner(item.reviewId)
            val localId = if (existingReview != null) {
                existingReview.id
            } else {
                val insertedId = vocabularyDao.insertReviewIgnoringConflicts(
                    VocabularyReview(
                        wordId = wordId,
                        rating = item.rating,
                        reviewedAt = item.reviewedAt,
                        intervalMinutes = item.intervalMinutes,
                        dueAt = item.dueAt,
                        firstReview = false,
                        syncId = item.reviewId,
                    ),
                )
                attached = true
                if (insertedId > 0L) insertedId else vocabularyDao.reviewBySyncIdAnyOwner(item.reviewId)?.id
            }
            vocabSyncDao.deleteStaged(peerId, item.reviewId)
            vocabSyncDao.upsertReview(
                VocabularyReviewSync(
                    peerId = peerId,
                    reviewId = item.reviewId,
                    localReviewId = localId,
                    language = language,
                    normalized = normalized,
                    wordGeneration = DRIVE_STAGED_GENERATION,
                    seq = item.seq,
                    ackedDeleted = false,
                ),
            )
        }
        return attached
    }

    private suspend fun readDeleteBoundary(permissionId: String, wordKey: String): Map<String, Long> {
        val json = recordStates.get(permissionId, DELETE_BOUNDARY_DOMAIN, wordKey)?.bodyJson ?: return emptyMap()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        return buildMap {
            for (device in obj.keys()) {
                val seq = obj.optLong(device, 0L)
                if (seq > 0L) put(device, seq)
            }
        }
    }

    /** Must run inside a `store` transaction. */
    private suspend fun writeDeleteBoundary(permissionId: String, wordKey: String, boundary: Map<String, Long>) {
        if (boundary.isEmpty()) return
        val existing = recordStates.get(permissionId, DELETE_BOUNDARY_DOMAIN, wordKey)
        recordStates.upsert(
            DriveRecordState(
                permissionId = permissionId,
                domain = DELETE_BOUNDARY_DOMAIN,
                recordKey = wordKey,
                versionFp = "boundary",
                versionOp = DriveRecordState.OP_PUT,
                lastHlc = existing?.lastHlc.orEmpty(),
                bodyJson = DriveCanonicalJson.encode(boundary),
            ),
        )
    }

    /**
     * Drops staged reviews the word's delete already covered; staged reviews it did not observe
     * stay for the next collection of the word (add-wins).
     */
    private suspend fun dropCoveredStagedReviews(
        permissionId: String,
        peerId: String,
        language: String,
        normalized: String,
        boundary: Map<String, Long>,
    ) {
        if (boundary.isEmpty()) return
        for (item in vocabSyncDao.stagedForWord(peerId, language, normalized, DRIVE_STAGED_GENERATION)) {
            val state = recordStates.get(permissionId, DriveWireCodec.DOMAIN_VOCAB_REVIEW, item.reviewId)
            if (state?.versionOp == DriveRecordState.OP_PUT &&
                boundaryCovers(boundary, state.originDeviceId, state.originSeq)
            ) {
                vocabSyncDao.deleteStaged(peerId, item.reviewId)
            }
        }
    }

    internal suspend fun reconcileWordSchedule(wordId: Long) {
        val word = vocabularyDao.word(wordId) ?: return
        val liveReviews = vocabularyDao.reviews(wordId)
        val reconciled = VocabularyMerge.reconcileSchedule(
            addedAt = word.addedAt,
            baseline = word.baselineOrNull(),
            reviews = liveReviews.map { r ->
                VocabularyMerge.ReviewEvent(
                    syncId = r.syncId ?: r.id.toString(),
                    rating = r.rating,
                    reviewedAt = r.reviewedAt,
                    intervalMinutes = r.intervalMinutes,
                    dueAt = r.dueAt,
                )
            },
        )
        val updated = word.copy(
            dueAt = reconciled.dueAt,
            reviewedAt = reconciled.reviewedAt,
            streak = reconciled.streak,
            intervalMinutes = reconciled.intervalMinutes,
            repetitions = reconciled.repetitions,
        )
        if (updated != word) {
            vocabularyDao.updateWord(updated)
        }
        for (r in liveReviews) {
            val shouldBeFirst = (r.syncId ?: r.id.toString()) == reconciled.firstReviewSyncId
            if (r.firstReview != shouldBeFirst) {
                vocabularyDao.updateReview(r.copy(firstReview = shouldBeFirst))
            }
        }
    }

    private suspend fun resolveOccurrenceWorkKey(
        permissionId: String,
        occ: VocabularyOccurrence,
        aliasByBookUrl: Map<String, com.chmouel.liseur.data.db.WorkAlias>,
    ): String? {
        val raw = occ.workId?.takeIf { it.isNotBlank() }
            ?: aliasByBookUrl[occ.bookUrl]?.workId?.takeIf { it.isNotBlank() }
            ?: return null
        return workIdentity.canonicalWorkKey(permissionId, raw)
    }

    internal suspend fun upgradeOccurrenceKeyIfNeeded(
        occ: VocabularyOccurrence,
        resolvedWorkKey: String?,
    ): VocabularyOccurrence? {
        val targetKey = if (resolvedWorkKey != null) {
            VocabularyMerge.canonicalOccurrenceKey(resolvedWorkKey, occ.locationKey)
        } else {
            occ.occurrenceKey.ifBlank {
                VocabularyMerge.provisionalOccurrenceKey(occ.bookUrl, occ.bookTitle, occ.locationKey)
            }
        }
        if (targetKey == occ.occurrenceKey && resolvedWorkKey == occ.workId) {
            return occ
        }
        var result: VocabularyOccurrence? = null
        database.withTransaction {
            val current = vocabularyDao.occurrenceById(occ.id) ?: return@withTransaction
            val upgraded = current.copy(workId = resolvedWorkKey, occurrenceKey = targetKey)
            val existingCanonical = vocabularyDao.occurrenceByKey(current.wordId, targetKey)
            if (existingCanonical == null || existingCanonical.id == current.id) {
                if (upgraded != current) vocabularyDao.updateOccurrence(upgraded)
                result = upgraded
            } else {
                val merged = VocabularyMerge.mergeOccurrencePair(
                    base = null,
                    local = existingCanonical.toMergeOccurrence(),
                    remote = upgraded.toMergeOccurrence(),
                )
                val updated = existingCanonical.copy(
                    workId = merged.workId,
                    bookTitle = merged.bookTitle,
                    locationKey = merged.locationKey,
                    locator = merged.locator,
                    sentence = merged.sentence,
                    searchSentence = normalizeVocabularySearch(merged.sentence.orEmpty()),
                    sentenceEdited = merged.sentenceEdited,
                    sentenceUpdatedAt = merged.sentenceUpdatedAt,
                    lastSeenAt = merged.lastSeenAt,
                    encounters = merged.encounters,
                )
                vocabularyDao.updateOccurrence(updated)
                vocabularyDao.deleteOccurrence(current.id)
                result = updated
            }
        }
        return result
    }

    private suspend fun findBookUrlForWorkKey(permissionId: String, canonicalWorkKey: String): String? {
        val peerId = DriveLibrarySync.accountPeerId(permissionId)
        val matched = identities.aliasesFor(peerId)
            .filter { it.usable && workIdentity.canonicalWorkKey(permissionId, it.workId) == canonicalWorkKey }
        if (matched.isNotEmpty()) {
            return matched.firstOrNull { !it.bookUrl.startsWith(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX) }?.bookUrl
                ?: matched.first().bookUrl
        }
        val stubUrl = DriveWorkIdentity.driveBookUrl(canonicalWorkKey)
        return books.getByUrl(stubUrl)?.url
    }

    private fun parseBaseWordMetadata(rawJson: String?): VocabularyMerge.WordMetadata? {
        if (rawJson.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        return DriveWireCodec.decodeVocabWordBody(obj)?.toMergeMetadata()
    }

    private fun parseBaseOccurrence(rawJson: String?): VocabularyMerge.Occurrence? {
        if (rawJson.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        return DriveWireCodec.decodeVocabOccurrenceBody(obj)?.toMergeOccurrence()
    }

    private fun VocabularyWord.toWireBody(): DriveVocabWordBody = DriveVocabWordBody(
        language = language,
        normalized = normalized,
        word = word,
        definition = definition,
        translationLanguage = translationLanguage,
        notes = notes,
        definitionEdited = definitionEdited,
        definitionUpdatedAt = definitionUpdatedAt,
        notesUpdatedAt = notesUpdatedAt,
        addedAt = addedAt,
        baseline = baselineOrNull()?.let { b ->
            DriveVocabBaselineBody(
                dueAt = b.dueAt,
                reviewedAt = b.reviewedAt,
                streak = b.streak,
                intervalMinutes = b.intervalMinutes,
                repetitions = b.repetitions,
            )
        },
    )

    private fun VocabularyWord.toMergeMetadata(): VocabularyMerge.WordMetadata = VocabularyMerge.WordMetadata(
        word = word,
        definition = definition,
        translationLanguage = translationLanguage,
        notes = notes,
        definitionEdited = definitionEdited,
        definitionUpdatedAt = definitionUpdatedAt,
        notesUpdatedAt = notesUpdatedAt,
        addedAt = addedAt,
        baseline = baselineOrNull(),
    )

    private fun DriveVocabWordBody.toMergeMetadata(): VocabularyMerge.WordMetadata = VocabularyMerge.WordMetadata(
        word = word,
        definition = definition,
        translationLanguage = translationLanguage,
        notes = notes,
        definitionEdited = definitionEdited,
        definitionUpdatedAt = definitionUpdatedAt,
        notesUpdatedAt = notesUpdatedAt,
        addedAt = addedAt,
        baseline = baseline?.let { b ->
            VocabularyMerge.Baseline(
                dueAt = b.dueAt,
                reviewedAt = b.reviewedAt,
                streak = b.streak,
                intervalMinutes = b.intervalMinutes,
                repetitions = b.repetitions,
            )
        },
    )

    private fun VocabularyWord.applyingMetadata(meta: VocabularyMerge.WordMetadata): VocabularyWord = copy(
        word = meta.word,
        definition = meta.definition,
        translationLanguage = meta.translationLanguage,
        notes = meta.notes,
        definitionEdited = meta.definitionEdited,
        definitionUpdatedAt = meta.definitionUpdatedAt,
        notesUpdatedAt = meta.notesUpdatedAt,
        addedAt = meta.addedAt,
        baselineDueAt = meta.baseline?.dueAt,
        baselineReviewedAt = meta.baseline?.reviewedAt,
        baselineStreak = meta.baseline?.streak ?: 0,
        baselineIntervalMinutes = meta.baseline?.intervalMinutes ?: 0,
        baselineRepetitions = meta.baseline?.repetitions ?: 0,
    )

    private fun VocabularyOccurrence.toWireBody(
        wordKey: String,
        workKey: String?,
    ): DriveVocabOccurrenceBody = DriveVocabOccurrenceBody(
        wordKey = wordKey,
        occurrenceKey = occurrenceKey,
        workKey = workKey,
        bookTitle = bookTitle,
        locationKey = VocabularyMerge.wireLocationKey(locationKey),
        locator = locator?.takeIf { it.isNotBlank() }?.let(DriveCanonicalJson::canonicalizeJsonString),
        sentence = sentence,
        sentenceEdited = sentenceEdited,
        sentenceUpdatedAt = sentenceUpdatedAt,
        lastSeenAt = lastSeenAt,
        encounters = encounters.coerceAtLeast(1),
    )

    private fun VocabularyOccurrence.toMergeOccurrence(): VocabularyMerge.Occurrence = VocabularyMerge.Occurrence(
        occurrenceKey = occurrenceKey,
        workId = workId,
        bookTitle = bookTitle,
        locationKey = VocabularyMerge.wireLocationKey(locationKey),
        locator = locator,
        sentence = sentence,
        sentenceEdited = sentenceEdited,
        sentenceUpdatedAt = sentenceUpdatedAt,
        lastSeenAt = lastSeenAt,
        encounters = encounters.coerceAtLeast(1),
    )

    private fun DriveVocabOccurrenceBody.toMergeOccurrence(): VocabularyMerge.Occurrence = VocabularyMerge.Occurrence(
        occurrenceKey = occurrenceKey,
        workId = workKey,
        bookTitle = bookTitle,
        locationKey = VocabularyMerge.wireLocationKey(locationKey),
        locator = locator,
        sentence = sentence,
        sentenceEdited = sentenceEdited,
        sentenceUpdatedAt = sentenceUpdatedAt,
        lastSeenAt = lastSeenAt,
        encounters = encounters.coerceAtLeast(1),
    )

    private fun DriveRecordState.ancestorsList(): List<String> =
        DriveWorkIdentity.decodeStringArray(ancestorsJson)

    private fun buildAncestors(
        state: DriveRecordState?,
        ackedFp: String?,
        exclude: String,
    ): List<String> {
        val primary = buildList {
            state?.versionFp?.takeIf { it.isNotBlank() }?.let(::add)
            ackedFp?.takeIf { it.isNotBlank() && it != VocabularyWordSync.FINGERPRINT_DELETED }?.let(::add)
        }
        return mergeAncestors(primary, state?.ancestorsList().orEmpty(), exclude)
    }

    private fun mergeAncestors(
        first: List<String>,
        second: List<String>,
        exclude: String,
    ): List<String> = (first + second)
        .filter { it.isNotBlank() && it != exclude }
        .distinct()
        .take(DriveWireCodec.MAX_ANCESTORS)

    companion object {
        private const val DRIVE_STAGED_GENERATION = 0L

        /** Local-only record state (never published): per-word vector of deleted review origins. */
        internal const val DELETE_BOUNDARY_DOMAIN = "_vocab_delete_boundary"

        /**
         * What an applied word delete proves was seen: its `observed` vector plus the deleter's own
         * records up to the delete's batch. A snapshot's seq is its `throughSeq`, not the delete's
         * own, so with [provenanceKnown] false only the explicit entries count.
         */
        internal fun deleteBoundaryVector(
            deleterDeviceId: String,
            deleterSeq: Long,
            observed: Map<String, Long>?,
            provenanceKnown: Boolean,
        ): Map<String, Long> {
            val explicit = observed.orEmpty()
            if (!provenanceKnown) return explicit
            return mergeBoundaries(explicit, mapOf(deleterDeviceId to deleterSeq))
        }

        internal fun mergeBoundaries(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
            val merged = a.toMutableMap()
            for ((device, seq) in b) {
                merged[device] = maxOf(merged[device] ?: 0L, seq)
            }
            return merged
        }

        internal fun boundaryCovers(boundary: Map<String, Long>, originDeviceId: String?, originSeq: Long?): Boolean {
            if (originDeviceId == null || originSeq == null) return false
            return (boundary[originDeviceId] ?: 0L) >= originSeq
        }

        /** Length-prefixed word wire key: `"<language.length>:<language>:<normalized>"`. */
        fun wordKey(language: String, normalized: String): String =
            "${language.length}:$language:$normalized"

        /** Parses `"<language.length>:<language>:<normalized>"` back into `(language, normalized)`. */
        fun parseWordKey(key: String): Pair<String, String>? {
            val firstColon = key.indexOf(':')
            if (firstColon <= 0) return null
            val langLen = key.substring(0, firstColon).toIntOrNull() ?: return null
            val langStart = firstColon + 1
            // Untrusted keys: reject a length that overruns the key before any arithmetic can overflow.
            if (langLen <= 0 || langLen > key.length - langStart) return null
            val langEnd = langStart + langLen
            if (langEnd >= key.length || key[langEnd] != ':') return null
            val language = key.substring(langStart, langEnd)
            val normalized = key.substring(langEnd + 1)
            if (language.isEmpty() || normalized.isEmpty()) return null
            return language to normalized
        }

        fun occurrenceRecordKey(wordKey: String, occurrenceKey: String): String =
            "$wordKey/$occurrenceKey"

        /** Deterministic UUID for legacy vocabulary reviews whose `syncId` is null (`docs/DRIVE_SYNC.md` §4.11). */
        fun deterministicLegacyReviewId(
            language: String,
            normalized: String,
            reviewedAt: Long,
            rating: String,
        ): String = UUID.nameUUIDFromBytes(
            "vocab-review|v1|$language|$normalized|$reviewedAt|$rating".toByteArray(Charsets.UTF_8),
        ).toString()
    }
}
