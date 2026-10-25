package com.chmouel.liseur.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SentenceTranslationsTest {
    private class Translator : SentenceTranslator {
        override val source = "fr"
        override val target = "en"
        override val destination: String? = null
        var identity = "svc"
        var identityAfterFailure: String? = null
        val asked = mutableListOf<Pair<String, String?>>()
        val books = mutableListOf<BookFacts?>()
        var gate: CompletableDeferred<Unit>? = null
        var failWith: TranslationError? = null
        var closed = false
        var template: String? = null

        override suspend fun answering() = identity

        override suspend fun book(identity: String, facts: BookFacts?) = TranslationBookContext.render(template, facts)

        override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
            val by = this.identity
            asked += sentence to context
            books += book
            gate?.await()
            failWith?.let {
                identityAfterFailure?.let { changed -> this.identity = changed }
                throw it
            }
            return Translated("${sentence.uppercase()}<${context.orEmpty()}>${book?.chapter.orEmpty()}", by)
        }

        override fun close() {
            closed = true
        }
    }

    private class RacingTranslator(
        val pageGate: CompletableDeferred<Unit>,
        val speechGate: CompletableDeferred<Unit>,
    ) : SentenceTranslator {
        override val source = "fr"
        override val target = "en"
        override val destination: String? = null
        override suspend fun answering() = "svc"

        override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated? =
            when (context) {
                "page" -> {
                    pageGate.await()
                    throw TranslationError.Refused()
                }
                "speech" -> {
                    speechGate.await()
                    Translated("spoken translation", identity)
                }
                else -> error("Unexpected context: $context")
            }

        override fun close() = Unit
    }

    private fun at(text: String, before: String?, selector: String = "#p1") = PageSentence(text, "ch1.xhtml", selector, before)

    @Test
    fun `a reader miss waits for bulk then rechecks its saved answer`(): Unit = runTest {
        val admissions = TranslationAdmissions()
        val memory = MemoryPageTranslationCache()
        val translator = Translator()
        val cache = object : PageTranslationCache by memory {
            override suspend fun <T> request(identity: String, work: suspend () -> T): T =
                admissions.reader("book", identity, work)
        }
        val sentence = at("un", null)
        val request = SentenceRequests(translator, null, null).prepare(sentence, null, "svc")
        val finishBulk = CompletableDeferred<Unit>()
        val bulk = async(start = CoroutineStart.UNDISPATCHED) {
            admissions.bulk("book", "svc") {
                finishBulk.await()
                memory.put(request.key, "bulk saved", 0)
            }
        }
        val reading = async(start = CoroutineStart.UNDISPATCHED) {
            SentenceTranslations(translator, cache).resolve(sentence, null)
        }
        runCurrent()
        assertTrue(translator.asked.isEmpty())
        assertFalse(reading.isCompleted)
        finishBulk.complete(Unit)
        bulk.await()
        assertEquals("bulk saved", reading.await().text)
        assertTrue(translator.asked.isEmpty())
    }

    @Test
    fun `bulk waits until a previously admitted reader saves its answer`(): Unit = runTest {
        val admissions = TranslationAdmissions()
        val memory = MemoryPageTranslationCache()
        val translator = Translator().apply { gate = CompletableDeferred() }
        val cache = object : PageTranslationCache by memory {
            override suspend fun <T> request(identity: String, work: suspend () -> T): T =
                admissions.reader("book", identity, work)
        }
        val sentence = at("un", null)
        val request = SentenceRequests(translator, null, null).prepare(sentence, null, "svc")
        val reading = async(start = CoroutineStart.UNDISPATCHED) {
            SentenceTranslations(translator, cache).resolve(sentence, null)
        }
        runCurrent()
        val bulk = async(start = CoroutineStart.UNDISPATCHED) {
            admissions.bulk("book", "svc") { memory.get(request.key).translation }
        }
        assertFalse(bulk.isCompleted)
        translator.gate!!.complete(Unit)
        assertEquals(reading.await().text, bulk.await())
        assertEquals(1, translator.asked.size)
    }

    @Test
    fun `each sentence is translated with the facts of its own chapter`() = runTest {
        val translator = Translator()
        val facts = mapOf("ch1.xhtml" to BookFacts(chapter = "One"), "ch2.xhtml" to BookFacts(chapter = "Two"))
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache(), book = { facts[it] })
        translations.resolve(at("un", null), null)
        translations.resolve(PageSentence("deux", "ch2.xhtml", "#p1", null), null)
        assertEquals(listOf(facts["ch1.xhtml"], facts["ch2.xhtml"]), translator.books)
    }

    @Test
    fun `the same sentence in two chapters is saved under what each was told`() = runTest {
        val translator = Translator().apply { template = "Chapter: {chapter}" }
        val facts = mapOf("ch1.xhtml" to BookFacts(chapter = "One"), "ch2.xhtml" to BookFacts(chapter = "Two"))
        val cache = MemoryPageTranslationCache()
        val first = SentenceTranslations(translator, cache, book = { facts[it] })
        assertEquals("OUI<>One", first.resolve(at("oui", null), null).text)
        assertEquals("OUI<>Two", first.resolve(PageSentence("oui", "ch2.xhtml", "#p1", null), null).text)
        assertEquals(2, translator.asked.size)

        val reopened = SentenceTranslations(translator, cache, book = { facts[it] })
        assertEquals("OUI<>Two", reopened.resolve(PageSentence("oui", "ch2.xhtml", "#p1", null), null).text)
        assertEquals("OUI<>One", reopened.resolve(at("oui", null), null).text)
        assertEquals(2, translator.asked.size)
    }

    @Test
    fun `facts that cannot be read leave the sentence translated without them`() = runTest {
        val translator = Translator()
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache(), book = { error("no positions") })
        assertEquals("UN<>", translations.resolve(at("un", null), null).text)
        assertEquals(listOf<BookFacts?>(null), translator.books)
    }

    @Test
    fun `the same sentence asked at once by the page and the voice is asked once`() = runTest {
        val translator = Translator().apply { gate = CompletableDeferred() }
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val page = async(start = CoroutineStart.UNDISPATCHED) { translations.resolve(at("un", null), null) }
        val voice = async(start = CoroutineStart.UNDISPATCHED) { translations.resolve(at("un", null), null) }
        runCurrent()
        translator.gate!!.complete(Unit)
        assertEquals(page.await(), voice.await())
        assertEquals(1, translator.asked.size)
    }

    @Test
    fun `a sentence already accepted where it is is spoken as shown, whatever came before`() = runTest {
        val translator = Translator()
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val shown = translations.resolve(at("deux", "un"), "un")
        // The voice started here, without the sentence before.
        val spoken = translations.resolve(at("deux", "un"), null)
        assertEquals(shown, spoken)
        assertEquals(1, translator.asked.size)
    }

    @Test
    fun `the same words elsewhere are translated in their own context`() = runTest {
        val translator = Translator()
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val first = translations.resolve(at("oui", "il dit", selector = "#p1"), "il dit")
        val second = translations.resolve(at("oui", "elle dit", selector = "#p9"), "elle dit")
        assertEquals("OUI<il dit>", first.text)
        assertEquals("OUI<elle dit>", second.text)
        assertEquals(2, translator.asked.size)
    }

    @Test
    fun `a change of service is never answered from the old one's accepted words`() = runTest {
        val translator = Translator()
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        translations.resolve(at("un", null), null)
        translator.identity = "other"
        val again = translations.resolve(at("un", null), null)
        assertEquals("other", again.by)
        assertEquals(2, translator.asked.size)
    }

    @Test
    fun `a new service does not join an old answer still being saved`() = runTest {
        val translator = Translator()
        val memory = MemoryPageTranslationCache()
        val saving = CompletableDeferred<Unit>()
        val cache = object : PageTranslationCache by memory {
            override suspend fun put(key: String, value: String, stamp: Long) {
                if (key.startsWith("svc\u0000")) saving.await()
                memory.put(key, value, stamp)
            }
        }
        val translations = SentenceTranslations(translator, cache)
        val sentence = at("un", null)
        val old = async(start = CoroutineStart.UNDISPATCHED) { translations.resolve(sentence, null) }
        translator.identity = "other"
        val current = async(start = CoroutineStart.UNDISPATCHED) { translations.resolve(sentence, null) }
        saving.complete(Unit)

        assertEquals("svc", old.await().by)
        assertEquals("other", current.await().by)
        assertEquals(2, translator.asked.size)
    }

    @Test
    fun `a waiter whose asker went away asks itself`() = runTest {
        val translator = Translator().apply { gate = CompletableDeferred() }
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val page = async(start = CoroutineStart.UNDISPATCHED) { translations.resolve(at("un", null), null) }
        val voice = async(start = CoroutineStart.UNDISPATCHED) { translations.resolve(at("un", null), null) }
        runCurrent()
        page.cancel()
        runCurrent()
        translator.gate!!.complete(Unit)
        assertEquals("UN<>", voice.await().text)
        assertEquals(2, translator.asked.size)
    }

    @Test
    fun `two askers at once with different sentences before get the words accepted first`() = runTest {
        val translator = Translator().apply { gate = CompletableDeferred() }
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val page = async(start = CoroutineStart.UNDISPATCHED) { translations.resolve(at("deux", "un"), "un") }
        // The voice started here, without the sentence before.
        val voice = async(start = CoroutineStart.UNDISPATCHED) { translations.resolve(at("deux", "un"), null) }
        runCurrent()
        assertEquals(2, translator.asked.size)
        translator.gate!!.complete(Unit)
        assertEquals(page.await().text, voice.await().text)
    }

    @Test
    fun `a failure reaches every asker and is not kept`() = runTest {
        val translator = Translator().apply {
            gate = CompletableDeferred()
            failWith = TranslationError.Refused()
        }
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val page = async(start = CoroutineStart.UNDISPATCHED) { runCatching { translations.resolve(at("un", null), null) } }
        val voice = async(start = CoroutineStart.UNDISPATCHED) { runCatching { translations.resolve(at("un", null), null) } }
        runCurrent()
        translator.gate!!.complete(Unit)
        assertTrue(page.await().exceptionOrNull() is TranslationError.Refused)
        assertTrue(voice.await().exceptionOrNull() is TranslationError.Refused)
        assertEquals(1, translator.asked.size)
        translator.failWith = null
        assertEquals("UN<>", translations.resolve(at("un", null), null).text)
    }

    @Test
    fun `a page failure stays visible until that sentence is retried`() = runTest {
        val translator = Translator().apply {
            failWith = TranslationError.Refused()
            identityAfterFailure = "svc-2"
        }
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val scope = translations.beginPageWalk()

        val failed = runCatching { translations.resolveForPage(scope, at("un", null), null) }

        assertTrue(failed.exceptionOrNull() is TranslationError.Refused)
        translator.failWith = null
        translator.identityAfterFailure = null
        translator.identity = "svc-2"
        val stillFailed = runCatching { translations.resolve(at("un", null), null) }
        assertTrue(stillFailed.exceptionOrNull() is TranslationError.Refused)
        assertEquals(1, translator.asked.size)

        val retried = translations.resolveForPage(scope, at("un", null), null)
        assertEquals("UN<>", retried.text)
        assertEquals("svc-2", retried.by)
        assertEquals(2, translator.asked.size)
        assertEquals(retried, translations.resolve(at("un", null), null))
        translations.endPageWalk(scope)
    }

    @Test
    fun `a page refusal prevents a concurrent speech success in another context`() = runTest {
        val translator = RacingTranslator(CompletableDeferred(), CompletableDeferred())
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val scope = translations.beginPageWalk()
        val sentence = at("un", null)
        val page = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { translations.resolveForPage(scope, sentence, "page") }
        }
        val speech = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { translations.resolve(sentence, "speech") }
        }
        runCurrent()

        translator.pageGate.complete(Unit)
        runCurrent()
        assertTrue(page.await().exceptionOrNull() is TranslationError.Refused)
        translator.speechGate.complete(Unit)
        runCurrent()

        assertTrue(speech.await().exceptionOrNull() is TranslationError.Refused)
        assertEquals(null, translations.acceptedFor(sentence, "svc"))
        translations.endPageWalk(scope)
    }

    @Test
    fun `a concurrent speech success lets the page use that translation`() = runTest {
        val translator = RacingTranslator(CompletableDeferred(), CompletableDeferred())
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val scope = translations.beginPageWalk()
        val sentence = at("un", null)
        val page = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { translations.resolveForPage(scope, sentence, "page") }
        }
        val speech = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { translations.resolve(sentence, "speech") }
        }
        runCurrent()

        translator.speechGate.complete(Unit)
        runCurrent()
        assertEquals("spoken translation", speech.await().getOrThrow().text)
        translator.pageGate.complete(Unit)
        runCurrent()

        assertEquals("spoken translation", page.await().getOrThrow().text)
        assertEquals("spoken translation", translations.acceptedFor(sentence, "svc")?.text)
        translations.endPageWalk(scope)
    }

    @Test
    fun `the translator closes once the page and the voice have both let go`() {
        val translator = Translator()
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        assertTrue(translations.acquire())
        translations.release()
        assertFalse(translator.closed)
        translations.release()
        assertTrue(translator.closed)
        assertFalse(translations.acquire())
    }

    @Test
    fun `the text before a sentence agrees when one side holds less of it`() {
        assertTrue(SentenceTranslations.sameBefore("a long paragraph opening, then", "paragraph opening,  then"))
        assertTrue(SentenceTranslations.sameBefore(null, ""))
        assertFalse(SentenceTranslations.sameBefore("one thing", "another"))
        assertFalse(SentenceTranslations.sameBefore(null, "something"))
    }
}
