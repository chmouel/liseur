package com.chmouel.liseur.ui.reading

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.chmouel.liseur.data.settings.ReaderFontWeight
import com.chmouel.liseur.data.settings.ReaderTextAlign

/**
 * What the settings preview draws, for settings Compose expresses
 * differently from Readium.
 *
 * Pure and separate from the composable so the arithmetic can be tested
 * without a device — a preview that quietly disagrees with the page is
 * worse than no preview, because it is believed.
 *
 * Not everything is previewable, and the preview does not pretend
 * otherwise: hyphenation needs a line break to fall in the right place
 * and word spacing needs more words than two paragraphs hold, so neither
 * is reliably visible there.
 */

/** Readium collapses everything but `justify` to a start-aligned line. */
fun previewTextAlign(align: ReaderTextAlign): TextAlign = when (align) {
    ReaderTextAlign.JUSTIFIED -> TextAlign.Justify
    ReaderTextAlign.DEFAULT, ReaderTextAlign.RAGGED -> TextAlign.Start
}

/**
 * Letter spacing as the page will have it.
 *
 * Readium halves the value on the way to CSS
 * (`letterSpacing = Length.Rem(it / 2)`), so the preview has to as well
 * or it overstates every setting by a factor of two. Returned in `em`,
 * which is what a rem is against the text's own size — and as a real
 * [TextUnit] so it cannot be mistaken for a figure in `sp`.
 */
fun previewLetterSpacing(value: Double?): TextUnit =
    if (value == null) 0.em else (value / 2).em

/**
 * The gap between paragraphs, in sp.
 *
 * Kept in sp rather than converted to `Dp` here so it stays pure, and so
 * the gap tracks both the size slider and the system font scale once the
 * composable converts it with the current density.
 *
 * An unset spacing draws a stand-in gap, because a preview of paragraphs
 * with nothing between them does not look like a book. An explicit zero
 * draws nothing at all, which is exactly what it asks for and the only
 * way the two can be told apart on screen.
 */
fun previewParagraphGapSp(value: Double?, textSp: Double): Double =
    if (value == null) 0.5 * textSp else value * textSp

/**
 * Weight as a Compose [FontWeight].
 *
 * Readium's multiplier is against the element's own weight, which for
 * body text is 400. Clamped to the 1..1000 that [FontWeight] accepts.
 */
fun previewFontWeight(weight: ReaderFontWeight): FontWeight {
    val multiplier = weight.multiplier ?: return FontWeight(FontWeight.Normal.weight)
    return FontWeight((multiplier * FontWeight.Normal.weight).toInt().coerceIn(1, 1000))
}
