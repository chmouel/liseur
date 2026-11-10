package com.chmouel.liseur.providers

import com.chmouel.liseur.R
import com.chmouel.liseur.ui.navigation.Route
import com.chmouel.liseur.ui.settings.search.SettingsEntry

/** The Play build's own rows, for the settings search. */
internal object PlaySettingsEntries {
    val GeminiKey = SettingsEntry(
        key = "gemini_key",
        page = Route.Services,
        title = R.string.services_gemini_key,
        section = R.string.read_aloud_provider_gemini,
    )
}

internal val flavorSettingsEntries: List<SettingsEntry> = listOf(PlaySettingsEntries.GeminiKey)
