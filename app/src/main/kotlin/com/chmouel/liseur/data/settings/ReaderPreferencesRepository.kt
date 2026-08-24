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

/** Persists the reading preferences (font, size, theme, brightness…). */
class ReaderPreferencesRepository(private val context: Context) {

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
    }

    val prefs: Flow<ReaderPrefs> = context.readerPrefsStore.data.map { p ->
        ReaderPrefs(
            font = ReaderFont.fromId(p[Keys.FONT]),
            fontSize = p[Keys.FONT_SIZE] ?: 1.0,
            themeChoice = ReaderThemeChoice.fromId(p[Keys.THEME]),
            lineHeight = p[Keys.LINE_HEIGHT],
            pageMargins = p[Keys.PAGE_MARGINS],
            brightness = p[Keys.BRIGHTNESS],
            pageTurnAnimation = p[Keys.PAGE_TURN_ANIMATION] ?: true,
            footerMode = FooterMode.fromId(p[Keys.FOOTER_MODE]),
            columnMode = ColumnMode.fromId(p[Keys.COLUMN_MODE]),
            autoScrollSpeed = AutoScrollPreference.sanitize(
                p[Keys.AUTO_SCROLL_SPEED] ?: AutoScrollPreference.DEFAULT_STEP,
            ),
        )
    }

    suspend fun setFont(font: ReaderFont) {
        context.readerPrefsStore.edit { it[Keys.FONT] = font.id }
    }

    suspend fun setFontSize(size: Double) {
        context.readerPrefsStore.edit {
            it[Keys.FONT_SIZE] = size.coerceIn(ReaderPrefs.MIN_FONT_SIZE, ReaderPrefs.MAX_FONT_SIZE)
        }
    }

    suspend fun setTheme(theme: ReaderThemeChoice) {
        context.readerPrefsStore.edit { it[Keys.THEME] = theme.id }
    }

    suspend fun setLineHeight(value: Double?) {
        context.readerPrefsStore.edit {
            if (value == null) it.remove(Keys.LINE_HEIGHT) else it[Keys.LINE_HEIGHT] = value
        }
    }

    suspend fun setPageMargins(value: Double?) {
        context.readerPrefsStore.edit {
            if (value == null) it.remove(Keys.PAGE_MARGINS) else it[Keys.PAGE_MARGINS] = value
        }
    }

    suspend fun setBrightness(value: Float?) {
        context.readerPrefsStore.edit {
            if (value == null) it.remove(Keys.BRIGHTNESS) else it[Keys.BRIGHTNESS] = value.coerceIn(0f, 1f)
        }
    }

    suspend fun setPageTurnAnimation(enabled: Boolean) {
        context.readerPrefsStore.edit { it[Keys.PAGE_TURN_ANIMATION] = enabled }
    }

    suspend fun setFooterMode(mode: FooterMode) {
        context.readerPrefsStore.edit { it[Keys.FOOTER_MODE] = mode.id }
    }

    suspend fun setColumnMode(mode: ColumnMode) {
        context.readerPrefsStore.edit { it[Keys.COLUMN_MODE] = mode.id }
    }

    suspend fun setAutoScrollSpeed(step: Float) {
        context.readerPrefsStore.edit {
            it[Keys.AUTO_SCROLL_SPEED] = AutoScrollPreference.snap(step)
        }
    }
}
