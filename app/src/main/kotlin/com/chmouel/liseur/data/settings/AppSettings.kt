package com.chmouel.liseur.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.json.JSONObject
import androidx.datastore.preferences.preferencesDataStore
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.LibraryFilters
import com.chmouel.liseur.domain.LibrarySort
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.reader.annotations.HighlightPalette
import com.chmouel.liseur.reader.annotations.HighlightTint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How the app itself is coloured, as opposed to the page you read. */
enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    /**
     * Whether this mode is asking for a dark app, given what the system
     * is currently set to.
     *
     * [systemDark] is only consulted under [SYSTEM]; the other two have
     * already answered. Kept here, off Compose, so the reading theme can
     * be resolved against the same answer the app draws itself with.
     */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

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

/**
 * Which side of a paginated page turns forward.
 *
 * [STANDARD] is the layout the app has always had: the side the book
 * came from goes back, the rest goes forward — the left of the page on
 * a left-to-right book, the right of it on a right-to-left one.
 * [SWAPPED] puts the forward turn under the other thumb, for a reader
 * holding the phone in the other hand — every page turn otherwise
 * reaches across the screen.
 *
 * "The other thumb" and not "the left side": a book that reads right to
 * left already turns forward on the left, so [SWAPPED] puts forward back
 * on the right there. The preset says which hand is holding the phone,
 * and leaves the book to say where its next page is.
 *
 * Only two, and deliberately: a zone editor is a settings hobby, and a
 * third preset has to earn its place by describing a hand position that
 * actually occurs. See `docs/adr/0009-tap-zone-customization.md`.
 *
 * The centre and the top strip still reveal the chrome under both, the
 * volume keys still go forward on down, and a book read by scrolling has
 * no page sides to tap in the first place.
 */
enum class TapZones(val id: String) {
    STANDARD("standard"),
    SWAPPED("swapped"),
    ;

    /**
     * Whether the sides are the other way round.
     *
     * Read against reading direction rather than instead of it: an RTL
     * book already turns forward on the left, and swapping it puts
     * forward back on the right. The composition is in
     * [com.chmouel.liseur.reader.chrome.ReaderTapZones.forward].
     */
    val swapped: Boolean get() = this == SWAPPED

    companion object {
        val Default = STANDARD

        fun fromId(id: String?): TapZones = entries.firstOrNull { it.id == id } ?: Default
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
 * @param tapZones Which side of a paginated page turns forward.
 * @param pinchToResize A two-finger pinch on the page changes the reading
 *   font size. On by default; it is here for a grip that produces stray
 *   two-finger touches, since a stray resize changes how every page looks
 *   from then on. Pinching an image to enlarge it is not covered: that one
 *   is one visible thing, dismissed with a tap.
 * @param resumeLastBook Opening the app goes back into the book you were in.
 * @param keepScreenOn The screen stays awake while a book is open. On by
 *   default for new installs, and can be turned off per reader or per
 *   book.
 * @param scrollMode Books are read by scrolling rather than by turning
 *   pages. The default for the whole library; a book read the other way
 *   is set apart from inside it.
 * @param librarySort How the library grid is arranged.
 * @param librarySortReversed The library order read back to front.
 * @param libraryFilters What the library grid is narrowed to.
 * @param eInkMode Whether to drop animation for an electronic paper screen.
 * @param colorEInk Keep the small useful colour palette on a colour e-paper
 *   panel. Ignored while e-ink mode is inactive.
 * @param vendorRefresh Whether to drive the panel through the maker's own
 *   screen controller where the device has one. Off until asked for: it
 *   is reached by reflection into firmware that differs between devices
 *   sold under the same name, so it is a thing the reader turns on and
 *   sees the result of, not a thing done to them.
 * @param definitionTarget Whether Define opens Liseur's definition card or
 *   sends the text to another app.
 * @param dictionaryLookupEnabled Whether Define may ask a dictionary server
 *   for a definition. Off until asked for, because that server is the one
 *   thing the app talks to that the reader did not choose themselves.
 * @param dictionaryBaseUrl The site definitions are fetched from. Any
 *   Wiktionary works, so a reader can pick their own language's edition or
 *   a mirror instead of the default.
 * @param highlightPalette Which colours the bar over a selected passage
 *   offers, and the configured default for a plain highlight. A passage note
 *   uses the first colour in that bar's stable order, or the default when
 *   the bar is empty.
 * @param readAloudVoice The Gemini voice reading aloud uses, by name, or
 *   null for the engine's default. Only builds that can read aloud offer it.
 * @param readAloudModel The Gemini speech model reading aloud uses, by name,
 *   or null for the engine's default.
 * @param readAloudProvider Which speech service reads aloud, by id, or null
 *   for the build's default.
 * @param speechServerUrl The OpenAI-compatible speech service reading aloud
 *   uses, as typed, or null for none.
 * @param speechServerVoice The voice asked of that service, by name, or null
 *   for none chosen yet.
 * @param speechServerModel The model asked of that service, by name, or null
 *   for none chosen yet.
 * @param speechServerVoices The voices of that service the reader wants
 *   offered, by name; empty offers every voice it lists.
 * @param readAloudSpeed How fast reading aloud plays, 1 being as the voice
 *   speaks.
 * @param deviceVoice The voice of the device's own speech engine reading
 *   aloud uses, by the engine's name for it, or null for the engine's
 *   default.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.Default,
    val dynamicColor: Boolean = true,
    val volumeKeysTurnPages: Boolean = true,
    val tapZones: TapZones = TapZones.Default,
    val pinchToResize: Boolean = true,
    val resumeLastBook: Boolean = true,
    val keepScreenOn: Boolean = true,
    val scrollMode: Boolean = false,
    val librarySort: LibrarySort = LibrarySort.Default,
    val librarySortReversed: Boolean = false,
    val libraryFilters: LibraryFilters = LibraryFilters.None,
    val eInkMode: EInkMode = EInkMode.Default,
    val colorEInk: Boolean = false,
    val vendorRefresh: Boolean = false,
    val definitionTarget: DefinitionTarget = DefinitionTarget.Default,
    val dictionaryLookupEnabled: Boolean = false,
    val dictionaryBaseUrl: String = DictionaryUrl.DEFAULT_BASE_URL,
    val uploadPolicy: UploadPolicy = UploadPolicy.Default,
    val statsRange: StatsRange = StatsRange.Default,
    val highlightPalette: HighlightPalette = HighlightPalette(),
    val readAloudVoice: String? = null,
    val readAloudModel: String? = null,
    val readAloudProvider: String? = null,
    val speechServerUrl: String? = null,
    val speechServerVoice: String? = null,
    val speechServerModel: String? = null,
    val speechServerVoices: Set<String> = emptySet(),
    val speechServerUnsettledUrl: String? = null,
    val readAloudSpeed: Float = 1f,
    val deviceVoice: String? = null,
    /** The voice chosen for each language, per speech service; see [VoicePreference]. */
    val voicePreferences: List<VoicePreference> = emptyList(),
)

/**
 * What to do with a book that arrives on the device when the connected
 * server accepts uploads.
 *
 * The default is [ASK] because sending a book nobody asked to send, over
 * whatever connection happens to be up, is the one way this feature can
 * cost a reader something.
 */
enum class UploadPolicy(val id: String) {
    ASK("ask"),
    ALWAYS("always"),
    NEVER("never"),
    ;

    companion object {
        val Default = ASK

        fun fromId(id: String?): UploadPolicy = entries.firstOrNull { it.id == id } ?: Default
    }
}

private val Context.appSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
)

/** Persists [AppSettings]. */
class AppSettingsRepository(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.appSettingsStore)

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys_turn_pages")
        val TAP_ZONES = stringPreferencesKey("tap_zones")
        val PINCH_TO_RESIZE = booleanPreferencesKey("pinch_to_resize")
        val RESUME_LAST_BOOK = booleanPreferencesKey("resume_last_book")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SCROLL_MODE = booleanPreferencesKey("scroll_mode")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val LIBRARY_SORT_REVERSED = booleanPreferencesKey("library_sort_reversed")
        val LIBRARY_FILTERS = stringPreferencesKey("library_filters")
        val LIBRARY_GROUP_BY_SERIES = booleanPreferencesKey("library_group_by_series")
        val EINK_MODE = stringPreferencesKey("eink_mode")
        val COLOR_EINK = booleanPreferencesKey("color_eink")
        val VENDOR_REFRESH = booleanPreferencesKey("vendor_refresh")
        val DEFINITION_TARGET = stringPreferencesKey("definition_target")
        val DICTIONARY_ENABLED = booleanPreferencesKey("dictionary_lookup_enabled")
        val DICTIONARY_BASE_URL = stringPreferencesKey("dictionary_base_url")
        val ACCOUNT_LOST = booleanPreferencesKey("calibre_account_lost_to_restore")
        val UPLOAD_POLICY = stringPreferencesKey("upload_policy")
        val STATS_RANGE = stringPreferencesKey("stats_range")
        val HIGHLIGHT_TINTS = stringSetPreferencesKey("highlight_tints_offered")
        val HIGHLIGHT_TINT_DEFAULT = stringPreferencesKey("highlight_tint_default")
        val CATALOG_PARTIAL_DISMISSED = stringPreferencesKey("catalog_partial_dismissed")
        val READ_ALOUD_VOICE = stringPreferencesKey("read_aloud_voice")
        val READ_ALOUD_MODEL = stringPreferencesKey("read_aloud_model")
        val READ_ALOUD_PROVIDER = stringPreferencesKey("read_aloud_provider")
        val SPEECH_SERVER_URL = stringPreferencesKey("speech_server_url")
        val SPEECH_SERVER_VOICE = stringPreferencesKey("speech_server_voice")
        val SPEECH_SERVER_MODEL = stringPreferencesKey("speech_server_model")
        val SPEECH_SERVER_VOICES = stringSetPreferencesKey("speech_server_voices")

        /** A server address saved whose model and voice are not yet chosen from its lists; never backed up. */
        val SPEECH_SERVER_UNSETTLED_URL = stringPreferencesKey("speech_server_unsettled_url")
        val READ_ALOUD_SPEED = floatPreferencesKey("read_aloud_speed")
        val READ_ALOUD_DEVICE_VOICE = stringPreferencesKey("read_aloud_device_voice")
        val READ_ALOUD_VOICE_PREFERENCES = stringPreferencesKey("read_aloud_voice_preferences")
    }

    /**
     * The catalog address whose part-read notice has been put away.
     *
     * Kept apart from [settings] because it is an answer to one message
     * rather than a preference, and kept as an address rather than a
     * flag so a reader who later points Liseur somewhere else is told
     * about that catalog.
     */
    val catalogPartialDismissedFor: Flow<String?> =
        store.data.map { it[Keys.CATALOG_PARTIAL_DISMISSED] }

    suspend fun setCatalogPartialDismissedFor(catalogUrl: String?) {
        store.edit { prefs ->
            if (catalogUrl == null) {
                prefs.remove(Keys.CATALOG_PARTIAL_DISMISSED)
            } else {
                prefs[Keys.CATALOG_PARTIAL_DISMISSED] = catalogUrl
            }
        }
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
        store.data.map { it[Keys.ACCOUNT_LOST] ?: false }

    suspend fun setAccountLostToRestore(lost: Boolean) {
        store.edit { it[Keys.ACCOUNT_LOST] = lost }
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            themeMode = ThemeMode.fromId(p[Keys.THEME_MODE]),
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            volumeKeysTurnPages = p[Keys.VOLUME_KEYS] ?: true,
            tapZones = TapZones.fromId(p[Keys.TAP_ZONES]),
            pinchToResize = p[Keys.PINCH_TO_RESIZE] ?: true,
            resumeLastBook = p[Keys.RESUME_LAST_BOOK] ?: true,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
            scrollMode = p[Keys.SCROLL_MODE] ?: false,
            librarySort = LibrarySort.fromId(p[Keys.LIBRARY_SORT]),
            librarySortReversed = p[Keys.LIBRARY_SORT_REVERSED] ?: false,
            libraryFilters = LibraryFilters(
                options = LibraryFilters.parse(p[Keys.LIBRARY_FILTERS]),
                groupBySeries = p[Keys.LIBRARY_GROUP_BY_SERIES] ?: true,
            ),
            eInkMode = EInkMode.fromId(p[Keys.EINK_MODE]),
            colorEInk = p[Keys.COLOR_EINK] ?: false,
            vendorRefresh = p[Keys.VENDOR_REFRESH] ?: false,
            definitionTarget = DefinitionTarget.fromId(p[Keys.DEFINITION_TARGET]),
            dictionaryLookupEnabled = p[Keys.DICTIONARY_ENABLED] ?: false,
            dictionaryBaseUrl = p[Keys.DICTIONARY_BASE_URL]?.let(DictionaryUrl::normalise)
                ?: DictionaryUrl.DEFAULT_BASE_URL,
            uploadPolicy = UploadPolicy.fromId(p[Keys.UPLOAD_POLICY]),
            statsRange = StatsRange.fromId(p[Keys.STATS_RANGE]),
            highlightPalette = HighlightPalette.of(
                offeredNames = p[Keys.HIGHLIGHT_TINTS],
                defaultName = p[Keys.HIGHLIGHT_TINT_DEFAULT],
            ),
            readAloudVoice = p[Keys.READ_ALOUD_VOICE],
            readAloudModel = p[Keys.READ_ALOUD_MODEL],
            readAloudProvider = p[Keys.READ_ALOUD_PROVIDER],
            speechServerUrl = p[Keys.SPEECH_SERVER_URL],
            speechServerVoice = p[Keys.SPEECH_SERVER_VOICE],
            speechServerModel = p[Keys.SPEECH_SERVER_MODEL],
            speechServerVoices = p[Keys.SPEECH_SERVER_VOICES].orEmpty(),
            speechServerUnsettledUrl = p[Keys.SPEECH_SERVER_UNSETTLED_URL],
            readAloudSpeed = p[Keys.READ_ALOUD_SPEED] ?: 1f,
            deviceVoice = p[Keys.READ_ALOUD_DEVICE_VOICE],
            voicePreferences = VoicePreferences.decode(p[Keys.READ_ALOUD_VOICE_PREFERENCES]),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /** Stored, user-facing preferences only; account and transient state stay private. */
    suspend fun backupValues(): JSONObject = store.data.first().backupJson(APP_BACKUP_TYPES.keys)

    /** Applies validated allowlisted values in one DataStore edit. */
    suspend fun restoreBackupValues(values: JSONObject) {
        store.edit { values.applyBackupJson(it, APP_BACKUP_TYPES) }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.THEME_MODE] = mode.id }
    }

    suspend fun setStatsRange(range: StatsRange) {
        store.edit { it[Keys.STATS_RANGE] = range.id }
    }

    suspend fun setUploadPolicy(policy: UploadPolicy) {
        store.edit { it[Keys.UPLOAD_POLICY] = policy.id }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setVolumeKeysTurnPages(enabled: Boolean) {
        store.edit { it[Keys.VOLUME_KEYS] = enabled }
    }

    suspend fun setTapZones(zones: TapZones) {
        store.edit { it[Keys.TAP_ZONES] = zones.id }
    }

    suspend fun setPinchToResize(enabled: Boolean) {
        store.edit { it[Keys.PINCH_TO_RESIZE] = enabled }
    }

    suspend fun setResumeLastBook(enabled: Boolean) {
        store.edit { it[Keys.RESUME_LAST_BOOK] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        store.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setScrollMode(enabled: Boolean) {
        store.edit { it[Keys.SCROLL_MODE] = enabled }
    }

    suspend fun setLibrarySort(sort: LibrarySort) {
        store.edit { it[Keys.LIBRARY_SORT] = sort.id }
    }

    suspend fun setLibrarySortReversed(reversed: Boolean) {
        store.edit { it[Keys.LIBRARY_SORT_REVERSED] = reversed }
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
        store.edit { p ->
            val filters = edit(
                LibraryFilters(
                    options = LibraryFilters.parse(p[Keys.LIBRARY_FILTERS]),
                    groupBySeries = p[Keys.LIBRARY_GROUP_BY_SERIES] ?: true,
                ),
            )
            p[Keys.LIBRARY_FILTERS] = filters.serialise()
            p[Keys.LIBRARY_GROUP_BY_SERIES] = filters.groupBySeries
        }
    }

    suspend fun setEInkMode(mode: EInkMode) {
        store.edit { it[Keys.EINK_MODE] = mode.id }
    }

    suspend fun setColorEInk(enabled: Boolean) {
        store.edit { it[Keys.COLOR_EINK] = enabled }
    }

    suspend fun setVendorRefresh(enabled: Boolean) {
        store.edit { it[Keys.VENDOR_REFRESH] = enabled }
    }

    suspend fun setDefinitionTarget(target: DefinitionTarget) {
        store.edit { it[Keys.DEFINITION_TARGET] = target.id }
    }

    suspend fun setDictionaryLookupEnabled(enabled: Boolean) {
        store.edit { it[Keys.DICTIONARY_ENABLED] = enabled }
    }

    /**
     * Stores the dictionary site, normalised. Anything that cannot be a
     * URL puts the default back rather than leaving the reader with a
     * dictionary that silently never answers.
     */
    suspend fun setDictionaryBaseUrl(url: String) {
        val normalised = DictionaryUrl.normalise(url) ?: DictionaryUrl.DEFAULT_BASE_URL
        store.edit { it[Keys.DICTIONARY_BASE_URL] = normalised }
    }

    /**
     * Offers [tint] when it was not offered, and stops offering it when
     * it was.
     *
     * The toggle happens inside the edit, against the set that is
     * actually stored, as `editLibraryFilters` does: two swatches tapped
     * faster than DataStore writes would otherwise both be worked out
     * from the same stale set, and the second would undo the first.
     *
     * The empty set is a legal answer, and a different one from never
     * having chosen: the bar then offers a plain Highlight in the
     * default colour instead of chips. Only names this build knows are
     * written, so nothing can be stored that has no swatch to untick it
     * with.
     */
    suspend fun toggleHighlightTint(tint: HighlightTint) {
        store.edit { p ->
            val next = HighlightPalette.of(p[Keys.HIGHLIGHT_TINTS], null).toggled(tint)
            p[Keys.HIGHLIGHT_TINTS] = next.offered.map { it.name }.toSet()
        }
    }

    suspend fun setHighlightDefaultTint(tint: HighlightTint) {
        store.edit { it[Keys.HIGHLIGHT_TINT_DEFAULT] = tint.name }
    }

    suspend fun setReadAloudVoice(name: String) {
        store.edit { it[Keys.READ_ALOUD_VOICE] = name }
    }

    /** Saves the Gemini speech model; blank goes back to the default. */
    suspend fun setReadAloudModel(name: String) {
        store.edit { if (name.isBlank()) it.remove(Keys.READ_ALOUD_MODEL) else it[Keys.READ_ALOUD_MODEL] = name }
    }

    suspend fun setReadAloudProvider(id: String) {
        store.edit { it[Keys.READ_ALOUD_PROVIDER] = id }
    }

    /** Stores the speech service's address as typed, or forgets it when [url] is blank. */
    suspend fun setSpeechServerUrl(url: String) = setOrRemove(Keys.SPEECH_SERVER_URL, url)

    /**
     * Stores a new speech service address together with the mark that
     * its model and voice are still to be chosen from its own lists, so
     * that choice survives leaving the screen or the app.
     */
    suspend fun setSpeechServerUrlUnsettled(url: String) {
        val trimmed = url.trim()
        store.edit { p ->
            if (trimmed.isEmpty()) {
                p.remove(Keys.SPEECH_SERVER_URL)
                p.remove(Keys.SPEECH_SERVER_UNSETTLED_URL)
            } else {
                p[Keys.SPEECH_SERVER_URL] = trimmed
                p[Keys.SPEECH_SERVER_UNSETTLED_URL] = trimmed
            }
        }
    }

    /** Clears the mark left by [setSpeechServerUrlUnsettled], if it is still [url]'s. */
    suspend fun settleSpeechServer(url: String) {
        store.edit { p -> if (p[Keys.SPEECH_SERVER_UNSETTLED_URL] == url) p.remove(Keys.SPEECH_SERVER_UNSETTLED_URL) }
    }

    /** Stores the speech service's voice, or forgets it when [name] is blank. */
    suspend fun setSpeechServerVoice(name: String) = setOrRemove(Keys.SPEECH_SERVER_VOICE, name)

    /** Stores the device's voice, or goes back to the engine's default when [name] is blank. */
    suspend fun setDeviceVoice(name: String) = setOrRemove(Keys.READ_ALOUD_DEVICE_VOICE, name)

    /** Stores the speech service's model, or forgets it when [name] is blank. */
    suspend fun setSpeechServerModel(name: String) = setOrRemove(Keys.SPEECH_SERVER_MODEL, name)

    /**
     * Stores the speech service's model and voice in one write, so nothing
     * ever reads the new model with the old model's voice.
     */
    suspend fun setSpeechServerModelAndVoice(model: String, voice: String) {
        val m = model.trim()
        val v = voice.trim()
        store.edit { p ->
            if (m.isEmpty()) p.remove(Keys.SPEECH_SERVER_MODEL) else p[Keys.SPEECH_SERVER_MODEL] = m
            if (v.isEmpty()) p.remove(Keys.SPEECH_SERVER_VOICE) else p[Keys.SPEECH_SERVER_VOICE] = v
        }
    }

    /** Stores the speech service's voices to offer, or offers them all again when [names] is empty. */
    suspend fun setSpeechServerVoices(names: Set<String>) {
        store.edit { p -> if (names.isEmpty()) p.remove(Keys.SPEECH_SERVER_VOICES) else p[Keys.SPEECH_SERVER_VOICES] = names }
    }

    /**
     * One write of a read-aloud voice: what [block] reads is what is
     * stored when it runs, and what it sets is saved together, or not at
     * all when it returns false. Returns what [block] returned.
     */
    suspend fun editReadAloudVoice(block: ReadAloudVoiceEdit.() -> Boolean): Boolean {
        var saved = false
        store.edit { p ->
            val edit = ReadAloudVoiceEdit(p)
            saved = edit.block()
            if (saved) edit.apply()
        }
        return saved
    }

    /** The stored read-aloud values a voice write checks, and the changes it makes. */
    class ReadAloudVoiceEdit internal constructor(private val p: MutablePreferences) {
        val geminiModel: String? get() = p[Keys.READ_ALOUD_MODEL]
        val serverUrl: String? get() = p[Keys.SPEECH_SERVER_URL]
        val serverModel: String? get() = p[Keys.SPEECH_SERVER_MODEL]

        private val changes = mutableListOf<(MutablePreferences) -> Unit>()

        fun setGeminiVoice(name: String) = set(Keys.READ_ALOUD_VOICE, name)

        fun setServerVoice(name: String) = set(Keys.SPEECH_SERVER_VOICE, name)

        fun setDeviceVoice(name: String) = set(Keys.READ_ALOUD_DEVICE_VOICE, name)

        /** Remembers [preference] for its language, keeping every other language's. */
        fun remember(preference: VoicePreference) {
            changes += { p ->
                val stored = VoicePreferences.decode(p[Keys.READ_ALOUD_VOICE_PREFERENCES])
                p[Keys.READ_ALOUD_VOICE_PREFERENCES] = VoicePreferences.encode(VoicePreferences.with(stored, preference))
            }
        }

        private fun set(key: Preferences.Key<String>, value: String) {
            val trimmed = value.trim()
            changes += { p -> if (trimmed.isEmpty()) p.remove(key) else p[key] = trimmed }
        }

        internal fun apply() = changes.forEach { it(p) }
    }

    suspend fun setReadAloudSpeed(speed: Float) {
        store.edit { it[Keys.READ_ALOUD_SPEED] = speed }
    }

    private suspend fun setOrRemove(key: Preferences.Key<String>, value: String) {
        val trimmed = value.trim()
        store.edit { p -> if (trimmed.isEmpty()) p.remove(key) else p[key] = trimmed }
    }

    /**
     * The offered set exactly as stored, with null for a reader who
     * never chose one.
     *
     * [AppSettings.highlightPalette] cannot answer this: it resolves an
     * absent set to the default three, so through it a reader who never
     * chose looks identical to one who chose exactly those three.
     * The settings backup has to tell them apart, or "never chose" is
     * stored as a choice and comes back as one on a restore.
     */
    suspend fun offeredHighlightTintNames(): Set<String>? =
        store.data.first()[Keys.HIGHLIGHT_TINTS]

    /**
     * Replaces the offered set outright, or clears it when [names] is
     * null so the reader counts as never having chosen.
     *
     * Unlike [toggleHighlightTint] this says where to end up rather than
     * which swatch was tapped, which is what an incoming palette is:
     * toggling its way there would publish every set on the route, and
     * one of those is the empty set, which is its own answer rather than
     * a step towards another.
     */
    suspend fun setOfferedHighlightTints(names: Set<String>?) {
        store.edit { p ->
            if (names == null) p.remove(Keys.HIGHLIGHT_TINTS) else p[Keys.HIGHLIGHT_TINTS] = names
        }
    }
}

internal val APP_BACKUP_TYPES = mapOf(
    "theme_mode" to BackupValueType.STRING, "dynamic_color" to BackupValueType.BOOLEAN,
    "volume_keys_turn_pages" to BackupValueType.BOOLEAN, "tap_zones" to BackupValueType.STRING,
    "pinch_to_resize" to BackupValueType.BOOLEAN, "resume_last_book" to BackupValueType.BOOLEAN,
    "keep_screen_on" to BackupValueType.BOOLEAN, "scroll_mode" to BackupValueType.BOOLEAN,
    "library_sort" to BackupValueType.STRING, "library_sort_reversed" to BackupValueType.BOOLEAN,
    "library_filters" to BackupValueType.STRING, "library_group_by_series" to BackupValueType.BOOLEAN,
    "eink_mode" to BackupValueType.STRING, "color_eink" to BackupValueType.BOOLEAN,
    "vendor_refresh" to BackupValueType.BOOLEAN, "definition_target" to BackupValueType.STRING,
    "dictionary_lookup_enabled" to BackupValueType.BOOLEAN, "dictionary_base_url" to BackupValueType.STRING,
    "upload_policy" to BackupValueType.STRING, "stats_range" to BackupValueType.STRING,
    "highlight_tints_offered" to BackupValueType.STRING_SET,
    "highlight_tint_default" to BackupValueType.STRING,
    "read_aloud_voice" to BackupValueType.STRING, "read_aloud_model" to BackupValueType.STRING,
    "read_aloud_provider" to BackupValueType.STRING,
    "speech_server_url" to BackupValueType.STRING, "speech_server_voice" to BackupValueType.STRING,
    "speech_server_model" to BackupValueType.STRING,
    "speech_server_voices" to BackupValueType.STRING_SET,
    "read_aloud_speed" to BackupValueType.FLOAT,
    "read_aloud_device_voice" to BackupValueType.STRING,
    "read_aloud_voice_preferences" to BackupValueType.STRING,
)
