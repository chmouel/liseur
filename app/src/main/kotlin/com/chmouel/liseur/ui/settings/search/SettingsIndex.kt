package com.chmouel.liseur.ui.settings.search

import androidx.annotation.StringRes
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.navigation.Route

/**
 * What decides whether a row is on its page right now, for the rows that
 * come and go. Search leaves out what the page would not show.
 */
data class SearchContext(
    val dynamicColorAvailable: Boolean = true,
    val columnsAvailable: Boolean = false,
    val eInk: Boolean = false,
    val vendorRefresh: Boolean = false,
    val builtInDictionary: Boolean = true,
    val dictionaryLookup: Boolean = false,
    val orientationLock: Boolean = true,
)

/**
 * One row a reader can find from the settings search.
 *
 * [title] and [summary] are the strings the row itself shows, so a match
 * reads the same in the results as on the page. A row whose description
 * changes with its state has no [summary] here. [advanced] marks a row
 * inside its page's closed Advanced group, which a result opens.
 */
class SettingsEntry(
    val key: String,
    val page: Route,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int? = null,
    @param:StringRes val section: Int? = null,
    val advanced: Boolean = false,
    val shown: (SearchContext) -> Boolean = { true },
) {
    override fun toString(): String = key
}

/**
 * Every searchable row, in the order the pages show them.
 *
 * A row added to a settings page needs an entry here, and the row needs
 * `Modifier.settingsAnchor` with it, or search will not find it.
 */
object SettingsEntries {
    private val registered = mutableListOf<SettingsEntry>()

    private fun entry(
        key: String,
        page: Route,
        @StringRes title: Int,
        @StringRes summary: Int? = null,
        @StringRes section: Int? = null,
        advanced: Boolean = false,
        shown: (SearchContext) -> Boolean = { true },
    ) = SettingsEntry(key, page, title, summary, section, advanced, shown).also { registered += it }

    private fun advanced(
        key: String,
        page: Route,
        @StringRes title: Int,
        @StringRes summary: Int? = null,
        shown: (SearchContext) -> Boolean = { true },
    ) = entry(key, page, title, summary, R.string.settings_advanced, advanced = true, shown = shown)

    // Settings
    val Theme = entry("theme", Route.Settings, R.string.settings_theme, section = R.string.settings_appearance)
    val LibraryStyle = entry(
        "library_style", Route.Settings, R.string.library_style, section = R.string.settings_appearance,
    )
    val ReadingAppearance = entry(
        "reading_appearance", Route.Settings, R.string.settings_reading_appearance, section = R.string.settings_reading,
    )
    val ReadingNavigation = entry(
        "reading_navigation", Route.Settings, R.string.settings_reading_navigation, section = R.string.settings_reading,
    )
    val ReadAloud = entry(
        "read_aloud", Route.Settings, R.string.read_aloud_settings_title, section = R.string.settings_reading,
    )
    val ServerAccount = entry(
        "server_account", Route.Settings, R.string.server_account, R.string.settings_account_detail,
        R.string.settings_library,
    )
    val LibraryFolders = entry(
        "library_folders", Route.Settings, R.string.library_folders, section = R.string.settings_library,
    )
    val Translation = entry(
        "translation", Route.Settings, R.string.translation_settings_title, section = R.string.settings_intelligence,
    )
    val Companion = entry(
        "companion", Route.Settings, R.string.companion_settings_title, section = R.string.settings_intelligence,
    )
    val Services = entry(
        "services", Route.Settings, R.string.services_title, R.string.services_entry_summary,
        R.string.settings_intelligence,
    )
    val DynamicColor = advanced(
        "dynamic_color", Route.Settings, R.string.settings_dynamic_color, R.string.settings_dynamic_color_detail,
        shown = { it.dynamicColorAvailable },
    )
    val AppLanguage = advanced("app_language", Route.Settings, R.string.app_language)
    val HiddenBooks = advanced(
        "hidden_books", Route.Settings, R.string.hidden_books, R.string.hidden_books_summary,
    )
    val Backup = advanced(
        "backup", Route.Settings, R.string.settings_backup_title, R.string.settings_backup_detail,
    )
    val About = entry("about", Route.Settings, R.string.about_open, section = R.string.settings_about)

    // Reading appearance
    val ReaderTheme = entry("reader_theme", Route.ReadingAppearance, R.string.settings_theme)
    val FontSize = entry("font_size", Route.ReadingAppearance, R.string.reader_size)
    val Brightness = entry("brightness", Route.ReadingAppearance, R.string.reader_brightness)
    val Font = entry("font", Route.ReadingAppearance, R.string.reader_font)
    val LineSpacing = advanced("line_spacing", Route.ReadingAppearance, R.string.reader_line_spacing)
    val Margins = advanced("margins", Route.ReadingAppearance, R.string.reader_margins)
    val Columns = advanced("columns", Route.ReadingAppearance, R.string.reader_columns, shown = { it.columnsAvailable })
    val TextAlign = advanced("text_align", Route.ReadingAppearance, R.string.reader_text_align)
    val Hyphens = advanced("hyphens", Route.ReadingAppearance, R.string.reader_hyphens)
    val FontWeight = advanced("font_weight", Route.ReadingAppearance, R.string.reader_font_weight)
    val LetterSpacing = advanced("letter_spacing", Route.ReadingAppearance, R.string.reader_letter_spacing)
    val WordSpacing = advanced("word_spacing", Route.ReadingAppearance, R.string.reader_word_spacing)
    val ParagraphSpacing = advanced("paragraph_spacing", Route.ReadingAppearance, R.string.reader_paragraph_spacing)
    val Footer = advanced("footer", Route.ReadingAppearance, R.string.reader_progress)
    val SelectionToolbar = advanced("selection_toolbar", Route.ReadingAppearance, R.string.selection_arrange)

    // Reading navigation
    val Resume = entry(
        "resume", Route.ReadingNavigation, R.string.settings_resume, R.string.settings_resume_detail,
        R.string.settings_reading,
    )
    val ScrollMode = entry(
        "scroll_mode", Route.ReadingNavigation, R.string.settings_scroll_mode, R.string.settings_scroll_mode_detail,
        R.string.settings_reading,
    )
    val EInk = entry(
        "eink", Route.ReadingNavigation, R.string.settings_eink, R.string.settings_eink_detail, R.string.settings_screen,
    )
    val ColorEInk = entry(
        "color_eink", Route.ReadingNavigation, R.string.settings_color_eink, R.string.settings_color_eink_detail,
        R.string.settings_screen, shown = { it.eInk },
    )
    val VendorRefresh = entry(
        "vendor_refresh", Route.ReadingNavigation, R.string.settings_vendor_refresh,
        section = R.string.settings_screen, shown = { it.vendorRefresh },
    )
    val DefineWith = entry(
        "define_with", Route.ReadingNavigation, R.string.settings_define_with, section = R.string.settings_dictionary,
    )
    val DictionaryLookup = entry(
        "dictionary_lookup", Route.ReadingNavigation, R.string.settings_dictionary_lookup,
        R.string.settings_dictionary_lookup_detail, R.string.settings_dictionary, shown = { it.builtInDictionary },
    )
    val DictionarySite = entry(
        "dictionary_site", Route.ReadingNavigation, R.string.settings_dictionary_site,
        R.string.settings_dictionary_site_detail, R.string.settings_dictionary,
        shown = { it.builtInDictionary && it.dictionaryLookup },
    )
    val DictionaryAltLanguage = entry(
        "dictionary_alt_language", Route.ReadingNavigation, R.string.settings_dictionary_alt_language,
        R.string.settings_dictionary_alt_language_detail, R.string.settings_dictionary,
        shown = { it.builtInDictionary && it.dictionaryLookup },
    )
    val DictionaryOnLongPress = entry(
        "dictionary_on_long_press", Route.ReadingNavigation, R.string.settings_dictionary_on_long_press,
        R.string.settings_dictionary_on_long_press_detail, R.string.settings_dictionary,
        shown = { it.builtInDictionary },
    )
    val PageTurnStyle = advanced("page_turn_style", Route.ReadingNavigation, R.string.reader_page_turn_style)
    val VolumeKeys = advanced(
        "volume_keys", Route.ReadingNavigation, R.string.settings_volume_keys, R.string.settings_volume_keys_detail,
    )
    val PageTaps = advanced("page_taps", Route.ReadingNavigation, R.string.settings_page_taps)
    val PinchToResize = advanced(
        "pinch_to_resize", Route.ReadingNavigation, R.string.settings_pinch_to_resize,
        R.string.settings_pinch_to_resize_detail,
    )
    val BrightnessEdge = advanced(
        "brightness_edge", Route.ReadingNavigation, R.string.settings_brightness_edge,
        R.string.settings_brightness_edge_detail,
    )
    val ReaderOrientation = advanced(
        "reader_orientation", Route.ReadingNavigation, R.string.settings_reader_orientation,
        R.string.settings_reader_orientation_detail, shown = { it.orientationLock },
    )
    val LockFooter = advanced(
        "lock_footer", Route.ReadingNavigation, R.string.settings_lock_footer, R.string.settings_lock_footer_detail,
    )
    val KeepScreenOn = advanced(
        "keep_screen_on", Route.ReadingNavigation, R.string.settings_keep_screen_on,
        R.string.settings_keep_screen_on_detail,
    )

    // Page taps
    val TapZones = entry("tap_zones", Route.PageTaps, R.string.settings_tap_zones, R.string.settings_tap_zones_detail)
    val TapSideWidth = entry(
        "tap_side_width", Route.PageTaps, R.string.settings_tap_side_width, R.string.settings_tap_side_width_detail,
    )

    // Read aloud
    val ReadAloudProvider = entry("read_aloud_provider", Route.ReadAloud, R.string.read_aloud_settings_provider)
    val SentencesPerRequest = entry(
        "sentences_per_request", Route.ReadAloud, R.string.read_aloud_settings_sentences_per_request,
        R.string.read_aloud_settings_sentences_per_request_detail, R.string.read_aloud_settings_playback,
    )
    val ReadAloudContext = advanced(
        "read_aloud_context", Route.ReadAloud, R.string.read_aloud_context_switch,
        R.string.read_aloud_context_switch_detail,
    )

    // Translation
    val TranslationProvider = entry("translation_provider", Route.Translation, R.string.translation_settings_provider)
    val TranslationTarget = entry("translation_target", Route.Translation, R.string.translation_settings_target)
    val WholeBookTranslations = entry("whole_book_translations", Route.Translation, R.string.whole_book_title)
    val TranslationContext = advanced("translation_context", Route.Translation, R.string.translation_settings_context)
    val TranslationBookDetails = advanced(
        "translation_book_details", Route.Translation, R.string.translation_book_switch,
    )

    // Reading companion
    val CompanionDetail = entry(
        "companion_detail", Route.ReadingCompanion, R.string.companion_settings_detail,
        R.string.companion_settings_detail_summary,
    )
    val CompanionRecaps = advanced(
        "companion_recaps", Route.ReadingCompanion, R.string.companion_settings_recaps,
        R.string.companion_settings_recaps_summary,
    )
    val CompanionStoryAuto = advanced(
        "companion_story_auto", Route.ReadingCompanion, R.string.companion_settings_story_auto,
        R.string.companion_settings_story_auto_summary,
    )
    val CompanionCodex = advanced(
        "companion_codex", Route.ReadingCompanion, R.string.codex_settings_enabled, R.string.codex_settings_summary,
    )

    // Services
    val ServicesServers = entry(
        "services_servers", Route.Services, R.string.services_add, R.string.services_add_detail,
        R.string.services_servers,
    )

    // Backup
    val BackupExport = entry(
        "backup_export", Route.SettingsBackup, R.string.settings_backup_export,
        R.string.settings_backup_export_detail,
    )
    val BackupRestore = entry(
        "backup_restore", Route.SettingsBackup, R.string.settings_backup_restore,
        R.string.settings_backup_restore_detail,
    )
    val BooksExport = entry(
        "books_export", Route.SettingsBackup, R.string.books_export_title, R.string.books_export_detail,
    )

    /** Every page's rows, then [flavor]'s: rows only one build shows. */
    fun all(flavor: List<SettingsEntry> = emptyList()): List<SettingsEntry> = registered + flavor
}

/** The title a settings page goes by in a result's path. */
@get:StringRes
val Route.settingsTitle: Int
    get() = when (this) {
        Route.ReadingAppearance -> R.string.settings_reading_appearance
        Route.ReadingNavigation -> R.string.settings_reading_navigation
        Route.PageTaps -> R.string.settings_page_taps
        Route.SelectionToolbar -> R.string.selection_arrange
        Route.ReadAloud -> R.string.read_aloud_settings_title
        Route.Translation -> R.string.translation_settings_title
        Route.ReadingCompanion -> R.string.companion_settings_title
        Route.Services -> R.string.services_title
        Route.SettingsBackup -> R.string.settings_backup_title
        else -> R.string.settings
    }
