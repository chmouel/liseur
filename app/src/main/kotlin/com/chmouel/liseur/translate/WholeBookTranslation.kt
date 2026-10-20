package com.chmouel.liseur.translate

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val online: () -> Boolean = { true },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
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
        // The chunk being filled, as run() fills them: only its sizes, never the sentences.
        var open: SentenceRequest? = null
        var openSize = 0
        var openCharacters = 0
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
                        val text = request.sentence.text.length
                        val first = open
                        if (!translator.batches || !TranslationPrompt.batchable(request.sentence.text)) {
                            open = null
                            input += requestCharacters(request)
                        } else if (first == null || startsChunk(first, request, openSize, openCharacters)) {
                            // A chunk of one sentence is a plain request. A second turns it into a batch, which
                            // pays the instructions, context and book once, then a numbered line per sentence.
                            open = request
                            openSize = 1
                            openCharacters = text
                            input += requestCharacters(request)
                        } else {
                            if (openSize == 1) input += BATCH_PROMPT_CHARACTERS + BATCH_LINE_CHARACTERS
                            openSize++
                            openCharacters += text
                            input += text + BATCH_LINE_CHARACTERS
                        }
                        output += text
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
        val window = if (translator.batches) PARALLEL_REQUESTS * BATCH_SENTENCES else PARALLEL_REQUESTS

        // Saves each completed answer even if a sibling fails or Pause is pressed.
        // The store's generation fence still rejects a deleted/replaced job.
        suspend fun commit(occurrences: List<SentenceRequest>, translation: String?) = withContext(NonCancellable) {
            commits.withLock {
                repeat(occurrences.size) {
                    val nextDone = done + if (translation == null) 0 else 1
                    val nextFailed = failed + if (translation == null) 1 else 0
                    val success = store.commit(occurrences.first().key, translation, nextDone, nextFailed)
                    done = nextDone
                    failed = nextFailed
                    if (success && translation == null) {
                        done++
                        failed--
                    }
                }
            }
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = ArrayList<SentenceRequest>(window)
            for (i in 0 until window) {
                val sentence = sentences.next() ?: break
                check(++read <= total) { "The book changed after scanning" }
                if (translator.answering() != identity) throw TranslationError.Changed()
                batch += requests.prepare(sentence, previous, identity)
                previous = sentence.text
            }
            if (batch.isEmpty()) break
            // Repeats in this window share one paid request; later windows find its saved row.
            val missing = ArrayList<List<SentenceRequest>>()
            for (occurrences in batch.groupBy { it.key }.values) {
                val saved = store.lookup(occurrences.first().key)
                when {
                    saved.translation != null -> {
                        translator.cached(occurrences.size)
                        commit(occurrences, saved.translation)
                    }
                    saved.refused -> commit(occurrences, null)
                    else -> missing += occurrences
                }
            }
            val slots = Semaphore(PARALLEL_REQUESTS)
            coroutineScope {
                chunks(missing).map { chunk -> async { slots.withPermit { translate(chunk, ::commit) } } }.awaitAll()
            }
        }
        check(done + failed == total) { "The book changed after scanning" }
        store.finish(done, failed)
    }

    /**
     * [missing] in requests: consecutive sentences of one resource and book
     * block travel together when the translator batches, a sentence with a
     * line break of its own alone.
     */
    private fun chunks(missing: List<List<SentenceRequest>>): List<List<List<SentenceRequest>>> {
        if (!translator.batches) return missing.map { listOf(it) }
        val chunks = ArrayList<List<List<SentenceRequest>>>()
        var current = ArrayList<List<SentenceRequest>>()
        var characters = 0
        fun flush() {
            if (current.isNotEmpty()) chunks += current
            current = ArrayList()
            characters = 0
        }
        for (occurrences in missing) {
            val request = occurrences.first()
            if (!TranslationPrompt.batchable(request.sentence.text)) {
                flush()
                chunks += listOf(occurrences)
                continue
            }
            val first = current.firstOrNull()?.first()
            if (first != null && startsChunk(first, request, current.size, characters)) flush()
            current += occurrences
            characters += request.sentence.text.length
        }
        flush()
        return chunks
    }

    /** Whether [request] cannot join the chunk [first] opened, holding [size] sentences of [characters]. */
    private fun startsChunk(first: SentenceRequest, request: SentenceRequest, size: Int, characters: Int): Boolean =
        first.sentence.href != request.sentence.href || first.book != request.book ||
            size >= BATCH_SENTENCES || characters + request.sentence.text.length > BATCH_CHARACTERS

    /**
     * [chunk] translated and committed. A reply that does not give every
     * sentence a usable answer is asked again in halves, down to single
     * sentences, whose own refusals become failure markers.
     */
    private suspend fun translate(chunk: List<List<SentenceRequest>>, commit: suspend (List<SentenceRequest>, String?) -> Unit) {
        if (chunk.size == 1) {
            val translation = try {
                translate(chunk[0].first())
            } catch (e: TranslationError) {
                if (!e.isSentenceFailure()) throw e
                null
            }
            commit(chunk[0], translation)
            return
        }
        val first = chunk.first().first()
        val answers = try {
            retrying(first.identity) {
                val texts = chunk.map { it.first().sentence.text }
                val answers = translator.translateBatch(texts, first.context, first.identity, first.facts)
                    ?: throw TranslationError.Changed()
                if (answers.size != texts.size) throw TranslationError.Malformed("wrong number of translations")
                if (answers.any { it.by != first.identity } || translator.answering() != first.identity) throw TranslationError.Changed()
                answers.mapIndexed { i, answer -> answer.text.also { checkTranslationLength(texts[i], it) } }
            }
        } catch (e: TranslationError) {
            if (!e.isSentenceFailure()) throw e
            null
        }
        if (answers == null) {
            val half = chunk.size / 2
            translate(chunk.subList(0, half), commit)
            translate(chunk.subList(half, chunk.size), commit)
            return
        }
        chunk.forEachIndexed { i, occurrences -> commit(occurrences, answers[i]) }
    }

    private suspend fun translate(request: SentenceRequest): String = retrying(request.identity) {
        val answer = translator.translate(request.sentence.text, request.context, request.identity, request.facts)
            ?: throw TranslationError.Changed()
        if (answer.by != request.identity || translator.answering() != request.identity) throw TranslationError.Changed()
        if (answer.text.isBlank()) throw TranslationError.Empty()
        checkTranslationLength(request.sentence.text, answer.text)
        answer.text
    }

    /**
     * [ask] again after a temporary failure, waiting longer each time, so a
     * busy server or a dropped connection does not pause the book. Every
     * wait, for the network to come back or for the server, counts against
     * one deadline from the first failure; a server that asks to wait past
     * it pauses the book rather than being asked early. A request that
     * failed in transit may still have been billed.
     */
    private suspend fun <T> retrying(identity: String, ask: suspend () -> T): T {
        var attempt = 0
        var deadline: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            if (translator.answering() != identity) throw TranslationError.Changed()
            try {
                return ask()
            } catch (e: TranslationError) {
                if (!e.isTemporary() || attempt >= RETRY_DELAYS.size) throw e
                val end = deadline ?: (clock() + RETRY_BUDGET).also { deadline = it }
                if (e is TranslationError.Network && !online()) {
                    // Offline costs no attempt: wait for any network, a LAN one included.
                    while (!online()) {
                        val left = end - clock()
                        if (left <= 0) throw e
                        delay(minOf(NETWORK_POLL, left))
                    }
                    continue
                }
                val wait = maxOf(RETRY_DELAYS[attempt++], e.retryAfterMillis ?: 0)
                if (wait > end - clock()) throw e
                delay(wait)
            }
        }
    }

    private companion object {
        const val MAX_SENTENCES = 1_000_000
        const val SCAN_BATCH = 128
        const val PARALLEL_REQUESTS = 4
        const val BATCH_SENTENCES = 40
        const val BATCH_CHARACTERS = 6_000
        const val BATCH_LINE_CHARACTERS = 8
        val RETRY_DELAYS = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000)
        const val RETRY_BUDGET = 30 * 60_000L
        const val NETWORK_POLL = 5_000L

        /** What the batch instructions add to a single sentence's. */
        val BATCH_PROMPT_CHARACTERS = TranslationPrompt.batchSystem(null, "fr").length - TranslationPrompt.system(null, "fr").length
    }
}

internal fun TranslationError.isSentenceFailure(): Boolean =
    this is TranslationError.Refused || this is TranslationError.Empty ||
        this is TranslationError.Truncated || (this is TranslationError.Malformed && this !is TranslationError.Protocol)

/** A failure that asking again later can get past: a 429, a timeout or server error, or a lost connection. */
internal fun TranslationError.isTemporary(): Boolean =
    (this is TranslationError.RateLimited && code == 429) || this is TranslationError.Network ||
        (this is TranslationError.Service && (code == 408 || code in 500..599))

internal fun translationCost(coverage: WholeBookCoverage, price: TextPrice?): ClosedFloatingPointRange<Double>? {
    price ?: return null
    // Token density and output expansion vary by language. This is a range, not a spending limit.
    val low = (coverage.inputCharacters / 6.0 * price.input + coverage.outputCharacters / 6.0 * price.output) / 1_000_000
    val high = (coverage.inputCharacters.toDouble() * price.input + coverage.outputCharacters * 2.0 * price.output) / 1_000_000
    return low..high
}
