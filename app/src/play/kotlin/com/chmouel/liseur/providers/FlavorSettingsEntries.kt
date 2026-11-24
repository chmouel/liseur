package com.chmouel.liseur.providers

import com.chmouel.liseur.R
import com.chmouel.liseur.ui.navigation.Route
import com.chmouel.liseur.ui.settings.search.SettingsEntry

/** The Play build's own rows, for the settings search. */
internal object PlaySettingsEntries {
    val Reporting = SettingsEntry(
        key = "usage_reporting",
        page = Route.Settings,
        title = R.string.reporting_title,
        summary = R.string.reporting_detail,
        section = R.string.settings_advanced,
        advanced = true,
    )
    val GeminiKey = SettingsEntry(
        key = "gemini_key",
        page = Route.Services,
        title = R.string.services_gemini_key,
        section = R.string.read_aloud_provider_gemini,
    )
    val Hardcover = SettingsEntry(
        key = "hardcover",
        page = Route.Services,
        title = R.string.hardcover_title,
        summary = R.string.hardcover_settings_summary,
        section = R.string.reading_services_title,
    )
    val ReadingServices = SettingsEntry(
        key = "reading_services",
        page = Route.Settings,
        title = R.string.reading_services_title,
        summary = R.string.hardcover_settings_summary,
        section = R.string.settings_library,
    )
    val GoogleDrive = SettingsEntry(
        key = "google_drive",
        page = Route.ServerAccount,
        title = R.string.drive_sync_title,
        summary = R.string.drive_sync_settings_summary,
        section = R.string.server_title,
    )
}

internal val flavorSettingsEntries: List<SettingsEntry> = listOf(
    PlaySettingsEntries.GeminiKey,
    PlaySettingsEntries.GoogleDrive,
    PlaySettingsEntries.ReadingServices,
    PlaySettingsEntries.Hardcover,
    PlaySettingsEntries.Reporting,
)
