package com.chmouel.liseur.tts

import com.chmouel.liseur.translate.MemoryPageTranslationCache
import com.chmouel.liseur.translate.PageSentence
import com.chmouel.liseur.translate.PageSentences
import com.chmouel.liseur.translate.PageTranslation
import com.chmouel.liseur.translate.SentenceTranslations
import com.chmouel.liseur.translate.SentenceTranslator
import com.chmouel.liseur.translate.Translated
import com.chmouel.liseur.translate.TranslationError
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.util.Language

class TranslatedSpeechTest {
    private class Translator : SentenceTranslator {
        override val source = "fr"
        override val target = "en"
        override val destination: String? = null
        val asked = mutableListOf<Pair<String, String?>>()
        var failOn: String? = null
        var longer = 1

        override suspend fun answering() = "svc"

        override suspend fun translate(sentence: String, context: String?, identity: String): Translated {
            asked += sentence to context
            if (sentence == failOn) throw TranslationError.Refused()
            return Translated(List(longer) { "<$sentence>" }.joinToString(" "), "svc")
        }

        override fun close() = Unit
    }

    private val place = PageSentence("", "ch1.xhtml", "#p1", "Before.")

    private fun speech(translator: Translator, split: (String) -> List<String> = { it.split("|") }) =
        TranslatedSpeech(SentenceTranslations(translator, MemoryPageTranslationCache()), split, { listOf(it) })

    @Test
    fun `a grouped request translates each sentence with the one before it`() = runTest {
        val translator = Translator()
        val said = speech(translator).prepare(SpeechKey("a|b|c", place.copy(text = "a|b|c")), previous = "x|y")
        assertEquals(listOf("<a> <b> <c>"), said)
        assertEquals(listOf("a" to "y", "b" to "a", "c" to "b"), translator.asked)
    }

    @Test
    fun `a sentence that cannot be translated fails the whole request, with nothing in the book's words`() = runTest {
        val translator = Translator().apply { failOn = "b" }
        val failed = runCatching { speech(translator).prepare(SpeechKey("a|b", place.copy(text = "a|b")), null) }
        assertTrue(failed.exceptionOrNull() is TranslationError.Refused)
    }

    @Test
    fun `a request with no place is never translated by its words alone`() = runTest {
        val translator = Translator()
        val failed = runCatching { speech(translator).prepare(SpeechKey("a"), null) }
        assertTrue(failed.exceptionOrNull() is TranslationError)
        assertTrue(translator.asked.isEmpty())
    }

    @Test
    fun `the voice says what the page put in its place`() = runTest {
        val translator = Translator()
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val book = listOf(
            PageSentence("un", "ch1.xhtml", "#p1", null),
            PageSentence("deux", "ch1.xhtml", "#p1", "un "),
        )
        val run = PageTranslation(backgroundScope, translations, { _: Int ->
            object : PageSentences {
                var at = 0
                override suspend fun next() = book.getOrNull(at++)
            }
        })
        run.start(0, "start")
        runCurrent()
        assertEquals(2, translator.asked.size)
        val spoken = TranslatedSpeech(translations, { listOf(it) }, { listOf(it) })
            .prepare(SpeechKey("deux", book[1]), previous = null)
        assertEquals(listOf("<deux>"), spoken)
        assertEquals(2, translator.asked.size)
        // The page let go; the voice still holds the translator.
        assertTrue(translations.acquire())
        run.stop()
        assertTrue(translations.open)
    }

    @Test
    fun `a translation longer than a request takes goes in pieces of whole sentences`() {
        val sentence = "This sentence is translated into something a fair bit longer than it was."
        val text = List(30) { sentence }.joinToString(" ")
        val pieces = TranslatedSpeech.piecesOf(text, Language("en"), max = 200)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= 200 })
        assertTrue(pieces.all { it.endsWith(".") })
        assertEquals(text.filterNot(Char::isWhitespace), pieces.joinToString("").filterNot(Char::isWhitespace))
    }

    @Test
    fun `a request said in pieces is one stretch of audio`() = runTest {
        val cache = SpeechCache(backgroundScope, { SpeechAudio(it.toByteArray()) }, prepare = { _, _ -> listOf("ab", "cd") })
        val audio = cache.claim(SpeechKey("x", place)).await()
        assertEquals("abcd", String(audio.pcm))
    }

    @Test
    fun `the same words in two places are two requests`() = runTest {
        val asked = mutableListOf<SpeechKey>()
        val cache = SpeechCache(backgroundScope, { SpeechAudio(it.toByteArray()) }, prepare = { key, _ ->
            asked += key
            listOf(key.text + key.place?.before)
        })
        cache.prefetch(SpeechKey("oui", place.copy(text = "oui", before = "il dit")))
        cache.prefetch(SpeechKey("oui", place.copy(text = "oui", before = "elle dit")))
        runCurrent()
        assertEquals(2, asked.size)
        assertEquals("ouielle dit", String(cache.claim(SpeechKey("oui", place.copy(text = "oui", before = "elle dit"))).await().pcm))
        assertEquals(2, asked.size)
    }
}
