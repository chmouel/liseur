package com.chmouel.liseur.reader.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionWebSearchTest {
    private fun reference(action: SelectionAction) = SelectionActionEntry(action, SelectionActionStyle.BOTH)

    @Test fun `reference searches encode the full selection as one query value`() {
        val text = "  René & 中文 + /?=#%\nsecond line  "
        val query = "Ren%C3%A9%20%26%20%E4%B8%AD%E6%96%87%20%2B%20%2F%3F%3D%23%25%0Asecond%20line"
        assertEquals("https://fr.wikipedia.org/w/index.php?search=$query", selectionWebUrl(reference(SelectionAction.WIKIPEDIA), text, "fr-FR"))
        assertEquals("https://openlibrary.org/search?q=$query", selectionWebUrl(reference(SelectionAction.OPEN_LIBRARY), text, "fr"))
        assertNull(selectionWebUrl(reference(SelectionAction.WIKIPEDIA), "  ", "en"))
        assertNull(selectionWebUrl(reference(SelectionAction.COPY), "text", "en"))
    }

    @Test fun `Wikipedia uses canonical language editions and falls back for unknown languages`() {
        mapOf("fra_FR" to "fr", "zh-Hant-TW" to "zh", "nb-NO" to "no", "nn" to "nn",
            "yue" to "zh-yue", "be-tarask" to "be-tarask", "deu" to "de", "iw" to "he",
            "qaa" to "en", "und" to "en", "https://evil.example" to "en", "" to "en")
            .forEach { (tag, expected) -> assertEquals(tag, expected, wikipediaEdition(tag)) }
        assertEquals("en", wikipediaEdition(null))
    }

    @Test fun `language comes from the displayed translation rather than book or changed settings`() {
        assertEquals("fr", selectionSearchLanguage(listOf("en"), true, "fr"))
        assertEquals("en-GB", selectionSearchLanguage(listOf("en-GB"), false, "fr"))
        assertNull(selectionSearchLanguage(listOf("en", "fr"), false, "de"))
        assertNull(selectionSearchLanguage(emptyList(), false, "de"))
        assertNull(selectionSearchLanguage(listOf("en"), true, null))
    }

    @Test fun `custom templates accept path query fragment and repeated placeholders`() {
        val template = "https://example.org/find/{text}?q={text}#result={text}"
        val entry = SelectionActionEntry(SelectionActionTarget.Website("custom_test", template), SelectionActionStyle.BOTH, "Mine")
        assertEquals("https://example.org/find/a%2Fb%20%26%20c?q=a%2Fb%20%26%20c#result=a%2Fb%20%26%20c",
            selectionWebUrl(entry, "a/b & c", null))
        assertEquals(template, selectionWebsiteTemplate("  $template  "))
    }

    @Test fun `invalid templates cannot change the scheme host or include credentials`() {
        listOf("", "https://example.org/", "http://example.org/?q={text}", "javascript:{text}",
            "intent://example.org/{text}", "file:///{text}", "https://{text}.example.org/",
            "https://example.org{text}/", "https://user:pass@example.org/?q={text}",
            "https://example.org:99999/?q={text}", "https://example.org/{unknown}?q={text}",
            "https://example.org/\n?q={text}", "https://example.org/space here/{text}")
            .forEach { assertNull(it, selectionWebsiteTemplate(it)) }
    }
}
