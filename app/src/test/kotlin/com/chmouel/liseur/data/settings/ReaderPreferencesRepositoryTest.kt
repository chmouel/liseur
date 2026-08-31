package com.chmouel.liseur.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The reading preferences, written down and read back.
 *
 * Against a real DataStore on a temporary file rather than a fake, so
 * the key names are part of what is asserted: six new settings that all
 * hold a double or a string are exactly the shape a copy-paste slip
 * hides in, and a test with a map behind it would agree with the slip.
 *
 * The second half is the store as it may actually be found — a file on a
 * device that another build, or a hand with `run-as`, has written to.
 */
class ReaderPreferencesRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() =
        PreferenceDataStoreFactory.create { folder.newFile("reader.preferences_pb") }

    @Test
    fun `nothing set is the page the book asked for`() = runTest {
        val prefs = ReaderPreferencesRepository(store()).prefs.first()
        assertEquals(ReaderTextAlign.DEFAULT, prefs.textAlign)
        assertEquals(ReaderFontWeight.DEFAULT, prefs.fontWeight)
        assertNull(prefs.hyphens)
        assertNull(prefs.letterSpacing)
        assertNull(prefs.wordSpacing)
        assertNull(prefs.paragraphSpacing)
        assertFalse(prefs.requiresAdvancedStyles(ReadingCss.Default))
    }

    @Test
    fun `each setting survives being written down`() = runTest {
        val repo = ReaderPreferencesRepository(store())
        repo.setTextAlign(ReaderTextAlign.JUSTIFIED)
        repo.setFontWeight(ReaderFontWeight.LIGHT)
        repo.setHyphens(false)
        repo.setLetterSpacing(0.05)
        repo.setWordSpacing(0.1)
        repo.setParagraphSpacing(0.4)

        val prefs = repo.prefs.first()
        assertEquals(ReaderTextAlign.JUSTIFIED, prefs.textAlign)
        assertEquals(ReaderFontWeight.LIGHT, prefs.fontWeight)
        assertEquals(false, prefs.hyphens)
        assertEquals(5, TypographyRange.LETTER_SPACING.tickOf(prefs.letterSpacing))
        assertEquals(5, TypographyRange.WORD_SPACING.tickOf(prefs.wordSpacing))
        assertEquals(4, TypographyRange.PARAGRAPH_SPACING.tickOf(prefs.paragraphSpacing))
    }

    @Test
    fun `no two settings share a key`() = runTest {
        // Six new keys of two types, written one at a time and read back
        // raw: a swapped pair passes every test that goes through the
        // repository alone.
        val data = store()
        val repo = ReaderPreferencesRepository(data)
        repo.setTextAlign(ReaderTextAlign.RAGGED)
        repo.setFontWeight(ReaderFontWeight.BOLD)
        repo.setHyphens(true)
        repo.setLetterSpacing(0.05)
        repo.setWordSpacing(0.1)
        repo.setParagraphSpacing(0.4)

        val raw = data.data.first()
        assertEquals("ragged", raw[stringPreferencesKey("text_align")])
        assertEquals("bold", raw[stringPreferencesKey("font_weight")])
        assertEquals(true, raw[booleanPreferencesKey("hyphens")])
        assertEquals(0.05, raw[doublePreferencesKey("letter_spacing")]!!, 1e-9)
        assertEquals(0.1, raw[doublePreferencesKey("word_spacing")]!!, 1e-9)
        assertEquals(0.4, raw[doublePreferencesKey("paragraph_spacing")]!!, 1e-9)
    }

    @Test
    fun `going back to the default takes the key away with it`() = runTest {
        // Rather than leaving a sentinel number behind that everything
        // reading this store would then have to tell apart from a real
        // one.
        val data = store()
        val repo = ReaderPreferencesRepository(data)
        repo.setLetterSpacing(0.05)
        repo.setHyphens(true)
        repo.setLetterSpacing(null)
        repo.setHyphens(null)

        val raw = data.data.first()
        assertFalse(raw.contains(doublePreferencesKey("letter_spacing")))
        assertFalse(raw.contains(booleanPreferencesKey("hyphens")))
        assertNull(repo.prefs.first().letterSpacing)
        assertNull(repo.prefs.first().hyphens)
    }

    @Test
    fun `an explicit zero is a setting, and not the same as no setting`() = runTest {
        val data = store()
        val repo = ReaderPreferencesRepository(data)
        repo.setWordSpacing(0.0)

        assertEquals(0.0, data.data.first()[doublePreferencesKey("word_spacing")]!!, 1e-9)
        val prefs = repo.prefs.first()
        assertEquals(0.0, prefs.wordSpacing!!, 1e-9)
        assertTrue(prefs.requiresAdvancedStyles(ReadingCss.Default))

        repo.setWordSpacing(null)
        assertFalse(data.data.first().contains(doublePreferencesKey("word_spacing")))
    }

    @Test
    fun `an enum back at its default is written down, not removed`() = runTest {
        // These have no absent state to return to: "default" is one of
        // the values, and fromId falls back to it either way.
        val data = store()
        val repo = ReaderPreferencesRepository(data)
        repo.setTextAlign(ReaderTextAlign.JUSTIFIED)
        repo.setTextAlign(ReaderTextAlign.DEFAULT)
        repo.setFontWeight(ReaderFontWeight.DEFAULT)

        val raw = data.data.first()
        assertEquals("default", raw[stringPreferencesKey("text_align")])
        assertEquals("default", raw[stringPreferencesKey("font_weight")])
        assertEquals(ReaderTextAlign.DEFAULT, repo.prefs.first().textAlign)
    }

    @Test
    fun `a spacing is snapped to a notch on the way in`() = runTest {
        // Snapping in the slider alone would let anything that is not
        // the slider persist a value between notches, which the control
        // would then round on the reader's behalf the next time they
        // dragged it.
        val data = store()
        ReaderPreferencesRepository(data).setParagraphSpacing(0.4237)
        assertEquals(4, TypographyRange.PARAGRAPH_SPACING.tickOf(
            data.data.first()[doublePreferencesKey("paragraph_spacing")],
        ))
    }

    @Test
    fun `a setter cannot store a number Readium would refuse`() = runTest {
        val data = store()
        val repo = ReaderPreferencesRepository(data)
        repo.setLetterSpacing(-1.0)
        repo.setFontSize(Double.NaN)
        repo.setLineHeight(Double.NaN)
        repo.setPageMargins(99.0)

        val raw = data.data.first()
        assertFalse(raw.contains(doublePreferencesKey("letter_spacing")))
        assertFalse(raw.contains(doublePreferencesKey("line_height")))
        assertFalse(raw.contains(doublePreferencesKey("page_margins")))
        assertEquals(1.0, raw[doublePreferencesKey("font_size")]!!, 1e-9)
    }

    @Test
    fun `a store written by something else still reads back usable`() = runTest {
        // The store is a file on a device: `adb shell run-as`, a restored
        // backup, an older or newer build. EpubPreferences throws from
        // its constructor on a negative size, spacing or margin, so an
        // unguarded value here is a crash while the reader is changing
        // their settings.
        val data = store()
        data.edit {
            it[doublePreferencesKey("font_size")] = Double.NaN
            it[doublePreferencesKey("line_height")] = -3.0
            it[doublePreferencesKey("page_margins")] = Double.POSITIVE_INFINITY
            it[doublePreferencesKey("letter_spacing")] = -0.5
            it[doublePreferencesKey("word_spacing")] = Double.NaN
            it[doublePreferencesKey("paragraph_spacing")] = 42.0
        }

        val prefs = ReaderPreferencesRepository(data).prefs.first()
        assertEquals(1.0, prefs.fontSize, 1e-9)
        assertNull(prefs.lineHeight)
        assertNull(prefs.pageMargins)
        assertNull(prefs.letterSpacing)
        assertNull(prefs.wordSpacing)
        assertEquals(TypographyRange.PARAGRAPH_SPACING.max, prefs.paragraphSpacing!!, 1e-9)
    }

    @Test
    fun `an unreadable stored value does not renormalize the book`() = runTest {
        // Discarded, so it cannot count as a setting the reader made —
        // which would switch advanced styles on and flatten the book's
        // type scale on the strength of a damaged byte.
        val data = store()
        data.edit {
            it[doublePreferencesKey("line_height")] = Double.NaN
            it[doublePreferencesKey("letter_spacing")] = -1.0
        }
        assertFalse(
            ReaderPreferencesRepository(data).prefs.first()
                .requiresAdvancedStyles(ReadingCss.Default),
        )
    }
}
