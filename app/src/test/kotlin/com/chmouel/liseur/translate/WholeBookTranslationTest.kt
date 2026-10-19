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
