package com.chmouel.liseur.data.settings.fonts

import com.chmouel.liseur.data.settings.fonts.SfntFixtures.NAME_FAMILY
import com.chmouel.liseur.data.settings.fonts.SfntFixtures.NAME_TYPOGRAPHIC_FAMILY
import com.chmouel.liseur.data.settings.fonts.SfntFixtures.OS2_BOLD
import com.chmouel.liseur.data.settings.fonts.SfntFixtures.OS2_ITALIC
import com.chmouel.liseur.data.settings.fonts.SfntFixtures.OTTO
import com.chmouel.liseur.data.settings.fonts.SfntFixtures.TRUE_TAG
import com.chmouel.liseur.data.settings.fonts.SfntFixtures.TRUE_TYPE
import com.chmouel.liseur.data.settings.fonts.SfntFixtures.name
import com.chmouel.liseur.data.settings.fonts.SfntFixtures.putU16
import com.chmouel.liseur.data.settings.fonts.SfntFixtures.putU32
import com.chmouel.liseur.data.settings.fonts.SfntFixtures.sfnt
import com.chmouel.liseur.data.settings.fonts.SfntFixtures.tableOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The font parser, fed the kind of bytes a file picker can produce.
 *
 * Every fixture is built out of bytes by [SfntFixtures] rather than
 * checked in as a binary, so a reader of these tests can see exactly
 * which byte makes the assertion true. The hostile cases matter more
 * than the happy ones: the input is
 * a file somebody chose out of a file manager, and the caller is a
 * callback with nowhere to put an exception.
 */
class SfntFontTest {

    @Test
    fun `reads the typographic family name in preference to the compatibility one`() {
        val font = sfnt(
            names = listOf(
                name(NAME_FAMILY, "Fixture Text Bold"),
                name(NAME_TYPOGRAPHIC_FAMILY, "Fixture Text"),
            ),
        )
        assertEquals("Fixture Text", SfntFont.parse(font)!!.familyName)
    }

    @Test
    fun `prefers the English Windows record over a Mac one`() {
        val font = sfnt(
            names = listOf(
                name(NAME_FAMILY, "Wrong", platform = 1, encoding = 0, language = 0, mac = true),
                name(NAME_FAMILY, "Right"),
            ),
        )
        assertEquals("Right", SfntFont.parse(font)!!.familyName)
    }

    @Test
    fun `reads a Mac record when it is all there is`() {
        val font = sfnt(
            names = listOf(
                name(NAME_FAMILY, "Mac Only", platform = 1, encoding = 0, language = 0, mac = true),
            ),
        )
        assertEquals("Mac Only", SfntFont.parse(font)!!.familyName)
    }

    @Test
    fun `reads a UCS-4 Windows record`() {
        val font = sfnt(names = listOf(name(NAME_FAMILY, "Wide", encoding = 10, ucs4 = true)))
        assertEquals("Wide", SfntFont.parse(font)!!.familyName)
    }

    @Test
    fun `a font with no name table is still a font`() {
        val parsed = SfntFont.parse(sfnt(names = emptyList()))!!
        assertNull(parsed.familyName)
        assertEquals(SfntFormat.TRUE_TYPE, parsed.format)
    }

    @Test
    fun `the extension comes from the magic and never from a claim`() {
        assertEquals("otf", SfntFont.parse(sfnt(magic = OTTO))!!.format.extension)
        assertEquals("ttf", SfntFont.parse(sfnt(magic = TRUE_TYPE))!!.format.extension)
        assertEquals("ttf", SfntFont.parse(sfnt(magic = TRUE_TAG))!!.format.extension)
    }

    @Test
    fun `reads the weight class`() {
        assertEquals(300, SfntFont.parse(sfnt(weightClass = 300))!!.weight)
    }

    @Test
    fun `falls back to bold when only the flag says so`() {
        val font = sfnt(weightClass = 0, fsSelection = OS2_BOLD)
        assertEquals(700, SfntFont.parse(font)!!.weight)
    }

    @Test
    fun `falls back to regular when nothing says otherwise`() {
        assertEquals(400, SfntFont.parse(sfnt(weightClass = 0))!!.weight)
    }

    @Test
    fun `reads italic from fsSelection`() {
        assertEquals(true, SfntFont.parse(sfnt(fsSelection = OS2_ITALIC))!!.italic)
        assertEquals(false, SfntFont.parse(sfnt(fsSelection = 0))!!.italic)
    }

    @Test
    fun `reads italic from macStyle when there is no OS2 table`() {
        val font = sfnt(macStyle = 0b10, withOs2 = false)
        assertEquals(true, SfntFont.parse(font)!!.italic)
    }

    @Test
    fun `reads a variable weight axis`() {
        assertEquals(200..900, SfntFont.parse(sfnt(weightAxis = 200 to 900))!!.weightRange)
    }

    @Test
    fun `clamps a weight axis rather than letting Readium assert on it`() {
        // setFontWeight(range) requires 1..1000. An unclamped range from a
        // malformed fvar would throw while the navigator was being built,
        // which costs the reader the book, not the font.
        assertEquals(1..1000, SfntFont.parse(sfnt(weightAxis = 0 to 4000))!!.weightRange)
    }

    @Test
    fun `an axis entirely above the allowed range collapses to its edge`() {
        assertEquals(1000..1000, SfntFont.parse(sfnt(weightAxis = 5000 to 6000))!!.weightRange)
    }

    @Test
    fun `discards a weight axis whose maximum is below its minimum`() {
        assertNull(SfntFont.parse(sfnt(weightAxis = 900 to 200))!!.weightRange)
    }

    @Test
    fun `ignores a non-weight axis`() {
        assertNull(SfntFont.parse(sfnt(weightAxis = 200 to 900, axisTag = "wdth"))!!.weightRange)
    }

    @Test
    fun `refuses a font collection`() {
        assertNull(SfntFont.parse(sfnt(magic = 0x74746366)))
    }

    @Test
    fun `refuses WOFF and WOFF2`() {
        assertNull(SfntFont.parse(sfnt(magic = 0x774F4646)))
        assertNull(SfntFont.parse(sfnt(magic = 0x774F4632)))
    }

    @Test
    fun `refuses an empty file`() {
        assertNull(SfntFont.parse(ByteArray(0)))
    }

    @Test
    fun `refuses a truncated header`() {
        assertNull(SfntFont.parse(sfnt().copyOf(6)))
    }

    @Test
    fun `refuses a file that is only a magic number`() {
        assertNull(SfntFont.parse(byteArrayOf(0, 1, 0, 0)))
    }

    @Test
    fun `survives a table count that could not possibly be true`() {
        val font = sfnt()
        font.putU16(4, 60000)
        assertNull(SfntFont.parse(font))
    }

    @Test
    fun `survives a table offset past the end of the file`() {
        val font = sfnt()
        // The first record's offset, pointed somewhere that is not there.
        font.putU32(12 + 8, 0x7FFF_FFF0)
        // Not a crash and not a lie: the tables it can read, it reads.
        SfntFont.parse(font)
    }

    @Test
    fun `survives an offset and length that would overflow if added`() {
        val font = sfnt()
        font.putU32(12 + 8, Int.MAX_VALUE - 4)
        font.putU32(12 + 12, Int.MAX_VALUE - 4)
        SfntFont.parse(font)
    }

    @Test
    fun `survives a name record pointing outside its own table`() {
        val font = sfnt(names = listOf(name(NAME_FAMILY, "Fixture")))
        val nameTable = font.tableOffset("name")
        // The string offset, moved well past the storage area.
        font.putU16(nameTable + 6 + 10, 60000)
        assertNull(SfntFont.parse(font)!!.familyName)
    }

    @Test
    fun `survives random bytes`() {
        val noise = ByteArray(4096) { (it * 31 % 251).toByte() }
        SfntFont.parse(noise)
    }

    @Test
    fun `strips a bidi override out of a family name`() {
        val font = sfnt(names = listOf(name(NAME_FAMILY, "Fix\u202Eture")))
        assertEquals("Fixture", SfntFont.parse(font)!!.familyName)
    }
}
