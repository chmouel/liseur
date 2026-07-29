package com.chmouel.liseur.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chmouel.liseur.domain.LibrarySort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How the app itself is coloured, as opposed to the page you read. */
enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        val Default = SYSTEM

        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * Settings that belong to the app rather than to a book.
 *
 * @param themeMode Light, dark, or whatever the system is doing.
 * @param dynamicColor Take the palette from the wallpaper (Android 12+).
 * @param volumeKeysTurnPages Volume keys page forward and back while reading.
 * @param resumeLastBook Opening the app goes back into the book you were in.
 * @param librarySort How the library grid is arranged.
 * @param librarySortReversed The library order read back to front.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.Default,
    val dynamicColor: Boolean = false,
    val volumeKeysTurnPages: Boolean = true,
    val resumeLastBook: Boolean = true,
    val librarySort: LibrarySort = LibrarySort.Default,
    val librarySortReversed: Boolean = false,
)

private val Context.appSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
)

/** Persists [AppSettings]. */
class AppSettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys_turn_pages")
        val RESUME_LAST_BOOK = booleanPreferencesKey("resume_last_book")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val LIBRARY_SORT_REVERSED = booleanPreferencesKey("library_sort_reversed")
    }

    val settings: Flow<AppSettings> = context.appSettingsStore.data.map { p ->
        AppSettings(
            themeMode = ThemeMode.fromId(p[Keys.THEME_MODE]),
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: false,
            volumeKeysTurnPages = p[Keys.VOLUME_KEYS] ?: true,
            resumeLastBook = p[Keys.RESUME_LAST_BOOK] ?: true,
            librarySort = LibrarySort.fromId(p[Keys.LIBRARY_SORT]),
            librarySortReversed = p[Keys.LIBRARY_SORT_REVERSED] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        context.appSettingsStore.edit { it[Keys.THEME_MODE] = mode.id }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.appSettingsStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setVolumeKeysTurnPages(enabled: Boolean) {
        context.appSettingsStore.edit { it[Keys.VOLUME_KEYS] = enabled }
    }

    suspend fun setResumeLastBook(enabled: Boolean) {
        context.appSettingsStore.edit { it[Keys.RESUME_LAST_BOOK] = enabled }
    }

    suspend fun setLibrarySort(sort: LibrarySort) {
        context.appSettingsStore.edit { it[Keys.LIBRARY_SORT] = sort.id }
    }

    suspend fun setLibrarySortReversed(reversed: Boolean) {
        context.appSettingsStore.edit { it[Keys.LIBRARY_SORT_REVERSED] = reversed }
    }
}
