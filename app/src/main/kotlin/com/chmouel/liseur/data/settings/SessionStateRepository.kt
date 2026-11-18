package com.chmouel.liseur.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore

private val Context.sessionStore: DataStore<Preferences> by preferencesDataStore(
    name = "session_state",
)

/**
 * Remembers where the reader was when the app was last put down, so the
 * next launch can pick up there instead of always starting at the library.
 */
class SessionStateRepository internal constructor(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.sessionStore)

    private object Keys {
        val LAST_SCREEN_WAS_READER = booleanPreferencesKey("last_screen_was_reader")
    }

    suspend fun enterLibrary(): Boolean {
        var leftFromReader = false
        store.edit {
            leftFromReader = it[Keys.LAST_SCREEN_WAS_READER] ?: false
            it[Keys.LAST_SCREEN_WAS_READER] = false
        }
        return leftFromReader
    }

    suspend fun setLeftFromReader(value: Boolean) {
        store.edit { it[Keys.LAST_SCREEN_WAS_READER] = value }
    }
}
