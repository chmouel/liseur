package com.chmouel.liseur.tts

import com.chmouel.liseur.data.settings.TranslationContextSize
import com.chmouel.liseur.translate.BookFacts
import com.chmouel.liseur.translate.PageSentence
import kotlinx.coroutines.test.runTest
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
    fun `earlier text never comes from another chapter`() = runTest {
        val place = PageSentence("Chapter two begins.", "ch2.xhtml", "#p1", null)
        val key = SpeechKey(place.text, place)
        val active = SpeechContext.Configuration(enabled = true, size = TranslationContextSize.SMALL, template = "")
        fun context(found: List<PageSentence>?) = SpeechContext(
            preceding = { _, _ -> found },
            bookFacts = { null },
            configuration = { active },
        )
        // At the chapter's start, the utterance before is the last chapter's.
        assertNull(context(emptyList()).resolve(key, previous = "The end of chapter one."))
        // Only a sentence that cannot be found falls back on what was read before it.
        assertTrue(context(null).resolve(key, previous = "The end of chapter one.")!!.contains("The end of chapter one."))
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
