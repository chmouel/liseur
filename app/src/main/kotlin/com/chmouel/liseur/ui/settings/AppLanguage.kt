package com.chmouel.liseur.ui.settings

import java.util.Locale

/**
 * The languages the app carries translations for, plus following the phone.
 *
 * `tag` is what gets stored and handed to the platform; `null` means the
 * phone decides. Names are written in their own language, because someone
 * looking for theirs recognises Русский sooner than "Russian" in a language
 * they may not read.
 */
enum class AppLanguage(val tag: String?, val nativeName: String?) {
    SYSTEM(null, null),
    ENGLISH("en", "English"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    ITALIAN("it", "Italiano"),
    RUSSIAN("ru", "Русский"),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),
    ;

    companion object {
        /**
         * The entry a stored or platform tag stands for. Regions are ignored
         * (`fr-CA` is French), and Chinese only counts when it would load the
         * simplified strings: an explicit Hans script, or no script with a
         * region that writes simplified. Anything else follows the phone.
         */
        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return SYSTEM
            val locale = Locale.forLanguageTag(tag)
            if (locale.language == "zh") {
                val simplified = locale.script == "Hans" ||
                    (locale.script.isEmpty() && locale.country in SIMPLIFIED_REGIONS)
                return if (simplified) CHINESE_SIMPLIFIED else SYSTEM
            }
            return entries.firstOrNull { it.tag == locale.language } ?: SYSTEM
        }

        private val SIMPLIFIED_REGIONS = setOf("", "CN", "SG", "MY")
    }
}

/**
 * What to hand the platform on Android 13 and up, the first time it runs
 * there: a choice made before the phone was upgraded, kept in our own
 * preference, becomes the platform's — unless the platform already has one,
 * which is newer by definition.
 */
internal fun legacyTagToAdopt(platformTag: String?, savedTag: String?): String? =
    if (platformTag.isNullOrBlank() && !savedTag.isNullOrBlank()) savedTag else null
