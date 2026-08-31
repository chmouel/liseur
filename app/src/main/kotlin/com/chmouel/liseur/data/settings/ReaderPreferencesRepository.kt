package com.chmouel.liseur.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readerPrefsStore: DataStore<Preferences> by preferencesDataStore(
    name = "reader_preferences",
)

/**
 * Persists the reading preferences (font, size, theme, brightness…).
 *
 * Takes the store rather than a [Context] so the round trip can be
 * tested against a real one on a temporary file, without an emulator;
 * `ReadingPaceRepository` is the same shape for the same reason.
 */
class ReaderPreferencesRepository(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.readerPrefsStore)

    private object Keys {
        val FONT = stringPreferencesKey("font")
        val FONT_SIZE = doublePreferencesKey("font_size")
        val THEME = stringPreferencesKey("theme")
        val LINE_HEIGHT = doublePreferencesKey("line_height")
        val PAGE_MARGINS = doublePreferencesKey("page_margins")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val PAGE_TURN_ANIMATION = booleanPreferencesKey("page_turn_animation")
        val FOOTER_MODE = stringPreferencesKey("footer_mode")
        val COLUMN_MODE = stringPreferencesKey("column_mode")
        val AUTO_SCROLL_SPEED = floatPreferencesKey("auto_scroll_speed")
        val TEXT_ALIGN = stringPreferencesKey("text_align")
        val FONT_WEIGHT = stringPreferencesKey("font_weight")
        val HYPHENS = booleanPreferencesKey("hyphens")
        val LETTER_SPACING = doublePreferencesKey("letter_spacing")
        val WORD_SPACING = doublePreferencesKey("word_spacing")
        val PARAGRAPH_SPACING = doublePreferencesKey("paragraph_spacing")
    }

    /**
     * Sanitized on the way out as well as on the way in: this file is on
     * a device, and a value that has been edited underneath us is a
     * crash in `EpubPreferences` rather than a wrong page.
     */
    val prefs: Flow<ReaderPrefs> = store.data.map { p ->
        ReaderPrefs(
            font = ReadingFont.fromId(p[Keys.FONT]),
            fontSize = p[Keys.FONT_SIZE] ?: 1.0,
            themeChoice = ReaderThemeChoice.fromId(p[Keys.THEME]),
            lineHeight = p[Keys.LINE_HEIGHT],
            pageMargins = p[Keys.PAGE_MARGINS],
            brightness = p[Keys.BRIGHTNESS],
            pageTurnAnimation = p[Keys.PAGE_TURN_ANIMATION] ?: true,
            footerMode = FooterMode.fromId(p[Keys.FOOTER_MODE]),
            columnMode = ColumnMode.fromId(p[Keys.COLUMN_MODE]),
            autoScrollSpeed = p[Keys.AUTO_SCROLL_SPEED] ?: AutoScrollPreference.DEFAULT_STEP,
            textAlign = ReaderTextAlign.fromId(p[Keys.TEXT_ALIGN]),
            fontWeight = ReaderFontWeight.fromId(p[Keys.FONT_WEIGHT]),
            hyphens = p[Keys.HYPHENS],
            letterSpacing = p[Keys.LETTER_SPACING],
            wordSpacing = p[Keys.WORD_SPACING],
            paragraphSpacing = p[Keys.PARAGRAPH_SPACING],
        ).sanitized()
    }

    suspend fun setFont(font: ReadingFont) {
        store.edit { it[Keys.FONT] = font.id }
    }

    suspend fun setFontSize(size: Double) {
        store.edit { it[Keys.FONT_SIZE] = TypographyRange.FONT_SIZE.require(size) }
    }

    suspend fun setTheme(theme: ReaderThemeChoice) {
        store.edit { it[Keys.THEME] = theme.id }
    }

    suspend fun setLineHeight(value: Double?) {
        setNullableDouble(Keys.LINE_HEIGHT, TypographyRange.LINE_HEIGHT.sanitize(value))
    }

    suspend fun setPageMargins(value: Double?) {
        setNullableDouble(Keys.PAGE_MARGINS, TypographyRange.PAGE_MARGINS.sanitize(value))
    }

    suspend fun setBrightness(value: Float?) {
        store.edit {
            if (value == null) it.remove(Keys.BRIGHTNESS) else it[Keys.BRIGHTNESS] = value.coerceIn(0f, 1f)
        }
    }

    suspend fun setPageTurnAnimation(enabled: Boolean) {
        store.edit { it[Keys.PAGE_TURN_ANIMATION] = enabled }
    }

    suspend fun setFooterMode(mode: FooterMode) {
        store.edit { it[Keys.FOOTER_MODE] = mode.id }
    }

    suspend fun setColumnMode(mode: ColumnMode) {
        store.edit { it[Keys.COLUMN_MODE] = mode.id }
    }

    suspend fun setAutoScrollSpeed(step: Float) {
        store.edit { it[Keys.AUTO_SCROLL_SPEED] = AutoScrollPreference.snap(step) }
    }

    suspend fun setTextAlign(align: ReaderTextAlign) {
        store.edit { it[Keys.TEXT_ALIGN] = align.id }
    }

    suspend fun setFontWeight(weight: ReaderFontWeight) {
        store.edit { it[Keys.FONT_WEIGHT] = weight.id }
    }

    suspend fun setHyphens(value: Boolean?) {
        store.edit {
            if (value == null) it.remove(Keys.HYPHENS) else it[Keys.HYPHENS] = value
        }
    }

    suspend fun setLetterSpacing(value: Double?) {
        setNullableDouble(Keys.LETTER_SPACING, TypographyRange.LETTER_SPACING.sanitize(value))
    }

    suspend fun setWordSpacing(value: Double?) {
        setNullableDouble(Keys.WORD_SPACING, TypographyRange.WORD_SPACING.sanitize(value))
    }

    suspend fun setParagraphSpacing(value: Double?) {
        setNullableDouble(Keys.PARAGRAPH_SPACING, TypographyRange.PARAGRAPH_SPACING.sanitize(value))
    }

    /**
     * Writing a spacing, where "no value" is a key that is not there
     * rather than a sentinel number: an absent key is what every reader
     * of this store already understands as the default, and a sentinel
     * would be one more number that has to be told apart from a real
     * one.
     */
    private suspend fun setNullableDouble(key: Preferences.Key<Double>, value: Double?) {
        store.edit {
            if (value == null) it.remove(key) else it[key] = value
        }
    }
}
