package com.chmouel.liseur.data.settings

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Carries settings sync across the move of the default font size from
 * [ReaderPrefs.LEGACY_DEFAULT_FONT_SIZE] to [ReaderPrefs.DEFAULT_FONT_SIZE].
 *
 * A reader who never chose a size reads at the new one after updating,
 * which to the change collector looks like an edit made at that moment.
 * [SettingsSyncRepository.adoptMovedDefault] says otherwise, and it has
 * to before the collector's first look and before the first sync pass,
 * so both wait on [ensure].
 */
class FontSizeDefaultMigration(
    private val syncState: SettingsSyncRepository,
    private val hasStoredFontSize: suspend () -> Boolean,
) {
    private val mutex = Mutex()

    @Volatile
    private var done = false

    /** Runs the migration once per process; a failure is retried next time. */
    suspend fun ensure() {
        if (done) return
        mutex.withLock {
            if (done) return
            if (!hasStoredFontSize()) {
                syncState.adoptMovedDefault(
                    settingKey = SETTING_KEY,
                    legacy = ReaderPrefs.LEGACY_DEFAULT_FONT_SIZE.toString(),
                    current = ReaderPrefs.DEFAULT_FONT_SIZE.toString(),
                )
            }
            done = true
        }
    }

    companion object {
        const val SETTING_KEY = "reader.font_size"
    }
}
