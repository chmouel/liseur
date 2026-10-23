package com.chmouel.liseur.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTranslationChoiceTest {

    private val seed = PageTranslationChoice("en", "fr", "server", "stub", "stub-chat")

    @Test
    fun `nothing changed or only spaces around the model applies nothing`() {
        assertFalse(seed.applies(seed, TranslationStep.Translate))
        assertFalse(seed.copy(model = " stub-chat ").applies(seed, TranslationStep.Translate))
    }

    @Test
    fun `a change applies once the service can translate it`() {
        assertTrue(seed.copy(target = "de").applies(seed, TranslationStep.Translate))
        assertTrue(seed.copy(model = "other").applies(seed, TranslationStep.Translate))
        // The bar offers the download once the run asks for it.
        assertTrue(seed.copy(service = "device", server = null).applies(seed, TranslationStep.NotDownloaded))
    }

    @Test
    fun `a change the service cannot run waits`() {
        val changed = seed.copy(service = "device", server = null, source = null)
        listOf(TranslationStep.NotSetUp, TranslationStep.NeedsSource, TranslationStep.Same, TranslationStep.Unsupported, null)
            .forEach { assertFalse("$it", changed.applies(seed, it)) }
    }
}
