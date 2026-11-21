package com.chmouel.liseur.vocabulary

import androidx.room.withTransaction
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.domain.VocabularyMerge
import com.chmouel.liseur.insights.UsageEvent
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

internal data class VocabularyLookup(
    val word: String,
    val language: String,
    val occurrence: VocabularyOccurrence?,
) {
    val normalized = normalizeVocabularyWord(word, language)
}

internal class VocabularyRepository(
    private val database: LiseurDatabase,
    private val report: (UsageEvent) -> Unit = {},
    private val onChanged: () -> Unit = {},
) {
    val dao = database.vocabularyDao()
    private val syncDao = database.vocabularySyncDao()
    private val serverDao = database.remoteServerDao()

    private suspend fun activeOwnerAccount(): String =
        serverDao.get()?.takeIf { it.kind == ServerKind.LISEUR_SYNC }?.accountKey.orEmpty()

    suspend fun collect(
        lookup: VocabularyLookup,
        definition: String?,
        translationLanguage: String?,
        now: Long = System.currentTimeMillis(),
        existingOnly: Boolean = false,
    ): Long? {
        val normalized = lookup.normalized ?: return null
        val (id, created) = database.withTransaction {
            val owner = activeOwnerAccount()
            val existing = dao.findWord(normalized, lookup.language)
            if (existingOnly && existing == null) return@withTransaction null
            val id = existing?.id ?: dao.insertWord(
                VocabularyWord(
                    word = lookup.word.trim(),
                    normalized = normalized,
                    language = lookup.language,
                    definition = definition,
                    translationLanguage = translationLanguage,
                    addedAt = now,
                    dueAt = now,
                    ownerAccount = owner,
                    definitionUpdatedAt = if (!definition.isNullOrBlank()) now else 0L,
                ),
            )
            if (existing == null && owner.isNotEmpty()) {
                // F3: Re-collecting while a delete is pending transitions to DELETE_THEN_RESURRECT.
                syncDao.word(owner, lookup.language, normalized)?.let { existingSync ->
                    VocabularyMerge.onLocalCollect(existingSync)?.let { next ->
                        if (next != existingSync) syncDao.upsertWord(next)
                    }
                }
            }
            if (existing != null) enrich(existing, definition, translationLanguage, now)
            lookup.occurrence?.let { source ->
                val searchSentence = normalizeVocabularySearch(source.sentence.orEmpty())
                val occKey = source.occurrenceKey.ifEmpty {
                    VocabularyMerge.provisionalOccurrenceKey(source.bookUrl, source.bookTitle, source.locationKey)
                }
                val previous = dao.occurrenceByKey(id, occKey)
                    ?: dao.occurrence(id, source.bookUrl, source.locationKey)
                if (previous == null) {
                    dao.insertOccurrence(
                        source.copy(
                            wordId = id,
                            lastSeenAt = now,
                            searchSentence = searchSentence,
                            occurrenceKey = occKey,
                        ),
                    )
                } else {
                    dao.recordEncounter(previous.id, now, source.bookTitle, source.sentence, searchSentence)
                }
            }
            id to (existing == null)
        } ?: return null
        if (created) reportUsage(UsageEvent.VOCABULARY_WORD_SAVED)
        notifyChanged()
        return id
    }

    suspend fun deleteWord(id: Long) {
        val deleted = database.withTransaction {
            val word = dao.wordIfVisible(id) ?: return@withTransaction false
            dao.deleteWord(id)
            // F3: Record LOCAL_DELETE_PENDING for every peer sync row that has a
            // confirmed live word or an in-flight/persisted UPSERT.
            for (sync in syncDao.wordsForKey(word.language, word.normalized)) {
                if (word.ownerAccount.isNotEmpty() && sync.peerId != word.ownerAccount) continue
                VocabularyMerge.onLocalDelete(sync)?.let { next ->
                    if (next != sync) syncDao.upsertWord(next)
                }
            }
            true
        }
        if (deleted) notifyChanged()
    }

    suspend fun enrich(id: Long, definition: String?, translationLanguage: String?, now: Long = System.currentTimeMillis()) {
        if (definition.isNullOrBlank()) return
        val changed = database.withTransaction {
            dao.wordIfVisible(id)?.let { enrich(it, definition, translationLanguage, now) } ?: false
        }
        if (changed) notifyChanged()
    }

    private suspend fun enrich(
        word: VocabularyWord,
        definition: String?,
        translationLanguage: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (word.definitionEdited || definition.isNullOrBlank()) return false
        val existing = word.definition.orEmpty()
        if (existing.isNotBlank() && translationLanguage != null && word.translationLanguage != translationLanguage) return false
        val base = if (translationLanguage == null && word.translationLanguage != null) "" else existing
        val known = base.lineSequence().filter(String::isNotBlank).toSet()
        val additions = definition.lineSequence().filter { it.isNotBlank() && it !in known }.distinct()
            .take((50 - known.size).coerceAtLeast(0)).joinToString("\n")
        val merged = when {
            base.isBlank() -> definition
            additions.isBlank() -> base
            else -> "$base\n$additions"
        }.take(24000)
        val updated = word.copy(
            definition = merged,
            translationLanguage = translationLanguage,
            definitionUpdatedAt = if (merged != word.definition || translationLanguage != word.translationLanguage) {
                now
            } else {
                word.definitionUpdatedAt
            },
        ).withSearchText()
        if (updated != word) {
            dao.updateWord(updated)
            return true
        }
        return false
    }

    suspend fun export(): List<VocabularyRecord> = database.withTransaction {
        val sources = dao.exportOccurrences().groupBy(VocabularyOccurrence::wordId)
        val reviews = dao.exportReviews().groupBy(VocabularyReview::wordId)
        dao.exportWords().map { word -> VocabularyRecord(word, sources[word.id].orEmpty(), reviews[word.id].orEmpty()) }
    }

    suspend fun import(records: List<VocabularyRecord>): Int = database.withTransaction {
        val owner = activeOwnerAccount()
        val imported = mutableSetOf<Long>()
        for ((word, sources, reviews) in records) {
            val hasIncompleteHistory = word.repetitions > reviews.size && word.baselineDueAt == null
            val preparedWord = if (hasIncompleteHistory) {
                word.copy(
                    ownerAccount = owner,
                    baselineDueAt = word.dueAt,
                    baselineReviewedAt = word.reviewedAt,
                    baselineStreak = word.streak,
                    baselineIntervalMinutes = word.intervalMinutes,
                    baselineRepetitions = word.repetitions,
                )
            } else {
                word.copy(ownerAccount = owner)
            }
            val existing = dao.findWord(word.normalized, word.language)
            val id = existing?.id ?: dao.insertWord(preparedWord.withSearchText())
            imported += id
            if (existing != null) {
                val newerReview = (word.reviewedAt ?: 0) > (existing.reviewedAt ?: 0)
                val restoreDefinition = word.definitionEdited && !existing.definitionEdited
                val nextNotes = existing.notes.ifBlank { word.notes }
                val adoptNotes = nextNotes != existing.notes
                val replacement = existing.copy(
                    definition = if (restoreDefinition) word.definition else existing.definition,
                    translationLanguage = if (restoreDefinition) word.translationLanguage else existing.translationLanguage,
                    definitionEdited = existing.definitionEdited || restoreDefinition,
                    definitionUpdatedAt = if (restoreDefinition) word.definitionUpdatedAt else existing.definitionUpdatedAt,
                    addedAt = minOf(word.addedAt, existing.addedAt),
                    dueAt = if (newerReview) word.dueAt else existing.dueAt,
                    reviewedAt = if (newerReview) word.reviewedAt else existing.reviewedAt,
                    streak = if (newerReview) word.streak else existing.streak,
                    intervalMinutes = if (newerReview) word.intervalMinutes else existing.intervalMinutes,
                    repetitions = maxOf(word.repetitions, existing.repetitions),
                    notes = nextNotes,
                    notesUpdatedAt = if (adoptNotes) word.notesUpdatedAt else existing.notesUpdatedAt,
                    baselineDueAt = existing.baselineDueAt ?: preparedWord.baselineDueAt,
                    baselineReviewedAt = existing.baselineReviewedAt ?: preparedWord.baselineReviewedAt,
                    baselineStreak = if (existing.baselineDueAt != null) existing.baselineStreak else preparedWord.baselineStreak,
                    baselineIntervalMinutes = if (existing.baselineDueAt != null) existing.baselineIntervalMinutes else preparedWord.baselineIntervalMinutes,
                    baselineRepetitions = if (existing.baselineDueAt != null) existing.baselineRepetitions else preparedWord.baselineRepetitions,
                ).withSearchText()
                if (replacement != existing) dao.updateWord(replacement)
                enrich(
                    replacement,
                    word.definition,
                    word.translationLanguage,
                    now = word.definitionUpdatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                )
            }
            for (source in sources) {
                val searchSentence = normalizeVocabularySearch(source.sentence.orEmpty())
                val occKey = source.occurrenceKey.ifEmpty {
                    VocabularyMerge.provisionalOccurrenceKey(source.bookUrl, source.bookTitle, source.locationKey)
                }
                val saved = dao.occurrenceByKey(id, occKey)
                    ?: dao.occurrence(id, source.bookUrl, source.locationKey)
                if (saved == null) {
                    dao.insertOccurrence(source.copy(wordId = id, searchSentence = searchSentence, occurrenceKey = occKey))
                } else {
                    val adoptSentence = (source.sentenceEdited && !saved.sentenceEdited) ||
                        (saved.sentence == null && source.sentence != null)
                    val merged = saved.copy(
                        sentence = if (adoptSentence) source.sentence else saved.sentence,
                        searchSentence = if (adoptSentence) searchSentence else saved.searchSentence,
                        sentenceEdited = if (adoptSentence) source.sentenceEdited else saved.sentenceEdited,
                        sentenceUpdatedAt = if (adoptSentence) source.sentenceUpdatedAt else saved.sentenceUpdatedAt,
                        lastSeenAt = maxOf(saved.lastSeenAt, source.lastSeenAt),
                        encounters = maxOf(saved.encounters, source.encounters),
                    )
                    if (merged != saved) dao.updateOccurrence(merged)
                }
            }
            if (reviews.isNotEmpty()) {
                val existingReviews = if (existing == null) emptyList() else dao.reviews(id)
                val existingSyncIds = existingReviews.mapNotNull { it.syncId }.toMutableSet()
                val nullSyncIdByContent = existingReviews
                    .filter { it.syncId == null }
                    .groupBy { it.copy(id = 0, wordId = id, syncId = null) }
                    .mapValues { it.value.toMutableList() }
                    .toMutableMap()
                val legacyContentKeys = existingReviews
                    .map { it.copy(id = 0, wordId = id, syncId = null) }
                    .toMutableSet()
                for (review in reviews) {
                    val contentKey = review.copy(id = 0, wordId = id, syncId = null)
                    val sid = review.syncId
                    if (sid != null) {
                        if (sid in existingSyncIds) continue
                        val legacy = nullSyncIdByContent[contentKey]?.takeIf { it.isNotEmpty() }?.removeAt(0)
                        if (legacy != null) {
                            dao.updateReview(legacy.copy(syncId = sid))
                            existingSyncIds += sid
                        } else {
                            dao.addReview(review.copy(id = 0, wordId = id))
                            existingSyncIds += sid
                            legacyContentKeys += contentKey
                        }
                    } else if (legacyContentKeys.add(contentKey)) {
                        val nextSid = UUID.randomUUID().toString()
                        existingSyncIds += nextSid
                        dao.addReview(
                            review.copy(
                                id = 0,
                                wordId = id,
                                syncId = nextSid,
                            ),
                        )
                    }
                }
            }
        }
        imported.size
    }.also { count ->
        if (count > 0) notifyChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun reviewQueue(book: String?, newLimit: Int, reviewLimit: Int) =
        database.invalidationTracker.createFlow(
            "vocabulary_reviews",
            "vocabulary_words",
            "vocabulary_occurrences",
            "remote_server",
        )
            .combine(flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(15_000)
                }
            }) { _, now -> now }
            .flatMapLatest {
                val now = System.currentTimeMillis()
                val today = dao.reviewsBetween(vocabularyDayStart(now), now)
                val remainingNew = if (newLimit == 0) -1 else (newLimit - today.newWords).coerceAtLeast(0)
                val remainingAll = if (reviewLimit == 0) -1 else (reviewLimit - today.total).coerceAtLeast(0)
                dao.dueCard(now, book, remainingNew, remainingAll).map { card ->
                    VocabularyQueue(card?.word, card?.dueCount ?: 0, today.total)
                }
            }

    /** The next word the review would show, with where it was met; null when nothing is due. */
    fun homeCard(newLimit: Int, reviewLimit: Int) =
        reviewQueue(null, newLimit, reviewLimit)
            .map { queue ->
                val word = queue.card?.takeIf { queue.dueCount > 0 } ?: return@map null
                val source = dao.latestOccurrence(word.id)
                HomeVocabulary(
                    id = word.id,
                    word = word.word,
                    language = word.language,
                    definition = word.definition?.takeIf(String::isNotBlank),
                    sentence = source?.sentence?.takeIf(String::isNotBlank),
                    bookTitle = source?.bookTitle?.takeIf(String::isNotBlank),
                    dueCount = queue.dueCount,
                    streak = word.streak,
                    repetitions = word.repetitions,
                )
            }
            .distinctUntilChanged()

    suspend fun review(
        card: VocabularyWord,
        rating: Recall,
        newLimit: Int,
        reviewLimit: Int,
        now: Long = System.currentTimeMillis(),
    ): VocabularyReviewUndo? = database.withTransaction {
        val word = dao.wordIfVisible(card.id) ?: return@withTransaction null
        if (word.repetitions != card.repetitions || word.dueAt > now) return@withTransaction null
        val today = dao.reviewsBetween(vocabularyDayStart(now), now)
        val first = word.repetitions == 0
        if ((reviewLimit > 0 && today.total >= reviewLimit) ||
            (first && newLimit > 0 && today.newWords >= newLimit)
        ) return@withTransaction null
        val next = scheduleVocabulary(word.streak, rating, now)
        dao.updateWord(word.copy(
            dueAt = next.dueAt, reviewedAt = now, streak = next.streak,
            intervalMinutes = next.intervalMinutes, repetitions = word.repetitions + 1,
        ))
        val id = dao.addReview(VocabularyReview(
            wordId = word.id, rating = rating.name, reviewedAt = now,
            intervalMinutes = next.intervalMinutes, dueAt = next.dueAt, firstReview = first,
            syncId = UUID.randomUUID().toString(),
        ))
        VocabularyReviewUndo(id, word)
    }.also {
        if (it != null) {
            reportUsage(UsageEvent.VOCABULARY_REVIEWED)
            notifyChanged()
        }
    }

    /** Rates the word Home shows, unless it has been reviewed since Home read it. */
    suspend fun review(home: HomeVocabulary, rating: Recall, newLimit: Int, reviewLimit: Int): VocabularyReviewUndo? {
        val card = dao.wordIfVisible(home.id)?.takeIf { it.repetitions == home.repetitions } ?: return null
        return review(card, rating, newLimit, reviewLimit)
    }

    suspend fun undoReview(undo: VocabularyReviewUndo): Boolean = database.withTransaction {
        val previous = undo.previous
        val word = dao.wordIfVisible(previous.id) ?: return@withTransaction false
        if (dao.latestReviewId(word.id) != undo.reviewId || word.repetitions != previous.repetitions + 1) return@withTransaction false
        val undone = dao.review(undo.reviewId)
        dao.updateWord(word.copy(
            dueAt = previous.dueAt, reviewedAt = previous.reviewedAt, streak = previous.streak,
            intervalMinutes = previous.intervalMinutes, repetitions = previous.repetitions,
        ))
        dao.deleteReview(undo.reviewId)
        undone?.syncId?.let { sid ->
            for (rSync in syncDao.reviewsForId(sid)) {
                if (!rSync.ackedDeleted) {
                    syncDao.upsertReview(
                        rSync.copy(
                            localReviewId = null,
                            pendingDeleted = true,
                            pendingJson = null,
                        ),
                    )
                }
            }
        }
        true
    }.also {
        if (it) {
            reportUsage(UsageEvent.VOCABULARY_REVIEW_UNDONE)
            notifyChanged()
        }
    }

    suspend fun editSentence(id: Long, sentence: String, now: Long = System.currentTimeMillis()) {
        val changed = database.withTransaction {
            dao.occurrenceWordIfVisible(id) ?: return@withTransaction false
            dao.editSentence(id, sentence, normalizeVocabularySearch(sentence), now)
            true
        }
        if (changed) notifyChanged()
    }

    suspend fun edit(id: Long, definition: String, notes: String, now: Long = System.currentTimeMillis()) {
        val changed = database.withTransaction {
            val word = dao.wordIfVisible(id) ?: return@withTransaction false
            val nextDef = definition.ifBlank { null }
            val defChanged = nextDef != word.definition
            val notesChanged = notes != word.notes
            val updated = word.copy(
                definition = nextDef,
                translationLanguage = word.translationLanguage.takeUnless { definition.isBlank() },
                notes = notes,
                definitionEdited = word.definitionEdited || definition != word.definition.orEmpty(),
                definitionUpdatedAt = if (defChanged) now else word.definitionUpdatedAt,
                notesUpdatedAt = if (notesChanged) now else word.notesUpdatedAt,
            ).withSearchText()
            if (updated != word) {
                dao.updateWord(updated)
                true
            } else {
                false
            }
        }
        if (changed) notifyChanged()
    }

    private fun notifyChanged() {
        try {
            onChanged()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Triggering sync must not fail a committed vocabulary change.
        }
    }

    private fun reportUsage(event: UsageEvent) {
        try {
            report(event)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Reporting must not turn a committed action into a failed save or rating.
        }
    }
}

internal data class VocabularyQueue(val card: VocabularyWord?, val dueCount: Int, val completedToday: Int)

internal data class VocabularyReviewUndo(val reviewId: Long, val previous: VocabularyWord)

/** A word due for review, as Home shows it. */
data class HomeVocabulary(
    val id: Long,
    val word: String,
    val language: String,
    val definition: String?,
    val sentence: String?,
    val bookTitle: String?,
    val dueCount: Int,
    val streak: Int,
    val repetitions: Int,
)
