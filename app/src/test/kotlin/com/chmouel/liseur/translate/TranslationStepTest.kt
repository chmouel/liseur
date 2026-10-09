package com.chmouel.liseur.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationStepTest {

    private fun step(
        passage: String = "Bonjour",
        configured: Boolean = true,
        detects: Boolean = false,
        source: String? = "fr",
        target: String = "en",
        targets: Map<String, PairState>? = mapOf("en" to PairState.Ready),
    ) = TranslationStep.of(passage, configured, detects, source, target, targets)

    @Test
    fun `a long passage is refused before anything else`() {
        assertEquals(TranslationStep.TooLong, step(passage = "a".repeat(TranslationPrompt.MAX_CHARACTERS + 1), configured = false))
    }

    @Test
    fun `nothing is asked of a service that is not set up`() {
        assertEquals(TranslationStep.NotSetUp, step(configured = false))
    }

    @Test
    fun `a passage already in the target language is not sent`() {
        assertEquals(TranslationStep.Same, step(source = "en-GB", target = "en"))
    }

    @Test
    fun `an unknown language is asked for unless the service works it out`() {
        assertEquals(TranslationStep.NeedsSource, step(source = null))
        assertEquals(TranslationStep.Translate, step(source = null, detects = true, targets = null))
    }

    @Test
    fun `the device's pair decides`() {
        assertEquals(TranslationStep.Translate, step())
        assertEquals(TranslationStep.NotDownloaded, step(targets = mapOf("en" to PairState.NeedsDownload)))
        assertEquals(TranslationStep.Unsupported, step(targets = mapOf("de" to PairState.Ready)))
        assertEquals(TranslationStep.Unsupported, step(targets = emptyMap()))
    }

    @Test
    fun `a service that takes any language translates`() {
        assertEquals(TranslationStep.Translate, step(targets = null))
    }
}
