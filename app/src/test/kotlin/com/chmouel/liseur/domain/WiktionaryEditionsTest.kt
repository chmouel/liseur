package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WiktionaryEditionsTest {

    @Test
    fun `an edition's base url is its subdomain`() {
        val french = WiktionaryEditions.all.first { it.code == "fr" }
        assertEquals("https://fr.wiktionary.org", french.baseUrl)
    }

    @Test
    fun `a stored url maps back to its edition`() {
        assertEquals("fr", WiktionaryEditions.editionOf("https://fr.wiktionary.org")?.code)
        assertEquals("en", WiktionaryEditions.editionOf("https://en.wiktionary.org")?.code)
    }

    @Test
    fun `anything else is custom`() {
        assertNull(WiktionaryEditions.editionOf("https://mirror.example/wiktionary"))
        assertNull(WiktionaryEditions.editionOf("https://fr.wikictionnary.org"))
        assertNull(WiktionaryEditions.editionOf(null))
        // A real edition, just not one the picker lists.
        assertNull(WiktionaryEditions.editionOf("https://eo.wiktionary.org"))
    }

    @Test
    fun `recognises any wikimedia-hosted edition, listed or not`() {
        assertTrue(WiktionaryEditions.isWiktionaryHost("https://fr.wiktionary.org"))
        assertTrue(WiktionaryEditions.isWiktionaryHost("https://eo.wiktionary.org"))
        assertFalse(WiktionaryEditions.isWiktionaryHost("https://mirror.example"))
        // The misspelling that earned this file is not a Wiktionary.
        assertFalse(WiktionaryEditions.isWiktionaryHost("https://fr.wikictionnary.org"))
        assertFalse(WiktionaryEditions.isWiktionaryHost("https://a.b.wiktionary.org"))
    }

    @Test
    fun `every listed edition is picked out of its own url`() {
        for (edition in WiktionaryEditions.all) {
            assertEquals(edition, WiktionaryEditions.editionOf(edition.baseUrl))
            assertTrue(WiktionaryEditions.isWiktionaryHost(edition.baseUrl))
        }
    }
}
