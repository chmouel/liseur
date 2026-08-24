package com.chmouel.liseur.reader.chrome

/**
 * The one place that knows how tall the reading footer is.
 *
 * The page reserves the footer's room before it lays itself out (see
 * ReaderScreen), so the reservation cannot be measured off the composed
 * footer — it has to be derived. Everything the derivation uses lives
 * here, and [ReadingFooter] draws with the same constants, so the
 * reservation and the footer cannot drift apart.
 */
object FooterMetrics {
    /** Vertical padding inside the footer row, top and bottom, in dp. */
    const val VERTICAL_PADDING_DP = 6f

    /** Air kept between the page's last line and the footer's top edge, in dp. */
    const val CLEARANCE_DP = 4f

    /**
     * The line height the footer's labelSmall text falls back to when
     * the theme's value is not given in sp (Material's own default).
     */
    const val FALLBACK_LINE_HEIGHT_SP = 16f

    /**
     * The height, in dp, a page must keep clear for the footer.
     *
     * The text is set in sp, which follows the system font-size
     * setting; dp does not. Converting sp to dp is exactly a multiply
     * by the font scale, so the reservation grows with the text it is
     * making room for. Window insets are deliberately not in here —
     * they are the layout's job, applied as inset padding where
     * Compose can consume overlaps between them.
     */
    fun reservedHeightDp(lineHeightSp: Float, fontScale: Float): Float =
        lineHeightSp * fontScale + VERTICAL_PADDING_DP * 2 + CLEARANCE_DP
}
