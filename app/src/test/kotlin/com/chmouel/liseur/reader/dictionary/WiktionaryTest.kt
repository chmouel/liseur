package com.chmouel.liseur.reader.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WiktionaryTest {

    private val json = """
        {
          "en": [
            {
              "partOfSpeech": "Verb",
              "language": "English",
              "definitions": [
                { "definition": "To <a href=\"/wiki/take\">take</a> away." },
                { "definition": "To move &amp; relocate." }
              ]
            },
            {
              "partOfSpeech": "Noun",
              "language": "English",
              "definitions": []
            }
          ],
          "fr": [
            {
              "partOfSpeech": "Verbe",
              "definitions": [ { "definition": "Enlever." } ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `keeps only the languages asked for, in order`() {
        val senses = parseWiktionaryDefinitions(json, listOf("fr", "en"))
        assertEquals(listOf("Verbe", "Verb"), senses.map { it.partOfSpeech })
    }

    @Test
    fun `drops parts of speech with no definitions`() {
        val senses = parseWiktionaryDefinitions(json, listOf("en"))
        assertEquals(1, senses.size)
        assertEquals("Verb", senses.first().partOfSpeech)
    }

    @Test
    fun `strips markup and decodes entities`() {
        val senses = parseWiktionaryDefinitions(json, listOf("en"))
        assertEquals(listOf("To take away.", "To move & relocate."), senses.first().definitions)
    }

    @Test
    fun `missing language yields nothing rather than throwing`() {
        assertTrue(parseWiktionaryDefinitions(json, listOf("de")).isEmpty())
    }

    @Test
    fun `malformed json yields nothing`() {
        assertTrue(parseWiktionaryDefinitions("not json", listOf("en")).isEmpty())
    }

    @Test
    fun `lookup term loses the punctuation a selection catches`() {
        assertEquals("filehandle", normaliseLookupTerm("  “filehandle,” "))
        assertEquals("well-known", normaliseLookupTerm("well-known."))
        assertEquals("don't", normaliseLookupTerm("(don't)"))
    }

    @Test
    fun `lookup term keeps only the first line`() {
        assertEquals("first", normaliseLookupTerm("first\nsecond"))
    }

    @Test
    fun `book language comes first and english is always available`() {
        assertEquals(listOf("fr", "en"), WiktionaryClient.languagesFor("fr-FR"))
        assertEquals(listOf("en"), WiktionaryClient.languagesFor("en"))
        assertEquals(listOf("en"), WiktionaryClient.languagesFor(null))
    }
}
