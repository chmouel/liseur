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
 * Backs this device's settings up to a liseur-sync server, and restores
 * them from there.
 *
 * Settings stay on the device (ADR-0041). The server files what it
 * receives under the device the token belongs to and shows it to no
 * other device, so nothing here ever weighs one device's choice against
 * another's: this device is the only writer of its copy, and its own
 * values are always the ones that count.
 *
 * The one thing a pass has to tell apart is a key this device has never
 * stored on the account, which is when a value already on the server is
 * this device's own earlier copy (a reinstall, or cleared app data) and
 * is restored, from a key it has stored before, which is when any
 * difference is a change made here and is uploaded.
 *
 * Only the keys in [settings] are sent or accepted.
 */
class LiseurSyncSettings(
    private val syncState: SettingsSyncRepository,
    private val settings: List<SyncableSetting>,
    private val http: LiseurSyncHttp = LiseurSyncHttp(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Servers found not to keep settings per device, so they are asked once. */
    private val unsupported = mutableSetOf<String>()

    /**
     * Restores what the server holds for keys this device has never
     * stored there, then uploads every setting that differs from what
     * the server holds.
     *
     * [canApplyReaderSettings] is asked again before every write rather
     * than once at the start, because a book can be opened while the
     * request is in the air. A restored setting that re-lays out the
     * page is then left for a later pass rather than applied under the
     * reader. Nothing is recorded for a key left that way, so the next
     * pass still restores it.
     *
     * [stillConnected] is asked before each network call and again
     * before anything is applied or recorded, so a disconnect or an
     * account switch partway through stops the run: a value from the
     * account just left may not be written to this device, and its
     * record must not be rebuilt after `forgetSyncPeer` has taken it
     * away.
     *
     * Returns the number of settings exchanged, or -1 if the server does
     * not keep settings per device.
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

        val serverMap: Map<String, ServerEntry>?
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
        if (serverMap == null) {
            // An older server keeps one copy per account and hands it to
            // every device, which is the sharing this client no longer
            // takes part in.
            unsupported += baseUrl
            Log.i(TAG, "Server shares settings across devices; not syncing them")
            return -1
        }

        if (!stillConnected()) return 0

        val stored = syncState.allStored(accountKey)
        val recorded = mutableMapOf<String, String>()
        val toPush = JSONObject()
        var exchanged = 0

        for (entry in settings) {
            val server = serverMap[entry.key]
            val localValue = entry.read()

            if (server != null && server.value == localValue) {
                if (stored[entry.key] != localValue) recorded[entry.key] = localValue
                continue
            }

            if (server != null && entry.key !in stored) {
                when (apply(entry, server.value, canApplyReaderSettings, stillConnected, localValue)) {
                    Applied.YES -> {
                        recorded[entry.key] = server.value
                        exchanged++
                        continue
                    }
                    Applied.LATER -> continue
                    // A value this build does not understand, written by
                    // a newer one: what this device holds now replaces it.
                    Applied.NEVER -> Unit
                }
            }

            if (!sendable(entry.key, localValue)) continue
            // Dated after the copy it replaces, whatever this device's
            // clock says. The server keeps the newer of two writes, and a
            // clock set back since the last upload would otherwise have
            // this device's own change refused in favour of its own past.
            val stamp = maxOf(now(), (server?.updatedAtMillis ?: 0L) + 1)
            toPush.put(
                entry.key,
                JSONObject()
                    .put("value", localValue)
                    .put("updated_at", millisToRfc3339(stamp)),
            )
        }

        if (toPush.length() > 0) {
            if (!stillConnected()) return exchanged
            exchanged += push(baseUrl, credentials, toPush, recorded)
        }
        if (!stillConnected()) return exchanged
        syncState.recordStored(accountKey, recorded)
        // Asked once more, because the check above and this write are
        // not one thing: a disconnect landing between them would have
        // cleared the record just before this put it back.
        if (!stillConnected()) syncState.forgetPeer(accountKey)
        return exchanged
    }

    /**
     * Whether a value can go on the wire at all.
     *
     * The server writes a whole batch in one transaction, so a single
     * value it refuses takes every other setting down with it, on this
     * pass and on every pass after, and one of these keys is a free-text
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
     * Uploads the changed settings and records the ones the server now
     * holds.
     *
     * The server answers with what it ended up keeping, and only a key
     * it kept as sent is recorded. Anything else is left unrecorded, so
     * the next pass offers it again.
     */
    private suspend fun push(
        baseUrl: String,
        credentials: RemoteCredentials,
        toPush: JSONObject,
        recorded: MutableMap<String, String>,
    ): Int {
        val response = http.put(
            LiseurSyncApi.meSettings(baseUrl),
            credentials,
            JSONObject().put("settings", toPush),
        )
        val merged = response.optJSONObject("settings")
        var exchanged = 0
        for (key in toPush.keys()) {
            val sent = toPush.getJSONObject(key).getString("value")
            val serverEntry = merged?.optJSONObject(key)
            val kept = serverEntry?.takeIf { it.has("value") }?.optString("value")
            if (kept != sent) {
                Log.d(TAG, "Server did not keep $key as sent; offering it again next pass")
                continue
            }
            recorded[key] = sent
            exchanged++
        }
        return exchanged
    }

    private enum class Applied { YES, LATER, NEVER }

    /**
     * Writes a restored value, or declines to.
     *
     * [LATER][Applied.LATER] means the moment was wrong (a book is open,
     * the account is gone, or the reader changed the setting while the
     * request was in the air) and the next pass should try again.
     * [NEVER][Applied.NEVER] means this build does not understand the
     * value, so trying again would not help.
     */
    private suspend fun apply(
        entry: SyncableSetting,
        value: String,
        canApplyReaderSettings: suspend () -> Boolean,
        stillConnected: suspend () -> Boolean,
        decidedAgainst: String,
    ): Applied {
        // Asked per setting, not once for the run. Writing a departed
        // account's value here cannot be taken back afterwards, so the
        // check has to sit next to the write rather than near it.
        if (!stillConnected()) return Applied.LATER
        if (entry.affectsOpenBook && !canApplyReaderSettings()) {
            Log.d(TAG, "Holding ${entry.key} back while a book is open")
            return Applied.LATER
        }
        // The decision to restore was made against the value this device
        // held a moment ago. If the reader has changed it since, their
        // change is the newer choice and the next pass uploads it.
        if (entry.read() != decidedAgainst) {
            Log.d(TAG, "${entry.key} changed while the pull was in flight; leaving it")
            return Applied.LATER
        }
        if (!entry.write(value)) {
            Log.w(TAG, "Stored value for ${entry.key} not understood; keeping this device's")
            return Applied.NEVER
        }
        return Applied.YES
    }

    /**
     * Whether a 404 means the server has no settings route.
     *
     * A route this server does not have is answered by the mux itself,
     * which does not produce the JSON body every deliberate refusal
     * here carries. So a 404 with no JSON to it is an older server, and
     * one carrying a refusal is this route saying no for a reason of its
     * own, which is a fault to report, not a feature to switch off.
     */
    private fun isUnsupported(e: LiseurSyncRejection): Boolean =
        e.code == LiseurSyncHttp.NOT_FOUND && e.body == null

    /**
     * What the server holds for this device, or null when the server
     * does not say its settings are per device.
     */
    private suspend fun pull(
        baseUrl: String,
        credentials: RemoteCredentials,
    ): Map<String, ServerEntry>? {
        val json = http.get(
            LiseurSyncApi.meSettings(baseUrl),
            credentials,
            expected = setOf(LiseurSyncHttp.NOT_FOUND),
        )
        if (json.optString("scope") != DEVICE_SCOPE) return null
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

        /** The `scope` a server that keeps settings per device answers with. */
        private const val DEVICE_SCOPE = "device"

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
