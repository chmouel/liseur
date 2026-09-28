package com.chmouel.liseur.reader

import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class PageColorSchemeTest {
    @Test
    fun `light and sepia pages report a light colour scheme`() {
        assertEquals(R.style.PageColorScheme_Light, pageColorSchemeStyle(ReaderTheme.LIGHT))
        assertEquals(R.style.PageColorScheme_Light, pageColorSchemeStyle(ReaderTheme.SEPIA))
    }

    @Test
    fun `dark and black pages report a dark colour scheme`() {
        assertEquals(R.style.PageColorScheme_Dark, pageColorSchemeStyle(ReaderTheme.DARK))
        assertEquals(R.style.PageColorScheme_Dark, pageColorSchemeStyle(ReaderTheme.BLACK))
    }
}
