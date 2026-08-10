package com.chmouel.liseur.data.settings

import androidx.compose.ui.graphics.Color

/**
 * Curated reading fonts, all OFL-licensed and bundled in assets/fonts.
 * [cssName] is the family name declared to the Readium navigator;
 * null means the publisher's original font.
 */
enum class ReaderFont(val id: String, val displayName: String, val cssName: String?) {
    LITERATA("literata", "Literata", "Literata"),
    VOLLKORN("vollkorn", "Vollkorn", "Vollkorn"),
    ATKINSON("atkinson", "Atkinson Hyperlegible", "Atkinson Hyperlegible"),
    INTER("inter", "Inter", "Inter"),
    PUBLISHER("publisher", "Publisher font", null),
    ;

    companion object {
        val Default = LITERATA

        fun fromId(id: String?): ReaderFont = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** Reading color themes, Kindle-style: independent from the app theme. */
enum class ReaderTheme(
    val id: String,
    val displayName: String,
    val background: Color,
    val foreground: Color,
) {
    LIGHT("light", "Light", Color(0xFFFFFFFF), Color(0xFF1A1A1A)),
    SEPIA("sepia", "Sepia", Color(0xFFF6EFDF), Color(0xFF3D3229)),
    DARK("dark", "Dark", Color(0xFF1F1F1F), Color(0xFFCECECE)),
    BLACK("black", "Black", Color(0xFF000000), Color(0xFFB8B8B8)),
    ;

    companion object {
        val Default = LIGHT

        fun fromId(id: String?): ReaderTheme = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * What the middle of the reading footer shows.
 *
 * The percentage read and the page number live at the footer's edges
 * and are always drawn; this enum only decides the slot between them,
 * and tapping the footer cycles it.
 */
enum class FooterMode(val id: String) {
    /**
     * Time left in the chapter once a reading pace has actually been
     * measured, and the chapter title until then — so the slot degrades
     * to something true rather than to a stock guess or to nothing.
     */
    SMART("time_chapter"),
    TIME_LEFT_BOOK("time_book"),
    CHAPTER_TITLE("chapter"),
    EMPTY("empty"),
    NONE("none"),
    ;

    /**
     * The next thing to show when the footer is tapped.
     *
     * [NONE] is not in the round: it hides the whole footer, and a tap
     * that landed on it would take away the very thing being tapped.
     * Hiding the footer is a decision, and it belongs in the typography
     * sheet where it can be undone. [EMPTY] is safe in the round — the
     * edges keep drawing, so the footer stays visible and tappable.
     */
    fun next(): FooterMode {
        val cycle = entries.filter { it != NONE }
        val at = cycle.indexOf(this)
        return cycle[(at + 1) % cycle.size]
    }

    companion object {
        val Default = SMART

        /**
         * Reads a stored mode. The ids `page` and `percent` belonged to
         * modes that put a single figure in the single slot the footer
         * used to have; both figures are now permanent edges, so those
         * preferences resolve to the default middle instead.
         */
        fun fromId(id: String?): FooterMode = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * How many columns of text a page is broken into.
 *
 * [AUTO] leaves the decision to Readium, which turns two columns on by
 * itself once the page is wide enough. The other two say so outright,
 * for the reader who wants one long column on a tablet or two short
 * ones on a smaller screen held sideways.
 *
 * Readium only honours a forced count on a wide enough viewport — its
 * stylesheet keeps the rule behind a `min-width: 60em` media query — so
 * on a phone all three of these read the same, which is the point: the
 * setting can be shown everywhere without changing what a phone does.
 */
enum class ColumnMode(val id: String, val displayName: String) {
    AUTO("auto", "Auto"),
    ONE("one", "1"),
    TWO("two", "2"),
    ;

    companion object {
        val Default = AUTO

        fun fromId(id: String?): ColumnMode = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * User reading preferences.
 *
 * @param fontSize Percentage where 1.0 is the publisher's default size.
 * @param lineHeight Line height multiplier (1.0–2.0), null keeps publisher styles.
 * @param pageMargins Page margin multiplier (0.5–2.0), null keeps publisher styles.
 * @param brightness Screen brightness override 0.0–1.0, null follows the system.
 * @param pageTurnAnimation Slide animation when turning pages; instant jump when off.
 * @param footerMode What the reading footer shows.
 * @param columnMode How many columns of text a wide page is broken into.
 */
data class ReaderPrefs(
    val font: ReaderFont = ReaderFont.Default,
    val fontSize: Double = 1.0,
    val theme: ReaderTheme = ReaderTheme.Default,
    val lineHeight: Double? = null,
    val pageMargins: Double? = null,
    val brightness: Float? = null,
    val pageTurnAnimation: Boolean = true,
    val footerMode: FooterMode = FooterMode.Default,
    val columnMode: ColumnMode = ColumnMode.Default,
) {
    companion object {
        const val MIN_FONT_SIZE = 0.6
        const val MAX_FONT_SIZE = 2.5
    }
}
