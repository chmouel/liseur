package com.chmouel.liseur.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechLanguageTest {
    @Test
    fun `three-letter and bibliographic codes become two letters`() {
        assertEquals("en", SpeechLanguage.normalize("eng"))
        assertEquals("fr", SpeechLanguage.normalize("fra"))
        assertEquals("fr", SpeechLanguage.normalize("fre"))
        assertEquals("de", SpeechLanguage.normalize("ger"))
        assertEquals("zh", SpeechLanguage.normalize("cmn"))
        assertEquals("he", SpeechLanguage.normalize("iw"))
    }

    @Test
    fun `regions and scripts take their canonical case`() {
        assertEquals("en-US", SpeechLanguage.normalize("en_us"))
        assertEquals("fr-FR", SpeechLanguage.normalize("fre-FR"))
        assertEquals("en-GB", SpeechLanguage.normalize("eng-GBR"))
        assertEquals("zh-Hans-CN", SpeechLanguage.normalize("zh-hans-cn"))
        assertEquals("es-419", SpeechLanguage.normalize("es-419"))
        assertEquals("GB", SpeechLanguage.region("en-GB"))
        assertNull(SpeechLanguage.region("zh-Hans"))
        assertEquals("en", SpeechLanguage.primary("en-GB"))
    }

    @Test
    fun `no language is not a language`() {
        assertNull(SpeechLanguage.normalize(null))
        assertNull(SpeechLanguage.normalize(""))
        assertNull(SpeechLanguage.normalize("und"))
        assertNull(SpeechLanguage.normalize("mul"))
        assertNull(SpeechLanguage.normalize("english"))
        assertNull(SpeechLanguage.normalize("e1"))
    }

    @Test
    fun `a book is in one language when every tag it declares names it`() {
        assertEquals(BookLanguage.Known("en-GB"), SpeechLanguage.ofBook(listOf("en", "en-GB", "eng")))
        assertEquals(BookLanguage.Known("fr"), SpeechLanguage.ofBook(listOf("fre", "und")))
        assertEquals(BookLanguage.Missing, SpeechLanguage.ofBook(emptyList()))
        assertEquals(BookLanguage.Missing, SpeechLanguage.ofBook(listOf("und")))
        assertEquals(BookLanguage.Ambiguous(listOf("en", "fr-CA")), SpeechLanguage.ofBook(listOf("eng", "fr-CA")))
    }
}
