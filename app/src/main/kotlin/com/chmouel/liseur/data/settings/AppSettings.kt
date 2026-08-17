package com.chmouel.liseur.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.LibraryFilters
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
 * Whether the app draws for an electronic paper screen.
 *
 * E-paper repaints slowly and leaves the last frame behind for a moment,
 * so anything that moves is at best wasted and at worst a smear that has
 * to be cleared. [ON] takes the movement out: no page turn slide, no
 * fading chrome, no shimmer while the library loads, no spinner turning
 * on the spot.
 *
 * [AUTO] guesses from the device, which is a guess and known to be one,
 * hence the two settings either side of it that overrule it.
 */
enum class EInkMode(val id: String) {
    AUTO("auto"),
    ON("on"),
    OFF("off"),
    ;

    /** Whether to draw for e-paper, given what the device looks like. */
    fun resolve(deviceLooksLikeEInk: Boolean): Boolean = when (this) {
        AUTO -> deviceLooksLikeEInk
        ON -> true
        OFF -> false
    }

    companion object {
        val Default = AUTO

        fun fromId(id: String?): EInkMode = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** Where the Define action sends selected text. */
enum class DefinitionTarget(val id: String) {
    BUILT_IN("built_in"),
    EXTERNAL_APP("external_app"),
    ;

    companion object {
        val Default = BUILT_IN

        fun fromId(id: String?): DefinitionTarget =
            entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * Settings that belong to the app rather than to a book.
 *
 * @param themeMode Light, dark, or whatever the system is doing.
 * @param dynamicColor Take the palette from the wallpaper (Android 12+).
 *   On by default where the system can do it; the hand-made palette is
 *   what you get back by turning it off, and what older phones always get.
 * @param volumeKeysTurnPages Volume keys page forward and back while reading.
 * @param resumeLastBook Opening the app goes back into the book you were in.
 * @param keepScreenOn The screen stays awake while a book is open. Off
 *   until asked for: it costs battery, and it overrides a device setting
 *   the reader chose themselves.
 * @param librarySort How the library grid is arranged.
 * @param librarySortReversed The library order read back to front.
 * @param libraryFilters What the library grid is narrowed to.
 * @param eInkMode Whether to drop animation for an electronic paper screen.
 * @param definitionTarget Whether Define opens Liseur's definition card or
 *   sends the text to another app.
 * @param dictionaryLookupEnabled Whether Define may ask a dictionary server
 *   for a definition. Off until asked for, because that server is the one
 *   thing the app talks to that the reader did not choose themselves.
 * @param dictionaryBaseUrl The site definitions are fetched from. Any
 *   Wiktionary works, so a reader can pick their own language's edition or
 *   a mirror instead of the default.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.Default,
    val dynamicColor: Boolean = true,
    val volumeKeysTurnPages: Boolean = true,
    val resumeLastBook: Boolean = true,
    val keepScreenOn: Boolean = false,
    val librarySort: LibrarySort = LibrarySort.Default,
    val librarySortReversed: Boolean = false,
    val libraryFilters: LibraryFilters = LibraryFilters.None,
    val eInkMode: EInkMode = EInkMode.Default,
    val definitionTarget: DefinitionTarget = DefinitionTarget.Default,
    val dictionaryLookupEnabled: Boolean = false,
    val dictionaryBaseUrl: String = DictionaryUrl.DEFAULT_BASE_URL,
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
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val LIBRARY_SORT_REVERSED = booleanPreferencesKey("library_sort_reversed")
        val LIBRARY_FILTERS = stringPreferencesKey("library_filters")
        val LIBRARY_GROUP_BY_SERIES = booleanPreferencesKey("library_group_by_series")
        val EINK_MODE = stringPreferencesKey("eink_mode")
        val DEFINITION_TARGET = stringPreferencesKey("definition_target")
        val DICTIONARY_ENABLED = booleanPreferencesKey("dictionary_lookup_enabled")
        val DICTIONARY_BASE_URL = stringPreferencesKey("dictionary_base_url")
        val ACCOUNT_LOST = booleanPreferencesKey("calibre_account_lost_to_restore")
    }

    /**
     * Whether a calibre-web account was dropped because its password
     * arrived from another device and could not be decrypted.
     *
     * Kept apart from [settings] because it is a one-off message rather
     * than a preference: it is raised once and cleared as soon as it has
     * been read, or as soon as an account is connected again.
     */
    val accountLostToRestore: Flow<Boolean> =
        context.appSettingsStore.data.map { it[Keys.ACCOUNT_LOST] ?: false }

    suspend fun setAccountLostToRestore(lost: Boolean) {
        context.appSettingsStore.edit { it[Keys.ACCOUNT_LOST] = lost }
    }

    val settings: Flow<AppSettings> = context.appSettingsStore.data.map { p ->
        AppSettings(
            themeMode = ThemeMode.fromId(p[Keys.THEME_MODE]),
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            volumeKeysTurnPages = p[Keys.VOLUME_KEYS] ?: true,
            resumeLastBook = p[Keys.RESUME_LAST_BOOK] ?: true,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: false,
            librarySort = LibrarySort.fromId(p[Keys.LIBRARY_SORT]),
            librarySortReversed = p[Keys.LIBRARY_SORT_REVERSED] ?: false,
            libraryFilters = LibraryFilters(
                options = LibraryFilters.parse(p[Keys.LIBRARY_FILTERS]),
                groupBySeries = p[Keys.LIBRARY_GROUP_BY_SERIES] ?: false,
            ),
            eInkMode = EInkMode.fromId(p[Keys.EINK_MODE]),
            definitionTarget = DefinitionTarget.fromId(p[Keys.DEFINITION_TARGET]),
            dictionaryLookupEnabled = p[Keys.DICTIONARY_ENABLED] ?: false,
            dictionaryBaseUrl = p[Keys.DICTIONARY_BASE_URL]?.let(DictionaryUrl::normalise)
                ?: DictionaryUrl.DEFAULT_BASE_URL,
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

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.appSettingsStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setLibrarySort(sort: LibrarySort) {
        context.appSettingsStore.edit { it[Keys.LIBRARY_SORT] = sort.id }
    }

    suspend fun setLibrarySortReversed(reversed: Boolean) {
        context.appSettingsStore.edit { it[Keys.LIBRARY_SORT_REVERSED] = reversed }
    }

    /**
     * Changes the filters from whatever is stored at the moment of the
     * write.
     *
     * A read of [current] followed by a write would be two steps, and
     * every checkbox in the filter menu is one tap away from the next:
     * two taps in quick succession would both read the selection as it
     * was before either, and the second write would drop the first
     * option. A menu whose boxes are meant to be ticked together cannot
     * afford to behave as single-select under a fast hand.
     *
     * DataStore serialises `edit`, so doing the reading inside it is
     * what makes the whole change atomic.
     */
    suspend fun editLibraryFilters(edit: (LibraryFilters) -> LibraryFilters) {
        context.appSettingsStore.edit { p ->
            val filters = edit(
                LibraryFilters(
                    options = LibraryFilters.parse(p[Keys.LIBRARY_FILTERS]),
                    groupBySeries = p[Keys.LIBRARY_GROUP_BY_SERIES] ?: false,
                ),
            )
            p[Keys.LIBRARY_FILTERS] = filters.serialise()
            p[Keys.LIBRARY_GROUP_BY_SERIES] = filters.groupBySeries
        }
    }

    suspend fun setEInkMode(mode: EInkMode) {
        context.appSettingsStore.edit { it[Keys.EINK_MODE] = mode.id }
    }

    suspend fun setDefinitionTarget(target: DefinitionTarget) {
        context.appSettingsStore.edit { it[Keys.DEFINITION_TARGET] = target.id }
    }

    suspend fun setDictionaryLookupEnabled(enabled: Boolean) {
        context.appSettingsStore.edit { it[Keys.DICTIONARY_ENABLED] = enabled }
    }

    /**
     * Stores the dictionary site, normalised. Anything that cannot be a
     * URL puts the default back rather than leaving the reader with a
     * dictionary that silently never answers.
     */
    suspend fun setDictionaryBaseUrl(url: String) {
        val normalised = DictionaryUrl.normalise(url) ?: DictionaryUrl.DEFAULT_BASE_URL
        context.appSettingsStore.edit { it[Keys.DICTIONARY_BASE_URL] = normalised }
    }
}
