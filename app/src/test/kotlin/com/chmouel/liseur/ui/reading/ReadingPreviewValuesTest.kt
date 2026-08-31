package com.chmouel.liseur.ui.reading

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.data.settings.ReaderFontWeight
import com.chmouel.liseur.data.settings.ReaderTextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings preview's arithmetic.
 *
 * A preview that disagrees with the page is worse than no preview,
 * because a reader believes it and stops looking. These are the two
 * places the two could disagree: Readium halves letter spacing on the
 * way to CSS, and paragraph spacing is a multiple of the text size
 * rather than a fixed gap.
 */
class ReadingPreviewValuesTest {

    @Test
    fun `only justified text is justified`() {
        assertEquals(TextAlign.Justify, previewTextAlign(ReaderTextAlign.JUSTIFIED))
        assertEquals(TextAlign.Start, previewTextAlign(ReaderTextAlign.RAGGED))
        assertEquals(TextAlign.Start, previewTextAlign(ReaderTextAlign.DEFAULT))
    }

    @Test
    fun `letter spacing is halved, as Readium halves it`() {
        // Readium sends Length.Rem(it / 2). A preview that skipped this
        // would overstate every setting by a factor of two.
        assertEquals(0.05f, previewLetterSpacing(0.1).value, 1e-6f)
        assertEquals(0.0f, previewLetterSpacing(0.0).value, 1e-6f)
    }

    @Test
    fun `letter spacing is relative to the text, not a fixed distance`() {
        // A rem is the text's own size, so em is the equivalent here.
        // Returning a real TextUnit is what stops the figure being used
        // where sp is meant.
        assertTrue(previewLetterSpacing(0.1).isEm)
        assertTrue(previewLetterSpacing(null).isEm)
        assertEquals(0.0f, previewLetterSpacing(null).value, 1e-6f)
    }

    @Test
    fun `an unset paragraph spacing draws a gap anyway`() {
        // Paragraphs with nothing between them do not look like a book,
        // so the default gets a declared stand-in.
        assertTrue(previewParagraphGapSp(null, textSp = 16.0) > 0.0)
    }

    @Test
    fun `an explicit zero draws no gap at all`() {
        // Which is the only way the reader can see the difference
        // between leaving the book alone and asking for none.
        assertEquals(0.0, previewParagraphGapSp(0.0, textSp = 16.0), 1e-9)
    }

    @Test
    fun `the gap scales with the text it separates`() {
        assertEquals(8.0, previewParagraphGapSp(0.5, textSp = 16.0), 1e-9)
        assertEquals(16.0, previewParagraphGapSp(0.5, textSp = 32.0), 1e-9)
    }

    @Test
    fun `the gap follows the system font scale, because it is in sp`() {
        // The composable converts through the current density, which is
        // where the font scale gets in. Kept in sp up to that point so
        // it does, and so this stays testable without a device.
        //
        // Not asserted as a multiplication: Compose scales sp
        // non-linearly above a certain size, so what matters is that a
        // reader who has turned their font up gets a wider gap, not that
        // it is a particular number of dp.
        val gapSp = previewParagraphGapSp(1.0, textSp = 16.0)
        val gaps = listOf(0.85f, 1.0f, 1.3f, 2.0f).map { fontScale ->
            with(Density(density = 2.5f, fontScale = fontScale)) { gapSp.sp.toDp().value }
        }
        assertEquals(16f, gaps[1], 1e-3f)
        gaps.zipWithNext { smaller, larger ->
            assertTrue("$gaps", larger > smaller)
        }
    }

    @Test
    fun `weight is a multiple of body text's own`() {
        assertEquals(FontWeight(400), previewFontWeight(ReaderFontWeight.DEFAULT))
        assertEquals(FontWeight(300), previewFontWeight(ReaderFontWeight.LIGHT))
        assertEquals(FontWeight(400), previewFontWeight(ReaderFontWeight.NORMAL))
        assertEquals(FontWeight(700), previewFontWeight(ReaderFontWeight.BOLD))
    }

    @Test
    fun `no offered weight lands outside what FontWeight accepts`() {
        ReaderFontWeight.entries.forEach {
            assertTrue(previewFontWeight(it).weight in 1..1000)
        }
    }
}
