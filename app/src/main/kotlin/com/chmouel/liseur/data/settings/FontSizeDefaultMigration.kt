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
 *
 * A stored old default that a server wrote is nobody's choice either:
 * another device offered its own default, and this one took it. It is
 * handed back to the default so the reader moves with everyone else.
 * No slider or pinch position lands on exactly the old default, so a
 * reader cannot have picked it on purpose.
 */
class FontSizeDefaultMigration(
    private val syncState: SettingsSyncRepository,
    private val storedFontSize: suspend () -> Double?,
    private val clearFontSize: suspend () -> Unit,
) {
    private val mutex = Mutex()

    @Volatile
    private var done = false

    /** Runs the migration once per process; a failure is retried next time. */
    suspend fun ensure() {
        if (done) return
        mutex.withLock {
            if (done) return
            migrate()
            done = true
        }
    }

    private suspend fun migrate() {
        val legacy = ReaderPrefs.LEGACY_DEFAULT_FONT_SIZE.toString()
        when (storedFontSize()) {
            null -> Unit
            // Cleared before the bookkeeping moves, so a process that dies
            // between the two finds no stored size and finishes the job.
            ReaderPrefs.LEGACY_DEFAULT_FONT_SIZE ->
                if (syncState.cameFromServer(SETTING_KEY, legacy)) clearFontSize() else return

            else -> return
        }
        syncState.adoptMovedDefault(
            settingKey = SETTING_KEY,
            legacy = legacy,
            current = ReaderPrefs.DEFAULT_FONT_SIZE.toString(),
        )
    }

    companion object {
        const val SETTING_KEY = "reader.font_size"
    }
}
