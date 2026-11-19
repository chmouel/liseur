package com.chmouel.liseur.vocabulary

import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

import com.chmouel.liseur.data.db.LiseurDatabase

internal data class VocabularyLookup(
    val word: String,
    val language: String,
    val occurrence: VocabularyOccurrence?,
) {
    val normalized = normalizeVocabularyWord(word, language)
}

internal class VocabularyRepository(private val database: LiseurDatabase) {
    val dao = database.vocabularyDao()

    suspend fun collect(
        lookup: VocabularyLookup,
        definition: String?,
        translationLanguage: String?,
        now: Long = System.currentTimeMillis(),
        existingOnly: Boolean = false,
    ): Long? {
        val normalized = lookup.normalized ?: return null
        return database.withTransaction {
            val existing = dao.findWord(normalized, lookup.language)
            if (existingOnly && existing == null) return@withTransaction null
            val id = existing?.id ?: dao.insertWord(
                VocabularyWord(
                    word = lookup.word.trim(), normalized = normalized, language = lookup.language,
                    definition = definition, translationLanguage = translationLanguage, addedAt = now, dueAt = now,
                ),
            )
            if (existing != null) enrich(existing, definition, translationLanguage)
            lookup.occurrence?.let { source ->
                val searchSentence = normalizeVocabularySearch(source.sentence.orEmpty())
                val previous = dao.occurrence(id, source.bookUrl, source.locationKey)
                if (previous == null) {
                    dao.insertOccurrence(source.copy(wordId = id, lastSeenAt = now, searchSentence = searchSentence))
                } else {
                    dao.recordEncounter(previous.id, now, source.bookTitle, source.sentence, searchSentence)
                }
            }
            id
        }
    }

    suspend fun enrich(id: Long, definition: String?, translationLanguage: String?) {
        if (definition.isNullOrBlank()) return
        database.withTransaction {
            dao.word(id)?.let { enrich(it, definition, translationLanguage) }
        }
    }

    private suspend fun enrich(word: VocabularyWord, definition: String?, translationLanguage: String?) {
        if (word.definitionEdited || definition.isNullOrBlank()) return
        val existing = word.definition.orEmpty()
        if (existing.isNotBlank() && translationLanguage != null && word.translationLanguage != translationLanguage) return
        val base = if (translationLanguage == null && word.translationLanguage != null) "" else existing
        val known = base.lineSequence().filter(String::isNotBlank).toSet()
        val additions = definition.lineSequence().filter { it.isNotBlank() && it !in known }.distinct()
            .take((50 - known.size).coerceAtLeast(0)).joinToString("\n")
        val merged = when {
            base.isBlank() -> definition
            additions.isBlank() -> base
            else -> "$base\n$additions"
        }.take(24000)
        val updated = word.copy(definition = merged, translationLanguage = translationLanguage).withSearchText()
        if (updated != word) dao.updateWord(updated)
    }

    suspend fun export(): List<VocabularyRecord> = database.withTransaction {
        val sources = dao.exportOccurrences().groupBy(VocabularyOccurrence::wordId)
        val reviews = dao.exportReviews().groupBy(VocabularyReview::wordId)
        dao.exportWords().map { word -> VocabularyRecord(word, sources[word.id].orEmpty(), reviews[word.id].orEmpty()) }
    }

    suspend fun import(records: List<VocabularyRecord>): Int = database.withTransaction {
        val imported = mutableSetOf<Long>()
        for ((word, sources, reviews) in records) {
            val existing = dao.findWord(word.normalized, word.language)
            val id = existing?.id ?: dao.insertWord(word.withSearchText())
            imported += id
            if (existing != null) {
                val newerReview = (word.reviewedAt ?: 0) > (existing.reviewedAt ?: 0)
                val restoreDefinition = word.definitionEdited && !existing.definitionEdited
                val replacement = existing.copy(
                    definition = if (restoreDefinition) word.definition else existing.definition,
                    translationLanguage = if (restoreDefinition) word.translationLanguage else existing.translationLanguage,
                    definitionEdited = existing.definitionEdited || restoreDefinition,
                    addedAt = minOf(word.addedAt, existing.addedAt),
                    dueAt = if (newerReview) word.dueAt else existing.dueAt,
                    reviewedAt = if (newerReview) word.reviewedAt else existing.reviewedAt,
                    streak = if (newerReview) word.streak else existing.streak,
                    intervalMinutes = if (newerReview) word.intervalMinutes else existing.intervalMinutes,
                    repetitions = maxOf(word.repetitions, existing.repetitions),
                    notes = existing.notes.ifBlank { word.notes },
                ).withSearchText()
                if (replacement != existing) dao.updateWord(replacement)
                enrich(replacement, word.definition, word.translationLanguage)
            }
            for (source in sources) {
                val searchSentence = normalizeVocabularySearch(source.sentence.orEmpty())
                val saved = dao.occurrence(id, source.bookUrl, source.locationKey)
                if (saved == null) {
                    dao.insertOccurrence(source.copy(wordId = id, searchSentence = searchSentence))
                } else {
                    val merged = saved.copy(
                        sentence = saved.sentence ?: source.sentence,
                        searchSentence = if (saved.sentence == null) searchSentence else saved.searchSentence,
                        lastSeenAt = maxOf(saved.lastSeenAt, source.lastSeenAt),
                        encounters = maxOf(saved.encounters, source.encounters),
                    )
                    if (merged != saved) dao.updateOccurrence(merged)
                }
            }
            if (reviews.isNotEmpty()) {
                val saved = if (existing == null) mutableSetOf() else dao.reviews(id).map { it.copy(id = 0) }.toMutableSet()
                for (review in reviews) {
                    val normalized = review.copy(id = 0, wordId = id)
                    if (saved.add(normalized)) dao.addReview(normalized)
                }
            }
        }
        imported.size
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun reviewQueue(book: String?, newLimit: Int, reviewLimit: Int) =
        database.invalidationTracker.createFlow("vocabulary_reviews", "vocabulary_words", "vocabulary_occurrences")
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

    suspend fun review(
        card: VocabularyWord,
        rating: Recall,
        newLimit: Int,
        reviewLimit: Int,
        now: Long = System.currentTimeMillis(),
    ): VocabularyReviewUndo? = database.withTransaction {
        val word = dao.word(card.id) ?: return@withTransaction null
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
        ))
        VocabularyReviewUndo(id, word)
    }

    suspend fun undoReview(undo: VocabularyReviewUndo) = database.withTransaction {
        val previous = undo.previous
        val word = dao.word(previous.id) ?: return@withTransaction
        if (dao.latestReviewId(word.id) != undo.reviewId || word.repetitions != previous.repetitions + 1) return@withTransaction
        dao.updateWord(word.copy(
            dueAt = previous.dueAt, reviewedAt = previous.reviewedAt, streak = previous.streak,
            intervalMinutes = previous.intervalMinutes, repetitions = previous.repetitions,
        ))
        dao.deleteReview(undo.reviewId)
    }

    suspend fun editSentence(id: Long, sentence: String) =
        dao.editSentence(id, sentence, normalizeVocabularySearch(sentence))

    suspend fun edit(id: Long, definition: String, notes: String) = database.withTransaction {
        dao.word(id)?.let { word ->
            dao.updateWord(word.copy(
                definition = definition.ifBlank { null },
                translationLanguage = word.translationLanguage.takeUnless { definition.isBlank() },
                notes = notes,
                definitionEdited = word.definitionEdited || definition != word.definition.orEmpty(),
            ).withSearchText())
        }
    }
}

internal data class VocabularyQueue(val card: VocabularyWord?, val dueCount: Int, val completedToday: Int)

internal data class VocabularyReviewUndo(val reviewId: Long, val previous: VocabularyWord)
