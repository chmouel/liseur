package com.chmouel.liseur.translate

import com.chmouel.liseur.data.settings.TranslationContextSize
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeBookTranslationTest {
    private open class Translator : SentenceTranslator {
        override val source = "en"
        override val target = "fr"
        override val destination = "test"
        var identity = "test"
        val asked = mutableListOf<String>()
        var refuse: String? = null
        override suspend fun answering() = identity
        override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
            asked += sentence
            if (sentence == refuse) throw TranslationError.Refused()
            return Translated(sentence.uppercase(), identity)
        }
        override fun close() = Unit
    }

    private class Store : WholeBookStore {
        val rows = mutableMapOf<String, WholeBookLookup>()
        val counts = mutableListOf<Pair<Int, Int>>()
        var stopAfter: Int? = null
        var finished = false
        override suspend fun lookup(key: String) = rows[key] ?: WholeBookLookup()
        override suspend fun commit(key: String, translation: String?, done: Int, failed: Int): Boolean {
            rows[key] = WholeBookLookup(translation, translation == null)
            counts += done to failed
            if (counts.size == stopAfter) throw IOException("storage stopped")
            return translation != null
        }
        override suspend fun finish(done: Int, failed: Int) {
            finished = true
        }
    }

    private fun sentences(vararg texts: String): PageSentences {
        var i = 0
        return PageSentences { texts.getOrNull(i++)?.let { PageSentence(it, "one.xhtml", "#p", null) } }
    }

    private fun job(translator: Translator, store: Store): WholeBookTranslation =
        WholeBookTranslation(translator, SentenceRequests(translator, null, null), store) { it.sentence.text.length }

    @Test
    fun `a protocol failure stops the job without marking sentences refused`(): Unit = runTest {
        val translator = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated =
                throw TranslationError.Protocol("no choices")
        }
        val store = Store()
        try {
            job(translator, store).run(sentences("one", "two", "three", "four", "five"), "test", 5)
            error("Expected endpoint failure")
        } catch (_: TranslationError.Protocol) {
            assertTrue(store.rows.isEmpty())
            assertFalse(store.finished)
        }
    }

    @Test
    fun `a reader jump and sequential job prepare the same context and book key`() = runTest {
        val chapter = listOf(
            PageSentence("One.", "one.xhtml", "#p", null),
            PageSentence("Oui.", "one.xhtml", "#p", "One. "),
            PageSentence("Oui.", "one.xhtml", "#q", null),
        )
        fun preparer(): SentenceRequests {
            val translator = object : SentenceTranslator {
                override val source = "en"
                override val target = "fr"
                override val destination = "test"
                override suspend fun answering() = "test"
                override suspend fun contextSize(identity: String) = TranslationContextSize.STANDARD
                override suspend fun book(identity: String, facts: BookFacts?) = facts?.chapter
                override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?) =
                    error("Preparation must not translate")
                override fun close() = Unit
            }
            return SentenceRequests(
                translator,
                PrecedingSentences(open = {
                    var at = 0
                    PageSentences { chapter.getOrNull(at++) }
                }),
                { BookFacts(chapter = "Chapter one") },
            )
        }
        val walker = preparer()
        var previous: String? = null
        val walked = chapter.map { sentence ->
            walker.prepare(sentence, previous, "test").also { previous = sentence.text }
        }
        assertEquals(walked[2].key, preparer().prepare(chapter[2], null, "test").key)
        assertFalse(walked[1].key == walked[2].key)
        assertTrue(walked[2].book == "Chapter one")
    }

    private open class Batcher : Translator() {
        override val batches = true
        val batched = mutableListOf<List<String>>()
        override suspend fun translateBatch(sentences: List<String>, context: String?, identity: String, book: BookFacts?): List<Translated> {
            batched += sentences
            if (refuse in sentences) throw TranslationError.Malformed("missing line")
            return sentences.map { Translated(it.uppercase(), identity) }
        }
    }

    @Test
    fun `a batching translator sends one request per chunk and saves every sentence under its own key`(): Unit = runTest {
        val translator = Batcher()
        val store = Store()
        val texts = Array(100) { "sentence $it" }
        job(translator, store).run(sentences(*texts), "test", 100)
        assertEquals(listOf(40, 40, 20), translator.batched.map { it.size })
        assertTrue(translator.asked.isEmpty())
        val keys = SentenceRequests(translator, null, null).let { requests ->
            var previous: String? = null
            texts.map { text -> requests.prepare(PageSentence(text, "one.xhtml", "#p", null), previous, "test").key.also { previous = text } }
        }
        assertEquals(texts.map { it.uppercase() }, keys.map { store.rows.getValue(it).translation })
        assertEquals(100 to 0, store.counts.last())
        assertTrue(store.finished)
    }

    @Test
    fun `a bad batch splits down to the refused sentence and keeps its siblings`(): Unit = runTest {
        val translator = Batcher().apply { refuse = "bad" }
        val store = Store()
        job(translator, store).run(sentences("one", "two", "bad", "four", "five"), "test", 5)
        assertEquals(listOf("bad"), translator.asked)
        assertEquals(4 to 1, store.counts.last())
        assertEquals(1, store.rows.values.count { it.refused })
        assertTrue(store.finished)
    }

    @Test
    fun `a sentence with its own line break is asked alone`(): Unit = runTest {
        val translator = Batcher()
        val store = Store()
        job(translator, store).run(sentences("one", "two\nlines", "three", "four"), "test", 4)
        // A chunk of one sentence uses the single-sentence request.
        assertEquals(listOf("one", "two\nlines"), translator.asked)
        assertEquals(listOf(listOf("three", "four")), translator.batched)
        assertEquals(4 to 0, store.counts.last())
    }

    @Test
    fun `temporary failures are asked again before the job pauses`(): Unit = runTest {
        val failures = ArrayDeque<TranslationError>(
            listOf(TranslationError.Service(503), TranslationError.Network(IOException("reset")), TranslationError.RateLimited(429)),
        )
        val translator = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                failures.removeFirstOrNull()?.let { throw it }
                return super.translate(sentence, context, identity, book)
            }
        }
        val store = Store()
        job(translator, store).run(sentences("one"), "test", 1)
        assertEquals(1 to 0, store.counts.last())

        val broken = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                asked += sentence
                throw TranslationError.Service(500)
            }
        }
        try {
            job(broken, Store()).run(sentences("one"), "test", 1)
            error("Expected a pause")
        } catch (_: TranslationError.Service) {
            assertEquals(6, broken.asked.size)
        }
        val denied = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                asked += sentence
                throw TranslationError.Service(401)
            }
        }
        try {
            job(denied, Store()).run(sentences("one"), "test", 1)
            error("Expected a pause")
        } catch (_: TranslationError.Service) {
            assertEquals(1, denied.asked.size)
        }
    }

    @Test
    fun `scan of a batching translator counts the shared request parts once per chunk`() = runTest {
        val texts = Array(80) { "sentence $it" }
        // The shared instructions, context and book block cost 500 characters.
        suspend fun scan(translator: Translator) = WholeBookTranslation(translator, SentenceRequests(translator, null, null), Store()) {
            it.sentence.text.length + 500
        }.scan(sentences(*texts))
        val single = scan(Translator())
        val batched = scan(Batcher())
        assertEquals(single.outputCharacters, batched.outputCharacters)
        // Two chunks of 40: each pays the shared parts and the batch instructions once, and a numbered line per sentence.
        assertEquals(single.inputCharacters - 78 * 500 + 2 * BATCH_PROMPT + 80 * 8, batched.inputCharacters)
    }

    private val BATCH_PROMPT = TranslationPrompt.batchSystem(null, "fr").length - TranslationPrompt.system(null, "fr").length

    private fun spread(texts: List<Pair<String, String>>): PageSentences {
        var i = 0
        return PageSentences { texts.getOrNull(i++)?.let { (href, text) -> PageSentence(text, href, "#p", null) } }
    }

    private suspend fun scanInput(translator: Translator, texts: List<Pair<String, String>>) =
        WholeBookTranslation(translator, SentenceRequests(translator, null, null), Store()) { it.sentence.text.length + 500 }
            .scan(spread(texts)).inputCharacters

    @Test
    fun `scan of a batching translator splits chunks where the job does`() = runTest {
        // One sentence per resource: every request is a plain one.
        val alone = List(40) { "chapter$it.xhtml" to "sentence $it" }
        assertEquals(scanInput(Translator(), alone), scanInput(Batcher(), alone))
        // 4,000 characters each: no two fit in 6,000, so no batch at all.
        val long = List(3) { "one.xhtml" to "$it" + "x".repeat(3_999) }
        assertEquals(scanInput(Translator(), long), scanInput(Batcher(), long))
        // A line break is asked alone and splits the chunk around it.
        val broken = listOf("one.xhtml" to "a", "one.xhtml" to "b", "one.xhtml" to "c\nd", "one.xhtml" to "e", "one.xhtml" to "f")
        val expected = (1 + 500) + (BATCH_PROMPT + 8 + 1 + 8) + (3 + 500) + (1 + 500) + (BATCH_PROMPT + 8 + 1 + 8)
        assertEquals(expected.toLong(), scanInput(Batcher(), broken))
    }

    @Test
    fun `a sparse cache costs a little more than the scan says, never less`() = runTest {
        // Every other sentence is saved: the job's windows of 160 then hold 80 missing ones, two chunks each,
        // while the scan sees one run. The estimate differs by the shared parts of the extra chunks only.
        val texts = Array(400) { "sentence $it" }
        val store = Store()
        val translator = Batcher()
        val requests = SentenceRequests(translator, null, null)
        var previous: String? = null
        texts.forEachIndexed { i, text ->
            val key = requests.prepare(PageSentence(text, "one.xhtml", "#p", null), previous, "test").key
            previous = text
            if (i % 2 == 0) store.rows[key] = WholeBookLookup("saved")
        }
        val estimate = WholeBookTranslation(translator, requests, store) { it.sentence.text.length + 500 }.scan(sentences(*texts))
        WholeBookTranslation(translator, requests, store) { it.sentence.text.length + 500 }.run(sentences(*texts), "test", 400)
        val chunks = translator.batched.size
        assertEquals(200, translator.batched.sumOf { it.size })
        assertEquals(5, (200 + 39) / 40)
        assertTrue("$chunks chunks", chunks >= 5)
        val sent = translator.batched.sumOf { batch -> 500 + BATCH_PROMPT + batch.sumOf { it.length + 8 } }.toLong()
        assertTrue("estimate ${estimate.inputCharacters} vs sent $sent", estimate.inputCharacters <= sent)
        assertTrue(sent - estimate.inputCharacters <= (chunks - 5L) * (500 + BATCH_PROMPT))
    }

    @Test
    fun `a server asking to wait past the retry budget pauses the book without being asked early`(): Unit = runTest {
        val translator = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                asked += sentence
                throw TranslationError.RateLimited(429).retryingAfter("7200")
            }
        }
        val job = WholeBookTranslation(translator, SentenceRequests(translator, null, null), Store(), clock = { testScheduler.currentTime }) { 1 }
        try {
            job.run(sentences("one"), "test", 1)
            error("Expected a pause")
        } catch (_: TranslationError.RateLimited) {
            assertEquals(1, translator.asked.size)
            assertEquals(0L, testScheduler.currentTime)
        }
        val polite = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                if (asked.isEmpty()) throw TranslationError.Service(503).retryingAfter("90").also { asked += sentence }
                return super.translate(sentence, context, identity, book)
            }
        }
        val start = testScheduler.currentTime
        WholeBookTranslation(polite, SentenceRequests(polite, null, null), Store(), clock = { testScheduler.currentTime }) { 1 }
            .run(sentences("one"), "test", 1)
        assertEquals(90_000L, testScheduler.currentTime - start)
    }

    @Test
    fun `offline waits cost no attempt but end at the retry deadline`(): Unit = runTest {
        var online = false
        val translator = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                if (!online) throw TranslationError.Network(IOException("offline")).also { asked += sentence }
                return super.translate(sentence, context, identity, book)
            }
        }
        val store = Store()
        val job = WholeBookTranslation(translator, SentenceRequests(translator, null, null), store, { online }, { testScheduler.currentTime }) { 1 }
        val running = launch { job.run(sentences("one"), "test", 1) }
        testScheduler.advanceTimeBy(20 * 60_000L)
        assertEquals("nothing is sent while offline", 1, translator.asked.size)
        online = true
        advanceUntilIdle()
        running.join()
        assertEquals(2, translator.asked.size)
        assertEquals(1 to 0, store.counts.last())

        val stranded = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                asked += sentence
                throw TranslationError.Network(IOException("offline"))
            }
        }
        val start = testScheduler.currentTime
        try {
            WholeBookTranslation(stranded, SentenceRequests(stranded, null, null), Store(), { false }, { testScheduler.currentTime }) { 1 }
                .run(sentences("one"), "test", 1)
            error("Expected a pause")
        } catch (_: TranslationError.Network) {
            assertEquals(1, stranded.asked.size)
            assertEquals(30 * 60_000L, testScheduler.currentTime - start)
        }
    }

    @Test
    fun `only a passing trouble resumes on its own, and a check is needed after changes`() {
        listOf("interrupted", "timeout", "network", "rate_limited:429", "service:503", "service:408")
            .forEach { assertTrue(it, resumable(it, automatic = true)) }
        listOf("paused", "service:401", "service:400", "service", null, "settings_changed", "storage", "rate_limited:402", "rate_limited")
            .forEach { assertFalse("$it", resumable(it, automatic = true)) }
        assertTrue(resumable("paused", automatic = false))
        assertTrue(resumable("service:401", automatic = false))
        assertTrue(resumable("rate_limited:402", automatic = false))
        assertTrue(resumable("rate_limited", automatic = false))
        assertFalse(resumable("settings_changed", automatic = false))
        assertFalse(resumable("storage", automatic = false))
    }

    @Test
    fun `scan deduplicates missing requests and sends no text`() = runTest {
        val translator = Translator()
        val result = job(translator, Store()).scan(sentences("one", "one", "one"))
        // The first has no preceding sentence; later two share the same context.
        assertEquals(3, result.total)
        assertEquals(2, result.requests)
        assertEquals(6L, result.inputCharacters)
        assertTrue(translator.asked.isEmpty())
    }

    @Test
    fun `scan batches lookups without changing context or counts at batch boundaries`(): Unit = runTest {
        val translator = Translator()
        val store = Store()
        val batchSizes = mutableListOf<Int>()
        val batched = object : WholeBookStore by store {
            override suspend fun scanLookups(keys: List<String>): List<WholeBookLookup> {
                batchSizes += keys.size
                return keys.map { store.lookup(it) }
            }
        }
        val texts = Array(260) { "sentence $it" }
        val coverage = WholeBookTranslation(translator, SentenceRequests(translator, null, null), batched) { it.sentence.text.length }
            .scan(sentences(*texts))
        assertEquals(listOf(128, 128, 4), batchSizes)
        assertEquals(260, coverage.total)
        assertEquals(260, coverage.requests)
        assertEquals(texts.sumOf { it.length }.toLong(), coverage.outputCharacters)
        assertTrue(translator.asked.isEmpty())
    }

    @Test
    fun `four requests overlap and successful replies commit before a slower sibling`(): Unit = runTest {
        var active = 0
        var peak = 0
        val translator = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                active++
                peak = maxOf(peak, active)
                try {
                    delay(if (sentence == "slow") 1_000 else 100)
                    return super.translate(sentence, context, identity, book)
                } finally {
                    active--
                }
            }
        }
        val store = Store()
        val started = testScheduler.currentTime
        val run = launch {
            job(translator, store).run(sentences("slow", "two", "three", "four", "five", "six", "seven", "eight"), "test", 8)
        }
        testScheduler.advanceTimeBy(200)
        assertEquals(3, store.rows.size)
        assertFalse(store.finished)
        run.join()
        assertEquals(4, peak)
        assertEquals(1_100, testScheduler.currentTime - started)
        assertEquals(8 to 0, store.counts.last())
        assertTrue(store.finished)
    }

    @Test
    fun `duplicate pending keys pay once and refusals count every occurrence`(): Unit = runTest {
        val translator = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                delay(100)
                return super.translate(sentence, context, identity, book)
            }
        }.apply { refuse = "bad" }
        val store = Store()
        job(translator, store).run(sentences("bad", "bad", "bad", "bad", "bad"), "test", 5)
        // The first has no context; every later occurrence has the same request key.
        assertEquals(2, translator.asked.size)
        assertEquals(0 to 5, store.counts.last())
        assertTrue(store.finished)
    }

    @Test
    fun `pause cancels every pending request and does not send the next window`(): Unit = runTest {
        var asked = 0
        var cancelled = 0
        val translator = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                asked++
                try {
                    CompletableDeferred<Unit>().await()
                    error("Unexpected reply")
                } finally {
                    cancelled++
                }
            }
        }
        val store = Store()
        val run = launch { job(translator, store).run(sentences("one", "two", "three", "four", "five"), "test", 5) }
        runCurrent()
        assertEquals(4, asked)
        run.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(4, cancelled)
        assertTrue(store.rows.isEmpty())
        assertFalse(store.finished)
    }

    @Test
    fun `fatal sibling failure preserves completed answers and cancels outstanding requests`(): Unit = runTest {
        var cancelled = false
        val translator = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                when (sentence) {
                    "saved" -> delay(10)
                    "fail" -> { delay(100); throw TranslationError.Network(IOException("offline")) }
                    else -> try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        cancelled = true
                    }
                }
                return super.translate(sentence, context, identity, book)
            }
        }
        val store = Store()
        try {
            job(translator, store).run(sentences("saved", "fail", "pending"), "test", 3)
            error("Expected network pause")
        } catch (_: TranslationError.Network) {
            assertEquals("SAVED", store.rows.values.single().translation)
            assertEquals(1 to 0, store.counts.single())
            assertTrue(cancelled)
            assertFalse(store.finished)
        }
    }

    @Test
    fun `a failed checkpoint does not inflate later completed writes`(): Unit = runTest {
        val translator = object : Translator() {
            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                delay(10)
                return super.translate(sentence, context, identity, book)
            }
        }
        val store = Store()
        var commits = 0
        val failing = object : WholeBookStore by store {
            override suspend fun commit(key: String, translation: String?, done: Int, failed: Int): Boolean {
                if (++commits == 1) {
                    delay(100)
                    throw IOException("checkpoint failed")
                }
                return store.commit(key, translation, done, failed)
            }
        }
        try {
            WholeBookTranslation(translator, SentenceRequests(translator, null, null), failing) { it.sentence.text.length }
                .run(sentences("one", "two", "three"), "test", 3)
            error("Expected storage pause")
        } catch (_: IOException) {
            assertEquals(2, store.rows.size)
            assertEquals(listOf(1 to 0, 2 to 0), store.counts)
            assertFalse(store.finished)
        }
    }

    @Test
    fun `resume after committed save uses absolute occurrence counts and retained failures`() = runTest {
        val translator = Translator().apply { refuse = "bad" }
        val store = Store().apply { stopAfter = 2 }
        try {
            job(translator, store).run(sentences("one", "bad", "last", "last", "last"), "test", 5)
            error("Expected storage interruption")
        } catch (_: IOException) {
            assertFalse(store.finished)
        }
        store.stopAfter = null
        job(translator, store).run(sentences("one", "bad", "last", "last", "last"), "test", 5)
        assertTrue(store.finished)
        assertEquals(1, translator.asked.count { it == "one" })
        assertEquals(1, translator.asked.count { it == "bad" })
        assertEquals(4 to 1, store.counts.last())
        assertTrue(store.counts.all { it.first + it.second <= 5 })
        val after = job(translator, store).scan(sentences("one", "bad", "last", "last", "last"))
        assertEquals(4, after.cached)
        assertEquals(1, after.failed)
        assertEquals(0, after.requests)
        val retry = job(translator, store).scan(sentences("one", "bad", "last", "last", "last"), retryFailures = true)
        assertEquals(1, retry.requests)
    }

    @Test
    fun `changed identity prevents submission`() = runTest {
        val translator = Translator().apply { identity = "new" }
        try {
            job(translator, Store()).run(sentences("one"), "old", 1)
            error("Expected changed settings")
        } catch (_: TranslationError.Changed) {
            assertTrue(translator.asked.isEmpty())
        }
    }

    @Test
    fun `reader goes past a persisted refusal without network`() = runTest {
        val translator = Translator()
        val cache = object : PageTranslationCache {
            override suspend fun get(key: String): PageTranslationCache.Lookup =
                when (key.substringAfterLast('\u0000')) {
                    "bad" -> PageTranslationCache.Lookup(null, 0, refused = true)
                    else -> PageTranslationCache.Lookup("saved", 0)
                }
            override suspend fun put(key: String, value: String, stamp: Long) = error("Unexpected save")
        }
        val reader = SentenceTranslations(translator, cache)
        assertEquals("saved", reader.resolve(PageSentence("one", "a", "#1", null), null).text)
        try {
            reader.resolve(PageSentence("bad", "a", "#2", null), null)
            error("Expected refusal")
        } catch (_: TranslationError.Refused) {
            assertEquals("saved", reader.resolve(PageSentence("last", "a", "#3", null), null).text)
        }
        assertTrue(translator.asked.isEmpty())
    }
}
