package com.chmouel.liseur.insights

import kotlinx.coroutines.flow.StateFlow

enum class UsageEvent(val wireName: String, val feature: String) {
    APP_FOREGROUNDED("app_foregrounded", "app"),
    BOOK_OPENED("book_opened", "reader"),
    READ_ALOUD_STARTED("read_aloud_started", "read_aloud"),
    TRANSLATION_REQUESTED("translation_requested", "translation"),
    COMPANION_OPENED("companion_opened", "companion"),
}

interface UsageReporting {
    val enabled: StateFlow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
    fun capture(event: UsageEvent)
}
