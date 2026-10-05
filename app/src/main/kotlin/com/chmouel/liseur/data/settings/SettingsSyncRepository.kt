package com.chmouel.liseur.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.settingsSyncStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings_sync",
)

/**
 * What this device last stored on each liseur-sync account it backs its
 * settings up to.
 *
 * Settings stay on the device (ADR-0041): the server keeps a copy keyed
 * by this device and shows it to nobody else, so the only writer of
 * those rows is this device. That leaves one fact worth remembering per
 * account and key: the value this device last saw stored there. A key
 * with no record is one this device has never stored on that account,
 * which is what lets a sync pass tell its own earlier copy (to restore)
 * from a setting changed here (to upload).
 *
 * The record is keyed by account because it describes that account's
 * copy, so it moves or goes with the account. The server keys its copy
 * by device, so the record also notes which server device it was made
 * as, and is dropped when the account comes back as another one.
 */
class SettingsSyncRepository(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.settingsSyncStore)

    @Volatile
    private var formatChecked = false

    private fun valuePrefix(accountKey: String) = "v:$accountKey:"

    private fun valueKey(accountKey: String, settingKey: String) =
        stringPreferencesKey(valuePrefix(accountKey) + settingKey)

    private fun deviceKey(accountKey: String) = stringPreferencesKey("d:$accountKey")

    /**
     * Ties the record for [accountKey] to the server's [deviceId] for this
     * device. A pasted token can keep the account and still sign in as a
     * different device, whose copy this record says nothing about: when
     * the id changes, the record is dropped so the next pass restores
     * that device's copy instead of uploading over it. A null id, from a
     * server that never named the device, leaves the record alone.
     */
    suspend fun claimDevice(accountKey: String, deviceId: String?) {
        if (deviceId == null) return
        ensureFormat()
        val marker = deviceKey(accountKey)
        val prefix = valuePrefix(accountKey)
        store.edit { prefs ->
            if (prefs[marker] == deviceId) return@edit
            for (key in prefs.asMap().keys.toList()) {
                if (key.name.startsWith(prefix)) prefs.remove(key)
            }
            prefs[marker] = deviceId
        }
    }

    /** What this device last stored on [accountKey], keyed by setting name. */
    suspend fun allStored(accountKey: String): Map<String, String> {
        ensureFormat()
        val prefs = store.data.first()
        val prefix = valuePrefix(accountKey)
        val out = mutableMapOf<String, String>()
        for ((key, value) in prefs.asMap()) {
            if (!key.name.startsWith(prefix)) continue
            out[key.name.removePrefix(prefix)] = value as? String ?: continue
        }
        return out
    }

    /**
     * Records what [accountKey] now holds for several settings at once,
     * in one commit, so a process dying halfway cannot leave half a
     * record that reads as if the rest was never stored.
     */
    suspend fun recordStored(accountKey: String, entries: Map<String, String>) {
        if (entries.isEmpty()) return
        ensureFormat()
        store.edit { prefs ->
            for ((settingKey, value) in entries) {
                prefs[valueKey(accountKey, settingKey)] = value
            }
        }
    }

    /** Moves the record to a new spelling of the same account. */
    suspend fun rekeyPeer(from: String, to: String) {
        if (from == to) return
        ensureFormat()
        // Copied rather than moved. This commits on its own, while the
        // caller's Room transaction may still roll back, and a record
        // filed under a name nothing answers to any more is lost. What is
        // left behind is a few dead entries under a spelling this device
        // has stopped using, which costs nothing.
        store.edit { prefs ->
            val fromValues = valuePrefix(from)
            for ((key, value) in prefs.asMap().toMap()) {
                if (!key.name.startsWith(fromValues)) continue
                val settingKey = key.name.removePrefix(fromValues)
                prefs[valueKey(to, settingKey)] = value as? String ?: continue
            }
            prefs[deviceKey(from)]?.let { prefs[deviceKey(to)] = it }
        }
    }

    /** Drops the record for an account being left. */
    suspend fun forgetPeer(accountKey: String) {
        ensureFormat()
        val prefix = valuePrefix(accountKey)
        store.edit { prefs ->
            for (key in prefs.asMap().keys.toList()) {
                if (key.name.startsWith(prefix)) prefs.remove(key)
            }
            prefs.remove(deviceKey(accountKey))
        }
    }

    /**
     * Clears what the earlier, cross-device version of this store kept:
     * change stamps, observed values and agreements dated by servers
     * that have since dropped their account-wide copies. Starting empty
     * makes the next pass upload this device's settings again.
     */
    private suspend fun ensureFormat() {
        if (formatChecked) return
        store.edit { prefs ->
            if (prefs[FORMAT] != CURRENT_FORMAT) {
                prefs.clear()
                prefs[FORMAT] = CURRENT_FORMAT
            }
        }
        formatChecked = true
    }

    private companion object {
        val FORMAT = intPreferencesKey("format")
        const val CURRENT_FORMAT = 2
    }
}
