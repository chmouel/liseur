package com.chmouel.liseur.translate

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationLanguagesTest {

    @Test
    fun `a tag keeps the script and the Portuguese region, and drops other regions`() {
        assertEquals("en", TranslationLanguages.of("en-GB"))
        assertEquals("fr", TranslationLanguages.of("fr_CA"))
        assertEquals("zh-Hant", TranslationLanguages.of("zh-TW"))
        assertEquals("zh-Hant", TranslationLanguages.of("zh-Hant-HK"))
        assertEquals("zh-Hans", TranslationLanguages.of("zh"))
        assertEquals("zh-Hans", TranslationLanguages.of("zh-CN"))
        assertEquals("sr-Latn", TranslationLanguages.of("sr-Latn-RS"))
        assertEquals("pt-BR", TranslationLanguages.of("pt-BR"))
        assertEquals("pt-PT", TranslationLanguages.of("pt_PT"))
        assertEquals("pt", TranslationLanguages.of("pt"))
        assertNull(TranslationLanguages.of("und"))
        assertNull(TranslationLanguages.of(""))
        assertNull(TranslationLanguages.of(null))
    }

    @Test
    fun `a book's language is the passage's only when it declares exactly one`() {
        assertEquals("fr", TranslationLanguages.source(listOf("fr-FR")))
        assertNull(TranslationLanguages.source(emptyList()))
        assertNull(TranslationLanguages.source(listOf("und")))
        assertNull(TranslationLanguages.source(listOf("fr", "de")))
        assertEquals(listOf("fr", "de"), TranslationLanguages.declared(listOf("fr", "de")))
    }

    @Test
    fun `two scripts or two Portuguese regions leave the passage's language open`() {
        assertNull(TranslationLanguages.source(listOf("zh-Hans", "zh-Hant")))
        assertEquals(listOf("zh-Hans", "zh-Hant"), TranslationLanguages.declared(listOf("zh-Hans", "zh-Hant")))
        assertNull(TranslationLanguages.source(listOf("pt-BR", "pt-PT")))
        assertEquals("pt-BR", TranslationLanguages.source(listOf("pt", "pt-BR")))
        assertEquals("zh-Hant", TranslationLanguages.source(listOf("zh", "zh-TW")))
        assertEquals("en", TranslationLanguages.source(listOf("en-GB", "en-US")))
    }

    @Test
    fun `the target is the reader's pick, else the app's language`() {
        assertEquals("de", TranslationLanguages.target("de", Locale.FRANCE))
        assertEquals("fr", TranslationLanguages.target(null, Locale.FRANCE))
        assertEquals("zh-Hant", TranslationLanguages.target(null, Locale.TAIWAN))
        assertEquals("fr", TranslationLanguages.target("garbage-and-more-garbage-tags", Locale.FRANCE))
    }

    @Test
    fun `one language is the same whatever its region, but not across scripts`() {
        assertTrue(TranslationLanguages.same("en", "en"))
        assertTrue(TranslationLanguages.same("pt", "pt-BR"))
        assertFalse(TranslationLanguages.same("pt-BR", "pt-PT"))
        assertFalse(TranslationLanguages.same("zh-Hans", "zh-Hant"))
        assertFalse(TranslationLanguages.same("fr", "en"))
    }

    @Test
    fun `the targets leave out the passage's own language, and only it`() {
        val tags = listOf("en", "fr", "zh-Hans", "zh-Hant", "pt-BR", "pt-PT")
        assertEquals(listOf("fr", "zh-Hans", "zh-Hant", "pt-BR", "pt-PT"), TranslationLanguages.targets(tags, "en"))
        assertEquals(listOf("en", "fr", "zh-Hans", "pt-BR", "pt-PT"), TranslationLanguages.targets(tags, "zh-Hant"))
        assertEquals(listOf("en", "fr", "zh-Hans", "zh-Hant"), TranslationLanguages.targets(tags, "pt"))
        assertEquals(tags, TranslationLanguages.targets(tags, null))
    }

    @Test
    fun `names are in the reader's language and start with a capital`() {
        assertEquals("Français", TranslationLanguages.name("fr", Locale.FRENCH))
        assertEquals("French", TranslationLanguages.name("fr", Locale.ENGLISH))
        assertEquals("Portuguese (Brazil)", TranslationLanguages.englishName("pt-BR"))
    }

    @Test
    fun `pinned languages come first, the rest by name as the reader sorts them`() {
        val ordered = TranslationLanguages.ordered(listOf("fr", "de", "en", "es"), listOf("es", "it"), Locale.ENGLISH)
        assertEquals(listOf("es", "en", "fr", "de"), ordered)
    }

    @Test
    fun `every language lists both Chinese scripts and both Portuguese, without the bare one`() {
        val all = TranslationLanguages.all()
        assertTrue(all.containsAll(listOf("zh-Hans", "zh-Hant", "pt-BR", "pt-PT", "en", "fr")))
        assertFalse("pt" in all)
        assertEquals(all.size, all.distinct().size)
    }
}
