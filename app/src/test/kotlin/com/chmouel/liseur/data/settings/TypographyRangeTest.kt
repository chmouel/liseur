package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a stored number is allowed to be.
 *
 * `EpubPreferences` throws from its constructor on a negative size,
 * spacing or margin, so these are not cosmetic: the moment a bad value
 * would be used is the moment the reader is changing their settings, and
 * an unguarded one takes the reader down with it.
 *
 * Assertions are on tick indices or with a delta, never on literal
 * decimals — a `Double` cannot hold 0.15, and a test that says it can
 * fails somewhere else for a reason nobody will enjoy finding.
 */
class TypographyRangeTest {

    private val slider = TypographyRange.LETTER_SPACING

    @Test
    fun `a spacing that is not a number is not a preference`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(slider.sanitize(bad))
            assertNull(TypographyRange.LINE_HEIGHT.sanitize(bad))
            assertNull(TypographyRange.PAGE_MARGINS.sanitize(bad))
        }
    }

    @Test
    fun `a negative spacing is discarded rather than pulled up to zero`() {
        // Clamping would turn a corrupt byte into an explicit override,
        // which switches Readium's advanced styles on and renormalizes
        // the book — a page rewritten because a file was damaged.
        assertNull(slider.sanitize(-1.0))
        assertNull(TypographyRange.WORD_SPACING.sanitize(-0.001))
        assertNull(TypographyRange.PARAGRAPH_SPACING.sanitize(-42.0))
    }

    @Test
    fun `zero spacing is a decision and is kept`() {
        assertEquals(0.0, slider.sanitize(0.0)!!, 1e-9)
        assertEquals(0.0, TypographyRange.WORD_SPACING.sanitize(0.0)!!, 1e-9)
        assertEquals(0.0, TypographyRange.PARAGRAPH_SPACING.sanitize(0.0)!!, 1e-9)
    }

    @Test
    fun `a spacing above the range is a preference honoured as closely as possible`() {
        // Liseur's ranges are narrower than Readium's, so a larger value
        // is a real setting from a wider one rather than nonsense.
        assertEquals(slider.max, slider.sanitize(0.9)!!, 1e-9)
        assertEquals(
            TypographyRange.PARAGRAPH_SPACING.max,
            TypographyRange.PARAGRAPH_SPACING.sanitize(9.0)!!,
            1e-9,
        )
    }

    @Test
    fun `a segmented value outside its range is discarded, never clamped`() {
        // The reader can only ever write one of three offered values or
        // nothing, so anything else is not a preference to approximate.
        assertNull(TypographyRange.LINE_HEIGHT.sanitize(0.0))
        assertNull(TypographyRange.LINE_HEIGHT.sanitize(0.9))
        assertNull(TypographyRange.LINE_HEIGHT.sanitize(2.1))
        assertNull(TypographyRange.PAGE_MARGINS.sanitize(4.1))
    }

    @Test
    fun `the offered segmented values survive`() {
        for (v in listOf(1.2, 1.8)) {
            assertEquals(v, TypographyRange.LINE_HEIGHT.sanitize(v)!!, 1e-9)
        }
        for (v in listOf(0.5, 2.0)) {
            assertEquals(v, TypographyRange.PAGE_MARGINS.sanitize(v)!!, 1e-9)
        }
        // Zero margins is inside Readium's own range: an unusual page,
        // not an impossible one.
        assertEquals(0.0, TypographyRange.PAGE_MARGINS.sanitize(0.0)!!, 1e-9)
    }

    @Test
    fun `a font size has no null to fall back to, so it falls back to a size`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertEquals(1.0, TypographyRange.FONT_SIZE.require(bad), 1e-9)
        }
        assertEquals(ReaderPrefs.MAX_FONT_SIZE, TypographyRange.FONT_SIZE.require(99.0), 1e-9)
        assertEquals(ReaderPrefs.MIN_FONT_SIZE, TypographyRange.FONT_SIZE.require(0.01), 1e-9)
        assertEquals(1.4, TypographyRange.FONT_SIZE.require(1.4), 1e-9)
    }

    @Test
    fun `snapping lands on a notch, counted rather than added up`() {
        for (tick in 0..slider.tickCount) {
            val exact = slider.min + tick * slider.increment
            assertEquals(tick, slider.tickOf(slider.snap(exact)))
        }
    }

    @Test
    fun `a value between notches goes to the nearer one`() {
        val nudged = slider.min + 4 * slider.increment + slider.increment * 0.4
        assertEquals(4, slider.tickOf(slider.snap(nudged)))
    }

    @Test
    fun `a value that came through a slider's Float survives the round trip`() {
        // The control carries a Float and the store holds a Double, so
        // every value makes this crossing at least once.
        for (tick in 0..slider.tickCount) {
            val viaFloat = (slider.min + tick * slider.increment).toFloat().toDouble()
            assertEquals(tick, slider.tickOf(slider.snap(viaFloat)))
        }
    }

    @Test
    fun `writing a snapped value back does not move it`() {
        var value = TypographyRange.PARAGRAPH_SPACING.snap(0.7)
        repeat(5) { value = TypographyRange.PARAGRAPH_SPACING.sanitize(value)!! }
        assertEquals(7, TypographyRange.PARAGRAPH_SPACING.tickOf(value))
    }

    @Test
    fun `an unset spacing and an explicit zero rest the thumb in the same place`() {
        assertEquals(spacingThumb(null, slider), spacingThumb(0.0, slider))
    }

    @Test
    fun `a slider always commits a value, never an absence`() {
        // Which is why the row needs a button as well: there is no drag
        // from an explicit zero back to leaving the book alone.
        assertEquals(0.0, spacingCommit(spacingThumb(null, slider), slider), 1e-9)
        assertEquals(slider.max, spacingCommit(slider.max.toFloat(), slider), 1e-9)
    }

    @Test
    fun `a stored id nobody recognises falls back rather than failing`() {
        for (id in listOf(null, "", "  ", "centre", "semibold")) {
            assertEquals(ReaderTextAlign.DEFAULT, ReaderTextAlign.fromId(id))
            assertEquals(ReaderFontWeight.DEFAULT, ReaderFontWeight.fromId(id))
        }
        assertEquals(ReaderTextAlign.JUSTIFIED, ReaderTextAlign.fromId("justified"))
        assertEquals(ReaderFontWeight.BOLD, ReaderFontWeight.fromId("bold"))
    }

    @Test
    fun `no font weight on offer is one Readium would refuse`() {
        // EpubPreferences requires 0.0..2.5, and storing an id rather
        // than a number is what makes that unreachable.
        ReaderFontWeight.entries.forEach { weight ->
            weight.multiplier?.let { assertTrue(it in 0.0..2.5) }
        }
    }

    @Test
    fun `sanitizing a whole set of preferences leaves nothing unusable`() {
        val wrecked = ReaderPrefs(
            fontSize = Double.NaN,
            lineHeight = -1.0,
            pageMargins = Double.POSITIVE_INFINITY,
            letterSpacing = -0.5,
            wordSpacing = Double.NaN,
            paragraphSpacing = 99.0,
            brightness = Float.NaN,
            autoScrollSpeed = Float.NaN,
        ).sanitized()

        assertEquals(1.0, wrecked.fontSize, 1e-9)
        assertNull(wrecked.lineHeight)
        assertNull(wrecked.pageMargins)
        assertNull(wrecked.letterSpacing)
        assertNull(wrecked.wordSpacing)
        assertEquals(TypographyRange.PARAGRAPH_SPACING.max, wrecked.paragraphSpacing!!, 1e-9)
        assertNull(wrecked.brightness)
        assertEquals(AutoScrollPreference.DEFAULT_STEP, wrecked.autoScrollSpeed, 1e-6f)
    }

    @Test
    fun `damage cannot switch advanced styles on by itself`() {
        val wrecked = ReaderPrefs(
            lineHeight = Double.NaN,
            letterSpacing = -1.0,
            wordSpacing = Double.NEGATIVE_INFINITY,
        ).sanitized()
        assertTrue(!wrecked.requiresAdvancedStyles(ReadingCss.Default))
    }
}
