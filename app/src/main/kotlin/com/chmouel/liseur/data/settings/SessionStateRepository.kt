package com.chmouel.liseur.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sessionStore: DataStore<Preferences> by preferencesDataStore(
    name = "session_state",
)

/**
 * Remembers where the reader was when the app was last put down, so the
 * next launch can pick up there instead of always starting at the library.
 */
class SessionStateRepository(private val context: Context) {

    private object Keys {
        val LAST_SCREEN_WAS_READER = booleanPreferencesKey("last_screen_was_reader")
    }

    suspend fun leftFromReader(): Boolean =
        context.sessionStore.data.map { it[Keys.LAST_SCREEN_WAS_READER] ?: false }.first()

    suspend fun setLeftFromReader(value: Boolean) {
        context.sessionStore.edit { it[Keys.LAST_SCREEN_WAS_READER] = value }
    }
}
