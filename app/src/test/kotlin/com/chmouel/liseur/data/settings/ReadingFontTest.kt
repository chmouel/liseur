package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Which font a stored id means, and what happens when its file is gone.
 *
 * The raw/effective split lives here, and it is the reason deleting a
 * font is a font going missing rather than a setting being destroyed.
 */
class ReadingFontTest {

    private val digest = "0123456789abcdef".repeat(4)
    private val imported = ReadingFont.Imported(digest)

    @Test
    fun `every bundled id round-trips`() {
        ReaderFont.entries.forEach { font ->
            assertEquals(ReadingFont.Bundled(font), ReadingFont.fromId(font.id))
        }
    }

    @Test
    fun `an imported id round-trips`() {
        assertEquals(imported, ReadingFont.fromId("user:$digest"))
        assertEquals("user:$digest", imported.id)
    }

    @Test
    fun `nothing at all is the default`() {
        assertEquals(ReadingFont.Default, ReadingFont.fromId(null))
        assertEquals(ReadingFont.Default, ReadingFont.fromId(""))
        assertEquals(ReadingFont.Default, ReadingFont.fromId("   "))
    }

    @Test
    fun `an id from a version that does not exist yet is the default`() {
        // Never guessed to be an import. A bundled font added later must
        // not be readable as one, or it would resolve to a file that can
        // never be there.
        assertEquals(ReadingFont.Default, ReadingFont.fromId("some-future-font"))
        assertEquals(ReadingFont.Default, ReadingFont.fromId("user"))
        assertEquals(ReadingFont.Default, ReadingFont.fromId("user:"))
    }

    @Test
    fun `a user id whose digest is not a digest is the default`() {
        assertEquals(ReadingFont.Default, ReadingFont.fromId("user:short"))
        assertEquals(ReadingFont.Default, ReadingFont.fromId("user:" + "z".repeat(64)))
        assertEquals(ReadingFont.Default, ReadingFont.fromId("user:" + "A".repeat(64)))
        assertEquals(ReadingFont.Default, ReadingFont.fromId("user:" + "0".repeat(63)))
        assertEquals(ReadingFont.Default, ReadingFont.fromId("user:" + "0".repeat(65)))
        assertEquals(ReadingFont.Default, ReadingFont.fromId("user:../../etc/passwd"))
    }

    @Test
    fun `an imported family is namespaced so it cannot take over a bundled one`() {
        assertNotEquals(ReaderFont.LITERATA.cssName, imported.cssName)
        assertEquals("LiseurUser-$digest", imported.cssName)
    }

    @Test
    fun `the publisher font declares no family at all`() {
        assertEquals(null, ReadingFont.Bundled(ReaderFont.PUBLISHER).cssName)
    }

    @Test
    fun `an import that is still installed resolves to itself`() {
        assertEquals(imported, imported.effective(setOf("user:$digest")))
    }

    @Test
    fun `an import whose file has gone resolves to the default`() {
        assertEquals(ReadingFont.Default, imported.effective(emptySet()))
        assertEquals(ReadingFont.Default, imported.effective(setOf("user:" + "f".repeat(64))))
    }

    @Test
    fun `resolving never alters the value that gets stored`() {
        // effective() answers a question; it does not change the answer to
        // "what did the reader choose". Re-import the same bytes and the
        // choice is simply live again.
        val registry = emptySet<String>()
        assertEquals(ReadingFont.Default, imported.effective(registry))
        assertEquals("user:$digest", imported.id)
        assertEquals(imported, imported.effective(setOf("user:$digest")))
    }

    @Test
    fun `a bundled font is never resolved away`() {
        val bundled = ReadingFont.Bundled(ReaderFont.VOLLKORN)
        assertEquals(bundled, bundled.effective(emptySet()))
    }
}
