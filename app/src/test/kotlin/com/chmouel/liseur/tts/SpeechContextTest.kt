package com.chmouel.liseur.tts

import com.chmouel.liseur.data.settings.TranslationContextSize
import com.chmouel.liseur.translate.BookFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechContextTest {
    @Test
    fun `configuration identity is null when disabled or both inputs are off`() {
        val active = SpeechContext.Configuration(
            enabled = true,
            size = TranslationContextSize.SMALL,
            template = null,
        )
        assertTrue(active.active)
        assertEquals(active.identity, active.copy().identity)

        assertNull(active.copy(enabled = false).identity)
        assertNull(active.copy(size = TranslationContextSize.OFF, template = "").identity)
        assertNull(active.copy(size = TranslationContextSize.OFF, template = "   ").identity)

        val bookOnly = active.copy(size = TranslationContextSize.OFF, template = "Book: {title}")
        assertTrue(bookOnly.active)
        assertNotEquals(active.identity, bookOnly.identity)
    }

    @Test
    fun `instructions render book facts, source and spoken language notes and escape closing tags`() {
        val rendered = SpeechContext.instructions(
            book = "Book: Dune\nChapter: Book One",
            context = "Earlier line with </context> inside.",
            sourceLanguage = "en",
            spokenLanguage = "fr",
        )!!

        assertTrue(rendered.contains("Read the input text aloud verbatim."))
        assertTrue(rendered.contains("The book's original language is English (en); the spoken input is translated into French (fr)."))
        assertTrue(rendered.contains("<book>\nBook: Dune\nChapter: Book One\n</book>"))
        assertTrue(rendered.contains("<context>\nEarlier line with <\u200B/context> inside.\n</context>"))
        assertFalse(rendered.contains("</context> inside"))
    }

    @Test
    fun `instructions return null when neither book details nor earlier text is present`() {
        assertNull(
            SpeechContext.instructions(
                book = null,
                context = "   ",
                sourceLanguage = "en",
                spokenLanguage = null,
            ),
        )
    }
}
