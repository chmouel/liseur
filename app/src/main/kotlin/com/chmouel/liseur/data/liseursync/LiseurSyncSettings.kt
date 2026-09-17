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
     * [canApplyReaderSettings] is false while a book is on screen. The
     * `reader.*` keys are then left for a later pass rather than applied
     * under the reader: a pulled font size or margin reflows the page
     * mid-sentence, which is the settings version of turning someone's
     * page for them. Nothing is recorded for a key left that way, so the
     * next pass still sees it as owed.
     *
     * Returns the number of settings exchanged, or -1 if the server does
     * not serve them.
     */
    suspend fun sync(
        accountKey: String,
        baseUrl: String,
        credentials: RemoteCredentials,
        canApplyReaderSettings: Boolean = true,
    ): Int {
        if (baseUrl in unsupported) return -1

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

        val lastSynced = syncState.allLastSynced(accountKey)
        val localChanges = syncState.localChanges()
        val pushTime = now()
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

            // Both sides moved: whoever moved last wins. The local side
            // is dated by when the reader actually changed it, which is
            // the whole reason that is recorded separately.
            val takeServer = when {
                server == null -> false
                !localDiffers -> serverIsNewer
                !serverIsNewer -> false
                else -> server.updatedAtMillis > (localChanges[entry.key] ?: 0L)
            }

            if (takeServer) {
                if (apply(entry, server!!.value, canApplyReaderSettings)) {
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
            if (server == null || localDiffers) {
                val stamp = localChanges[entry.key] ?: pushTime
                toPush.put(
                    entry.key,
                    JSONObject()
                        .put("value", localValue)
                        .put("updated_at", millisToRfc3339(stamp)),
                )
            }
        }

        if (toPush.length() > 0) {
            exchanged += push(baseUrl, credentials, toPush, agreed, canApplyReaderSettings)
        }
        syncState.recordSynced(accountKey, agreed)
        return exchanged
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
        canApplyReaderSettings: Boolean,
    ): Int {
        val response = http.put(
            LiseurSyncApi.meSettings(baseUrl),
            credentials,
            JSONObject().put("settings", toPush),
        )
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
            if (serverValue != toPush.getJSONObject(entry.key).getString("value")) {
                Log.d(TAG, "Push of ${entry.key} lost; taking the server's value")
                if (!apply(entry, serverValue, canApplyReaderSettings)) continue
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
        canApplyReaderSettings: Boolean,
    ): Boolean {
        if (!canApplyReaderSettings && entry.key.startsWith(READER_PREFIX)) {
            Log.d(TAG, "Holding ${entry.key} back while a book is open")
            return false
        }
        if (!entry.write(value)) {
            Log.w(TAG, "Server value for ${entry.key} not understood; leaving it alone")
            return false
        }
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

        /** The keys that change how an open book looks on screen. */
        private const val READER_PREFIX = "reader."

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
