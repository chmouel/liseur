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
}
