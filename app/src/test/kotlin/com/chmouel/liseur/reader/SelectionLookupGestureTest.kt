package com.chmouel.liseur.reader

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionLookupGestureTest {
    @Test
    fun `only one lookup happens until the selection is cleared`() {
        val gesture = SelectionLookupGesture()
        assertTrue(gesture.select("reader", Locale.ENGLISH))
        assertFalse(gesture.select("reader", Locale.ENGLISH))
        assertFalse(gesture.select("reader selects", Locale.ENGLISH))
        gesture.clear()
        assertTrue(gesture.select("reader", Locale.ENGLISH))
    }

    @Test
    fun `select text bypasses lookup until the selection is cleared`() {
        val gesture = SelectionLookupGesture()
        gesture.selectText()
        assertFalse(gesture.select("reader", Locale.ENGLISH))
        gesture.clear()
        assertTrue(gesture.select("reader", Locale.ENGLISH))
    }

    @Test
    fun `multiple words and punctuation alone keep the toolbar`() {
        for (text in listOf("two words", "two\nwords", "…", "")) {
            val gesture = SelectionLookupGesture()
            assertFalse(text, gesture.select(text, Locale.ENGLISH))
            assertFalse(gesture.select("word", Locale.ENGLISH))
        }
    }

    @Test
    fun `word boundaries accept accents apostrophes and non Latin scripts`() {
        for ((text, locale) in listOf(
            "définition" to Locale.FRENCH,
            "don't" to Locale.ENGLISH,
            "слово" to Locale.forLanguageTag("ru"),
            "书" to Locale.SIMPLIFIED_CHINESE,
        )) {
            assertTrue(text, SelectionLookupGesture().select(text, locale))
        }
    }
}
