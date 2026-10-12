package com.chmouel.liseur.tts

import com.chmouel.liseur.translate.MemoryPageTranslationCache
import com.chmouel.liseur.translate.PageSentence
import com.chmouel.liseur.translate.PageSentences
import com.chmouel.liseur.translate.PageTranslation
import com.chmouel.liseur.translate.SentenceTranslations
import com.chmouel.liseur.translate.SentenceTranslator
import com.chmouel.liseur.translate.Translated
import com.chmouel.liseur.translate.TranslationError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.util.Language

@OptIn(ExperimentalCoroutinesApi::class)
class TranslatedSpeechTest {
    private class Translator : SentenceTranslator {
        override val source = "fr"
        override val target = "en"
        override val destination: String? = null
        var identity = "svc"
        val asked = mutableListOf<Pair<String, String?>>()
        var failOn: String? = null
        var longer = 1

        override suspend fun answering() = identity

        override suspend fun translate(sentence: String, context: String?, identity: String): Translated {
            asked += sentence to context
            if (sentence == failOn) throw TranslationError.Refused()
            return Translated(List(longer) { "<$sentence>" }.joinToString(" "), identity)
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
    fun `read aloud shares a sentence the page left untranslated`() = runTest {
        val translator = Translator().apply { failOn = "untranslated" }
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val book = listOf(
            PageSentence("first", "ch1.xhtml", "#p1", null),
            PageSentence("untranslated", "ch1.xhtml", "#p1", "first"),
        )
        val page = PageTranslation(backgroundScope, translations, { _: Int ->
            object : PageSentences {
                var at = 0
                override suspend fun next() = book.getOrNull(at++)
            }
        })
        page.start(0, "start")
        runCurrent()
        assertEquals(listOf("first"), page.swaps.value["ch1.xhtml"]?.map { it.sentence.text })

        translator.failOn = null
        val failed = runCatching {
            TranslatedSpeech(translations, { listOf(it) }, { listOf(it) })
                .prepare(SpeechKey("untranslated", book[1], "svc"), previous = "first")
        }
        assertTrue(failed.exceptionOrNull() is TranslationError.Refused)
        assertEquals(2, translator.asked.size)

        translator.identity = "svc-2"
        val underNewService = TranslatedSpeech(translations, { listOf(it) }, { listOf(it) })
            .prepare(SpeechKey("untranslated", book[1], "svc-2"), previous = "first")
        assertEquals(listOf("<untranslated>"), underNewService)
        assertEquals(3, translator.asked.size)

        assertTrue(translations.acquire())
        page.stop()
        assertTrue(translations.acquire())
        val restartedPage = PageTranslation(backgroundScope, translations, { _: Int ->
            object : PageSentences {
                var at = 0
                override suspend fun next() = book.getOrNull(at++)
            }
        })
        restartedPage.start(0, "start")
        runCurrent()
        assertEquals(listOf("first", "untranslated"), restartedPage.swaps.value["ch1.xhtml"]?.map { it.sentence.text })
        assertEquals(4, translator.asked.size)
        restartedPage.stop()
        translations.release()
    }

    @Test
    fun `a changed translation identity cannot be filed under the previous one`() = runTest {
        val translator = Translator()
        val key = SpeechKey("sentence", place.copy(text = "sentence"), "svc")
        translator.identity = "new-service"

        val failed = runCatching { speech(translator, { listOf(it) }).prepare(key, previous = null) }

        assertTrue(failed.exceptionOrNull() is TranslationError.Changed)
        assertEquals(1, translator.asked.size)
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
    fun `translation identity changes discard prefetched audio`() = runTest {
        val prepared = mutableListOf<SpeechKey>()
        val cache = SpeechCache(backgroundScope, { SpeechAudio(it.toByteArray()) }, prepare = { key, _ ->
            prepared += key
            listOf(key.translationIdentity.orEmpty())
        })
        val old = SpeechKey("same", place.copy(text = "same"), "old")
        val current = old.copy(translationIdentity = "current")

        cache.prefetch(old)
        runCurrent()
        assertEquals(3L, cache.cachedBytes)

        val audio = cache.claim(current).await()

        assertEquals("current", String(audio.pcm))
        assertEquals(0L, cache.cachedBytes)
        assertEquals(listOf(old, current), prepared)
    }

    @Test
    fun `a grouped request cannot join more PCM than the cache budget`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val chunk = ByteArray(SpeechAudio.MAX_PCM_BYTES)
        var synthesized = 0
        val cache = SpeechCache(
            scope,
            { synthesized++; SpeechAudio(chunk) },
            prepare = { _, _ -> listOf("one", "two", "three") },
        )

        val failed = runCatching { cache.claim(SpeechKey("group", place)).await() }
        scope.cancel()

        assertTrue(failed.exceptionOrNull() is SpeechError.InvalidResponse)
        assertEquals(3, synthesized)
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
