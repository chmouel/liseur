package com.chmouel.liseur.data.liseursync

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.VocabularyReviewSync
import com.chmouel.liseur.data.db.VocabularyStagedReview
import com.chmouel.liseur.data.db.VocabularySyncDao
import com.chmouel.liseur.data.db.VocabularyWordSync
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.domain.VocabularyMerge
import com.chmouel.liseur.vocabulary.VocabularyDao
import com.chmouel.liseur.vocabulary.VocabularyOccurrence
import com.chmouel.liseur.vocabulary.VocabularyReview
import com.chmouel.liseur.vocabulary.VocabularyWord
import com.chmouel.liseur.vocabulary.normalizeVocabularySearch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class LiseurSyncVocabulary(
    private val servers: RemoteServerDao,
    private val identities: WorkIdentityDao,
    private val vocabularyDao: VocabularyDao,
    private val syncDao: VocabularySyncDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val unsupported = mutableSetOf<String>()

    /**
     * Synchronizes vocabulary words and reviews for [server].
     * Returns `true` if more pages remain to pull on a subsequent pass.
     */
    suspend fun sync(server: RemoteServer): Boolean {
        if (server.baseUrl in unsupported) return false
        val credentials = server.credentials ?: return false
        val peer = server.accountKey

        // F14: Atomically enroll unowned local words into this account before
        // pulling or preparing any pushes, preventing unique-index collisions.
        if (!enrollUnowned(server, peer)) return false

        val more = try {
            pull(server, peer, credentials)
        } catch (e: LiseurSyncRejection) {
            if (isUnsupported(e)) {
                unsupported += server.baseUrl
                return false
            }
            throw e
        }

        if (!stillOurs(server)) return false

        try {
            // Two word push rounds let DELETE_THEN_RESURRECT retire the old
            // generation in round 1 and push the resurrection UPSERT in round 2.
            repeat(2) {
                if (!stillOurs(server)) return false
                val didWork = pushWords(server, peer, credentials)
                if (!didWork) return@repeat
            }
            if (!stillOurs(server)) return false
            pushReviews(server, peer, credentials)
        } catch (e: LiseurSyncRejection) {
            if (isUnsupported(e)) {
                unsupported += server.baseUrl
                return false
            }
            throw e
        }
        return more
    }

    /**
     * Pull-only refresh for the `vocabulary` live topic.
     */
    suspend fun pullOnly(server: RemoteServer): Boolean {
        if (server.baseUrl in unsupported) return false
        val credentials = server.credentials ?: return false
        val peer = server.accountKey
        if (!enrollUnowned(server, peer)) return false
        return try {
            pull(server, peer, credentials)
        } catch (e: LiseurSyncRejection) {
            if (isUnsupported(e)) {
                unsupported += server.baseUrl
                return false
            }
            throw e
        }
    }

    // -- F14: Atomic enrollment ------------------------------------------

    private suspend fun enrollUnowned(server: RemoteServer, peer: String): Boolean {
        var ok = false
        inTransaction {
            if (!stillOurs(server)) return@inTransaction
            ok = true
            val unowned = vocabularyDao.unownedWords()
            for (word in unowned) {
                val existing = vocabularyDao.findWordForOwner(word.normalized, word.language, peer)
                if (existing == null) {
                    vocabularyDao.updateWord(word.copy(ownerAccount = peer))
                } else {
                    val mergedMeta = VocabularyMerge.mergeWordMetadata(
                        base = null,
                        local = existing.toMetadata(),
                        remote = word.toMetadata(),
                    )
                    val mergedOccs = VocabularyMerge.mergeOccurrences(
                        base = emptyList(),
                        local = vocabularyDao.occurrencesList(existing.id).map { it.toMergeOccurrence() },
                        remote = vocabularyDao.occurrencesList(word.id).map { it.toMergeOccurrence() },
                    )
                    val updated = existing.applyingMetadata(mergedMeta).withSearchText()
                    vocabularyDao.updateWord(updated)
                    replaceOccurrences(existing.id, mergedOccs, emptyMap())

                    val existingReviews = vocabularyDao.reviews(existing.id)
                    val existingSyncIds = existingReviews.mapNotNull { it.syncId }.toMutableSet()
                    val unownedReviews = vocabularyDao.reviews(word.id)
                    for (r in unownedReviews) {
                        val sid = r.syncId ?: UUID.randomUUID().toString()
                        if (existingSyncIds.add(sid)) {
                            vocabularyDao.addReview(r.copy(id = 0, wordId = existing.id, syncId = sid))
                        }
                    }
                    vocabularyDao.deleteWord(word.id)
                    reconcileWordSchedule(existing.id)
                }
            }

            // Ensure every live review of this account has a stable syncId.
            for (word in vocabularyDao.wordsForOwner(peer)) {
                for (review in vocabularyDao.reviews(word.id)) {
                    if (review.syncId == null) {
                        vocabularyDao.updateReview(review.copy(syncId = UUID.randomUUID().toString()))
                    }
                }
            }
        }
        return ok
    }

    // -- F15: Transactional pull -----------------------------------------

    private suspend fun pull(
        server: RemoteServer,
        peer: String,
        credentials: com.chmouel.liseur.data.remote.RemoteCredentials,
    ): Boolean {
        var cursor = servers.get(server.id)?.vocabularyCursorSeq ?: server.vocabularyCursorSeq
        var pages = 0
        while (pages < MAX_PULL_PAGES) {
            if (!stillOurs(server)) return false
            val url = LiseurSyncApi.vocabularyChanges(server.baseUrl, cursor, PULL_PAGE_LIMIT)
            val body = http.get(url, credentials)
            val page = parseChangesPage(body)
            val workToBook = identities.aliasesFor(peer)
                .filter { it.usable }
                .associate { it.workId to it.bookUrl }

            var committed = false
            inTransaction {
                if (!stillOurs(server)) return@inTransaction
                val reconcileIds = mutableSetOf<Long>()
                val events = buildList {
                    for (w in page.words) add(FeedEvent.Word(w))
                    for (r in page.reviews) add(FeedEvent.Review(r))
                }.sortedBy { it.seq }

                for (event in events) {
                    when (event) {
                        is FeedEvent.Word -> applyRemoteWord(peer, event.word, workToBook, reconcileIds)
                        is FeedEvent.Review -> applyRemoteReview(peer, event.review, reconcileIds)
                    }
                }
                for (wordId in reconcileIds) {
                    reconcileWordSchedule(wordId)
                }
                servers.setVocabularyCursor(page.nextCursor, server.id)
                committed = true
            }
            if (!committed) return false
            cursor = page.nextCursor
            pages++
            if (!page.hasMore) return false
        }
        return true
    }

    private suspend fun applyRemoteWord(
        peer: String,
        remote: RemoteWord,
        workToBook: Map<String, String>,
        reconcileIds: MutableSet<Long>,
    ) {
        val local = vocabularyDao.findWordForOwner(remote.normalized, remote.language, peer)
        val sync = syncDao.word(peer, remote.language, remote.normalized)
        val action = VocabularyMerge.decideRemoteWord(
            remoteDeleted = remote.deleted,
            remoteGeneration = remote.generation,
            sync = sync,
            localWordId = local?.id,
        )

        when (action) {
            VocabularyMerge.RemoteWordAction.IGNORE_OWN_TOMBSTONE -> {
                syncDao.deleteStagedUpToGeneration(peer, remote.language, remote.normalized, remote.generation)
                if (sync != null && remote.seq > sync.seq) {
                    syncDao.upsertWord(sync.copy(seq = remote.seq))
                }
            }

            VocabularyMerge.RemoteWordAction.TRANSITION_TO_RESURRECT_PENDING,
            VocabularyMerge.RemoteWordAction.KEEP_UNCONFIRMED_LOCAL_RECORD_TOMBSTONE,
            -> {
                syncDao.deleteStagedUpToGeneration(peer, remote.language, remote.normalized, remote.generation)
                syncDao.upsertWord(
                    (sync ?: VocabularyWordSync(peer, remote.language, remote.normalized)).copy(
                        rev = remote.rev,
                        generation = remote.generation,
                        seq = remote.seq,
                        syncedWordId = null,
                        lifecycle = VocabularyWordSync.RESURRECT_PENDING,
                        ackedFingerprint = VocabularyWordSync.FINGERPRINT_DELETED,
                        ackedBaseJson = null,
                        pendingJson = null,
                        pendingRev = null,
                        pendingFingerprint = null,
                        pendingBaseJson = null,
                        pendingIntent = null,
                        pendingWordId = null,
                    ),
                )
            }

            VocabularyMerge.RemoteWordAction.DELETE_LOCAL_AND_RECORD_TOMBSTONE -> {
                syncDao.deleteStagedUpToGeneration(peer, remote.language, remote.normalized, remote.generation)
                if (local != null) {
                    vocabularyDao.deleteWord(local.id)
                }
                syncDao.upsertWord(
                    (sync ?: VocabularyWordSync(peer, remote.language, remote.normalized)).copy(
                        rev = remote.rev,
                        generation = remote.generation,
                        seq = remote.seq,
                        syncedWordId = null,
                        lifecycle = VocabularyWordSync.SYNCED,
                        ackedFingerprint = VocabularyWordSync.FINGERPRINT_DELETED,
                        ackedBaseJson = null,
                        pendingJson = null,
                        pendingRev = null,
                        pendingFingerprint = null,
                        pendingBaseJson = null,
                        pendingIntent = null,
                        pendingWordId = null,
                    ),
                )
            }

            VocabularyMerge.RemoteWordAction.KEEP_LOCAL_DELETED_UPDATE_REV -> {
                if (sync != null) {
                    syncDao.upsertWord(
                        sync.copy(
                            rev = remote.rev,
                            generation = remote.generation,
                            seq = remote.seq,
                            pendingJson = null,
                            pendingRev = null,
                            pendingFingerprint = null,
                            pendingBaseJson = null,
                            pendingIntent = null,
                            pendingWordId = null,
                        ),
                    )
                }
            }

            VocabularyMerge.RemoteWordAction.KEEP_LOCAL_WORD_UPDATE_REV -> {
                if (sync != null) {
                    syncDao.upsertWord(
                        sync.copy(
                            rev = remote.rev,
                            generation = remote.generation,
                            seq = remote.seq,
                            pendingJson = null,
                            pendingRev = null,
                            pendingFingerprint = null,
                            pendingBaseJson = null,
                            pendingIntent = null,
                            pendingWordId = null,
                        ),
                    )
                }
            }

            VocabularyMerge.RemoteWordAction.REPLACE_LOCAL_WITH_REMOTE -> {
                val wordId = local!!.id
                vocabularyDao.deleteReviewsForWord(wordId)
                val updated = local.applyingMetadata(remote.metadata).withSearchText()
                vocabularyDao.updateWord(updated)
                replaceOccurrences(wordId, remote.occurrences, workToBook)
                val remoteBase = encodeBaseJson(remote.metadata, remote.occurrences)
                syncDao.upsertWord(
                    (sync ?: VocabularyWordSync(peer, remote.language, remote.normalized)).copy(
                        rev = remote.rev,
                        generation = remote.generation,
                        seq = remote.seq,
                        syncedWordId = wordId,
                        lifecycle = VocabularyWordSync.SYNCED,
                        ackedFingerprint = fingerprint(remote.metadata, remote.occurrences),
                        ackedBaseJson = remoteBase,
                        pendingJson = null,
                        pendingRev = null,
                        pendingFingerprint = null,
                        pendingBaseJson = null,
                        pendingIntent = null,
                        pendingWordId = null,
                        refusedFingerprint = null,
                        refusedReason = null,
                    ),
                )
                attachStagedReviews(peer, remote.language, remote.normalized, remote.generation, wordId)
                reconcileIds += wordId
            }

            VocabularyMerge.RemoteWordAction.MERGE_WITH_REMOTE -> {
                val remoteBase = encodeBaseJson(remote.metadata, remote.occurrences)
                val remoteFp = fingerprint(remote.metadata, remote.occurrences)
                val wordId: Long
                val mergedFp: String
                if (local == null) {
                    val inserted = VocabularyWord(
                        word = remote.metadata.word,
                        normalized = remote.normalized,
                        language = remote.language,
                        definition = remote.metadata.definition,
                        translationLanguage = remote.metadata.translationLanguage,
                        notes = remote.metadata.notes,
                        addedAt = remote.metadata.addedAt,
                        dueAt = remote.metadata.baseline?.dueAt ?: remote.metadata.addedAt,
                        reviewedAt = remote.metadata.baseline?.reviewedAt,
                        streak = remote.metadata.baseline?.streak ?: 0,
                        intervalMinutes = remote.metadata.baseline?.intervalMinutes ?: 0,
                        repetitions = remote.metadata.baseline?.repetitions ?: 0,
                        definitionEdited = remote.metadata.definitionEdited,
                        ownerAccount = peer,
                        definitionUpdatedAt = remote.metadata.definitionUpdatedAt,
                        notesUpdatedAt = remote.metadata.notesUpdatedAt,
                        baselineDueAt = remote.metadata.baseline?.dueAt,
                        baselineReviewedAt = remote.metadata.baseline?.reviewedAt,
                        baselineStreak = remote.metadata.baseline?.streak ?: 0,
                        baselineIntervalMinutes = remote.metadata.baseline?.intervalMinutes ?: 0,
                        baselineRepetitions = remote.metadata.baseline?.repetitions ?: 0,
                    ).withSearchText()
                    wordId = vocabularyDao.insertWord(inserted)
                    replaceOccurrences(wordId, remote.occurrences, workToBook)
                    mergedFp = remoteFp
                    reconcileIds += wordId
                } else {
                    wordId = local.id
                    val confirmedBase = if (sync != null && sync.syncedWordId == local.id) {
                        parseBaseJson(sync.ackedBaseJson)
                    } else {
                        null
                    }
                    val localOccs = vocabularyDao.occurrencesList(wordId)
                    val mergedMeta = VocabularyMerge.mergeWordMetadata(
                        base = confirmedBase?.first,
                        local = local.toMetadata(),
                        remote = remote.metadata,
                    )
                    val mergedOccs = VocabularyMerge.mergeOccurrences(
                        base = confirmedBase?.second.orEmpty(),
                        local = localOccs.map { it.toMergeOccurrence() },
                        remote = remote.occurrences,
                    )
                    val updated = local.applyingMetadata(mergedMeta).withSearchText()
                    vocabularyDao.updateWord(updated)
                    val localBookByKey = localOccs.associate { it.occurrenceKey to it.bookUrl }
                    replaceOccurrences(wordId, mergedOccs, workToBook, localBookByKey)
                    mergedFp = fingerprint(mergedMeta, mergedOccs)
                    if (local.baselineOrNull() != mergedMeta.baseline || local.addedAt != mergedMeta.addedAt) {
                        reconcileIds += wordId
                    }
                }
                syncDao.upsertWord(
                    (sync ?: VocabularyWordSync(peer, remote.language, remote.normalized)).copy(
                        rev = remote.rev,
                        generation = remote.generation,
                        seq = remote.seq,
                        syncedWordId = wordId,
                        lifecycle = VocabularyWordSync.SYNCED,
                        ackedFingerprint = if (mergedFp == remoteFp) remoteFp else "",
                        ackedBaseJson = remoteBase,
                        pendingJson = null,
                        pendingRev = null,
                        pendingFingerprint = null,
                        pendingBaseJson = null,
                        pendingIntent = null,
                        pendingWordId = null,
                        refusedFingerprint = null,
                        refusedReason = null,
                    ),
                )
                if (attachStagedReviews(peer, remote.language, remote.normalized, remote.generation, wordId)) {
                    reconcileIds += wordId
                }
            }
        }
    }

    private suspend fun applyRemoteReview(
        peer: String,
        remote: RemoteReview,
        reconcileIds: MutableSet<Long>,
    ) {
        if (remote.deleted) {
            // F15: Remove from both staged and live reviews.
            syncDao.deleteStaged(peer, remote.reviewId)
            val existing = vocabularyDao.reviewBySyncId(remote.reviewId, peer)
            if (existing != null) {
                vocabularyDao.deleteReview(existing.id)
                reconcileIds += existing.wordId
            }
            syncDao.upsertReview(
                VocabularyReviewSync(
                    peerId = peer,
                    reviewId = remote.reviewId,
                    localReviewId = null,
                    language = remote.language,
                    normalized = remote.normalized,
                    wordGeneration = remote.wordGeneration,
                    seq = remote.seq,
                    ackedDeleted = true,
                    pendingJson = null,
                    pendingDeleted = false,
                ),
            )
            return
        }

        val existingSync = syncDao.review(peer, remote.reviewId)
        if (existingSync != null && (existingSync.pendingDeleted || existingSync.ackedDeleted)) {
            syncDao.deleteStaged(peer, remote.reviewId)
            syncDao.upsertReview(existingSync.copy(seq = maxOf(existingSync.seq, remote.seq)))
            return
        }

        val wordSync = syncDao.word(peer, remote.language, remote.normalized)
        val localWord = wordSync?.syncedWordId?.let { vocabularyDao.word(it) }
        if (localWord != null &&
            wordSync.lifecycle == VocabularyWordSync.SYNCED &&
            wordSync.generation == remote.wordGeneration
        ) {
            val existing = vocabularyDao.reviewBySyncId(remote.reviewId, peer)
            val localId = existing?.id ?: vocabularyDao.addReview(
                VocabularyReview(
                    wordId = localWord.id,
                    rating = remote.rating,
                    reviewedAt = remote.reviewedAt,
                    intervalMinutes = remote.intervalMinutes,
                    dueAt = remote.dueAt,
                    firstReview = false,
                    syncId = remote.reviewId,
                ),
            )
            syncDao.deleteStaged(peer, remote.reviewId)
            syncDao.upsertReview(
                VocabularyReviewSync(
                    peerId = peer,
                    reviewId = remote.reviewId,
                    localReviewId = localId,
                    language = remote.language,
                    normalized = remote.normalized,
                    wordGeneration = remote.wordGeneration,
                    seq = remote.seq,
                    ackedDeleted = false,
                    pendingJson = null,
                    pendingDeleted = false,
                ),
            )
            reconcileIds += localWord.id
        } else if (wordSync != null && wordSync.generation > remote.wordGeneration) {
            // Stale generation review; ignore.
            syncDao.deleteStaged(peer, remote.reviewId)
        } else {
            syncDao.upsertStaged(
                VocabularyStagedReview(
                    peerId = peer,
                    reviewId = remote.reviewId,
                    language = remote.language,
                    normalized = remote.normalized,
                    wordGeneration = remote.wordGeneration,
                    rating = remote.rating,
                    reviewedAt = remote.reviewedAt,
                    intervalMinutes = remote.intervalMinutes,
                    dueAt = remote.dueAt,
                    seq = remote.seq,
                ),
            )
        }
    }

    private suspend fun attachStagedReviews(
        peer: String,
        language: String,
        normalized: String,
        generation: Long,
        wordId: Long,
    ): Boolean {
        val staged = syncDao.stagedForWord(peer, language, normalized, generation)
        if (staged.isEmpty()) return false
        var attached = false
        for (s in staged) {
            val existingSync = syncDao.review(peer, s.reviewId)
            if (existingSync != null && (existingSync.pendingDeleted || existingSync.ackedDeleted)) {
                syncDao.deleteStaged(peer, s.reviewId)
                syncDao.upsertReview(existingSync.copy(seq = maxOf(existingSync.seq, s.seq)))
                continue
            }
            val existing = vocabularyDao.reviewBySyncId(s.reviewId, peer)
            val localId = existing?.id ?: vocabularyDao.addReview(
                VocabularyReview(
                    wordId = wordId,
                    rating = s.rating,
                    reviewedAt = s.reviewedAt,
                    intervalMinutes = s.intervalMinutes,
                    dueAt = s.dueAt,
                    firstReview = false,
                    syncId = s.reviewId,
                ),
            )
            syncDao.upsertReview(
                VocabularyReviewSync(
                    peerId = peer,
                    reviewId = s.reviewId,
                    localReviewId = localId,
                    language = s.language,
                    normalized = s.normalized,
                    wordGeneration = s.wordGeneration,
                    seq = s.seq,
                    ackedDeleted = false,
                ),
            )
            syncDao.deleteStaged(peer, s.reviewId)
            attached = true
        }
        return attached
    }

    // -- Push Words (persist-before-send) --------------------------------

    private data class QueuedWord(
        val language: String,
        val normalized: String,
        val rawJson: String,
    )

    private suspend fun pushWords(
        server: RemoteServer,
        peer: String,
        credentials: com.chmouel.liseur.data.remote.RemoteCredentials,
    ): Boolean {
        val queued = mutableListOf<QueuedWord>()
        val bookToWork = identities.aliasesFor(peer)
            .filter { it.usable }
            .associate { it.bookUrl to it.workId }
        val workToBook = bookToWork.entries.associate { (bookUrl, workId) -> workId to bookUrl }

        inTransaction {
            if (!stillOurs(server)) return@inTransaction
            val syncRows = syncDao.allWords(peer).associateBy { it.language to it.normalized }
            val liveWords = vocabularyDao.wordsForOwner(peer).associateBy { it.language to it.normalized }
            val allKeys = linkedSetOf<Pair<String, String>>()
            allKeys.addAll(syncRows.keys)
            allKeys.addAll(liveWords.keys)

            for (key in allKeys) {
                val (lang, norm) = key
                val sync = syncRows[key]
                val live = liveWords[key]

                // Replay already-persisted wire bytes verbatim on retry.
                if (sync?.pendingJson != null) {
                    queued += QueuedWord(lang, norm, sync.pendingJson)
                    continue
                }

                // Pending delete (including DELETE_THEN_RESURRECT retiring old gen first).
                if (sync != null &&
                    (sync.lifecycle == VocabularyWordSync.LOCAL_DELETE_PENDING ||
                        sync.lifecycle == VocabularyWordSync.DELETE_THEN_RESURRECT)
                ) {
                    if (sync.rev == 0L || sync.ackedFingerprint == VocabularyWordSync.FINGERPRINT_DELETED) {
                        // Never existed live on server (or already deleted).
                        val nextLifecycle = if (sync.lifecycle == VocabularyWordSync.DELETE_THEN_RESURRECT) {
                            VocabularyWordSync.RESURRECT_PENDING
                        } else {
                            VocabularyWordSync.SYNCED
                        }
                        syncDao.upsertWord(sync.copy(lifecycle = nextLifecycle, syncedWordId = null))
                        if (nextLifecycle == VocabularyWordSync.SYNCED) continue
                    } else {
                        val json = encodeWordDeleteJson(lang, norm, sync.rev)
                        syncDao.upsertWord(
                            sync.copy(
                                pendingJson = json,
                                pendingRev = sync.rev,
                                pendingFingerprint = VocabularyWordSync.FINGERPRINT_DELETED,
                                pendingBaseJson = null,
                                pendingIntent = VocabularyWordSync.INTENT_DELETE,
                                pendingWordId = null,
                            ),
                        )
                        queued += QueuedWord(lang, norm, json)
                        continue
                    }
                }

                val word = live ?: continue
                val currentSync = syncDao.word(peer, lang, norm)
                val occs = resolveLocalOccurrences(word.id, currentSync, bookToWork, workToBook)
                val meta = word.toMetadata()
                val mergeOccs = occs.map { it.toMergeOccurrence() }.take(MAX_WIRE_OCCURRENCES)
                val fp = fingerprint(meta, mergeOccs)

                if (currentSync != null && (fp == currentSync.ackedFingerprint || fp == currentSync.refusedFingerprint)) {
                    continue
                }

                val baseRev = currentSync?.rev ?: 0L
                val wireJson = encodeWordUpsertJson(lang, norm, baseRev, meta, mergeOccs)
                if (wireJson.toByteArray(Charsets.UTF_8).size > MAX_WIRE_ITEM_BYTES) {
                    syncDao.upsertWord(
                        (currentSync ?: VocabularyWordSync(peer, lang, norm)).copy(
                            refusedFingerprint = fp,
                            refusedReason = "item too large",
                        ),
                    )
                    continue
                }

                val baseJson = encodeBaseJson(meta, mergeOccs)
                syncDao.upsertWord(
                    (currentSync ?: VocabularyWordSync(peer, lang, norm)).copy(
                        pendingJson = wireJson,
                        pendingRev = baseRev,
                        pendingFingerprint = fp,
                        pendingBaseJson = baseJson,
                        pendingIntent = VocabularyWordSync.INTENT_UPSERT,
                        pendingWordId = word.id,
                        refusedFingerprint = null,
                        refusedReason = null,
                    ),
                )
                queued += QueuedWord(lang, norm, wireJson)
            }
        }

        if (queued.isEmpty()) return false

        val url = LiseurSyncApi.pushVocabularyWords(server.baseUrl)
        for (batch in chunkByCountAndBytes(queued, MAX_WORD_BATCH, MAX_BATCH_BYTES) { it.rawJson }) {
            if (!stillOurs(server)) return false
            val body = """{"words":[${batch.joinToString(",") { it.rawJson }}]}"""
            val response = http.postRaw(url, credentials, body)
            val results = response.optJSONArray("results") ?: continue

            inTransaction {
                if (!stillOurs(server)) return@inTransaction
                val reconcileIds = mutableSetOf<Long>()
                for (i in 0 until minOf(batch.size, results.length())) {
                    val q = batch[i]
                    val res = results.optJSONObject(i) ?: continue
                    val status = res.optString("status")
                    val sync = syncDao.word(peer, q.language, q.normalized) ?: continue
                    when (status) {
                        "applied", "duplicate" -> {
                            val settled = VocabularyMerge.onPushWordAck(
                                sync = sync,
                                resRev = res.optLong("rev", sync.rev),
                                resGeneration = res.optLong("generation", sync.generation),
                                resSeq = res.optLong("seq", sync.seq),
                            )
                            syncDao.upsertWord(settled)
                        }

                        "conflict" -> {
                            val cleared = sync.copy(
                                pendingJson = null,
                                pendingRev = null,
                                pendingFingerprint = null,
                                pendingBaseJson = null,
                                pendingIntent = null,
                                pendingWordId = null,
                            )
                            syncDao.upsertWord(cleared)
                            val serverObj = res.optJSONObject("server")
                            if (serverObj != null) {
                                parseRemoteWord(serverObj)?.let { remote ->
                                    applyRemoteWord(peer, remote, workToBook, reconcileIds)
                                }
                            } else {
                                // Absent word on server: reset rev/generation so next pass creates cleanly.
                                syncDao.upsertWord(
                                    cleared.copy(
                                        rev = 0,
                                        generation = 0,
                                        syncedWordId = null,
                                        ackedFingerprint = "",
                                        ackedBaseJson = null,
                                    ),
                                )
                            }
                        }

                        else -> {
                            syncDao.upsertWord(
                                sync.copy(
                                    pendingJson = null,
                                    pendingRev = null,
                                    pendingFingerprint = null,
                                    pendingBaseJson = null,
                                    pendingIntent = null,
                                    pendingWordId = null,
                                    refusedFingerprint = sync.pendingFingerprint,
                                    refusedReason = res.optString("reason").ifBlank { status },
                                ),
                            )
                        }
                    }
                }
                for (wordId in reconcileIds) {
                    reconcileWordSchedule(wordId)
                }
            }
        }
        return true
    }

    /**
     * F7: Resolves `workId` on local occurrences. Before first sync (`rev == 0`),
     * promotes provisional keys to canonical keys and deduplicates; once synced
     * (`rev > 0`), populates `workId` while keeping `occurrenceKey` stable.
     */
    private suspend fun resolveLocalOccurrences(
        wordId: Long,
        sync: VocabularyWordSync?,
        bookToWork: Map<String, String>,
        workToBook: Map<String, String>,
    ): List<VocabularyOccurrence> {
        val alreadySynced = (sync?.rev ?: 0L) > 0L
        val current = vocabularyDao.occurrencesList(wordId)
        var changed = false
        for (occ in current) {
            val resolvedWork = occ.workId ?: bookToWork[occ.bookUrl]
            if (resolvedWork == null) continue
            val targetKey = if (alreadySynced) {
                occ.occurrenceKey
            } else {
                VocabularyMerge.canonicalOccurrenceKey(resolvedWork, occ.locationKey)
            }
            val targetBookUrl = occ.bookUrl.ifEmpty { workToBook[resolvedWork].orEmpty() }
            if (occ.workId == resolvedWork && occ.occurrenceKey == targetKey && occ.bookUrl == targetBookUrl) {
                continue
            }
            val existingCanonical = if (targetKey != occ.occurrenceKey) {
                vocabularyDao.occurrenceByKey(wordId, targetKey)
            } else {
                null
            }
            if (existingCanonical != null && existingCanonical.id != occ.id) {
                val merged = VocabularyMerge.mergeOccurrencePair(
                    base = null,
                    local = existingCanonical.toMergeOccurrence(),
                    remote = occ.toMergeOccurrence().copy(occurrenceKey = targetKey, workId = resolvedWork),
                )
                vocabularyDao.updateOccurrence(
                    existingCanonical.copy(
                        bookUrl = existingCanonical.bookUrl.ifEmpty { targetBookUrl },
                        workId = resolvedWork,
                        sentence = merged.sentence,
                        searchSentence = normalizeVocabularySearch(merged.sentence.orEmpty()),
                        sentenceEdited = merged.sentenceEdited,
                        sentenceUpdatedAt = merged.sentenceUpdatedAt,
                        lastSeenAt = merged.lastSeenAt,
                        encounters = merged.encounters,
                        locator = existingCanonical.locator ?: occ.locator,
                    ),
                )
                vocabularyDao.deleteOccurrence(occ.id)
            } else {
                vocabularyDao.updateOccurrence(
                    occ.copy(
                        bookUrl = targetBookUrl,
                        workId = resolvedWork,
                        occurrenceKey = targetKey,
                    ),
                )
            }
            changed = true
        }
        return if (changed) vocabularyDao.occurrencesList(wordId) else current
    }

    // -- Push Reviews (incarnation-bound, persist-before-send) -----------

    private data class QueuedReview(
        val reviewId: String,
        val rawJson: String,
        val sentDeleted: Boolean,
    )

    private suspend fun pushReviews(
        server: RemoteServer,
        peer: String,
        credentials: com.chmouel.liseur.data.remote.RemoteCredentials,
    ) {
        val queued = mutableListOf<QueuedReview>()

        inTransaction {
            if (!stillOurs(server)) return@inTransaction
            val existingSync = syncDao.allReviews(peer).associateBy { it.reviewId }

            // 1. Replay pending review writes & pending review undo deletes.
            for (rSync in existingSync.values) {
                if (rSync.pendingJson != null) {
                    val sentDeleted = runCatching {
                        JSONObject(rSync.pendingJson).optBoolean("deleted", false)
                    }.getOrDefault(false)
                    queued += QueuedReview(rSync.reviewId, rSync.pendingJson, sentDeleted)
                    continue
                }
                if (rSync.pendingDeleted && !rSync.ackedDeleted && rSync.refusedReason == null) {
                    val json = encodeReviewDeleteJson(rSync)
                    syncDao.upsertReview(rSync.copy(pendingJson = json))
                    queued += QueuedReview(rSync.reviewId, json, sentDeleted = true)
                }
            }

            // 2. Push new live reviews ONLY for confirmed live word incarnations.
            val wordSyncByKey = syncDao.allWords(peer).associateBy { it.language to it.normalized }
            for (word in vocabularyDao.wordsForOwner(peer)) {
                val wSync = wordSyncByKey[word.language to word.normalized] ?: continue
                if (wSync.lifecycle != VocabularyWordSync.SYNCED ||
                    wSync.generation <= 0L ||
                    wSync.syncedWordId != word.id
                ) {
                    continue
                }
                for (review in vocabularyDao.reviews(word.id)) {
                    val sid = review.syncId ?: UUID.randomUUID().toString().also {
                        vocabularyDao.updateReview(review.copy(syncId = it))
                    }
                    if (existingSync.containsKey(sid)) continue
                    val json = encodeReviewUpsertJson(
                        reviewId = sid,
                        language = word.language,
                        normalized = word.normalized,
                        wordGeneration = wSync.generation,
                        review = review,
                    )
                    syncDao.upsertReview(
                        VocabularyReviewSync(
                            peerId = peer,
                            reviewId = sid,
                            localReviewId = review.id,
                            language = word.language,
                            normalized = word.normalized,
                            wordGeneration = wSync.generation,
                            pendingJson = json,
                            pendingDeleted = false,
                        ),
                    )
                    queued += QueuedReview(sid, json, sentDeleted = false)
                }
            }
        }

        if (queued.isEmpty()) return

        val url = LiseurSyncApi.pushVocabularyReviews(server.baseUrl)
        for (batch in chunkByCountAndBytes(queued, MAX_REVIEW_BATCH, MAX_BATCH_BYTES) { it.rawJson }) {
            if (!stillOurs(server)) return
            val body = """{"reviews":[${batch.joinToString(",") { it.rawJson }}]}"""
            val response = http.postRaw(url, credentials, body)
            val results = response.optJSONArray("results") ?: continue

            inTransaction {
                if (!stillOurs(server)) return@inTransaction
                for (i in 0 until minOf(batch.size, results.length())) {
                    val q = batch[i]
                    val res = results.optJSONObject(i) ?: continue
                    val status = res.optString("status")
                    val rSync = syncDao.review(peer, q.reviewId) ?: continue
                    when (status) {
                        "applied", "duplicate" -> {
                            syncDao.upsertReview(
                                if (q.sentDeleted) {
                                    rSync.copy(
                                        seq = res.optLong("seq", rSync.seq),
                                        ackedDeleted = true,
                                        pendingJson = null,
                                        pendingDeleted = false,
                                        refusedReason = null,
                                    )
                                } else {
                                    rSync.copy(
                                        seq = res.optLong("seq", rSync.seq),
                                        ackedDeleted = false,
                                        pendingJson = null,
                                        refusedReason = null,
                                    )
                                },
                            )
                        }

                        else -> {
                            syncDao.upsertReview(
                                rSync.copy(
                                    pendingJson = null,
                                    pendingDeleted = if (q.sentDeleted) false else rSync.pendingDeleted,
                                    refusedReason = res.optString("reason").ifBlank { status },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // -- Schedule reconciliation & occurrence persistence ----------------

    private suspend fun reconcileWordSchedule(wordId: Long) {
        val word = vocabularyDao.word(wordId) ?: return
        val reviews = vocabularyDao.reviews(wordId)
        val events = reviews.map { r ->
            VocabularyMerge.ReviewEvent(
                syncId = r.syncId ?: r.id.toString(),
                rating = r.rating,
                reviewedAt = r.reviewedAt,
                intervalMinutes = r.intervalMinutes,
                dueAt = r.dueAt,
            )
        }
        val reconciled = VocabularyMerge.reconcileSchedule(
            addedAt = word.addedAt,
            baseline = word.baselineOrNull(),
            reviews = events,
        )
        val updatedWord = word.copy(
            dueAt = reconciled.dueAt,
            reviewedAt = reconciled.reviewedAt,
            streak = reconciled.streak,
            intervalMinutes = reconciled.intervalMinutes,
            repetitions = reconciled.repetitions,
        )
        if (updatedWord != word) {
            vocabularyDao.updateWord(updatedWord)
        }
        for (r in reviews) {
            val shouldBeFirst = (r.syncId ?: r.id.toString()) == reconciled.firstReviewSyncId
            if (r.firstReview != shouldBeFirst) {
                vocabularyDao.updateReview(r.copy(firstReview = shouldBeFirst))
            }
        }
    }

    private suspend fun replaceOccurrences(
        wordId: Long,
        occurrences: List<VocabularyMerge.Occurrence>,
        workToBook: Map<String, String>,
        localBookByKey: Map<String, String> = emptyMap(),
    ) {
        val existingByKey = vocabularyDao.occurrencesList(wordId).associateBy { it.occurrenceKey }
        val nextKeys = occurrences.mapTo(mutableSetOf()) { it.occurrenceKey }
        for ((key, existing) in existingByKey) {
            if (key !in nextKeys) {
                vocabularyDao.deleteOccurrence(existing.id)
            }
        }
        for (o in occurrences) {
            val existing = existingByKey[o.occurrenceKey]
            val bookUrl = localBookByKey[o.occurrenceKey]?.takeIf { it.isNotEmpty() }
                ?: existing?.bookUrl?.takeIf { it.isNotEmpty() }
                ?: o.workId?.let { workToBook[it] }
                ?: ""
            val desired = VocabularyOccurrence(
                id = existing?.id ?: 0,
                wordId = wordId,
                bookUrl = bookUrl,
                bookTitle = o.bookTitle,
                locationKey = o.locationKey,
                locator = o.locator,
                sentence = o.sentence,
                lastSeenAt = o.lastSeenAt,
                encounters = o.encounters.coerceAtLeast(1),
                searchSentence = normalizeVocabularySearch(o.sentence.orEmpty()),
                workId = o.workId,
                occurrenceKey = o.occurrenceKey,
                sentenceEdited = o.sentenceEdited,
                sentenceUpdatedAt = o.sentenceUpdatedAt,
            )
            if (existing == null) {
                vocabularyDao.insertOccurrence(desired)
            } else if (desired != existing) {
                vocabularyDao.updateOccurrence(desired)
            }
        }
    }

    private suspend fun stillOurs(server: RemoteServer): Boolean =
        servers.get(server.id)?.let { it.accountKey == server.accountKey } == true

    private fun isUnsupported(e: LiseurSyncRejection): Boolean =
        e.code == LiseurSyncHttp.NOT_FOUND &&
            e.rawBody.trim().let { it.isEmpty() || it == "404 page not found" }

    // -- Wire JSON parsing & encoding ------------------------------------

    private sealed class FeedEvent {
        abstract val seq: Long
        data class Word(val word: RemoteWord) : FeedEvent() { override val seq: Long get() = word.seq }
        data class Review(val review: RemoteReview) : FeedEvent() { override val seq: Long get() = review.seq }
    }

    private data class ChangesPage(
        val words: List<RemoteWord>,
        val reviews: List<RemoteReview>,
        val nextCursor: Long,
        val hasMore: Boolean,
    )

    private data class RemoteWord(
        val language: String,
        val normalized: String,
        val rev: Long,
        val generation: Long,
        val seq: Long,
        val deleted: Boolean,
        val metadata: VocabularyMerge.WordMetadata,
        val occurrences: List<VocabularyMerge.Occurrence>,
    )

    private data class RemoteReview(
        val reviewId: String,
        val language: String,
        val normalized: String,
        val wordGeneration: Long,
        val seq: Long,
        val deleted: Boolean,
        val rating: String,
        val reviewedAt: Long,
        val intervalMinutes: Int,
        val dueAt: Long,
    )

    private fun parseChangesPage(json: JSONObject): ChangesPage {
        val wordsArr = json.optJSONArray("words")
        val words = buildList {
            if (wordsArr != null) {
                for (i in 0 until wordsArr.length()) {
                    wordsArr.optJSONObject(i)?.let(::parseRemoteWord)?.let(::add)
                }
            }
        }
        val reviewsArr = json.optJSONArray("reviews")
        val reviews = buildList {
            if (reviewsArr != null) {
                for (i in 0 until reviewsArr.length()) {
                    reviewsArr.optJSONObject(i)?.let(::parseRemoteReview)?.let(::add)
                }
            }
        }
        return ChangesPage(
            words = words,
            reviews = reviews,
            nextCursor = json.optLong("next_cursor", 0L),
            hasMore = json.optBoolean("has_more", false),
        )
    }

    private fun parseRemoteWord(obj: JSONObject): RemoteWord? {
        val language = obj.optString("language")
        val normalized = obj.optString("normalized")
        if (language.isEmpty() || normalized.isEmpty()) return null
        val deleted = obj.optBoolean("deleted", false)
        val rev = obj.optLong("rev", 0L)
        val generation = obj.optLong("generation", 0L)
        val seq = obj.optLong("seq", 0L)
        if (deleted) {
            return RemoteWord(
                language = language,
                normalized = normalized,
                rev = rev,
                generation = generation,
                seq = seq,
                deleted = true,
                metadata = VocabularyMerge.WordMetadata("", null, null, "", false, 0L, 0L, 0L, null),
                occurrences = emptyList(),
            )
        }
        val baselineObj = obj.optJSONObject("imported_baseline")
        val baseline = baselineObj?.let { b ->
            val dueAt = parseIsoMillis(b.optString("due_at")) ?: return@let null
            VocabularyMerge.Baseline(
                dueAt = dueAt,
                reviewedAt = parseIsoMillis(b.optString("reviewed_at")),
                streak = b.optInt("streak", 0),
                intervalMinutes = b.optInt("interval_minutes", 0),
                repetitions = b.optInt("repetitions", 0),
            )
        }
        val meta = VocabularyMerge.WordMetadata(
            word = obj.optString("word", normalized),
            definition = obj.optNullableString("definition"),
            translationLanguage = obj.optNullableString("translation_language"),
            notes = obj.optString("notes", ""),
            definitionEdited = obj.optBoolean("definition_edited", false),
            definitionUpdatedAt = parseIsoMillis(obj.optString("definition_updated_at")) ?: 0L,
            notesUpdatedAt = parseIsoMillis(obj.optString("notes_updated_at")) ?: 0L,
            addedAt = parseIsoMillis(obj.optString("added_at")) ?: now(),
            baseline = baseline,
        )
        val occArr = obj.optJSONArray("occurrences")
        val occs = buildList {
            if (occArr != null) {
                for (i in 0 until occArr.length()) {
                    val o = occArr.optJSONObject(i) ?: continue
                    val key = o.optString("occurrence_key")
                    if (key.isEmpty()) continue
                    val locatorVal = o.opt("locator")
                    val locatorStr = if (locatorVal != null && locatorVal !== JSONObject.NULL) {
                        locatorVal.toString()
                    } else {
                        null
                    }
                    add(
                        VocabularyMerge.Occurrence(
                            occurrenceKey = key,
                            workId = o.optNullableString("work_id"),
                            bookTitle = o.optString("book_title", ""),
                            locationKey = o.optString("location_key", ""),
                            locator = locatorStr,
                            sentence = o.optNullableString("sentence"),
                            sentenceEdited = o.optBoolean("sentence_edited", false),
                            sentenceUpdatedAt = parseIsoMillis(o.optString("sentence_updated_at")) ?: 0L,
                            lastSeenAt = parseIsoMillis(o.optString("last_seen_at")) ?: meta.addedAt,
                            encounters = o.optInt("encounters", 1).coerceAtLeast(1),
                        ),
                    )
                }
            }
        }
        return RemoteWord(
            language = language,
            normalized = normalized,
            rev = rev,
            generation = generation,
            seq = seq,
            deleted = false,
            metadata = meta,
            occurrences = occs,
        )
    }

    private fun parseRemoteReview(obj: JSONObject): RemoteReview? {
        val reviewId = obj.optString("review_id")
        val language = obj.optString("language")
        val normalized = obj.optString("normalized")
        if (reviewId.isEmpty() || language.isEmpty() || normalized.isEmpty()) return null
        val deleted = obj.optBoolean("deleted", false)
        return RemoteReview(
            reviewId = reviewId,
            language = language,
            normalized = normalized,
            wordGeneration = obj.optLong("word_generation", 0L),
            seq = obj.optLong("seq", 0L),
            deleted = deleted,
            rating = obj.optString("rating", ""),
            reviewedAt = parseIsoMillis(obj.optString("reviewed_at")) ?: 0L,
            intervalMinutes = obj.optInt("interval_minutes", 0),
            dueAt = parseIsoMillis(obj.optString("due_at")) ?: 0L,
        )
    }

    internal fun encodeWordDeleteJson(language: String, normalized: String, baseRev: Long): String =
        JSONObject()
            .put("language", language)
            .put("normalized", normalized)
            .put("base_rev", baseRev)
            .put("deleted", true)
            .toString()

    internal fun encodeWordUpsertJson(
        language: String,
        normalized: String,
        baseRev: Long,
        meta: VocabularyMerge.WordMetadata,
        occurrences: List<VocabularyMerge.Occurrence>,
    ): String {
        val obj = JSONObject()
            .put("language", language)
            .put("normalized", normalized)
            .put("base_rev", baseRev)
            .put("deleted", false)
            .put("word", meta.word)
            .put("notes", meta.notes)
            .put("definition_edited", meta.definitionEdited)
            .put("added_at", formatIsoMillis(meta.addedAt))
        if (meta.definition != null) obj.put("definition", meta.definition)
        if (meta.translationLanguage != null) obj.put("translation_language", meta.translationLanguage)
        if (meta.definitionUpdatedAt > 0L) obj.put("definition_updated_at", formatIsoMillis(meta.definitionUpdatedAt))
        if (meta.notesUpdatedAt > 0L) obj.put("notes_updated_at", formatIsoMillis(meta.notesUpdatedAt))
        meta.baseline?.let { b ->
            val bObj = JSONObject()
                .put("due_at", formatIsoMillis(b.dueAt))
                .put("streak", b.streak)
                .put("interval_minutes", b.intervalMinutes)
                .put("repetitions", b.repetitions)
            b.reviewedAt?.let { bObj.put("reviewed_at", formatIsoMillis(it)) }
            obj.put("imported_baseline", bObj)
        }
        obj.put("occurrences", encodeOccurrencesArray(occurrences))
        return obj.toString()
    }

    private fun wireLocator(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_WIRE_LOCATOR_BYTES) return null
        return runCatching { JSONObject(raw).toString() }.getOrNull()
    }

    private fun encodeOccurrencesArray(occurrences: List<VocabularyMerge.Occurrence>): JSONArray {
        val arr = JSONArray()
        for (o in occurrences.sortedBy { it.occurrenceKey }.take(MAX_WIRE_OCCURRENCES)) {
            val item = JSONObject()
                .put("occurrence_key", o.occurrenceKey)
                .put("book_title", o.bookTitle)
                .put("location_key", VocabularyMerge.wireLocationKey(o.locationKey))
                .put("sentence_edited", o.sentenceEdited)
                .put("last_seen_at", formatIsoMillis(o.lastSeenAt))
                .put("encounters", o.encounters.coerceAtLeast(1))
            if (!o.workId.isNullOrBlank()) item.put("work_id", o.workId)
            if (o.sentence != null) item.put("sentence", o.sentence)
            if (o.sentenceUpdatedAt > 0L) item.put("sentence_updated_at", formatIsoMillis(o.sentenceUpdatedAt))
            wireLocator(o.locator)?.let { item.put("locator", JSONObject(it)) }
            arr.put(item)
        }
        return arr
    }

    private fun encodeBaseJson(
        meta: VocabularyMerge.WordMetadata,
        occurrences: List<VocabularyMerge.Occurrence>,
    ): String {
        val obj = JSONObject()
            .put("word", meta.word)
            .put("notes", meta.notes)
            .put("definition_edited", meta.definitionEdited)
            .put("definition_updated_at", meta.definitionUpdatedAt)
            .put("notes_updated_at", meta.notesUpdatedAt)
            .put("added_at", meta.addedAt)
        if (meta.definition != null) obj.put("definition", meta.definition)
        if (meta.translationLanguage != null) obj.put("translation_language", meta.translationLanguage)
        meta.baseline?.let { b ->
            val bObj = JSONObject()
                .put("due_at", b.dueAt)
                .put("streak", b.streak)
                .put("interval_minutes", b.intervalMinutes)
                .put("repetitions", b.repetitions)
            if (b.reviewedAt != null) bObj.put("reviewed_at", b.reviewedAt)
            obj.put("baseline", bObj)
        }
        obj.put("occurrences", encodeOccurrencesArray(occurrences))
        return obj.toString()
    }

    private fun parseBaseJson(raw: String?): Pair<VocabularyMerge.WordMetadata, List<VocabularyMerge.Occurrence>>? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val baseline = obj.optJSONObject("baseline")?.let { b ->
            VocabularyMerge.Baseline(
                dueAt = b.optLong("due_at", 0L),
                reviewedAt = if (b.has("reviewed_at") && !b.isNull("reviewed_at")) b.optLong("reviewed_at") else null,
                streak = b.optInt("streak", 0),
                intervalMinutes = b.optInt("interval_minutes", 0),
                repetitions = b.optInt("repetitions", 0),
            )
        }
        val meta = VocabularyMerge.WordMetadata(
            word = obj.optString("word", ""),
            definition = obj.optNullableString("definition"),
            translationLanguage = obj.optNullableString("translation_language"),
            notes = obj.optString("notes", ""),
            definitionEdited = obj.optBoolean("definition_edited", false),
            definitionUpdatedAt = obj.optLong("definition_updated_at", 0L),
            notesUpdatedAt = obj.optLong("notes_updated_at", 0L),
            addedAt = obj.optLong("added_at", 0L),
            baseline = baseline,
        )
        val occArr = obj.optJSONArray("occurrences")
        val occs = buildList {
            if (occArr != null) {
                for (i in 0 until occArr.length()) {
                    val o = occArr.optJSONObject(i) ?: continue
                    val key = o.optString("occurrence_key")
                    if (key.isEmpty()) continue
                    val locatorVal = o.opt("locator")
                    val locatorStr = if (locatorVal != null && locatorVal !== JSONObject.NULL) {
                        locatorVal.toString()
                    } else {
                        null
                    }
                    add(
                        VocabularyMerge.Occurrence(
                            occurrenceKey = key,
                            workId = o.optNullableString("work_id"),
                            bookTitle = o.optString("book_title", ""),
                            locationKey = o.optString("location_key", ""),
                            locator = locatorStr,
                            sentence = o.optNullableString("sentence"),
                            sentenceEdited = o.optBoolean("sentence_edited", false),
                            sentenceUpdatedAt = parseIsoMillis(o.optString("sentence_updated_at")) ?: 0L,
                            lastSeenAt = parseIsoMillis(o.optString("last_seen_at")) ?: meta.addedAt,
                            encounters = o.optInt("encounters", 1).coerceAtLeast(1),
                        ),
                    )
                }
            }
        }
        return meta to occs
    }

    private fun encodeReviewUpsertJson(
        reviewId: String,
        language: String,
        normalized: String,
        wordGeneration: Long,
        review: VocabularyReview,
    ): String = JSONObject()
        .put("review_id", reviewId)
        .put("language", language)
        .put("normalized", normalized)
        .put("word_generation", wordGeneration)
        .put("deleted", false)
        .put("rating", review.rating)
        .put("reviewed_at", formatIsoMillis(review.reviewedAt))
        .put("interval_minutes", review.intervalMinutes)
        .put("due_at", formatIsoMillis(review.dueAt))
        .toString()

    private fun encodeReviewDeleteJson(rSync: VocabularyReviewSync): String = JSONObject()
        .put("review_id", rSync.reviewId)
        .put("language", rSync.language)
        .put("normalized", rSync.normalized)
        .put("word_generation", rSync.wordGeneration)
        .put("deleted", true)
        .toString()

    private fun fingerprint(
        meta: VocabularyMerge.WordMetadata,
        occurrences: List<VocabularyMerge.Occurrence>,
    ): String {
        val canonical = buildString {
            append(meta.word).append('\u001f')
            append(meta.definition.orEmpty()).append('\u001f')
            append(meta.translationLanguage.orEmpty()).append('\u001f')
            append(meta.notes).append('\u001f')
            append(meta.definitionEdited).append('\u001f')
            append(meta.definitionUpdatedAt).append('\u001f')
            append(meta.notesUpdatedAt).append('\u001f')
            append(meta.addedAt).append('\u001f')
            meta.baseline?.let { b ->
                append(b.dueAt).append(':').append(b.reviewedAt).append(':')
                    .append(b.streak).append(':').append(b.intervalMinutes).append(':').append(b.repetitions)
            }
            for (o in occurrences.sortedBy { it.occurrenceKey }.take(MAX_WIRE_OCCURRENCES)) {
                append('\u001e')
                append(o.occurrenceKey).append('\u001f')
                append(o.workId.orEmpty()).append('\u001f')
                append(o.bookTitle).append('\u001f')
                append(VocabularyMerge.wireLocationKey(o.locationKey)).append('\u001f')
                append(wireLocator(o.locator).orEmpty()).append('\u001f')
                append(o.sentence.orEmpty()).append('\u001f')
                append(o.sentenceEdited).append('\u001f')
                append(o.sentenceUpdatedAt).append('\u001f')
                append(o.lastSeenAt).append('\u001f')
                append(o.encounters)
            }
        }
        return VocabularyMerge.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun VocabularyWord.toMetadata(): VocabularyMerge.WordMetadata = VocabularyMerge.WordMetadata(
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

    private fun VocabularyOccurrence.toMergeOccurrence(): VocabularyMerge.Occurrence = VocabularyMerge.Occurrence(
        occurrenceKey = occurrenceKey.ifEmpty {
            VocabularyMerge.provisionalOccurrenceKey(bookUrl, bookTitle, locationKey)
        },
        workId = workId,
        bookTitle = bookTitle,
        locationKey = locationKey,
        locator = locator,
        sentence = sentence,
        sentenceEdited = sentenceEdited,
        sentenceUpdatedAt = sentenceUpdatedAt,
        lastSeenAt = lastSeenAt,
        encounters = encounters,
    )

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun formatIsoMillis(millis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))

    private fun parseIsoMillis(raw: String): Long? =
        raw.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    companion object {
        private const val PULL_PAGE_LIMIT = 200
        private const val MAX_PULL_PAGES = 50
        private const val MAX_WORD_BATCH = 100
        private const val MAX_REVIEW_BATCH = 500
        private const val MAX_BATCH_BYTES = 512 * 1024
        private const val MAX_WIRE_ITEM_BYTES = 64 * 1024
        private const val MAX_WIRE_LOCATOR_BYTES = 16 * 1024
        private const val MAX_WIRE_OCCURRENCES = 50
        private const val BATCH_ENVELOPE_OVERHEAD_BYTES = 32

        internal fun <T> chunkByCountAndBytes(
            items: List<T>,
            maxCount: Int,
            maxBytes: Int,
            rawOf: (T) -> String,
        ): List<List<T>> {
            val out = mutableListOf<List<T>>()
            var current = mutableListOf<T>()
            var bytes = BATCH_ENVELOPE_OVERHEAD_BYTES
            for (item in items) {
                val itemBytes = rawOf(item).toByteArray(Charsets.UTF_8).size + 1
                if (current.isNotEmpty() && (current.size >= maxCount || bytes + itemBytes > maxBytes)) {
                    out += current
                    current = mutableListOf()
                    bytes = BATCH_ENVELOPE_OVERHEAD_BYTES
                }
                current += item
                bytes += itemBytes
            }
            if (current.isNotEmpty()) out += current
            return out
        }
    }
}
