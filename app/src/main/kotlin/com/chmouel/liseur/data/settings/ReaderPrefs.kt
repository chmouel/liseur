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
 * User reading preferences.
 *
 * @param fontSize Percentage where 1.0 is the publisher's default size.
 * @param lineHeight Line height multiplier (1.0–2.0), null keeps publisher styles.
 * @param pageMargins Page margin multiplier (0.5–2.0), null keeps publisher styles.
 * @param brightness Screen brightness override 0.0–1.0, null follows the system.
 */
data class ReaderPrefs(
    val font: ReaderFont = ReaderFont.Default,
    val fontSize: Double = 1.0,
    val theme: ReaderTheme = ReaderTheme.Default,
    val lineHeight: Double? = null,
    val pageMargins: Double? = null,
    val brightness: Float? = null,
) {
    companion object {
        const val MIN_FONT_SIZE = 0.6
        const val MAX_FONT_SIZE = 2.5
    }
}
