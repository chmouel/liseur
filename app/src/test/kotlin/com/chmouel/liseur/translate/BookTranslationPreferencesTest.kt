package com.chmouel.liseur.translate

import org.junit.Assert.*
import org.junit.Test

class BookTranslationPreferencesTest {
    @Test fun termsUseUnicodeBoundariesAndLiteralUnseparatedScripts() {
        assertTrue(containsTerm("Élodie’s book", "Élodie"))
        assertFalse(containsTerm("Élodienne", "Élodie"))
        assertFalse(containsTerm("Annabelle", "Anna"))
        assertFalse(containsTerm("e\u0301lan", "e"))
        assertTrue(containsTerm("我见到小明了", "小明"))
        assertTrue(containsTerm("(Anna)", "Anna"))
    }

    @Test fun onlyMatchingTermsTravelAndSourceIsUnchanged() {
        val preferences = BookTranslationPreferences("Keep the dry humour", listOf(TranslationTerm("Anna", "Anne"), TranslationTerm("Bob", "Robert")))
        val instructions = preferences.instructions(listOf("  Anna said: I left.  "))!!
        assertTrue(instructions.contains("Anne"))
        assertFalse(instructions.contains("Robert"))
        assertTrue(TranslationPrompt.user("  I left.  ").contains("\n  I left.  \n"))
        assertTrue(TranslationPrompt.translateGemma("en", "fr", "  I left.  ").endsWith("  I left.  "))
        assertTrue(TranslationPrompt.batchUser(listOf("  I left.  ")).contains("[1]   I left.  \n"))
    }

    @Test fun preferenceIdentityIncludesDirectionAndTerminology() {
        val empty = BookTranslationPreferences()
        assertEquals("", empty.identity)
        val custom = empty.copy(direction = "Dry humour")
        assertNotEquals(empty.identity, custom.identity)
        assertNotEquals(custom.identity, custom.copy(terms = listOf(TranslationTerm("Anna", "Anne"))).identity)
    }

    @Test fun previewStartsAtTheSentenceHoldingTheFirstVisibleWord() {
        val paragraph = listOf("Il pleut.", "Il pleut encore sur la ville.", "Marie attend.")
        assertEquals(0, sentenceHolding(paragraph, "", "Il"))
        // The words before tell the repeated start apart.
        assertEquals(1, sentenceHolding(paragraph, "Il pleut. Il pleut ", "encore"))
        assertEquals(2, sentenceHolding(paragraph, "encore sur la ville. ", "Marie"))
        assertNull(sentenceHolding(paragraph.take(1), "Il pleut. ", "Il"))
        assertNull(sentenceHolding(paragraph, "", null))
    }
}
