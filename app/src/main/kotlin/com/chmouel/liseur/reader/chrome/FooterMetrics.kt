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
     * Air kept under the footer, between it and the screen's edge, in dp.
     *
     * The footer is only ever drawn while the chrome is hidden, and the
     * chrome being hidden is what hides the system bars, so there is no
     * navigation bar down there to sit on — reserving its inset left a
     * finger-deep band of blank paper on three-button navigation. With
     * the footer's own [VERTICAL_PADDING_DP] this comes to the same
     * 12dp margin the page keeps at the top.
     */
    const val BOTTOM_MARGIN_DP = 6f

    /**
     * The line height the footer's labelSmall text falls back to when
     * the theme's value is not given in sp (Material's own default).
     */
    const val FALLBACK_LINE_HEIGHT_SP = 16f

    /**
     * The height, in dp, a page must keep clear for the footer.
     *
     * The line height arrives already converted to dp — the caller
     * owns the sp-to-dp conversion, through `Density.toDp()`, which
     * honours Android's nonlinear font scaling where a bare multiply
     * by the font scale would not. Window insets are deliberately not
     * in here: the footer is drawn with the system bars hidden, so the
     * only thing under it is the screen's edge and [BOTTOM_MARGIN_DP].
     */
    fun reservedHeightDp(lineHeightDp: Float): Float =
        lineHeightDp + VERTICAL_PADDING_DP * 2 + CLEARANCE_DP + BOTTOM_MARGIN_DP
}
