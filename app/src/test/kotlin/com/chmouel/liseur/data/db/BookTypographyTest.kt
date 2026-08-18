package com.chmouel.liseur.data.db

import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import org.junit.Assert.assertEquals
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
        font = ReaderFont.LITERATA,
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
        assertEquals(ReaderFont.ATKINSON, effective.font)
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
        assertEquals(ReaderFont.Default, effective.font)
    }
}
