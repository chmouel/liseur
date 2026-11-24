package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.VocabularyWordSync
import com.chmouel.liseur.vocabulary.Recall
import com.chmouel.liseur.vocabulary.scheduleVocabulary
import java.security.MessageDigest
import java.util.Base64

object VocabularyMerge {
    const val MAX_WIRE_LOCATION_KEY_BYTES = 128

    fun wireLocationKey(locationKey: String): String {
        val bytes = locationKey.toByteArray(Charsets.UTF_8)
        return if (bytes.size <= MAX_WIRE_LOCATION_KEY_BYTES) locationKey else sha256Base64Url(bytes)
    }

    fun canonicalOccurrenceKey(workId: String, locationKey: String): String {
        val wire = wireLocationKey(locationKey)
        return sha256Base64Url("work:${workId.length}:$workId:${wire.length}:$wire".toByteArray(Charsets.UTF_8))
    }

    fun provisionalOccurrenceKey(bookUrl: String, bookTitle: String, locationKey: String): String {
        val wire = wireLocationKey(locationKey)
        return sha256Base64Url(
            "local:${bookUrl.length}:$bookUrl:${bookTitle.length}:$bookTitle:${wire.length}:$wire"
                .toByteArray(Charsets.UTF_8),
        )
    }

    fun sha256Base64Url(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xff
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0f])
            }
        }
    }

    data class Baseline(
        val dueAt: Long,
        val reviewedAt: Long?,
        val streak: Int,
        val intervalMinutes: Int,
        val repetitions: Int,
    )

    data class WordMetadata(
        val word: String,
        val definition: String?,
        val translationLanguage: String?,
        val notes: String,
        val definitionEdited: Boolean,
        val definitionUpdatedAt: Long,
        val notesUpdatedAt: Long,
        val addedAt: Long,
        val baseline: Baseline?,
    )

    data class Occurrence(
        val occurrenceKey: String,
        val workId: String?,
        val bookTitle: String,
        val locationKey: String,
        val locator: String?,
        val sentence: String?,
        val sentenceEdited: Boolean,
        val sentenceUpdatedAt: Long,
        val lastSeenAt: Long,
        val encounters: Int,
    )

    data class ReviewEvent(
        val syncId: String,
        val rating: String,
        val reviewedAt: Long,
        val intervalMinutes: Int,
        val dueAt: Long,
    )

    data class ReconciledSchedule(
        val dueAt: Long,
        val reviewedAt: Long?,
        val streak: Int,
        val intervalMinutes: Int,
        val repetitions: Int,
        val firstReviewSyncId: String?,
    )

    fun mergeWordMetadata(
        base: WordMetadata?,
        local: WordMetadata,
        remote: WordMetadata,
    ): WordMetadata {
        val word = when {
            base != null && local.word != base.word && remote.word == base.word -> local.word
            base != null && remote.word != base.word && local.word == base.word -> remote.word
            else -> remote.word.ifBlank { local.word }
        }

        val (definition, translationLanguage, definitionEdited, definitionUpdatedAt) =
            mergeDefinition(base, local, remote)

        val (notes, notesUpdatedAt) = mergeNotes(base, local, remote)

        return WordMetadata(
            word = word,
            definition = definition,
            translationLanguage = translationLanguage,
            notes = notes,
            definitionEdited = definitionEdited,
            definitionUpdatedAt = definitionUpdatedAt,
            notesUpdatedAt = notesUpdatedAt,
            addedAt = minOf(local.addedAt, remote.addedAt),
            baseline = mergeBaseline(local.baseline, remote.baseline),
        )
    }

    private data class MergedDefinition(
        val definition: String?,
        val translationLanguage: String?,
        val definitionEdited: Boolean,
        val definitionUpdatedAt: Long,
    )

    private fun mergeDefinition(
        base: WordMetadata?,
        local: WordMetadata,
        remote: WordMetadata,
    ): MergedDefinition {
        if (base != null) {
            val localChanged = local.definition != base.definition ||
                local.translationLanguage != base.translationLanguage ||
                local.definitionEdited != base.definitionEdited
            val remoteChanged = remote.definition != base.definition ||
                remote.translationLanguage != base.translationLanguage ||
                remote.definitionEdited != base.definitionEdited
            if (localChanged && !remoteChanged) {
                return MergedDefinition(
                    local.definition,
                    local.translationLanguage,
                    local.definitionEdited,
                    local.definitionUpdatedAt,
                )
            }
            if (remoteChanged && !localChanged) {
                return MergedDefinition(
                    remote.definition,
                    remote.translationLanguage,
                    remote.definitionEdited,
                    remote.definitionUpdatedAt,
                )
            }
            if (!localChanged && !remoteChanged) {
                return MergedDefinition(
                    remote.definition,
                    remote.translationLanguage,
                    remote.definitionEdited,
                    maxOf(local.definitionUpdatedAt, remote.definitionUpdatedAt),
                )
            }
        }
        // Both sides changed or unconfirmed initial merge (base == null).
        if (local.definitionEdited != remote.definitionEdited) {
            val winner = if (local.definitionEdited) local else remote
            return MergedDefinition(
                winner.definition,
                winner.translationLanguage,
                winner.definitionEdited,
                winner.definitionUpdatedAt,
            )
        }
        if (local.definitionUpdatedAt != remote.definitionUpdatedAt) {
            val winner = if (local.definitionUpdatedAt > remote.definitionUpdatedAt) local else remote
            return MergedDefinition(
                winner.definition,
                winner.translationLanguage,
                winner.definitionEdited,
                winner.definitionUpdatedAt,
            )
        }
        if (base == null && !local.definitionEdited && !remote.definitionEdited) {
            if (remote.definition.isNullOrBlank() && !local.definition.isNullOrBlank()) {
                return MergedDefinition(
                    local.definition,
                    local.translationLanguage,
                    false,
                    local.definitionUpdatedAt,
                )
            }
        }
        val winner = if (
            compareValuesBy(local, remote, { it.definition.orEmpty() }, { it.translationLanguage.orEmpty() }) >= 0
        ) {
            local
        } else {
            remote
        }
        return MergedDefinition(
            winner.definition,
            winner.translationLanguage,
            winner.definitionEdited,
            winner.definitionUpdatedAt,
        )
    }

    private fun mergeNotes(
        base: WordMetadata?,
        local: WordMetadata,
        remote: WordMetadata,
    ): Pair<String, Long> {
        if (base != null) {
            val localChanged = local.notes != base.notes
            val remoteChanged = remote.notes != base.notes
            if (localChanged && !remoteChanged) return local.notes to local.notesUpdatedAt
            if (remoteChanged && !localChanged) return remote.notes to remote.notesUpdatedAt
            if (!localChanged && !remoteChanged) {
                return remote.notes to maxOf(local.notesUpdatedAt, remote.notesUpdatedAt)
            }
        } else {
            if (local.notesUpdatedAt != remote.notesUpdatedAt) {
                return if (local.notesUpdatedAt > remote.notesUpdatedAt) {
                    local.notes to local.notesUpdatedAt
                } else {
                    remote.notes to remote.notesUpdatedAt
                }
            }
            if (local.notes.isBlank() && remote.notes.isNotBlank()) return remote.notes to remote.notesUpdatedAt
            if (remote.notes.isBlank() && local.notes.isNotBlank()) return local.notes to local.notesUpdatedAt
        }
        return when {
            local.notesUpdatedAt > remote.notesUpdatedAt -> local.notes to local.notesUpdatedAt
            remote.notesUpdatedAt > local.notesUpdatedAt -> remote.notes to remote.notesUpdatedAt
            else -> maxOf(local.notes, remote.notes) to local.notesUpdatedAt
        }
    }

    fun mergeBaseline(local: Baseline?, remote: Baseline?): Baseline? {
        if (local == null) return remote
        if (remote == null) return local
        val cmp = compareValuesBy(
            local,
            remote,
            { it.reviewedAt != null },
            { it.reviewedAt ?: 0L },
            { it.repetitions },
            { it.streak },
            { it.intervalMinutes },
            { it.dueAt },
        )
        return if (cmp >= 0) local else remote
    }

    fun mergeOccurrences(
        base: List<Occurrence>,
        local: List<Occurrence>,
        remote: List<Occurrence>,
    ): List<Occurrence> {
        val baseByKey = base.associateBy { it.occurrenceKey }
        val localByKey = local.associateBy { it.occurrenceKey }
        val remoteByKey = remote.associateBy { it.occurrenceKey }

        val merged = linkedMapOf<String, Occurrence>()
        val allKeys = linkedSetOf<String>()
        allKeys.addAll(remoteByKey.keys)
        allKeys.addAll(localByKey.keys)

        for (key in allKeys) {
            val b = baseByKey[key]
            val l = localByKey[key]
            val r = remoteByKey[key]
            val result = when {
                l != null && r != null -> mergeOccurrencePair(b, l, r)
                l != null -> l
                else -> r!!
            }
            merged[key] = result
        }

        // Deduplicate provisional vs canonical occurrences that resolve to the
        // same (workId, wireLocationKey). Keep the canonical entry when present
        // (or the first one if both are provisional with the same workId).
        val deduped = linkedMapOf<String, Occurrence>()
        val byWorkLoc = mutableMapOf<Pair<String, String>, String>()
        for ((key, occ) in merged) {
            val wid = occ.workId
            if (wid.isNullOrBlank()) {
                deduped[key] = occ
                continue
            }
            val token = wid to wireLocationKey(occ.locationKey)
            val existingKey = byWorkLoc[token]
            if (existingKey == null) {
                byWorkLoc[token] = key
                deduped[key] = occ
            } else {
                val existing = deduped.remove(existingKey)!!
                val canonical = canonicalOccurrenceKey(wid, occ.locationKey)
                val keptKey = if (key == canonical) key else existingKey
                val combined = mergeOccurrencePair(
                    base = baseByKey[keptKey],
                    local = existing.copy(occurrenceKey = keptKey),
                    remote = occ.copy(occurrenceKey = keptKey),
                )
                byWorkLoc[token] = keptKey
                deduped[keptKey] = combined
            }
        }
        return deduped.values.sortedWith(
            compareByDescending<Occurrence> { it.lastSeenAt }.thenBy { it.occurrenceKey },
        )
    }

    fun mergeOccurrencePair(
        base: Occurrence?,
        local: Occurrence,
        remote: Occurrence,
    ): Occurrence {
        val (sentence, sentenceEdited, sentenceUpdatedAt) = mergeSentence(base, local, remote)
        val bookTitle = when {
            base != null && local.bookTitle != base.bookTitle && remote.bookTitle == base.bookTitle -> local.bookTitle
            base != null && remote.bookTitle != base.bookTitle && local.bookTitle == base.bookTitle -> remote.bookTitle
            else -> remote.bookTitle.ifBlank { local.bookTitle }
        }
        return Occurrence(
            occurrenceKey = remote.occurrenceKey,
            workId = remote.workId ?: local.workId,
            bookTitle = bookTitle,
            locationKey = wireLocationKey(local.locationKey.ifBlank { remote.locationKey }),
            locator = if (local.locator != null && remote.locator != null) {
                maxOf(local.locator, remote.locator)
            } else {
                local.locator ?: remote.locator
            },
            sentence = sentence,
            sentenceEdited = sentenceEdited,
            sentenceUpdatedAt = sentenceUpdatedAt,
            lastSeenAt = maxOf(local.lastSeenAt, remote.lastSeenAt),
            encounters = maxOf(local.encounters, remote.encounters),
        )
    }

    private data class MergedSentence(
        val sentence: String?,
        val sentenceEdited: Boolean,
        val sentenceUpdatedAt: Long,
    )

    private fun mergeSentence(
        base: Occurrence?,
        local: Occurrence,
        remote: Occurrence,
    ): MergedSentence {
        if (base != null) {
            val localChanged = local.sentence != base.sentence || local.sentenceEdited != base.sentenceEdited
            val remoteChanged = remote.sentence != base.sentence || remote.sentenceEdited != base.sentenceEdited
            if (localChanged && !remoteChanged) {
                return MergedSentence(local.sentence, local.sentenceEdited, local.sentenceUpdatedAt)
            }
            if (remoteChanged && !localChanged) {
                return MergedSentence(remote.sentence, remote.sentenceEdited, remote.sentenceUpdatedAt)
            }
            if (!localChanged && !remoteChanged) {
                return MergedSentence(
                    remote.sentence,
                    remote.sentenceEdited,
                    maxOf(local.sentenceUpdatedAt, remote.sentenceUpdatedAt),
                )
            }
        }
        if (local.sentenceEdited != remote.sentenceEdited) {
            val winner = if (local.sentenceEdited) local else remote
            return MergedSentence(winner.sentence, winner.sentenceEdited, winner.sentenceUpdatedAt)
        }
        if (local.sentenceUpdatedAt != remote.sentenceUpdatedAt) {
            val winner = if (local.sentenceUpdatedAt > remote.sentenceUpdatedAt) local else remote
            return MergedSentence(winner.sentence, winner.sentenceEdited, winner.sentenceUpdatedAt)
        }
        if (!local.sentenceEdited && !remote.sentenceEdited) {
            if (remote.sentence.isNullOrBlank() && !local.sentence.isNullOrBlank()) {
                return MergedSentence(local.sentence, false, local.sentenceUpdatedAt)
            }
        }
        if (local.sentenceUpdatedAt == remote.sentenceUpdatedAt && local.sentence != remote.sentence) {
            val winner = if ((local.sentence ?: "") >= (remote.sentence ?: "")) local else remote
            return MergedSentence(winner.sentence, winner.sentenceEdited, winner.sentenceUpdatedAt)
        }
        return MergedSentence(remote.sentence, remote.sentenceEdited, remote.sentenceUpdatedAt)
    }

    /**
     * Deterministically derives a word's SRS state and which live review is
     * marked `firstReview = true` from its optional [baseline] and live [reviews].
     */
    fun reconcileSchedule(
        addedAt: Long,
        baseline: Baseline?,
        reviews: List<ReviewEvent>,
    ): ReconciledSchedule {
        val sorted = reviews.sortedWith(compareBy<ReviewEvent>({ it.reviewedAt }, { it.syncId }))
        var streak = baseline?.streak ?: 0
        var intervalMinutes = baseline?.intervalMinutes ?: 0
        var dueAt = baseline?.dueAt ?: addedAt
        var reviewedAt = baseline?.reviewedAt
        var repetitions = baseline?.repetitions ?: 0

        val baselineCutoff = baseline?.reviewedAt
        val applicable = if (baselineCutoff != null) {
            sorted.filter { it.reviewedAt > baselineCutoff }
        } else {
            sorted
        }

        for (review in applicable) {
            val recall = runCatching { Recall.valueOf(review.rating) }.getOrNull() ?: continue
            val next = scheduleVocabulary(streak, recall, review.reviewedAt)
            streak = next.streak
            intervalMinutes = next.intervalMinutes
            dueAt = next.dueAt
            reviewedAt = review.reviewedAt
            repetitions += 1
        }

        val firstReviewSyncId = if ((baseline?.repetitions ?: 0) == 0) {
            sorted.firstOrNull()?.syncId
        } else {
            null
        }

        return ReconciledSchedule(
            dueAt = dueAt,
            reviewedAt = reviewedAt,
            streak = streak,
            intervalMinutes = intervalMinutes,
            repetitions = repetitions,
            firstReviewSyncId = firstReviewSyncId,
        )
    }

    // -- Four-state word lifecycle machine (F3 + F11) --------------------

    enum class RemoteWordAction {
        /** Confirmed live incarnation was retired & resurrected on server; replace local. */
        REPLACE_LOCAL_WITH_REMOTE,

        /** Local delete pending; do not re-insert local word, only advance rev/gen/seq. */
        KEEP_LOCAL_DELETED_UPDATE_REV,

        /** Delete-then-resurrect pending; keep local word, only advance rev/gen/seq. */
        KEEP_LOCAL_WORD_UPDATE_REV,

        /** Three-way merge (base = ackedBaseJson if confirmed live incarnation, else null). */
        MERGE_WITH_REMOTE,

        /** Ignore tombstone for generation <= our RESURRECT_PENDING generation (F3). */
        IGNORE_OWN_TOMBSTONE,

        /** Server already tombstoned old gen while we want to resurrect; move to RESURRECT_PENDING. */
        TRANSITION_TO_RESURRECT_PENDING,

        /** Unconfirmed local word exists while server holds a tombstone; keep local and resurrect. */
        KEEP_UNCONFIRMED_LOCAL_RECORD_TOMBSTONE,

        /** Confirmed word deleted on server; delete local word and record tombstone. */
        DELETE_LOCAL_AND_RECORD_TOMBSTONE,
    }

    /**
     * Updates [sync] when the user deletes the local word (F3).
     * Records `LOCAL_DELETE_PENDING` whenever the server has a live word OR an
     * initial UPSERT was already persisted/in-flight (`pendingIntent == UPSERT`).
     */
    fun onLocalDelete(sync: VocabularyWordSync?): VocabularyWordSync? {
        if (sync == null) return null
        val serverHasLiveOrInFlightCreate =
            (sync.rev > 0 && sync.ackedFingerprint != VocabularyWordSync.FINGERPRINT_DELETED) ||
                sync.pendingIntent == VocabularyWordSync.INTENT_UPSERT
        return when {
            sync.lifecycle == VocabularyWordSync.RESURRECT_PENDING &&
                sync.ackedFingerprint == VocabularyWordSync.FINGERPRINT_DELETED &&
                sync.pendingIntent != VocabularyWordSync.INTENT_UPSERT ->
                // Local resurrection was cancelled before it ever went out.
                sync.copy(lifecycle = VocabularyWordSync.SYNCED, syncedWordId = null)

            serverHasLiveOrInFlightCreate ->
                sync.copy(lifecycle = VocabularyWordSync.LOCAL_DELETE_PENDING, syncedWordId = null)

            else ->
                sync.copy(syncedWordId = null)
        }
    }

    /**
     * Updates [sync] when the user re-collects a word whose delete is still pending.
     */
    fun onLocalCollect(sync: VocabularyWordSync?): VocabularyWordSync? {
        if (sync == null) return null
        return if (sync.lifecycle == VocabularyWordSync.LOCAL_DELETE_PENDING) {
            sync.copy(lifecycle = VocabularyWordSync.DELETE_THEN_RESURRECT)
        } else {
            sync
        }
    }

    /**
     * Decides how Pull or Push Conflict handles a remote word event (F3 + F11).
     */
    fun decideRemoteWord(
        remoteDeleted: Boolean,
        remoteGeneration: Long,
        sync: VocabularyWordSync?,
        localWordId: Long?,
    ): RemoteWordAction {
        if (!remoteDeleted) {
            val confirmedLiveIncarnation = sync != null &&
                localWordId != null &&
                sync.syncedWordId == localWordId &&
                sync.generation > 0
            return when {
                confirmedLiveIncarnation && remoteGeneration > sync!!.generation ->
                    RemoteWordAction.REPLACE_LOCAL_WITH_REMOTE

                sync?.lifecycle == VocabularyWordSync.LOCAL_DELETE_PENDING ->
                    RemoteWordAction.KEEP_LOCAL_DELETED_UPDATE_REV

                sync?.lifecycle == VocabularyWordSync.DELETE_THEN_RESURRECT ->
                    RemoteWordAction.KEEP_LOCAL_WORD_UPDATE_REV

                else ->
                    RemoteWordAction.MERGE_WITH_REMOTE
            }
        }
        // Remote tombstone
        return when {
            sync?.lifecycle == VocabularyWordSync.RESURRECT_PENDING &&
                remoteGeneration <= sync.generation ->
                RemoteWordAction.IGNORE_OWN_TOMBSTONE

            sync?.lifecycle == VocabularyWordSync.DELETE_THEN_RESURRECT ->
                RemoteWordAction.TRANSITION_TO_RESURRECT_PENDING

            localWordId != null && (sync == null || sync.syncedWordId == null || sync.generation == 0L) ->
                RemoteWordAction.KEEP_UNCONFIRMED_LOCAL_RECORD_TOMBSTONE

            else ->
                RemoteWordAction.DELETE_LOCAL_AND_RECORD_TOMBSTONE
        }
    }

    /**
     * Settles a word push acknowledgement (`applied` or `duplicate`) against the
     * persisted `pending_*` snapshot (F3).
     */
    fun onPushWordAck(
        sync: VocabularyWordSync,
        resRev: Long,
        resGeneration: Long,
        resSeq: Long,
    ): VocabularyWordSync {
        return if (sync.pendingIntent == VocabularyWordSync.INTENT_DELETE) {
            val nextLifecycle = if (sync.lifecycle == VocabularyWordSync.DELETE_THEN_RESURRECT) {
                VocabularyWordSync.RESURRECT_PENDING
            } else {
                VocabularyWordSync.SYNCED
            }
            sync.copy(
                rev = resRev,
                generation = resGeneration,
                seq = resSeq,
                syncedWordId = null,
                lifecycle = nextLifecycle,
                ackedFingerprint = VocabularyWordSync.FINGERPRINT_DELETED,
                ackedBaseJson = null,
                pendingJson = null,
                pendingRev = null,
                pendingFingerprint = null,
                pendingBaseJson = null,
                pendingIntent = null,
                pendingWordId = null,
                refusedFingerprint = null,
                refusedReason = null,
            )
        } else {
            val pendingDelete = sync.lifecycle == VocabularyWordSync.LOCAL_DELETE_PENDING ||
                sync.lifecycle == VocabularyWordSync.DELETE_THEN_RESURRECT
            sync.copy(
                rev = resRev,
                generation = resGeneration,
                seq = resSeq,
                syncedWordId = if (pendingDelete) null else sync.pendingWordId,
                lifecycle = if (pendingDelete) sync.lifecycle else VocabularyWordSync.SYNCED,
                ackedFingerprint = sync.pendingFingerprint.orEmpty(),
                ackedBaseJson = sync.pendingBaseJson,
                pendingJson = null,
                pendingRev = null,
                pendingFingerprint = null,
                pendingBaseJson = null,
                pendingIntent = null,
                pendingWordId = null,
                refusedFingerprint = null,
                refusedReason = null,
            )
        }
    }
}
