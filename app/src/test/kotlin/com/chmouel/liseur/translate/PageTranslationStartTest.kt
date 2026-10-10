package com.chmouel.liseur.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTranslationStartTest {
    private class Translator : SentenceTranslator {
        override val source = "fr"
        override val target = "en"
        override val destination: String? = null
        var closed = false

        override suspend fun answering() = "svc"

        override suspend fun translate(sentence: String, context: String?, identity: String) = null

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `an installed run keeps its translator`() = runTest {
        val translator = Translator()
        var installed: SentenceTranslator? = null

        val started = startPageTranslation({ translator }, { "here" }, { true }) { t, _ -> installed = t }

        assertTrue(started)
        assertTrue(installed === translator)
        assertFalse(translator.closed)
    }

    @Test
    fun `a start no longer wanted closes its translator`() = runTest {
        val translator = Translator()
        var installed = false

        val started = startPageTranslation({ translator }, { "here" }, { false }) { _, _ -> installed = true }

        assertFalse(started)
        assertFalse(installed)
        assertTrue(translator.closed)
    }

    @Test
    fun `a start cancelled while finding where to begin closes its translator`() = runTest {
        val translator = Translator()
        val finding = CompletableDeferred<Unit>()
        var installed = false

        val start = async(start = CoroutineStart.UNDISPATCHED) {
            startPageTranslation({ translator }, { finding.await() }, { true }) { _, _ -> installed = true }
        }
        start.cancel()
        start.join()

        assertFalse(installed)
        assertTrue(translator.closed)
    }

    @Test
    fun `no service starts nothing`() = runTest {
        var installed = false

        val started = startPageTranslation({ null }, { "here" }, { true }) { _, _ -> installed = true }

        assertFalse(started)
        assertFalse(installed)
    }
}
