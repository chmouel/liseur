package com.chmouel.liseur.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.settingsSyncStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings_sync",
)

/**
 * The bookkeeping behind settings sync: what each account agreed to, and
 * when this device last changed a setting.
 *
 * The two are kept apart on purpose, because they answer to different
 * owners. The agreed baseline (`v:` and `ts:`) is *peer state* — a value
 * and a timestamp that mean something only to the account they came
 * from — so it is keyed by account and moves or goes with that account,
 * like every other peer-keyed table. The local change record (`ob:` and
 * `ch:`) is a fact about this device's own settings, true no matter who
 * is signed in, so it is not keyed by account and must survive a switch:
 * signing into a different server does not un-change a font.
 */
class SettingsSyncRepository(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.settingsSyncStore)

    private fun valuePrefix(accountKey: String) = "v:$accountKey:"

    private fun stampPrefix(accountKey: String) = "ts:$accountKey:"

    private fun valueKey(accountKey: String, settingKey: String) =
        stringPreferencesKey(valuePrefix(accountKey) + settingKey)

    private fun stampKey(accountKey: String, settingKey: String) =
        longPreferencesKey(stampPrefix(accountKey) + settingKey)

    private fun observedKey(settingKey: String) = stringPreferencesKey("ob:$settingKey")

    private fun changedKey(settingKey: String) = longPreferencesKey("ch:$settingKey")

    /** Everything [accountKey] agreed to, keyed by setting name. */
    suspend fun allLastSynced(accountKey: String): Map<String, SyncedEntry> {
        val prefs = store.data.first()
        val prefix = valuePrefix(accountKey)
        val out = mutableMapOf<String, SyncedEntry>()
        for (key in prefs.asMap().keys) {
            if (!key.name.startsWith(prefix)) continue
            val settingKey = key.name.removePrefix(prefix)
            val value = prefs[stringPreferencesKey(key.name)] ?: continue
            val ts = prefs[stampKey(accountKey, settingKey)] ?: continue
            out[settingKey] = SyncedEntry(value, ts)
        }
        return out
    }

    /**
     * Records what [accountKey] and this device agree on, for several
     * settings at once.
     *
     * One commit rather than one per setting: a first connect agrees
     * about twenty-odd keys together, and writing them one at a time is
     * that many file writes, any of which can be the last one before the
     * process dies — leaving a half-made agreement that reads as if the
     * rest was never sent.
     */
    suspend fun recordSynced(accountKey: String, entries: Map<String, SyncedEntry>) {
        if (entries.isEmpty()) return
        store.edit { prefs ->
            for ((settingKey, entry) in entries) {
                prefs[valueKey(accountKey, settingKey)] = entry.value
                prefs[stampKey(accountKey, settingKey)] = entry.serverTimestamp
            }
        }
    }

    /** How many settings [accountKey] has a baseline for. */
    suspend fun countForPeer(accountKey: String): Int {
        val prefix = valuePrefix(accountKey)
        return store.data.first().asMap().keys.count { it.name.startsWith(prefix) }
    }

    /** Moves the baseline to a new spelling of the same account. */
    suspend fun rekeyPeer(from: String, to: String) {
        if (from == to) return
        store.edit { prefs ->
            val fromValues = valuePrefix(from)
            val fromStamps = stampPrefix(from)
            for ((key, value) in prefs.asMap().toMap()) {
                when {
                    key.name.startsWith(fromValues) -> {
                        val settingKey = key.name.removePrefix(fromValues)
                        prefs[valueKey(to, settingKey)] = value as String
                        prefs.remove(key)
                    }

                    key.name.startsWith(fromStamps) -> {
                        val settingKey = key.name.removePrefix(fromStamps)
                        prefs[stampKey(to, settingKey)] = value as Long
                        prefs.remove(key)
                    }
                }
            }
        }
    }

    /**
     * Drops the baseline for an account being left.
     *
     * The local change record stays: it says what this device did, which
     * the next account still needs to know so the settings already here
     * are offered to it rather than silently kept back.
     */
    suspend fun forgetPeer(accountKey: String) {
        val values = valuePrefix(accountKey)
        val stamps = stampPrefix(accountKey)
        store.edit { prefs ->
            for (key in prefs.asMap().keys.toList()) {
                if (key.name.startsWith(values) || key.name.startsWith(stamps)) prefs.remove(key)
            }
        }
    }

    /** When this device last changed each setting, for the keys it has seen change. */
    suspend fun localChanges(): Map<String, Long> {
        val prefs = store.data.first()
        val out = mutableMapOf<String, Long>()
        for (key in prefs.asMap().keys) {
            if (!key.name.startsWith("ch:")) continue
            val settingKey = key.name.removePrefix("ch:")
            out[settingKey] = prefs[longPreferencesKey(key.name)] ?: continue
        }
        return out
    }

    /**
     * Takes note of the settings as they stand, stamping the ones that
     * moved since the last look.
     *
     * The first sight of a setting seeds the baseline without stamping
     * it: an install, or a key added by a new version, has not been
     * *changed* by the reader, and stamping it would claim an edit that
     * never happened and push it over another device's real one.
     */
    suspend fun observeLocal(values: Map<String, String>, now: Long) {
        store.edit { prefs ->
            for ((settingKey, value) in values) {
                val seen = prefs[observedKey(settingKey)]
                if (seen == value) continue
                prefs[observedKey(settingKey)] = value
                if (seen != null) prefs[changedKey(settingKey)] = now
            }
        }
    }

    data class SyncedEntry(val value: String, val serverTimestamp: Long)
}
