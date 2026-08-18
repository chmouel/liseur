package com.chmouel.liseur.reader

import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import com.chmouel.liseur.ui.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Theme

/**
 * Columns are the one reading preference the window gets a veto on, so
 * the veto is checked here rather than by opening a book on a tablet.
 */
class ReaderPreferencesMapperTest {

    /** The palette the reader arrived at; irrelevant to these cases. */
    private val theme = ReaderTheme.LIGHT

    @Test
    fun `a compact window refuses a column count it cannot carry`() {
        assertEquals(ColumnMode.AUTO, ColumnMode.TWO.effectiveFor(WidthClass.COMPACT))
        assertEquals(ColumnMode.AUTO, ColumnMode.ONE.effectiveFor(WidthClass.COMPACT))
        assertEquals(ColumnMode.AUTO, ColumnMode.AUTO.effectiveFor(WidthClass.COMPACT))
    }

    @Test
    fun `anything wider takes a stated choice at its word`() {
        for (width in listOf(WidthClass.MEDIUM, WidthClass.EXPANDED)) {
            assertEquals(ColumnMode.ONE, ColumnMode.ONE.effectiveFor(width))
            assertEquals(ColumnMode.TWO, ColumnMode.TWO.effectiveFor(width))
        }
    }

    @Test
    fun `Auto is two columns once there is room for them`() {
        for (width in listOf(WidthClass.MEDIUM, WidthClass.EXPANDED)) {
            assertEquals(ColumnMode.TWO, ColumnMode.AUTO.effectiveFor(width))
        }
    }

    @Test
    fun `Auto leaves the column count unset rather than sending AUTO`() {
        // An untouched preference has to produce the page Readium would
        // have laid out on its own, byte for byte.
        assertNull(ReaderPrefs().toEpubPreferences(theme).columnCount)
        assertNull(ReaderPrefs(columnMode = ColumnMode.AUTO).toEpubPreferences(theme).columnCount)
    }

    @Test
    fun `an explicit choice reaches Readium`() {
        assertEquals(
            ColumnCount.ONE,
            ReaderPrefs(columnMode = ColumnMode.ONE).toEpubPreferences(theme).columnCount,
        )
        assertEquals(
            ColumnCount.TWO,
            ReaderPrefs(columnMode = ColumnMode.TWO).toEpubPreferences(theme).columnCount,
        )
    }

    @Test
    fun `the override argument wins over the stored preference`() {
        val prefs = ReaderPrefs(columnMode = ColumnMode.TWO)
        assertNull(prefs.toEpubPreferences(theme, ColumnMode.AUTO).columnCount)
    }

    @Test
    fun `columns are the only thing this changes`() {
        val one = ReaderPrefs(columnMode = ColumnMode.ONE).toEpubPreferences(theme)
        val two = ReaderPrefs(columnMode = ColumnMode.TWO).toEpubPreferences(theme)
        assertEquals(one.copy(columnCount = null), two.copy(columnCount = null))
    }

    @Test
    fun `a book is paginated unless it is asked to scroll`() {
        assertEquals(false, ReaderPrefs().toEpubPreferences(theme).scroll)
        assertEquals(
            true,
            ReaderPrefs().toEpubPreferences(theme, scroll = true).scroll,
        )
    }

    @Test
    fun `scrolling is the only thing the scroll flag changes`() {
        val paginated = ReaderPrefs().toEpubPreferences(theme, scroll = false)
        val scrolled = ReaderPrefs().toEpubPreferences(theme, scroll = true)
        assertEquals(paginated.copy(scroll = null), scrolled.copy(scroll = null))
    }

    @Test
    fun `the theme passed in is the one that reaches Readium`() {
        // The prefs carry a choice, not a palette, and "follow the app"
        // has no colours of its own — so the resolved theme has to win
        // outright rather than be a hint the mapper can second-guess.
        val prefs = ReaderPrefs(themeChoice = ReaderThemeChoice.LIGHT)
        assertEquals(Theme.DARK, prefs.toEpubPreferences(ReaderTheme.DARK).theme)
        assertEquals(Theme.SEPIA, prefs.toEpubPreferences(ReaderTheme.SEPIA).theme)
    }

    @Test
    fun `Black is a dark page of its own colour`() {
        // Readium has no true-black theme, so both of ours arrive as
        // DARK; what keeps them apart is the background we send with it.
        val dark = ReaderPrefs().toEpubPreferences(ReaderTheme.DARK)
        val black = ReaderPrefs().toEpubPreferences(ReaderTheme.BLACK)
        assertEquals(Theme.DARK, dark.theme)
        assertEquals(Theme.DARK, black.theme)
        assertNotEquals(dark.backgroundColor, black.backgroundColor)
    }

    @Test
    fun `images are left exactly as the book drew them`() {
        // Readium can dim or invert them on a dark page. We deliberately
        // ask for neither: brightness(80%) leaves a white diagram nearly
        // as bright while muddying every photograph beside it, and the
        // per-image version that would be worth having needs JavaScript
        // in the WebView. A reader who wants this has no setting for it
        // on purpose, so this stays null rather than drifting.
        for (palette in ReaderTheme.entries) {
            assertNull(ReaderPrefs().toEpubPreferences(palette).imageFilter)
        }
    }
}
