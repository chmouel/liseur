package com.chmouel.liseur.reader

import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.ui.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.navigator.preferences.ColumnCount

/**
 * Columns are the one reading preference the window gets a veto on, so
 * the veto is checked here rather than by opening a book on a tablet.
 */
class ReaderPreferencesMapperTest {

    @Test
    fun `a compact window refuses a column count it cannot carry`() {
        assertEquals(ColumnMode.AUTO, ColumnMode.TWO.effectiveFor(WidthClass.COMPACT))
        assertEquals(ColumnMode.AUTO, ColumnMode.ONE.effectiveFor(WidthClass.COMPACT))
        assertEquals(ColumnMode.AUTO, ColumnMode.AUTO.effectiveFor(WidthClass.COMPACT))
    }

    @Test
    fun `anything wider takes the reader at their word`() {
        for (width in listOf(WidthClass.MEDIUM, WidthClass.EXPANDED)) {
            for (mode in ColumnMode.entries) {
                assertEquals(mode, mode.effectiveFor(width))
            }
        }
    }

    @Test
    fun `Auto leaves the column count unset rather than sending AUTO`() {
        // An untouched preference has to produce the page Readium would
        // have laid out on its own, byte for byte.
        assertNull(ReaderPrefs().toEpubPreferences().columnCount)
        assertNull(ReaderPrefs(columnMode = ColumnMode.AUTO).toEpubPreferences().columnCount)
    }

    @Test
    fun `an explicit choice reaches Readium`() {
        assertEquals(
            ColumnCount.ONE,
            ReaderPrefs(columnMode = ColumnMode.ONE).toEpubPreferences().columnCount,
        )
        assertEquals(
            ColumnCount.TWO,
            ReaderPrefs(columnMode = ColumnMode.TWO).toEpubPreferences().columnCount,
        )
    }

    @Test
    fun `the override argument wins over the stored preference`() {
        val prefs = ReaderPrefs(columnMode = ColumnMode.TWO)
        assertNull(prefs.toEpubPreferences(ColumnMode.AUTO).columnCount)
    }

    @Test
    fun `columns are the only thing this changes`() {
        val one = ReaderPrefs(columnMode = ColumnMode.ONE).toEpubPreferences()
        val two = ReaderPrefs(columnMode = ColumnMode.TWO).toEpubPreferences()
        assertEquals(one.copy(columnCount = null), two.copy(columnCount = null))
    }
}
