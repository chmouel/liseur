package com.chmouel.liseur.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceTranslationsTest {
    private class Translator : SentenceTranslator {
        override val source = "fr"
        override val target = "en"
        override val destination: String? = null
        var identity = "svc"
        val asked = mutableListOf<Pair<String, String?>>()
        var gate: CompletableDeferred<Unit>? = null
        var failWith: TranslationError? = null
        var closed = false

        override suspend fun answering() = identity

        override suspend fun translate(sentence: String, context: String?, identity: String): Translated {
            val by = this.identity
            asked += sentence to context
            gate?.await()
            failWith?.let { throw it }
            return Translated("${sentence.uppercase()}<${context.orEmpty()}>", by)
        }

        override fun close() {
            closed = true
        }
    }

    private fun at(text: String, before: String?, selector: String = "#p1") = PageSentence(text, "ch1.xhtml", selector, before)

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
