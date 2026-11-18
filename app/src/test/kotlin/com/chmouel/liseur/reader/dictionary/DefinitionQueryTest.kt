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
    fun `selected headwords keep their original punctuation`() {
        assertEquals(DefinitionQuery("C++", "en"), definitionQuery(" C++ ", null, "en", false))
        assertEquals(DefinitionQuery("@", null), definitionQuery("@", null, null, false))
        assertEquals(DefinitionQuery("“d’Artagnan”", "fr"), definitionQuery("“d’Artagnan”", null, "fr", false))
        assertEquals(emptyList<String>(), DefinitionQuery("well—known", "en").fallbackWords())
    }

    @Test
    fun `cleanup is a fallback for the selected spelling`() {
        assertEquals(listOf("C"), DefinitionQuery("C++", "en").fallbackWords())
        assertEquals(emptyList<String>(), DefinitionQuery("@", null).fallbackWords())
        assertEquals(listOf("cat's", "cat"), DefinitionQuery("“(ˈc·a|t↑’s!)”", "en").fallbackWords())
        assertEquals(listOf("filehandle"), DefinitionQuery("“filehandle,”", null).fallbackWords())
        assertEquals(listOf("well-known"), DefinitionQuery("well-known.", null).fallbackWords())
        assertEquals(listOf("don't"), DefinitionQuery("(don't)", null).fallbackWords())
        assertEquals(listOf("first"), DefinitionQuery("first\nsecond", null).fallbackWords())
    }

    @Test
    fun `grammatical fallback uses the lookup language`() {
        assertEquals(listOf("d'accord", "accord"), DefinitionQuery("d’accord", "fr-CA").fallbackWords())
        assertEquals(listOf("qu'autrefois", "autrefois"), definitionQuery("", "qu’autrefois,", "fr", true)?.fallbackWords())
        assertEquals(listOf("cat"), DefinitionQuery("cat's", "en-GB").fallbackWords())
        assertEquals(listOf("l'acqua"), DefinitionQuery("l’acqua", "it").fallbackWords())
        assertEquals(listOf("d'fhág"), DefinitionQuery("d’fhág", "ga").fallbackWords())
        assertEquals(listOf("d'accord"), DefinitionQuery("d’accord", null).fallbackWords())
        assertEquals(listOf("aujourd'hui"), DefinitionQuery("aujourd’hui", "fr").fallbackWords())
    }

    @Test
    fun `Unicode spaces preserve lookup word boundaries`() {
        listOf('\u00A0', '\u2003', '\u2009', '\u202F', '\u3000').forEach { space ->
            assertEquals(listOf("très bien"), DefinitionQuery("très${space}bien", "fr").fallbackWords())
        }
    }

    @Test
    fun `quotes and brackets in other scripts are stripped from the edges`() {
        assertEquals(listOf("bonjour"), DefinitionQuery("«bonjour»", "fr").fallbackWords())
        assertEquals(listOf("hola"), DefinitionQuery("¿hola?", "es").fallbackWords())
        assertEquals(listOf("cat"), DefinitionQuery("「cat」", null).fallbackWords())
        assertEquals(listOf("chat"), DefinitionQuery("¡chat!", null).fallbackWords())
    }

    @Test
    fun `every French elision falls back to the word it hides`() {
        assertEquals(listOf("j'aime", "aime"), DefinitionQuery("j’aime", "fr").fallbackWords())
        assertEquals(listOf("c'est", "est"), DefinitionQuery("c’est", "fr").fallbackWords())
    }
}
