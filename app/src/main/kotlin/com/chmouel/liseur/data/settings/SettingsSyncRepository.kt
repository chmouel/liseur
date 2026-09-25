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

    private fun movedDefaultPrefix(accountKey: String) = "md:$accountKey:"

    private fun valueKey(accountKey: String, settingKey: String) =
        stringPreferencesKey(valuePrefix(accountKey) + settingKey)

    private fun stampKey(accountKey: String, settingKey: String) =
        longPreferencesKey(stampPrefix(accountKey) + settingKey)

    private fun movedDefaultKey(accountKey: String, settingKey: String) =
        longPreferencesKey(movedDefaultPrefix(accountKey) + settingKey)

    private fun observedKey(settingKey: String) = stringPreferencesKey("ob:$settingKey")

    private fun appliedKey(settingKey: String) = stringPreferencesKey("ap:$settingKey")

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
                // A new agreement supersedes the old default it was
                // dated against.
                prefs.remove(movedDefaultKey(accountKey, settingKey))
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
        // Copied rather than moved. This commits on its own, while the
        // caller's Room transaction may still roll back, and a baseline
        // filed under a name nothing answers to any more is a baseline
        // lost: the next connection would read as a first one and take
        // the account's settings over an edit made here offline. What is
        // left behind is a few dead entries under a spelling this device
        // has stopped using, which costs nothing and is written over if
        // the migration runs again.
        store.edit { prefs ->
            val fromValues = valuePrefix(from)
            val fromStamps = stampPrefix(from)
            val fromMoved = movedDefaultPrefix(from)
            for ((key, value) in prefs.asMap().toMap()) {
                when {
                    key.name.startsWith(fromMoved) -> {
                        val settingKey = key.name.removePrefix(fromMoved)
                        prefs[movedDefaultKey(to, settingKey)] = value as Long
                    }

                    key.name.startsWith(fromValues) -> {
                        val settingKey = key.name.removePrefix(fromValues)
                        prefs[valueKey(to, settingKey)] = value as String
                    }

                    key.name.startsWith(fromStamps) -> {
                        val settingKey = key.name.removePrefix(fromStamps)
                        prefs[stampKey(to, settingKey)] = value as Long
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
        val prefixes = listOf(
            valuePrefix(accountKey),
            stampPrefix(accountKey),
            movedDefaultPrefix(accountKey),
        )
        store.edit { prefs ->
            for (key in prefs.asMap().keys.toList()) {
                if (prefixes.any { key.name.startsWith(it) }) prefs.remove(key)
            }
        }
    }

    /**
     * When this device last changed each setting, for the keys it has seen
     * change.
     *
     * Given [accountKey], a default that moved since that account agreed
     * to the old one counts as a change dated by [adoptMovedDefault]. That
     * date is only meaningful against the account it was taken from, so
     * it is never offered to another.
     */
    suspend fun localChanges(accountKey: String? = null): Map<String, Long> {
        val prefs = store.data.first()
        val out = mutableMapOf<String, Long>()
        for (key in prefs.asMap().keys) {
            if (!key.name.startsWith("ch:")) continue
            val settingKey = key.name.removePrefix("ch:")
            out[settingKey] = prefs[longPreferencesKey(key.name)] ?: continue
        }
        if (accountKey != null) {
            val moved = movedDefaultPrefix(accountKey)
            for (key in prefs.asMap().keys) {
                if (!key.name.startsWith(moved)) continue
                val settingKey = key.name.removePrefix(moved)
                val at = prefs[longPreferencesKey(key.name)] ?: continue
                out[settingKey] = maxOf(out[settingKey] ?: 0L, at)
            }
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
     *
     * A value the sync pass wrote is not stamped either, for the same
     * reason and with more at stake. It reaches this collector looking
     * like any other write, and dating it now would say the reader chose
     * it, moments ago. Within one account that is harmless, since the
     * value matches what was agreed and so is never offered — but the
     * change record deliberately outlives an account switch, and on the
     * next account nothing is agreed yet, so that invented edit is fresh
     * enough to beat whatever that account holds. One server's settings
     * would quietly move into another's. [markApplied] records the value
     * that was handed to this device, and it is skipped here.
     */
    suspend fun observeLocal(values: Map<String, String>, now: Long) {
        store.edit { prefs ->
            for ((settingKey, value) in values) {
                val seen = prefs[observedKey(settingKey)]
                if (seen == value) continue
                prefs[observedKey(settingKey)] = value
                // The marker answers for one change and is then spent.
                // Leaving it would make a later, deliberate return to the
                // same value look like the server's doing all over again,
                // and the key would keep the time of the edit before it.
                val applied = prefs[appliedKey(settingKey)]
                prefs.remove(appliedKey(settingKey))
                if (seen == null) continue
                if (applied == value) continue
                prefs[changedKey(settingKey)] = now
            }
        }
    }

    /**
     * Notes that these values arrived from a server rather than from the
     * reader, and so are not changes this device has to offer anyone.
     *
     * Written from two sides because the collector runs on its own and
     * the two can land in either order: the value is remembered so a
     * collection arriving afterwards knows not to stamp it, and any
     * stamp a collection already left is removed. Whichever goes first,
     * the key ends up unstamped.
     */
    suspend fun markApplied(values: Map<String, String>) {
        if (values.isEmpty()) return
        store.edit { prefs ->
            for ((settingKey, value) in values) {
                prefs[appliedKey(settingKey)] = value
                prefs[observedKey(settingKey)] = value
                prefs.remove(changedKey(settingKey))
            }
        }
    }

    /**
     * Brings the bookkeeping along when a new version moves a default the
     * reader never chose, from [legacy] to [current].
     *
     * Left alone, the collector would see the value move and stamp it
     * now, as though the reader had just picked it — and that invented
     * edit would then beat a real choice made on another device, both
     * on an account agreed with and on one connected later. So the
     * observed value is moved with the default, and no stamp follows.
     *
     * For each account that agreed to [legacy], the new default is dated
     * just after that account's agreement, and kept with that account's
     * baseline. An account still holding the old default then takes the
     * new one, and one that has since moved to a real choice keeps it,
     * because that choice is later. Dating it now would win against the
     * real choice, and dating it at the agreement itself would lose to
     * the old default, which the server refuses to replace with a write
     * of the same time. One date shared by every account would compare
     * one server's history against another's.
     *
     * Acts only while the observed value is still [legacy], so it runs
     * once. The caller checks that the reader has no value of their own.
     */
    suspend fun adoptMovedDefault(settingKey: String, legacy: String, current: String) {
        store.edit { prefs ->
            if (prefs[observedKey(settingKey)] != legacy) return@edit
            prefs[observedKey(settingKey)] = current
            val suffix = ":$settingKey"
            for (key in prefs.asMap().keys.toList()) {
                if (!key.name.startsWith("v:") || !key.name.endsWith(suffix)) continue
                if (prefs[stringPreferencesKey(key.name)] != legacy) continue
                val accountKey = key.name.removePrefix("v:").removeSuffix(suffix)
                val agreedAt = prefs[stampKey(accountKey, settingKey)] ?: continue
                prefs[movedDefaultKey(accountKey, settingKey)] = agreedAt + 1
            }
        }
    }

    data class SyncedEntry(val value: String, val serverTimestamp: Long)
}
