package com.chmouel.liseur.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslatedPagesTest {
    @Test
    fun `selected WebView text is decoded from its JSON result`() {
        assertEquals("mot cible", TranslatedPages.decodeSelectionText("\"mot cible\""))
    }

    @Test
    fun `empty or invalid WebView text is unavailable`() {
        assertNull(TranslatedPages.decodeSelectionText(null))
        assertNull(TranslatedPages.decodeSelectionText("null"))
        assertNull(TranslatedPages.decodeSelectionText("\"  \""))
        assertNull(TranslatedPages.decodeSelectionText("\""))
    }
}
