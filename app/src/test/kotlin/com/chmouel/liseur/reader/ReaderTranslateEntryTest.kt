package com.chmouel.liseur.reader

import com.chmouel.liseur.translate.PageTranslationModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderTranslateEntryTest {

    private val french = PageTranslationModes.Mode("en", "fr")

    private fun entry(
        available: Boolean = true,
        reflowable: Boolean = true,
        translated: Boolean = false,
        languages: PageTranslationModes.Mode? = french,
        eligible: Boolean = false,
        saved: Boolean = false,
    ) = ReaderTranslateEntry.of(available, reflowable, translated, languages, eligible, saved)

    @Test
    fun `ready languages translate into the target`() {
        assertEquals(ReaderTranslateEntry.Into(french, wholeBook = false), entry())
    }

    @Test
    fun `nothing set up still offers translating the book`() {
        assertEquals(ReaderTranslateEntry.SetUp(wholeBook = false), entry(languages = null))
    }

    @Test
    fun `long press needs an eligible service or a saved whole-book translation`() {
        assertEquals(ReaderTranslateEntry.Into(french, wholeBook = true), entry(eligible = true))
        assertEquals(ReaderTranslateEntry.SetUp(wholeBook = true), entry(languages = null, saved = true))
    }

    @Test
    fun `hidden without translation, for fixed layout, or on a translated page`() {
        assertNull(entry(available = false))
        assertNull(entry(reflowable = false))
        assertNull(entry(translated = true, eligible = true))
    }
}
