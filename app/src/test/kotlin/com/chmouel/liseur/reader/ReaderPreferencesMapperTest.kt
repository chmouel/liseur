package com.chmouel.liseur.reader

import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.data.settings.ReaderFontWeight
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTextAlign
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import com.chmouel.liseur.data.settings.ReadingCss
import com.chmouel.liseur.data.settings.justificationHyphenates
import com.chmouel.liseur.ui.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.epub.css.ColCount
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.TextAlign
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

    /**
     * One setting each, of the six that need Readium's advanced styles
     * in a stylesheet that carries their rule.
     */
    private val advancedSingletons = listOf(
        ReaderPrefs(lineHeight = 1.6),
        ReaderPrefs(paragraphSpacing = 0.5),
        ReaderPrefs(textAlign = ReaderTextAlign.JUSTIFIED),
        ReaderPrefs(hyphens = true),
        ReaderPrefs(letterSpacing = 0.1),
        ReaderPrefs(wordSpacing = 0.1),
    )

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

    /** What a book's stylesheet says about a set of preferences. */
    private fun ReaderPrefs.advancedIn(css: ReadingCss) =
        toEpubPreferences(theme, css = css).publisherStyles

    @Test
    fun `settings that Readium applies regardless leave publisher styles alone`() {
        // Readium CSS applies --USER__fontSize and --USER__pageMargins
        // whatever the publisher styles say, and font weight rides the
        // user-properties overrides map with no rule of its own.
        // EpubPreferencesEditor agrees: it gates fontSize's and
        // pageMargins' effectiveness on nothing but a reflowable book.
        //
        // Turning advanced styles off for one of them rewrites every
        // heading's size for nothing, so the page reflows far beyond the
        // change asked for. Page margins used to do exactly that.
        for (css in ReadingCss.entries) {
            assertNull(ReaderPrefs().advancedIn(css))
            assertNull(ReaderPrefs(fontSize = 1.4).advancedIn(css))
            assertNull(ReaderPrefs(pageMargins = 1.5).advancedIn(css))
            assertNull(ReaderPrefs(fontWeight = ReaderFontWeight.BOLD).advancedIn(css))
        }
    }

    @Test
    fun `each advanced setting turns publisher styles off on its own`() {
        for (prefs in advancedSingletons) {
            assertEquals("$prefs", false, prefs.advancedIn(ReadingCss.Default))
        }
    }

    @Test
    fun `zero and off are things the reader asked for`() {
        // The absence of a request is null. An explicit "no extra space"
        // and hyphenation deliberately switched off are both requests,
        // and neither survives without advanced styles.
        assertEquals(false, ReaderPrefs(letterSpacing = 0.0).advancedIn(ReadingCss.Default))
        assertEquals(false, ReaderPrefs(hyphens = false).advancedIn(ReadingCss.Default))
    }

    @Test
    fun `a right-to-left book is not renormalized for rules its stylesheet lacks`() {
        // The RTL stylesheet carries no hyphens, letter-spacing or
        // word-spacing rule, so switching advanced styles on for one of
        // them would flatten the book's type scale and apply none of the
        // spacing that asked for it. It keeps textAlign, though, which is
        // why one "not the default stylesheet" state would not do.
        assertNull(ReaderPrefs(letterSpacing = 0.1).advancedIn(ReadingCss.Rtl))
        assertNull(ReaderPrefs(wordSpacing = 0.1).advancedIn(ReadingCss.Rtl))
        assertNull(ReaderPrefs(hyphens = true).advancedIn(ReadingCss.Rtl))

        assertEquals(false, ReaderPrefs(lineHeight = 1.6).advancedIn(ReadingCss.Rtl))
        assertEquals(false, ReaderPrefs(paragraphSpacing = 0.5).advancedIn(ReadingCss.Rtl))
        assertEquals(
            false,
            ReaderPrefs(textAlign = ReaderTextAlign.JUSTIFIED).advancedIn(ReadingCss.Rtl),
        )
    }

    @Test
    fun `a CJK book loses alignment as well`() {
        for (prefs in advancedSingletons) {
            val expected = prefs.lineHeight != null || prefs.paragraphSpacing != null
            assertEquals("$prefs", expected, prefs.advancedIn(ReadingCss.Cjk) == false)
        }
    }

    @Test
    fun `a fixed layout book honours none of it`() {
        for (prefs in advancedSingletons) {
            assertNull("$prefs", prefs.advancedIn(ReadingCss.Unsupported))
        }
    }

    @Test
    fun `a setting a book cannot use is still sent`() {
        // It stops counting towards advanced styles; it is not erased.
        // The reader set it app-wide, and the next book they open may be
        // one whose stylesheet has the rule.
        for (css in ReadingCss.entries) {
            val prefs = ReaderPrefs(
                letterSpacing = 0.1,
                wordSpacing = 0.2,
                hyphens = true,
                textAlign = ReaderTextAlign.JUSTIFIED,
            ).toEpubPreferences(theme, css = css)
            assertEquals(0.1, prefs.letterSpacing!!, 1e-9)
            assertEquals(0.2, prefs.wordSpacing!!, 1e-9)
            assertEquals(true, prefs.hyphens)
            assertEquals(TextAlign.JUSTIFY, prefs.textAlign)
        }
    }

    @Test
    fun `each fine typography setting reaches the field Readium reads`() {
        val prefs = ReaderPrefs(
            textAlign = ReaderTextAlign.RAGGED,
            fontWeight = ReaderFontWeight.LIGHT,
            hyphens = false,
            letterSpacing = 0.05,
            wordSpacing = 0.1,
            paragraphSpacing = 0.4,
        ).toEpubPreferences(theme, css = ReadingCss.Default)

        assertEquals(TextAlign.START, prefs.textAlign)
        assertEquals(0.75, prefs.fontWeight!!, 1e-9)
        assertEquals(false, prefs.hyphens)
        assertEquals(0.05, prefs.letterSpacing!!, 1e-9)
        assertEquals(0.1, prefs.wordSpacing!!, 1e-9)
        assertEquals(0.4, prefs.paragraphSpacing!!, 1e-9)
    }

    @Test
    fun `leaving a setting alone sends nothing at all`() {
        val prefs = ReaderPrefs().toEpubPreferences(theme, css = ReadingCss.Default)
        assertNull(prefs.textAlign)
        assertNull(prefs.fontWeight)
        assertNull(prefs.hyphens)
        assertNull(prefs.letterSpacing)
        assertNull(prefs.wordSpacing)
        assertNull(prefs.paragraphSpacing)
    }

    @Test
    fun `justified text hyphenates until told otherwise`() {
        // The stylesheet carries
        //   :root[readium-advanced-on][--USER__textAlign: justify] body
        //     { hyphens: auto }
        // and --USER__bodyHyphens is !important, so an explicit setting
        // wins and no setting loses. The way out is Off, not Default.
        assertTrue(justificationHyphenates(ReaderTextAlign.JUSTIFIED, null))
        assertFalse(justificationHyphenates(ReaderTextAlign.JUSTIFIED, false))
        assertFalse(justificationHyphenates(ReaderTextAlign.JUSTIFIED, true))
        assertFalse(justificationHyphenates(ReaderTextAlign.RAGGED, null))
        assertFalse(justificationHyphenates(ReaderTextAlign.DEFAULT, null))
    }

    @Test
    fun `a stored number that cannot be used neither throws nor changes the page`() {
        // EpubPreferences throws from its constructor on a negative
        // size, spacing or margin, and this runs while the reader is
        // changing their settings. NaN gets through a clamp untouched,
        // so it has to be refused rather than coerced.
        val wrecked = ReaderPrefs(
            fontSize = Double.NaN,
            lineHeight = Double.NEGATIVE_INFINITY,
            pageMargins = -1.0,
            letterSpacing = Double.NaN,
            wordSpacing = -0.5,
            paragraphSpacing = Double.POSITIVE_INFINITY,
        )
        for (css in ReadingCss.entries) {
            val prefs = wrecked.toEpubPreferences(theme, css = css)
            assertEquals(1.0, prefs.fontSize!!, 1e-9)
            assertNull(prefs.lineHeight)
            assertNull(prefs.pageMargins)
            assertNull(prefs.letterSpacing)
            assertNull(prefs.wordSpacing)
            assertNull(prefs.paragraphSpacing)
            assertNull(prefs.publisherStyles)
        }
    }

    @Test
    fun `the mapper says nothing about a book's language or direction`() {
        // readingCssFor reproduces Readium's own resolution of these
        // three from publication metadata, and that reproduction is
        // exact only while nothing overrides them. Setting one here
        // would make the settings sheet describe a stylesheet the book
        // is not being rendered with — silently, and only for the
        // readers whose books are affected.
        val prefs = ReaderPrefs(
            textAlign = ReaderTextAlign.JUSTIFIED,
            hyphens = true,
            letterSpacing = 0.1,
        ).toEpubPreferences(theme, css = ReadingCss.Default)
        assertNull(prefs.language)
        assertNull(prefs.readingProgression)
        assertNull(prefs.verticalText)
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
