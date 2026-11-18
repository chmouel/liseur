package com.chmouel.liseur.reader.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefinitionQueryTest {
    @Test
    fun `translated selection uses the displayed word language`() {
        assertEquals(
            DefinitionQuery("hello", "en"),
            definitionQuery("bonjour", "hello", "en", useDisplayedText = true),
        )
        assertEquals(
            DefinitionQuery("bonjour", "fr"),
            definitionQuery("bonjour", "bonjour", "fr", useDisplayedText = true),
        )
    }

    @Test
    fun `translated selection does not fall back to the original word`() {
        assertNull(definitionQuery("bonjour", null, "en", useDisplayedText = true))
        assertEquals(DefinitionQuery("hello", null), definitionQuery("bonjour", "hello", null, useDisplayedText = true))
    }

    @Test
    fun `original selection uses the book word and language`() {
        assertEquals(
            DefinitionQuery("bonjour", "fr"),
            definitionQuery("bonjour", "hello", "fr", useDisplayedText = false),
        )
    }

    @Test
    fun `selected text lookup removes French elisions and keeps internal apostrophes`() {
        assertEquals(DefinitionQuery("accord", "fr"), definitionQuery("“d’accord”", null, "fr", false))
        assertEquals(DefinitionQuery("accord", "fr"), definitionQuery("D'accord", null, "fr", false))
        assertEquals(DefinitionQuery("autrefois", "fr"), definitionQuery("", "qu’autrefois,", "fr", true))
        assertEquals(DefinitionQuery("aujourd'hui", "fr"), definitionQuery("aujourd’hui", null, "fr", false))
    }

    @Test
    fun `selected text lookup cleans dictionary markers and edge punctuation`() {
        assertEquals(DefinitionQuery("cat", "en"), definitionQuery("“(ˈc·a|t↑’s!)”", null, "en", false))
        assertEquals(DefinitionQuery("très bien", "fr"), definitionQuery("très\u00a0bien", null, "fr", false))
        assertNull(definitionQuery("…!", null, null, false))
    }

    @Test
    fun `grammatical cleanup uses the lookup language`() {
        assertEquals(DefinitionQuery("accord", "fr-CA"), definitionQuery("d’accord", null, "fr-CA", false))
        assertEquals(DefinitionQuery("l'acqua", "it"), definitionQuery("l’acqua", null, "it", false))
        assertEquals(DefinitionQuery("d'fhág", "ga"), definitionQuery("d’fhág", null, "ga", false))
        assertEquals(DefinitionQuery("d'accord", null), definitionQuery("d’accord", null, null, false))
        assertEquals(DefinitionQuery("cat", "en-GB"), definitionQuery("cat’s", null, "en-GB", false))
        assertEquals(DefinitionQuery("O'Connor's", null), definitionQuery("O’Connor’s", null, null, false))
    }

    @Test
    fun `Unicode spaces preserve lookup word boundaries`() {
        listOf('\u00A0', '\u2003', '\u2009', '\u202F', '\u3000').forEach { space ->
            assertEquals(DefinitionQuery("très bien", "fr"), definitionQuery("très${space}bien", null, "fr", false))
        }
    }
}
