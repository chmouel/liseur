package com.chmouel.liseur.reader

import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import com.chmouel.liseur.translate.MemoryPageTranslationCache
import com.chmouel.liseur.translate.SentenceTranslations
import com.chmouel.liseur.translate.SentenceTranslator
import com.chmouel.liseur.translate.Translated
import com.chmouel.liseur.tts.SpeechTranslation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalReadiumApi::class)
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ReaderReadAloudTest {
    private class Translator : SentenceTranslator {
        override val source: String? = "fr"
        override val target = "en"
        override val destination: String? = null
        var closes = 0

        override suspend fun answering() = "test"

        override suspend fun translate(sentence: String, context: String?, identity: String) =
            Translated(sentence, identity)

        override fun close() {
            closes++
        }
    }

    @Test
    fun `translated selection without a source match starts from a coarse locator`() {
        val selected = Locator(href = Url("chapter.xhtml")!!, mediaType = MediaType.XHTML).copy(
            locations = Locator.Locations(
                otherLocations = mapOf(
                    ExactLocatorAnchor.MARKER to 1,
                    ExactLocatorAnchor.CSS_SELECTOR to "p:nth-child(2)",
                ),
            ),
            text = Locator.Text(before = "before", highlight = "translated words", after = "after"),
        )

        val start = readAloudStartLocator(selected, original = null, translated = true)

        assertEquals(Locator.Text(), start.text)
        assertEquals(null, start.locations.otherLocations[ExactLocatorAnchor.MARKER])
        assertEquals(selected.href, start.href)
    }

    @Test
    fun `read aloud keeps a mapped source locator and ordinary selection text`() {
        val selected = Locator(href = Url("chapter.xhtml")!!, mediaType = MediaType.XHTML)
        val original = selected.copy(text = Locator.Text(highlight = "source words"))

        assertSame(original, readAloudStartLocator(selected, original, translated = true))
        assertSame(selected, readAloudStartLocator(selected, original = null, translated = false))
    }

    @Test
    fun `page translation is handed to read aloud and stays open until the session releases it`() = runTest {
        val translator = Translator()
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val locator = Locator(href = Url("chapter.xhtml")!!, mediaType = MediaType.XHTML)
        var receivedLocator: Locator? = null
        var receivedTranslation: SpeechTranslation? = null

        val handedOff = handoffPageTranslationToReadAloud(
            start = { place, speech ->
                receivedLocator = place
                receivedTranslation = speech
            },
            translations = translations,
            source = "fr",
            target = "en",
            startingPoint = { locator },
            isCurrent = { true },
        )

        assertTrue(handedOff)
        assertSame(locator, receivedLocator)
        val speech = requireNotNull(receivedTranslation)
        assertSame(translations, speech.translations)
        assertEquals("fr", speech.source)
        assertEquals("en", speech.target)

        translations.release()
        assertTrue(translations.open)
        assertEquals(0, translator.closes)
        speech.translations.release()
        assertFalse(translations.open)
        assertEquals(1, translator.closes)
    }

    @Test
    fun `failed start-place lookup releases the acquired page translation`() = runTest {
        val translator = Translator()
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        var started = false

        val failure = runCatching {
            handoffPageTranslationToReadAloud(
                start = { _, _ -> started = true },
                translations = translations,
                source = "fr",
                target = "en",
                startingPoint = { error("start place unavailable") },
                isCurrent = { true },
            )
        }

        assertEquals("start place unavailable", failure.exceptionOrNull()?.message)
        assertFalse(started)
        translations.release()
        assertEquals(1, translator.closes)
    }

    @Test
    fun `translation stopped while finding start place does not transfer its hold`() = runTest {
        val translator = Translator()
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache())
        val locator = Locator(href = Url("chapter.xhtml")!!, mediaType = MediaType.XHTML)
        val atStartPlace = CompletableDeferred<Unit>()
        val finishStartPlace = CompletableDeferred<Locator>()
        var current = true
        var started = false

        val handoff = async {
            handoffPageTranslationToReadAloud(
                start = { _, _ -> started = true },
                translations = translations,
                source = "fr",
                target = "en",
                startingPoint = {
                    atStartPlace.complete(Unit)
                    finishStartPlace.await()
                },
                isCurrent = { current },
            )
        }

        atStartPlace.await()
        current = false
        finishStartPlace.complete(locator)

        assertFalse(handoff.await())
        assertFalse(started)
        translations.release()
        assertEquals(1, translator.closes)
    }
}
