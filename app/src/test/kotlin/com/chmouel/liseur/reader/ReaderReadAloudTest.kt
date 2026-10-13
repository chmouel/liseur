package com.chmouel.liseur.reader

import com.chmouel.liseur.translate.MemoryPageTranslationCache
import com.chmouel.liseur.translate.SentenceTranslations
import com.chmouel.liseur.translate.SentenceTranslator
import com.chmouel.liseur.translate.Translated
import com.chmouel.liseur.tts.SpeechTranslation
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
            )
        }

        assertEquals("start place unavailable", failure.exceptionOrNull()?.message)
        assertFalse(started)
        translations.release()
        assertEquals(1, translator.closes)
    }
}
