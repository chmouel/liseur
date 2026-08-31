package com.chmouel.liseur.data.db

import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderFontWeight
import com.chmouel.liseur.data.settings.ReadingFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTextAlign
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a book keeps for itself, and what stays shared.
 *
 * The split is the whole point of the feature: get it wrong the generous
 * way and setting one book apart quietly stops the reading theme from
 * following the time of day everywhere else.
 */
class BookTypographyTest {

    private val shared = ReaderPrefs(
        font = ReadingFont.Bundled(ReaderFont.LITERATA),
        fontSize = 1.0,
        themeChoice = ReaderThemeChoice.DARK,
        lineHeight = 1.4,
        pageMargins = 1.0,
        brightness = 0.3f,
        pageTurnAnimation = false,
        footerMode = FooterMode.SMART,
    )

    private val own = BookTypography(
        bookUrl = "book",
        font = ReaderFont.ATKINSON.id,
        fontSize = 1.6,
        lineHeight = 2.0,
        pageMargins = 1.8,
    )

    @Test
    fun `a book with nothing of its own reads like every other`() {
        assertEquals(shared, shared.withTypographyOf(null))
    }

    @Test
    fun `a book set apart is set in what it asked for`() {
        val effective = shared.withTypographyOf(own)
        assertEquals(ReadingFont.Bundled(ReaderFont.ATKINSON), effective.font)
        assertEquals(1.6, effective.fontSize, 0.0)
        assertEquals(2.0, effective.lineHeight!!, 0.0)
        assertEquals(1.8, effective.pageMargins!!, 0.0)
    }

    @Test
    fun `the theme, the brightness and the rest stay shared`() {
        val effective = shared.withTypographyOf(own)
        assertEquals(ReaderThemeChoice.DARK, effective.themeChoice)
        assertEquals(0.3f, effective.brightness!!, 0f)
        assertEquals(false, effective.pageTurnAnimation)
        assertEquals(FooterMode.SMART, effective.footerMode)
    }

    @Test
    fun `a book can ask for the publisher's own spacing while others do not`() {
        val effective = shared.withTypographyOf(
            own.copy(lineHeight = null, pageMargins = null),
        )
        assertEquals(null, effective.lineHeight)
        assertEquals(null, effective.pageMargins)
        assertEquals(1.4, shared.lineHeight!!, 0.0)
    }

    @Test
    fun `setting a book apart changes nothing about how it reads`() {
        val effective = shared.withTypographyOf(BookTypography.from("book", shared))
        assertEquals(shared, effective)
    }

    @Test
    fun `a font that is no longer bundled falls back rather than failing`() {
        val effective = shared.withTypographyOf(own.copy(font = "a-font-we-removed"))
        assertEquals(ReadingFont.Default, effective.font)
    }

    @Test
    fun `a book set apart in an imported font keeps the import`() {
        val digest = "a".repeat(64)
        val effective = shared.withTypographyOf(own.copy(font = "user:" + digest))
        assertEquals(ReadingFont.Imported(digest), effective.font)
    }

    @Test
    fun `setting a book apart records the raw font, not the fallback`() {
        // The point of the raw/effective split. If a book set apart while
        // its imported font happened to be missing recorded the fallback,
        // re-importing the very same file would no longer bring the
        // choice back — and the reader would never learn why.
        val digest = "b".repeat(64)
        val missing = shared.copy(font = ReadingFont.Imported(digest))
        assertEquals("user:" + digest, BookTypography.from("book", missing).font)
    }

    @Test
    fun `the fine typography settings stay shared even for a book set apart`() {
        // Alignment, hyphenation, weight and the three spacings are
        // about how the reader reads rather than about the book, and the
        // row would need a migration to carry them. A book taking them
        // over silently would be the feature quietly growing.
        val tuned = shared.copy(
            textAlign = ReaderTextAlign.JUSTIFIED,
            fontWeight = ReaderFontWeight.LIGHT,
            hyphens = true,
            letterSpacing = 0.05,
            wordSpacing = 0.1,
            paragraphSpacing = 0.4,
        )
        val effective = tuned.withTypographyOf(own)

        assertEquals(ReaderTextAlign.JUSTIFIED, effective.textAlign)
        assertEquals(ReaderFontWeight.LIGHT, effective.fontWeight)
        assertEquals(true, effective.hyphens)
        assertEquals(0.05, effective.letterSpacing!!, 1e-9)
        assertEquals(0.1, effective.wordSpacing!!, 1e-9)
        assertEquals(0.4, effective.paragraphSpacing!!, 1e-9)
    }

    @Test
    fun `a book's own row cannot hold a value that would crash the reader`() {
        // This row is a file on a device, and it is the newer and less
        // trustworthy of the two sources — so it is what has to be
        // checked, after the merge rather than before it.
        val effective = shared.withTypographyOf(
            own.copy(fontSize = Double.NaN, lineHeight = -3.0, pageMargins = 99.0),
        )
        assertEquals(1.0, effective.fontSize, 1e-9)
        assertNull(effective.lineHeight)
        assertNull(effective.pageMargins)
    }

    @Test
    fun `setting a book apart writes down numbers it can be opened with`() {
        // A bad value stored now is a crash the next time the book is
        // opened, long after anything could explain it.
        val wrecked = shared.copy(
            fontSize = Double.POSITIVE_INFINITY,
            lineHeight = Double.NaN,
            pageMargins = -1.0,
        )
        val row = BookTypography.from("book", wrecked)
        assertEquals(1.0, row.fontSize, 1e-9)
        assertNull(row.lineHeight)
        assertNull(row.pageMargins)
    }
}
