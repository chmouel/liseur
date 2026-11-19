package com.chmouel.liseur.insights

import kotlinx.coroutines.flow.StateFlow

enum class UsageEvent(val wireName: String, val feature: String) {
    APP_FOREGROUNDED("app_foregrounded", "app"),
    BOOK_OPENED("book_opened", "reader"),
    READ_ALOUD_STARTED("read_aloud_started", "read_aloud"),
    TRANSLATION_REQUESTED("translation_requested", "translation"),
    VOCABULARY_OPENED("vocabulary_opened", "vocabulary"),
    VOCABULARY_WORD_SAVED("vocabulary_word_saved", "vocabulary"),
    VOCABULARY_REVIEWED("vocabulary_reviewed", "vocabulary"),
    VOCABULARY_REVIEW_UNDONE("vocabulary_review_undone", "vocabulary"),
    COMPANION_OPENED("companion_opened", "companion"),
    COMPANION_PROFILE_OPENED("companion_profile_opened", "companion"),
    COMPANION_RELATIONSHIPS_OPENED("companion_relationships_opened", "companion"),
    COMPANION_STORY_OPENED("companion_story_opened", "companion"),
    COMPANION_RECAP_REQUESTED("companion_recap_requested", "companion"),
    COMPANION_AUTO_RECAP_COMPLETED("companion_auto_recap_completed", "companion"),
    COMPANION_SUMMARY_REQUESTED("companion_summary_requested", "companion"),
    COMPANION_LOOKUP_STARTED("companion_lookup_started", "companion"),
    COMPANION_LOOKUP_SUCCEEDED("companion_lookup_succeeded", "companion"),
    COMPANION_LOOKUP_NETWORK_FAILED("companion_lookup_network_failed", "companion"),
    COMPANION_LOOKUP_SERVICE_FAILED("companion_lookup_service_failed", "companion"),
    COMPANION_LOOKUP_INVALID_RESPONSE("companion_lookup_invalid_response", "companion"),
    COMPANION_LOOKUP_CANCELLED("companion_lookup_cancelled", "companion"),
}

interface UsageReporting {
    val enabled: StateFlow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
    fun capture(event: UsageEvent)
    fun setScreen(screen: String) = Unit
}
