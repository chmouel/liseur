package com.chmouel.liseur.data.settings.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Names arriving from outside the app.
 *
 * Both of the names an imported font can be shown under come from
 * somewhere Liseur does not control — the file's own `name` table, and
 * whatever a DocumentsProvider decided to call it. The same sanitiser
 * has to cover both, which is why there is only one.
 */
class FontNamesTest {

    @Test
    fun `an ordinary name is left alone`() {
        assertEquals("Literata", FontNames.sanitize("Literata"))
    }

    @Test
    fun `trims and collapses whitespace`() {
        assertEquals("Fixture Text", FontNames.sanitize("  Fixture   Text  "))
    }

    @Test
    fun `strips a right-to-left override`() {
        // Left in, this renders the settings row backwards, and keeps
        // rendering the rows after it backwards too.
        assertEquals("Literata gnitteS", FontNames.sanitize("Literata \u202Egnitte\u202CS"))
    }

    @Test
    fun `strips every bidi control`() {
        val hostile = "A\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069B"
        assertEquals("AB", FontNames.sanitize(hostile))
    }

    @Test
    fun `turns a newline into a space rather than a second row`() {
        assertEquals("One Two", FontNames.sanitize("One\nTwo"))
    }

    @Test
    fun `drops the NUL padding of a fixed-length record`() {
        assertEquals("Fixture", FontNames.sanitize("Fixture\u0000\u0000\u0000"))
    }

    @Test
    fun `caps a very long name`() {
        val long = "N".repeat(200)
        assertEquals(64, FontNames.sanitize(long)!!.length)
    }

    @Test
    fun `does not leave a trailing space where the cap fell`() {
        val name = "W".repeat(63) + " tail"
        assertEquals("W".repeat(63), FontNames.sanitize(name))
    }

    @Test
    fun `a name with nothing in it is no name at all`() {
        assertNull(FontNames.sanitize(""))
        assertNull(FontNames.sanitize("   "))
        assertNull(FontNames.sanitize("\u202E\u2069"))
    }

    @Test
    fun `keeps non-Latin scripts, which are the point of the feature`() {
        assertEquals("思源宋體", FontNames.sanitize("思源宋體"))
        assertEquals("Шрифт", FontNames.sanitize("Шрифт"))
    }

    @Test
    fun `a font that names itself nothing falls back to its digest`() {
        assertEquals("Imported font 1a2b3c4d", FontNames.fallbackName("1a2b3c4d" + "0".repeat(56)))
    }
}
