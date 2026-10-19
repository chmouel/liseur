package com.chmouel.liseur.translate

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class WholeBookCoverage(
    val total: Int,
    val cached: Int,
    val failed: Int,
    val characters: Long,
    val inputCharacters: Long,
    val outputCharacters: Long,
    val requests: Int,
)

internal data class WholeBookLookup(val translation: String? = null, val refused: Boolean = false)

internal interface WholeBookStore {
    suspend fun lookup(key: String): WholeBookLookup
    suspend fun scanLookups(keys: List<String>): List<WholeBookLookup> = keys.map { lookup(it) }
    suspend fun commit(key: String, translation: String?, done: Int, failed: Int): Boolean
    suspend fun finish(done: Int, failed: Int)
}

internal class WholeBookTranslation(
    private val translator: SentenceTranslator,
    private val requests: SentenceRequests,
    private val store: WholeBookStore,
    private val requestCharacters: (SentenceRequest) -> Int,
) {
    suspend fun scan(sentences: PageSentences, retryFailures: Boolean = false): WholeBookCoverage {
        var total = 0
        var cached = 0
        var failed = 0
        var characters = 0L
        var input = 0L
        var output = 0L
        var missing = 0
        var previous: String? = null
        val counted = HashSet<String>()
        val identity = translator.answering()
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = ArrayList<SentenceRequest>(SCAN_BATCH)
            for (i in 0 until SCAN_BATCH) {
                val sentence = sentences.next() ?: break
                check(++total <= MAX_SENTENCES) { "Too many sentences" }
                batch += requests.prepare(sentence, previous, identity)
                characters += sentence.text.length
                previous = sentence.text
            }
            if (batch.isEmpty()) break
            val lookups = store.scanLookups(batch.map { it.key })
            check(lookups.size == batch.size)
            for ((request, saved) in batch.zip(lookups)) {
                when {
                    saved.translation != null -> cached++
                    saved.refused && !retryFailures -> failed++
                    counted.add(translationKey(request.key)) -> {
                        input += requestCharacters(request)
                        output += request.sentence.text.length
                        missing++
                    }
                }
            }
        }
        if (translator.answering() != identity) throw TranslationError.Changed()
        return WholeBookCoverage(total, cached, failed, characters, input, output, missing)
    }

    suspend fun run(sentences: PageSentences, identity: String, total: Int) {
        var done = 0
        var failed = 0
        var read = 0
        var previous: String? = null
        val commits = Mutex()
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = ArrayList<SentenceRequest>(PARALLEL_REQUESTS)
            for (i in 0 until PARALLEL_REQUESTS) {
                val sentence = sentences.next() ?: break
                check(++read <= total) { "The book changed after scanning" }
                if (translator.answering() != identity) throw TranslationError.Changed()
                batch += requests.prepare(sentence, previous, identity)
                previous = sentence.text
            }
            if (batch.isEmpty()) break
            coroutineScope {
                // Repeats in this window share one paid request; later windows find its saved row.
                batch.groupBy { it.key }.values.map { occurrences ->
                    async {
                        val request = occurrences.first()
                        val saved = store.lookup(request.key)
                        if (saved.translation != null) translator.cached(occurrences.size)
                        var translation = saved.translation
                        if (translation == null && !saved.refused) {
                            try {
                                translation = translate(request)
                            } catch (e: TranslationError) {
                                if (!e.isSentenceFailure()) throw e
                            }
                        }
                        // Save each completed answer even if a sibling fails or Pause is pressed.
                        // The store's generation fence still rejects a deleted/replaced job.
                        withContext(NonCancellable) {
                            commits.withLock {
                                repeat(occurrences.size) {
                                    val nextDone = done + if (translation == null) 0 else 1
                                    val nextFailed = failed + if (translation == null) 1 else 0
                                    val success = store.commit(request.key, translation, nextDone, nextFailed)
                                    done = nextDone
                                    failed = nextFailed
                                    if (success && translation == null) {
                                        done++
                                        failed--
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        check(done + failed == total) { "The book changed after scanning" }
        store.finish(done, failed)
    }

    private suspend fun translate(request: SentenceRequest): String {
        var limited = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (translator.answering() != request.identity) throw TranslationError.Changed()
            try {
                val answer = translator.translate(request.sentence.text, request.context, request.identity, request.facts)
                    ?: throw TranslationError.Changed()
                if (answer.by != request.identity || translator.answering() != request.identity) throw TranslationError.Changed()
                if (answer.text.isBlank()) throw TranslationError.Empty()
                checkTranslationLength(request.sentence.text, answer.text)
                return answer.text
            } catch (e: TranslationError.RateLimited) {
                // Only an explicit 429 is safe to retry; a timeout may already have been billed.
                if (e.code != 429 || limited++ >= 2) throw e
                delay(5_000L * limited)
            }
        }
    }

    private companion object {
        const val MAX_SENTENCES = 1_000_000
        const val SCAN_BATCH = 128
        const val PARALLEL_REQUESTS = 4
    }
}

internal fun TranslationError.isSentenceFailure(): Boolean =
    this is TranslationError.Refused || this is TranslationError.Empty ||
        this is TranslationError.Truncated || (this is TranslationError.Malformed && this !is TranslationError.Protocol)

internal fun translationCost(coverage: WholeBookCoverage, price: TextPrice?): ClosedFloatingPointRange<Double>? {
    price ?: return null
    // Token density and output expansion vary by language. This is a range, not a spending limit.
    val low = (coverage.inputCharacters / 6.0 * price.input + coverage.outputCharacters / 6.0 * price.output) / 1_000_000
    val high = (coverage.inputCharacters.toDouble() * price.input + coverage.outputCharacters * 2.0 * price.output) / 1_000_000
    return low..high
}
