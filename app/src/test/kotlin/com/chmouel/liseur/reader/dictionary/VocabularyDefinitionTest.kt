package com.chmouel.liseur.reader.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class VocabularyDefinitionTest {
    @Test
    fun `offline formats become plain vocabulary text`() {
        val formatted = StarDictEntry(
            headword = "seaman",
            sections = listOf(StarDictEntry.Section("Noun", listOf("<b>Sailor</b> &amp; <i>mariner</i>."))),
        )
        val fallback = StarDictEntry("seaman", fallbackHtml = "<p>A member of a ship’s crew.</p>")
        assertEquals(
            listOf("Sailor & mariner.", "A member of a ship’s crew."),
            vocabularyOfflineDefinitionLines(listOf(formatted, fallback)),
        )
    }
    @Test
    fun `vocabulary saves the dictionary senses from the book's language`() {
        val senses = listOf(
            DictionarySense("Symbol", listOf("An identifier"), language = "mul"),
            DictionarySense("Verb", listOf("To make an incision"), language = "en"),
            DictionarySense("Noun", listOf("Une coupe courte"), language = "fr"),
        )
        assertEquals(listOf("To make an incision"), vocabularyOnlineDefinitionLines(senses, "en-US"))
        assertEquals(listOf("Une coupe courte"), vocabularyOnlineDefinitionLines(senses, "fr-FR"))
    }

}
