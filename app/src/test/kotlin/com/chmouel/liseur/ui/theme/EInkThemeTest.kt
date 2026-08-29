package com.chmouel.liseur.ui.theme

import com.chmouel.liseur.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EInkThemeTest {

    @Test
    fun `e-ink starts monochrome`() {
        assertFalse(AppSettings().colorEInk)
        assertEquals(EInkPalette.MONOCHROME, eInkPalette(eInk = true, colorEInk = false))
    }

    @Test
    fun `a color panel keeps the static color palette`() {
        assertEquals(EInkPalette.COLOR, eInkPalette(eInk = true, colorEInk = true))
    }

    @Test
    fun `the color preference is ignored while e-ink is off`() {
        assertEquals(EInkPalette.NONE, eInkPalette(eInk = false, colorEInk = false))
        assertEquals(EInkPalette.NONE, eInkPalette(eInk = false, colorEInk = true))
    }
}
