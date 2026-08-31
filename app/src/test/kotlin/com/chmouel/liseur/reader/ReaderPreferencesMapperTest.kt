package com.chmouel.liseur.reader

import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import com.chmouel.liseur.ui.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.navigator.epub.css.ColCount
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Columns are the one reading preference the window gets a veto on, so
 * the veto is checked here rather than by opening a book on a tablet.
 */
@OptIn(ExperimentalReadiumApi::class)
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
    fun `a font size change leaves publisher styles alone`() {
        // Readium CSS applies --USER__fontSize whatever the publisher
        // styles say. Turning them off just because the slider left its
        // default rewrites every element's size, and the page reflows far
        // beyond the change asked for.
        assertNull(ReaderPrefs().toEpubPreferences(theme).publisherStyles)
        assertNull(ReaderPrefs(fontSize = 1.4).toEpubPreferences(theme).publisherStyles)
    }

    @Test
    fun `advanced settings are what turn publisher styles off`() {
        assertEquals(
            false,
            ReaderPrefs(lineHeight = 1.6).toEpubPreferences(theme).publisherStyles,
        )
        assertEquals(
            false,
            ReaderPrefs(pageMargins = 1.5).toEpubPreferences(theme).publisherStyles,
        )
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

    @Test
    fun `the selection is painted in a colour the page can blend`() {
        // Readium CSS would otherwise paint an opaque #b4d8fe under both
        // dark themes' pale ink. The alpha is the whole fix: it is what
        // lets one value read on white, beige, dark grey and black, and
        // it is why the value cannot go through RsProperties' own
        // selectionBackgroundColor, which takes six hex digits at most.
        // Spelled rgba() and not eight-digit hex, which the WebView on
        // our oldest Android is two versions too old to parse.
        val overrides = readingRsProperties(ColumnMode.AUTO).overrides
        assertEquals("rgba(74, 144, 226, 0.4)", overrides["--RS__selectionBackgroundColor"])
        assertEquals("currentColor", overrides["--RS__selectionTextColor"])
    }

    @Test
    fun `the selection is painted the same way whatever the page is`() {
        // No column mode, and no theme either, has any business changing
        // it: RS properties are fixed when the navigator is built, so a
        // value that varied would be the one thing here that goes stale
        // the moment the reader switches theme mid-book.
        for (columns in ColumnMode.entries) {
            assertEquals(
                readingRsProperties(ColumnMode.AUTO).overrides,
                readingRsProperties(columns).overrides,
            )
        }
    }

    @Test
    fun `two columns still ask for a width they can fit in`() {
        // The column count alone is a ceiling: Readium's default 45em
        // column width means two of them only appear on a screen wide
        // enough for ninety.
        val auto = readingRsProperties(ColumnMode.AUTO)
        assertNull(auto.colCount)
        assertNull(auto.colWidth)

        val one = readingRsProperties(ColumnMode.ONE)
        assertEquals(ColCount.ONE, one.colCount)
        assertNull(one.colWidth)

        val two = readingRsProperties(ColumnMode.TWO)
        assertEquals(ColCount.TWO, two.colCount)
        assertNotNull(two.colWidth)
    }
}
