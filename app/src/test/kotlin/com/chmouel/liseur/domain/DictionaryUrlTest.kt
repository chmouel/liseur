package com.chmouel.liseur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictionaryUrlTest {

    @Test
    fun `adds a scheme and drops the trailing slash`() {
        assertEquals("https://fr.wiktionary.org", DictionaryUrl.normalise("fr.wiktionary.org"))
        assertEquals("https://fr.wiktionary.org", DictionaryUrl.normalise("https://fr.wiktionary.org/"))
        assertEquals("https://fr.wiktionary.org", DictionaryUrl.normalise("  fr.wiktionary.org  "))
    }

    @Test
    fun `drops the query and fragment`() {
        assertEquals(
            "https://en.wiktionary.org",
            DictionaryUrl.normalise("https://en.wiktionary.org/?search=x#top"),
        )
    }

    @Test
    fun `keeps a path prefix for a mirror behind a proxy`() {
        assertEquals(
            "https://mirror.example/wiktionary",
            DictionaryUrl.normalise("https://mirror.example/wiktionary"),
        )
    }

    /**
     * The book server gets a cleartext exemption because it is often on
     * the reader's own network; a public dictionary does not.
     */
    @Test
    fun `refuses plain http and other schemes`() {
        assertNull(DictionaryUrl.normalise("http://en.wiktionary.org"))
        assertNull(DictionaryUrl.normalise("ftp://en.wiktionary.org"))
    }

    @Test
    fun `refuses input that cannot be a site`() {
        assertNull(DictionaryUrl.normalise(""))
        assertNull(DictionaryUrl.normalise("   "))
        assertNull(DictionaryUrl.normalise("https://"))
        assertNull(DictionaryUrl.normalise("https://:8080"))
        // A bare word is a typo, not a host worth sending a word to.
        assertNull(DictionaryUrl.normalise("wiktionary"))
    }

    @Test
    fun `builds the api and entry urls from the site`() {
        assertEquals(
            "https://fr.wiktionary.org/api/rest_v1/page/definition/chat",
            DictionaryUrl.definitionApi("https://fr.wiktionary.org", "chat"),
        )
        assertEquals(
            "https://fr.wiktionary.org/wiki/chat",
            DictionaryUrl.entryPage("https://fr.wiktionary.org", "chat"),
        )
    }

    @Test
    fun `encodes terms with spaces and accents`() {
        assertEquals(
            "https://en.wiktionary.org/api/rest_v1/page/definition/ad%20hoc",
            DictionaryUrl.definitionApi(DictionaryUrl.DEFAULT_BASE_URL, "ad hoc"),
        )
        assertEquals(
            "https://en.wiktionary.org/wiki/na%C3%AFve",
            DictionaryUrl.entryPage(DictionaryUrl.DEFAULT_BASE_URL, "naïve"),
        )
    }

    @Test
    fun `falls back to the default when nothing is stored`() {
        assertEquals(DictionaryUrl.DEFAULT_BASE_URL, DictionaryUrl.orDefault(null))
        assertEquals(DictionaryUrl.DEFAULT_BASE_URL, DictionaryUrl.orDefault(""))
    }

    @Test
    fun `names the host for the consent explanation`() {
        assertEquals("fr.wiktionary.org", DictionaryUrl.hostOf("https://fr.wiktionary.org"))
        assertEquals("mirror.example", DictionaryUrl.hostOf("https://mirror.example/wiktionary"))
        assertEquals("en.wiktionary.org", DictionaryUrl.hostOf(""))
    }
}
