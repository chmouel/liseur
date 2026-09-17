package com.chmouel.liseur.data.liseursync

import android.util.Log
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.settings.SettingsSyncRepository
import com.chmouel.liseur.data.settings.SyncableSetting
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Syncs the app's syncable settings with a liseur-sync server.
 *
 * Each setting is a key/value pair with a timestamp. The server stores
 * whatever keys it receives; the distinction between syncable and
 * device-local is made here, by only sending and accepting the keys
 * in [settings].
 *
 * Change detection compares current local values against the last-synced
 * snapshot rather than instrumenting every setter: if the current value
 * differs from what was synced, it was changed locally. *When* it
 * changed is a separate question the comparison cannot answer, and
 * `SettingsChangeTracker` answers it — a conflict has to be settled on
 * when the reader made the edit, not on when the device next found a
 * network.
 *
 * The baseline belongs to one account, so every read and write of it is
 * keyed by [accountKey]: the timestamps another server issued say
 * nothing about this one's, and comparing across the two stalls both.
 */
class LiseurSyncSettings(
    private val syncState: SettingsSyncRepository,
    private val settings: List<SyncableSetting>,
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Servers already found not to serve settings, so they are asked once. */
    private val unsupported = mutableSetOf<String>()

    /**
     * Pulls settings from the server, applies the ones that won, then
     * pushes the ones changed here.
     *
     * [canApplyReaderSettings] is asked again before every write rather
     * than once at the start, because a book can be opened while the
     * request is in the air — a resume-last-book start is exactly that.
     * A setting that re-lays out the page is then left for a later pass
     * rather than applied under the reader: a pulled font size or margin
     * reflows the page mid-sentence, which is the settings version of
     * turning someone's page for them. Nothing is recorded for a key
     * left that way, so the next pass still sees it as owed.
     *
     * [stillConnected] is asked before each network call and again
     * before anything is applied or recorded. The request is itself a
     * side effect, so a disconnect or an account switch partway through
     * must stop the run rather than be overwritten by what it had
     * already decided: the answer to a question asked of the account
     * just left may not be written to this device, and its baseline must
     * not be rebuilt after `forgetSyncPeer` has taken it away.
     *
     * Returns the number of settings exchanged, or -1 if the server does
     * not serve them.
     */
    suspend fun sync(
        accountKey: String,
        baseUrl: String,
        credentials: RemoteCredentials,
        canApplyReaderSettings: suspend () -> Boolean = { true },
        stillConnected: suspend () -> Boolean = { true },
    ): Int {
        if (baseUrl in unsupported) return -1
        if (!stillConnected()) return 0

        val serverMap: Map<String, ServerEntry>
        try {
            serverMap = pull(baseUrl, credentials)
        } catch (e: LiseurSyncRejection) {
            if (!isUnsupported(e)) throw e
            // Remembered rather than rediscovered: an old server would
            // otherwise be asked again on every sync, forever.
            unsupported += baseUrl
            Log.i(TAG, "Server does not serve settings; not asking again this session")
            return -1
        }

        if (!stillConnected()) return 0

        val lastSynced = syncState.allLastSynced(accountKey)
        val pushTime = now()
        // No change is dated later than now. A device whose clock was
        // wrong records one that is, and correcting the clock does not
        // correct what was already written down. Such a stamp would win
        // every disagreement with the account until the date arrived,
        // and the server refuses a whole batch carrying a time more than
        // a day ahead, so one of them would block every other setting on
        // every pass. Capping here rather than on the way out covers the
        // comparisons too: a time that has not happened yet is a wrong
        // answer whatever it is measured against, and now is the nearest
        // right one.
        val localChanges = syncState.localChanges().mapValues { minOf(it.value, pushTime) }
        val agreed = mutableMapOf<String, SettingsSyncRepository.SyncedEntry>()
        val toPush = JSONObject()
        var exchanged = 0

        for (entry in settings) {
            val server = serverMap[entry.key]
            val synced = lastSynced[entry.key]
            val localValue = entry.read()

            val serverIsNewer = server != null &&
                (synced == null || server.updatedAtMillis > synced.serverTimestamp)
            val localDiffers = synced == null || localValue != synced.value

            // What this device did to the key since it was agreed, if
            // the collector saw it. Zero is "nothing recorded", which is
            // not the same as "nothing happened" — see localStamp.
            val changedHere = localChanges[entry.key] ?: 0L

            // Both sides moved: whoever moved last wins. The local side
            // is dated by when the reader actually changed it, which is
            // the whole reason that is recorded separately.
            val takeServer = when {
                server == null -> false
                // The value is back where the account agreed it, which
                // usually means nothing happened here. It can also mean
                // the reader went away and came back, and that is still
                // a choice, made when they came back: a value the server
                // changed before that has been overruled since.
                !localDiffers -> serverIsNewer && server.updatedAtMillis > changedHere
                !serverIsNewer -> false
                else -> server.updatedAtMillis > localStamp(entry.key, synced, localChanges, pushTime)
            }

            if (takeServer) {
                if (apply(entry, server!!.value, canApplyReaderSettings, stillConnected, localValue)) {
                    agreed[entry.key] = SettingsSyncRepository.SyncedEntry(
                        server.value,
                        server.updatedAtMillis,
                    )
                    exchanged++
                }
                continue
            }

            // A key the server does not have is always offered, even one
            // agreed on before: the server having lost it means this
            // device is the only copy left.
            // A key the server does not hold what this device holds is
            // offered, including one whose value is back where it was
            // agreed: the server may have lost it, or another device may
            // have moved it since and been overruled here.
            if (server == null || localDiffers || server.value != localValue) {
                if (!sendable(entry.key, localValue)) continue
                // An unchanged value is re-offered as what it always
                // was. Dating it now would have a key the server merely
                // lost outrank a real edit made elsewhere since. Where
                // the reader did change it and put it back, that later
                // time is the one that speaks for it.
                val stamp = if (!localDiffers && synced != null) {
                    maxOf(synced.serverTimestamp, changedHere)
                } else {
                    localStamp(entry.key, synced, localChanges, pushTime)
                }
                toPush.put(
                    entry.key,
                    JSONObject()
                        .put("value", localValue)
                        // Capped again because the agreed time came off
                        // the server, which allows a day of slack of its
                        // own, and re-offering it may be the far side of
                        // that day.
                        .put("updated_at", millisToRfc3339(minOf(stamp, pushTime))),
                )
            }
        }

        if (toPush.length() > 0) {
            if (!stillConnected()) return exchanged
            exchanged += push(
                baseUrl,
                credentials,
                toPush,
                agreed,
                canApplyReaderSettings,
                stillConnected,
            )
        }
        if (!stillConnected()) return exchanged
        syncState.recordSynced(accountKey, agreed)
        // Asked once more, because the check above and these two writes
        // are not one thing: a disconnect landing between them would
        // have cleared the baseline just before this put it back.
        if (!stillConnected()) syncState.forgetPeer(accountKey)
        return exchanged
    }

    /**
     * When the reader last changed a setting that differs from what this
     * account agreed to.
     *
     * The stamp comes from a collector watching the settings flows, so
     * there is a moment after a setter commits in which the change is
     * real but undated, and a sync landing in it finds nothing. Reading
     * that as the epoch hands every such conflict to the server and
     * destroys an edit made seconds ago, so a divergence from an agreed
     * baseline with no stamp counts as one made now: it can only have
     * happened after the agreement, and the agreement is the only bound
     * there is.
     *
     * A key with no baseline at all is the opposite case. Nothing was
     * changed here; this is merely what the device holds, and a phone
     * signing in to an account for the first time is asking for that
     * account's settings, not offering it its own defaults.
     */
    private fun localStamp(
        key: String,
        synced: SettingsSyncRepository.SyncedEntry?,
        localChanges: Map<String, Long>,
        fallbackNow: Long,
    ): Long = localChanges[key] ?: if (synced == null) 0L else fallbackNow

    /**
     * Whether a value can go on the wire at all.
     *
     * The server writes a whole batch in one transaction, so a single
     * value it refuses takes every other setting down with it, on this
     * pass and on every pass after — and one of these keys is a free-text
     * URL. Dropping the offending key alone keeps the rest moving.
     */
    private fun sendable(key: String, value: String): Boolean {
        if (value.contains('\u0000')) {
            Log.w(TAG, "Not sending $key: the server cannot store this value")
            return false
        }
        val bytes = value.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_VALUE_BYTES) {
            Log.w(TAG, "Not sending $key: $bytes bytes is over the server's limit")
            return false
        }
        return true
    }

    /**
     * Sends the changed settings and records what the server ended up
     * holding.
     *
     * The upsert on the other end keeps whichever side is newer, so a
     * `200` does not mean the value was taken — a push can be refused
     * and look exactly like one that landed. What comes back is the
     * merged state, and that, not what was sent, is what both sides now
     * agree on. Recording the sent value against the server's timestamp
     * instead would invent a pair that exists nowhere, and since neither
     * side would then look newer than the other, the key would sit
     * diverged and silent for good.
     */
    private suspend fun push(
        baseUrl: String,
        credentials: RemoteCredentials,
        toPush: JSONObject,
        agreed: MutableMap<String, SettingsSyncRepository.SyncedEntry>,
        canApplyReaderSettings: suspend () -> Boolean,
        stillConnected: suspend () -> Boolean,
    ): Int {
        val response = http.put(
            LiseurSyncApi.meSettings(baseUrl),
            credentials,
            JSONObject().put("settings", toPush),
        )
        if (!stillConnected()) return 0
        val merged = response.optJSONObject("settings")
        var exchanged = 0
        for (entry in settings) {
            if (!toPush.has(entry.key)) continue
            val serverEntry = merged?.optJSONObject(entry.key)
            val serverValue = serverEntry?.takeIf { it.has("value") }?.optString("value")
            val serverTs = serverEntry?.optString("updated_at")?.let(::rfc3339ToMillis)
            if (serverValue == null || serverTs == null) {
                // The server said nothing about this key. Record nothing
                // either, so the next pass offers it again.
                continue
            }
            val sent = toPush.getJSONObject(entry.key).getString("value")
            if (serverValue != sent) {
                // The conflict is settled against the value that was
                // sent, never against a newer one the reader wrote while
                // the request was in the air. Overwriting that edit
                // would lose it and then file the server's answer as
                // agreed, which is the one state nothing later can
                // correct.
                Log.d(TAG, "Push of ${entry.key} lost; taking the server's value")
                if (!apply(entry, serverValue, canApplyReaderSettings, stillConnected, sent)) continue
            }
            agreed[entry.key] = SettingsSyncRepository.SyncedEntry(serverValue, serverTs)
            exchanged++
        }
        return exchanged
    }

    /**
     * Writes a value from the server, or declines to.
     *
     * Returns whether it was written, because the caller must not record
     * an agreement about a value it did not manage to store: a build
     * that does not recognise a newer build's font would otherwise note
     * the fallback as agreed and push it back over the real choice.
     */
    private suspend fun apply(
        entry: SyncableSetting,
        value: String,
        canApplyReaderSettings: suspend () -> Boolean,
        stillConnected: suspend () -> Boolean,
        decidedAgainst: String,
    ): Boolean {
        // Asked per setting, not once for the run. Writing a departed
        // account's value here cannot be taken back afterwards, so the
        // check has to sit next to the write rather than near it.
        if (!stillConnected()) return false
        if (entry.affectsOpenBook && !canApplyReaderSettings()) {
            Log.d(TAG, "Holding ${entry.key} back while a book is open")
            return false
        }
        // The decision to take the server's value was made against the
        // value this device held a moment ago. If the reader has changed
        // it since, that decision was about something else; leave it and
        // let the next pass weigh the new value on its own.
        if (entry.read() != decidedAgainst) {
            Log.d(TAG, "${entry.key} changed while the pull was in flight; leaving it")
            return false
        }
        if (!entry.write(value)) {
            Log.w(TAG, "Server value for ${entry.key} not understood; leaving it alone")
            return false
        }
        // Immediately, and not with the rest at the end of the pass.
        // Anything between the write and this is time in which the
        // reader can change the setting and the collector can stamp it,
        // and a later note would then put the server's value back as the
        // one observed and drop that stamp with it.
        syncState.markApplied(mapOf(entry.key to value))
        return true
    }

    /**
     * Whether a 404 means the server has no settings route.
     *
     * A route this server does not have is answered by the mux itself,
     * which does not produce the JSON body every deliberate refusal
     * here carries. So a 404 with no JSON to it is an older server, and
     * one carrying a refusal is this route saying no for a reason of its
     * own — which is a fault to report, not a feature to switch off.
     */
    private fun isUnsupported(e: LiseurSyncRejection): Boolean =
        e.code == LiseurSyncHttp.NOT_FOUND && e.body == null

    private suspend fun pull(
        baseUrl: String,
        credentials: RemoteCredentials,
    ): Map<String, ServerEntry> {
        val json = http.get(
            LiseurSyncApi.meSettings(baseUrl),
            credentials,
            expected = setOf(LiseurSyncHttp.NOT_FOUND),
        )
        val settingsObj = json.optJSONObject("settings") ?: return emptyMap()
        val out = mutableMapOf<String, ServerEntry>()
        for (key in settingsObj.keys()) {
            val entry = settingsObj.optJSONObject(key) ?: continue
            if (!entry.has("value")) continue
            val millis = rfc3339ToMillis(entry.optString("updated_at")) ?: continue
            out[key] = ServerEntry(entry.optString("value"), millis)
        }
        return out
    }

    private class ServerEntry(val value: String, val updatedAtMillis: Long)

    companion object {
        private const val TAG = "SettingsSync"

        /**
         * The server's per-value ceiling, mirrored so a value too big for
         * it is dropped here instead of failing the whole batch there.
         */
        private const val MAX_VALUE_BYTES = 4 * 1024

        private val formatter = DateTimeFormatter.ISO_INSTANT

        private fun millisToRfc3339(millis: Long): String =
            formatter.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))

        private fun rfc3339ToMillis(s: String): Long? = try {
            Instant.from(formatter.parse(s)).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
