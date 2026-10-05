package com.chmouel.liseur.ui.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/**
 * Where the app language lives and how it reaches the screen.
 *
 * Android 13 and up keep a per-app language themselves: `LocaleManager`
 * stores it, restarts the activities, and shows the same choice in the
 * phone's own settings, so the platform is the only record there. Older
 * phones have nothing of the kind; the choice is a plain preference, read
 * synchronously in each activity's `attachBaseContext` (DataStore cannot be
 * read that early) and laid over the system configuration from there.
 *
 * AppCompat's `setApplicationLocales` is not used: the activities are not
 * AppCompat ones, so it would store nothing.
 */
internal object AppLocales {
    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    private val platformOwnsIt get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun current(context: Context): AppLanguage =
        AppLanguage.fromTag(if (platformOwnsIt) platformTag(context) else savedTag(context))

    /**
     * Applies [language]; the visible activity comes back in it. A language
     * the picker does not list, set from the system or adb, reads as
     * [AppLanguage.SYSTEM], so picking that must still clear it.
     */
    fun apply(activity: Activity, language: AppLanguage) {
        val stored = if (platformOwnsIt) platformTag(activity) else savedTag(activity)
        if (stored == language.tag) return
        if (platformOwnsIt) {
            setPlatformTag(activity, language.tag)
        } else {
            prefs(activity).edit().apply {
                if (language.tag == null) remove(KEY_TAG) else putString(KEY_TAG, language.tag)
            }.commit()
            activity.recreate()
        }
    }

    /**
     * The configuration an activity below Android 13 lays over the system
     * one, or `null` to take the system's as it is. It carries the locale
     * and nothing else, so orientation, night mode and the rest keep
     * following the device after the activity is created. `fontScale` is
     * zeroed because `Configuration()` defaults it to 1, which would
     * otherwise override the reader's system text size.
     */
    fun overrideConfiguration(base: Context): Configuration? {
        val locales = applyProcessDefault(base) ?: return null
        return Configuration().apply {
            fontScale = 0f
            setLocales(locales)
        }
    }

    /**
     * Below Android 13, points the process default locale (what java.util
     * dates, numbers and week starts follow) at the saved language, or back
     * at the system's, and returns the saved one. Called when the process
     * starts, after each configuration change (the platform resets the
     * default there) and from each activity, so work done with no activity
     * on screen, such as a widget redraw, formats the same way as the app;
     * Android 13 does the same for its own per-app language.
     */
    fun applyProcessDefault(context: Context): LocaleList? {
        if (platformOwnsIt) return null
        val locales = savedTag(context)?.let { LocaleList.forLanguageTags(it) }
        LocaleList.setDefault(locales ?: Resources.getSystem().configuration.locales)
        return locales
    }

    /**
     * On Android 13 and up, hands a choice made on an older Android to the
     * platform once and forgets it. Without this, a phone upgraded to 13
     * would quietly go back to the system language.
     */
    fun adoptLegacyChoice(context: Context) {
        if (!platformOwnsIt) return
        val saved = savedTag(context) ?: return
        legacyTagToAdopt(platformTag(context), saved)?.let { setPlatformTag(context, it) }
        prefs(context).edit().remove(KEY_TAG).apply()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun platformTag(context: Context): String? {
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        return if (locales == null || locales.isEmpty) null else locales[0].toLanguageTag()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setPlatformTag(context: Context, tag: String?) {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales =
            tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
    }

    private fun savedTag(context: Context): String? = prefs(context).getString(KEY_TAG, null)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
